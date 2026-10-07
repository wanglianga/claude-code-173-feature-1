package com.cabinet.domain.enums;

/**
 * 下架商品实物去向（补货员到场清点后登记）：
 * SCRAP 报损报废 / RECYCLE 回收（周转回收，等待后续处理）/
 * RETURN_SUPPLIER 退回供应商 / SALEABLE 复检后仍可销售（解除停售恢复曝光）。
 */
public enum Disposition { SCRAP, RECYCLE, RETURN_SUPPLIER, SALEABLE }
