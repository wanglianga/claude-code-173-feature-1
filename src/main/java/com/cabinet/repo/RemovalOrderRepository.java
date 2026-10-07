package com.cabinet.repo;

import com.cabinet.domain.RemovalOrder;
import com.cabinet.domain.enums.RemovalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RemovalOrderRepository extends JpaRepository<RemovalOrder, Long> {
    Optional<RemovalOrder> findByOrderNo(String orderNo);

    List<RemovalOrder> findByStatusInOrderByScheduledAtDesc(List<RemovalStatus> statuses);

    List<RemovalOrder> findByCabinetIdAndStatusInOrderByScheduledAtDesc(
            Long cabinetId, List<RemovalStatus> statuses);

    /** 已到时限但尚未执行、且未标记逾期的任务 */
    List<RemovalOrder> findByStatusInAndDueAtBeforeAndOverdueMarkedAtIsNull(
            List<RemovalStatus> statuses, LocalDateTime now);

    /** 逾期任务（含已执行前被标记过的，运营端展示） */
    List<RemovalOrder> findByOverdueMarkedAtIsNotNullAndStatusInOrderByDueAtAsc(
            List<RemovalStatus> statuses);

    /** 幂等：同柜机+同批次是否已有未完成下架任务（临期扫描不重复生成） */
    @Query("select count(o) > 0 from RemovalOrder o, RemovalItem i " +
           "where i.removalOrder = o and o.cabinet.id = :cabinetId and i.batch.id = :batchId " +
           "and o.status in :statuses")
    boolean existsActive(@Param("cabinetId") Long cabinetId,
                         @Param("batchId") Long batchId,
                         @Param("statuses") List<RemovalStatus> statuses);
}
