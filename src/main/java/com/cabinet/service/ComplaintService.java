package com.cabinet.service;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.*;
import com.cabinet.dto.EvidenceView;
import com.cabinet.exception.ApiException;
import com.cabinet.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 客服投诉与售后：
 * - 证据回放：开门记录、多源识别结果（含视频片段定位）、订单明细、批次库存变化、温控曲线；
 * - 判责退款：支持整单/按行退款；商品已收回则回补库存（REFUND_RETURN 台账），
 *   未收回登记报损 REFUND_NO_RETURN；订单与会话进入已退款状态。
 */
@Service
@RequiredArgsConstructor
public class ComplaintService {

    private final ComplaintRepository complaintRepository;
    private final DoorSessionRepository sessionRepository;
    private final RecognitionEventRepository eventRepository;
    private final OrderRecordRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final BatchLedgerRepository ledgerRepository;
    private final TempLogRepository tempLogRepository;
    private final ChannelStockRepository stockRepository;
    private final ChannelRepository channelRepository;
    private final AppUserRepository userRepository;
    private final LossRecordRepository lossRepository;
    private final BatchLedgerService ledgerService;
    private final BizEventPublisher bizEventPublisher;

    @Transactional
    public Complaint submit(String sessionNo, String category, String description) {
        DoorSession session = sessionRepository.findBySessionNo(sessionNo)
                .orElseThrow(() -> ApiException.notFound("开门会话不存在"));
        OrderRecord order = orderRepository.findBySessionId(session.getId()).orElse(null);
        Complaint complaint = Complaint.builder()
                .complaintNo(genNo("CP"))
                .session(session).order(order)
                .userCode(session.getUserCode()).userName(session.getUserName())
                .category(category).description(description)
                .status(ComplaintStatus.SUBMITTED)
                .build();
        return complaintRepository.save(complaint);
    }

    /** 组装售后证据回放视图 */
    @Transactional(readOnly = true)
    public EvidenceView evidence(String complaintNo) {
        Complaint c = complaintRepository.findByComplaintNo(complaintNo)
                .orElseThrow(() -> ApiException.notFound("投诉单不存在"));
        DoorSession session = c.getSession();
        List<RecognitionEvent> events =
                eventRepository.findBySessionIdOrderByDetectedAtAsc(session.getId());
        OrderRecord order = c.getOrder();
        List<OrderItem> items = order != null
                ? orderItemRepository.findByOrderId(order.getId()) : List.of();

        // 该订单引起的批次库存变化（SALE_OUT），并补充该批次最近台账
        List<BatchLedger> ledgers = new ArrayList<>();
        for (OrderItem oi : items) {
            ledgers.addAll(ledgerRepository.findByBatchIdOrderByIdAsc(oi.getBatch().getId()));
        }
        ledgers.sort(java.util.Comparator.comparing(BatchLedger::getId));

        List<TempLog> temps = tempLogRepository
                .findTop200ByCabinetIdOrderByRecordedAtDesc(session.getCabinet().getId());

        return new EvidenceView(c, session, order, items, events, ledgers, temps);
    }

    /**
     * 判责处理：退款。
     * refundItems: orderItemId -> 退款数量（为空且 grantRefund=true 则整单退）
     * goodsReturned: 商品是否已收回；未收回 -> 报损（退款未收回）
     * supplierLiability: 责任归属供应商（临期/质量问题）时报损计 SUPPLIER，结算从应付扣除
     */
    @Transactional
    public Complaint resolve(String complaintNo, String handlerUsername, boolean grantRefund,
                             List<RefundLine> refundItems, boolean goodsReturned,
                             boolean supplierLiability, String resolution) {
        Complaint complaint = complaintRepository.findByComplaintNo(complaintNo)
                .orElseThrow(() -> ApiException.notFound("投诉单不存在"));
        AppUser handler = userRepository.findByUsername(handlerUsername)
                .orElseThrow(() -> ApiException.badRequest("客服账号不存在"));
        complaint.setHandler(handler);
        complaint.setStatus(grantRefund
                ? ComplaintStatus.RESOLVED_REFUNDED : ComplaintStatus.RESOLVED_REJECTED);
        complaint.setResolution(resolution);
        complaint.setResolvedAt(LocalDateTime.now());

        OrderRecord order = complaint.getOrder();
        BigDecimal refundTotal = BigDecimal.ZERO;
        List<String> refundDetail = new ArrayList<>();

        if (grantRefund && order != null
                && order.getStatus() != OrderStatus.REFUNDED
                && order.getStatus() != OrderStatus.CANCELLED) {
            List<OrderItem> orderItems = orderItemRepository.findByOrderId(order.getId());
            for (OrderItem oi : orderItems) {
                int refundQty;
                if (refundItems == null || refundItems.isEmpty()) {
                    refundQty = oi.getQty() - oi.getRefundedQty();
                } else {
                    refundQty = refundItems.stream()
                            .filter(r -> r.orderItemId() == oi.getId())
                            .findFirst().map(RefundLine::qty).orElse(0);
                }
                if (refundQty <= 0) continue;
                if (refundQty > oi.getQty() - oi.getRefundedQty()) {
                    throw ApiException.badRequest("退款数量超过可退数量");
                }
                oi.setRefundedQty(oi.getRefundedQty() + refundQty);
                orderItemRepository.save(oi);
                BigDecimal lineRefund = oi.getUnitPrice().multiply(BigDecimal.valueOf(refundQty));
                refundTotal = refundTotal.add(lineRefund);
                refundDetail.add(oi.getProduct().getSku() + " x" + refundQty + "=" + lineRefund);

                // 库存处理：收回则回补货道库存（找该商品在原货道的最早批次行回补），
                // 未收回则登记报损
                if (goodsReturned) {
                    ChannelStock stock = stockRepository
                            .findByChannelIdAndBatchId(
                                    channelId(oi), oi.getBatch().getId())
                            .orElse(null);
                    if (stock != null) {
                        stock.setQtyOnHand(stock.getQtyOnHand() + refundQty);
                        // 批次已停售/限制曝光时收回品同样不得再售
                        if (oi.getBatch().isSaleHalted() || oi.getBatch().isExposureBlocked()) {
                            stock.setQtySaleHalted(stock.getQtySaleHalted() + refundQty);
                        }
                        stockRepository.save(stock);
                    }
                    ledgerService.record(oi.getBatch(), order.getCabinet(),
                            LedgerType.REFUND_RETURN, refundQty, lineRefund,
                            "COMPLAINT", complaint.getId(),
                            "投诉退款商品收回 " + complaintNo);
                } else {
                    BigDecimal cost = oi.getBatch().getCostPrice() != null
                            ? oi.getBatch().getCostPrice().multiply(BigDecimal.valueOf(refundQty))
                            : BigDecimal.ZERO;
                    lossRepository.save(LossRecord.builder()
                            .lossNo(genNo("LS"))
                            .cabinet(order.getCabinet())
                            .product(oi.getProduct()).batch(oi.getBatch())
                            .reason(LossReason.REFUND_NO_RETURN)
                            .qty(refundQty).costAmount(cost)
                            .liability(supplierLiability ? "SUPPLIER" : "OPERATOR")
                            .bizType("COMPLAINT").bizId(complaint.getId())
                            .remark(supplierLiability ? "供应商责任退款（临期/质量），商品未收回"
                                    : "误判退款，商品未收回").build());
                    ledgerService.record(oi.getBatch(), order.getCabinet(),
                            LedgerType.LOSS_OUT, -refundQty, cost,
                            "COMPLAINT", complaint.getId(),
                            (supplierLiability ? "供应商责任" : "")
                                    + "投诉退款商品未收回 " + complaintNo);
                }
            }

            order.setRefundedAmount(order.getRefundedAmount().add(refundTotal));
            boolean allRefunded = orderItems.stream()
                    .allMatch(oi -> oi.getRefundedQty() >= oi.getQty());
            order.setStatus(allRefunded ? OrderStatus.REFUNDED : OrderStatus.PARTIALLY_REFUNDED);
            orderRepository.save(order);

            DoorSession session = complaint.getSession();
            session.setStatus(SessionStatus.SETTLED_REFUNDED);
            sessionRepository.save(session);

            bizEventPublisher.publish("ORDER_REFUNDED", order.getOrderNo(),
                    order.getCabinet().getCabinetNo(), null,
                    "投诉 " + complaintNo + " 退款 " + refundTotal, refundTotal);
        }

        complaint.setRefundAmount(refundTotal);
        complaint.setRefundDetailJson(String.join("; ", refundDetail));
        return complaintRepository.save(complaint);
    }

    private Long channelId(OrderItem oi) {
        return channelRepository
                .findByCabinetIdAndCode(oi.getOrder().getCabinet().getId(), oi.getChannelCode())
                .map(Channel::getId).orElse(null);
    }

    private String genNo(String prefix) {
        return prefix + LocalDate.now().toString().replace("-", "")
                + String.format("%04d", System.nanoTime() % 10000);
    }

    public record RefundLine(Long orderItemId, Integer qty) {}
}
