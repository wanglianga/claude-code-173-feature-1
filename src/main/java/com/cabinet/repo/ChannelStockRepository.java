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

    /**
     * FEFO：货道内当前可售（排除温控隔离、批次停售、批次曝光限制）且有余量的批次，按到期日升序。
     * 临期停售是批次级的：同货道其他批次仍正常售卖。
     */
    @Query("select s from ChannelStock s where s.channel.id = :channelId " +
           "and s.qtyOnHand - s.qtyQuarantined - s.qtySaleHalted > 0 " +
           "and s.batch.saleHalted = false and s.batch.exposureBlocked = false " +
           "order by s.batch.expiryDate asc, s.batch.id asc")
    List<ChannelStock> findSellableByChannelFefo(@Param("channelId") Long channelId);

    /**
     * 货道内全部批次行（含停售/隔离行），按 FEFO 排序。
     * 关门结算时用于处理“开门时该批次尚未停售”的在途会话。
     */
    @Query("select s from ChannelStock s where s.channel.id = :channelId " +
           "and s.qtyOnHand > 0 order by s.batch.expiryDate asc, s.batch.id asc")
    List<ChannelStock> findAllByChannelFefo(@Param("channelId") Long channelId);

    /** 柜机内全部有余额库存 */
    @Query("select s from ChannelStock s join s.channel c where c.cabinet.id = :cabinetId " +
           "and (s.qtyOnHand > 0 or s.qtyQuarantined > 0 or s.qtySaleHalted > 0) " +
           "order by c.code, s.batch.expiryDate")
    List<ChannelStock> findByCabinetId(@Param("cabinetId") Long cabinetId);

    /** 全链路余量：某批次所有货道在库（含隔离/停售）数量 */
    @Query("select coalesce(sum(s.qtyOnHand),0) from ChannelStock s where s.batch.id = :batchId")
    Integer sumOnHandByBatch(@Param("batchId") Long batchId);

    /** 某批次全柜停售量合计 */
    @Query("select coalesce(sum(s.qtySaleHalted),0) from ChannelStock s where s.batch.id = :batchId")
    Integer sumSaleHaltedByBatch(@Param("batchId") Long batchId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ChannelStock s where s.id = :id")
    Optional<ChannelStock> lockById(@Param("id") Long id);

    /** 行级悲观锁：售卖扣款与下架清点并发时串行化同一货道批次行 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ChannelStock s where s.channel.id = :channelId and s.batch.id = :batchId")
    Optional<ChannelStock> lockByChannelIdAndBatchId(@Param("channelId") Long channelId,
                                                     @Param("batchId") Long batchId);
}
