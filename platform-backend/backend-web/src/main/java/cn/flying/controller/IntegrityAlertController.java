package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.entity.IntegrityAlert;
import cn.flying.dao.vo.file.IntegrityAlertVO;
import cn.flying.dao.vo.file.IntegrityCheckStatsVO;
import cn.flying.dao.vo.file.ResolveAlertVO;
import cn.flying.service.integrity.IntegrityCheckService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin controller for managing storage integrity check alerts.
 */
@RestController
@RequestMapping("/api/v1/admin/integrity-alerts")
@PreAuthorize("isAdmin()")
@Tag(name = "Admin - Integrity Alerts", description = "Storage integrity check alert management")


@RequiredArgsConstructor
public class IntegrityAlertController {

    private final IntegrityCheckService integrityCheckService;

    /**
     * List integrity alerts with pagination and optional filters.
     */
    @GetMapping
    @Operation(summary = "List integrity alerts (paginated)")
    @OperationLog(module = "integrity", operationType = "query", description = "List integrity alerts")
    public Result<IPage<IntegrityAlertVO>> listAlerts(
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId,
            @Parameter(description = "Alert status filter") @RequestParam(required = false) Integer status,
            @Parameter(description = "Alert type filter") @RequestParam(required = false) String alertType,
            @Parameter(description = "Page number") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") Integer pageSize) {

        Page<IntegrityAlert> page = new Page<>(pageNum, pageSize);
        IPage<IntegrityAlert> result = integrityCheckService.listAlerts(tenantId, status, alertType, page);
        IPage<IntegrityAlertVO> voPage = result.convert(IntegrityAlertController::toAlertVO);
        return Result.success(voPage);
    }

    /**
     * Trigger a manual integrity check for the current tenant.
     */
    @PostMapping("/check")
    @Operation(summary = "Trigger manual integrity check")
    @OperationLog(module = "integrity", operationType = "execute", description = "Trigger manual integrity check")
    public Result<IntegrityCheckStatsVO> triggerManualCheck(
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId) {
        IntegrityCheckStatsVO stats = integrityCheckService.triggerManualCheck(tenantId);
        return Result.success(stats);
    }

    /**
     * Acknowledge an integrity alert.
     */
    @PutMapping("/{id}/acknowledge")
    @Operation(summary = "Acknowledge an integrity alert")
    @OperationLog(module = "integrity", operationType = "update", description = "Acknowledge integrity alert")
    public Result<String> acknowledgeAlert(
            @Parameter(description = "Alert ID") @PathVariable String id,
            @RequestAttribute(Const.ATTR_USER_ID) Long userId) {
        Long alertId = IdUtils.fromExternalId(id);
        if (alertId == null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID);
        }
        integrityCheckService.acknowledgeAlert(alertId, userId);
        return Result.success("Alert acknowledged");
    }

    /**
     * Resolve an integrity alert with a note.
     */
    @PutMapping("/{id}/resolve")
    @Operation(summary = "Resolve an integrity alert")
    @OperationLog(module = "integrity", operationType = "update", description = "Resolve integrity alert")
    public Result<String> resolveAlert(
            @Parameter(description = "Alert ID") @PathVariable String id,
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @RequestBody @Valid ResolveAlertVO request) {
        Long alertId = IdUtils.fromExternalId(id);
        if (alertId == null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID);
        }
        integrityCheckService.resolveAlert(alertId, userId, request.note());
        return Result.success("Alert resolved");
    }

    /**
     * Convert IntegrityAlert entity to IntegrityAlertVO, using external IDs.
     */
    private static IntegrityAlertVO toAlertVO(IntegrityAlert alert) {
        if (alert == null) {
            return null;
        }
        return new IntegrityAlertVO(
                IdUtils.toExternalId(alert.getId()),
                IdUtils.toExternalId(alert.getFileId()),
                alert.getFileHash(),
                alert.getActualHash(),
                alert.getChainHash(),
                alert.getAlertType(),
                alert.getStatus(),
                IdUtils.toExternalId(alert.getResolvedBy()),
                alert.getResolvedAt(),
                alert.getNote(),
                alert.getCreateTime()
        );
    }
}
