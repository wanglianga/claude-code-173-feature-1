package com.cabinet;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.*;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 临期自动下架新链路集成测试（H2）：
 * - 批次进入临期范围即停售，同货道其他批次照常售卖；
 * - 补货员扫码领取、清点报损/回收/退供/仍可售四种去向；
 * - 开门在途与停售并发：库存/订单/下架结果对得上，差异留痕；
 * - 逾期任务限制批次曝光，运营可解封；
 * - 柜机离线后上线同步停售清单；
 * - 客服按批次找订单、供应商责任退款；批次档案可还原去向。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestKafkaConfig.class)
class NearExpiryRemovalFlowTest {

    @Autowired private TestFixtures fx;
    @Autowired private RestockService restockService;
    @Autowired private SessionSettlementService sessionService;
    @Autowired private RemovalService removalService;
    @Autowired private AnalyticsService analyticsService;
    @Autowired private ComplaintService complaintService;
    @Autowired private ChannelStockRepository stockRepository;
    @Autowired private ProductBatchRepository batchRepository;
    @Autowired private RemovalOrderRepository removalOrderRepository;
    @Autowired private RemovalItemRepository removalItemRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private LossRecordRepository lossRepository;
    @Autowired private BatchLedgerRepository ledgerRepository;
    @Autowired private AlertRepository alertRepository;

    private record Ctx(AppUser user, Supplier supplier, Product product,
                       Cabinet cabinet, Channel channel) {}

    private Ctx base() {
        AppUser user = fx.user("REPLENISHER");
        Supplier supplier = fx.supplier();
        Product product = fx.chilledProduct(supplier);
        Point point = fx.point(LocationType.COMMUNITY);
        Cabinet cabinet = fx.chilledCabinet(point);
        Channel channel = fx.channel(cabinet, product, "A01");
        return new Ctx(user, supplier, product, cabinet, channel);
    }

    private ProductBatch stock(Ctx c, String batchNo, int expiryInDays, int qty) {
        ProductBatch batch = ProductBatch.builder()
                .batchNo(batchNo).product(c.product()).supplier(c.supplier())
                .productionDate(LocalDate.now().minusDays(1))
                .expiryDate(LocalDate.now().plusDays(expiryInDays))
                .receivedQty(qty).costPrice(c.product().getCostPrice())
                .status(BatchStatus.IN_STOCK).build();
        batchRepository.save(batch);
        RestockOrder o = restockService.arrive(c.cabinet().getCabinetNo(),
                c.user().getUsername(), null, null);
        restockService.placeItem(o.getOrderNo(), c.product().getSku(), batchNo,
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(expiryInDays),
                qty, "A01", null, 4.0);
        restockService.complete(o.getOrderNo(), null);
        return batch;
    }

    private RemovalOrder pendingTaskFor(Long batchId) {
        return removalOrderRepository.findAll().stream()
                .filter(r -> r.getStatus() == RemovalStatus.PENDING)
                .filter(r -> removalItemRepository.findByRemovalOrderId(r.getId()).stream()
                        .anyMatch(i -> i.getBatch().getId().equals(batchId)))
                .findFirst().orElseThrow();
    }

    @Test
    void 临期扫描立即按批次停售但同货道其他批次继续售卖() {
        Ctx c = base();
        ProductBatch near = stock(c, "B-NEAR", 1, 3);
        ProductBatch fresh = stock(c, "B-FRESH", 6, 3);

        int created = removalService.scanExpiry(2);
        assertThat(created).isGreaterThanOrEqualTo(1);

        ProductBatch halted = batchRepository.findById(near.getId()).orElseThrow();
        assertThat(halted.isSaleHalted()).isTrue();
        assertThat(halted.getHaltReason()).isEqualTo("NEAR_EXPIRY");
        ChannelStock nearStock = stockRepository
                .findByChannelIdAndBatchId(c.channel().getId(), near.getId()).orElseThrow();
        assertThat(nearStock.getQtySaleHalted()).isEqualTo(3);
        assertThat(nearStock.sellableQty()).isZero();

        // 同货道新鲜批次不受影响
        ChannelStock freshStock = stockRepository
                .findByChannelIdAndBatchId(c.channel().getId(), fresh.getId()).orElseThrow();
        assertThat(freshStock.sellableQty()).isEqualTo(3);

        // 购买 2 瓶：FEFO 跳过停售批次，扣的是新鲜批次
        String sn = "S-" + System.nanoTime();
        sessionService.openDoor(c.cabinet().getCabinetNo(), sn, "U1", "用户", null);
        sessionService.recordRecognition(sn, c.cabinet().getCabinetNo(), "A01",
                c.product().getSku(), null, "VISION", "PICK", 2, 0.99,
                null, null, null, null, null, null);
        sessionService.recordRecognition(sn, c.cabinet().getCabinetNo(), "A01",
                c.product().getSku(), null, "RFID", "PICK", 2, 0.99,
                null, null, null, null, null, null);
        OrderRecord order = sessionService.closeDoor(sn, 520, null);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CHARGED);
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getBatch().getId()).isEqualTo(fresh.getId());
        // 服务事务提交后重新读取库存
        ChannelStock freshAfter = stockRepository
                .findByChannelIdAndBatchId(c.channel().getId(), fresh.getId()).orElseThrow();
        ChannelStock nearAfter = stockRepository
                .findByChannelIdAndBatchId(c.channel().getId(), near.getId()).orElseThrow();
        assertThat(freshAfter.getQtyOnHand()).isEqualTo(1);
        // 临期批次原封不动
        assertThat(nearAfter.getQtyOnHand()).isEqualTo(3);
        // 台账记录停售
        assertThat(ledgerRepository.findByBatchIdOrderByIdAsc(near.getId()))
                .anyMatch(l -> l.getLedgerType() == LedgerType.SALE_BLOCK);
    }

    @Test
    void 补货员扫码领取后清点四种去向并逐笔记账() {
        Ctx c = base();
        ProductBatch b = stock(c, "B-DISP", 1, 5);
        removalService.scanExpiry(2);
        RemovalOrder task = pendingTaskFor(b.getId());

        removalService.accept(task.getOrderNo(), c.user().getUsername());
        RemovalItem item = removalItemRepository.findByRemovalOrderId(task.getId()).get(0);

        // 实点 5：报损2 回收1 退供1 仍可售1
        RemovalOrder done = removalService.countAndExecute(task.getOrderNo(),
                c.user().getUsername(),
                List.of(new RemovalService.CountLine(item.getId(), 5, 2, 1, 1, 1, "现场清点")),
                null);

        assertThat(done.getStatus()).isEqualTo(RemovalStatus.EXECUTED);
        assertThat(done.getCountedTotal()).isEqualTo(5);
        ChannelStock s = stockRepository
                .findByChannelIdAndBatchId(c.channel().getId(), b.getId()).orElseThrow();
        // 出库 4 件（2+1+1），仍可售 1 件留在货道恢复售卖
        assertThat(s.getQtyOnHand()).isEqualTo(1);
        assertThat(s.getQtySaleHalted()).isZero();
        assertThat(s.sellableQty()).isEqualTo(1);

        List<LossRecord> losses = lossRepository.findByBatchIdOrderByIdAsc(b.getId());
        assertThat(losses).hasSize(1);
        assertThat(losses.get(0).getReason()).isEqualTo(LossReason.SCRAP_NEAR_EXPIRY);
        assertThat(losses.get(0).getQty()).isEqualTo(2);
        assertThat(losses.get(0).getLiability()).isEqualTo("SUPPLIER");

        var ledgers = ledgerRepository.findByBatchIdOrderByIdAsc(b.getId());
        assertThat(ledgers).anyMatch(l -> l.getLedgerType() == LedgerType.SALE_BLOCK);
        assertThat(ledgers).filteredOn(l -> l.getLedgerType() == LedgerType.REMOVAL_OUT)
                .extracting(BatchLedger::getChangeQty).containsExactlyInAnyOrder(-2, -1, -1);
        assertThat(ledgers).anyMatch(l -> l.getLedgerType() == LedgerType.SALE_UNBLOCK);
        // 仍有可售库存，批次不关闭
        assertThat(batchRepository.findById(b.getId()).orElseThrow().getStatus())
                .isEqualTo(BatchStatus.IN_STOCK);
    }

    @Test
    void 去向数量与实点不一致应拒绝执行() {
        Ctx c = base();
        ProductBatch b = stock(c, "B-BAD", 1, 3);
        removalService.scanExpiry(2);
        RemovalOrder task = pendingTaskFor(b.getId());
        RemovalItem item = removalItemRepository.findByRemovalOrderId(task.getId()).get(0);

        assertThatThrownBy(() -> removalService.countAndExecute(task.getOrderNo(),
                c.user().getUsername(),
                List.of(new RemovalService.CountLine(item.getId(), 3, 3, 1, 0, 0, null)), null))
                .hasMessageContaining("去向数量合计");
        // 未执行、库存未动
        ChannelStock s = stockRepository
                .findByChannelIdAndBatchId(c.channel().getId(), b.getId()).orElseThrow();
        assertThat(s.getQtyOnHand()).isEqualTo(3);
        assertThat(s.getQtySaleHalted()).isEqualTo(3);
    }

    @Test
    void 开门在途期间批次停售关门结算后清点差异自动核销() {
        Ctx c = base();
        ProductBatch b = stock(c, "B-INFLIGHT", 1, 2);

        // 1) 用户已开门并拿起 2 件（关门结算尚未发生）
        String sn = "S-" + System.nanoTime();
        sessionService.openDoor(c.cabinet().getCabinetNo(), sn, "U2", "在途用户", null);
        sessionService.recordRecognition(sn, c.cabinet().getCabinetNo(), "A01",
                c.product().getSku(), null, "VISION", "PICK", 2, 0.99,
                null, null, null, null, null, null);
        sessionService.recordRecognition(sn, c.cabinet().getCabinetNo(), "A01",
                c.product().getSku(), null, "RFID", "PICK", 2, 0.99,
                null, null, null, null, null, null);

        // 2) 此时临期扫描产生：服务端先停售 2 件（柜机离线/柜门已开，摘品指令没赶上）
        removalService.scanExpiry(2);
        RemovalOrder task = pendingTaskFor(b.getId());
        assertThat(stockRepository.findByChannelIdAndBatchId(c.channel().getId(), b.getId())
                .orElseThrow().getQtySaleHalted()).isEqualTo(2);

        // 3) 关门结算：正常可售为 0，从停售量扣减，订单成立并产生风险提醒
        OrderRecord order = sessionService.closeDoor(sn, 520, null);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CHARGED);
        ChannelStock s = stockRepository
                .findByChannelIdAndBatchId(c.channel().getId(), b.getId()).orElseThrow();
        assertThat(s.getQtyOnHand()).isZero();
        assertThat(s.getQtySaleHalted()).isZero();
        assertThat(alertRepository.findAll()).anyMatch(a -> a.getAlertType() == AlertType.BATCH_RISK);

        // 4) 补货员到场清点：实物 0 件，系统停售账 2 件 -> 差异 2 自动核销并告警，不重复出库
        RemovalItem item = removalItemRepository.findByRemovalOrderId(task.getId()).get(0);
        RemovalOrder done = removalService.countAndExecute(task.getOrderNo(),
                c.user().getUsername(),
                List.of(new RemovalService.CountLine(item.getId(), 0, 0, 0, 0, 0,
                        "已被在途订单买走")), null);
        assertThat(done.getStatus()).isEqualTo(RemovalStatus.EXECUTED);
        assertThat(alertRepository.findAll())
                .anyMatch(a -> a.getAlertType() == AlertType.STOCK_DISCREPANCY);
        // 实物已被在途订单买走，批次关闭；状态为 SOLD_OUT（风险售出），台账完整保留停售与售出记录
        assertThat(batchRepository.findById(b.getId()).orElseThrow().getStatus())
                .isIn(BatchStatus.REMOVED, BatchStatus.SOLD_OUT);

        // 5) 风险订单可被客服按批次查到并标记
        var sales = analyticsService.batchSales(b.getBatchNo());
        assertThat(sales).hasSize(1);
        assertThat(sales.get(0).risk()).isTrue();
        assertThat(sales.get(0).orderNo()).isEqualTo(order.getOrderNo());
    }

    @Test
    void 任务逾期未处理应进入逾期列表并限制批次曝光() {
        Ctx c = base();
        ProductBatch b = stock(c, "B-OVD", 1, 2);
        removalService.scanExpiry(2);
        RemovalOrder task = pendingTaskFor(b.getId());

        // 把时限改到过去，模拟补货员迟迟不处理
        task.setDueAt(LocalDateTime.now().minusHours(1));
        removalOrderRepository.save(task);

        int n = removalService.markOverdue();
        assertThat(n).isGreaterThanOrEqualTo(1);
        RemovalOrder marked = removalOrderRepository.findById(task.getId()).orElseThrow();
        assertThat(marked.getOverdueMarkedAt()).isNotNull();
        assertThat(batchRepository.findById(b.getId()).orElseThrow().isExposureBlocked()).isTrue();
        assertThat(removalService.deviceSync(c.cabinet().getCabinetNo()).haltedBatches())
                .anyMatch(h -> h.batchNo().equals("B-OVD") && h.exposureBlocked());

        // 运营核实后解封
        removalService.releaseExposure("B-OVD", "现场已处置");
        assertThat(batchRepository.findById(b.getId()).orElseThrow().isExposureBlocked()).isFalse();
    }

    @Test
    void 柜机离线期间产生停售上线同步可见且扫描幂等() {
        Ctx c = base();
        ProductBatch b = stock(c, "B-SYNC", 1, 4);
        // 柜机“离线”，服务端照常扫描停售
        removalService.scanExpiry(2);
        var view = removalService.deviceSync(c.cabinet().getCabinetNo());
        assertThat(view.haltedBatches()).hasSize(1);
        assertThat(view.haltedBatches().get(0).batchNo()).isEqualTo("B-SYNC");
        assertThat(view.activeTasks()).hasSize(1);

        // 再次扫描不重复生成任务
        int again = removalService.scanExpiry(2);
        assertThat(again).isZero();
    }

    @Test
    void 客服按批次找订单并判供应商责任退款计入结算扣减() {
        Ctx c = base();
        ProductBatch b = stock(c, "B-CS", 6, 2);
        String sn = "S-" + System.nanoTime();
        sessionService.openDoor(c.cabinet().getCabinetNo(), sn, "U3", "投诉用户", null);
        sessionService.recordRecognition(sn, c.cabinet().getCabinetNo(), "A01",
                c.product().getSku(), null, "VISION", "PICK", 1, 0.99,
                null, null, null, null, null, null);
        sessionService.recordRecognition(sn, c.cabinet().getCabinetNo(), "A01",
                c.product().getSku(), null, "RFID", "PICK", 1, 0.99,
                null, null, null, null, null, null);
        sessionService.closeDoor(sn, 260, null);

        var sales = analyticsService.batchSales(b.getBatchNo());
        assertThat(sales).hasSize(1);

        AppUser cs = fx.user("CUSTOMER_SERVICE");
        Complaint complaint = complaintService.submit(sn, "临期变质", "牛奶变味");
        complaintService.resolve(complaint.getComplaintNo(), cs.getUsername(),
                true, null, false, true, "供应商质量责任，退款商品未收回");

        LossRecord loss = lossRepository.findByBatchIdOrderByIdAsc(b.getId()).stream()
                .filter(l -> l.getReason() == LossReason.REFUND_NO_RETURN).findFirst().orElseThrow();
        assertThat(loss.getLiability()).isEqualTo("SUPPLIER");
    }

    @Test
    void 批次处置档案可还原下架原因与实物去向() {
        Ctx c = base();
        ProductBatch b = stock(c, "B-REC", 1, 4);
        removalService.scanExpiry(2);
        RemovalOrder task = pendingTaskFor(b.getId());
        RemovalItem item = removalItemRepository.findByRemovalOrderId(task.getId()).get(0);
        removalService.countAndExecute(task.getOrderNo(), c.user().getUsername(),
                List.of(new RemovalService.CountLine(item.getId(), 4, 1, 1, 1, 1, null)), null);

        AnalyticsService.BatchRecordView record = analyticsService.batchRecord(b.getBatchNo());
        assertThat(record.batch().getBatchNo()).isEqualTo("B-REC");
        assertThat(record.disposals()).hasSize(1);
        AnalyticsService.DisposalView d = record.disposals().get(0);
        assertThat(d.scrapQty()).isEqualTo(1);
        assertThat(d.recycleQty()).isEqualTo(1);
        assertThat(d.returnSupplierQty()).isEqualTo(1);
        assertThat(d.saleableQty()).isEqualTo(1);
        assertThat(d.reason()).isEqualTo("NEAR_EXPIRY");
        assertThat(record.ledgers()).isNotEmpty();
    }
}
