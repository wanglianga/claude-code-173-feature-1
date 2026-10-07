package com.cabinet.domain;

import com.cabinet.domain.enums.CabinetStatus;
import com.cabinet.domain.enums.TempZone;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** 无人货柜 */
@Entity
@Table(name = "cabinet",
        uniqueConstraints = @UniqueConstraint(columnNames = "cabinetNo"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Cabinet extends BaseEntity {

    /** 柜机编号 */
    private String cabinetNo;

    @ManyToOne(fetch = FetchType.LAZY)
    private Point point;

    private String model;

    /** 柜机温区 */
    @Enumerated(EnumType.STRING)
    private TempZone tempZone;

    /** 允许温度下限 */
    private Double tempMin;
    /** 允许温度上限 */
    private Double tempMax;

    /** 最近一次上报温度 */
    private Double lastTempC;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private CabinetStatus status = CabinetStatus.NORMAL;

    private LocalDateTime lastHeartbeatAt;

    /** 温控异常开始时间（用于计算失控时长） */
    private LocalDateTime tempAlarmSince;

    /**
     * 在线状态（由心跳刷新）。临期停售指令在柜机离线期间只入库不下发，
     * 设备重连/心跳时按 lastCommandSeq 补发，保证离线期间产生的任务最终到达柜端。
     */
    @Builder.Default
    private boolean online = true;

    /** 柜端最近一次确认收到的指令序号 */
    @Builder.Default
    private Long lastCommandSeq = 0L;
}
