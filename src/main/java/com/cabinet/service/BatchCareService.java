package com.cabinet.service;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.AlertType;
import com.cabinet.domain.enums.LedgerType;
import com.cabinet.domain.enums.LossReason;
import com.cabinet.domain.enums.OrderStatus;
import com.cabinet.dto.BatchRecordView;
import com.cabinet.exception.ApiException;
import com.cabinet.repo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客服批次关怀与批次还原：
 * - 商品已被用户买走后发现批次问题（临期/变质/停售并发售出）：按批次找出全部购买订单，
 *   客服可对受影响订单做供应商责任退款（用户通常不退回实物 -> SUPPLIER_QUALITY 报损，
 *   计入供应商责任，结算扣减），并向用户发出风险提醒（告警 + 业务事件）。
 * - 批次记录还原：聚合主数据、台账、报损、订单、下架四去向、结算、告警，
 *   完整还原“为什么下架、最终去了哪里、结算如何变化”。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchCareService {

    private final ProductBatchRepository batchRepository;
    private final ProductRepository productRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderRecordRepository orderRepository;
    private final DoorSessionRepository sessionRepository;
    private final BatchLedgerRepository ledgerRepository;
    private final LossRecordRepository lossRepository;
    private final RemovalOrderRepository removalOrderRepository;
    private final RemovalItemRepository removalItemRepository;
    private final SettlementItemRepository settlementItemRepository;
    private final AlertRepository alertRepository;
    private final AppUserRepository userRepository;
    private final BatchLedgerService ledgerService;
    private final AlertService alertService;
    private final BizEventPublisher bizEventPublisher;

    public record BatchOrderRow(Long orderItemId, String orderNo, String cabinetNo,
                                String userCode, String userName, String channelCode,
                                int qty, int refundedQty, BigDecimal unitPrice,
                                BigDecimal refundableAmount, String status,
                                LocalDateTime chargedAt) {}

    /** 按批次查找购买订单（含已扣款/部分退/全退），供客服逐单联系与退款 */
    @Transactional(readOnly = true)
    public List<BatchOrderRow> batchOrders(String batchNo) {
        ProductBatch batch = mustBatch(batchNo);
        List<BatchOrderRow> rows = new ArrayList<>();
        for (OrderItem oi : orderItemRepository.findChargedByBatch(batch.getId())) {
            OrderRecord o = oi.getOrder();
            int refundable = oi.getQty() - oi.getRefundedQty();
            rows.add(new BatchOrderRow(oi.getId(), o.getOrderNo(),
                    o.getCabinet() != null ? o.getCabinet().getCabinetNo() : null,
                    o.getUserCode(),
                    o.getSession() != null ? o.getSession().getUserName() : null,
                    oi.getChannelCode(), oi.getQty(), oi.getRefundedQty(),
                    oi.getUnitPrice(),
                    oi.getUnitPrice().multiply(BigDecimal.valueOf(refundable)),
                    o.getStatus().name(), o.getChargedAt()));
        }
        return rows;
    }

    public record RiskNotifyResult(String batchNo, int affectedOrders, int notifiedUsers,
                                   List<String> orderNos) {}

    /**
     * 批次风险提醒：对买过该批次的用户逐单生成提醒（告警留痕 + 业务事件触达用户通道）。
     */
    @Transactional
    public RiskNotifyResult notifyRisk(String batchNo, String handlerUsername, String message) {
        ProductBatch batch = mustBatch(batchNo);
        List<OrderItem> items = orderItemRepository.findChargedByBatch(batch.getId());
        Map<String, String> users = new LinkedHashMap<>();
        List<String> orderNos = new ArrayList<>();
        for (OrderItem oi : items) {
            OrderRecord o = oi.getOrder();
            orderNos.add(o.getOrderNo());
            if (o.getUserCode() != null) users.putIfAbsent(o.getUserCode(),
                    o.getSession() != null ? o.getSession().getUserName() : null);
            Alert alert = Alert.builder()
                    .cabinet(o.getCabinet())
                    .alertType(AlertType.BATCH_RISK)
                    .severity("CRITICAL")
                    .message("批次 " + batchNo + " 风险提醒：请联系用户 " + o.getUserCode()
                            + "（订单 " + o.getOrderNo() + "）。" + message)
                    .bizType("ORDER").bizId(o.getId())
                    .build();
            alertRepository.save(alert);
            bizEventPublisher.publish("BATCH_RISK_NOTIFY", o.getOrderNo(),
                    o.getCabinet() != null ? o.getCabinet().getCabinetNo() : null,
                    batchNo, "批次风险提醒用户 " + o.getUserCode() + ": " + message, null);
        }
        if (handlerUsername != null) {
            log.info("客服 {} 对批次 {} 发起风险提醒，影响订单 {} 笔", handlerUsername, batchNo, items.size());
        }
        return new RiskNotifyResult(batchNo, items.size(), users.size(), orderNos);
    }

    public record BatchRefundResult(String batchNo, int refundedOrders, int refundedItems,
                                    BigDecimal refundAmount, int lossQty, BigDecimal lossCost) {}

    /**
     * 供应商责任批量退款：对该批次尚未退完的购买订单执行退款。
     * 场景：商品已售出（临期/变质/停售并发售出），用户不退回实物，
     * 退款同时登记 SUPPLIER_QUALITY 报损（liability=SUPPLIER），周期结算自动扣减供应商应付款。
     */
    @Transactional
    public BatchRefundResult refundByBatch(String batchNo, String handlerUsername, String reason) {
        ProductBatch batch = mustBatch(batchNo);
        AppUser handler = handlerUsername != null
                ? userRepository.findByUsername(handlerUsername).orElse(null) : null;

        int orders = 0, items = 0, lossQty = 0;
        BigDecimal refundTotal = BigDecimal.ZERO;
        BigDecimal lossCostTotal = BigDecimal.ZERO;
        Map<Long, OrderRecord> touchedOrders = new LinkedHashMap<>();

        for (OrderItem oi : orderItemRepository.findChargedByBatch(batch.getId())) {
            int qty = oi.getQty() - oi.getRefundedQty();
            if (qty <= 0) continue;
            OrderRecord order = oi.getOrder();
            if (order.getStatus() == OrderStatus.CANCELLED) continue;

            BigDecimal refund = oi.getUnitPrice().multiply(BigDecimal.valueOf(qty));
            BigDecimal cost = batch.getCostPrice() != null
                    ? batch.getCostPrice().multiply(BigDecimal.valueOf(qty)) : BigDecimal.ZERO;

            oi.setRefundedQty(oi.getQty());
            orderItemRepository.save(oi);
            order.setRefundedAmount(order.getRefundedAmount().add(refund));
            orderRepository.save(order);
            touchedOrders.put(order.getId(), order);

            // 供应商责任报损（实物未收回）
            lossRepository.save(LossRecord.builder()
                    .lossNo("LS" + System.nanoTime())
                    .cabinet(order.getCabinet()).product(oi.getProduct()).batch(batch)
                    .reason(LossReason.SUPPLIER_QUALITY)
                    .qty(qty).costAmount(cost)
                    .liability("SUPPLIER")
                    .bizType("BATCH_CARE").bizId(batch.getId())
                    .remark("批次责任退款(" + (handler != null ? handler.getUsername() : "system")
                            + "): " + reason).build());
            ledgerService.record(batch, order.getCabinet(), LedgerType.LOSS_OUT, -qty,
                    cost, "BATCH_CARE", batch.getId(),
                    "批次供应商责任退款，实物未收回 " + order.getOrderNo());

            refundTotal = refundTotal.add(refund);
            lossCostTotal = lossCostTotal.add(cost);
            lossQty += qty;
            items++;
        }

        // 回写订单状态/会话状态
        for (OrderRecord o : touchedOrders.values()) {
            List<OrderItem> all = orderItemRepository.findByOrderId(o.getId());
            boolean full = all.stream().allMatch(x -> x.getRefundedQty() >= x.getQty());
            o.setStatus(full ? OrderStatus.REFUNDED : OrderStatus.PARTIALLY_REFUNDED);
            orderRepository.save(o);
            if (o.getSession() != null) {
                DoorSession s = sessionRepository.findById(o.getSession().getId()).orElse(null);
                if (s != null) {
                    s.setStatus(com.cabinet.domain.enums.SessionStatus.SETTLED_REFUNDED);
                    sessionRepository.save(s);
                }
            }
            bizEventPublisher.publish("ORDER_REFUNDED", o.getOrderNo(),
                    o.getCabinet() != null ? o.getCabinet().getCabinetNo() : null,
                    batchNo, "批次 " + batchNo + " 供应商责任退款", null);
            orders++;
        }

        return new BatchRefundResult(batchNo, orders, items, refundTotal, lossQty, lossCostTotal);
    }

    /** 批次还原视图：为什么被下架、实物去向、结算变化全量留痕 */
    @Transactional(readOnly = true)
    public BatchRecordView batchRecord(String batchNo) {
        ProductBatch batch = mustBatch(batchNo);
        Product product = batch.getProduct() != null
                ? productRepository.findById(batch.getProduct().getId()).orElse(null) : null;

        List<RemovalItem> removalItems = removalItemRepository.findAll().stream()
                .filter(ri -> ri.getBatch().getId().equals(batch.getId())).toList();
        List<RemovalOrder> removalOrders = removalItems.stream()
                .map(RemovalItem::getRemovalOrder).distinct().toList();

        return new BatchRecordView(
                batch,
                product,
                batch.getSupplier(),
                ledgerRepository.findByBatchIdOrderByIdAsc(batch.getId()),
                lossRepository.findByBatchIdOrderByIdAsc(batch.getId()),
                orderItemRepository.findChargedByBatch(batch.getId()),
                removalOrders,
                removalItems,
                settlementItemRepository.findByBatchId(batch.getId()),
                alertRepository.findByBizTypeAndBizIdOrderByIdDesc("PRODUCT_BATCH", batch.getId()));
    }

    private ProductBatch mustBatch(String batchNo) {
        return batchRepository.findByBatchNo(batchNo)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchNo));
    }
}
