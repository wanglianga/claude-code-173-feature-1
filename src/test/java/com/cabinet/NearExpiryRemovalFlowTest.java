package com.cabinet;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.*;
import com.cabinet.dto.BatchRecordView;
import com.cabinet.kafka.dto.DeviceEvent;
import com.cabinet.repo.*;
import com.cabinet.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 临期商品自动下架全链路集成测试（H2）：
 * - 同货道多批次：只停临期批次，整条货道与其他批次继续售卖；
 * - 柜机离线期间产生临期任务，重连后按 seq 补发停售指令；
 * - 补货员到场扫码、清点四去向（报损/回收/退供/仍可销售）；
 * - 临期停售与开门扣款并发：停售窗口售出兜底扣款+风险告警，清点自动核销；
 * - 清点盘亏自动记报损、账实守恒；
 * - 任务逾期 -> 批次限流曝光，禁止再次上架；
 * - 客服按批次找购买订单、风险提醒、供应商责任退款；
 * - 批次全记录还原。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestKafkaConfig.class)
class NearExpiryRemovalFlowTest {

    @Autowired private TestFixtures fx;
    @Autowired private RestockService restockService;
    @Autowired private SessionSettlementService sessionService;
    @Autowired private RemovalService removalService;
    @Autowired private BatchCareService batchCareService;
    @Autowired private DeviceCommandService deviceCommandService;
    @Autowired private com.cabinet.kafka.DeviceEventProcessor deviceEventProcessor;

    @Autowired private ProductBatchRepository batchRepository;
    @Autowired private ChannelStockRepository stockRepository;
    @Autowired private RemovalOrderRepository removalOrderRepository;
    @Autowired private RemovalItemRepository removalItemRepository;
    @Autowired private DeviceCommandRepository commandRepository;
    @Autowired private AlertRepository alertRepository;
    @Autowired private LossRecordRepository lossRepository;
    @Autowired private BatchLedgerRepository ledgerRepository;
    @Autowired private OrderRecordRepository orderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private CabinetRepository cabinetRepository;
    @Autowired private RestockOrderRepository restockOrderRepository;

    private record TwoBatches(Cabinet cabinet, Channel channel, Product product,
                              AppUser replenisher, ProductBatch near, ProductBatch fresh) {}

    /** 同一货道上架两个批次：nearExpiryDays 天后到期 + 30 天后到期，各 qty 件 */
    private TwoBatches setup(int nearExpiryDays, int nearQty, int freshQty, String channelCode) {
        AppUser user = fx.user("REPLENISHER");
        Supplier supplier = fx.supplier();
        Product product = fx.chilledProduct(supplier);
        Point point = fx.point(LocationType.COMMUNITY);
        Cabinet cabinet = fx.chilledCabinet(point);
        cabinet.setOnline(true);
        cabinetRepository.save(cabinet);
        Channel channel = fx.channel(cabinet, product, channelCode);

        ProductBatch near = placeBatch(cabinet, user, product, channel,
                "B-NEAR-" + System.nanoTime(), nearExpiryDays, nearQty);
        ProductBatch fresh = freshQty > 0
                ? placeBatch(cabinet, user, product, channel,
                        "B-FRESH-" + System.nanoTime(), 30, freshQty)
                : null;
        return new TwoBatches(cabinet, channel, product, user, near, fresh);
    }

    private ProductBatch placeBatch(Cabinet cabinet, AppUser user, Product product,
                                    Channel channel, String batchNo, int expiryDays, int qty) {
        RestockOrder order = restockService.arrive(cabinet.getCabinetNo(),
                user.getUsername(), "photo.jpg", null);
        restockService.placeItem(order.getOrderNo(), product.getSku(), batchNo,
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(expiryDays),
                qty, channel.getCode(), "photo.jpg", 4.0);
        restockService.complete(order.getOrderNo(), null);
        return batchRepository.findByBatchNo(batchNo).orElseThrow();
    }

    private ChannelStock stockOf(Channel channel, ProductBatch batch) {
        return stockRepository.findByChannelIdAndBatchId(channel.getId(), batch.getId()).orElseThrow();
    }

    /** 模拟一次购物：开门->视觉+RFID 一致识别 pickQty->关门(重量按 260g/件交叉校验) */
    private OrderRecord buy(Cabinet cabinet, String channelCode, String sku, int pickQty) {
        String sessionNo = "S-" + System.nanoTime();
        sessionService.openDoor(cabinet.getCabinetNo(), sessionNo, "U1", "测试用户", null);
        sessionService.recordRecognition(sessionNo, cabinet.getCabinetNo(), channelCode,
                sku, null, "VISION", "PICK", pickQty, 0.99, null, "v.mp4", 1, 3, null, null);
        sessionService.recordRecognition(sessionNo, cabinet.getCabinetNo(), channelCode,
                sku, null, "RFID", "PICK", pickQty, 0.99, null, null, null, null, null, null);
        return sessionService.closeDoor(sessionNo, pickQty * 260, null);
    }

    private RemovalOrder pendingTaskForBatch(Long batchId) {
        return removalOrderRepository.findAll().stream()
                .filter(r -> r.getStatus() == RemovalStatus.PENDING)
                .filter(r -> removalItemRepository.findByRemovalOrderId(r.getId()).stream()
                        .anyMatch(i -> i.getBatch().getId().equals(batchId)))
                .findFirst().orElseThrow();
    }

    @Test
    void 同货道多批次临期只停涉事批次其他批次继续售卖() {
        TwoBatches d = setup(1, 4, 6, "A01");

        int created = removalService.scanExpiry(2);
        assertThat(created).isGreaterThanOrEqualTo(1);

        ProductBatch near = batchRepository.findById(d.near.getId()).orElseThrow();
        ProductBatch fresh = batchRepository.findById(d.fresh.getId()).orElseThrow();
        // 涉事批次停售且货道内该批次量全部隔离
        assertThat(near.isSaleBlocked()).isTrue();
        ChannelStock ns = stockOf(d.channel, near);
        assertThat(ns.getQtyQuarantined()).isEqualTo(4);
        // 同货道另一批次不受影响
        assertThat(fresh.isSaleBlocked()).isFalse();
        ChannelStock fs = stockOf(d.channel, fresh);
        assertThat(fs.getQtyOnHand() - fs.getQtyQuarantined()).isEqualTo(6);
        // 已向柜端下发批次停售指令
        assertThat(commandRepository.findAll()).anyMatch(c ->
                c.getBatchNo().equals(near.getBatchNo())
                        && "BLOCK_BATCH".equals(c.getCommandType()));

        // 货道仍可购物：FEFO 跳过停售批次，扣的是新鲜批次
        OrderRecord order = buy(d.cabinet, "A01", d.product.getSku(), 2);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CHARGED);
        assertThat(stockOf(d.channel, near).getQtyOnHand()).isEqualTo(4);
        assertThat(stockOf(d.channel, fresh).getQtyOnHand()).isEqualTo(4);
    }

    @Test
    void 柜机离线期间临期任务重连后补发停售指令() {
        TwoBatches d = setup(1, 3, 3, "A01");
        d.cabinet().setOnline(false);
        cabinetRepository.save(d.cabinet());

        removalService.scanExpiry(2);

        List<DeviceCommand> pending = commandRepository.findAll().stream()
                .filter(c -> c.getBatchNo().equals(d.near().getBatchNo())).toList();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getStatus()).isEqualTo("PENDING");
        assertThat(pending.get(0).getSentAt()).isNull();

        // 柜机重连，心跳携带已确认序号 0 拉取指令
        List<DeviceCommand> pulled = deviceEventProcessor.process(DeviceEvent.builder()
                .type("COMMAND_PULL").cabinetNo(d.cabinet().getCabinetNo()).ackSeq(0L)
                .eventTime(LocalDateTime.now()).build());
        assertThat(pulled).anyMatch(c -> c.getBatchNo().equals(d.near().getBatchNo())
                && "BLOCK_BATCH".equals(c.getCommandType()));
        assertThat(commandRepository.findById(pending.get(0).getId()).orElseThrow().getStatus())
                .isEqualTo("ACKED");
        assertThat(cabinetRepository.findById(d.cabinet().getId()).orElseThrow().isOnline()).isTrue();
    }

    @Test
    void 扫码清点四去向分别记账并对仍可销售批次复售() {
        TwoBatches d = setup(1, 6, 0, "A01");
        removalService.scanExpiry(2);
        RemovalOrder task = pendingTaskForBatch(d.near().getId());

        // 到场扫码
        RemovalService.RemovalTaskView view = removalService.scan(task.getOrderNo(),
                d.replenisher().getUsername());
        assertThat(view.order().getScannedAt()).isNotNull();
        assertThat(view.liveItems()).hasSize(1);
        assertThat(view.liveItems().get(0).currentOnHand()).isEqualTo(6);

        RemovalItem item = removalItemRepository.findByRemovalOrderId(task.getId()).get(0);
        // 同一明细行拆分四个去向：报损2 回收1 退供1 仍可销售2
        RemovalOrder done = removalService.countAndExecute(task.getOrderNo(),
                d.replenisher().getUsername(),
                List.of(
                        new RemovalService.CountLine(item.getId(), 2, Disposition.SCRAP, "p1.jpg"),
                        new RemovalService.CountLine(item.getId(), 1, Disposition.RECYCLE, null),
                        new RemovalService.CountLine(item.getId(), 1, Disposition.RETURN_SUPPLIER, null),
                        new RemovalService.CountLine(item.getId(), 2, Disposition.RESALE, null)),
                "四去向清点");

        assertThat(done.getStatus()).isEqualTo(RemovalStatus.EXECUTED);
        assertThat(done.getResolvedTotalQty()).isEqualTo(6);
        ChannelStock s = stockOf(d.channel, d.near);
        assertThat(s.getQtyOnHand()).isEqualTo(2);      // 仅复售 2 件留柜
        assertThat(s.getQtyQuarantined()).isZero();    // 隔离已释放

        var ledgers = ledgerRepository.findByBatchIdOrderByIdAsc(d.near().getId());
        assertThat(ledgers).anyMatch(l -> l.getLedgerType() == LedgerType.REMOVAL_OUT
                && l.getChangeQty() == -2 && l.getRemark().contains("报损"));
        assertThat(ledgers).anyMatch(l -> l.getLedgerType() == LedgerType.REMOVAL_OUT
                && l.getRemark().contains("回收"));
        assertThat(ledgers).anyMatch(l -> l.getLedgerType() == LedgerType.REMOVAL_OUT
                && l.getRemark().contains("退回供应商"));
        assertThat(ledgers).anyMatch(l -> l.getLedgerType() == LedgerType.RESALE_UNBLOCK
                && l.getRemark().contains("仍可销售"));
        // 仅报损产生报损记录
        List<LossRecord> losses = lossRepository.findByBatchIdOrderByIdAsc(d.near().getId());
        assertThat(losses).hasSize(1);
        assertThat(losses.get(0).getReason()).isEqualTo(LossReason.SCRAP_NEAR_EXPIRY);
        assertThat(losses.get(0).getLiability()).isEqualTo("SUPPLIER");
        // 仍有在库 -> 批次解除停售并下发复售指令
        ProductBatch unblocked = batchRepository.findById(d.near().getId()).orElseThrow();
        assertThat(unblocked.isSaleBlocked()).isFalse();
        assertThat(commandRepository.findAll()).anyMatch(c ->
                c.getBatchNo().equals(d.near().getBatchNo())
                        && "UNBLOCK_BATCH".equals(c.getCommandType()));
    }

    @Test
    void 停售与开门并发停售窗口售出自动核销并风险告警() {
        TwoBatches d = setup(1, 5, 0, "A01");
        removalService.scanExpiry(2);
        RemovalOrder task = pendingTaskForBatch(d.near().getId());

        // 用户在停售瞬间已拿走 2 件（兜底扣款成功）
        OrderRecord order = buy(d.cabinet, "A01", d.product.getSku(), 2);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CHARGED);
        assertThat(stockOf(d.channel, d.near).getQtyOnHand()).isEqualTo(3);
        // 停售批次被买走 -> 风险告警
        assertThat(alertRepository.findAll()).anyMatch(a -> a.getAlertType() == AlertType.SOLD_EXPIRED);

        // 补货员清点剩余 3 件全部报损
        RemovalItem item = removalItemRepository.findByRemovalOrderId(task.getId()).get(0);
        RemovalOrder done = removalService.countAndExecute(task.getOrderNo(),
                d.replenisher().getUsername(),
                List.of(new RemovalService.CountLine(item.getId(), 3, Disposition.SCRAP, null)),
                null);

        assertThat(done.getSoldWhilePendingQty()).isEqualTo(2);
        assertThat(done.getResolvedTotalQty()).isEqualTo(3);
        assertThat(stockOf(d.channel, d.near).getQtyOnHand()).isZero();
        assertThat(batchRepository.findById(d.near().getId()).orElseThrow().getStatus())
                .isEqualTo(BatchStatus.REMOVED);

        // 客服按批次能找到该笔购买订单
        List<BatchCareService.BatchOrderRow> rows =
                batchCareService.batchOrders(d.near().getBatchNo());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).orderNo()).isEqualTo(order.getOrderNo());
        assertThat(rows.get(0).qty()).isEqualTo(2);
    }

    @Test
    void 清点账有实无自动盘亏报损并守恒() {
        TwoBatches d = setup(1, 5, 0, "A01");
        removalService.scanExpiry(2);
        RemovalOrder task = pendingTaskForBatch(d.near().getId());

        RemovalItem item = removalItemRepository.findByRemovalOrderId(task.getId()).get(0);
        // 实物只有 4 件，全部报损；系统 5 件，短少 1 件自动盘亏
        RemovalOrder done = removalService.countAndExecute(task.getOrderNo(),
                d.replenisher().getUsername(),
                List.of(new RemovalService.CountLine(item.getId(), 4, Disposition.SCRAP, null)),
                null);

        assertThat(done.getShortageQty()).isEqualTo(1);
        List<LossRecord> losses = lossRepository.findByBatchIdOrderByIdAsc(d.near().getId());
        assertThat(losses).anyMatch(l -> l.getReason() == LossReason.SCRAP_NEAR_EXPIRY && l.getQty() == 4);
        assertThat(losses).anyMatch(l -> l.getReason() == LossReason.SHRINKAGE
                && l.getQty() == 1 && "OPERATOR".equals(l.getLiability()));
        assertThat(stockOf(d.channel, d.near).getQtyOnHand()).isZero();
        assertThat(ledgerRepository.findByBatchIdOrderByIdAsc(d.near().getId()))
                .anyMatch(l -> l.getLedgerType() == LedgerType.ADJUST_MINUS && l.getChangeQty() == -1);
    }

    @Test
    void 任务逾期限制批次曝光且禁止再次上架() {
        TwoBatches d = setup(1, 3, 0, "A01");
        removalService.scanExpiry(2);
        RemovalOrder task = pendingTaskForBatch(d.near().getId());

        // 任务超过截止时间仍未处理
        task.setDueAt(LocalDateTime.now().minusHours(1));
        removalOrderRepository.save(task);

        int marked = removalService.markOverdueTasks();
        assertThat(marked).isGreaterThanOrEqualTo(1);
        assertThat(removalOrderRepository.findById(task.getId()).orElseThrow().isOverdue()).isTrue();
        ProductBatch blocked = batchRepository.findById(d.near().getId()).orElseThrow();
        assertThat(blocked.isExposureBlocked()).isTrue();
        assertThat(alertRepository.findAll()).anyMatch(a -> a.getAlertType() == AlertType.REMOVAL_OVERDUE);

        // 该批次禁止再次上架
        RestockOrder ro = restockService.arrive(d.cabinet().getCabinetNo(),
                d.replenisher().getUsername(), null, null);
        assertThatThrownBy(() -> restockService.placeItem(ro.getOrderNo(), d.product().getSku(),
                d.near().getBatchNo(), LocalDate.now().minusDays(1), LocalDate.now().plusDays(5),
                1, "A01", null, 4.0))
                .hasMessageContaining("限制曝光");
    }

    @Test
    void 客服按批次风险提醒并供应商责任退款() {
        TwoBatches d = setup(1, 5, 0, "A01");
        OrderRecord order = buy(d.cabinet, "A01", d.product().getSku(), 3);

        // 风险提醒：3 件对应 1 笔订单 1 个用户
        BatchCareService.RiskNotifyResult risk = batchCareService.notifyRisk(
                d.near().getBatchNo(), "service", "牛奶可能变质，请勿饮用");
        assertThat(risk.affectedOrders()).isEqualTo(1);
        assertThat(risk.notifiedUsers()).isEqualTo(1);
        assertThat(alertRepository.findAll()).anyMatch(a -> a.getAlertType() == AlertType.BATCH_RISK);

        // 供应商责任退款（实物未收回）
        AppUser cs = fx.user("CUSTOMER_SERVICE");
        BatchCareService.BatchRefundResult refund = batchCareService.refundByBatch(
                d.near().getBatchNo(), cs.getUsername(), "批次临期变质");
        assertThat(refund.refundedOrders()).isEqualTo(1);
        assertThat(refund.refundAmount()).isEqualByComparingTo("16.50"); // 3*5.50
        assertThat(refund.lossQty()).isEqualTo(3);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.REFUNDED);
        List<LossRecord> losses = lossRepository.findByBatchIdOrderByIdAsc(d.near().getId());
        assertThat(losses).anyMatch(l -> l.getReason() == LossReason.SUPPLIER_QUALITY
                && l.getQty() == 3 && "SUPPLIER".equals(l.getLiability()));
        assertThat(ledgerRepository.findByBatchIdOrderByIdAsc(d.near().getId()))
                .anyMatch(l -> l.getLedgerType() == LedgerType.LOSS_OUT
                        && "BATCH_CARE".equals(l.getBizType()));
    }

    @Test
    void 批次全记录可还原下架原因与最终去向() {
        TwoBatches d = setup(1, 4, 0, "A01");
        removalService.scanExpiry(2);
        RemovalOrder task = pendingTaskForBatch(d.near().getId());
        RemovalItem item = removalItemRepository.findByRemovalOrderId(task.getId()).get(0);
        removalService.countAndExecute(task.getOrderNo(), d.replenisher().getUsername(),
                List.of(
                        new RemovalService.CountLine(item.getId(), 2, Disposition.SCRAP, null),
                        new RemovalService.CountLine(item.getId(), 1, Disposition.RECYCLE, null),
                        new RemovalService.CountLine(item.getId(), 1, Disposition.RETURN_SUPPLIER, null)),
                null);

        BatchRecordView record = batchCareService.batchRecord(d.near().getBatchNo());
        assertThat(record.batch().getBatchNo()).isEqualTo(d.near().getBatchNo());
        assertThat(record.removalOrders()).hasSize(1);
        assertThat(record.removalItems()).hasSize(1);
        assertThat(record.removalItems().get(0).getExecutedQty()).isEqualTo(4);
        assertThat(record.losses()).anyMatch(l -> l.getReason() == LossReason.SCRAP_NEAR_EXPIRY);
        assertThat(record.ledgers()).extracting(BatchLedger::getLedgerType)
                .contains(LedgerType.RESTOCK_IN, LedgerType.REMOVAL_OUT);
        // 下架原因留痕
        assertThat(record.alerts()).anyMatch(a -> a.getAlertType() == AlertType.NEAR_EXPIRY
                || a.getAlertType() == AlertType.EXPIRED);
    }

    @Test
    void 手工部分下架只预留指定数量不停整批且其他批次可售() {        TwoBatches d = setup(10, 5, 5, "A01"); // 两个都不临期
        RemovalOrder manual = removalService.createManualOrder(d.cabinet(), RemovalReason.DAMAGED,
                List.of(new RemovalService.ManualRemovalLine("A01", d.near().getBatchNo(), 2)));

        ProductBatch near = batchRepository.findById(d.near().getId()).orElseThrow();
        assertThat(near.isSaleBlocked()).isFalse();              // 未整批停售
        assertThat(stockOf(d.channel, near).getQtyQuarantined()).isEqualTo(2); // 仅预留 2 件
        assertThat(manual.isFullBlock()).isFalse();
        // 货道仍有 8 件可售（3+5）
        int sellable = stockRepository.findSellableByChannelFefo(d.channel().getId()).stream()
                .mapToInt(s -> s.getQtyOnHand() - s.getQtyQuarantined()).sum();
        assertThat(sellable).isEqualTo(8);

        RemovalItem item = removalItemRepository.findByRemovalOrderId(manual.getId()).get(0);
        removalService.countAndExecute(manual.getOrderNo(), d.replenisher().getUsername(),
                List.of(new RemovalService.CountLine(item.getId(), 2, Disposition.SCRAP, null)), null);
        assertThat(stockOf(d.channel, near).getQtyOnHand()).isEqualTo(3);
        assertThat(stockOf(d.channel, near).getQtyQuarantined()).isZero();
    }

    @Test
    void 临期停售扫描与关门扣款并发不超卖且最终账实守恒() throws Exception {
        TwoBatches d = setup(1, 10, 0, "A01");

        int threads = 6;
        CyclicBarrier barrier = new CyclicBarrier(threads + 1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicReference<Throwable> firstError = new AtomicReference<>();

        // 2 个线程尝试关门扣款（各买 5 件，共需 10 件），与扫描停赛跑
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    barrier.await();
                    String sessionNo = "S-CONC-" + idx + "-" + System.nanoTime();
                    sessionService.openDoor(d.cabinet().getCabinetNo(), sessionNo,
                            "U" + idx, "并发用户" + idx, null);
                    sessionService.recordRecognition(sessionNo, d.cabinet().getCabinetNo(), "A01",
                            d.product().getSku(), null, "VISION", "PICK", 5, 0.99,
                            null, null, null, null, null, null);
                    sessionService.recordRecognition(sessionNo, d.cabinet().getCabinetNo(), "A01",
                            d.product().getSku(), null, "RFID", "PICK", 5, 0.99,
                            null, null, null, null, null, null);
                    sessionService.closeDoor(sessionNo, 5 * 260, null);
                } catch (Throwable t) {
                    firstError.compareAndSet(null, t);
                }
            });
        }
        // 其余线程重复扫描（幂等：只有第一个扫描能停售建任务）
        for (int i = 0; i < threads - 2; i++) {
            pool.submit(() -> {
                try {
                    barrier.await();
                    removalService.scanExpiry(2);
                } catch (Throwable t) {
                    firstError.compareAndSet(null, t);
                }
            });
        }
        barrier.await();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        if (firstError.get() != null) {
            throw new AssertionError("并发出错", firstError.get());
        }

        // 最终：库存不为负；总售出 + 剩余在库 = 初始 10
        long soldQty = orderRepository.findByCabinetId(d.cabinet().getId()).stream()
                .filter(o -> o.getStatus() == OrderStatus.CHARGED)
                .flatMap(o -> orderItemRepository.findByOrderId(o.getId()).stream())
                .filter(oi -> oi.getBatch().getId().equals(d.near().getId()))
                .mapToInt(OrderItem::getQty).sum();
        int onHand = stockOf(d.channel, d.near).getQtyOnHand();
        assertThat(soldQty + onHand).isEqualTo(10);
        assertThat(onHand).isGreaterThanOrEqualTo(0);

        // 无论谁先谁后：批次要么已停售（扫描抢到），要么已售罄（两单先结算完）；账实必须守恒
        ProductBatch batch = batchRepository.findById(d.near().getId()).orElseThrow();
        long taskCount = removalOrderRepository.findAll().stream()
                .filter(r -> removalItemRepository.findByRemovalOrderId(r.getId()).stream()
                        .anyMatch(i -> i.getBatch().getId().equals(d.near().getId())))
                .count();
        if (taskCount > 0) {
            assertThat(batch.isSaleBlocked() || batch.getStatus() == BatchStatus.REMOVED).isTrue();
        } else {
            assertThat(onHand).isZero();
        }
        assertThat(taskCount).isLessThanOrEqualTo(1); // 重复扫描幂等：同柜机同批次只建一张任务
    }
}
