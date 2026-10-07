package com.cabinet.web;

import com.cabinet.dto.BatchRecordView;
import com.cabinet.service.BatchCareService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * 客服批次关怀 + 批次还原：
 * 商品买走后发现批次问题时，按批次找购买订单、发风险提醒、做供应商责任退款；
 * 批次记录聚合视图还原下架原因、实物去向与结算变化。
 */
@Tag(name = "批次关怀与还原",
     description = "按批次追溯购买订单、风险提醒、供应商责任退款、批次全记录还原")
@RestController
@RequestMapping("/api/batches")
@RequiredArgsConstructor
public class BatchCareController {

    private final BatchCareService batchCareService;

    @Operation(summary = "按批次查询购买订单（买走该批次的用户、订单、可退金额）")
    @GetMapping("/{batchNo}/orders")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE','OPERATOR','ADMIN','FINANCE')")
    public List<BatchCareService.BatchOrderRow> orders(@PathVariable String batchNo) {
        return batchCareService.batchOrders(batchNo);
    }

    public record RiskRequest(String message) {}

    @Operation(summary = "批次风险提醒：对买过该批次的用户逐单生成提醒与跟进告警")
    @PostMapping("/{batchNo}/risk-notify")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE','ADMIN')")
    public BatchCareService.RiskNotifyResult risk(@PathVariable String batchNo,
                                                  @RequestBody RiskRequest req, Principal principal) {
        return batchCareService.notifyRisk(batchNo, principal.getName(),
                req.message() == null ? "" : req.message());
    }

    public record RefundRequest(String reason) {}

    @Operation(summary = "供应商责任批量退款：对该批次未退订单全额退款，实物未收回记供应商责任报损")
    @PostMapping("/{batchNo}/refund")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE','ADMIN')")
    public BatchCareService.BatchRefundResult refund(@PathVariable String batchNo,
                                                     @RequestBody RefundRequest req, Principal principal) {
        return batchCareService.refundByBatch(batchNo, principal.getName(),
                req.reason() == null ? "批次质量/临期问题" : req.reason());
    }

    @Operation(summary = "批次全记录还原：主数据/台账/报损/订单/下架四去向/结算/告警")
    @GetMapping("/{batchNo}/record")
    @PreAuthorize("isAuthenticated()")
    public BatchRecordView record(@PathVariable String batchNo) {
        return batchCareService.batchRecord(batchNo);
    }
}
