package com.cabinet.domain;

import com.cabinet.domain.enums.Disposition;
import jakarta.persistence.*;
import lombok.*;

/**
 * 下架明细：精确到货道与批次。
 * 记录系统停售数量（生成任务时快照）、补货员实点数量，
 * 以及实物去向拆分（报损/回收/退供/仍可销售）。
 * 一个批次行可按实物情况拆成多种去向，数量之和必须等于清点数量。
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

    /** 任务生成时系统账应下架数量（停售快照） */
    private Integer plannedQty;

    /** 补货员现场实点数量 */
    private Integer countedQty;

    /** 报损数量 */
    @Builder.Default
    private Integer scrapQty = 0;
    /** 回收数量 */
    @Builder.Default
    private Integer recycleQty = 0;
    /** 退回供应商数量 */
    @Builder.Default
    private Integer returnSupplierQty = 0;
    /** 复检后仍可销售数量（解除该部分停售、恢复曝光） */
    @Builder.Default
    private Integer saleableQty = 0;

    /** 实际执行（出库）数量 = 报损+回收+退供；仍可销售部分不出库 */
    private Integer executedQty;

    /** 兼容旧接口的单一去向标记（取该行主要去向） */
    @Enumerated(EnumType.STRING)
    private Disposition disposition;

    private String remark;

    /** 去向数量合计 */
    public int dispositionTotal() {
        return nz(scrapQty) + nz(recycleQty) + nz(returnSupplierQty) + nz(saleableQty);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
