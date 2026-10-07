package com.cabinet.service;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.*;
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
 * 盘点服务：按柜机生成系统库存快照 -> 录入实盘 -> 计算差异 ->
 * 审批调整（盘亏生成报损 SHRINKAGE，批次台账 ADJUST_MINUS/PLUS）。
 */
@Service
@RequiredArgsConstructor
public class StocktakeService {

    private final StocktakeOrderRepository stocktakeRepository;
    private final StocktakeItemRepository itemRepository;
    private final ChannelStockRepository stockRepository;
    private final CabinetRepository cabinetRepository;
    private final AppUserRepository userRepository;
    private final LossRecordRepository lossRepository;
    private final BatchLedgerService ledgerService;
    private final AlertService alertService;
    private final ChannelRepository channelRepository;

    @Transactional
    public StocktakeOrder create(String cabinetNo, String operatorUsername) {
        Cabinet cabinet = cabinetRepository.findByCabinetNo(cabinetNo)
                .orElseThrow(() -> ApiException.notFound("柜机不存在"));
        AppUser op = userRepository.findByUsername(operatorUsername).orElse(null);
        StocktakeOrder order = StocktakeOrder.builder()
                .orderNo(genNo("ST"))
                .cabinet(cabinet).operator(op)
                .startedAt(LocalDateTime.now())
                .status(StocktakeStatus.DRAFT)
                .build();
        stocktakeRepository.save(order);

        // 系统库存快照（含批次）
        List<ChannelStock> stocks = stockRepository.findByCabinetId(cabinet.getId());
        for (ChannelStock s : stocks) {
            itemRepository.save(StocktakeItem.builder()
                    .stocktake(order)
                    .channel(s.getChannel())
                    .product(s.getProduct())
                    .batch(s.getBatch())
                    .systemQty(s.getQtyOnHand())
                    .countedQty(null)
                    .diffQty(0)
                    .adjusted(false)
                    .build());
        }
        return order;
    }

    /** 录入实盘数量：channelCode + batchNo + countedQty */
    @Transactional
    public StocktakeOrder count(String stocktakeNo, List<CountLine> counts) {
        StocktakeOrder order = stocktakeRepository.findByOrderNo(stocktakeNo)
                .orElseThrow(() -> ApiException.notFound("盘点单不存在"));
        List<StocktakeItem> items = itemRepository.findByStocktakeId(order.getId());
        int totalDiff = 0;
        for (CountLine c : counts) {
            StocktakeItem item = items.stream()
                    .filter(i -> i.getChannel().getCode().equals(c.channelCode())
                            && i.getBatch().getBatchNo().equals(c.batchNo()))
                    .findFirst()
                    .orElseThrow(() -> ApiException.badRequest(
                            "盘点行不存在: " + c.channelCode() + "/" + c.batchNo()));
            item.setCountedQty(c.countedQty());
            item.setDiffQty(c.countedQty() - item.getSystemQty());
            totalDiff += Math.abs(item.getDiffQty());
            itemRepository.save(item);
        }
        order.setTotalDiff(totalDiff);
        order.setStatus(totalDiff > 0 ? StocktakeStatus.PENDING_ADJUST : StocktakeStatus.COUNTED);
        order.setCompletedAt(LocalDateTime.now());
        if (totalDiff > 0) {
            alertService.raise(order.getCabinet(), AlertType.STOCK_DISCREPANCY, "WARN",
                    "盘点差异 " + totalDiff + " 件，待审批调整: " + stocktakeNo,
                    "STOCKTAKE", order.getId());
        }
        return stocktakeRepository.save(order);
    }

    /** 审批并调整库存差异 */
    @Transactional
    public StocktakeOrder adjust(String stocktakeNo, boolean approve, String remark) {
        StocktakeOrder order = stocktakeRepository.findByOrderNo(stocktakeNo)
                .orElseThrow(() -> ApiException.notFound("盘点单不存在"));
        if (order.getStatus() != StocktakeStatus.PENDING_ADJUST) {
            throw ApiException.conflict("盘点单无待调整差异");
        }
        List<StocktakeItem> items = itemRepository.findByStocktakeId(order.getId());
        for (StocktakeItem item : items) {
            if (item.isAdjusted() || item.getCountedQty() == null || item.getDiffQty() == 0) continue;
            ChannelStock stock = stockRepository
                    .findByChannelIdAndBatchId(item.getChannel().getId(), item.getBatch().getId())
                    .orElseThrow(() -> ApiException.conflict("库存记录缺失"));
            if (approve) {
                int target = item.getCountedQty();
                int delta = target - stock.getQtyOnHand();
                stock.setQtyOnHand(Math.max(0, target));
                stock.setQtyQuarantined(Math.min(stock.getQtyQuarantined(), stock.getQtyOnHand()));
                stock.setQtySaleHalted(Math.min(stock.getQtySaleHalted(), stock.getQtyOnHand()));
                stockRepository.save(stock);
                item.setAdjusted(true);

                BigDecimal cost = item.getBatch().getCostPrice() != null
                        ? item.getBatch().getCostPrice().multiply(BigDecimal.valueOf(Math.abs(delta)))
                        : BigDecimal.ZERO;
                if (delta < 0) {
                    ledgerService.record(item.getBatch(), order.getCabinet(),
                            LedgerType.ADJUST_MINUS, delta, cost,
                            "STOCKTAKE", order.getId(), "盘亏调整");
                    lossRepository.save(LossRecord.builder()
                            .lossNo(genNo("LS"))
                            .cabinet(order.getCabinet())
                            .product(item.getProduct()).batch(item.getBatch())
                            .reason(LossReason.SHRINKAGE)
                            .qty(-delta).costAmount(cost)
                            .liability("OPERATOR")
                            .bizType("STOCKTAKE").bizId(order.getId())
                            .remark(remark).build());
                } else {
                    ledgerService.record(item.getBatch(), order.getCabinet(),
                            LedgerType.ADJUST_PLUS, delta, cost,
                            "STOCKTAKE", order.getId(), "盘盈调整");
                }
            }
        }
        order.setStatus(StocktakeStatus.ADJUSTED);
        order.setRemark(remark);
        return stocktakeRepository.save(order);
    }

    private String genNo(String prefix) {
        return prefix + LocalDate.now().toString().replace("-", "")
                + String.format("%04d", System.nanoTime() % 10000);
    }

    public record CountLine(String channelCode, String batchNo, Integer countedQty) {}
}
