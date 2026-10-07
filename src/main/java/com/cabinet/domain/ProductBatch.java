package com.cabinet.domain;

import com.cabinet.domain.enums.BatchStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 商品批次：保质期与供应商结算、损耗追溯的最小单元 */
@Entity
@Table(name = "product_batch",
        uniqueConstraints = @UniqueConstraint(columnNames = "batchNo"),
        indexes = @Index(columnList = "expiryDate"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductBatch extends BaseEntity {

    /** 批次号 */
    private String batchNo;

    @ManyToOne(fetch = FetchType.LAZY)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    private Supplier supplier;

    private LocalDate productionDate;
    /** 保质期到期日（到期日当天 00:00 视为过期） */
    private LocalDate expiryDate;

    /** 本批次收货总数（入站数量） */
    private Integer receivedQty;

    /** 批次成本单价（结算依据，快照） */
    @Column(precision = 12, scale = 2)
    private BigDecimal costPrice;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private BatchStatus status = BatchStatus.IN_STOCK;

    /**
     * 批次是否被停止售卖（临期/过期扫描后置为 true）。
     * 停售是批次级的：同一货道上的其他批次不受影响，FEFO 扣减会跳过该批次。
     * 补货员清点出 SALEABLE 去向或运营解封时复位。
     */
    @Builder.Default
    private boolean saleHalted = false;

    /** 停售原因：NEAR_EXPIRY / EXPIRED / OVERDUE_TASK / MANUAL */
    private String haltReason;

    private LocalDateTime saleHaltedAt;

    /**
     * 是否限制曝光：临期任务逾期未处理时置 true，
     * 柜机端/运营选品均不得再把该批次展示为可购；解封需运营人工处理。
     */
    @Builder.Default
    private boolean exposureBlocked = false;

    private LocalDateTime exposureBlockedAt;
}
