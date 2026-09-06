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
import cn.flying.dao.vo.platform.PlatformSessionVO;
import cn.flying.dao.vo.platform.PlatformOverviewVO;
import cn.flying.dao.vo.platform.PlatformHealthVO;
import cn.flying.service.platform.PlatformQueryService;

/** Platform identity, overview and sanitized resource health endpoints. */
@RestController
@RequestMapping("/api/v1/platform")
@Tag(name = "平台管理")
@RequiredArgsConstructor
@Validated
public class PlatformOverviewController {

    private final PlatformQueryService queryService;

    /** Returns the authenticated system-scoped platform identity. */
    @GetMapping("/session")
    @Operation(operationId = "platformGetSession", summary = "查询平台会话能力")
    @PreAuthorize("hasRole('platform_admin') and hasAuthority('" + PlatformPermissions.OVERVIEW_READ + "')")
    @OperationLog(module = "平台管理", operationType = "查询", description = "查询平台会话能力",
            saveRequestData = false, saveResponseData = false)
    public Result<PlatformSessionVO> session() {
        return Result.success(queryService.session());
    }

    /** Returns bounded platform metadata counts. */
    @GetMapping("/overview")
    @Operation(operationId = "platformGetOverview", summary = "查询平台概览")
    @PreAuthorize("hasRole('platform_admin') and hasAuthority('" + PlatformPermissions.OVERVIEW_READ + "')")
    @OperationLog(module = "平台管理", operationType = "查询", description = "查询平台概览",
            saveRequestData = false, saveResponseData = false)
    public Result<PlatformOverviewVO> overview() {
        return Result.success(queryService.overview());
    }

    /** Returns allowlisted shared component status only. */
    @GetMapping("/resources/health")
    @Operation(operationId = "platformGetResourceHealth", summary = "查询共享资源健康")
    @PreAuthorize("hasRole('platform_admin') and hasAuthority('" + PlatformPermissions.RESOURCE_READ + "')")
    @OperationLog(module = "平台管理", operationType = "查询", description = "查询共享资源健康",
            saveRequestData = false, saveResponseData = false)
    public Result<PlatformHealthVO> health() {
        return Result.success(queryService.health());
    }
}
