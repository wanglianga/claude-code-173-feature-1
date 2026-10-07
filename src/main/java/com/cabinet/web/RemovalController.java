package com.cabinet.web;

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
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@Tag(name = "临期下架与补货员处置任务",
     description = "临期扫描(批次级停售)、扫码到场、清点四去向(报损/回收/退供/复售)、逾期与限流曝光")
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

    public record ExecuteRequest(Disposition disposition, String remark) {}

    public record CountRequest(List<RemovalService.CountLine> counts, String remark) {}

    @Operation(summary = "下架任务列表（可按柜机/状态筛选，默认全部）")
    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','REPLENISHER','FINANCE')")
    public List<RemovalOrder> list(@RequestParam(required = false) String cabinetNo,
                                   @RequestParam(required = false) RemovalStatus status) {
        List<RemovalOrder> all = removalOrderRepository
                .findAll(Sort.by(Sort.Direction.DESC, "id"));
        return all.stream()
                .filter(r -> status == null || r.getStatus() == status)
                .filter(r -> cabinetNo == null || cabinetNo.isBlank()
                        || (r.getCabinet() != null
                            && cabinetNo.equals(r.getCabinet().getCabinetNo())))
                .toList();
    }

    @Operation(summary = "运营端：逾期未处理的下架任务（对应批次已被限流曝光）")
    @GetMapping("/tasks/overdue")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public List<RemovalOrder> overdue() {
        // 实时按截止时间计算，确保不依赖定时巡检也能看到
        removalService.markOverdueTasks();
        return removalOrderRepository.findByStatusAndOverdueTrue(RemovalStatus.PENDING);
    }

    @Operation(summary = "手动触发逾期巡检：逾期任务落标并限制批次曝光，返回处理数")
    @PostMapping("/tasks/overdue-check")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Map<String, Object> overdueCheck() {
        return Map.of("markedOverdue", removalService.markOverdueTasks());
    }

    @Operation(summary = "补货员任务清单（待执行）")
    @GetMapping("/tasks/mine")
    @PreAuthorize("hasAnyRole('REPLENISHER','OPERATOR','ADMIN')")
    public List<RemovalOrder> mine(Principal principal) {
        return removalOrderRepository.findAll().stream()
                .filter(r -> r.getStatus() == RemovalStatus.PENDING
                        && (r.getAssignee() == null
                            || principal.getName().equals(r.getAssignee().getUsername())))
                .sorted((a, b) -> Boolean.compare(!a.isOverdue(), !b.isOverdue()))
                .toList();
    }

    @Operation(summary = "手动触发临期/过期扫描，返回生成的下架任务数（扫描同时完成批次级停售与柜端指令）")
    @PostMapping("/scan-expiry")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public Map<String, Object> scan(@RequestParam(defaultValue = "2") int nearExpiryDays) {
        int n = removalService.scanExpiry(nearExpiryDays);
        return Map.of("createdRemovalTasks", n);
    }

    @Operation(summary = "手工创建下架任务（TEMP_ABUSE/DAMAGED/WRONG_CHANNEL/RECALLED），创建即批次级停售")
    @PostMapping
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','REPLENISHER')")
    public RemovalOrder create(@RequestBody ManualRequest req) {
        var cabinet = cabinetRepository.findByCabinetNo(req.cabinetNo())
                .orElseThrow(() -> ApiException.notFound("柜机不存在"));
        return removalService.createManualOrder(cabinet, req.reason(), req.items());
    }

    @Operation(summary = "补货员到场扫码：绑定任务并返回实时对账数据（当前在库、停售后已售、待清点）")
    @PostMapping("/{removalNo}/scan")
    @PreAuthorize("hasAnyRole('REPLENISHER','OPERATOR','ADMIN')")
    public RemovalService.RemovalTaskView scan(@PathVariable String removalNo, Principal principal) {
        return removalService.scan(removalNo, principal.getName());
    }

    @Operation(summary = "扫码清点提交：按明细逐行登记实际数量与去向(报损/回收/退供/仍可销售)，自动账实对账")
    @PostMapping("/{removalNo}/count")
    @PreAuthorize("hasAnyRole('REPLENISHER','OPERATOR','ADMIN')")
    public RemovalOrder count(@PathVariable String removalNo,
                              @RequestBody CountRequest req, Principal principal) {
        return removalService.countAndExecute(removalNo, principal.getName(),
                req.counts(), req.remark());
    }

    @Operation(summary = "任务详情（含逐行实时库存/停售后已售/差异，用于扫码页与运营核对）")
    @GetMapping("/{removalNo}/task")
    @PreAuthorize("isAuthenticated()")
    public RemovalService.RemovalTaskView task(@PathVariable String removalNo) {
        return removalService.taskViewByNo(removalNo);
    }

    @Operation(summary = "兼容旧接口：按计划数量整单统一去向执行（建议改用 /scan + /count）")
    @PostMapping("/{removalNo}/execute")
    @PreAuthorize("hasAnyRole('REPLENISHER','OPERATOR','ADMIN')")
    public RemovalOrder execute(@PathVariable String removalNo,
                                @RequestBody ExecuteRequest req, Principal principal) {
        Disposition d = req.disposition() != null ? req.disposition() : Disposition.SCRAP;
        return removalService.execute(removalNo, principal.getName(), d, req.remark());
    }

    @Operation(summary = "查看下架明细")
    @GetMapping("/{removalId}/items")
    @PreAuthorize("isAuthenticated()")
    public Object items(@PathVariable Long removalId) {
        return removalItemRepository.findByRemovalOrderId(removalId);
    }
}
