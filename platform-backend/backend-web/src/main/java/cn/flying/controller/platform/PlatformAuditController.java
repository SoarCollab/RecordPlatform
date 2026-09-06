package cn.flying.controller.platform;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.PlatformPermissions;
import cn.flying.common.constant.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import cn.flying.dao.vo.platform.PlatformAuditVO;
import cn.flying.service.platform.PlatformAuditService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/** System-owned, platform-only operation audit API. */
@RestController
@RequestMapping("/api/v1/platform/audit")
@Tag(name = "平台管理")
@RequiredArgsConstructor
@Validated
public class PlatformAuditController {

    private final PlatformAuditService service;

    /** Lists bounded platform operation outcomes, including failures and in-progress claims. */
    @GetMapping
    @Operation(operationId = "platformListAudit", summary = "分页查询平台操作审计")
    @PreAuthorize("hasRole('platform_admin') and hasAuthority('" + PlatformPermissions.AUDIT_READ + "')")
    @OperationLog(module = "平台管理", operationType = "查询", description = "分页查询平台操作审计",
            saveRequestData = false, saveResponseData = false)
    public Result<IPage<PlatformAuditVO>> list(@RequestParam(defaultValue = "1") @Min(1) @Max(9007199254740991L) long pageNum,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long pageSize,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) @Pattern(regexp = "PROCESSING|SUCCESS|FAILURE") String status) {
        return Result.success(service.list(pageNum, pageSize,
                tenantId == null ? null : PlatformIds.tenant(tenantId), status));
    }

    /** Returns sanitized details for one typed platform operation identifier. */
    @GetMapping("/{operationId}")
    @Operation(operationId = "platformGetAudit", summary = "查询平台操作审计详情")
    @PreAuthorize("hasRole('platform_admin') and hasAuthority('" + PlatformPermissions.AUDIT_READ + "')")
    @OperationLog(module = "平台管理", operationType = "查询", description = "查询平台操作审计详情",
            saveRequestData = false, saveResponseData = false)
    public Result<PlatformAuditVO> get(@PathVariable String operationId) {
        return Result.success(service.get(PlatformIds.entity(operationId)));
    }
}
