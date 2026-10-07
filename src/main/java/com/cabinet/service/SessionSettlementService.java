package com.cabinet.service;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.*;
import com.cabinet.exception.ApiException;
import com.cabinet.repo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 开门会话与结算服务：
 * 开门 -> 多源识别事件（拿起/放回可相互抵消）-> 关门融合：
 *  - 置信度足够且称重一致：按 FEFO 扣批次库存、生成订单扣款、批次台账售出；
 *  - 识别冲突/低置信/重量不符：订单挂起 PENDING_REVIEW，不扣款不扣库存，等待人工复核；
 *  - 无净拿取：订单取消。
 * 复核确认时再执行扣库扣款；复核否定则取消，避免误扣款。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionSettlementService {

    private final DoorSessionRepository sessionRepository;
    private final RecognitionEventRepository eventRepository;
    private final OrderRecordRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CabinetRepository cabinetRepository;
    private final ChannelRepository channelRepository;
    private final ProductRepository productRepository;
    private final ProductBatchRepository batchRepository;
    private final ChannelStockRepository stockRepository;
    private final BatchLedgerService ledgerService;
    private final RecognitionFusion fusion;
    private final AlertService alertService;
    private final BizEventPublisher bizEventPublisher;

    @Value("${cabinet.recognition.confidence-threshold:0.75}")
    private double confidenceThreshold;

    @Value("${cabinet.recognition.weight-tolerance-gram:80}")
    private int weightToleranceGram;

    @Transactional
    public DoorSession openDoor(String cabinetNo, String sessionNo, String userCode,
                                String userName, LocalDateTime at) {
        Cabinet cabinet = cabinetRepository.findByCabinetNo(cabinetNo)
                .orElseThrow(() -> ApiException.notFound("柜机不存在: " + cabinetNo));
        if (cabinet.getStatus() == CabinetStatus.ALARM_LOCKED) {
            // 食品安全：温控锁定柜机禁止开门售卖，记录被拒开门
            DoorSession rejected = DoorSession.builder()
                    .sessionNo(sessionNo)
                    .cabinet(cabinet).userCode(userCode).userName(userName)
                    .openedAt(at).closedAt(at)
                    .status(SessionStatus.CANCELLED)
                    .settleNote("温控报警锁定，开门被拒")
                    .build();
            return sessionRepository.save(rejected);
        }
        DoorSession session = DoorSession.builder()
                .sessionNo(sessionNo)
                .cabinet(cabinet)
                .userCode(userCode).userName(userName)
                .openedAt(at)
                .status(SessionStatus.OPEN)
                .build();
        cabinet.setLastHeartbeatAt(at);
        cabinetRepository.save(cabinet);
        return sessionRepository.save(session);
    }

    @Transactional
    public RecognitionEvent recordRecognition(String sessionNo, String cabinetNo, String channelCode,
                                              String sku, String batchNo, String source, String action,
                                              Integer qty, Double confidence, Integer weightGram,
                                              String videoUrl, Integer clipStart, Integer clipEnd,
                                              LocalDateTime detectedAt, String rawPayload) {
        DoorSession session = sessionRepository.findBySessionNo(sessionNo)
                .orElseThrow(() -> ApiException.notFound("开门会话不存在: " + sessionNo));
        Cabinet cabinet = cabinetRepository.findByCabinetNo(cabinetNo)
                .orElseThrow(() -> ApiException.notFound("柜机不存在"));
        Product product = sku != null ? productRepository.findBySku(sku).orElse(null) : null;
        ProductBatch batch = batchNo != null ? batchRepository.findByBatchNo(batchNo).orElse(null) : null;

        return eventRepository.save(RecognitionEvent.builder()
                .session(session).cabinet(cabinet)
                .channelCode(channelCode).product(product).batch(batch)
                .source(RecognitionSource.valueOf(source))
                .action(DetectAction.valueOf(action))
                .qty(qty == null ? 1 : qty)
                .confidence(confidence)
                .weightGram(weightGram)
                .videoClipUrl(videoUrl).clipStartSec(clipStart).clipEndSec(clipEnd)
                .detectedAt(detectedAt != null ? detectedAt : LocalDateTime.now())
                .rawPayload(rawPayload)
                .build());
    }

    /** 关门结算 */
    @Transactional
    public OrderRecord closeDoor(String sessionNo, Integer weightDeltaGram, LocalDateTime closedAt) {
        DoorSession session = sessionRepository.findBySessionNo(sessionNo)
                .orElseThrow(() -> ApiException.notFound("开门会话不存在: " + sessionNo));
        if (session.getStatus() != SessionStatus.OPEN) {
            return orderRepository.findBySessionId(session.getId()).orElse(null);
        }
        session.setClosedAt(closedAt != null ? closedAt : LocalDateTime.now());
        session.setWeightDeltaGram(weightDeltaGram != null ? weightDeltaGram : 0);

        List<RecognitionEvent> events = eventRepository.findBySessionIdOrderByDetectedAtAsc(session.getId());
        List<RecognitionFusion.NetLine> lines = fusion.fuse(
                events, session.getWeightDeltaGram(), weightToleranceGram);

        double minConf = lines.stream().mapToDouble(RecognitionFusion.NetLine::minConfidence).min().orElse(1.0);
        session.setMinConfidence(minConf);

        // 库存可行性检查：识别行必须有足够可售批次库存
        List<String> feasibilityErrors = checkFeasibility(session.getCabinet(), lines);
        boolean needReview = minConf < confidenceThreshold || !feasibilityErrors.isEmpty();

        if (lines.isEmpty()) {
            session.setStatus(SessionStatus.CANCELLED);
            session.setSettleNote("无净拿取商品");
            sessionRepository.save(session);
            OrderRecord order = buildOrder(session, OrderStatus.CANCELLED, BigDecimal.ZERO,
                    "无净拿取，订单取消", List.of());
            bizEventPublisher.publish("ORDER_CANCELLED", order.getOrderNo(),
                    session.getCabinet().getCabinetNo(), null, "无净拿取", null);
            return order;
        }

        if (needReview) {
            String note = "识别存疑挂起复核: " + String.join("; ", feasibilityErrors);
            if (minConf < confidenceThreshold) {
                note = "置信度 " + String.format("%.2f", minConf) + " 低于阈值，" + note;
            }
            session.setStatus(SessionStatus.REVIEW_PENDING);
            session.setSettleNote(note);
            sessionRepository.save(session);
            BigDecimal expectAmount = estimateAmount(lines);
            OrderRecord order = buildOrder(session, OrderStatus.PENDING_REVIEW, expectAmount, note, List.of());
            alertService.raise(session.getCabinet(), AlertType.RECOGNITION_CONFLICT, "WARN",
                    "会话 " + sessionNo + " 识别存疑待复核", "SESSION", session.getId());
            bizEventPublisher.publish("ORDER_PENDING_REVIEW", order.getOrderNo(),
                    session.getCabinet().getCabinetNo(), null, note, expectAmount);
            return order;
        }

        return doSettle(session, lines);
    }

    /**
     * 人工复核确认：按运营确认的净拿取行执行扣库扣款。
     * confirmedLines: channelCode/sku/qty（为空则按融合结果原样确认）
     */
    @Transactional
    public OrderRecord confirmReview(String sessionNo, List<ConfirmedLine> confirmedLines, String note) {
        DoorSession session = sessionRepository.findBySessionNo(sessionNo)
                .orElseThrow(() -> ApiException.notFound("会话不存在"));
        if (session.getStatus() != SessionStatus.REVIEW_PENDING) {
            throw ApiException.conflict("会话不处于待复核状态");
        }
        List<RecognitionFusion.NetLine> lines;
        if (confirmedLines == null || confirmedLines.isEmpty()) {
            List<RecognitionEvent> events =
                    eventRepository.findBySessionIdOrderByDetectedAtAsc(session.getId());
            lines = fusion.fuse(events,
                    session.getWeightDeltaGram() != null ? session.getWeightDeltaGram() : 0,
                    weightToleranceGram);
        } else {
            lines = new ArrayList<>();
            for (ConfirmedLine c : confirmedLines) {
                Product p = productRepository.findBySku(c.sku())
                        .orElseThrow(() -> ApiException.badRequest("商品不存在: " + c.sku()));
                lines.add(new RecognitionFusion.NetLine(c.channelCode(), p, null, c.qty(),
                        1.0, false, Set.of("MANUAL")));
            }
        }
        session.setStatus(SessionStatus.SETTLED);
        session.setSettleNote("人工复核确认结算" + (note != null ? ": " + note : ""));
        sessionRepository.save(session);
        return doSettle(session, lines);
    }

    /** 复核否定：取消订单（不扣款） */
    @Transactional
    public OrderRecord rejectReview(String sessionNo, String note) {
        DoorSession session = sessionRepository.findBySessionNo(sessionNo)
                .orElseThrow(() -> ApiException.notFound("会话不存在"));
        session.setStatus(SessionStatus.CANCELLED);
        session.setSettleNote("人工复核取消: " + note);
        sessionRepository.save(session);
        OrderRecord pending = orderRepository.findBySessionId(session.getId()).orElse(null);
        if (pending != null) {
            pending.setStatus(OrderStatus.CANCELLED);
            pending.setNote(note);
            orderRepository.save(pending);
            return pending;
        }
        return buildOrder(session, OrderStatus.CANCELLED, BigDecimal.ZERO, note, List.of());
    }

    /** 真正扣库存 + 生成订单明细 + 台账 */
    private OrderRecord doSettle(DoorSession session, List<RecognitionFusion.NetLine> lines) {
        List<Deduction> deductions = new ArrayList<>();
        List<ProductBatch> inFlightHaltedBatches = new ArrayList<>();
        for (RecognitionFusion.NetLine line : lines) {
            Channel channel = channelRepository
                    .findByCabinetIdAndCode(session.getCabinet().getId(), line.channelCode())
                    .orElseThrow(() -> ApiException.conflict("货道不存在: " + line.channelCode()));
            int remain = line.qty();
            // RFID 批次提示优先，其余按 FEFO；仅从未停售/未限制曝光批次扣减
            List<ChannelStock> fefo = stockRepository.findSellableByChannelFefo(channel.getId());
            if (line.batchHint() != null) {
                ChannelStock hinted = fefo.stream()
                        .filter(s -> s.getBatch().getId().equals(line.batchHint().getId()))
                        .findFirst().orElse(null);
                if (hinted != null) {
                    List<ChannelStock> reordered = new ArrayList<>();
                    reordered.add(hinted);
                    fefo.stream().filter(s -> !s.getBatch().getId().equals(line.batchHint().getId()))
                            .forEach(reordered::add);
                    fefo = reordered;
                }
            }
            // 第一遍：锁定库存行后扣正常可售量
            for (ChannelStock stock : fefo) {
                if (remain <= 0) break;
                ChannelStock locked = stockRepository.lockById(stock.getId()).orElse(stock);
                int sellable = locked.sellableQty();
                int take = Math.min(remain, sellable);
                if (take <= 0) continue;
                locked.setQtyOnHand(locked.getQtyOnHand() - take);
                stockRepository.save(locked);
                deductions.add(new Deduction(channel.getCode(), line.product(), locked.getBatch(), take));
                remain -= take;
            }
            // 第二遍：开门在途期间批次刚被临期停售（柜机尚未摘品/用户物理已拿），
            // 从停售量中扣减，订单与下架账通过后续清点差异自动对齐
            if (remain > 0) {
                for (ChannelStock stock : stockRepository.findAllByChannelFefo(channel.getId())) {
                    if (remain <= 0) break;
                    ChannelStock locked = stockRepository.lockById(stock.getId()).orElse(stock);
                    int inFlight = Math.min(remain, locked.getQtySaleHalted());
                    if (inFlight <= 0) continue;
                    locked.setQtyOnHand(locked.getQtyOnHand() - inFlight);
                    locked.setQtySaleHalted(locked.getQtySaleHalted() - inFlight);
                    stockRepository.save(locked);
                    deductions.add(new Deduction(channel.getCode(), line.product(),
                            locked.getBatch(), inFlight));
                    inFlightHaltedBatches.add(locked.getBatch());
                    remain -= inFlight;
                }
            }
            if (remain > 0) {
                throw ApiException.conflict("货道 " + line.channelCode()
                        + " 可售库存不足，缺少 " + remain + " 件 " + line.product().getSku());
            }
        }
        // 停售量因在途售出归零的批次，若全柜无其他停售量则解除停售标记（曝光限制须运营解封）
        for (ProductBatch b : inFlightHaltedBatches.stream().distinct().toList()) {
            if (stockRepository.sumSaleHaltedByBatch(b.getId()) <= 0 && !b.isExposureBlocked()) {
                b.setSaleHalted(false);
                b.setHaltReason(null);
                batchRepository.save(b);
            }
            alertService.raise(session.getCabinet(), AlertType.BATCH_RISK, "WARN",
                    "开门会话 " + session.getSessionNo() + " 在途售出已停售批次 "
                            + b.getBatchNo() + "，需关注临期售出风险与供应商责任",
                    "BATCH", b.getId());
        }

        // 聚合同批次/同货道扣减为订单明细并记账
        List<OrderItem> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        Map<String, Deduction> agg = new LinkedHashMap<>();
        for (Deduction d : deductions) {
            String k = d.channelCode + "|" + d.batch.getId();
            Deduction exist = agg.get(k);
            if (exist != null) exist = new Deduction(exist.channelCode, exist.product, exist.batch,
                    exist.qty + d.qty);
            else exist = d;
            agg.put(k, exist);
        }
        OrderRecord order = orderRepository.findBySessionId(session.getId())
                .filter(o -> o.getStatus() == OrderStatus.PENDING_REVIEW)
                .orElseGet(() -> OrderRecord.builder()
                        .orderNo(genNo("OD"))
                        .session(session).cabinet(session.getCabinet())
                        .userCode(session.getUserCode())
                        .build());
        order.setStatus(OrderStatus.CHARGED);
        order.setChargedAt(LocalDateTime.now());
        order.setPaymentTxnNo("PAY" + System.nanoTime());
        order.setTotalAmount(BigDecimal.ZERO);
        order.setRefundedAmount(BigDecimal.ZERO);
        order = orderRepository.save(order);

        for (Deduction d : agg.values()) {
            BigDecimal price = d.product.getRetailPrice() != null
                    ? d.product.getRetailPrice() : BigDecimal.ZERO;
            BigDecimal subtotal = price.multiply(BigDecimal.valueOf(d.qty));
            items.add(orderItemRepository.save(OrderItem.builder()
                    .order(order).product(d.product).batch(d.batch)
                    .channelCode(d.channelCode).qty(d.qty)
                    .unitPrice(price).subtotal(subtotal)
                    .refundedQty(0).build()));
            total = total.add(subtotal);

            BigDecimal cost = d.batch.getCostPrice() != null
                    ? d.batch.getCostPrice().multiply(BigDecimal.valueOf(d.qty)) : BigDecimal.ZERO;
            ledgerService.record(d.batch, session.getCabinet(), LedgerType.SALE_OUT, -d.qty,
                    cost, "ORDER", order.getId(),
                    "会话 " + session.getSessionNo() + " 售出 " + d.channelCode);
        }
        order.setTotalAmount(total);
        order.setNote("融合识别源: " + lines.stream()
                .flatMap(l -> l.sources().stream()).distinct().sorted().toList());
        orderRepository.save(order);

        if (session.getStatus() != SessionStatus.SETTLED) {
            session.setStatus(SessionStatus.SETTLED);
        }
        session.setSettleNote((session.getSettleNote() == null ? "" : session.getSettleNote())
                + " | 已扣款 " + total);
        sessionRepository.save(session);

        bizEventPublisher.publish("ORDER_CHARGED", order.getOrderNo(),
                session.getCabinet().getCabinetNo(), null,
                "关门结算 " + deductions.stream().mapToInt(d -> d.qty).sum() + " 件", total);
        return order;
    }

    private List<String> checkFeasibility(Cabinet cabinet, List<RecognitionFusion.NetLine> lines) {
        List<String> errors = new ArrayList<>();
        for (RecognitionFusion.NetLine line : lines) {
            Channel channel = channelRepository
                    .findByCabinetIdAndCode(cabinet.getId(), line.channelCode()).orElse(null);
            if (channel == null) { errors.add("货道不存在 " + line.channelCode()); continue; }
            // 可售量 + 临期停售量都可结算（开门在途停售按风险单处理），温控隔离量不可售
            int available = stockRepository.findAllByChannelFefo(channel.getId()).stream()
                    .mapToInt(s -> s.getQtyOnHand() - s.getQtyQuarantined()).sum();
            if (available < line.qty()) {
                errors.add(String.format("货道 %s 识别拿走 %d 件但可用库存仅 %d 件",
                        line.channelCode(), line.qty(), available));
            }
        }
        return errors;
    }

    private BigDecimal estimateAmount(List<RecognitionFusion.NetLine> lines) {
        return lines.stream()
                .map(l -> (l.product().getRetailPrice() != null ? l.product().getRetailPrice()
                        : BigDecimal.ZERO).multiply(BigDecimal.valueOf(l.qty())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private OrderRecord buildOrder(DoorSession session, OrderStatus status, BigDecimal amount,
                                   String note, List<OrderItem> items) {
        return orderRepository.save(OrderRecord.builder()
                .orderNo(genNo("OD"))
                .session(session).cabinet(session.getCabinet())
                .userCode(session.getUserCode())
                .status(status).totalAmount(amount)
                .chargedAt(status == OrderStatus.CHARGED ? LocalDateTime.now() : null)
                .note(note)
                .build());
    }

    private String genNo(String prefix) {
        return prefix + LocalDate.now().toString().replace("-", "")
                + String.format("%04d", System.nanoTime() % 10000);
    }

    private record Deduction(String channelCode, Product product, ProductBatch batch, int qty) {}

    /** 人工复核确认行 */
    public record ConfirmedLine(String channelCode, String sku, Integer qty) {}
}
