package com.cabinet.repo;

import com.cabinet.domain.ProductBatch;
import com.cabinet.domain.enums.BatchStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ProductBatchRepository extends JpaRepository<ProductBatch, Long> {
    Optional<ProductBatch> findByBatchNo(String batchNo);

    /** 查在库且到期日 <= threshold 的批次（临期/过期扫描） */
    List<ProductBatch> findByStatusAndExpiryDateLessThanEqual(BatchStatus status, LocalDate threshold);

    /** 临期扫描：在库、未停售、且仍有在库余量、到期日 <= threshold */
    @Query("select distinct b from ProductBatch b join ChannelStock s on s.batch.id = b.id " +
           "where b.status = com.cabinet.domain.enums.BatchStatus.IN_STOCK " +
           "and b.saleBlocked = false and b.expiryDate <= :threshold " +
           "and s.qtyOnHand > 0")
    List<ProductBatch> findScanCandidates(@Param("threshold") LocalDate threshold);

    /** 批次状态变更加行锁（停售/复售/收尾与售卖、清点互斥） */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ProductBatch b where b.id = :id")
    Optional<ProductBatch> lockById(@Param("id") Long id);
}
