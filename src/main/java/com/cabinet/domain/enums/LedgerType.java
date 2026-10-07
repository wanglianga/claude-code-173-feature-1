package com.cabinet.domain.enums;

/** 批次台账流水类型：补货入/售卖出/停售/解封/下架出/报损出/盘盈盘亏/退款回库/结算 */
public enum LedgerType {
    RESTOCK_IN,
    SALE_OUT,
    SALE_BLOCK,       // 临期/逾期停止售卖（在库不变，仅记录停售数量与原因）
    SALE_UNBLOCK,     // 复检可售/运营解封，恢复曝光
    REMOVAL_OUT,
    LOSS_OUT,
    ADJUST_PLUS,
    ADJUST_MINUS,
    REFUND_RETURN,
    SETTLE
}
