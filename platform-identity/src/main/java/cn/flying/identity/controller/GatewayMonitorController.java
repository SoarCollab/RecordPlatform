package cn.flying.identity.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.flying.identity.service.GatewayMonitorService;
import cn.flying.identity.util.ResponseConverter;
import cn.flying.identity.vo.RestResponse;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 网关监控控制器
 * 提供符合RESTful规范的网关流量监控、性能统计等管理接口
 *
 * @author 王贝强
 * @since 2025-01-16
 */
@RestController
@RequestMapping("/api/admin/gateway")
@Tag(name = "网关监控", description = "网关流量监控和性能统计管理")
@SaCheckRole("admin")
public class GatewayMonitorController {

    @Resource
    private GatewayMonitorService gatewayMonitorService;

    /**
     * 获取实时流量统计
     * GET /api/admin/gateway/traffic/realtime
     */
    @GetMapping("/traffic/realtime")
    @Operation(summary = "获取实时流量统计", description = "获取指定时间范围内的实时流量统计数据")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "500", description = "系统错误")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getRealTimeTrafficStats(
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") int timeRange) {
        Result<Map<String, Object>> result = gatewayMonitorService.getRealTimeTrafficStats(timeRange);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取API调用统计
     * GET /api/admin/gateway/apis/stats
     */
    @GetMapping("/apis/stats")
    @Operation(summary = "获取API调用统计", description = "获取API调用频次统计和排行")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getApiCallStats(
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") int timeRange,
            @Parameter(description = "返回条数限制") @RequestParam(defaultValue = "20") int limit) {
        Result<Map<String, Object>> result = gatewayMonitorService.getApiCallStats(timeRange, limit);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取错误统计
     * GET /api/admin/gateway/errors/stats
     */
    @GetMapping("/errors/stats")
    @Operation(summary = "获取错误统计", description = "获取系统错误统计和分析")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getErrorStats(
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") int timeRange) {
        Result<Map<String, Object>> result = gatewayMonitorService.getErrorStats(timeRange);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取性能统计
     * GET /api/admin/gateway/performance/stats
     */
    @GetMapping("/performance/stats")
    @Operation(summary = "获取性能统计", description = "获取系统性能统计和响应时间分析")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getPerformanceStats(
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") int timeRange) {
        Result<Map<String, Object>> result = gatewayMonitorService.getPerformanceStats(timeRange);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取用户活跃度统计
     * GET /api/admin/gateway/users/activity
     */
    @GetMapping("/users/activity")
    @Operation(summary = "获取用户活跃度统计", description = "获取用户活跃度和访问模式分析")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getUserActivityStats(
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") int timeRange) {
        Result<Map<String, Object>> result = gatewayMonitorService.getUserActivityStats(timeRange);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 检测异常流量
     * GET /api/admin/gateway/traffic/anomalies
     */
    @GetMapping("/traffic/anomalies")
    @Operation(summary = "检测异常流量", description = "检测指定IP或用户的异常流量模式")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "检测完成"),
        @ApiResponse(responseCode = "400", description = "参数错误"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> detectAbnormalTraffic(
            @Parameter(description = "客户端IP") @RequestParam String clientIp,
            @Parameter(description = "用户ID") @RequestParam(required = false) Long userId) {
        Result<Map<String, Object>> result = gatewayMonitorService.detectAbnormalTraffic(clientIp, userId);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取系统健康状态
     * GET /api/admin/gateway/health
     */
    @GetMapping("/health")
    @Operation(summary = "获取系统健康状态", description = "获取网关系统的健康状态和运行指标")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "503", description = "服务不可用")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getSystemHealth() {
        Result<Map<String, Object>> result = gatewayMonitorService.getSystemHealth();
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取热点API排行
     * GET /api/admin/gateway/apis/hot
     */
    @GetMapping("/apis/hot")
    @Operation(summary = "获取热点API排行", description = "获取访问量最高的API接口排行")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getHotApiRanking(
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") int timeRange,
            @Parameter(description = "返回条数限制") @RequestParam(defaultValue = "10") int limit) {
        Result<Map<String, Object>> result = gatewayMonitorService.getHotApiRanking(timeRange, limit);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取慢查询统计
     * GET /api/admin/gateway/performance/slow-queries
     */
    @GetMapping("/performance/slow-queries")
    @Operation(summary = "获取慢查询统计", description = "获取响应时间超过阈值的慢查询统计")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getSlowQueryStats(
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") int timeRange,
            @Parameter(description = "慢查询阈值（毫秒）") @RequestParam(defaultValue = "1000") long threshold) {
        Result<Map<String, Object>> result = gatewayMonitorService.getSlowQueryStats(timeRange, threshold);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取地理位置统计
     * GET /api/admin/gateway/geographic/stats
     */
    @GetMapping("/geographic/stats")
    @Operation(summary = "获取地理位置统计", description = "获取访问来源的地理位置分布统计")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getGeographicStats(
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") int timeRange) {
        Result<Map<String, Object>> result = gatewayMonitorService.getGeographicStats(timeRange);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 清理过期的监控数据
     * DELETE /api/admin/gateway/data
     */
    @DeleteMapping("/data")
    @Operation(summary = "清理过期监控数据", description = "清理超过保留期限的监控数据")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "清理成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> cleanExpiredData(
            @Parameter(description = "保留天数") @RequestParam(defaultValue = "30") int retentionDays) {
        Result<Map<String, Object>> result = gatewayMonitorService.cleanExpiredData(retentionDays);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取综合监控仪表板数据
     * GET /api/admin/gateway/dashboard
     */
    @GetMapping("/dashboard")
    @Operation(summary = "获取监控仪表板数据", description = "获取网关监控仪表板的综合数据")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "500", description = "系统错误")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getDashboardData(
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") int timeRange) {

        try {
            Map<String, Object> dashboard = new java.util.HashMap<>();

            // 获取各项统计数据
            Result<Map<String, Object>> trafficStats = gatewayMonitorService.getRealTimeTrafficStats(timeRange);
            Result<Map<String, Object>> apiStats = gatewayMonitorService.getApiCallStats(timeRange, 10);
            Result<Map<String, Object>> errorStats = gatewayMonitorService.getErrorStats(timeRange);
            Result<Map<String, Object>> performanceStats = gatewayMonitorService.getPerformanceStats(timeRange);
            Result<Map<String, Object>> userStats = gatewayMonitorService.getUserActivityStats(timeRange);
            Result<Map<String, Object>> healthStats = gatewayMonitorService.getSystemHealth();

            // 组装仪表板数据
            if (trafficStats.isSuccess()) {
                dashboard.put("traffic", trafficStats.getData());
            }
            if (apiStats.isSuccess()) {
                dashboard.put("apiStats", apiStats.getData());
            }
            if (errorStats.isSuccess()) {
                dashboard.put("errorStats", errorStats.getData());
            }
            if (performanceStats.isSuccess()) {
                dashboard.put("performance", performanceStats.getData());
            }
            if (userStats.isSuccess()) {
                dashboard.put("userActivity", userStats.getData());
            }
            if (healthStats.isSuccess()) {
                dashboard.put("systemHealth", healthStats.getData());
            }

            dashboard.put("timeRange", timeRange);
            dashboard.put("lastUpdated", System.currentTimeMillis());

            return ResponseEntity.ok(RestResponse.ok(dashboard));
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(RestResponse.internalServerError(ResultEnum.SYSTEM_ERROR.getCode(), 
                        "获取仪表板数据失败"));
        }
    }
}
