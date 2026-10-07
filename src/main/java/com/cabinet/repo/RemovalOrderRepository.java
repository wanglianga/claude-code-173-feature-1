package com.cabinet.repo;

import com.cabinet.domain.RemovalOrder;
import com.cabinet.domain.enums.RemovalStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RemovalOrderRepository extends JpaRepository<RemovalOrder, Long> {
    Optional<RemovalOrder> findByOrderNo(String orderNo);

    List<RemovalOrder> findByStatusOrderByIdDesc(RemovalStatus status);

    /** 运营逾期视图：待执行且已过截止时间 */
    List<RemovalOrder> findByStatusAndDueAtBefore(RemovalStatus status, LocalDateTime now);

    List<RemovalOrder> findByStatusAndOverdueTrue(RemovalStatus status);

    /** 补货员任务清单 */
    List<RemovalOrder> findByAssigneeIdAndStatusOrderByIdDesc(Long assigneeId, RemovalStatus status);
}
