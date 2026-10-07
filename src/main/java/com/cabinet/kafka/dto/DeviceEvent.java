package com.cabinet.kafka.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 柜机上报事件。
 * type: DOOR_OPEN / DOOR_CLOSE / RECOGNITION / TEMP / HEARTBEAT / CHANNEL_JAM
 * 设备与服务之间统一走该报文，由服务侧按 cabinetNo + sessionNo 聚合。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceEvent {

    /** 事件类型 */
    private String type;

    /** 柜机编号 */
    private String cabinetNo;

    /** 开门会话号（同一次开门购物相同） */
    private String sessionNo;

    private String userCode;
    private String userName;

    /** 识别相关 */
    private String channelCode;
    private String sku;
    private String batchNo;
    private String source;          // WEIGHT / VISION / RFID
    private String action;          // PICK / PUT
    private Integer qty;
    private Double confidence;
    private Integer weightGram;
    private String videoClipUrl;
    private Integer clipStartSec;
    private Integer clipEndSec;

    /** 关门时柜内净称重变化(g) */
    private Integer weightDeltaGram;

    /** 温度上报 */
    private Double tempC;

    /** 卡货货道 */
    private String jamChannelCode;

    /** 柜端已确认的指令序号（COMMAND_PULL/COMMAND_ACK 使用） */
    private Long ackSeq;

    private LocalDateTime eventTime;
}
