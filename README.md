# 无人货柜补货盘点临期下架服务

面向城市社区、办公楼、医院无人货柜场景的后端服务，覆盖补货员补货上架、柜机多源识别下单、临期/过期下架、报损、冷藏温控、盘点差异调整、用户投诉退款与供应商批次结算的全链路。

技术栈：**Java 17 + Spring Boot 3.3 + Spring Security + Spring Kafka + PostgreSQL + H2(测试)**。

## 一、能力总览

| 业务问题 | 实现方式 |
| --- | --- |
| 补货记录 | 柜机编号、商品批次、保质期（生产/到期日）、数量、货道位置、温度要求、补货照片全部落表（`restock_order`/`restock_item`） |
| 重量/视觉/RFID 识别下单 | 设备事件投递 Kafka `cabinet.device.event`，服务侧按会话聚合多源事件，关门融合后 FEFO 扣批次库存、生成订单与批次台账 |
| 识别错误 | 多源数量不一致/低置信度/称重交叉校验不符 → 订单挂起 `PENDING_REVIEW`，**不扣款不扣库存**，人工复核确认/否定 |
| 拿起又放回 | 每个识别源独立计算“拿起-放回”净值，净值为 0 不出单、不扣款 |
| 货道卡货 | 设备 `CHANNEL_JAM` 事件生成告警；卡货损坏可走手工下架-报废 |
| 冷藏温度异常 | 温度越限即锁定柜机（拒绝开门），持续越限 30 分钟隔离柜内全部批次，食品安全处置（下架/报损） |
| 临期自动下架 | 批次进入临期范围即在**同一事务内按批次停售**（只停该批，不停整条货道，同货道其他批次照常售卖），同时给补货员生成带时限的下架任务；柜机离线期间停售指令在上线时通过同步接口补齐 |
| 补货员扫码清点 | 补货员到场扫任务码领取任务，清点实际数量，逐行登记**报损/回收/退供应商/仍可销售**四种去向；去向合计必须等于实点数，仍可售部分复检后自动恢复售卖 |
| 停售与扣款并发对账 | 库存行悲观锁串行化售卖与下架；开门在途期间批次被停售时关门可从停售量结算并记**临期售出风险提醒**，补货清点自动核销账实差异，库存、订单、下架结果对得上 |
| 下架任务逾期 | 任务超过时限（默认 24h）未处理进入运营逾期列表，**限制该批次再次曝光**，需运营核实后解封 |
| 按批次售后 | 客服按批次查到所有购买订单（含停售后成交等风险标记），处理退款、风险提醒；可判定**供应商责任**，未收回商品的报损计入供应商结算扣减 |
| 批次去向追溯 | 批次档案 `/api/analytics/batches/{batchNo}/record` 聚合停售记录、台账流水、报损、购买订单与每次下架的四种去向数量，可还原“为什么下架、最终去了哪里” |
| 放错货道 | 上架时校验货道计划商品，不一致记 `WRONG_CHANNEL` 告警，补货单完成态为 `COMPLETED_MISMATCH` |
| 盘点库存不符 | 盘点单生成系统快照 → 录入实盘 → 差异审批调整：盘亏记报损（SHRINKAGE），台账 ADJUST_MINUS/PLUS |
| 用户误扣款 | 投诉单关联开门会话；客服证据回放后按整单/按行退款；商品收回回补库存（REFUND_RETURN），未收回记报损（REFUND_NO_RETURN） |
| 按点位调品类 | `PUT /api/channels/{id}/planned-product` 调整货道计划商品 |
| 批次全链路追溯 | `batch_ledger` 逐笔记录补货入/售卖出/下架出/报损出/盘盈盘亏/退款回库/结算；`/api/analytics/batches/{batchNo}/trace` |
| 售后证据回放 | 开门记录、多源识别结果（含视频片段 URL 与起止秒）、订单、批次库存变化、温控曲线一次返回 |
| 供应商结算 | 按周期汇总真实售出（扣除退款）与供应商责任报损，逐批次明细，应付 = 售卖成本 − 责任报损 |

### 关键状态机

- **开门会话**：`OPEN → SETTLED`（已扣款）/ `REVIEW_PENDING → SETTLED|CANCELLED`（人工复核）/ `CANCELLED`（无净拿取、温控拒开门）/ `SETTLED_REFUNDED`
- **商品批次**：`IN_STOCK → SOLD_OUT | REMOVED → SETTLED`；批次另有 `saleHalted`（停售）/`exposureBlocked`（逾期曝光限制）标记
- **下架任务**：`PENDING（已停售待领取）→ ACCEPTED（补货员扫码领取、清点中）→ EXECUTED` / `CANCELLED`；超时未处理被标记 OVERDUE
- **下架去向（每种均可按数量拆分）**：`SCRAP` 报损报废（产生报损，临期/过期默认供应商责任）、`RECYCLE` 回收、`RETURN_SUPPLIER` 退供应商（不产生报损）、`SALEABLE` 复检仍可售（解除该部分停售、恢复曝光）
- **库存口径**：可售 = 在库 − 温控隔离量 − 批次停售量；FEFO 只扣未停售、未限制曝光的批次
- **结算单**：`DRAFT → CONFIRMED → PAID`，同供应商同周期不可重复生成

## 二、一键启动（Docker Compose）

```bash
docker compose up --build
```

启动三个容器：PostgreSQL 16（5432）、Kafka 3.9 KRaft 单节点（9092）、本应用（8080）。
应用启动时自动建表（`ddl-auto: update`）并写入种子数据。

- Swagger UI：<http://localhost:8080/swagger-ui.html>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>

## 三、本地开发启动（无 Docker）

前置：JDK 17、Maven 3.8+、PostgreSQL、Kafka（KRaft 即可）。

```bash
# 1. 准备数据库（示例为本机 5432）
createdb -U postgres cabinet
# 并通过环境变量覆盖连接信息（默认值见 application.yml）：
export CABINET_DB_URL=jdbc:postgresql://localhost:5432/cabinet
export CABINET_DB_USER=app
export CABINET_DB_PASSWORD=app
export KAFKA_BOOTSTRAP=localhost:9092

# 2. 启动应用（自动建表 + 种子数据）
mvn spring-boot:run
# 若 Kafka/PG 在自定义地址，可使用内置 local profile：
# mvn spring-boot:run -Dspring-boot.run.profiles=local   （见 application-local.yml）
```

## 四、测试账号（HTTP Basic，种子数据）

| 角色 | 用户名 | 密码 | 权限 |
| --- | --- | --- | --- |
| 补货员 | `replenisher` | `cabinet123` | 补货上架、执行下架、盘点 |
| 运营 | `operator` | `cabinet123` | 品类调整、下架、盘点审批、复核、告警、统计 |
| 财务 | `finance` | `cabinet123` | 供应商结算、查看批次损耗 |
| 客服 | `service` | `cabinet123` | 证据回放、投诉判责退款 |
| 管理员 | `admin` | `cabinet123` | 全部 |

种子主数据：供应商 `SUP001 鲜食达`、`SUP002 清泉饮品`；商品 `P-SANDWICH`（冷藏火腿三明治）、`P-MILK`（冷藏鲜牛奶）、`P-WATER`（常温矿泉水）；柜机 `CAB-001`（阳光花园社区，冷藏）、`CAB-002`（云谷科技园，常温）、`CAB-003`（市中心医院，冷藏）。另含次日到期批次 `B-MILK-SEED`（CAB-001 货道 A02 在柜 4 瓶），启动后可直接用验证步骤 4) 演示临期停售→扫码清点全链路。

## 五、验证方式（核心链路）

> 以下 curl 使用 `sync=true` 同步处理设备事件，便于直接观察结果；生产柜机默认异步走 Kafka（去掉 `sync` 参数即可验证消息链路）。

### 1) 补货上架（批次 + 保质期 + 货道 + 温度 + 照片）

```bash
curl -s -u replenisher:cabinet123 -X POST http://localhost:8080/api/restock/arrive \
  -H 'Content-Type: application/json' \
  -d '{"cabinetNo":"CAB-001","photoUrls":"https://example.io/photos/arrive.jpg","remark":"早班补货"}'
# 返回 orderNo，例如 RS202610060001

curl -s -u replenisher:cabinet123 -X POST http://localhost:8080/api/restock/RS.../items \
  -H 'Content-Type: application/json' \
  -d '{"sku":"P-MILK","batchNo":"B-MILK-1001","productionDate":"2026-10-05","expiryDate":"2026-10-12","qty":6,"channelCode":"A02","photoUrl":"https://example.io/photos/milk.jpg","cabinetTempC":4.2}'

curl -s -u replenisher:cabinet123 -X POST http://localhost:8080/api/restock/RS.../complete -d '{}' \
  -H 'Content-Type: application/json'
```

放错货道演示：把 `P-MILK` 放进计划为三明治的货道 `A01`，返回 `warning=WRONG_CHANNEL`，完成后补货单状态为 `COMPLETED_MISMATCH`，`GET /api/alerts` 出现告警。

### 2) 开门购物（多源识别 + 拿起放回）→ 关门结算

```bash
# 开门
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"DOOR_OPEN","cabinetNo":"CAB-001","sessionNo":"S-DEMO-1","userCode":"U1001","userName":"王女士"}'

# 视觉识别拿起 2 瓶
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"RECOGNITION","cabinetNo":"CAB-001","sessionNo":"S-DEMO-1","channelCode":"A02","sku":"P-MILK","source":"VISION","action":"PICK","qty":2,"confidence":0.95,"videoClipUrl":"https://video.io/s1","clipStartSec":3,"clipEndSec":6}'

# RFID 同样识别 2 瓶（多源一致）
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"RECOGNITION","cabinetNo":"CAB-001","sessionNo":"S-DEMO-1","channelCode":"A02","sku":"P-MILK","source":"RFID","action":"PICK","qty":2,"confidence":0.99}'

# 关门，柜内净重减少 520g（2×260g，交叉校验一致）
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"DOOR_CLOSE","cabinetNo":"CAB-001","sessionNo":"S-DEMO-1","weightDeltaGram":520}'
```

结果：订单 `CHARGED` 11.00 元（2×5.50），A02 库存按批次扣减，批次台账出现 `SALE_OUT`。
若发一条 `PUT` 且与拿起数量相等，则订单 `CANCELLED`；若视觉与重量识别数量冲突，则订单 `PENDING_REVIEW`，经 `POST /api/sessions/{sessionNo}/review/confirm` 复核后才扣款。

### 3) 温控异常

```bash
# 12℃ 超过冷藏上限 8℃ → 柜机锁定
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"TEMP","cabinetNo":"CAB-001","tempC":12.0}'
# 此后 DOOR_OPEN 被拒（会话 CANCELLED，备注“温控报警锁定，开门被拒”）
```

### 4) 临期扫描 → 批次停售 → 扫码领取 → 清点去向

种子批次 `B-MILK-SEED`（次日到期，CAB-001 A02 在柜 4 瓶）可直接演示。

```bash
# 4.1 扫描：进入临期范围的批次立即停售（只停该批），并生成补货员任务（默认 24h 时限）
curl -s -u operator:cabinet123 -X POST 'http://localhost:8080/api/removals/scan-expiry?nearExpiryDays=2'
# -> {"createdRemovalOrders": 1}

# 4.2 查待处理任务（status=PENDING/ACCEPTED/EXECUTED），任务单号即补货员扫码内容
curl -s -u replenisher:cabinet123 'http://localhost:8080/api/removals?cabinetNo=CAB-001&status=PENDING'
# 记录 orderNo，例如 RM202610060001

# 4.3 柜机（重新）上线同步：拿到停售批次清单与未完成任务——离线期间产生的停售在此补齐
curl -s -u operator:cabinet123 'http://localhost:8080/api/removals/device-sync?cabinetNo=CAB-001'

# 4.4 补货员到场扫任务码领取
curl -s -u replenisher:cabinet123 -X POST http://localhost:8080/api/removals/RM.../accept

# 4.5 查任务明细，拿到每行的 removalItemId 与系统停售数量 plannedQty
curl -s -u replenisher:cabinet123 http://localhost:8080/api/removals/1/items
# 现场清点 4 件：报损 2、回收 1、退供应商 1（四类数量之和必须等于实点数 countedQty）
curl -s -u replenisher:cabinet123 -X POST http://localhost:8080/api/removals/RM.../count \
  -H 'Content-Type: application/json' \
  -d '{"remark":"现场清点完成","lines":[{"removalItemId":1,"countedQty":4,"scrapQty":2,"recycleQty":1,"returnSupplierQty":1,"saleableQty":0}]}'
# 复检仍可售的场景把数量填到 saleableQty，该部分自动解除停售恢复售卖，不出库。
# 实点少于系统停售账（如开门在途已售出）会自动核销差异并产生 STOCK_DISCREPANCY 告警。
# 旧的整单同一去向接口仍可用：POST /api/removals/RM.../execute {"disposition":"SCRAP"}
```

### 4a) 同货道多批次：一批临期不停整条货道

在 A02 同时放一个新鲜批次后扫描临期：停售的只是临期批次（`qtySaleHalted` 增加），
新鲜批次仍可正常售卖，关门结算 FEFO 会自动跳过停售批次。

### 4b) 逾期任务与曝光限制

```bash
# 手动触发逾期检查（生产为每小时定时）；超过时限未执行的任务批次被限制曝光
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/removals/mark-overdue
curl -s -u operator:cabinet123 http://localhost:8080/api/removals/overdue
# 运营现场核实/处置后解封批次曝光
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/removals/batches/B-MILK-SEED/release-exposure \
  -H 'Content-Type: application/json' -d '{"reason":"现场已处置，无风险"}'
```

### 4c) 客服按批次找订单、风险提醒与供应商责任退款

```bash
# 按批次列出全部购买订单（risk=true 表示在停售后或到期当日及以后成交）
curl -s -u service:cabinet123 http://localhost:8080/api/analytics/batches/B-MILK-1001/orders
# 风险订单清单（供主动外呼/短信风险提醒）
curl -s -u service:cabinet123 http://localhost:8080/api/analytics/risk-sales
# 判责退款时 supplierLiability=true：未收回商品的报损记 SUPPLIER，结算自动扣减
curl -s -u service:cabinet123 -X POST http://localhost:8080/api/complaints/CP.../resolve \
  -H 'Content-Type: application/json' \
  -d '{"grantRefund":true,"goodsReturned":false,"supplierLiability":true,"resolution":"临期变质，供应商责任"}'
```

### 4d) 批次处置档案（还原为什么下架、最终去了哪里）

```bash
# 一次返回：批次停售/曝光状态、台账流水、报损记录、购买订单、每次下架的四种去向数量
curl -s -u operator:cabinet123 http://localhost:8080/api/analytics/batches/B-MILK-1001/record
curl -s -u operator:cabinet123 http://localhost:8080/api/analytics/batches/B-MILK-1001/trace
```

### 5) 盘点差异调整

```bash
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/stocktakes \
  -H 'Content-Type: application/json' -d '{"cabinetNo":"CAB-001"}'
# 录入实盘（对某行少盘 1 件）
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/stocktakes/ST.../count \
  -H 'Content-Type: application/json' \
  -d '{"counts":[{"channelCode":"A02","batchNo":"B-MILK-1001","countedQty":3}]}'
# 审批调整（盘亏记报损 SHRINKAGE）
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/stocktakes/ST.../adjust \
  -H 'Content-Type: application/json' -d '{"approve":true,"remark":"监控未见异常，按盘亏处理"}'
```

### 6) 投诉 → 证据回放 → 退款

```bash
curl -s -u service:cabinet123 -X POST http://localhost:8080/api/complaints \
  -H 'Content-Type: application/json' \
  -d '{"sessionNo":"S-DEMO-1","category":"误扣款","description":"我拿起又放回了仍被扣费"}'

curl -s -u service:cabinet123 http://localhost:8080/api/complaints/CP.../evidence
# 一次返回：开门记录、识别事件（含视频片段）、订单明细、批次台账（库存变化）、温控记录

# 判责整单退款、商品未收回（产生 REFUND_NO_RETURN 报损）
curl -s -u service:cabinet123 -X POST http://localhost:8080/api/complaints/CP.../resolve \
  -H 'Content-Type: application/json' \
  -d '{"grantRefund":true,"goodsReturned":false,"resolution":"视频显示已放回，整单退款"}'
```

### 7) 运营统计 / 批次追溯 / 供应商结算

```bash
curl -s -u operator:cabinet123 "http://localhost:8080/api/analytics/points?from=2026-10-01&to=2026-10-31"
curl -s -u operator:cabinet123 http://localhost:8080/api/analytics/batches/B-MILK-1001/trace
curl -s -u operator:cabinet123 http://localhost:8080/api/analytics/batches/B-MILK-1001/losses

# 财务按周期生成结算单（supplierId 见 GET /api/suppliers）
curl -s -u finance:cabinet123 -X POST http://localhost:8080/api/finance/settlements/generate \
  -H 'Content-Type: application/json' \
  -d '{"supplierId":1,"start":"2026-10-01","end":"2026-10-31"}'
curl -s -u finance:cabinet123 -X POST http://localhost:8080/api/finance/settlements/STL.../confirm
curl -s -u finance:cabinet123 -X POST http://localhost:8080/api/finance/settlements/STL.../pay
```

### 8) Kafka 消息链路验证

设备事件默认投递 `cabinet.device.event`（3 分区，key=cabinetNo 保证柜机内有序）；
结算、退款、下架、报损、温控锁定、结算确认会发布到 `cabinet.biz.event`；处理失败重试 2 次后进入 `cabinet.device.event.DLT`。

```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic cabinet.biz.event --from-beginning
```

## 六、自动化测试

```bash
mvn test
```

- `RecognitionFusionTest`：拿起放回抵消、多源一致融合、多源冲突降级、称重交叉校验；
- `BusinessFlowIntegrationTest`（H2）：补货上架/放错货道/过期禁上架、关门结算 FEFO、拿起放回取消、存疑复核、温控锁定隔离、临期下架报损、盘点盘亏、投诉退款（收回/未收回）、供应商结算口径；
- `NearExpiryRemovalFlowTest`（H2）：临期批次级停售且同货道他批照售、扫码领取与四种去向清点、去向校验、开门在途停售并发对账与风险提醒、逾期曝光限制与解封、离线同步与扫描幂等、按批次找订单与供应商责任退款、批次处置档案。

## 七、主要数据模型

```
point_location 1—N cabinet 1—N channel N—1 product(计划品类)
restock_order 1—N restock_item（批次/保质期/数量/货道/温区/照片/告警）
product_batch（saleHalted 停售 / exposureBlocked 逾期曝光限制）1—N channel_stock（在库/温控隔离量/批次停售量）
product_batch 1—N batch_ledger（补货/售卖/停售/解封/下架/报损/盘点/退款/结算 逐笔）
door_session 1—N recognition_event（重量/视觉/RFID，含视频片段）
door_session 1—1 order_record 1—N order_item（精确到批次）
removal_order（任务时限/领取人/逾期标记）1—N removal_item（系统停售数/实点数/报损/回收/退供/仍可售）
stocktake_order 1—N stocktake_item（系统数/实盘数/差异/调整标记）
complaint N—1 door_session；settlement_batch 1—N settlement_item（批次级对账）
temp_log / alert 支撑温控、临期、逾期与批次风险提醒
```
