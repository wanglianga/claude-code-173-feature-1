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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 临期下架/处置服务。核心规则：
 *
 * 1) 批次级停售：批次进入临期范围的同一事务内即停止售卖——
 *    仅把该批次在各货道的可售数量计入 qtySaleHalted，同货道其他批次照常售卖；
 *    柜机离线不影响服务端库存账，柜机上线后通过 sync 接口拉取停售批次清单。
 * 2) 任务制：为每个“柜机 × 批次”生成一张下架任务单（补货员扫码领取），
 *    补货员到场清点实盘数量，按报损/回收/退供应商/仍可销售四种去向登记，
 *    数量与系统账自动对账，差异（开门在途售出等）留痕告警，不留糊涂账。
 * 3) 并发一致：库存行走悲观锁，售卖扣款与下架清点串行化；
 *    FEFO 只扣未停售、未限制曝光的批次，因此停售后不会再卖、临期开门在途也不会误扣临期批。
 * 4) 逾期：任务超过时限未处理，运营端可见逾期任务，批次被限制曝光，需运营解封。
 * 5) 全部数量、去向、成本变化逐笔写入批次台账，可还原“为什么下架、最终去了哪里”。
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
    private final CabinetRepository cabinetRepository;
    private final BatchLedgerService ledgerService;
    private final AlertService alertService;
    private final BizEventPublisher bizEventPublisher;

    @Value("${cabinet.expiry.task-due-hours:24}")
    private int taskDueHours;

    /** 扫描临期/过期在库批次：按批次停售，并按“柜机 × 批次”生成待处理任务 */
    @Transactional
    public int scanExpiry(int nearExpiryDays) {
        LocalDate today = LocalDate.now();
        LocalDate threshold = today.plusDays(nearExpiryDays);
        List<ProductBatch> batches = batchRepository
                .findByStatusAndExpiryDateLessThanEqual(BatchStatus.IN_STOCK, threshold);
        int created = 0;
        for (ProductBatch batch : batches) {
            List<ChannelStock> stocks = stockRepository.findByBatchId(batch.getId());
            Map<Long, List<ChannelStock>> byCabinet = new LinkedHashMap<>();
            for (ChannelStock s : stocks) {
                if (s.getQtyOnHand() - s.getQtyQuarantined() - s.getQtySaleHalted() <= 0) continue;
                byCabinet.computeIfAbsent(s.getChannel().getCabinet().getId(), k -> new ArrayList<>()).add(s);
            }
            for (var entry : byCabinet.entrySet()) {
                Cabinet cabinet = entry.getValue().get(0).getChannel().getCabinet();
                if (removalRepository.existsActive(cabinet.getId(), batch.getId(),
                        List.of(RemovalStatus.PENDING, RemovalStatus.ACCEPTED))) {
                    continue;
                }
                boolean expired = !batch.getExpiryDate().isAfter(today);
                RemovalReason reason = expired ? RemovalReason.EXPIRED : RemovalReason.NEAR_EXPIRY;
                created += createHaltedTask(cabinet, batch, reason,
                        entry.getValue(), "SYSTEM",
                        expired ? "过期紧急下架" : "临期预防性下架",
                        expired ? AlertType.EXPIRED : AlertType.NEAR_EXPIRY,
                        expired ? "CRITICAL" : "WARN") ? 1 : 0;
            }
        }
        return created;
    }

    /**
     * 手工创建下架单（温控异常 / 卡货损坏 / 放错货道纠正 / 召回），同样先停售后处置。
     * items: channelCode + batchNo(可空，空则 FEFO) + qty
     */
    @Transactional
    public RemovalOrder createManualOrder(Cabinet cabinet, RemovalReason reason,
                                          List<ManualRemovalLine> lines) {
        RemovalOrder order = RemovalOrder.builder()
                .orderNo(genNo("RM"))
                .cabinet(cabinet)
                .triggeredBy("OPERATOR")
                .reason(reason)
                .status(RemovalStatus.PENDING)
                .scheduledAt(LocalDateTime.now())
                .dueAt(LocalDateTime.now().plusHours(taskDueHours))
                .build();
        removalRepository.save(order);

        for (ManualRemovalLine line : lines) {
            Channel channel = channelRepository
                    .findByCabinetIdAndCode(cabinet.getId(), line.channelCode())
                    .orElseThrow(() -> ApiException.badRequest("货道不存在: " + line.channelCode()));
            List<ChannelStock> stocks = stockRepository.findByChannelId(channel.getId());
            ChannelStock target;
            if (line.batchNo() != null) {
                ProductBatch b = batchRepository.findByBatchNo(line.batchNo())
                        .orElseThrow(() -> ApiException.badRequest("批次不存在"));
                target = stocks.stream().filter(s -> s.getBatch().getId().equals(b.getId()))
                        .findFirst().orElseThrow(() -> ApiException.badRequest("货道无该批次"));
            } else {
                target = stocks.stream()
                        .filter(s -> s.getQtyOnHand() > 0)
                        .min(Comparator.comparing(s -> s.getBatch().getExpiryDate()))
                        .orElseThrow(() -> ApiException.badRequest("货道无在库"));
            }
            ProductBatch batch = target.getBatch();
            // 先停售：锁定库存行，把可售部分转入停售量（温控隔离量、已停售量不重复计入）
            ChannelStock locked = stockRepository.lockByChannelIdAndBatchId(channel.getId(), batch.getId())
                    .orElseThrow(() -> ApiException.conflict("库存记录缺失"));
            int haltQty = Math.min(line.qty(), locked.sellableQty());
            if (haltQty <= 0) {
                throw ApiException.conflict("货道 " + channel.getCode() + " 批次 " + batch.getBatchNo()
                        + " 无待处置可售库存（可能已隔离或已停售）");
            }
            locked.setQtySaleHalted(locked.getQtySaleHalted() + haltQty);
            stockRepository.save(locked);
            markBatchHalted(batch, reason.name());
            ledgerService.record(batch, cabinet, LedgerType.SALE_BLOCK, 0, BigDecimal.ZERO,
                    "REMOVAL_ORDER", order.getId(),
                    reason + " 手工下架停售 " + channel.getCode() + " x" + haltQty);
            removalItemRepository.save(RemovalItem.builder()
                    .removalOrder(order)
                    .channel(channel)
                    .product(target.getProduct())
                    .batch(batch)
                    .plannedQty(haltQty)
                    .scrapQty(0).recycleQty(0).returnSupplierQty(0).saleableQty(0)
                    .build());
        }
        return order;
    }

    /**
     * 停售并生成任务的公共方法（行级悲观锁，避免与开门扣款并发互相覆盖）。
     */
    private boolean createHaltedTask(Cabinet cabinet, ProductBatch batch, RemovalReason reason,
                                     List<ChannelStock> stockLines, String triggeredBy,
                                     String taskRemark, AlertType alertType, String severity) {
        // 按库存行 id 排序加锁，防止多事务交叉加锁死锁
        List<ChannelStock> locked = new ArrayList<>();
        for (ChannelStock s : stockLines.stream().sorted(Comparator.comparing(ChannelStock::getId)).toList()) {
            stockRepository.lockById(s.getId()).ifPresent(locked::add);
        }
        int haltTotal = 0;
        List<RemovalItem> items = new ArrayList<>();
        RemovalOrder order = RemovalOrder.builder()
                .orderNo(genNo("RM"))
                .cabinet(cabinet)
                .triggeredBy(triggeredBy)
                .reason(reason)
                .status(RemovalStatus.PENDING)
                .scheduledAt(LocalDateTime.now())
                .dueAt(LocalDateTime.now().plusHours(taskDueHours))
                .remark(taskRemark)
                .build();
        removalRepository.save(order);

        for (ChannelStock s : locked) {
            int sellable = s.getQtyOnHand() - s.getQtyQuarantined() - s.getQtySaleHalted();
            if (sellable <= 0) continue;
            s.setQtySaleHalted(s.getQtySaleHalted() + sellable);
            stockRepository.save(s);
            haltTotal += sellable;
            items.add(RemovalItem.builder()
                    .removalOrder(order)
                    .channel(s.getChannel())
                    .product(s.getProduct())
                    .batch(batch)
                    .plannedQty(sellable)
                    .scrapQty(0).recycleQty(0).returnSupplierQty(0).saleableQty(0)
                    .build());
        }
        if (haltTotal == 0) {
            // 并发下可售量已被其他事务（在途订单）清空，撤销空任务
            removalRepository.delete(order);
            return false;
        }
        items.forEach(removalItemRepository::save);
        markBatchHalted(batch, reason.name());
        for (RemovalItem ri : items) {
            ledgerService.record(batch, cabinet, LedgerType.SALE_BLOCK, 0, BigDecimal.ZERO,
                    "REMOVAL_ORDER", order.getId(),
                    reason + " 批次停售 " + ri.getChannel().getCode() + " x" + ri.getPlannedQty());
        }
        alertService.raise(cabinet, alertType, severity,
                String.format("批次 %s %s，已在柜 %s 停止该批次售卖并生成下架任务 %s，停售 %d 件",
                        batch.getBatchNo(), taskRemark, cabinet.getCabinetNo(), order.getOrderNo(), haltTotal),
                "BATCH", batch.getId());
        bizEventPublisher.publish("BATCH_SALE_HALTED", order.getOrderNo(),
                cabinet.getCabinetNo(), batch.getBatchNo(),
                reason + " 批次停售，任务 " + order.getOrderNo() + "，停售 " + haltTotal + " 件", null);
        return true;
    }

    /** 补货员扫码领取任务（任务单号即二维码内容） */
    @Transactional
    public RemovalOrder accept(String removalNo, String username) {
        RemovalOrder order = must(removalNo);
        if (order.getStatus() == RemovalStatus.EXECUTED) {
            throw ApiException.conflict("任务已执行完成");
        }
        if (order.getStatus() == RemovalStatus.CANCELLED) {
            throw ApiException.conflict("任务已取消");
        }
        AppUser user = userRepository.findByUsername(username)
                .orElseThrow(() -> ApiException.badRequest("补货员不存在"));
        if (order.getStatus() == RemovalStatus.ACCEPTED
                && !order.getAcceptedBy().getId().equals(user.getId())) {
            throw ApiException.conflict("任务已被补货员 " + order.getAcceptedBy().getUsername() + " 领取");
        }
        if (order.getStatus() == RemovalStatus.PENDING) {
            order.setStatus(RemovalStatus.ACCEPTED);
            order.setAcceptedBy(user);
            order.setAcceptedAt(LocalDateTime.now());
            removalRepository.save(order);
        }
        return order;
    }

    /** 旧接口兼容：整单按同一去向执行（SCRAP / RETURN_SUPPLIER / RECYCLE） */
    @Transactional
    public RemovalOrder execute(String removalNo, String executorUsername,
                                Disposition defaultDisposition, String remark) {
        RemovalOrder order = must(removalNo);
        List<RemovalItem> items = removalItemRepository.findByRemovalOrderId(order.getId());
        List<CountLine> lines = new ArrayList<>();
        for (RemovalItem item : items) {
            int qty = item.getPlannedQty() == null ? 0 : item.getPlannedQty();
            lines.add(new CountLine(item.getId(), qty,
                    defaultDisposition == Disposition.SCRAP ? qty : 0,
                    defaultDisposition == Disposition.RECYCLE ? qty : 0,
                    defaultDisposition == Disposition.RETURN_SUPPLIER ? qty : 0,
                    0, null));
        }
        return countAndExecute(removalNo, executorUsername, lines, remark);
    }

    /**
     * 补货员现场清点并按去向执行。
     * 每行：实点数量 + 报损/回收/退供/仍可销售拆分（四类之和必须等于实点数）。
     * 实点数 < 系统停售账：视为账实差异（多为开门在途已售出），记录差异并告警，不重复出库。
     */
    @Transactional
    public RemovalOrder countAndExecute(String removalNo, String executorUsername,
                                        List<CountLine> lines, String remark) {
        RemovalOrder order = must(removalNo);
        if (order.getStatus() == RemovalStatus.EXECUTED) {
            throw ApiException.conflict("下架任务已执行");
        }
        if (order.getStatus() == RemovalStatus.CANCELLED) {
            throw ApiException.conflict("下架任务已取消");
        }
        AppUser executor = userRepository.findByUsername(executorUsername)
                .orElseThrow(() -> ApiException.badRequest("补货员不存在"));
        // 允许补货员扫执行码一步完成：PENDING 时自动领取
        if (order.getStatus() == RemovalStatus.PENDING) {
            order.setStatus(RemovalStatus.ACCEPTED);
            order.setAcceptedBy(executor);
            order.setAcceptedAt(LocalDateTime.now());
        } else if (!order.getAcceptedBy().getId().equals(executor.getId())) {
            throw ApiException.conflict("任务由另一补货员领取，不能代为执行");
        }

        List<RemovalItem> items = removalItemRepository.findByRemovalOrderId(order.getId());
        Map<Long, RemovalItem> itemMap = new LinkedHashMap<>();
        items.forEach(i -> itemMap.put(i.getId(), i));

        int countedTotal = 0;
        int shortageTotal = 0;
        List<ProductBatch> touchedBatches = new ArrayList<>();

        for (CountLine line : lines) {
            RemovalItem item = itemMap.get(line.removalItemId());
            if (item == null) {
                throw ApiException.badRequest("清点行不属于该任务: " + line.removalItemId());
            }
            int counted = nz(line.countedQty());
            int scrap = nz(line.scrapQty());
            int recycle = nz(line.recycleQty());
            int retSupplier = nz(line.returnSupplierQty());
            int saleable = nz(line.saleableQty());
            int removed = scrap + recycle + retSupplier;
            if (counted < 0 || scrap < 0 || recycle < 0 || retSupplier < 0 || saleable < 0) {
                throw ApiException.badRequest("数量不能为负");
            }
            if (scrap + recycle + retSupplier + saleable != counted) {
                throw ApiException.badRequest(String.format(
                        "货道 %s 批次 %s 去向数量合计 %d 与实点数量 %d 不一致",
                        item.getChannel().getCode(), item.getBatch().getBatchNo(),
                        scrap + recycle + retSupplier + saleable, counted));
            }
            int planned = nz(item.getPlannedQty());
            int shortage = Math.max(0, planned - counted);

            // 锁定货道批次库存行：与关门结算串行化，保证库存/订单/下架对得上
            ChannelStock stock = stockRepository
                    .lockByChannelIdAndBatchId(item.getChannel().getId(), item.getBatch().getId())
                    .orElseThrow(() -> ApiException.conflict("库存记录缺失"));
            if (removed > stock.getQtyOnHand()) {
                throw ApiException.conflict(String.format(
                        "货道 %s 批次 %s 实物出库 %d 件超过在库 %d 件",
                        item.getChannel().getCode(), item.getBatch().getBatchNo(),
                        removed, stock.getQtyOnHand()));
            }

            stock.setQtyOnHand(stock.getQtyOnHand() - removed);
            stock.setQtyQuarantined(Math.min(stock.getQtyQuarantined(), stock.getQtyOnHand()));
            int releaseHalted = Math.min(stock.getQtySaleHalted(),
                    removed + saleable + shortage);
            stock.setQtySaleHalted(Math.max(0, stock.getQtySaleHalted() - releaseHalted));
            stockRepository.save(stock);

            ProductBatch batch = item.getBatch();
            BigDecimal unitCost = batch.getCostPrice() != null
                    ? batch.getCostPrice() : BigDecimal.ZERO;
            touchedBatches.add(batch);

            if (scrap > 0) {
                BigDecimal cost = unitCost.multiply(BigDecimal.valueOf(scrap));
                LossReason lossReason = mapLossReason(order.getReason());
                String liability = order.getReason() == RemovalReason.TEMP_ABUSE
                        || order.getReason() == RemovalReason.DAMAGED
                        || order.getReason() == RemovalReason.WRONG_CHANNEL
                        ? "OPERATOR" : "SUPPLIER";
                lossRepository.save(LossRecord.builder()
                        .lossNo(genNo("LS"))
                        .cabinet(order.getCabinet())
                        .product(item.getProduct()).batch(batch)
                        .reason(lossReason).qty(scrap).costAmount(cost)
                        .liability(liability)
                        .bizType("REMOVAL_ORDER").bizId(order.getId())
                        .remark(order.getReason() + " 现场清点报损").build());
                ledgerService.record(batch, order.getCabinet(), LedgerType.REMOVAL_OUT, -scrap,
                        cost, "REMOVAL_ORDER", order.getId(),
                        order.getReason() + " 下架报损 " + item.getChannel().getCode() + " x" + scrap);
                bizEventPublisher.publish("LOSS_RECORDED", order.getOrderNo(),
                        order.getCabinet().getCabinetNo(), batch.getBatchNo(),
                        "报损 x" + scrap, cost);
            }
            if (recycle > 0) {
                BigDecimal cost = unitCost.multiply(BigDecimal.valueOf(recycle));
                ledgerService.record(batch, order.getCabinet(), LedgerType.REMOVAL_OUT, -recycle,
                        cost, "REMOVAL_ORDER", order.getId(),
                        order.getReason() + " 下架回收 " + item.getChannel().getCode() + " x" + recycle);
            }
            if (retSupplier > 0) {
                BigDecimal cost = unitCost.multiply(BigDecimal.valueOf(retSupplier));
                ledgerService.record(batch, order.getCabinet(), LedgerType.REMOVAL_OUT, -retSupplier,
                        cost, "REMOVAL_ORDER", order.getId(),
                        order.getReason() + " 退回供应商 " + item.getChannel().getCode() + " x" + retSupplier);
            }
            if (saleable > 0) {
                ledgerService.record(batch, order.getCabinet(), LedgerType.SALE_UNBLOCK, 0,
                        BigDecimal.ZERO, "REMOVAL_ORDER", order.getId(),
                        "现场复检仍可销售，恢复售卖 " + item.getChannel().getCode() + " x" + saleable);
            }
            if (shortage > 0) {
                ledgerService.record(batch, order.getCabinet(), LedgerType.SALE_UNBLOCK, 0,
                        BigDecimal.ZERO, "REMOVAL_ORDER", order.getId(),
                        "账实差异核销停售 " + item.getChannel().getCode() + " x" + shortage
                                + "（系统账有、现场无，多为在途订单已售/盘点差异）");
                shortageTotal += shortage;
            }

            item.setCountedQty(counted);
            item.setScrapQty(scrap);
            item.setRecycleQty(recycle);
            item.setReturnSupplierQty(retSupplier);
            item.setSaleableQty(saleable);
            item.setExecutedQty(removed);
            item.setDisposition(primaryDisposition(scrap, recycle, retSupplier, saleable));
            item.setRemark(line.remark());
            removalItemRepository.save(item);
            countedTotal += counted;
        }

        order.setStatus(RemovalStatus.EXECUTED);
        order.setExecutor(executor);
        order.setExecutedAt(LocalDateTime.now());
        order.setCountedTotal(countedTotal);
        if (remark != null) order.setRemark(remark);
        removalRepository.save(order);

        // 批次状态/停售标记回收
        for (ProductBatch b : touchedBatches.stream().distinct().toList()) {
            int remain = stockRepository.sumOnHandByBatch(b.getId());
            int haltLeft = stockRepository.sumSaleHaltedByBatch(b.getId());
            if (remain <= 0 && b.getStatus() == BatchStatus.IN_STOCK) {
                b.setStatus(BatchStatus.REMOVED);
            }
            if (haltLeft <= 0 && !b.isExposureBlocked()) {
                b.setSaleHalted(false);
                b.setHaltReason(null);
            }
            batchRepository.save(b);
        }

        if (shortageTotal > 0) {
            alertService.raise(order.getCabinet(), AlertType.STOCK_DISCREPANCY, "WARN",
                    "下架任务 " + order.getOrderNo() + " 账实差异合计 " + shortageTotal
                            + " 件（系统停售账多于现场实点，需核对在途订单）",
                    "REMOVAL_ORDER", order.getId());
        }
        bizEventPublisher.publish("REMOVAL_EXECUTED", order.getOrderNo(),
                order.getCabinet().getCabinetNo(), null,
                "清点 " + countedTotal + " 件，差异 " + shortageTotal + " 件", null);
        return order;
    }

    /** 逾期扫描：超时未执行的任务标记逾期，并限制对应批次曝光 */
    @Transactional
    public int markOverdue() {
        List<RemovalOrder> overdue = removalRepository
                .findByStatusInAndDueAtBeforeAndOverdueMarkedAtIsNull(
                        List.of(RemovalStatus.PENDING, RemovalStatus.ACCEPTED),
                        LocalDateTime.now());
        for (RemovalOrder order : overdue) {
            order.setOverdueMarkedAt(LocalDateTime.now());
            removalRepository.save(order);
            List<ProductBatch> batches = removalItemRepository.findByRemovalOrderId(order.getId())
                    .stream().map(RemovalItem::getBatch).distinct().toList();
            for (ProductBatch batch : batches) {
                if (!batch.isExposureBlocked()) {
                    batch.setExposureBlocked(true);
                    batch.setExposureBlockedAt(LocalDateTime.now());
                    batchRepository.save(batch);
                }
                alertService.raise(order.getCabinet(), AlertType.REMOVAL_OVERDUE, "CRITICAL",
                        String.format("下架任务 %s 已逾期（批次 %s，限 %s 前处理），已限制该批次曝光",
                                order.getOrderNo(), batch.getBatchNo(), order.getDueAt()),
                        "REMOVAL_ORDER", order.getId());
                bizEventPublisher.publish("BATCH_EXPOSURE_BLOCKED", order.getOrderNo(),
                        order.getCabinet().getCabinetNo(), batch.getBatchNo(),
                        "任务逾期，限制批次曝光", null);
            }
        }
        return overdue.size();
    }

    /** 运营人工解封批次曝光（现场已处置/任务关闭后） */
    @Transactional
    public ProductBatch releaseExposure(String batchNo, String reason) {
        ProductBatch batch = batchRepository.findByBatchNo(batchNo)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchNo));
        batch.setExposureBlocked(false);
        batch.setExposureBlockedAt(null);
        if (stockRepository.sumSaleHaltedByBatch(batch.getId()) <= 0) {
            batch.setSaleHalted(false);
            batch.setHaltReason(null);
        }
        batchRepository.save(batch);
        bizEventPublisher.publish("BATCH_EXPOSURE_RELEASED", batchNo, null, batchNo,
                "运营解封批次曝光: " + reason, null);
        return batch;
    }

    /**
     * 柜机上线同步：返回该柜机当前被停售/限制曝光的批次及未完成任务，
     * 柜机端据此把批次从可购货道中摘出（离线期间产生的停售指令在此补齐）。
     */
    @Transactional(readOnly = true)
    public DeviceSyncView deviceSync(String cabinetNo) {
        Cabinet cabinet = cabinetRepository.findByCabinetNo(cabinetNo)
                .orElseThrow(() -> ApiException.notFound("柜机不存在: " + cabinetNo));
        List<DeviceSyncView.HaltedBatch> halted = new ArrayList<>();
        for (ChannelStock s : stockRepository.findByCabinetId(cabinet.getId())) {
            int halt = s.getQtySaleHalted();
            boolean blocked = s.getBatch().isExposureBlocked();
            if (halt <= 0 && !blocked) continue;
            halted.add(new DeviceSyncView.HaltedBatch(
                    s.getChannel().getCode(), s.getBatch().getBatchNo(),
                    s.getProduct() != null ? s.getProduct().getSku()
                            : s.getBatch().getProduct().getSku(),
                    Math.max(halt, 0), blocked));
        }
        List<DeviceSyncView.TaskBrief> tasks = new ArrayList<>();
        for (RemovalOrder o : removalRepository.findByCabinetIdAndStatusInOrderByScheduledAtDesc(
                cabinet.getId(), List.of(RemovalStatus.PENDING, RemovalStatus.ACCEPTED))) {
            List<String> batchNos = removalItemRepository.findByRemovalOrderId(o.getId()).stream()
                    .map(i -> i.getBatch().getBatchNo()).distinct().toList();
            tasks.add(new DeviceSyncView.TaskBrief(o.getOrderNo(),
                    o.getReason() == null ? null : o.getReason().name(),
                    o.getStatus().name(),
                    o.getDueAt() == null ? null : o.getDueAt().toString(),
                    batchNos));
        }
        return new DeviceSyncView(cabinetNo, halted, tasks);
    }

    private void markBatchHalted(ProductBatch batch, String reason) {
        if (!batch.isSaleHalted()) {
            batch.setSaleHalted(true);
            batch.setSaleHaltedAt(LocalDateTime.now());
        }
        batch.setHaltReason(reason);
        batchRepository.save(batch);
    }

    private Disposition primaryDisposition(int scrap, int recycle, int ret, int saleable) {
        int best = Math.max(Math.max(scrap, recycle), Math.max(ret, saleable));
        if (best == 0) return null;
        if (scrap == best) return Disposition.SCRAP;
        if (recycle == best) return Disposition.RECYCLE;
        if (ret == best) return Disposition.RETURN_SUPPLIER;
        return Disposition.SALEABLE;
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

    private RemovalOrder must(String no) {
        return removalRepository.findByOrderNo(no)
                .orElseThrow(() -> ApiException.notFound("下架任务不存在: " + no));
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private String genNo(String prefix) {
        return prefix + LocalDate.now().toString().replace("-", "")
                + String.format("%04d", System.nanoTime() % 10000);
    }

    /** 手工下架行 */
    public record ManualRemovalLine(String channelCode, String batchNo, Integer qty) {}

    /** 现场清点行：四种去向数量之和必须等于 countedQty */
    public record CountLine(Long removalItemId, Integer countedQty,
                            Integer scrapQty, Integer recycleQty,
                            Integer returnSupplierQty, Integer saleableQty,
                            String remark) {}

    /** 柜机同步视图：停售批次清单 + 未完成任务 */
    public record DeviceSyncView(String cabinetNo,
                                 List<HaltedBatch> haltedBatches,
                                 List<TaskBrief> activeTasks) {
        public record HaltedBatch(String channelCode, String batchNo, String sku,
                                  int haltedQty, boolean exposureBlocked) {}
        public record TaskBrief(String orderNo, String reason, String status,
                                String dueAt, List<String> batchNos) {}
    }
}
