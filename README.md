# 无人货柜补货盘点临期下架服务

面向城市社区、办公楼、医院无人货柜场景的后端服务，覆盖补货员补货上架、柜机多源识别下单、**临期批次自动下架（批次级停售 → 补货员扫码清点四去向 → 账实对账）**、报损、冷藏温控、盘点差异调整、用户投诉退款与供应商批次结算的全链路。

技术栈：**Java 17 + Spring Boot 3.3 + Spring Security + Spring Kafka + PostgreSQL + H2(测试)**。

## 一、能力总览

| 业务问题 | 实现方式 |
| --- | --- |
| 补货记录 | 柜机编号、商品批次、保质期（生产/到期日）、数量、货道位置、温度要求、补货照片全部落表（`restock_order`/`restock_item`） |
| 重量/视觉/RFID 识别下单 | 设备事件投递 Kafka `cabinet.device.event`，服务侧按会话聚合多源事件，关门融合后 FEFO 扣批次库存、生成订单与批次台账 |
| 识别错误 | 多源数量不一致/低置信度/称重交叉校验不符 → 订单挂起 `PENDING_REVIEW`，**不扣款不扣库存**，人工复核确认/否定 |
| 拿起又放回 | 每个识别源独立计算“拿起-放回”净值，净值为 0 不出单、不扣款 |
| **临期批次自动下架** | 每日 02:10 扫描（可手动触发）：批次进入临期范围后先做**批次级停售**（`product_batch.sale_blocked` + 该批次在各货道的隔离量 + 柜端 `BLOCK_BATCH` 指令），再按「柜机×批次」生成补货员处理任务 `removal_order` |
| **同一货道多批次** | 停售/隔离粒度是**批次**：FEFO 售卖跳过停售批次，同货道其他批次正常售卖，不会因一批临期停掉整条货道 |
| **柜机离线期间产生任务** | 停售指令先落 `device_command`（柜机内单调递增 `seq`，PENDING），柜机重连发 `COMMAND_PULL` 按已确认序号补发、幂等确认；心跳 `HEARTBEAT/ONLINE/OFFLINE` 维护在线状态 |
| **补货员扫码清点** | 到场 `POST /api/removals/{no}/scan` 绑定执行人，返回逐行实时对账（当前在库、停售后已售、差异）；按明细逐行提交实际数量与去向 |
| **四种实物去向** | `SCRAP` 报损（供应商责任报损）/ `RECYCLE` 回收 / `RETURN_SUPPLIER` 退供应商 / `RESALE` 仍可销售（解除隔离并下发 `UNBLOCK_BATCH` 复售），同一明细可拆分多去向 |
| **停售与开门扣款并发** | 库存行悲观写锁（锁后刷新最新值）互斥；开门早于停售时允许从已停售批次兜底扣款，并产生 `SOLD_EXPIRED` 风险告警；清点时用「停售后售出明细」自动核销已买走部分 |
| **库存/订单/下架对账** | 清点执行强制守恒：整批任务 `当前在库 = 四去向处置合计 + 盘亏短少`，停售快照 = 停售后已售 + 当前在库(±其他流水)；账有实无自动记 `SHRINKAGE` 盘亏报损 |
| **任务逾期与限流曝光** | 任务带截止时间（临期默认 8 小时、过期 2 小时），每小时巡检；逾期任务在运营端可见并对批次置 `exposure_blocked`，**禁止该批次再次上架/曝光**；处置完确认复售时解除 |
| 货道卡货 | 设备 `CHANNEL_JAM` 事件生成告警；卡货损坏可走手工部分下架（只隔离预留数量，不停整批） |
| 冷藏温度异常 | 温度越限即锁定柜机（拒绝开门），持续越限 30 分钟隔离柜内全部批次，食品安全处置（下架/报损） |
| 放错货道 | 上架时校验货道计划商品，不一致记 `WRONG_CHANNEL` 告警，补货单完成态为 `COMPLETED_MISMATCH` |
| 盘点库存不符 | 盘点单生成系统快照 → 录入实盘 → 差异审批调整：盘亏记报损（SHRINKAGE），台账 ADJUST_MINUS/PLUS |
| 用户误扣款 | 投诉单关联开门会话；客服证据回放后按整单/按行退款；商品收回回补库存（已停售批次进隔离不复活），未收回记报损 |
| **客服按批次追单与责任退款** | `GET /api/batches/{batchNo}/orders` 按批次找全部购买订单；`risk-notify` 向买过该批次的用户逐单风险提醒；`refund` 供应商责任批量退款（实物未收回记 `SUPPLIER_QUALITY` 报损，结算自动扣供应商款） |
| **批次记录还原** | `GET /api/batches/{batchNo}/record` 聚合主数据、台账、报损、购买订单、下架任务与四去向明细、结算明细、告警——可还原“为什么下架、最终去了哪里、结算如何变化” |
| 按点位调品类 | `PUT /api/channels/{id}/planned-product` 调整货道计划商品 |
| 批次全链路追溯 | `batch_ledger` 逐笔记录补货入/售卖出/下架出/报损出/盘盈盘亏/退款回库/复售/结算；`/api/analytics/batches/{batchNo}/trace` |
| 售后证据回放 | 开门记录、多源识别结果（含视频片段 URL 与起止秒）、订单、批次库存变化、温控曲线一次返回 |
| 供应商结算 | 按周期汇总真实售出（扣除退款）与供应商责任报损（含临期报废、批次质量责任退款），逐批次明细，应付 = 售卖成本 − 责任报损 |

### 关键状态机

- **开门会话**：`OPEN → SETTLED`（已扣款）/ `REVIEW_PENDING → SETTLED|CANCELLED`（人工复核）/ `CANCELLED`（无净拿取、温控拒开门）/ `SETTLED_REFUNDED`
- **商品批次**：`IN_STOCK → SOLD_OUT | REMOVED → SETTLED`；叠加两个独立开关：`saleBlocked`（临期停售，控制能否买）与 `exposureBlocked`（任务逾期，控制能否再次上架/曝光）
- **下架任务**：`PENDING（可标记 overdue）→ EXECUTED`；`fullBlock=true` 为临期扫描整批任务，`false` 为手工部分下架；执行后记录 `plannedTotalQty/resolvedTotalQty/soldWhilePendingQty/shortageQty`
- **下架去向**：`SCRAP`（报损，产生责任报损）/ `RECYCLE`（回收）/ `RETURN_SUPPLIER`（退供）/ `RESALE`（复售，解除隔离）
- **柜端指令**：`device_command: PENDING → ACKED`，按柜机 `seq` 补发去重
- **结算单**：`DRAFT → CONFIRMED → PAID`，同供应商同周期不可重复生成

## 二、一键启动（Docker Compose）

```bash
docker compose up --build
```

启动三个容器：PostgreSQL 16（5432）、Kafka 3.9 KRaft 单节点（9092）、本应用（8080）。
应用启动时自动建表（`ddl-auto: update`）并写入种子数据（含一个已在 CAB-003/A01 上架 6 盒、次日到期的批次 `B-MILK-SEED`，可直接演示扫描停售）。

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
| 补货员 | `replenisher` | `cabinet123` | 补货上架、扫码执行下架、盘点 |
| 运营 | `operator` | `cabinet123` | 品类调整、临期扫描、手工下架、盘点审批、复核、逾期任务、告警、统计 |
| 财务 | `finance` | `cabinet123` | 供应商结算、查看批次损耗 |
| 客服 | `service` | `cabinet123` | 证据回放、投诉判责退款、按批次追单/风险提醒/责任退款 |
| 管理员 | `admin` | `cabinet123` | 全部 |

种子主数据：供应商 `SUP001 鲜食达`、`SUP002 清泉饮品`；商品 `P-SANDWICH`（冷藏火腿三明治）、`P-MILK`（冷藏鲜牛奶）、`P-WATER`（常温矿泉水）；柜机 `CAB-001`（阳光花园社区，冷藏）、`CAB-002`（云谷科技园，常温）、`CAB-003`（市中心医院，冷藏）。

## 五、验证方式（核心链路）

> 设备事件用 `sync=true` 同步处理便于直接观察；去掉 `sync` 即走 Kafka 异步链路。

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
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"DOOR_OPEN","cabinetNo":"CAB-001","sessionNo":"S-DEMO-1","userCode":"U1001","userName":"王女士"}'

curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"RECOGNITION","cabinetNo":"CAB-001","sessionNo":"S-DEMO-1","channelCode":"A02","sku":"P-MILK","source":"VISION","action":"PICK","qty":2,"confidence":0.95,"videoClipUrl":"https://video.io/s1","clipStartSec":3,"clipEndSec":6}'

curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"RECOGNITION","cabinetNo":"CAB-001","sessionNo":"S-DEMO-1","channelCode":"A02","sku":"P-MILK","source":"RFID","action":"PICK","qty":2,"confidence":0.99}'

curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"DOOR_CLOSE","cabinetNo":"CAB-001","sessionNo":"S-DEMO-1","weightDeltaGram":520}'
```

结果：订单 `CHARGED` 11.00 元（2×5.50），A02 库存按批次扣减，批次台账出现 `SALE_OUT`。
若一条 `PUT` 与拿起数量相等，则订单 `CANCELLED`；若视觉与重量识别冲突，则订单 `PENDING_REVIEW`，经 `POST /api/sessions/{sessionNo}/review/confirm` 复核后才扣款。

### 3) 温控异常

```bash
# 12℃ 超过冷藏上限 8℃ → 柜机锁定
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"TEMP","cabinetNo":"CAB-001","tempC":12.0}'
# 此后 DOOR_OPEN 被拒（会话 CANCELLED，备注“温控报警锁定，开门被拒”）
```

### 4) 临期自动下架全链路（批次级停售 → 扫码清点四去向）

```bash
# (1) 扫描：批次级停售 + 按柜机生成补货员任务 + 柜端 BLOCK_BATCH 指令
curl -s -u operator:cabinet123 -X POST 'http://localhost:8080/api/removals/scan-expiry?nearExpiryDays=2'
# {"createdRemovalTasks":1}

# (2) 查待处理任务（补货员看 /api/removals/tasks/mine，运营可加 status=PENDING）
curl -s -u replenisher:cabinet123 http://localhost:8080/api/removals/tasks/mine

# (3) 柜机离线场景：先标记离线再扫描，指令挂起；重连后拉取（离线补偿）
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' -d '{"type":"OFFLINE","cabinetNo":"CAB-003"}'
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"COMMAND_PULL","cabinetNo":"CAB-003","ackSeq":0}'
# 返回离线期间积压的 BLOCK_BATCH 指令，柜端按 seq 幂等执行

# (4) 补货员到场扫码：返回任务明细与实时对账（当前在库/停售后已售/差异）
curl -s -u replenisher:cabinet123 -X POST http://localhost:8080/api/removals/RM.../scan

# (5) 扫码清点：按明细 itemId 提交实际数量与去向（同一明细可拆四去向）
curl -s -u replenisher:cabinet123 -X POST http://localhost:8080/api/removals/RM.../count \
  -H 'Content-Type: application/json' -d '{
    "counts":[
      {"removalItemId":1,"countedQty":2,"disposition":"SCRAP","photoUrl":"https://example.io/p1.jpg"},
      {"removalItemId":1,"countedQty":1,"disposition":"RECYCLE"},
      {"removalItemId":1,"countedQty":1,"disposition":"RETURN_SUPPLIER"},
      {"removalItemId":1,"countedQty":2,"disposition":"RESALE"}
    ],
    "remark":"到场清点，2盒仍可销售"}'
# 返回任务：resolvedTotalQty=6, soldWhilePendingQty=0, shortageQty=0
# 台账：REMOVAL_OUT(报损/回收/退供) + RESALE_UNBLOCK(复售)；SCRAP 同步生成供应商责任报损；
# 批次仍有 2 盒在库 -> 解除停售、释放隔离、下发 UNBLOCK_BATCH

# 兼容旧接口（整单统一去向，按计划数量执行）：
curl -s -u replenisher:cabinet123 -X POST http://localhost:8080/api/removals/RM.../execute \
  -H 'Content-Type: application/json' -d '{"disposition":"SCRAP","remark":"全部报废"}'
```

### 5) 逾期任务与限流曝光

```bash
# 运营查看逾期任务（实时巡检，逾期批次被置 exposure_blocked）
curl -s -u operator:cabinet123 http://localhost:8080/api/removals/tasks/overdue
# 该批次再走补货上架会被拒：400 “因下架任务逾期已被限制曝光，禁止再次上架”
```

### 6) 客服按批次追单、风险提醒、供应商责任退款

```bash
# 查买过该批次的订单
curl -s -u service:cabinet123 http://localhost:8080/api/batches/B-MILK-SEED/orders
# 对受影响用户逐单风险提醒（生成 BATCH_RISK 告警 + cabinet.biz.event 触达事件）
curl -s -u service:cabinet123 -X POST http://localhost:8080/api/batches/B-MILK-SEED/risk-notify \
  -H 'Content-Type: application/json' -d '{"message":"该批次牛奶可能变质，请勿饮用，将为您退款"}'
# 供应商责任批量退款（实物未收回 -> SUPPLIER_QUALITY 报损，结算扣减供应商应付款）
curl -s -u service:cabinet123 -X POST http://localhost:8080/api/batches/B-MILK-SEED/refund \
  -H 'Content-Type: application/json' -d '{"reason":"批次临期变质"}'

# 批次全记录还原：为什么下架 + 最终去向 + 结算变化
curl -s -u service:cabinet123 http://localhost:8080/api/batches/B-MILK-SEED/record
```

### 7) 盘点差异调整

```bash
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/stocktakes \
  -H 'Content-Type: application/json' -d '{"cabinetNo":"CAB-001"}'
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/stocktakes/ST.../count \
  -H 'Content-Type: application/json' \
  -d '{"counts":[{"channelCode":"A02","batchNo":"B-MILK-1001","countedQty":3}]}'
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/stocktakes/ST.../adjust \
  -H 'Content-Type: application/json' -d '{"approve":true,"remark":"监控未见异常，按盘亏处理"}'
```

### 8) 投诉 → 证据回放 → 退款

```bash
curl -s -u service:cabinet123 -X POST http://localhost:8080/api/complaints \
  -H 'Content-Type: application/json' \
  -d '{"sessionNo":"S-DEMO-1","category":"误扣款","description":"我拿起又放回了仍被扣费"}'
curl -s -u service:cabinet123 http://localhost:8080/api/complaints/CP.../evidence
curl -s -u service:cabinet123 -X POST http://localhost:8080/api/complaints/CP.../resolve \
  -H 'Content-Type: application/json' \
  -d '{"grantRefund":true,"goodsReturned":false,"resolution":"视频显示已放回，整单退款"}'
```

### 9) 运营统计 / 批次追溯 / 供应商结算

```bash
curl -s -u operator:cabinet123 "http://localhost:8080/api/analytics/points?from=2026-10-01&to=2026-10-31"
curl -s -u operator:cabinet123 http://localhost:8080/api/analytics/batches/B-MILK-1001/trace
curl -s -u operator:cabinet123 http://localhost:8080/api/analytics/batches/B-MILK-1001/losses

curl -s -u finance:cabinet123 -X POST http://localhost:8080/api/finance/settlements/generate \
  -H 'Content-Type: application/json' \
  -d '{"supplierId":1,"start":"2026-10-01","end":"2026-10-31"}'
curl -s -u finance:cabinet123 -X POST http://localhost:8080/api/finance/settlements/STL.../confirm
curl -s -u finance:cabinet123 -X POST http://localhost:8080/api/finance/settlements/STL.../pay
```

### 10) Kafka 消息链路验证

设备事件默认投递 `cabinet.device.event`（3 分区，key=cabinetNo 保证柜机内有序）；
结算、退款、下架任务、报损、风险提醒、温控锁定、结算确认发布到 `cabinet.biz.event`；处理失败重试 2 次后进入 `cabinet.device.event.DLT`。

```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic cabinet.biz.event --from-beginning
```

## 六、自动化测试

```bash
mvn test
```

共 26 个用例，全部通过（H2 内存库，Kafka 用 Mock）：

- `RecognitionFusionTest`（4）：拿起放回抵消、多源一致融合、多源冲突降级、称重交叉校验；
- `BusinessFlowIntegrationTest`（12）：补货上架/放错货道/过期禁上架、关门结算 FEFO、拿起放回取消、存疑复核、温控锁定隔离、临期下架报损、盘点盘亏、投诉退款（收回/未收回）、供应商结算口径；
- `NearExpiryRemovalFlowTest`（10，本次新增）：
  1. 同货道多批次只停涉事批次、其他批次继续售卖，柜端停售指令落表；
  2. 柜机离线期间临期任务，重连 `COMMAND_PULL` 按 seq 补发并幂等确认；
  3. 扫码清点四去向（报损/回收/退供/复售）分别记账，复售解除隔离并下发复售指令；
  4. 停售与开门并发：停售窗口售出兜底扣款 + 风险告警，清点自动核销；
  5. 清点账有实无自动盘亏报损，库存/订单/下架守恒；
  6. 任务逾期 → 批次限流曝光，禁止再次上架；
  7. 客服按批次风险提醒 + 供应商责任退款（SUPPLIER_QUALITY 报损）；
  8. 批次全记录还原下架原因与四去向；
  9. 手工部分下架只预留指定数量、不停整批；
  10. 多线程：临期扫描与两笔关门扣款并发，无死锁/超卖，售出+在库守恒、重复扫描幂等。

## 七、主要数据模型

```
point_location 1—N cabinet 1—N channel N—1 product(计划品类)
cabinet 1—N device_command（批次停售/复售指令，seq 补发，离线补偿）
restock_order 1—N restock_item（批次/保质期/数量/货道/温区/照片/告警）
product_batch（sale_blocked 停售开关 + blocked_at/reason；exposure_blocked 限流曝光）
product_batch 1—N channel_stock（货道批次库存，含隔离量 qty_quarantined）
product_batch 1—N batch_ledger（补货/售卖/下架/报损/盘点/退款/复售/结算 逐笔）
door_session 1—N recognition_event（重量/视觉/RFID，含视频片段）
door_session 1—1 order_record 1—N order_item（精确到批次）
removal_order（补货员任务：截止时间/逾期/扫码到场/计划数/处置数/停售后已售/盘亏/full_block）
  1—N removal_item（计划数/清点实测数/执行数/去向/照片）
stocktake_order 1—N stocktake_item（系统数/实盘数/差异/调整标记）
complaint N—1 door_session；settlement_batch 1—N settlement_item（批次级对账）
temp_log / alert 支撑温控、临期、停售并发售出、逾期与批次风险提醒
```
