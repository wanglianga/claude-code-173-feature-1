package com.cabinet.web;

import com.cabinet.domain.RemovalItem;
import com.cabinet.domain.RemovalOrder;
import com.cabinet.domain.enums.Disposition;
import com.cabinet.domain.enums.RemovalReason;
import com.cabinet.domain.enums.RemovalStatus;
import com.cabinet.exception.ApiException;
import com.cabinet.repo.CabinetRepository;
import com.cabinet.repo.RemovalItemRepository;
import com.cabinet.repo.RemovalOrderRepository;
import com.cabinet.service.RemovalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@Tag(name = "临期下架与处置",
     description = "临期扫描批次级停售、补货员扫码领取、现场清点去向（报损/回收/退供/仍可售）、逾期与曝光限制、柜机同步")
@RestController
@RequestMapping("/api/removals")
@RequiredArgsConstructor
public class RemovalController {

    private final RemovalService removalService;
    private final RemovalItemRepository removalItemRepository;
    private final RemovalOrderRepository removalOrderRepository;
    private final CabinetRepository cabinetRepository;

    public record ManualRequest(String cabinetNo, RemovalReason reason,
                                List<RemovalService.ManualRemovalLine> items) {}

    /** 旧接口兼容：整单同一去向 */
    public record ExecuteRequest(Disposition disposition, String remark) {}

    /** 现场清点执行：逐行实点 + 四种去向 */
    public record CountRequest(List<RemovalService.CountLine> lines, String remark) {}

    public record ReleaseRequest(String reason) {}

    @Operation(summary = "下架任务列表（可按柜机/状态筛选）")
    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','REPLENISHER','FINANCE')")
    public List<RemovalOrder> list(@RequestParam(required = false) String cabinetNo,
                                   @RequestParam(required = false) RemovalStatus status) {
        List<RemovalStatus> statuses = status != null
                ? List.of(status)
                : List.of(RemovalStatus.PENDING, RemovalStatus.ACCEPTED,
                          RemovalStatus.EXECUTED, RemovalStatus.CANCELLED);
        List<RemovalOrder> all = removalOrderRepository
                .findByStatusInOrderByScheduledAtDesc(statuses);
        if (cabinetNo == null || cabinetNo.isBlank()) return all;
        return all.stream()
                .filter(r -> r.getCabinet() != null
                        && cabinetNo.equals(r.getCabinet().getCabinetNo()))
                .toList();
    }

    @Operation(summary = "逾期任务列表（运营端）")
    @GetMapping("/overdue")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','FINANCE')")
    public List<RemovalOrder> overdue() {
        return removalOrderRepository.findByOverdueMarkedAtIsNotNullAndStatusInOrderByDueAtAsc(
                List.of(RemovalStatus.PENDING, RemovalStatus.ACCEPTED,
                        RemovalStatus.EXECUTED, RemovalStatus.CANCELLED));
    }

    @Operation(summary = "手动触发临期/过期扫描：批次级停售并生成下架任务，返回任务数")
    @PostMapping("/scan-expiry")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Map<String, Object> scan(@RequestParam(defaultValue = "2") int nearExpiryDays) {
        int n = removalService.scanExpiry(nearExpiryDays);
        return Map.of("createdRemovalOrders", n);
    }

    @Operation(summary = "手动触发逾期检查：逾期任务标记并限制批次曝光")
    @PostMapping("/mark-overdue")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Map<String, Object> markOverdue() {
        return Map.of("overdueOrders", removalService.markOverdue());
    }

    @Operation(summary = "手工创建下架任务（TEMP_ABUSE/DAMAGED/WRONG_CHANNEL/RECALLED），创建即停售")
    @PostMapping
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','REPLENISHER')")
    public RemovalOrder create(@RequestBody ManualRequest req) {
        var cabinet = cabinetRepository.findByCabinetNo(req.cabinetNo())
                .orElseThrow(() -> ApiException.notFound("柜机不存在"));
        return removalService.createManualOrder(cabinet, req.reason(), req.items());
    }

    @Operation(summary = "补货员扫码领取下架任务")
    @PostMapping("/{removalNo}/accept")
    @PreAuthorize("hasAnyRole('REPLENISHER','OPERATOR','ADMIN')")
    public RemovalOrder accept(@PathVariable String removalNo, Principal principal) {
        return removalService.accept(removalNo, principal.getName());
    }

    @Operation(summary = "现场清点并执行：每行实点数量与报损/回收/退供/仍可售去向")
    @PostMapping("/{removalNo}/count")
    @PreAuthorize("hasAnyRole('REPLENISHER','OPERATOR','ADMIN')")
    public RemovalOrder count(@PathVariable String removalNo, @RequestBody CountRequest req,
                              Principal principal) {
        if (req.lines() == null || req.lines().isEmpty()) {
            throw ApiException.badRequest("请提交清点行");
        }
        return removalService.countAndExecute(removalNo, principal.getName(),
                req.lines(), req.remark());
    }

    @Operation(summary = "执行下架（兼容旧接口）：整单同一去向 SCRAP/RECYCLE/RETURN_SUPPLIER")
    @PostMapping("/{removalNo}/execute")
    @PreAuthorize("hasAnyRole('REPLENISHER','OPERATOR','ADMIN')")
    public RemovalOrder execute(@PathVariable String removalNo,
                                @RequestBody ExecuteRequest req, Principal principal) {
        Disposition d = req.disposition() != null ? req.disposition() : Disposition.SCRAP;
        return removalService.execute(removalNo, principal.getName(), d, req.remark());
    }

    @Operation(summary = "运营解封批次曝光（现场已处置或核实无风险）")
    @PostMapping("/batches/{batchNo}/release-exposure")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Map<String, Object> release(@PathVariable String batchNo, @RequestBody ReleaseRequest req) {
        removalService.releaseExposure(batchNo,
                req != null && req.reason() != null ? req.reason() : "运营人工解封");
        return Map.of("batchNo", batchNo, "exposureBlocked", false);
    }

    @Operation(summary = "柜机上线同步：停售批次清单与未完成下架任务（补齐离线期间停售指令）")
    @GetMapping("/device-sync")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','REPLENISHER')")
    public RemovalService.DeviceSyncView deviceSync(@RequestParam String cabinetNo) {
        return removalService.deviceSync(cabinetNo);
    }

    @Operation(summary = "查看下架任务明细（系统停售数量、实点、四种去向）")
    @GetMapping("/{removalId}/items")
    @PreAuthorize("isAuthenticated()")
    public List<RemovalItem> items(@PathVariable Long removalId) {
        return removalItemRepository.findByRemovalOrderId(removalId);
    }
}
