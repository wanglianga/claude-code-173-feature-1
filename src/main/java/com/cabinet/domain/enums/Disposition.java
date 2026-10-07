package com.cabinet.domain.enums;

/**
 * 下架商品实物去向（补货员到场扫码清点后逐项登记）：
 * SCRAP            报损报废（临期/过期/损坏，登记报损与责任归属）
 * RECYCLE          回收（返厂回收再利用等，不产生报损，不回可售库存）
 * RETURN_SUPPLIER  退回供应商
 * RESALE           清点后确认仍可销售，解除停售回到货道继续售卖
 */
public enum Disposition {
    SCRAP("报损"),
    RECYCLE("回收"),
    RETURN_SUPPLIER("退供应商"),
    RESALE("仍可销售");

    private final String label;

    Disposition(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
