package cn.flying.identity.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.flying.identity.service.GatewayMonitorService;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 网关监控控制器
 * 提供网关流量监控、性能统计等管理接口
 *
 * @author 王贝强
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
     *
     * @param timeRange 时间范围（分钟）
     * @return 流量统计数据
     */
    @GetMapping("/traffic/realtime")
    @Operation(summary = "获取实时流量统计", description = "获取指定时间范围内的实时流量统计数据")
    public Result<Map<String, Object>> getRealTimeTrafficStats(
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") int timeRange) {
        return gatewayMonitorService.getRealTimeTrafficStats(timeRange);
    }

    /**
     * 获取API调用统计
     *
     * @param timeRange 时间范围（分钟）
     * @param limit     返回条数限制
     * @return API调用统计
     */
    @GetMapping("/api/stats")
    @Operation(summary = "获取API调用统计", description = "获取API调用频次统计和排行")
    public Result<Map<String, Object>> getApiCallStats(
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") int timeRange,
            @Parameter(description = "返回条数限制") @RequestParam(defaultValue = "20") int limit) {
        return gatewayMonitorService.getApiCallStats(timeRange, limit);
    }

    /**
     * 获取错误统计
     *
     * @param timeRange 时间范围（分钟）
     * @return 错误统计数据
     */
    @GetMapping("/errors/stats")
    @Operation(summary = "获取错误统计", description = "获取系统错误统计和分析")
    public Result<Map<String, Object>> getErrorStats(
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") int timeRange) {
        return gatewayMonitorService.getErrorStats(timeRange);
    }

    /**
     * 获取性能统计
     *
     * @param timeRange 时间范围（分钟）
     * @return 性能统计数据
     */
    @GetMapping("/performance/stats")
    @Operation(summary = "获取性能统计", description = "获取系统性能统计和响应时间分析")
    public Result<Map<String, Object>> getPerformanceStats(
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") int timeRange) {
        return gatewayMonitorService.getPerformanceStats(timeRange);
    }

    /**
     * 获取用户活跃度统计
     *
     * @param timeRange 时间范围（分钟）
     * @return 用户活跃度统计
     */
    @GetMapping("/users/activity")
    @Operation(summary = "获取用户活跃度统计", description = "获取用户活跃度和访问模式分析")
    public Result<Map<String, Object>> getUserActivityStats(
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") int timeRange) {
        return gatewayMonitorService.getUserActivityStats(timeRange);
    }

    /**
     * 检测异常流量
     *
     * @param clientIp 客户端IP
     * @param userId   用户ID（可选）
     * @return 异常检测结果
     */
    @GetMapping("/traffic/anomaly")
    @Operation(summary = "检测异常流量", description = "检测指定IP或用户的异常流量模式")
    public Result<Map<String, Object>> detectAbnormalTraffic(
            @Parameter(description = "客户端IP") @RequestParam String clientIp,
            @Parameter(description = "用户ID") @RequestParam(required = false) Long userId) {
        return gatewayMonitorService.detectAbnormalTraffic(clientIp, userId);
    }

    /**
     * 获取系统健康状态
     *
     * @return 系统健康状态
     */
    @GetMapping("/health")
    @Operation(summary = "获取系统健康状态", description = "获取网关系统的健康状态和运行指标")
    public Result<Map<String, Object>> getSystemHealth() {
        return gatewayMonitorService.getSystemHealth();
    }

    /**
     * 获取热点API排行
     *
     * @param timeRange 时间范围（分钟）
     * @param limit     返回条数限制
     * @return 热点API排行
     */
    @GetMapping("/api/hot")
    @Operation(summary = "获取热点API排行", description = "获取访问量最高的API接口排行")
    public Result<Map<String, Object>> getHotApiRanking(
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") int timeRange,
            @Parameter(description = "返回条数限制") @RequestParam(defaultValue = "10") int limit) {
        return gatewayMonitorService.getHotApiRanking(timeRange, limit);
    }

    /**
     * 获取慢查询统计
     *
     * @param timeRange 时间范围（分钟）
     * @param threshold 慢查询阈值（毫秒）
     * @return 慢查询统计
     */
    @GetMapping("/performance/slow")
    @Operation(summary = "获取慢查询统计", description = "获取响应时间超过阈值的慢查询统计")
    public Result<Map<String, Object>> getSlowQueryStats(
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") int timeRange,
            @Parameter(description = "慢查询阈值（毫秒）") @RequestParam(defaultValue = "1000") long threshold) {
        return gatewayMonitorService.getSlowQueryStats(timeRange, threshold);
    }

    /**
     * 获取地理位置统计
     *
     * @param timeRange 时间范围（分钟）
     * @return 地理位置统计
     */
    @GetMapping("/geographic/stats")
    @Operation(summary = "获取地理位置统计", description = "获取访问来源的地理位置分布统计")
    public Result<Map<String, Object>> getGeographicStats(
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") int timeRange) {
        return gatewayMonitorService.getGeographicStats(timeRange);
    }

    /**
     * 清理过期的监控数据
     *
     * @param retentionDays 保留天数
     * @return 清理结果
     */
    @DeleteMapping("/data/cleanup")
    @Operation(summary = "清理过期监控数据", description = "清理超过保留期限的监控数据")
    public Result<Map<String, Object>> cleanExpiredData(
            @Parameter(description = "保留天数") @RequestParam(defaultValue = "30") int retentionDays) {
        return gatewayMonitorService.cleanExpiredData(retentionDays);
    }

    /**
     * 获取综合监控仪表板数据
     *
     * @param timeRange 时间范围（分钟）
     * @return 仪表板数据
     */
    @GetMapping("/dashboard")
    @Operation(summary = "获取监控仪表板数据", description = "获取网关监控仪表板的综合数据")
    public Result<Map<String, Object>> getDashboardData(
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
            if (trafficStats.getCode() == ResultEnum.SUCCESS.getCode()) {
                dashboard.put("traffic", trafficStats.getData());
            }
            if (apiStats.getCode() == ResultEnum.SUCCESS.getCode()) {
                dashboard.put("api_stats", apiStats.getData());
            }
            if (errorStats.getCode() == ResultEnum.SUCCESS.getCode()) {
                dashboard.put("error_stats", errorStats.getData());
            }
            if (performanceStats.getCode() == ResultEnum.SUCCESS.getCode()) {
                dashboard.put("performance", performanceStats.getData());
            }
            if (userStats.getCode() == ResultEnum.SUCCESS.getCode()) {
                dashboard.put("user_activity", userStats.getData());
            }
            if (healthStats.getCode() == ResultEnum.SUCCESS.getCode()) {
                dashboard.put("system_health", healthStats.getData());
            }

            dashboard.put("time_range", timeRange);
            dashboard.put("last_updated", System.currentTimeMillis());

            return Result.success(dashboard);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }
}
