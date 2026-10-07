package com.cabinet.repo;

import com.cabinet.domain.RemovalItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RemovalItemRepository extends JpaRepository<RemovalItem, Long> {
    List<RemovalItem> findByRemovalOrderId(Long removalOrderId);
    List<RemovalItem> findByBatchId(Long batchId);
}
