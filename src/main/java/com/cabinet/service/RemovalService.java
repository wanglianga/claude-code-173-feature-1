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

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 临期/异常批次下架与补货员处置任务服务。
 *
 * 关键规则：
 * 1) 批次进入临期范围：先做“批次级”停售（saleBlocked + 货道批次隔离量 + 柜端 BLOCK_BATCH
 *    指令），再生成补货员处理任务；停售粒度是批次而不是货道，同一货道其他批次继续售卖。
 *    柜机离线时指令落表 PENDING，重连后按 seq 补发（{@link DeviceCommandService}）。
 * 2) 补货员到场扫码（scan）绑定任务，清点实物后按去向登记：
 *    SCRAP 报损 / RECYCLE 回收 / RETURN_SUPPLIER 退供应商 / RESALE 仍可销售。
 * 3) 并发对账：停售与开门扣款可能并发——库存行悲观互斥；执行清点时以停售快照(plannedQty)
 *    为基准，用停售后售出明细核销已买走部分，未解释短少自动记盘亏，最终强制守恒：
 *    planned = 停售后售出 + 盘亏短少 + 报损 + 回收 + 退供 + 复售。
 * 4) 任务逾期未处理：标记 overdue 并对批次限流曝光（exposureBlocked，禁止再次上架/推荐）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RemovalService {

    private final RemovalOrderRepository removalRepository;
    private final RemovalItemRepository removalItemRepository;
    private final ChannelStockRepository stockRepository;
    private final ProductBatchRepository batchRepository;
    private final LossRecordRepository lossRepository;
    private final AppUserRepository userRepository;
    private final ChannelRepository channelRepository;
    private final OrderItemRepository orderItemRepository;
    private final BatchLedgerService ledgerService;
    private final AlertService alertService;
    private final BizEventPublisher bizEventPublisher;
    private final DeviceCommandService deviceCommandService;

    private final EntityManager entityManager;

    /** 加悲观写锁并刷新为库内最新值（锁后必须基于最新隔离量/停售标记决策） */
    private <T> T lockFresh(T entity) {
        entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        return entity;
    }

    @Value("${cabinet.task.near-expiry-due-hours:8}")
    private int nearExpiryDueHours;

    // ------------------------------------------------------------------
    // 临期扫描：批次级停售 + 生成任务
    // ------------------------------------------------------------------

    /** 扫描临期/过期在库批次：批次级停售，按 柜机×批次 生成下架处置任务。返回生成任务数 */
    @Transactional
    public int scanExpiry(int nearExpiryDays) {
        LocalDate today = LocalDate.now();
        LocalDate threshold = today.plusDays(nearExpiryDays);
        List<ProductBatch> candidates = batchRepository.findScanCandidates(threshold);
        if (candidates.isEmpty()) return 0;

        // 第一遍（无锁读）汇总涉及的柜机、批次、库存行；随后按全局统一顺序加锁：
        // cabinet(id) -> batch(id) -> stock(货道,到期日,id)，与温控(柜机->库存)、
        // 关门结算(柜机->批次->库存)、清点一致，杜绝 AB-BA 死锁。
        record Plan(ProductBatch batch, Map<Long, List<ChannelStock>> byCabinet) {}
        List<Plan> plans = new ArrayList<>();
        java.util.SortedSet<Long> cabinetIds = new java.util.TreeSet<>();
        for (ProductBatch b0 : candidates) {
            List<ChannelStock> stocks = stockRepository.findByBatchId(b0.getId());
            Map<Long, List<ChannelStock>> byCabinet = new LinkedHashMap<>();
            for (ChannelStock s : stocks) {
                if (s.getQtyOnHand() <= 0) continue;
                byCabinet.computeIfAbsent(s.getChannel().getCabinet().getId(),
                        k -> new ArrayList<>()).add(s);
                cabinetIds.add(s.getChannel().getCabinet().getId());
            }
            if (!byCabinet.isEmpty()) plans.add(new Plan(b0, byCabinet));
        }
        if (plans.isEmpty()) return 0;

        // 1) 先锁全部涉及柜机
        Map<Long, Cabinet> lockedCabinets = new LinkedHashMap<>();
        for (Long cid : cabinetIds) {
            lockedCabinets.put(cid,
                    entityManager.find(Cabinet.class, cid, LockModeType.PESSIMISTIC_WRITE));
        }
        // 2) 按 id 顺序锁批次并双重检查（refresh 拿锁等待期间其他扫描已提交的停售状态）
        List<Plan> lockedPlans = new ArrayList<>();
        for (Plan plan : plans.stream().sorted(Comparator.comparing(p -> p.batch().getId())).toList()) {
            ProductBatch batch = lockFresh(batchRepository.findById(plan.batch().getId()).orElseThrow());
            if (batch.isSaleBlocked() || batch.getStatus() != BatchStatus.IN_STOCK) continue;
            lockedPlans.add(new Plan(batch, plan.byCabinet()));
        }

        int created = 0;
        // 3) 逐批次停售 + 建任务（柜机锁已持有，库存行在批次之后锁定）
        for (Plan plan : lockedPlans) {
            ProductBatch batch = plan.batch();
            boolean expired = !batch.getExpiryDate().isAfter(today);
            String reason = expired ? RemovalReason.EXPIRED.name() : RemovalReason.NEAR_EXPIRY.name();
            blockBatch(batch, reason, lockedCabinets);

            for (var entry : plan.byCabinet().entrySet()) {
                Cabinet cabinet = lockedCabinets.get(entry.getKey());
                created += createTaskForStocks(batch, cabinet, entry.getValue(),
                        expired ? RemovalReason.EXPIRED : RemovalReason.NEAR_EXPIRY);
            }
        }
        return created;
    }

    /**
     * 批次级停售：置停售标记、把在库量计入隔离（可售量=在库-隔离），各柜端停售指令。
     * 调用前必须已按 cabinet -> batch 顺序持锁。
     */
    private void blockBatch(ProductBatch batch, String reason, Map<Long, Cabinet> lockedCabinets) {
        LocalDateTime now = LocalDateTime.now();
        batch.setSaleBlocked(true);
        batch.setBlockedAt(now);
        batch.setBlockReason(reason);
        batchRepository.save(batch);

        // 库存行按货道->到期日->id 顺序加锁并设置隔离量；柜机锁已持有
        List<ChannelStock> all = stockRepository.findByBatchId(batch.getId()).stream()
                .filter(s -> s.getQtyOnHand() > 0)
                .sorted(Comparator.comparing((ChannelStock s) -> s.getChannel().getId())
                        .thenComparing(s -> s.getBatch().getExpiryDate())
                        .thenComparing(s -> s.getBatch().getId()))
                .toList();
        java.util.SortedSet<Long> targetCabinets = new java.util.TreeSet<>();
        for (ChannelStock s : all) {
            ChannelStock locked = lockFresh(stockRepository.findById(s.getId()).orElseThrow());
            locked.setQtyQuarantined(Math.max(locked.getQtyQuarantined(), locked.getQtyOnHand()));
            stockRepository.save(locked);
            targetCabinets.add(locked.getChannel().getCabinet().getId());
        }
        for (Long cid : targetCabinets) {
            Cabinet c = lockedCabinets.get(cid);
            deviceCommandService.issueBlock(c, batch.getBatchNo(), "PRODUCT_BATCH", batch.getId(),
                    "批次 " + batch.getBatchNo() + " 因 " + reason + " 停止售卖，同货道其他批次不受影响");
        }
    }

    /** 为某柜机下该批次的全部货道库存生成一张处置任务（调用前已持柜机/批次锁） */
    private int createTaskForStocks(ProductBatch batch, Cabinet cabinet,
                                    List<ChannelStock> stockLines, RemovalReason reason) {
        LocalDateTime now = LocalDateTime.now();
        if (reason == RemovalReason.EXPIRED) {
            alertService.raise(cabinet, AlertType.EXPIRED, "CRITICAL",
                    "批次 " + batch.getBatchNo() + " 已过期仍在柜 " + cabinet.getCabinetNo()
                            + "，已停售并生成紧急下架任务",
                    "PRODUCT_BATCH", batch.getId());
        } else {
            alertService.raise(cabinet, AlertType.NEAR_EXPIRY, "WARN",
                    "批次 " + batch.getBatchNo() + " 将于 " + batch.getExpiryDate()
                            + " 临期，已在柜端停售并派发下架任务",
                    "PRODUCT_BATCH", batch.getId());
        }

        RemovalOrder order = RemovalOrder.builder()
                .orderNo(genNo("RM"))
                .cabinet(cabinet)
                .triggeredBy("SYSTEM")
                .reason(reason)
                .status(RemovalStatus.PENDING)
                .scheduledAt(now)
                .dueAt(now.plusHours(reason == RemovalReason.EXPIRED ? 2 : nearExpiryDueHours))
                .soldWhilePendingQty(0)
                .shortageQty(0)
                .fullBlock(true)
                .remark(reason == RemovalReason.EXPIRED ? "过期紧急下架" : "临期预防性下架")
                .build();
        removalRepository.save(order);

        int total = 0;
        for (ChannelStock s : stockLines) {
            // 库存行已在 blockBatch 中加锁且仍在同一事务内，直接读取快照数量
            int planned = s.getQtyOnHand();
            if (planned <= 0) continue;
            removalItemRepository.save(RemovalItem.builder()
                    .removalOrder(order)
                    .channel(s.getChannel())
                    .product(s.getProduct())
                    .batch(batch)
                    .plannedQty(planned)
                    .build());
            total += planned;
        }
        if (total == 0) {
            removalRepository.delete(order);
            return 0;
        }
        order.setPlannedTotalQty(total);
        removalRepository.save(order);

        // 停售时点之前已被买走（订单在停售瞬间前后并发）的风险提醒由执行清点阶段核对，
        // 此处先发布任务生成事件
        bizEventPublisher.publish("REMOVAL_TASK_CREATED", order.getOrderNo(),
                cabinet.getCabinetNo(), batch.getBatchNo(),
                reason + " 下架任务生成，批次已停售，待清点数量 " + total, null);
        return 1;
    }

    // ------------------------------------------------------------------
    // 手工下架（温控/卡货/放错货道/召回）：柜机锁内只预留指定数量，不停整批
    // ------------------------------------------------------------------

    @Transactional
    public RemovalOrder createManualOrder(Cabinet cabinet, RemovalReason reason,
                                          List<ManualRemovalLine> lines) {
        // 先锁柜机（全局锁序 cabinet -> batch -> stock）
        Cabinet lockedCabinet = entityManager.find(Cabinet.class, cabinet.getId(),
                LockModeType.PESSIMISTIC_WRITE);
        LocalDateTime now = LocalDateTime.now();
        RemovalOrder order = RemovalOrder.builder()
                .orderNo(genNo("RM"))
                .cabinet(cabinet)
                .triggeredBy("OPERATOR")
                .reason(reason)
                .status(RemovalStatus.PENDING)
                .scheduledAt(now)
                .dueAt(now.plusHours(nearExpiryDueHours))
                .soldWhilePendingQty(0)
                .shortageQty(0)
                .fullBlock(false)
                .build();
        removalRepository.save(order);

        int total = 0;
        for (ManualRemovalLine line : lines) {
            Channel channel = channelRepository
                    .findByCabinetIdAndCode(cabinet.getId(), line.channelCode())
                    .orElseThrow(() -> ApiException.badRequest("货道不存在: " + line.channelCode()));
            List<ChannelStock> stocks = stockRepository.findByChannelId(channel.getId());
            ChannelStock target;
            if (line.batchNo() != null) {
                ProductBatch specified = batchRepository.findByBatchNo(line.batchNo())
                        .orElseThrow(() -> ApiException.badRequest("批次不存在"));
                final Long specifiedId = specified.getId();
                ChannelStock found = stocks.stream()
                        .filter(s -> s.getBatch().getId().equals(specifiedId))
                        .findFirst().orElseThrow(() -> ApiException.badRequest("货道无该批次"));
                target = found;
            } else {
                // 卡货/温控等按 FEFO 取最早的可售批次（排除已全部隔离/停售的行）
                target = stocks.stream()
                        .filter(s -> s.getQtyOnHand() - s.getQtyQuarantined() > 0
                                && !s.getBatch().isSaleBlocked())
                        .min(Comparator.comparing(s -> s.getBatch().getExpiryDate()))
                        .orElseThrow(() -> ApiException.badRequest("货道无可售在库"));
            }
            int qty = line.qty();
            // 部分下架只“预留”指定数量：对该货道批次行加锁并增加等量隔离，
            // 不做整批停售（同货道/同批次其余商品仍可售卖）；整批处置请走临期扫描。
            ChannelStock locked = lockFresh(
                    stockRepository.findById(target.getId()).orElseThrow());
            int available = locked.getQtyOnHand() - locked.getQtyQuarantined();
            if (available < qty) {
                throw ApiException.conflict(String.format("货道 %s 批次 %s 可预留数量不足(%d<%d)",
                        channel.getCode(), locked.getBatch().getBatchNo(), available, qty));
            }
            locked.setQtyQuarantined(locked.getQtyQuarantined() + qty);
            stockRepository.save(locked);

            removalItemRepository.save(RemovalItem.builder()
                    .removalOrder(order)
                    .channel(channel)
                    .product(locked.getProduct())
                    .batch(locked.getBatch())
                    .plannedQty(qty)
                    .build());
            total += qty;
        }
        order.setPlannedTotalQty(total);
        return removalRepository.save(order);
    }

    // ------------------------------------------------------------------
    // 补货员到场扫码 + 清点提交
    // ------------------------------------------------------------------

    /** 补货员到场扫码：绑定执行人、记录到场时间，返回任务与实时对账数据 */
    @Transactional
    public RemovalTaskView scan(String removalNo, String username) {
        RemovalOrder order = mustPending(removalNo);
        AppUser user = userRepository.findByUsername(username)
                .orElseThrow(() -> ApiException.badRequest("补货员账号不存在"));
        if (order.getAssignee() == null) order.setAssignee(user);
        order.setScannedAt(LocalDateTime.now());
        removalRepository.save(order);
        return taskView(order);
    }

    /**
     * 扫码清点提交（四去向）。
     * counts: 下架明细行 itemId + 实际清点数量 countedQty + 去向 disposition + 照片。
     * 同一明细行需要拆分多个去向时，提交多条相同 itemId 记录。
     */
    @Transactional
    public RemovalOrder countAndExecute(String removalNo, String username,
                                        List<CountLine> counts, String remark) {
        RemovalOrder order = mustPending(removalNo);
        AppUser executor = userRepository.findByUsername(username)
                .orElseThrow(() -> ApiException.badRequest("补货员账号不存在"));
        if (order.getAssignee() == null) order.setAssignee(executor);
        if (order.getScannedAt() == null) order.setScannedAt(LocalDateTime.now());

        List<RemovalItem> items = removalItemRepository.findByRemovalOrderId(order.getId());
        Map<Long, List<CountLine>> byItem = new LinkedHashMap<>();
        for (CountLine c : counts) {
            byItem.computeIfAbsent(c.removalItemId(), k -> new ArrayList<>()).add(c);
        }

        // 统一全局加锁顺序 cabinet(id) -> batch(id) -> stock(货道,到期日,id)：
        // 清点收尾可能对该批次所在的其他柜机下发复售指令，故需预先锁齐批次涉及的全部柜机。
        List<Long> batchIds = items.stream().map(i -> i.getBatch().getId()).distinct().sorted().toList();
        java.util.SortedSet<Long> cabinetIds = new java.util.TreeSet<>();
        for (Long bid : batchIds) {
            stockRepository.findByBatchId(bid).forEach(s ->
                    cabinetIds.add(s.getChannel().getCabinet().getId()));
        }
        cabinetIds.add(order.getCabinet().getId());
        Map<Long, Cabinet> lockedCabinets = new LinkedHashMap<>();
        for (Long cid : cabinetIds) {
            lockedCabinets.put(cid,
                    entityManager.find(Cabinet.class, cid, LockModeType.PESSIMISTIC_WRITE));
        }
        Map<Long, ProductBatch> batchLocks = new LinkedHashMap<>();
        for (Long bid : batchIds) {
            batchLocks.put(bid,
                    lockFresh(batchRepository.findById(bid).orElseThrow()));
        }
        Map<Long, ChannelStock> stockByItem = new LinkedHashMap<>();
        items.stream()
                .sorted(Comparator.comparing((RemovalItem i) -> i.getChannel().getId())
                        .thenComparing(i -> i.getBatch().getExpiryDate())
                        .thenComparing(i -> i.getBatch().getId()))
                .forEach(i -> {
                    ChannelStock s = stockRepository.findByChannelIdAndBatchId(
                                    i.getChannel().getId(), i.getBatch().getId())
                            .orElseThrow(() -> ApiException.conflict("库存记录缺失"));
                    stockByItem.put(i.getId(),
                            lockFresh(stockRepository.findById(s.getId()).orElseThrow()));
                });

        int resolvedTotal = 0;
        int soldWhilePendingTotal = 0;
        int shortageTotal = 0;
        Set<ProductBatch> touchedBatches = new LinkedHashSet<>();

        for (RemovalItem item : items) {
            ProductBatch lockedBatch = batchLocks.get(item.getBatch().getId());
            ChannelStock stock = stockByItem.get(item.getId());

            int planned = item.getPlannedQty();
            int onHand = stock.getQtyOnHand();

            // 停售之后该货道该批次被用户买走的数量（与开门订单扣款对账）
            LocalDateTime blockedSince = lockedBatch.getBlockedAt() != null
                    ? lockedBatch.getBlockedAt() : order.getScheduledAt();
            int soldAfterBlock = orderItemRepository.sumSoldByBatchChannelSince(
                    item.getBatch().getId(), item.getChannel().getCode(),
                    order.getCabinet().getId(), blockedSince);

            List<CountLine> lines = byItem.getOrDefault(item.getId(), List.of());
            if (lines.isEmpty()) {
                throw ApiException.badRequest("缺少货道 " + item.getChannel().getCode()
                        + " 批次 " + item.getBatch().getBatchNo() + " 的清点数量");
            }
            int counted = lines.stream().mapToInt(CountLine::countedQty).sum();
            if (counted < 0) throw ApiException.badRequest("清点数量不能为负");
            if (counted > onHand) {
                throw ApiException.conflict(String.format(
                        "货道 %s 批次 %s 清点数量 %d 超过当前在库 %d（停售后已售 %d）",
                        item.getChannel().getCode(), item.getBatch().getBatchNo(),
                        counted, onHand, soldAfterBlock));
            }

            int shortage;
            if (order.isFullBlock()) {
                // 整批停售：实物清点总量必须覆盖当前在库，账有实无即盘亏
                shortage = onHand - counted;
                // 账面对账参考：停售快照 = 停售后售出 + 当前在库（+退款收回等其他流水）
                int expectedOnHand = Math.max(0, planned - soldAfterBlock);
                if (expectedOnHand != onHand) {
                    log.info("批次 {} 货道 {} 在库 {} 与快照推算 {} 存在差异（含退款收回/盘点调整等其他流水），以实物清点为准",
                            item.getBatch().getBatchNo(), item.getChannel().getCode(),
                            onHand, expectedOnHand);
                }
            } else {
                // 部分下架：任务数量已在库存行隔离预留（不会被售出），清点不足即为实物短少
                if (counted > planned) {
                    throw ApiException.conflict(String.format(
                            "货道 %s 批次 %s 清点处置 %d 超过任务数量 %d",
                            item.getChannel().getCode(), item.getBatch().getBatchNo(), counted, planned));
                }
                shortage = planned - counted;
            }

            // 逐去向处置
            int scrap = 0, recycle = 0, retSupplier = 0, resale = 0;
            String photoUrl = null;
            for (CountLine line : lines) {
                int q = line.countedQty();
                if (q <= 0) continue;
                photoUrl = line.photoUrl();
                applyDisposition(order, item, stock, line.disposition(), q);
                switch (line.disposition()) {
                    case SCRAP -> scrap += q;
                    case RECYCLE -> recycle += q;
                    case RETURN_SUPPLIER -> retSupplier += q;
                    case RESALE -> resale += q;
                }
            }
            // 整批任务：实物短少统一盘亏报损（运营责任），保证账实一致
            if (shortage > 0) {
                applyShortage(order, item, stock, shortage);
            }

            // 明细回写（executedQty 为实物处置合计，不含盘亏；主去向取数量最大者）
            item.setCountedQty(counted);
            item.setExecutedQty(counted);
            item.setDisposition(primaryDisposition(scrap, recycle, retSupplier, resale));
            item.setPhotoUrl(photoUrl);
            removalItemRepository.save(item);

            int disposed = scrap + recycle + retSupplier + resale;
            if (order.isFullBlock()) {
                // 实物守恒：当前在库 = 已处置去向 + 盘亏短少；售出部分已在订单侧留痕
                if (disposed + shortage != onHand) {
                    throw ApiException.conflict(String.format(
                            "批次 %s 货道 %s 对账不平：在库 %d != 处置 %d + 短少 %d",
                            item.getBatch().getBatchNo(), item.getChannel().getCode(),
                            onHand, disposed, shortage));
                }
            } else {
                // 预留守恒：任务数量 = 处置去向 + 短少
                if (disposed + shortage != planned) {
                    throw ApiException.conflict(String.format(
                            "批次 %s 货道 %s 预留对账不平：任务 %d != 处置 %d + 短少 %d",
                            item.getBatch().getBatchNo(), item.getChannel().getCode(),
                            planned, disposed, shortage));
                }
            }

            resolvedTotal += disposed + shortage;
            soldWhilePendingTotal += soldAfterBlock;
            shortageTotal += shortage;
            touchedBatches.add(item.getBatch());
        }

        order.setStatus(RemovalStatus.EXECUTED);
        order.setExecutor(executor);
        order.setExecutedAt(LocalDateTime.now());
        order.setResolvedTotalQty(resolvedTotal);
        order.setSoldWhilePendingQty(soldWhilePendingTotal);
        order.setShortageQty(shortageTotal);
        order.setOverdue(false);
        if (remark != null) order.setRemark(remark);
        removalRepository.save(order);

        // 批次收尾：在库清零则 REMOVED；仍有复售在库且无其他待处理任务 -> 解除批次停售并下发复售
        for (ProductBatch b : touchedBatches) {
            finalizeBatchAfterRemoval(b);
        }

        bizEventPublisher.publish("REMOVAL_EXECUTED", order.getOrderNo(),
                order.getCabinet().getCabinetNo(), null,
                "清点完成 处置 " + resolvedTotal + " 件，停售后已售核销 "
                        + soldWhilePendingTotal + "，盘亏 " + shortageTotal, null);
        return order;
    }

    private Disposition primaryDisposition(int scrap, int recycle, int ret, int resale) {
        if (scrap >= recycle && scrap >= ret && scrap >= resale && scrap > 0) return Disposition.SCRAP;
        if (recycle >= ret && recycle >= resale && recycle > 0) return Disposition.RECYCLE;
        if (ret >= resale && ret > 0) return Disposition.RETURN_SUPPLIER;
        return Disposition.RESALE;
    }

    /** 按去向处置实物数量并记账 */
    private void applyDisposition(RemovalOrder order, RemovalItem item, ChannelStock stock,
                                  Disposition disp, int q) {
        ProductBatch batch = item.getBatch();
        BigDecimal cost = batch.getCostPrice() != null
                ? batch.getCostPrice().multiply(BigDecimal.valueOf(q)) : BigDecimal.ZERO;
        switch (disp) {
            case SCRAP -> {
                removeFromStock(stock, q);
                ledgerService.record(batch, order.getCabinet(), LedgerType.REMOVAL_OUT, -q,
                        cost, "REMOVAL_ORDER", order.getId(),
                        order.getReason() + " 下架报损 " + item.getChannel().getCode() + " x" + q);
                LossReason lossReason = mapLossReason(order.getReason());
                // 临期/过期/召回/温控（供应商交货保质期问题）默认供应商责任；运营操作类归运营
                String liability = switch (order.getReason()) {
                    case TEMP_ABUSE, DAMAGED, WRONG_CHANNEL -> "OPERATOR";
                    default -> "SUPPLIER";
                };
                lossRepository.save(LossRecord.builder()
                        .lossNo(genNo("LS"))
                        .cabinet(order.getCabinet()).product(item.getProduct()).batch(batch)
                        .reason(lossReason).qty(q).costAmount(cost)
                        .liability(liability)
                        .bizType("REMOVAL_ORDER").bizId(order.getId())
                        .remark(order.getReason() + " 补货员清点报损").build());
                bizEventPublisher.publish("LOSS_RECORDED", genNo("LS"),
                        order.getCabinet().getCabinetNo(), batch.getBatchNo(),
                        lossReason + " x" + q, cost);
            }
            case RECYCLE -> {
                removeFromStock(stock, q);
                ledgerService.record(batch, order.getCabinet(), LedgerType.REMOVAL_OUT, -q,
                        cost, "REMOVAL_ORDER", order.getId(),
                        order.getReason() + " 下架回收 " + item.getChannel().getCode() + " x" + q);
            }
            case RETURN_SUPPLIER -> {
                removeFromStock(stock, q);
                ledgerService.record(batch, order.getCabinet(), LedgerType.REMOVAL_OUT, -q,
                        cost, "REMOVAL_ORDER", order.getId(),
                        order.getReason() + " 退回供应商 " + item.getChannel().getCode() + " x" + q);
                bizEventPublisher.publish("RETURN_SUPPLIER", order.getOrderNo(),
                        order.getCabinet().getCabinetNo(), batch.getBatchNo(),
                        "退供应商 x" + q, cost);
            }
            case RESALE -> {
                // 商品留在货道：仅解除这部分隔离，在库数量不变
                stock.setQtyQuarantined(Math.max(0, stock.getQtyQuarantined() - q));
                stockRepository.save(stock);
                ledgerService.record(batch, order.getCabinet(), LedgerType.RESALE_UNBLOCK, 0,
                        BigDecimal.ZERO, "REMOVAL_ORDER", order.getId(),
                        "清点确认仍可销售 " + item.getChannel().getCode() + " x" + q + "，解除隔离");
            }
        }
    }

    /** 盘亏短少：从在库/隔离中核减并记 SHRINKAGE 报损（运营责任） */
    private void applyShortage(RemovalOrder order, RemovalItem item, ChannelStock stock, int q) {
        removeFromStock(stock, q);
        ProductBatch batch = item.getBatch();
        BigDecimal cost = batch.getCostPrice() != null
                ? batch.getCostPrice().multiply(BigDecimal.valueOf(q)) : BigDecimal.ZERO;
        ledgerService.record(batch, order.getCabinet(), LedgerType.ADJUST_MINUS, -q,
                cost, "REMOVAL_ORDER", order.getId(),
                "下架清点盘亏(账有实无) " + item.getChannel().getCode() + " x" + q);
        lossRepository.save(LossRecord.builder()
                .lossNo(genNo("LS"))
                .cabinet(order.getCabinet()).product(item.getProduct()).batch(batch)
                .reason(LossReason.SHRINKAGE).qty(q).costAmount(cost)
                .liability("OPERATOR")
                .bizType("REMOVAL_ORDER").bizId(order.getId())
                .remark("临期下架清点短少，自动盘亏").build());
    }

    /** 从货道批次库存核减（同步核减等量隔离/预留量） */
    private void removeFromStock(ChannelStock stock, int q) {
        if (stock.getQtyOnHand() < q) {
            throw ApiException.conflict("库存不足，无法核减 " + q + " 件");
        }
        stock.setQtyOnHand(stock.getQtyOnHand() - q);
        stock.setQtyQuarantined(Math.max(0, stock.getQtyQuarantined() - q));
        stockRepository.save(stock);
    }

    /**
     * 批次收尾：
     * - 全量在库为 0 -> REMOVED（保持停售/限流，不再曝光）；
     * - 仍有在库且该批次已无待执行下架任务 -> 解除批次停售，对涉及柜机下发复售指令。
     */
    private void finalizeBatchAfterRemoval(ProductBatch b0) {
        // 清点事务内已持有批次/库存锁，此处用查询锁而非 refresh（refresh 会冲掉同事务未落库脏值）
        ProductBatch b = batchRepository.lockById(b0.getId()).orElseThrow();
        int remain = stockRepository.sumOnHandByBatch(b.getId());
        boolean pendingExists = removalItemRepository.existsOpenTaskByBatch(b.getId());
        if (remain <= 0) {
            if (b.getStatus() == BatchStatus.IN_STOCK) {
                b.setStatus(BatchStatus.REMOVED);
            }
            // 已下架完的批次维持 saleBlocked/exposureBlocked，杜绝再次曝光
            batchRepository.save(b);
            return;
        }
        if (!pendingExists && b.isSaleBlocked()) {
            b.setSaleBlocked(false);
            b.setBlockedAt(null);
            b.setBlockReason(null);
            // 任务已处理完且确认仍有可售商品：解除逾期限流，允许该批次正常曝光
            if (b.isExposureBlocked()) {
                b.setExposureBlocked(false);
                b.setExposureBlockedAt(null);
            }
            batchRepository.save(b);
            // 释放该批次剩余库存的隔离量，恢复 FEFO 可售（这些行在清点阶段已加锁）；对仍有在库的柜机下发复售指令
            for (ChannelStock s : stockRepository.findByBatchId(b.getId())) {
                if (s.getQtyOnHand() > 0 && s.getQtyQuarantined() > 0) {
                    s.setQtyQuarantined(0);
                    stockRepository.save(s);
                }
                if (s.getQtyOnHand() > 0) {
                    deviceCommandService.issueUnblock(s.getChannel().getCabinet(), b.getBatchNo(),
                            "REMOVAL_ORDER", null, "清点后部分商品仍可销售，恢复售卖");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 旧接口兼容：整单按计划数量、统一去向执行（无分批清点）
    // ------------------------------------------------------------------

    @Transactional
    public RemovalOrder execute(String removalNo, String executorUsername,
                                Disposition defaultDisposition, String remark) {
        RemovalOrder order = mustPending(removalNo);
        List<RemovalItem> items = removalItemRepository.findByRemovalOrderId(order.getId());
        List<CountLine> lines = new ArrayList<>();
        for (RemovalItem item : items) {
            lines.add(new CountLine(item.getId(), item.getPlannedQty(), defaultDisposition, null));
        }
        return countAndExecute(removalNo, executorUsername, lines, remark);
    }

    // ------------------------------------------------------------------
    // 逾期巡检与限流曝光
    // ------------------------------------------------------------------

    /** 巡检逾期任务：超过截止时间未执行 -> 逾期标记 + 批次限流曝光，返回处理数 */
    @Transactional
    public int markOverdueTasks() {
        List<RemovalOrder> overdueOrders =
                removalRepository.findByStatusAndDueAtBefore(RemovalStatus.PENDING, LocalDateTime.now());
        int n = 0;
        for (RemovalOrder o : overdueOrders) {
            if (o.isOverdue()) continue;
            o.setOverdue(true);
            removalRepository.save(o);
            alertService.raise(o.getCabinet(), AlertType.REMOVAL_OVERDUE, "WARN",
                    "下架任务 " + o.getOrderNo() + " 已逾期未处理，批次已限制再次曝光",
                    "REMOVAL_ORDER", o.getId());
            for (RemovalItem item : removalItemRepository.findByRemovalOrderId(o.getId())) {
                ProductBatch b = lockFresh(
                        batchRepository.findById(item.getBatch().getId()).orElseThrow());
                if (!b.isExposureBlocked()) {
                    b.setExposureBlocked(true);
                    b.setExposureBlockedAt(LocalDateTime.now());
                    batchRepository.save(b);
                }
            }
            bizEventPublisher.publish("REMOVAL_OVERDUE", o.getOrderNo(),
                    o.getCabinet().getCabinetNo(), null,
                    "临期下架任务逾期，批次限制曝光", null);
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------
    // 查询视图
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public RemovalTaskView taskViewByNo(String removalNo) {
        return taskView(removalRepository.findByOrderNo(removalNo)
                .orElseThrow(() -> ApiException.notFound("下架单不存在")));
    }

    @Transactional(readOnly = true)
    public RemovalTaskView taskView(RemovalOrder order) {
        List<RemovalItem> items = removalItemRepository.findByRemovalOrderId(order.getId());
        List<ItemLive> liveItems = new ArrayList<>();
        int soldTotal = 0;
        for (RemovalItem item : items) {
            ChannelStock stock = stockRepository
                    .findByChannelIdAndBatchId(item.getChannel().getId(), item.getBatch().getId())
                    .orElse(null);
            int onHand = stock != null ? stock.getQtyOnHand() : 0;
            LocalDateTime since = item.getBatch().getBlockedAt() != null
                    ? item.getBatch().getBlockedAt() : order.getScheduledAt();
            int sold = orderItemRepository.sumSoldByBatchChannelSince(
                    item.getBatch().getId(), item.getChannel().getCode(),
                    order.getCabinet().getId(), since);
            soldTotal += sold;
            liveItems.add(new ItemLive(item, onHand, sold, item.getPlannedQty() - sold - onHand));
        }
        boolean overdue = order.getStatus() == RemovalStatus.PENDING
                && order.getDueAt() != null && order.getDueAt().isBefore(LocalDateTime.now());
        return new RemovalTaskView(order, items, liveItems, soldTotal, overdue);
    }

    private RemovalOrder mustPending(String removalNo) {
        RemovalOrder order = removalRepository.findByOrderNo(removalNo)
                .orElseThrow(() -> ApiException.notFound("下架任务不存在: " + removalNo));
        if (order.getStatus() == RemovalStatus.EXECUTED) {
            throw ApiException.conflict("下架任务已执行完成");
        }
        if (order.getStatus() == RemovalStatus.CANCELLED) {
            throw ApiException.conflict("下架任务已取消");
        }
        return order;
    }

    private LossReason mapLossReason(RemovalReason reason) {
        return switch (reason) {
            case EXPIRED -> LossReason.SCRAP_EXPIRED;
            case NEAR_EXPIRY -> LossReason.SCRAP_NEAR_EXPIRY;
            case TEMP_ABUSE -> LossReason.TEMP_ABUSE;
            case DAMAGED -> LossReason.SCRAP_DAMAGED;
            case WRONG_CHANNEL -> LossReason.SHRINKAGE;
            case RECALLED -> LossReason.SCRAP_DAMAGED;
        };
    }

    private String genNo(String prefix) {
        return prefix + LocalDate.now().toString().replace("-", "")
                + String.format("%04d", System.nanoTime() % 10000);
    }

    /** 手工下架行 */
    public record ManualRemovalLine(String channelCode, String batchNo, Integer qty) {}

    /** 扫码清点行：明细ID + 实际数量 + 去向 + 照片 */
    public record CountLine(Long removalItemId, Integer countedQty,
                            Disposition disposition, String photoUrl) {}

    /** 明细实时对账数据 */
    public record ItemLive(RemovalItem item, int currentOnHand,
                           int soldWhilePending, int unexplained) {}

    /** 补货员扫码后的任务视图 */
    public record RemovalTaskView(RemovalOrder order, List<RemovalItem> items,
                                  List<ItemLive> liveItems, int soldWhilePending,
                                  boolean overdue) {}
}
