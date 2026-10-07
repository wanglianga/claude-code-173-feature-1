package com.cabinet.repo;

import com.cabinet.domain.RemovalItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RemovalItemRepository extends JpaRepository<RemovalItem, Long> {
    List<RemovalItem> findByRemovalOrderId(Long removalOrderId);

    /** 该批次是否还存在待执行的下架任务（决定清点复售后能否解除批次停售） */
    @Query("select case when count(ri) > 0 then true else false end from RemovalItem ri " +
           "where ri.batch.id = :batchId " +
           "and ri.removalOrder.status = com.cabinet.domain.enums.RemovalStatus.PENDING")
    boolean existsOpenTaskByBatch(@Param("batchId") Long batchId);
}
