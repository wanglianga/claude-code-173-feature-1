package com.cabinet.service;

import com.cabinet.domain.*;
import com.cabinet.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
/**
 * 运营统计：按点位汇总销量、销售额、损耗（数量/成本）与临期率，
 * 支撑按点位调整品类与补货计划；供应商/运营可按批次查看损耗原因与全链路台账。
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private static final int NEAR_EXPIRY_DAYS = 2;

    private final CabinetRepository cabinetRepository;
    private final LossRecordRepository lossRepository;
    private final ChannelStockRepository stockRepository;
    private final ProductBatchRepository batchRepository;
    private final BatchLedgerRepository ledgerRepository;
    private final OrderRecordRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final RemovalOrderRepository removalOrderRepository;
    private final RemovalItemRepository removalItemRepository;

    public record PointStat(String pointCode, String pointName, String type,
                            long cabinetCount, int soldQty, BigDecimal soldAmount,
                            int lossQty, BigDecimal lossCost,
                            int nearExpiryQty, int onHandQty,
                            BigDecimal lossRate, BigDecimal nearExpiryRate) {}

    @Transactional(readOnly = true)
    public List<PointStat> pointStats(LocalDate from, LocalDate to) {
        LocalDateTime fromT = from.atStartOfDay();
        LocalDateTime toT = to.plusDays(1).atStartOfDay();
        LocalDate nearLine = LocalDate.now().plusDays(NEAR_EXPIRY_DAYS);

        Map<Long, Point> pointMap = new LinkedHashMap<>();
        Map<Long, List<Cabinet>> cabinetsByPoint = new LinkedHashMap<>();
        for (Cabinet c : cabinetRepository.findAll()) {
            if (c.getPoint() == null) continue;
            pointMap.put(c.getPoint().getId(), c.getPoint());
            cabinetsByPoint.computeIfAbsent(c.getPoint().getId(), k -> new ArrayList<>()).add(c);
        }

        List<PointStat> result = new ArrayList<>();
        for (var entry : cabinetsByPoint.entrySet()) {
            Point p = pointMap.get(entry.getKey());
            List<Cabinet> cabinets = entry.getValue();
            List<Long> cabinetIds = cabinets.stream().map(Cabinet::getId).toList();

            int soldQty = 0;
            BigDecimal soldAmount = BigDecimal.ZERO;
            for (OrderRecord order : orderRepository.findByCabinetIdIn(cabinetIds)) {
                LocalDateTime t = order.getChargedAt();
                if (t == null || t.isBefore(fromT) || !t.isBefore(toT)) continue;
                List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
                for (OrderItem oi : items) {
                    soldQty += oi.getQty() - oi.getRefundedQty();
                }
                // 净销售额 = 单额 - 已退
                soldAmount = soldAmount.add(order.getTotalAmount())
                        .subtract(order.getRefundedAmount());
            }

            int lossQty = 0;
            BigDecimal lossCost = BigDecimal.ZERO;
            for (Long cid : cabinetIds) {
                for (LossRecord lr : lossRepository.findByCabinetId(cid)) {
                    LocalDateTime t = lr.getCreatedAt();
                    if (t.isBefore(fromT) || !t.isBefore(toT)) continue;
                    lossQty += lr.getQty();
                    lossCost = lossCost.add(lr.getCostAmount() != null
                            ? lr.getCostAmount() : BigDecimal.ZERO);
                }
            }

            int onHand = 0;
            int nearExpiry = 0;
            for (Long cid : cabinetIds) {
                for (ChannelStock s : stockRepository.findByCabinetId(cid)) {
                    onHand += s.getQtyOnHand();
                    LocalDate exp = s.getBatch().getExpiryDate();
                    if (s.getQtyOnHand() > 0 && exp != null && !exp.isAfter(nearLine)) {
                        nearExpiry += s.getQtyOnHand();
                    }
                }
            }

            int throughput = soldQty + lossQty;
            BigDecimal lossRate = throughput == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(lossQty * 100.0 / throughput)
                            .setScale(2, RoundingMode.HALF_UP);
            BigDecimal nearRate = onHand == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(nearExpiry * 100.0 / onHand)
                            .setScale(2, RoundingMode.HALF_UP);

            result.add(new PointStat(p.getCode(), p.getName(), p.getType().name(),
                    cabinets.size(), soldQty, soldAmount, lossQty, lossCost,
                    nearExpiry, onHand, lossRate, nearRate));
        }
        return result;
    }

    /** 批次损耗明细（供应商/运营视角，含原因与责任归属） */
    @Transactional(readOnly = true)
    public List<LossRecord> batchLosses(String batchNo) {
        ProductBatch batch = batchRepository.findByBatchNo(batchNo)
                .orElseThrow(() -> new IllegalArgumentException("批次不存在: " + batchNo));
        return lossRepository.findByBatchIdOrderByIdAsc(batch.getId());
    }

    /**
     * 按批次查到所有购买订单（客服售后/召回用）：
     * 精确到订单号、开门会话、柜机、用户、购买与已退数量；
     * 风险订单（在批次停售之后或到期日当天及以后成交）会被标出，便于主动风险提醒与供应商追责。
     */
    @Transactional(readOnly = true)
    public List<BatchSaleView> batchSales(String batchNo) {
        ProductBatch batch = batchRepository.findByBatchNo(batchNo)
                .orElseThrow(() -> new IllegalArgumentException("批次不存在: " + batchNo));
        List<BatchSaleView> views = new ArrayList<>();
        for (OrderItem oi : orderItemRepository.findByBatchId(batch.getId())) {
            OrderRecord o = oi.getOrder();
            LocalDateTime chargedAt = o.getChargedAt();
            boolean risk = chargedAt != null && (
                    (batch.getSaleHaltedAt() != null && !chargedAt.isBefore(batch.getSaleHaltedAt()))
                    || (batch.getExpiryDate() != null
                        && !chargedAt.toLocalDate().isBefore(batch.getExpiryDate())));
            views.add(new BatchSaleView(
                    o.getOrderNo(),
                    o.getSession() != null ? o.getSession().getSessionNo() : null,
                    o.getCabinet() != null ? o.getCabinet().getCabinetNo() : null,
                    o.getUserCode(), chargedAt,
                    oi.getProduct().getSku(), oi.getQty(), oi.getRefundedQty(),
                    o.getStatus() == null ? null : o.getStatus().name(),
                    oi.getSubtotal(), risk));
        }
        views.sort(Comparator.comparing(BatchSaleView::chargedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return views;
    }

    /** 全量风险订单：售出已停售/已临期过期批次的订单，供客服主动风险提醒 */
    @Transactional(readOnly = true)
    public List<BatchSaleView> riskSales() {
        List<BatchSaleView> all = new ArrayList<>();
        for (ProductBatch b : batchRepository.findAll()) {
            if (!b.isSaleHalted() && !b.isExposureBlocked()) continue;
            all.addAll(batchSales(b.getBatchNo()));
        }
        return all.stream().filter(BatchSaleView::risk).toList();
    }

    /**
     * 批次处置档案：还原一批商品“为什么被下架、最终去了哪里”。
     * 聚合批次状态/停售标记、台账流水、报损记录、下架清点明细与结算明细。
     */
    @Transactional(readOnly = true)
    public BatchRecordView batchRecord(String batchNo) {
        ProductBatch batch = batchRepository.findByBatchNo(batchNo)
                .orElseThrow(() -> new IllegalArgumentException("批次不存在: " + batchNo));
        List<BatchLedger> ledgers = ledgerRepository.findByBatchIdOrderByIdAsc(batch.getId());
        List<LossRecord> losses = lossRepository.findByBatchIdOrderByIdAsc(batch.getId());
        List<BatchSaleView> sales = batchSales(batchNo);
        List<DisposalView> disposals = new ArrayList<>();
        for (RemovalItem ri : removalItemRepository.findByBatchId(batch.getId())) {
            RemovalOrder ro = ri.getRemovalOrder();
            disposals.add(new DisposalView(
                    ro.getOrderNo(),
                    ro.getCabinet() != null ? ro.getCabinet().getCabinetNo() : null,
                    ri.getChannel() != null ? ri.getChannel().getCode() : null,
                    ro.getReason() == null ? null : ro.getReason().name(),
                    ro.getStatus() == null ? null : ro.getStatus().name(),
                    nz(ri.getPlannedQty()), nz(ri.getCountedQty()),
                    nz(ri.getScrapQty()), nz(ri.getRecycleQty()),
                    nz(ri.getReturnSupplierQty()), nz(ri.getSaleableQty()),
                    ro.getScheduledAt(), ro.getExecutedAt(),
                    ro.getOverdueMarkedAt() != null, ri.getRemark()));
        }
        return new BatchRecordView(batch, ledgers, losses, sales, disposals);
    }

    /** 批次全链路台账：补货/售卖/停售/解封/下架/报损/盘点/退款/结算 */
    @Transactional(readOnly = true)
    public List<BatchLedger> batchTrace(String batchNo) {
        ProductBatch batch = batchRepository.findByBatchNo(batchNo)
                .orElseThrow(() -> new IllegalArgumentException("批次不存在: " + batchNo));
        return ledgerRepository.findByBatchIdOrderByIdAsc(batch.getId());
    }

    /** 批次购买订单视图（含风险标记） */
    public record BatchSaleView(String orderNo, String sessionNo, String cabinetNo,
                                String userCode, LocalDateTime chargedAt,
                                String sku, int qty, int refundedQty,
                                String orderStatus, BigDecimal subtotal, boolean risk) {}

    /** 批次处置档案 */
    public record BatchRecordView(ProductBatch batch,
                                  List<BatchLedger> ledgers,
                                  List<LossRecord> losses,
                                  List<BatchSaleView> sales,
                                  List<DisposalView> disposals) {}

    /** 下架处置明细：计划/实点/报损/回收/退供/仍可售，还原实物最终去向 */
    public record DisposalView(String orderNo, String cabinetNo, String channelCode,
                               String reason, String status,
                               int plannedQty, int countedQty,
                               int scrapQty, int recycleQty,
                               int returnSupplierQty, int saleableQty,
                               LocalDateTime scheduledAt, LocalDateTime executedAt,
                               boolean overdue, String remark) {}

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
