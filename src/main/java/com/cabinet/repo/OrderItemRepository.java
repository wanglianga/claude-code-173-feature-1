package com.cabinet.repo;

import com.cabinet.domain.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    List<OrderItem> findByOrderId(Long orderId);
    List<OrderItem> findByBatchId(Long batchId);

    /**
     * 停售快照之后，某批次在指定柜机货道被用户买走的数量（含已退款行：
     * 退款收回的商品会回补库存，在对账公式中与在库增加自动抵消）。
     * 仅统计已实际扣款的订单（CHARGED/部分退/全退），挂起复核未扣款的不算。
     */
    @Query("select coalesce(sum(oi.qty),0) from OrderItem oi " +
           "where oi.batch.id = :batchId and oi.channelCode = :channelCode " +
           "and oi.order.cabinet.id = :cabinetId " +
           "and oi.order.chargedAt >= :since " +
           "and oi.order.status in (com.cabinet.domain.enums.OrderStatus.CHARGED," +
           "com.cabinet.domain.enums.OrderStatus.PARTIALLY_REFUNDED," +
           "com.cabinet.domain.enums.OrderStatus.REFUNDED)")
    Integer sumSoldByBatchChannelSince(@Param("batchId") Long batchId,
                                       @Param("channelCode") String channelCode,
                                       @Param("cabinetId") Long cabinetId,
                                       @Param("since") LocalDateTime since);

    /** 客服按批次追溯购买订单明细（按扣款时间倒序） */
    @Query("select oi from OrderItem oi where oi.batch.id = :batchId " +
           "and oi.order.chargedAt is not null order by oi.order.chargedAt desc")
    List<OrderItem> findChargedByBatch(@Param("batchId") Long batchId);
}
