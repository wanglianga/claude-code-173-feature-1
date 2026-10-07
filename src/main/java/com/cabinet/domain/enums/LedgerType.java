package com.cabinet.domain.enums;

/** 批次台账流水类型：补货入/售卖出/下架出/报损出/盘盈盘亏/退款回库/下架复售回库/结算 */
public enum LedgerType {
    RESTOCK_IN,
    SALE_OUT,
    REMOVAL_OUT,
    LOSS_OUT,
    ADJUST_PLUS,
    ADJUST_MINUS,
    REFUND_RETURN,
    /** 临期下架清点为“仍可销售”，商品留在货道重新可售（数量不变，仅记录状态回退） */
    RESALE_UNBLOCK,
    SETTLE
}
