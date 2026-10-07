package com.cabinet.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 下发给货柜端的指令（批次级停售/复售）。
 * 柜机离线期间产生的停售任务先落表（PENDING），设备心跳/重连时补发；
 * 柜端按 seq 去重确认（ACK），at-least-once 下发 + 幂等确认。
 */
@Entity
@Table(name = "device_command",
        indexes = {
                @Index(columnList = "cabinet_id,status"),
                @Index(columnList = "cabinet_id,seq", unique = true)
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceCommand extends BaseEntity {

    /** 柜机内单调递增的指令序号 */
    private Long seq;

    @ManyToOne(fetch = FetchType.LAZY)
    private Cabinet cabinet;

    /** BLOCK_BATCH 批次停售 / UNBLOCK_BATCH 批次复售 */
    private String commandType;

    private String batchNo;

    /** 关联业务（REMOVAL_ORDER + id 等） */
    private String bizType;
    private Long bizId;

    @Column(length = 500)
    private String payload;

    /** PENDING 待下发/待确认 / ACKED 柜端已确认 */
    @Builder.Default
    private String status = "PENDING";

    private LocalDateTime sentAt;
    private LocalDateTime ackedAt;

    /** 下发次数（离线补发重试计数） */
    @Builder.Default
    private Integer sendAttempts = 0;
}
