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
import cn.flying.dao.vo.platform.PlatformConfigurationVO;
import cn.flying.dao.vo.platform.PlatformMutationVO;
import cn.flying.dao.vo.platform.UpdatePlatformConfigurationRequest;
import cn.flying.service.platform.PlatformConfigurationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.List;

/** Code-owned safe global configuration API. */
@RestController
@RequestMapping("/api/v1/platform/configuration")
@Tag(name = "平台管理")
@RequiredArgsConstructor
@Validated
public class PlatformConfigurationController {

    private final PlatformConfigurationService service;

    /** Returns safe metadata and validated values from the fixed registry. */
    @GetMapping
    @Operation(operationId = "platformListConfiguration", summary = "查询平台安全配置")
    @PreAuthorize("hasRole('platform_admin') and hasAuthority('" + PlatformPermissions.CONFIGURATION_READ + "')")
    @OperationLog(module = "平台管理", operationType = "查询", description = "查询平台安全配置",
            saveRequestData = false, saveResponseData = false)
    public Result<List<PlatformConfigurationVO>> list() {
        return Result.success(service.list());
    }

    /** Returns one allowlisted configuration entry. */
    @GetMapping("/{key}")
    @Operation(operationId = "platformGetConfiguration", summary = "查询平台配置详情")
    @PreAuthorize("hasRole('platform_admin') and hasAuthority('" + PlatformPermissions.CONFIGURATION_READ + "')")
    @OperationLog(module = "平台管理", operationType = "查询", description = "查询平台配置详情",
            saveRequestData = false, saveResponseData = false)
    public Result<PlatformConfigurationVO> get(@PathVariable String key) {
        return Result.success(service.get(key));
    }

    /** Updates an allowlisted integer with expected-version and idempotency requirements. */
    @PutMapping("/{key}")
    @Operation(operationId = "platformUpdateConfiguration", summary = "更新平台安全配置")
    @PreAuthorize("hasRole('platform_admin') and hasAuthority('" + PlatformPermissions.CONFIGURATION_WRITE + "')")
    @OperationLog(module = "平台管理", operationType = "修改", description = "更新平台安全配置",
            saveRequestData = false, saveResponseData = false)
    public Result<PlatformMutationVO> update(@PathVariable String key,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = PlatformIds.UUID_PATTERN) String idempotencyKey,
            @Valid @RequestBody UpdatePlatformConfigurationRequest request) {
        return Result.success(service.update(key, idempotencyKey, request));
    }
}
