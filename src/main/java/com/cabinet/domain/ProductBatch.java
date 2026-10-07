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
        indexes = {
                @Index(columnList = "expiryDate"),
                @Index(columnList = "saleBlocked")
        })
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
     * 批次级停售开关：临期进入处置范围后置 true。
     * 粒度是“批次”而不是货道——同一货道上其他批次仍可正常售卖。
     * 货柜端据此停止该批次的曝光与出货，服务端结算据此拒绝再扣该批次。
     */
    @Builder.Default
    private boolean saleBlocked = false;

    /** 停售时间（用于区分开门/扣款与停售的先后，并发对账依据） */
    private LocalDateTime blockedAt;

    /** 停售原因（NEAR_EXPIRY/EXPIRED/TEMP_ABUSE/MANUAL...） */
    private String blockReason;

    /**
     * 限流曝光：下架任务逾期未处理后置 true。
     * saleBlocked 控制“能否买”，exposureBlocked 控制“能否再次被推荐/曝光/补货上架”。
     */
    @Builder.Default
    private boolean exposureBlocked = false;

    private LocalDateTime exposureBlockedAt;
}
