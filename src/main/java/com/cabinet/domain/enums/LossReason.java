package com.cabinet.domain.enums;

/** 报损原因 */
public enum LossReason {
    SCRAP_EXPIRED,      // 过期报废
    SCRAP_NEAR_EXPIRY,  // 临期报废
    SCRAP_DAMAGED,      // 破损报废
    TEMP_ABUSE,         // 温控异常报损
    SHRINKAGE,          // 盘点/清点盘亏
    JAM_DAMAGED,        // 卡货损坏
    REFUND_NO_RETURN,   // 退款但商品未收回
    SUPPLIER_QUALITY,   // 供应商质量责任（变质/批次问题）导致的退款报损
    RECYCLE             // 回收出库（不产生责任报损，仅统计口径）
}
