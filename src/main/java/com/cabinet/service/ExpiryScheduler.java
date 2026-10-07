package com.cabinet.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时任务：
 * - 每日扫描临期/过期批次：批次级停售并生成补货员下架任务；
 * - 定时检查下架任务逾期：标记逾期并限制批次曝光。
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

    /** 每小时第 20 分钟检查临期任务逾期 */
    @Scheduled(cron = "${cabinet.overdue-cron:0 20 * * * *}")
    public void checkOverdue() {
        try {
            int n = removalService.markOverdue();
            if (n > 0) {
                log.warn("下架任务逾期 {} 张，已限制对应批次曝光", n);
            }
        } catch (Exception e) {
            log.error("逾期任务扫描失败", e);
        }
    }
}
