package com.cabinet.domain;

import com.cabinet.domain.enums.RemovalReason;
import com.cabinet.domain.enums.RemovalStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 下架任务（补货员处理单）：临期/过期/温控异常/卡货损坏/放错货道纠正等。
 *
 * 临期类任务在生成的同一事务内即按批次停止售卖（货柜离线也不影响服务端库存账），
 * 补货员扫码领取任务、到场清点后，按实物去向（报损/回收/退供/仍可销售）执行。
 * 任务有处理时限，逾期未处理会被运营端标记并限制该批次曝光。
 */
@Entity
@Table(name = "removal_order",
        indexes = {
                @Index(columnList = "status"),
                @Index(columnList = "due_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RemovalOrder extends BaseEntity {

    /** 任务单号，同时作为补货员扫码识别码（如任务二维码内容 RM202610060001） */
    private String orderNo;

    @ManyToOne(fetch = FetchType.LAZY)
    private Cabinet cabinet;

    /** 触发来源：SYSTEM(临期/温控定时扫描) / OPERATOR */
    private String triggeredBy;

    @Enumerated(EnumType.STRING)
    private RemovalReason reason;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private RemovalStatus status = RemovalStatus.PENDING;

    private LocalDateTime scheduledAt;

    /** 任务处理时限（超过即逾期，默认生成后 24h） */
    private LocalDateTime dueAt;

    /** 扫码领取任务的补货员 */
    @ManyToOne(fetch = FetchType.LAZY)
    private AppUser acceptedBy;

    private LocalDateTime acceptedAt;

    private LocalDateTime executedAt;

    /** 执行的补货员/运营（与领取人通常一致） */
    @ManyToOne(fetch = FetchType.LAZY)
    private AppUser executor;

    /** 逾期标记时间（非空表示已被逾期扫描标记并限制批次曝光） */
    private LocalDateTime overdueMarkedAt;

    /** 清点总实物数（执行时汇总，便于与系统账核对） */
    @Builder.Default
    private Integer countedTotal = 0;

    private String remark;
}
