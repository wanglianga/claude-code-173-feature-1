package com.cabinet.domain;

import com.cabinet.domain.enums.RemovalReason;
import com.cabinet.domain.enums.RemovalStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 下架单（同时是派发给补货员的临期处理任务）：
 * 临期/过期/温控异常/卡货损坏/放错货道纠正/召回。
 * 批次进入临期范围时：先批次级停售并生成此任务，补货员到场扫码清点后执行。
 * 超过 dueAt 未执行进入逾期，运营端可见，并对批次做限流曝光。
 */
@Entity
@Table(name = "removal_order",
        indexes = {
                @Index(columnList = "status"),
                @Index(columnList = "assignee_id,status"),
                @Index(columnList = "dueAt")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RemovalOrder extends BaseEntity {

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

    /** 任务截止时间（逾期判定依据，临期默认当日营业结束） */
    private LocalDateTime dueAt;

    private LocalDateTime executedAt;

    /** 派发的补货员（为空表示待认领/到场扫码即绑定） */
    @ManyToOne(fetch = FetchType.LAZY)
    private AppUser assignee;

    /** 执行的补货员/运营（扫码清点人） */
    @ManyToOne(fetch = FetchType.LAZY)
    private AppUser executor;

    /** 补货员到场扫码时间 */
    private LocalDateTime scannedAt;

    /** 任务逾期标记（定时巡检/查询时按 dueAt 计算并落标，触发限流曝光） */
    @Builder.Default
    private boolean overdue = false;

    /** 清点说明/对账备注（含与系统数量差异说明） */
    @Column(length = 1000)
    private String remark;

    /** 计划下架总数（生成任务时系统快照） */
    private Integer plannedTotalQty;

    /** 实际清点处置总数（四去向合计） */
    private Integer resolvedTotalQty;

    /** 清点期间已被用户买走的数量（账实对账自动核销） */
    @Builder.Default
    private Integer soldWhilePendingQty = 0;

    /** 清点数量与系统数量的盘亏差异（无法解释的短少，记报损） */
    @Builder.Default
    private Integer shortageQty = 0;

    /**
     * 是否整批停售任务：临期/过期扫描为 true（任务覆盖该柜机该批次全部在库，
     * 清点按实物总量对账）；手工部分下架为 false（仅处置指定数量）。
     */
    @Builder.Default
    private boolean fullBlock = false;
}
