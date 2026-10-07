package com.cabinet.web;

import com.cabinet.domain.BatchLedger;
import com.cabinet.domain.LossRecord;
import com.cabinet.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "运营分析与批次追溯",
     description = "按点位销量/损耗/临期率；按批次查购买订单/风险订单；批次处置档案与全链路台账")
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @Operation(summary = "按点位统计销量、销售额、损耗、临期率")
    @GetMapping("/points")
    @PreAuthorize("hasAnyRole('OPERATOR','FINANCE','ADMIN')")
    public List<AnalyticsService.PointStat> pointStats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analyticsService.pointStats(from, to);
    }

    @Operation(summary = "按批次查看损耗记录（原因、数量、成本、责任归属）")
    @GetMapping("/batches/{batchNo}/losses")
    @PreAuthorize("hasAnyRole('OPERATOR','FINANCE','ADMIN','CUSTOMER_SERVICE')")
    public List<LossRecord> batchLosses(@PathVariable String batchNo) {
        return analyticsService.batchLosses(batchNo);
    }

    @Operation(summary = "按批次查询购买订单（客服召回/退款，含临期风险标记）")
    @GetMapping("/batches/{batchNo}/orders")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE','OPERATOR','FINANCE','ADMIN')")
    public List<AnalyticsService.BatchSaleView> batchOrders(@PathVariable String batchNo) {
        return analyticsService.batchSales(batchNo);
    }

    @Operation(summary = "风险订单清单：已停售/临期过期批次仍成交的订单，用于主动风险提醒")
    @GetMapping("/risk-sales")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE','OPERATOR','ADMIN')")
    public List<AnalyticsService.BatchSaleView> riskSales() {
        return analyticsService.riskSales();
    }

    @Operation(summary = "批次处置档案：批次状态、台账、报损、购买订单（为何下架、最终去向）")
    @GetMapping("/batches/{batchNo}/record")
    @PreAuthorize("isAuthenticated()")
    public AnalyticsService.BatchRecordView batchRecord(@PathVariable String batchNo) {
        return analyticsService.batchRecord(batchNo);
    }

    @Operation(summary = "批次全链路台账：补货入/售卖出/停售/解封/下架/报损/盘点/退款/结算")
    @GetMapping("/batches/{batchNo}/trace")
    @PreAuthorize("isAuthenticated()")
    public List<BatchLedger> batchTrace(@PathVariable String batchNo) {
        return analyticsService.batchTrace(batchNo);
    }
}
