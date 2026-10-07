package com.cabinet.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * 货道-批次库存。补货上架后在对应货道形成批次库存；
 * 售卖按 FEFO（先到期先出）扣减。
 *
 * 不可售数量分两类：
 * - qtyQuarantined：温控异常等食品安全隔离量；
 * - qtySaleHalted：批次临期/过期停止售卖量（批次级，仅停该批，不停货道）。
 * 可售 = qtyOnHand - qtyQuarantined - qtySaleHalted。
 */
@Entity
@Table(name = "channel_stock",
        uniqueConstraints = @UniqueConstraint(columnNames = {"channel_id", "batch_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChannelStock extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id")
    private Channel channel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private ProductBatch batch;

    @ManyToOne(fetch = FetchType.LAZY)
    private Product product;

    @Builder.Default
    private Integer qtyOnHand = 0;

    /** 因温控异常被食品安全隔离的数量（不可售卖） */
    @Builder.Default
    private Integer qtyQuarantined = 0;

    /** 因批次临期/过期/任务逾期被停止售卖的数量（不可售卖、不再曝光） */
    @Builder.Default
    private Integer qtySaleHalted = 0;

    /** 可售数量 */
    public int sellableQty() {
        return Math.max(0, qtyOnHand - qtyQuarantined - qtySaleHalted);
    }
}
