package com.cabinet.repo;

import com.cabinet.domain.DeviceCommand;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DeviceCommandRepository extends JpaRepository<DeviceCommand, Long> {

    /** 柜端拉取：序号大于 lastAckSeq 的指令（离线期间积压 + 新指令一次拉全） */
    List<DeviceCommand> findByCabinetIdAndSeqGreaterThanOrderBySeqAsc(Long cabinetId, Long seq);

    List<DeviceCommand> findByCabinetIdAndStatusOrderBySeqAsc(Long cabinetId, String status);

    /** 生成序号时对柜机行加锁，避免并发扫描产生重复序号 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cabinet c where c.id = :id")
    Optional<com.cabinet.domain.Cabinet> lockCabinet(@Param("id") Long id);

    long countByCabinetIdAndStatus(Long cabinetId, String status);
}
