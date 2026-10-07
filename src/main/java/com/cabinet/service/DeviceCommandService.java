package com.cabinet.service;

import com.cabinet.domain.Cabinet;
import com.cabinet.domain.DeviceCommand;
import com.cabinet.repo.CabinetRepository;
import com.cabinet.repo.DeviceCommandRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 货柜端指令服务：批次级停售(BLOCK_BATCH)/复售(UNBLOCK_BATCH)。
 * 指令先落表（柜机内单调递增 seq），柜机在线时直接置为已发送；
 * 柜机离线期间产生的指令保持 PENDING，设备重连/心跳后通过拉取接口补发，
 * 柜端按 seq 去重确认 —— 保证“离线期间产生的临期停售”最终一定到达柜端。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceCommandService {

    public static final String BLOCK_BATCH = "BLOCK_BATCH";
    public static final String UNBLOCK_BATCH = "UNBLOCK_BATCH";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACKED = "ACKED";

    private final DeviceCommandRepository commandRepository;
    private final CabinetRepository cabinetRepository;
    private final BizEventPublisher bizEventPublisher;

    /** 下发批次停售指令（离线安全） */
    @Transactional
    public DeviceCommand issueBlock(Cabinet cabinet, String batchNo,
                                   String bizType, Long bizId, String payload) {
        return issue(cabinet, BLOCK_BATCH, batchNo, bizType, bizId, payload);
    }

    /** 下发批次复售指令 */
    @Transactional
    public DeviceCommand issueUnblock(Cabinet cabinet, String batchNo,
                                     String bizType, Long bizId, String payload) {
        return issue(cabinet, UNBLOCK_BATCH, batchNo, bizType, bizId, payload);
    }

    private DeviceCommand issue(Cabinet cabinet, String type, String batchNo,
                                String bizType, Long bizId, String payload) {
        // 锁柜机行分配 seq，避免并发扫描/手工下架序号冲突
        Cabinet locked = commandRepository.lockCabinet(cabinet.getId()).orElseThrow();
        long seq = (locked.getLastCommandSeq() == null ? 0L : locked.getLastCommandSeq()) + 1;
        locked.setLastCommandSeq(seq);
        cabinetRepository.save(locked);

        DeviceCommand cmd = DeviceCommand.builder()
                .seq(seq).cabinet(locked).commandType(type).batchNo(batchNo)
                .bizType(bizType).bizId(bizId).payload(payload)
                .status(STATUS_PENDING).sendAttempts(0)
                .build();
        if (locked.isOnline()) {
            cmd.setSendAttempts(1);
            cmd.setSentAt(LocalDateTime.now());
        } else {
            log.info("柜机 {} 离线，{} 指令 seq={} 批次 {} 挂起待补发",
                    locked.getCabinetNo(), type, seq, batchNo);
        }
        return commandRepository.save(cmd);
    }

    /**
     * 柜端重连/心跳拉取：返回 seq 大于其已确认序号的全部指令（含离线积压）。
     * 拉取即视为送达确认（柜端按 seq 幂等执行），同时柜机置为在线。
     */
    @Transactional
    public List<DeviceCommand> pullPending(String cabinetNo, Long ackSeq) {
        Cabinet cabinet = cabinetRepository.findByCabinetNo(cabinetNo)
                .orElseThrow(() -> new IllegalArgumentException("柜机不存在: " + cabinetNo));
        long after = ackSeq != null ? ackSeq : 0L;
        // 设备主动拉取说明已在线
        cabinet.setOnline(true);
        cabinet.setLastHeartbeatAt(LocalDateTime.now());
        cabinetRepository.save(cabinet);

        List<DeviceCommand> pending =
                commandRepository.findByCabinetIdAndSeqGreaterThanOrderBySeqAsc(cabinet.getId(), after);
        LocalDateTime now = LocalDateTime.now();
        for (DeviceCommand cmd : pending) {
            cmd.setStatus(STATUS_ACKED);
            cmd.setAckedAt(now);
            if (cmd.getSentAt() == null) cmd.setSentAt(now);
            cmd.setSendAttempts(cmd.getSendAttempts() + 1);
        }
        return pending;
    }

    /** 柜端逐条确认 */
    @Transactional
    public DeviceCommand ack(String cabinetNo, Long seq) {
        Cabinet cabinet = cabinetRepository.findByCabinetNo(cabinetNo)
                .orElseThrow(() -> new IllegalArgumentException("柜机不存在"));
        cabinet.setOnline(true);
        cabinetRepository.save(cabinet);
        DeviceCommand cmd = commandRepository
                .findByCabinetIdAndStatusOrderBySeqAsc(cabinet.getId(), STATUS_PENDING).stream()
                .filter(c -> c.getSeq().equals(seq)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("待确认指令不存在: seq=" + seq));
        cmd.setStatus(STATUS_ACKED);
        cmd.setAckedAt(LocalDateTime.now());
        return commandRepository.save(cmd);
    }
}
