package com.cabinet.dto;

import com.cabinet.domain.*;

import java.util.List;

/**
 * 批次还原视图：回答“某一批商品为什么被下架、最终去了哪里”。
 * 一次聚合批次主数据、台账流水、报损、购买订单、下架任务(含四去向明细)、
 * 结算明细与相关告警，所有下架数量、实物去向、结算变化均可还原。
 */
public record BatchRecordView(
        ProductBatch batch,
        Product product,
        Supplier supplier,
        List<BatchLedger> ledgers,
        List<LossRecord> losses,
        List<OrderItem> soldOrderItems,
        List<RemovalOrder> removalOrders,
        List<RemovalItem> removalItems,
        List<SettlementItem> settlementItems,
        List<Alert> alerts) {
}
