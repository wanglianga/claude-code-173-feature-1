package com.cabinet;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.*;
import com.cabinet.dto.EvidenceView;
import com.cabinet.repo.*;
import com.cabinet.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 业务全链路集成测试（H2）：
 * 补货上架 -> 开门多源识别 -> 关门结算(FEFO/台账) -> 投诉退款；
 * 临期扫描下架报损；温控异常锁定隔离；盘点差异调整；供应商结算。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestKafkaConfig.class)
class BusinessFlowIntegrationTest {

    @Autowired private TestFixtures fx;
    @Autowired private RestockService restockService;
    @Autowired private SessionSettlementService sessionService;
    @Autowired private RecognitionEventRepository eventRepository;
    @Autowired private ChannelStockRepository stockRepository;
    @Autowired private BatchLedgerRepository ledgerRepository;
    @Autowired private OrderRecordRepository orderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private RemovalService removalService;
    @Autowired private TemperatureService temperatureService;
    @Autowired private StocktakeService stocktakeService;
    @Autowired private ComplaintService complaintService;
    @Autowired private SettlementService settlementService;
    @Autowired private SettlementItemRepository settlementItemRepository;
    @Autowired private LossRecordRepository lossRepository;
    @Autowired private ProductBatchRepository batchRepository;
    @Autowired private CabinetRepository cabinetRepository;
    @Autowired private ChannelRepository channelRepository;

    private record SetupData(AppUser replenisher, Supplier supplier, Product product,
                             Point point, Cabinet cabinet, Channel channel,
                             ProductBatch batch, String restockNo) {}

    private SetupData restock(int batchExpiryDays, int qty, String channelCode) {
        AppUser user = fx.user("REPLENISHER");
        Supplier supplier = fx.supplier();
        Product product = fx.chilledProduct(supplier);
        Point point = fx.point(LocationType.COMMUNITY);
        Cabinet cabinet = fx.chilledCabinet(point);
        Channel channel = fx.channel(cabinet, product, channelCode);
        ProductBatch batch = fx.batch(product, batchExpiryDays, 0);

        RestockOrder order = restockService.arrive(cabinet.getCabinetNo(),
                user.getUsername(), "photo-before.jpg", null);
        restockService.placeItem(order.getOrderNo(), product.getSku(), batch.getBatchNo(),
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(batchExpiryDays),
                qty, channelCode, "photo-after.jpg", 4.0);
        restockService.complete(order.getOrderNo(), null);
        return new SetupData(user, supplier, product, point, cabinet, channel, batch, order.getOrderNo());
    }

    @Test
    void 补货上架形成库存与批次台账() {
        SetupData d = restock(7, 5, "A01");
        List<ChannelStock> stocks = stockRepository.findByChannelId(d.channel.getId());
        assertThat(stocks).hasSize(1);
        assertThat(stocks.get(0).getQtyOnHand()).isEqualTo(5);
        var ledgers = ledgerRepository.findByBatchIdOrderByIdAsc(d.batch.getId());
        assertThat(ledgers).anyMatch(l -> l.getLedgerType() == LedgerType.RESTOCK_IN
                && l.getChangeQty() == 5 && l.getBalanceQty() == 5);
    }

    @Test
    void 放错货道应产生告警且补货单为不匹配状态() {
        AppUser user = fx.user("REPLENISHER");
        Product p1 = fx.chilledProduct(fx.supplier());
        Product p2 = fx.chilledProduct(fx.supplier());
        Point point = fx.point(LocationType.OFFICE);
        Cabinet cabinet = fx.chilledCabinet(point);
        Channel channel = fx.channel(cabinet, p1, "A01"); // 计划商品 p1
        ProductBatch b2 = fx.batch(p2, 7, 0);

        RestockOrder order = restockService.arrive(cabinet.getCabinetNo(), user.getUsername(), null, null);
        RestockItem item = restockService.placeItem(order.getOrderNo(), p2.getSku(),
                b2.getBatchNo(), LocalDate.now().minusDays(1), LocalDate.now().plusDays(7),
                3, "A01", null, 4.0);
        RestockOrder completed = restockService.complete(order.getOrderNo(), null);

        assertThat(item.getWarning()).isEqualTo(PlacementWarning.WRONG_CHANNEL);
        assertThat(completed.getStatus()).isEqualTo(RestockStatus.COMPLETED_MISMATCH);
    }

    @Test
    void 已过期批次禁止上架() {
        SetupData d = restock(7, 1, "A01");
        ProductBatch expired = fx.batch(d.product, -1, 0);
        RestockOrder order = restockService.arrive(d.cabinet.getCabinetNo(),
                d.replenisher.getUsername(), null, null);
        assertThatThrownBy(() -> restockService.placeItem(order.getOrderNo(),
                d.product.getSku(), expired.getBatchNo(),
                LocalDate.now().minusDays(10), LocalDate.now().minusDays(1),
                1, "A01", null, 4.0))
                .hasMessageContaining("已过期");
    }

    @Test
    void 正常购物关门结算扣库存并生成订单() {
        SetupData d = restock(7, 5, "A01");
        String sessionNo = "S-" + System.nanoTime();
        sessionService.openDoor(d.cabinet.getCabinetNo(), sessionNo, "U001", "用户甲", null);
        sessionService.recordRecognition(sessionNo, d.cabinet.getCabinetNo(), "A01",
                d.product.getSku(), null, "VISION", "PICK", 2, 0.95, null,
                "v.mp4", 1, 3, null, null);
        sessionService.recordRecognition(sessionNo, d.cabinet.getCabinetNo(), "A01",
                d.product.getSku(), null, "RFID", "PICK", 2, 0.99, null,
                "v.mp4", 1, 3, null, null);

        OrderRecord order = sessionService.closeDoor(sessionNo, 520, null);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CHARGED);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("11.00");
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getQty()).isEqualTo(2);
        assertThat(items.get(0).getBatch().getId()).isEqualTo(d.batch.getId());
        // 库存 5-2=3
        assertThat(stockRepository.findByChannelId(d.channel.getId()).get(0).getQtyOnHand())
                .isEqualTo(3);
        // 台账出现 SALE_OUT，余额 3
        var sale = ledgerRepository.findByBatchIdOrderByIdAsc(d.batch.getId()).stream()
                .filter(l -> l.getLedgerType() == LedgerType.SALE_OUT).findFirst().orElseThrow();
        assertThat(sale.getChangeQty()).isEqualTo(-2);
        assertThat(sale.getBalanceQty()).isEqualTo(3);
    }

    @Test
    void 拿起又放回零净拿取应取消订单() {
        SetupData d = restock(7, 5, "A01");
        String sessionNo = "S-" + System.nanoTime();
        sessionService.openDoor(d.cabinet.getCabinetNo(), sessionNo, "U002", "用户乙", null);
        sessionService.recordRecognition(sessionNo, d.cabinet.getCabinetNo(), "A01",
                d.product.getSku(), null, "VISION", "PICK", 1, 0.95, null, null, null, null, null, null);
        sessionService.recordRecognition(sessionNo, d.cabinet.getCabinetNo(), "A01",
                d.product.getSku(), null, "VISION", "PUT", 1, 0.93, null, null, null, null, null, null);
        OrderRecord order = sessionService.closeDoor(sessionNo, 0, null);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stockRepository.findByChannelId(d.channel.getId()).get(0).getQtyOnHand())
                .isEqualTo(5);
    }

    @Test
    void 识别冲突应挂起复核且确认后结算() {
        SetupData d = restock(7, 5, "A01");
        String sessionNo = "S-" + System.nanoTime();
        sessionService.openDoor(d.cabinet.getCabinetNo(), sessionNo, "U003", "用户丙", null);
        // 重量识别 2 件 vs 视觉 1 件 -> 冲突
        sessionService.recordRecognition(sessionNo, d.cabinet.getCabinetNo(), "A01",
                d.product.getSku(), null, "WEIGHT", "PICK", 2, 0.9, 520, null, null, null, null, null);
        sessionService.recordRecognition(sessionNo, d.cabinet.getCabinetNo(), "A01",
                d.product.getSku(), null, "VISION", "PICK", 1, 0.8, null, null, null, null, null, null);
        OrderRecord pending = sessionService.closeDoor(sessionNo, 260, null);
        assertThat(pending.getStatus()).isEqualTo(OrderStatus.PENDING_REVIEW);
        // 尚未扣库存
        assertThat(stockRepository.findByChannelId(d.channel.getId()).get(0).getQtyOnHand())
                .isEqualTo(5);

        // 人工确认按 1 件结算
        OrderRecord charged = sessionService.confirmReview(sessionNo,
                List.of(new SessionSettlementService.ConfirmedLine("A01", d.product.getSku(), 1)),
                "视频确认仅拿 1 瓶");
        assertThat(charged.getStatus()).isEqualTo(OrderStatus.CHARGED);
        assertThat(charged.getTotalAmount()).isEqualByComparingTo("5.50");
        assertThat(stockRepository.findByChannelId(d.channel.getId()).get(0).getQtyOnHand())
                .isEqualTo(4);
    }

    @Test
    void 温控异常锁定柜机拒绝开门并隔离库存() {
        SetupData d = restock(7, 5, "A01");
        // 12℃ 越限（冷藏上限 8℃）
        temperatureService.record(d.cabinet, 12.0, java.time.LocalDateTime.now());
        assertThat(d.cabinet.getStatus()).isEqualTo(CabinetStatus.ALARM_LOCKED);

        String sessionNo = "S-" + System.nanoTime();
        DoorSession rejected = sessionService.openDoor(d.cabinet.getCabinetNo(),
                sessionNo, "U004", "用户丁", null);
        assertThat(rejected.getStatus()).isEqualTo(SessionStatus.CANCELLED);
        assertThat(rejected.getSettleNote()).contains("温控");

        // 持续越限超过 30 分钟 -> 隔离
        temperatureService.record(d.cabinet, 13.0,
                java.time.LocalDateTime.now().plusMinutes(31));
        ChannelStock s = stockRepository.findByChannelId(d.channel.getId()).get(0);
        assertThat(s.getQtyQuarantined()).isEqualTo(5);
    }

    @Test
    void 临期扫描生成下架单执行后报损并记账() {
        SetupData d = restock(1, 4, "A01"); // 明天到期
        int created = removalService.scanExpiry(2);
        assertThat(created).isGreaterThanOrEqualTo(1);
        // 找到该柜机的待执行下架单
        RemovalOrder target = findPendingRemovalForBatch(d.batch.getId());
        assertThat(target).isNotNull();
        removalService.execute(target.getOrderNo(), d.replenisher.getUsername(),
                Disposition.SCRAP, null);

        assertThat(stockRepository.findByChannelId(d.channel.getId()).get(0).getQtyOnHand())
                .isZero();
        List<LossRecord> losses = lossRepository.findByBatchIdOrderByIdAsc(d.batch.getId());
        assertThat(losses).hasSize(1);
        assertThat(losses.get(0).getReason()).isEqualTo(LossReason.SCRAP_NEAR_EXPIRY);
        assertThat(losses.get(0).getLiability()).isEqualTo("SUPPLIER");
        assertThat(batchRepository.findById(d.batch.getId()).orElseThrow().getStatus())
                .isEqualTo(BatchStatus.REMOVED);
    }

    @Test
    void 盘点盘亏审批后调整库存并登记报损() {
        SetupData d = restock(7, 5, "A01");
        StocktakeOrder st = stocktakeService.create(d.cabinet.getCabinetNo(),
                d.replenisher.getUsername());
        stocktakeService.count(st.getOrderNo(), List.of(
                new StocktakeService.CountLine("A01", d.batch.getBatchNo(), 4)));
        StocktakeOrder pending = stocktakeOrderRepository.findByOrderNo(st.getOrderNo())
                .orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(StocktakeStatus.PENDING_ADJUST);
        assertThat(pending.getTotalDiff()).isEqualTo(1);

        stocktakeService.adjust(st.getOrderNo(), true, "实盘 4 件");
        assertThat(stockRepository.findByChannelId(d.channel.getId()).get(0).getQtyOnHand())
                .isEqualTo(4);
        List<LossRecord> losses = lossRepository.findByBatchIdOrderByIdAsc(d.batch.getId());
        assertThat(losses).anyMatch(l -> l.getReason() == LossReason.SHRINKAGE && l.getQty() == 1);
        assertThat(ledgerRepository.findByBatchIdOrderByIdAsc(d.batch.getId()))
                .anyMatch(l -> l.getLedgerType() == LedgerType.ADJUST_MINUS && l.getChangeQty() == -1);
    }

    @Test
    void 投诉整单退款商品未收回应报损并写退款台账() {
        SetupData d = restock(7, 5, "A01");
        String sessionNo = "S-" + System.nanoTime();
        sessionService.openDoor(d.cabinet.getCabinetNo(), sessionNo, "U005", "投诉用户", null);
        sessionService.recordRecognition(sessionNo, d.cabinet.getCabinetNo(), "A01",
                d.product.getSku(), null, "VISION", "PICK", 1, 0.99, null,
                "clip.mp4", 0, 2, null, null);
        sessionService.recordRecognition(sessionNo, d.cabinet.getCabinetNo(), "A01",
                d.product.getSku(), null, "RFID", "PICK", 1, 0.99, null,
                "clip.mp4", 0, 2, null, null);
        OrderRecord order = sessionService.closeDoor(sessionNo, 260, null);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CHARGED);

        AppUser cs = fx.user("CUSTOMER_SERVICE");
        Complaint complaint = complaintService.submit(sessionNo, "误扣款", "我放回了仍被扣费");
        EvidenceView evidence = complaintService.evidence(complaint.getComplaintNo());
        assertThat(evidence.session().getSessionNo()).isEqualTo(sessionNo);
        assertThat(evidence.recognitionEvents()).hasSize(2);
        assertThat(evidence.orderItems()).hasSize(1);
        assertThat(evidence.batchLedgers())
                .anyMatch(l -> l.getLedgerType() == LedgerType.SALE_OUT);
        assertThat(evidence.tempLogs()).isNotNull();

        Complaint resolved = complaintService.resolve(complaint.getComplaintNo(),
                cs.getUsername(), true, null, false, false, "视频显示已放回，整单退款");
        assertThat(resolved.getStatus()).isEqualTo(ComplaintStatus.RESOLVED_REFUNDED);
        assertThat(resolved.getRefundAmount()).isEqualByComparingTo("5.50");

        OrderRecord refunded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(refunded.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(refunded.getRefundedAmount()).isEqualByComparingTo("5.50");
        // 商品未收回：库存不回补，登记 REFUND_NO_RETURN 报损与 LOSS_OUT
        assertThat(stockRepository.findByChannelId(d.channel.getId()).get(0).getQtyOnHand())
                .isEqualTo(4);
        assertThat(lossRepository.findByBatchIdOrderByIdAsc(d.batch.getId()))
                .anyMatch(l -> l.getReason() == LossReason.REFUND_NO_RETURN && l.getQty() == 1);
    }

    @Test
    void 退款商品收回应回补库存并记REFUND_RETURN() {
        SetupData d = restock(7, 5, "A01");
        String sessionNo = "S-" + System.nanoTime();
        sessionService.openDoor(d.cabinet.getCabinetNo(), sessionNo, "U006", "用户己", null);
        sessionService.recordRecognition(sessionNo, d.cabinet.getCabinetNo(), "A01",
                d.product.getSku(), null, "VISION", "PICK", 1, 0.99, null, null, null, null, null, null);
        sessionService.recordRecognition(sessionNo, d.cabinet.getCabinetNo(), "A01",
                d.product.getSku(), null, "RFID", "PICK", 1, 0.99, null, null, null, null, null, null);
        OrderRecord order = sessionService.closeDoor(sessionNo, 260, null);

        AppUser cs = fx.user("CUSTOMER_SERVICE");
        Complaint c = complaintService.submit(sessionNo, "临期变质", "牛奶变味");
        complaintService.resolve(c.getComplaintNo(), cs.getUsername(),
                true, null, true, false, "商品收回，退款并报损由下架流程处理");
        assertThat(stockRepository.findByChannelId(d.channel.getId()).get(0).getQtyOnHand())
                .isEqualTo(5);
        assertThat(ledgerRepository.findByBatchIdOrderByIdAsc(d.batch.getId()))
                .anyMatch(l -> l.getLedgerType() == LedgerType.REFUND_RETURN && l.getChangeQty() == 1);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    void 供应商结算按真实售卖与供应商责任报损() {
        SetupData d = restock(7, 10, "A01");
        // 售出 3 件
        String sessionNo = "S-" + System.nanoTime();
        sessionService.openDoor(d.cabinet.getCabinetNo(), sessionNo, "U007", "用户庚", null);
        sessionService.recordRecognition(sessionNo, d.cabinet.getCabinetNo(), "A01",
                d.product.getSku(), null, "VISION", "PICK", 3, 0.99, null, null, null, null, null, null);
        sessionService.recordRecognition(sessionNo, d.cabinet.getCabinetNo(), "A01",
                d.product.getSku(), null, "RFID", "PICK", 3, 0.99, null, null, null, null, null, null);
        sessionService.closeDoor(sessionNo, 780, null);
        // 临期下架报废 2 件（供应商责任）
        ProductBatch fresh = d.batch;
        // 构造同批次下架
        RemovalOrder rm = removalService.createManualOrder(d.cabinet, RemovalReason.EXPIRED,
                List.of(new RemovalService.ManualRemovalLine("A01", d.batch.getBatchNo(), 2)));
        removalService.execute(rm.getOrderNo(), d.replenisher.getUsername(), Disposition.SCRAP, null);

        LocalDate today = LocalDate.now();
        SettlementBatch stl = settlementService.generate(d.supplier.getId(),
                today.minusDays(1), today.plusDays(1));
        assertThat(stl.getSoldQty()).isEqualTo(3);
        assertThat(stl.getLossQty()).isEqualTo(2);
        // 售卖成本 3*2.8=8.4，报损 2*2.8=5.6，应付 2.8
        assertThat(stl.getPayableAmount()).isEqualByComparingTo("2.80");
        assertThat(stl.getStatus()).isEqualTo(SettlementStatus.DRAFT);

        settlementService.confirm(stl.getSettlementNo());
        settlementService.markPaid(stl.getSettlementNo());
        List<SettlementItem> items = settlementItemRepository.findBySettlementId(stl.getId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getSoldQty()).isEqualTo(3);
        assertThat(items.get(0).getLossQty()).isEqualTo(2);
    }

    private RemovalOrder findPendingRemovalForBatch(Long batchId) {
        return removalOrderRepository.findAll().stream()
                .filter(r -> r.getStatus() == RemovalStatus.PENDING)
                .filter(r -> removalItemRepository.findByRemovalOrderId(r.getId()).stream()
                        .anyMatch(i -> i.getBatch().getId().equals(batchId)))
                .findFirst().orElse(null);
    }

    @Autowired private RemovalOrderRepository removalOrderRepository;
    @Autowired private StocktakeOrderRepository stocktakeOrderRepository;
    @Autowired private RemovalItemRepository removalItemRepository;
}
