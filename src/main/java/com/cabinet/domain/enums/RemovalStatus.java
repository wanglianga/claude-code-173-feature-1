package com.cabinet.domain.enums;

/**
 * 下架任务状态：
 * PENDING 待领取（系统扫描/手工创建，批次已同步停售）
 * ACCEPTED 补货员已扫码领取、清点中
 * EXECUTED 清点去向已执行完成
 * CANCELLED 取消（误生成等）
 */
public enum RemovalStatus { PENDING, ACCEPTED, EXECUTED, CANCELLED }
