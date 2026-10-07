package com.cabinet.domain;

import com.cabinet.domain.enums.Disposition;
import jakarta.persistence.*;
import lombok.*;

/**
 * 下架明细：精确到货道与批次。
 * plannedQty 为生成任务时系统在该货道该批次的数量快照；
 * 补货员扫码清点后逐货道逐批次登记实际数量与去向
 * （报损/回收/退供应商/仍可销售），同一条上的商品去向一致。
 */
@Entity
@Table(name = "removal_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RemovalItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    private RemovalOrder removalOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id")
    private Channel channel;

    @ManyToOne(fetch = FetchType.LAZY)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    private ProductBatch batch;

    /** 计划下架数量（任务生成时的系统数量快照） */
    private Integer plannedQty;

    /** 补货员实际清点数量（扫码清点） */
    private Integer countedQty;

    /** 实际执行处置数量 */
    private Integer executedQty;

    @Enumerated(EnumType.STRING)
    private Disposition disposition;

    /** 清点照片 URL */
    private String photoUrl;
}
