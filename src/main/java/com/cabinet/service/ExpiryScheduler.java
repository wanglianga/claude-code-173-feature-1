package com.cabinet.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 临期/过期定时扫描与逾期任务巡检：
 * - 扫描：批次级停售 + 生成补货员下架任务；
 * - 逾期巡检：超时未处理的任务落标，并对批次限流曝光。
 * cron 可配，测试环境用 "-" 关闭。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiryScheduler {

    private final RemovalService removalService;

    @Value("${cabinet.expiry.near-expiry-days:2}")
    private int nearExpiryDays;

    @Scheduled(cron = "${cabinet.scan-cron}")
    public void scan() {
        try {
            int n = removalService.scanExpiry(nearExpiryDays);
            log.info("临期扫描完成，生成下架任务 {} 张", n);
        } catch (Exception e) {
            log.error("临期扫描失败", e);
        }
    }

    /** 每小时巡检逾期任务（限流曝光） */
    @Scheduled(cron = "${cabinet.overdue-cron:0 0 * * * *}")
    public void overdueCheck() {
        try {
            int n = removalService.markOverdueTasks();
            if (n > 0) log.info("逾期下架任务巡检：新逾期 {} 张，相关批次已限制曝光", n);
        } catch (Exception e) {
            log.error("逾期任务巡检失败", e);
        }
    }
}
