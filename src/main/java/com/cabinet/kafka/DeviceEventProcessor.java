package com.cabinet.kafka;

import com.cabinet.domain.Cabinet;
import com.cabinet.domain.DeviceCommand;
import com.cabinet.domain.enums.AlertType;
import com.cabinet.kafka.dto.DeviceEvent;
import com.cabinet.repo.CabinetRepository;
import com.cabinet.service.AlertService;
import com.cabinet.service.DeviceCommandService;
import com.cabinet.service.SessionSettlementService;
import com.cabinet.service.TemperatureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备事件处理器：Kafka 消费端与 HTTP 模拟入口共用同一套业务逻辑。
 * 心跳/重连时柜机可通过 COMMAND_PULL 拉取离线期间积压的批次停售/复售指令。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceEventProcessor {

    private final CabinetRepository cabinetRepository;
    private final SessionSettlementService sessionService;
    private final TemperatureService temperatureService;
    private final AlertService alertService;
    private final DeviceCommandService deviceCommandService;

    @Transactional
    public List<DeviceCommand> process(DeviceEvent event) {
        LocalDateTime at = event.getEventTime() != null ? event.getEventTime() : LocalDateTime.now();
        switch (event.getType()) {
            case "DOOR_OPEN" -> sessionService.openDoor(
                    event.getCabinetNo(), event.getSessionNo(),
                    event.getUserCode(), event.getUserName(), at);
            case "RECOGNITION" -> sessionService.recordRecognition(
                    event.getSessionNo(), event.getCabinetNo(), event.getChannelCode(),
                    event.getSku(), event.getBatchNo(), event.getSource(), event.getAction(),
                    event.getQty(), event.getConfidence(), event.getWeightGram(),
                    event.getVideoClipUrl(), event.getClipStartSec(), event.getClipEndSec(),
                    at, null);
            case "DOOR_CLOSE" -> sessionService.closeDoor(
                    event.getSessionNo(), event.getWeightDeltaGram(), at);
            case "TEMP" -> cabinetRepository.findByCabinetNo(event.getCabinetNo()).ifPresentOrElse(
                    c -> {
                        if (event.getTempC() != null) temperatureService.record(c, event.getTempC(), at);
                    },
                    () -> log.warn("温度事件柜机不存在: {}", event.getCabinetNo()));
            case "HEARTBEAT", "ONLINE" -> cabinetRepository.findByCabinetNo(event.getCabinetNo())
                    .ifPresent(c -> {
                        c.setOnline(true);
                        c.setLastHeartbeatAt(at);
                        if (event.getTempC() != null) c.setLastTempC(event.getTempC());
                        cabinetRepository.save(c);
                    });
            case "OFFLINE" -> cabinetRepository.findByCabinetNo(event.getCabinetNo())
                    .ifPresent(c -> {
                        c.setOnline(false);
                        cabinetRepository.save(c);
                        log.info("柜机 {} 标记离线，期间停售指令将挂起待补发", event.getCabinetNo());
                    });
            case "COMMAND_PULL" -> {
                // 柜机重连后按已确认序号拉取指令（离线补偿）
                return deviceCommandService.pullPending(event.getCabinetNo(), event.getAckSeq());
            }
            case "COMMAND_ACK" -> {
                deviceCommandService.ack(event.getCabinetNo(), event.getAckSeq());
            }
            case "CHANNEL_JAM" -> cabinetRepository.findByCabinetNo(event.getCabinetNo())
                    .ifPresent(c -> alertService.raise(c, AlertType.CHANNEL_JAM, "WARN",
                            "货道 " + event.getJamChannelCode() + " 卡货", "CHANNEL_JAM", null));
            default -> log.warn("未知设备事件类型: {}", event.getType());
        }
        return List.of();
    }
}
