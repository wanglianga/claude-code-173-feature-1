package com.cabinet.service;

import com.cabinet.domain.Cabinet;
import com.cabinet.domain.ChannelStock;
import com.cabinet.domain.TempLog;
import com.cabinet.domain.enums.AlertType;
import com.cabinet.domain.enums.CabinetStatus;
import com.cabinet.repo.CabinetRepository;
import com.cabinet.repo.ChannelStockRepository;
import com.cabinet.repo.TempLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 温控服务：冷藏/冷冻柜持续上报温度。
 * 越限 -> 记录温度告警并锁定柜机（禁止开门售卖）；
 * 持续越限超过安全时长 -> 隔离柜内全部在库批次（食品安全，等待下架/报损判定）；
 * 恢复正常 -> 解锁并解除隔离。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemperatureService {

    /** 连续报警超过该分钟数则隔离柜内库存 */
    private static final long QUARANTINE_AFTER_MIN = 30;

    private final TempLogRepository tempLogRepository;
    private final CabinetRepository cabinetRepository;
    private final ChannelStockRepository stockRepository;
    private final AlertService alertService;
    private final BizEventPublisher bizEventPublisher;

    @Transactional
    public TempLog record(Cabinet cabinet, double tempC, LocalDateTime at) {
        double min = cabinet.getTempMin() != null ? cabinet.getTempMin()
                : cabinet.getTempZone().getDefaultMin();
        double max = cabinet.getTempMax() != null ? cabinet.getTempMax()
                : cabinet.getTempZone().getDefaultMax();
        boolean alarm = tempC < min || tempC > max;
        cabinet.setLastTempC(tempC);
        cabinet.setLastHeartbeatAt(at);

        TempLog logRec = TempLog.builder().cabinet(cabinet).tempC(tempC).alarm(alarm).recordedAt(at).build();
        tempLogRepository.save(logRec);

        if (alarm) {
            if (cabinet.getStatus() == CabinetStatus.NORMAL) {
                cabinet.setStatus(CabinetStatus.ALARM_LOCKED);
                cabinet.setTempAlarmSince(at);
                alertService.raise(cabinet, AlertType.TEMP_ALARM, "CRITICAL",
                        String.format("柜机温度越限: %.1f℃ (允许 %.1f~%.1f)，已锁定柜机", tempC, min, max),
                        "CABINET", cabinet.getId());
                bizEventPublisher.publish("TEMP_LOCK", cabinet.getCabinetNo(),
                        cabinet.getCabinetNo(), null,
                        "温度异常锁定: " + tempC + "℃", null);
            } else if (cabinet.getTempAlarmSince() != null
                    && cabinet.getTempAlarmSince().plusMinutes(QUARANTINE_AFTER_MIN).isBefore(at)) {
                quarantineAll(cabinet);
            }
        } else if (cabinet.getStatus() == CabinetStatus.ALARM_LOCKED) {
            // 温度恢复 -> 解锁并解除隔离（隔离商品须人工复检后决定，此处仅解除锁定，隔离不自动恢复）
            cabinet.setStatus(CabinetStatus.NORMAL);
            cabinet.setTempAlarmSince(null);
            alertService.raise(cabinet, AlertType.TEMP_ALARM, "INFO",
                    String.format("柜机温度恢复正常: %.1f℃，已解锁；隔离商品需人工复检", tempC),
                    "CABINET", cabinet.getId());
        }
        // 显式保存，兼容入参为游离态的调用方式
        cabinetRepository.save(cabinet);
        return logRec;
    }

    /** 隔离柜机内全部可售库存 */
    @Transactional
    public int quarantineAll(Cabinet cabinet) {
        List<ChannelStock> stocks = stockRepository.findByCabinetId(cabinet.getId());
        int n = 0;
        for (ChannelStock s : stocks) {
            int sellable = s.sellableQty();
            if (sellable > 0) {
                // 仅隔离当前仍可售部分，已临期停售量不重复隔离
                s.setQtyQuarantined(s.getQtyQuarantined() + sellable);
                stockRepository.save(s);
                n += sellable;
            }
        }
        if (n > 0) {
            alertService.raise(cabinet, AlertType.TEMP_ALARM, "CRITICAL",
                    "温控异常持续超过 " + QUARANTINE_AFTER_MIN + " 分钟，已隔离 " + n + " 件商品等待食品安全处置",
                    "CABINET", cabinet.getId());
        }
        return n;
    }
}
