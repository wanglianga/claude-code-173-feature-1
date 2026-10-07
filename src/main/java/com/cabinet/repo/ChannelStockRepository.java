package com.cabinet.repo;

import com.cabinet.domain.ChannelStock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChannelStockRepository extends JpaRepository<ChannelStock, Long> {

    List<ChannelStock> findByChannelId(Long channelId);

    List<ChannelStock> findByBatchId(Long batchId);

    Optional<ChannelStock> findByChannelIdAndBatchId(Long channelId, Long batchId);

    /** FEFO：货道内可售（非隔离、批次未停售）且有余量的批次，按到期日升序 */
    @Query("select s from ChannelStock s where s.channel.id = :channelId " +
           "and s.qtyOnHand - s.qtyQuarantined > 0 " +
           "and s.batch.saleBlocked = false " +
           "order by s.batch.expiryDate asc, s.batch.id asc")
    List<ChannelStock> findSellableByChannelFefo(@Param("channelId") Long channelId);

    /** 人工复核强制结算用：含已停售批次（用户在停售瞬间已拿走，需补扣款并在下架对账中核销） */
    @Query("select s from ChannelStock s where s.channel.id = :channelId " +
           "and s.qtyOnHand > 0 order by s.batch.expiryDate asc, s.batch.id asc")
    List<ChannelStock> findAllByChannelFefo(@Param("channelId") Long channelId);

    /**
     * 关门结算可行性校验用：货道当前实际可扣数量（读数据库已提交值，不受本事务陈旧实体影响）。
     * 正常批次=在库-隔离；已停售批次按在库兜底（开门先于停售的并发售出仍需扣款并在下架对账核销）。
     */
    @Query("select coalesce(sum(case when s.batch.saleBlocked = true " +
           "then s.qtyOnHand else s.qtyOnHand - s.qtyQuarantined end),0) " +
           "from ChannelStock s where s.channel.id = :channelId")
    Integer sumAvailableForSettle(@Param("channelId") Long channelId);

    /** 柜机内全部有余额库存 */
    @Query("select s from ChannelStock s join s.channel c where c.cabinet.id = :cabinetId " +
           "and (s.qtyOnHand > 0 or s.qtyQuarantined > 0) order by c.code, s.batch.expiryDate")
    List<ChannelStock> findByCabinetId(@Param("cabinetId") Long cabinetId);

    /** 全链路余量：某批次所有货道在库（含隔离）数量 */
    @Query("select coalesce(sum(s.qtyOnHand),0) from ChannelStock s where s.batch.id = :batchId")
    Integer sumOnHandByBatch(@Param("batchId") Long batchId);

    /** 行锁：关门扣款与临期停售/清点核减互斥的关键 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ChannelStock s where s.id = :id")
    Optional<ChannelStock> lockById(@Param("id") Long id);
}
