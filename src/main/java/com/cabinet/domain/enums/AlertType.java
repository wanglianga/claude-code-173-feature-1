package com.cabinet.domain.enums;

public enum AlertType {
    WRONG_CHANNEL,      // 放错货道
    CHANNEL_JAM,        // 货道卡货
    TEMP_ALARM,         // 冷藏温度异常
    NEAR_EXPIRY,        // 临期未下架预警
    EXPIRED,            // 过期
    STOCK_DISCREPANCY,  // 盘点差异
    RECOGNITION_CONFLICT, // 识别存疑
    REMOVAL_OVERDUE,    // 临期下架任务逾期未处理
    BATCH_RISK          // 批次质量/临期售出风险提醒
}
