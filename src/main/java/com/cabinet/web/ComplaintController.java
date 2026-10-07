package com.cabinet.web;

import com.cabinet.domain.Complaint;
import com.cabinet.dto.EvidenceView;
import com.cabinet.repo.ComplaintRepository;
import com.cabinet.service.ComplaintService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@Tag(name = "客服投诉售后", description = "投诉提交、证据回放（开门/识别/视频/库存变化/温控）、判责退款")
@RestController
@RequestMapping("/api/complaints")
@RequiredArgsConstructor
public class ComplaintController {

    private final ComplaintService complaintService;
    private final ComplaintRepository complaintRepository;

    public record SubmitRequest(String sessionNo, String category, String description) {}

    public record ResolveRequest(Boolean grantRefund,
                                 List<ComplaintService.RefundLine> refundItems,
                                 Boolean goodsReturned,
                                 Boolean supplierLiability,
                                 String resolution) {}

    @Operation(summary = "用户就某开门会话提交投诉（误扣款/放回仍计费/临期变质/卡货等）")
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public Complaint submit(@RequestBody SubmitRequest req) {
        return complaintService.submit(req.sessionNo(), req.category(), req.description());
    }

    @Operation(summary = "投诉列表")
    @GetMapping
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE','OPERATOR','ADMIN')")
    public List<Complaint> list() {
        return complaintRepository.findAll();
    }

    @Operation(summary = "证据回放：开门记录+识别结果(含视频片段)+订单+批次台账+温控曲线")
    @GetMapping("/{complaintNo}/evidence")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE','OPERATOR','ADMIN','FINANCE')")
    public EvidenceView evidence(@PathVariable String complaintNo) {
        return complaintService.evidence(complaintNo);
    }

    @Operation(summary = "客服判责：退款（整单/按行）+ 商品是否收回；或驳回")
    @PostMapping("/{complaintNo}/resolve")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE','ADMIN')")
    public Complaint resolve(@PathVariable String complaintNo, @RequestBody ResolveRequest req,
                             Principal principal) {
        boolean grant = Boolean.TRUE.equals(req.grantRefund());
        boolean returned = Boolean.TRUE.equals(req.goodsReturned());
        boolean supplierLiable = Boolean.TRUE.equals(req.supplierLiability());
        return complaintService.resolve(complaintNo, principal.getName(), grant,
                req.refundItems(), returned, supplierLiable, req.resolution());
    }
}
