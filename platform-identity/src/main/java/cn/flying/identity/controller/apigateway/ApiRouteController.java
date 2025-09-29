package cn.flying.identity.controller.apigateway;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.flying.identity.dto.apigateway.ApiRoute;
import cn.flying.identity.service.apigateway.ApiRouteService;
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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * API路由管理控制器
 * 提供符合RESTful规范的动态路由配置、管理、监控等接口
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
@RestController
@RequestMapping("/api/gateway/routes")
@Tag(name = "API路由管理", description = "提供动态路由配置、管理、监控等功能")
@SaCheckLogin
public class ApiRouteController {

    @Resource
    private ApiRouteService routeService;

    /**
     * 创建路由
     * POST /api/gateway/routes - 创建新路由
     */
    @PostMapping
    @Operation(summary = "创建路由", description = "创建新的API路由规则")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "创建成功"),
        @ApiResponse(responseCode = "400", description = "参数无效"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "409", description = "路由已存在")
    })
    @SaCheckPermission("api:route:create")
    public ResponseEntity<RestResponse<ApiRoute>> createRoute(@RequestBody @Validated ApiRoute route) {
        log.info("创建路由: path={}, service={}", route.getRoutePath(), route.getServiceName());
        
        Result<ApiRoute> result = routeService.createRoute(route);
        
        if (result.isSuccess()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(RestResponse.created(result.getData()));
        } else {
            RestResponse<ApiRoute> response = ResponseConverter.convert(result);
            return ResponseEntity.status(response.getStatus()).body(response);
        }
    }

    /**
     * 更新路由
     * PUT /api/gateway/routes/{routeId} - 更新路由
     */
    @PutMapping("/{routeId}")
    @Operation(summary = "更新路由", description = "修改已有路由的配置信息")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "更新成功"),
        @ApiResponse(responseCode = "400", description = "参数无效"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "404", description = "路由不存在")
    })
    @SaCheckPermission("api:route:update")
    public ResponseEntity<RestResponse<Void>> updateRoute(
            @Parameter(description = "路由ID", required = true) @PathVariable Long routeId,
            @RequestBody @Validated ApiRoute route) {
        
        route.setId(routeId);
        log.info("更新路由: routeId={}", routeId);
        
        Result<Void> result = routeService.updateRoute(route);
        RestResponse<Void> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 删除路由
     * DELETE /api/gateway/routes/{routeId} - 删除路由
     */
    @DeleteMapping("/{routeId}")
    @Operation(summary = "删除路由", description = "永久删除指定的路由规则")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "删除成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "404", description = "路由不存在")
    })
    @SaCheckPermission("api:route:delete")
    public ResponseEntity<Void> deleteRoute(
            @Parameter(description = "路由ID", required = true) @PathVariable Long routeId) {
        
        log.info("删除路由: routeId={}", routeId);
        Result<Void> result = routeService.deleteRoute(routeId);
        
        if (result.isSuccess()) {
            return ResponseEntity.noContent().build();
        } else {
            RestResponse<Void> response = ResponseConverter.convert(result);
            return ResponseEntity.status(response.getStatus()).build();
        }
    }

    /**
     * 更新路由状态
     * PUT /api/gateway/routes/{routeId}/status - 更新路由状态
     */
    @PutMapping("/{routeId}/status")
    @Operation(summary = "更新路由状态", description = "启用或禁用路由规则")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "操作成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "404", description = "路由不存在")
    })
    @SaCheckPermission("api:route:manage")
    public ResponseEntity<RestResponse<Void>> updateRouteStatus(
            @Parameter(description = "路由ID", required = true) @PathVariable Long routeId,
            @Parameter(description = "是否启用", required = true) @RequestParam boolean enabled) {
        
        log.info("更新路由状态: routeId={}, enabled={}", routeId, enabled);
        Result<Void> result = enabled 
            ? routeService.enableRoute(routeId)
            : routeService.disableRoute(routeId);
        
        RestResponse<Void> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取路由详情
     * GET /api/gateway/routes/{routeId} - 获取路由详情
     */
    @GetMapping("/{routeId}")
    @Operation(summary = "获取路由详情", description = "查询指定路由的详细配置信息")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "404", description = "路由不存在")
    })
    public ResponseEntity<RestResponse<ApiRoute>> getRouteById(
            @Parameter(description = "路由ID", required = true) @PathVariable Long routeId) {
        
        log.info("查询路由详情: routeId={}", routeId);
        Result<ApiRoute> result = routeService.getRouteById(routeId);
        RestResponse<ApiRoute> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取启用的路由
     * GET /api/gateway/routes/active - 获取启用的路由
     */
    @GetMapping("/active")
    @Operation(summary = "获取启用的路由", description = "查询所有处于启用状态的路由规则")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证")
    })
    public ResponseEntity<RestResponse<List<ApiRoute>>> getActiveRoutes() {
        log.info("查询启用的路由列表");
        
        Result<List<ApiRoute>> result = routeService.getActiveRoutes();
        RestResponse<List<ApiRoute>> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 按服务查询路由
     * GET /api/gateway/routes/service/{serviceName} - 获取服务路由
     */
    @GetMapping("/service/{serviceName}")
    @Operation(summary = "按服务查询路由", description = "查询特定服务的所有路由规则")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证")
    })
    public ResponseEntity<RestResponse<List<ApiRoute>>> getRoutesByService(
            @Parameter(description = "服务名称", required = true) @PathVariable String serviceName) {
        
        log.info("按服务查询路由: serviceName={}", serviceName);
        Result<List<ApiRoute>> result = routeService.getRoutesByService(serviceName);
        RestResponse<List<ApiRoute>> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 匹配路由
     * GET /api/gateway/routes/match - 匹配路由
     */
    @GetMapping("/match")
    @Operation(summary = "匹配路由", description = "根据请求路径和方法匹配最合适的路由规则")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "匹配成功"),
        @ApiResponse(responseCode = "404", description = "未找到匹配的路由")
    })
    public ResponseEntity<RestResponse<ApiRoute>> matchRoute(
            @Parameter(description = "请求路径", required = true) @RequestParam String path,
            @Parameter(description = "HTTP方法", required = true) @RequestParam String method) {
        
        log.debug("匹配路由: path={}, method={}", path, method);
        Result<ApiRoute> result = routeService.matchRoute(path, method);
        RestResponse<ApiRoute> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 批量导入路由
     * POST /api/gateway/routes/batch - 批量导入路由
     */
    @PostMapping("/batch")
    @Operation(summary = "批量导入路由", description = "从JSON数组批量导入多条路由规则")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "导入成功"),
        @ApiResponse(responseCode = "400", description = "参数无效"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    @SaCheckPermission("api:route:import")
    public ResponseEntity<RestResponse<Map<String, Object>>> batchImportRoutes(@RequestBody List<ApiRoute> routes) {
        log.info("批量导入路由: count={}", routes.size());
        
        Result<Map<String, Object>> result = routeService.batchImportRoutes(routes);
        
        if (result.isSuccess()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(RestResponse.created(result.getData()));
        } else {
            RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
            return ResponseEntity.status(response.getStatus()).body(response);
        }
    }

    /**
     * 导出路由配置
     * GET /api/gateway/routes/export - 导出路由
     */
    @GetMapping("/export")
    @Operation(summary = "导出路由配置", description = "导出所有路由配置为JSON格式")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "导出成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    @SaCheckPermission("api:route:export")
    public ResponseEntity<RestResponse<List<ApiRoute>>> exportRoutes() {
        log.info("导出路由配置");
        
        Result<List<ApiRoute>> result = routeService.exportRoutes();
        RestResponse<List<ApiRoute>> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 刷新路由缓存
     * POST /api/gateway/routes/cache/refresh - 刷新缓存
     */
    @PostMapping("/cache/refresh")
    @Operation(summary = "刷新路由缓存", description = "手动刷新路由配置缓存")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "刷新成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    @SaCheckPermission("api:route:refresh")
    public ResponseEntity<RestResponse<Void>> refreshRouteCache() {
        log.info("刷新路由缓存");
        
        Result<Void> result = routeService.refreshRouteCache();
        RestResponse<Void> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取路由统计信息
     * GET /api/gateway/routes/{routeId}/statistics - 获取统计信息
     */
    @GetMapping("/{routeId}/statistics")
    @Operation(summary = "获取路由统计", description = "查询路由的调用统计和性能指标")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "404", description = "路由不存在")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getRouteStatistics(
            @Parameter(description = "路由ID", required = true) @PathVariable Long routeId,
            @Parameter(description = "统计天数") @RequestParam(defaultValue = "7") int days) {
        
        log.info("查询路由统计: routeId={}, days={}", routeId, days);
        Result<Map<String, Object>> result = routeService.getRouteStatistics(routeId, days);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 测试路由连通性
     * POST /api/gateway/routes/{routeId}/test - 测试路由
     */
    @PostMapping("/{routeId}/test")
    @Operation(summary = "测试路由", description = "测试路由配置的目标服务连通性")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "测试完成"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "404", description = "路由不存在")
    })
    @SaCheckPermission("api:route:test")
    public ResponseEntity<RestResponse<Map<String, Object>>> testRoute(
            @Parameter(description = "路由ID", required = true) @PathVariable Long routeId) {
        
        log.info("测试路由连通性: routeId={}", routeId);
        Result<Map<String, Object>> result = routeService.testRoute(routeId);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 更新路由优先级
     * PUT /api/gateway/routes/{routeId}/priority - 更新优先级
     */
    @PutMapping("/{routeId}/priority")
    @Operation(summary = "更新路由优先级", description = "调整路由匹配的优先级顺序")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "更新成功"),
        @ApiResponse(responseCode = "400", description = "参数无效"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "404", description = "路由不存在")
    })
    @SaCheckPermission("api:route:update")
    public ResponseEntity<RestResponse<Void>> updateRoutePriority(
            @Parameter(description = "路由ID", required = true) @PathVariable Long routeId,
            @Parameter(description = "优先级（越小越高）", required = true) @RequestParam Integer priority) {
        
        log.info("更新路由优先级: routeId={}, priority={}", routeId, priority);
        Result<Void> result = routeService.updateRoutePriority(routeId, priority);
        RestResponse<Void> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 更新路由限流配置
     * PUT /api/gateway/routes/{routeId}/rate-limit - 更新限流
     */
    @PutMapping("/{routeId}/rate-limit")
    @Operation(summary = "更新路由限流", description = "设置路由的QPS限制")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "更新成功"),
        @ApiResponse(responseCode = "400", description = "参数无效"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "404", description = "路由不存在")
    })
    @SaCheckPermission("api:route:update")
    public ResponseEntity<RestResponse<Void>> updateRouteRateLimit(
            @Parameter(description = "路由ID", required = true) @PathVariable Long routeId,
            @Parameter(description = "限流QPS", required = true) @RequestParam Integer rateLimit) {
        
        log.info("更新路由限流配置: routeId={}, rateLimit={}", routeId, rateLimit);
        Result<Void> result = routeService.updateRouteRateLimit(routeId, rateLimit);
        RestResponse<Void> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取路由健康状态
     * GET /api/gateway/routes/{routeId}/health - 获取健康状态
     */
    @GetMapping("/{routeId}/health")
    @Operation(summary = "获取路由健康状态", description = "查询路由的健康状态和可用性")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "404", description = "路由不存在")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getRouteHealth(
            @Parameter(description = "路由ID", required = true) @PathVariable Long routeId) {
        
        log.debug("查询路由健康状态: routeId={}", routeId);
        Result<Map<String, Object>> result = routeService.getRouteHealth(routeId);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 批量更新路由状态
     * PUT /api/gateway/routes/batch/status - 批量更新状态
     */
    @PutMapping("/batch/status")
    @Operation(summary = "批量更新路由状态", description = "批量启用或禁用多条路由规则")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "操作完成"),
        @ApiResponse(responseCode = "400", description = "参数无效"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    @SaCheckPermission("api:route:manage")
    public ResponseEntity<RestResponse<Map<String, Object>>> batchUpdateRouteStatus(
            @Parameter(description = "路由ID列表", required = true) @RequestBody List<Long> routeIds,
            @Parameter(description = "是否启用", required = true) @RequestParam boolean enabled) {
        
        log.info("批量更新路由状态: count={}, enabled={}", routeIds.size(), enabled);

        Map<String, Object> resultData = new HashMap<>();
        int successCount = 0;
        int failCount = 0;
        List<String> errors = new ArrayList<>();

        for (Long routeId : routeIds) {
            try {
                Result<Void> updateResult = enabled ?
                        routeService.enableRoute(routeId) :
                        routeService.disableRoute(routeId);

                if (updateResult.isSuccess()) {
                    successCount++;
                } else {
                    failCount++;
                    errors.add("路由 " + routeId + ": " + updateResult.getMessage());
                }
            } catch (Exception e) {
                failCount++;
                errors.add("路由 " + routeId + ": " + e.getMessage());
            }
        }

        resultData.put("totalCount", routeIds.size());
        resultData.put("successCount", successCount);
        resultData.put("failCount", failCount);
        resultData.put("errors", errors);

        return ResponseEntity.ok(RestResponse.ok(resultData));
    }

    /**
     * 克隆路由配置
     * POST /api/gateway/routes/{routeId}/clone - 克隆路由
     */
    @PostMapping("/{routeId}/clone")
    @Operation(summary = "克隆路由", description = "基于现有路由创建新的路由规则")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "克隆成功"),
        @ApiResponse(responseCode = "400", description = "参数无效"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "404", description = "源路由不存在")
    })
    @SaCheckPermission("api:route:create")
    public ResponseEntity<RestResponse<ApiRoute>> cloneRoute(
            @Parameter(description = "源路由ID", required = true) @PathVariable Long routeId,
            @Parameter(description = "新路由路径", required = true) @RequestParam String newPath,
            @Parameter(description = "新路由描述") @RequestParam(required = false) String description) {
        
        log.info("克隆路由: sourceId={}, newPath={}", routeId, newPath);

        // 获取源路由
        Result<ApiRoute> sourceResult = routeService.getRouteById(routeId);
        if (!sourceResult.isSuccess()) {
            RestResponse<ApiRoute> response = ResponseConverter.convert(sourceResult);
            return ResponseEntity.status(response.getStatus()).body(response);
        }

        // 创建新路由
        ApiRoute newRoute = sourceResult.getData();
        newRoute.setId(null);
        newRoute.setRoutePath(newPath);
        newRoute.setDescription(description != null ? description : "克隆自路由 " + routeId);
        newRoute.setCreateTime(null);
        newRoute.setUpdateTime(null);

        Result<ApiRoute> createResult = routeService.createRoute(newRoute);
        
        if (createResult.isSuccess()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(RestResponse.created(createResult.getData()));
        } else {
            RestResponse<ApiRoute> response = ResponseConverter.convert(createResult);
            return ResponseEntity.status(response.getStatus()).body(response);
        }
    }

    /**
     * 搜索路由
     * GET /api/gateway/routes/search - 搜索路由
     */
    @GetMapping("/search")
    @Operation(summary = "搜索路由", description = "根据关键词搜索路由配置")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "搜索完成"),
        @ApiResponse(responseCode = "400", description = "参数无效")
    })
    public ResponseEntity<RestResponse<List<ApiRoute>>> searchRoutes(
            @Parameter(description = "搜索关键词") @RequestParam String keyword) {
        
        log.debug("搜索路由: keyword={}", keyword);

        // 获取所有路由并过滤
        Result<List<ApiRoute>> allRoutesResult = routeService.getActiveRoutes();
        if (!allRoutesResult.isSuccess()) {
            RestResponse<List<ApiRoute>> response = ResponseConverter.convert(allRoutesResult);
            return ResponseEntity.status(response.getStatus()).body(response);
        }

        List<ApiRoute> filteredRoutes = allRoutesResult.getData().stream()
                .filter(route ->
                        route.getRoutePath().contains(keyword) ||
                                route.getServiceName().contains(keyword) ||
                                (route.getDescription() != null && route.getDescription().contains(keyword))
                )
                .collect(Collectors.toList());

        return ResponseEntity.ok(RestResponse.ok(filteredRoutes));
    }
}