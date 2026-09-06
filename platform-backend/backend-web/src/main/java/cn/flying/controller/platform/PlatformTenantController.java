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
import cn.flying.dao.vo.platform.ChangePlatformTenantStatusRequest;
import cn.flying.dao.vo.platform.CreatePlatformTenantRequest;
import cn.flying.dao.vo.platform.PlatformMutationVO;
import cn.flying.dao.vo.platform.PlatformQuotaVO;
import cn.flying.dao.vo.platform.PlatformTenantVO;
import cn.flying.dao.vo.platform.PlatformUsageVO;
import cn.flying.dao.vo.platform.UpdatePlatformQuotaRequest;
import cn.flying.dao.vo.platform.UpdatePlatformTenantRequest;
import cn.flying.service.platform.PlatformTenantCommandService;
import cn.flying.service.platform.PlatformTenantQueryService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Explicit target-tenant lifecycle, usage and quota API. */
@RestController
@RequestMapping("/api/v1/platform/tenants")
@Tag(name = "平台管理")
@RequiredArgsConstructor
@Validated
public class PlatformTenantController {

    private final PlatformTenantQueryService queryService;
    private final PlatformTenantCommandService commandService;

    /** Lists a bounded page of safe tenant metadata. */
    @GetMapping
    @Operation(operationId = "platformListTenants", summary = "分页查询平台租户")
    @PreAuthorize("hasRole('platform_admin') and hasAuthority('" + PlatformPermissions.TENANT_READ + "')")
    @OperationLog(module = "平台管理", operationType = "查询", description = "分页查询平台租户",
            saveRequestData = false, saveResponseData = false)
    public Result<IPage<PlatformTenantVO>> list(@RequestParam(defaultValue = "1") @Min(1) @Max(9007199254740991L) long pageNum,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long pageSize,
            @RequestParam(required = false) @Size(max = 100) String keyword,
            @RequestParam(required = false) @Min(0) @Max(1) Integer status) {
        return Result.success(queryService.list(pageNum, pageSize, keyword, status));
    }

    /** Creates tenant metadata and initial quota without default credentials. */
    @PostMapping
    @Operation(operationId = "platformCreateTenant", summary = "创建租户")
    @PreAuthorize("hasRole('platform_admin') and hasAuthority('" + PlatformPermissions.TENANT_WRITE + "')")
    @OperationLog(module = "平台管理", operationType = "创建", description = "创建租户",
            saveRequestData = false, saveResponseData = false)
    public Result<PlatformMutationVO> create(@RequestHeader("Idempotency-Key") @Pattern(regexp = PlatformIds.UUID_PATTERN) String key,
            @Valid @RequestBody CreatePlatformTenantRequest request) {
        return Result.success(commandService.create(key, request));
    }

    /** Reads one typed explicit tenant target. */
    @GetMapping("/{tenantId}")
    @Operation(operationId = "platformGetTenant", summary = "查询租户详情")
    @PreAuthorize("hasRole('platform_admin') and hasAuthority('" + PlatformPermissions.TENANT_READ + "')")
    @OperationLog(module = "平台管理", operationType = "查询", description = "查询租户详情",
            saveRequestData = false, saveResponseData = false)
    public Result<PlatformTenantVO> get(@PathVariable String tenantId) {
        return Result.success(queryService.get(PlatformIds.tenant(tenantId)));
    }

    /** Updates safe metadata with an expected resource version. */
    @PutMapping("/{tenantId}")
    @Operation(operationId = "platformUpdateTenant", summary = "更新租户元数据")
    @PreAuthorize("hasRole('platform_admin') and hasAuthority('" + PlatformPermissions.TENANT_WRITE + "')")
    @OperationLog(module = "平台管理", operationType = "修改", description = "更新租户元数据",
            saveRequestData = false, saveResponseData = false)
    public Result<PlatformMutationVO> update(@PathVariable String tenantId, @RequestHeader("Idempotency-Key") @Pattern(regexp = PlatformIds.UUID_PATTERN) String key,
            @Valid @RequestBody UpdatePlatformTenantRequest request) {
        return Result.success(commandService.update(PlatformIds.tenant(tenantId), key, request));
    }

    /** Disables or restores a tenant with durable idempotency and session fencing. */
    @PutMapping("/{tenantId}/status")
    @Operation(operationId = "platformChangeTenantStatus", summary = "更新租户状态")
    @PreAuthorize("hasRole('platform_admin') and hasAuthority('" + PlatformPermissions.TENANT_WRITE + "')")
    @OperationLog(module = "平台管理", operationType = "修改", description = "更新租户状态",
            saveRequestData = false, saveResponseData = false)
    public Result<PlatformMutationVO> changeStatus(@PathVariable String tenantId, @RequestHeader("Idempotency-Key") @Pattern(regexp = PlatformIds.UUID_PATTERN) String key,
            @Valid @RequestBody ChangePlatformTenantStatusRequest request) {
        return Result.success(commandService.changeStatus(PlatformIds.tenant(tenantId), key, request));
    }

    /** Returns explicitly tenant-scoped usage and business audit counts. */
    @GetMapping("/{tenantId}/usage")
    @Operation(operationId = "platformGetTenantUsage", summary = "查询租户用量")
    @PreAuthorize("hasRole('platform_admin') and hasAuthority('" + PlatformPermissions.TENANT_READ + "')")
    @OperationLog(module = "平台管理", operationType = "查询", description = "查询租户用量",
            saveRequestData = false, saveResponseData = false)
    public Result<PlatformUsageVO> usage(@PathVariable String tenantId) {
        return Result.success(queryService.usage(PlatformIds.tenant(tenantId)));
    }

    /** Returns effective quota and current override version. */
    @GetMapping("/{tenantId}/quota")
    @Operation(operationId = "platformGetTenantQuota", summary = "查询租户配额")
    @PreAuthorize("hasRole('platform_admin') and hasAuthority('" + PlatformPermissions.QUOTA_READ + "')")
    @OperationLog(module = "平台管理", operationType = "查询", description = "查询租户配额",
            saveRequestData = false, saveResponseData = false)
    public Result<PlatformQuotaVO> quota(@PathVariable String tenantId) {
        return Result.success(queryService.quota(PlatformIds.tenant(tenantId)));
    }

    /** Updates only the exact versioned quota override of the explicit tenant. */
    @PutMapping("/{tenantId}/quota")
    @Operation(operationId = "platformUpdateTenantQuota", summary = "更新租户配额")
    @PreAuthorize("hasRole('platform_admin') and hasAuthority('" + PlatformPermissions.QUOTA_WRITE + "')")
    @OperationLog(module = "平台管理", operationType = "修改", description = "更新租户配额",
            saveRequestData = false, saveResponseData = false)
    public Result<PlatformMutationVO> updateQuota(@PathVariable String tenantId, @RequestHeader("Idempotency-Key") @Pattern(regexp = PlatformIds.UUID_PATTERN) String key,
            @Valid @RequestBody UpdatePlatformQuotaRequest request) {
        return Result.success(commandService.updateQuota(PlatformIds.tenant(tenantId), key, request));
    }
}
