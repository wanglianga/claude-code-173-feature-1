package com.cabinet.domain.enums;

/** 报损原因 */
public enum LossReason {
    SCRAP_EXPIRED,      // 过期报废
    SCRAP_NEAR_EXPIRY,  // 临期报废
    SCRAP_DAMAGED,      // 破损报废
    TEMP_ABUSE,         // 温控异常报损
    SHRINKAGE,          // 盘点盘亏
    JAM_DAMAGED,        // 卡货损坏
    REFUND_NO_RETURN,   // 退款但商品未收回
    REMOVAL_SHORTAGE    // 下架清点实物少于系统账（差异待查）
}
