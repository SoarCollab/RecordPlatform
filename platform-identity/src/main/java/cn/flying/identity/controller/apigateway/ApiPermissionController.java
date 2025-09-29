package cn.flying.identity.controller.apigateway;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.flying.identity.dto.apigateway.ApiPermission;
import cn.flying.identity.service.apigateway.ApiPermissionService;
import cn.flying.identity.util.ResponseConverter;
import cn.flying.identity.vo.RestResponse;
import cn.flying.platformapi.constant.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * API权限管理控制器
 * 提供符合RESTful规范的API访问权限的授予、撤销、验证等接口
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
@RestController
@RequestMapping("/api/gateway/permissions")
@Tag(name = "API权限管理", description = "提供API访问权限的授予、撤销、验证等功能")
@SaCheckLogin
public class ApiPermissionController extends BaseApiGatewayController {

    @Resource
    private ApiPermissionService permissionService;

    /**
     * 授予权限
     * POST /api/gateway/permissions - 授予单个权限
     */
    @PostMapping
    @Operation(summary = "授予权限", description = "授予应用访问指定接口的权限")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "权限授予成功"),
        @ApiResponse(responseCode = "400", description = "参数无效"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "404", description = "应用或接口不存在"),
        @ApiResponse(responseCode = "409", description = "权限已存在")
    })
    @SaCheckPermission("api:permission:grant")
    public ResponseEntity<RestResponse<Void>> grantPermission(
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "接口ID", required = true) @RequestParam Long interfaceId,
            @Parameter(description = "有效天数") @RequestParam(required = false) Integer expireDays) {

        Long grantBy = getCurrentUserId();
        log.info("授予权限: appId={}, interfaceId={}, grantBy={}", appId, interfaceId, grantBy);
        
        Result<Void> result = permissionService.grantPermission(appId, interfaceId, grantBy, expireDays);
        
        if (result.isSuccess()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(RestResponse.created(null));
        } else {
            RestResponse<Void> response = ResponseConverter.convert(result);
            return ResponseEntity.status(response.getStatus()).body(response);
        }
    }

    /**
     * 批量授予权限
     * POST /api/gateway/permissions/batch - 批量授予权限
     */
    @PostMapping("/batch")
    @Operation(summary = "批量授予权限", description = "批量授予应用访问多个接口的权限")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "批量授予成功"),
        @ApiResponse(responseCode = "400", description = "参数无效"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "404", description = "应用或部分接口不存在")
    })
    @SaCheckPermission("api:permission:grant")
    public ResponseEntity<RestResponse<Map<String, Object>>> grantBatchPermissions(
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "接口ID列表", required = true) @RequestBody List<Long> interfaceIds,
            @Parameter(description = "有效天数") @RequestParam(required = false) Integer expireDays) {

        Long grantBy = getCurrentUserId();
        log.info("批量授予权限: appId={}, interfaceCount={}, grantBy={}", 
            appId, interfaceIds.size(), grantBy);
        
        Result<Map<String, Object>> result = permissionService.grantBatchPermissions(
            appId, interfaceIds, grantBy, expireDays);
        
        if (result.isSuccess()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(RestResponse.created(result.getData()));
        } else {
            RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
            return ResponseEntity.status(response.getStatus()).body(response);
        }
    }

    /**
     * 撤销权限
     * DELETE /api/gateway/permissions - 撤销单个权限
     */
    @DeleteMapping
    @Operation(summary = "撤销权限", description = "撤销应用访问指定接口的权限")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "撤销成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "404", description = "权限不存在")
    })
    @SaCheckPermission("api:permission:revoke")
    public ResponseEntity<Void> revokePermission(
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "接口ID", required = true) @RequestParam Long interfaceId) {

        log.info("撤销权限: appId={}, interfaceId={}", appId, interfaceId);
        Result<Void> result = permissionService.revokePermission(appId, interfaceId);
        
        if (result.isSuccess()) {
            return ResponseEntity.noContent().build();
        } else {
            RestResponse<Void> response = ResponseConverter.convert(result);
            return ResponseEntity.status(response.getStatus()).build();
        }
    }

    /**
     * 更新权限状态
     * PUT /api/gateway/permissions/{permissionId}/status - 更新权限状态
     */
    @PutMapping("/{permissionId}/status")
    @Operation(summary = "更新权限状态", description = "启用或禁用指定的权限")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "操作成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "404", description = "权限不存在")
    })
    @SaCheckPermission("api:permission:manage")
    public ResponseEntity<RestResponse<Void>> updatePermissionStatus(
            @Parameter(description = "权限ID", required = true) @PathVariable Long permissionId,
            @Parameter(description = "是否启用", required = true) @RequestParam boolean enabled) {

        log.info("更新权限状态: permissionId={}, enabled={}", permissionId, enabled);
        Result<Void> result = enabled 
            ? permissionService.enablePermission(permissionId)
            : permissionService.disablePermission(permissionId);
        
        RestResponse<Void> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 检查权限
     * GET /api/gateway/permissions/check - 验证权限
     */
    @GetMapping("/check")
    @Operation(summary = "检查权限", description = "验证应用是否有访问接口的权限")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "验证完成"),
        @ApiResponse(responseCode = "400", description = "参数无效")
    })
    public ResponseEntity<RestResponse<Boolean>> hasPermission(
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "接口ID", required = true) @RequestParam Long interfaceId) {

        log.debug("检查权限: appId={}, interfaceId={}", appId, interfaceId);
        Result<Boolean> result = permissionService.hasPermission(appId, interfaceId);
        RestResponse<Boolean> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 根据路径检查权限
     * GET /api/gateway/permissions/check-by-path - 根据路径验证权限
     */
    @GetMapping("/check-by-path")
    @Operation(summary = "根据路径检查权限", description = "通过接口路径和方法验证权限")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "验证完成"),
        @ApiResponse(responseCode = "400", description = "参数无效")
    })
    public ResponseEntity<RestResponse<Boolean>> hasPermissionByPath(
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "接口路径", required = true) @RequestParam String interfacePath,
            @Parameter(description = "接口方法", required = true) @RequestParam String interfaceMethod) {

        log.debug("根据路径检查权限: appId={}, path={}, method={}", appId, interfacePath, interfaceMethod);
        Result<Boolean> result = permissionService.hasPermissionByPath(appId, interfacePath, interfaceMethod);
        RestResponse<Boolean> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取应用权限列表
     * GET /api/gateway/permissions/application/{appId} - 获取应用的所有权限
     */
    @GetMapping("/application/{appId}")
    @Operation(summary = "获取应用权限列表", description = "查询指定应用的所有权限")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "404", description = "应用不存在")
    })
    @SaCheckPermission("api:permission:list")
    public ResponseEntity<RestResponse<List<ApiPermission>>> getPermissionsByApp(
            @Parameter(description = "应用ID", required = true) @PathVariable Long appId) {

        log.info("查询应用权限列表: appId={}", appId);
        Result<List<ApiPermission>> result = permissionService.getPermissionsByApp(appId);
        RestResponse<List<ApiPermission>> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取接口权限列表
     * GET /api/gateway/permissions/interface/{interfaceId} - 获取接口的所有权限
     */
    @GetMapping("/interface/{interfaceId}")
    @Operation(summary = "获取接口权限列表", description = "查询哪些应用有访问该接口的权限")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "404", description = "接口不存在")
    })
    @SaCheckPermission("api:permission:list")
    public ResponseEntity<RestResponse<List<ApiPermission>>> getPermissionsByInterface(
            @Parameter(description = "接口ID", required = true) @PathVariable Long interfaceId) {

        log.info("查询接口权限列表: interfaceId={}", interfaceId);
        Result<List<ApiPermission>> result = permissionService.getPermissionsByInterface(interfaceId);
        RestResponse<List<ApiPermission>> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取可访问接口
     * GET /api/gateway/permissions/accessible-interfaces/{appId} - 获取可访问的接口
     */
    @GetMapping("/accessible-interfaces/{appId}")
    @Operation(summary = "获取可访问接口", description = "查询应用有权限访问的所有接口")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "404", description = "应用不存在")
    })
    @SaCheckPermission("api:permission:list")
    public ResponseEntity<RestResponse<List<Map<String, Object>>>> getAccessibleInterfaces(
            @Parameter(description = "应用ID", required = true) @PathVariable Long appId) {

        log.info("查询可访问接口: appId={}", appId);
        Result<List<Map<String, Object>>> result = permissionService.getAccessibleInterfaces(appId);
        RestResponse<List<Map<String, Object>>> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 延长权限
     * PUT /api/gateway/permissions/{permissionId}/expiration - 延长权限有效期
     */
    @PutMapping("/{permissionId}/expiration")
    @Operation(summary = "延长权限", description = "延长权限的有效期")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "延长成功"),
        @ApiResponse(responseCode = "400", description = "参数无效"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "404", description = "权限不存在")
    })
    @SaCheckPermission("api:permission:manage")
    public ResponseEntity<RestResponse<Void>> extendPermission(
            @Parameter(description = "权限ID", required = true) @PathVariable Long permissionId,
            @Parameter(description = "延长天数", required = true) @RequestParam int expireDays) {

        log.info("延长权限: permissionId={}, expireDays={}", permissionId, expireDays);
        Result<Void> result = permissionService.extendPermission(permissionId, expireDays);
        RestResponse<Void> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取即将过期权限
     * GET /api/gateway/permissions/expiring - 获取即将过期的权限
     */
    @GetMapping("/expiring")
    @Operation(summary = "获取即将过期权限", description = "查询指定天数内即将过期的权限")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    @SaCheckPermission("api:permission:list")
    public ResponseEntity<RestResponse<List<ApiPermission>>> getExpiringPermissions(
            @Parameter(description = "天数阈值") @RequestParam(defaultValue = "7") int days) {

        log.info("查询即将过期权限: days={}", days);
        Result<List<ApiPermission>> result = permissionService.getExpiringPermissions(days);
        RestResponse<List<ApiPermission>> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 清理过期权限
     * POST /api/gateway/permissions/cleanup - 清理过期权限
     */
    @PostMapping("/cleanup")
    @Operation(summary = "清理过期权限", description = "自动禁用或删除已过期的权限")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "清理完成"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    @SaCheckPermission("api:permission:clean")
    public ResponseEntity<RestResponse<Integer>> cleanExpiredPermissions() {
        log.info("清理过期权限");
        
        Result<Integer> result = permissionService.cleanExpiredPermissions();
        RestResponse<Integer> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 同步权限缓存
     * POST /api/gateway/permissions/cache/sync/{appId} - 同步权限到缓存
     */
    @PostMapping("/cache/sync/{appId}")
    @Operation(summary = "同步权限缓存", description = "将应用权限同步到Redis缓存")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "同步成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "404", description = "应用不存在")
    })
    @SaCheckPermission("api:permission:sync")
    public ResponseEntity<RestResponse<Void>> syncPermissionsToCache(
            @Parameter(description = "应用ID", required = true) @PathVariable Long appId) {

        log.info("同步权限缓存: appId={}", appId);
        Result<Void> result = permissionService.syncPermissionsToCache(appId);
        RestResponse<Void> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }
}