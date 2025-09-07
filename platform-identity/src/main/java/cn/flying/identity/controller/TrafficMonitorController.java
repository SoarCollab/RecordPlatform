package cn.flying.identity.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.flying.identity.service.TrafficMonitorService;
import cn.flying.platformapi.constant.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 流量监控控制器
 * 提供流量监控数据查询和管理功能
 *
 * @author 王贝强
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/traffic-monitor")
@Tag(name = "流量监控管理", description = "网关流量监控、异常检测和拦截管理（管理员专用）")
@SaCheckRole("admin")
public class TrafficMonitorController {

    @Resource
    private TrafficMonitorService trafficMonitorService;

    /**
     * 获取实时流量统计
     */
    @GetMapping("/stats/realtime")
    @Operation(summary = "获取实时流量统计", description = "获取指定时间范围内的实时流量统计数据")
    public Result<Map<String, Object>> getRealTimeTrafficStats(
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") int timeRangeMinutes) {
        return trafficMonitorService.getRealTimeTrafficStats(timeRangeMinutes);
    }

    /**
     * 获取异常流量统计
     */
    @GetMapping("/stats/anomalous")
    @Operation(summary = "获取异常流量统计", description = "获取异常流量的统计分析数据")
    public Result<Map<String, Object>> getAnomalousTrafficStats(
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") int timeRangeMinutes) {
        return trafficMonitorService.getAnomalousTrafficStats(timeRangeMinutes);
    }

    /**
     * 获取流量排行榜
     */
    @GetMapping("/stats/top-ips")
    @Operation(summary = "获取IP流量排行", description = "获取访问量最高的IP地址排行")
    public Result<List<Map<String, Object>>> getTopTrafficIps(
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") int timeRangeMinutes,
            @Parameter(description = "返回数量限制") @RequestParam(defaultValue = "20") int limit) {
        return trafficMonitorService.getTopTrafficIps(timeRangeMinutes, limit);
    }

    /**
     * 获取API访问排行
     */
    @GetMapping("/stats/top-apis")
    @Operation(summary = "获取API访问排行", description = "获取访问量最高的API接口排行")
    public Result<List<Map<String, Object>>> getTopApis(
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") int timeRangeMinutes,
            @Parameter(description = "返回数量限制") @RequestParam(defaultValue = "20") int limit) {
        return trafficMonitorService.getTopApis(timeRangeMinutes, limit);
    }

    /**
     * 获取黑名单信息
     */
    @GetMapping("/blacklist")
    @Operation(summary = "获取黑名单列表", description = "获取当前生效的IP黑名单信息")
    public Result<List<Map<String, Object>>> getBlacklistInfo() {
        return trafficMonitorService.getBlacklistInfo();
    }

    /**
     * 添加IP到黑名单
     */
    @PostMapping("/blacklist/add")
    @Operation(summary = "添加IP黑名单", description = "手动将IP地址添加到黑名单")
    public Result<Void> addToBlacklist(
            @Parameter(description = "客户端IP") @RequestParam String clientIp,
            @Parameter(description = "拉黑原因") @RequestParam String reason,
            @Parameter(description = "持续时间（小时）") @RequestParam(defaultValue = "24") int durationHours) {
        return trafficMonitorService.addToBlacklist(clientIp, reason, durationHours);
    }

    /**
     * 从黑名单移除IP
     */
    @DeleteMapping("/blacklist/remove")
    @Operation(summary = "移除IP黑名单", description = "从黑名单中移除指定IP地址")
    public Result<Void> removeFromBlacklist(
            @Parameter(description = "客户端IP") @RequestParam String clientIp) {
        return trafficMonitorService.removeFromBlacklist(clientIp);
    }

    /**
     * 检查IP是否在黑名单
     */
    @GetMapping("/blacklist/check")
    @Operation(summary = "检查IP黑名单状态", description = "检查指定IP是否在黑名单中")
    public Result<Boolean> isBlacklisted(
            @Parameter(description = "客户端IP") @RequestParam String clientIp) {
        return trafficMonitorService.isBlacklisted(clientIp);
    }

    /**
     * 手动触发异常检测
     */
    @PostMapping("/detection/trigger")
    @Operation(summary = "触发异常检测", description = "手动触发流量异常检测")
    public Result<Map<String, Object>> triggerAnomalyDetection(
            @Parameter(description = "客户端IP（可选）") @RequestParam(required = false) String clientIp) {
        return trafficMonitorService.triggerAnomalyDetection(clientIp);
    }

    /**
     * 检查流量拦截状态
     */
    @GetMapping("/block/check")
    @Operation(summary = "检查拦截状态", description = "检查指定IP的流量拦截状态")
    public Result<Map<String, Object>> checkTrafficBlock(
            @Parameter(description = "客户端IP") @RequestParam String clientIp,
            @Parameter(description = "用户ID") @RequestParam(required = false) Long userId,
            @Parameter(description = "请求路径") @RequestParam(required = false) String requestPath,
            @Parameter(description = "用户代理") @RequestParam(required = false) String userAgent) {
        return trafficMonitorService.checkTrafficBlock(clientIp, userId, requestPath, userAgent);
    }

    /**
     * 获取流量监控仪表板
     */
    @GetMapping("/dashboard")
    @Operation(summary = "获取监控仪表板", description = "获取流量监控仪表板的综合数据")
    public Result<Map<String, Object>> getTrafficDashboard() {
        return trafficMonitorService.getTrafficDashboard();
    }

    /**
     * 获取系统健康状态
     */
    @GetMapping("/health")
    @Operation(summary = "获取系统健康状态", description = "获取流量监控系统的健康状态")
    public Result<Map<String, Object>> getSystemHealthStatus() {
        return trafficMonitorService.getSystemHealthStatus();
    }

    /**
     * 清理过期数据
     */
    @DeleteMapping("/data/cleanup")
    @Operation(summary = "清理过期数据", description = "清理超过保留期限的流量监控数据")
    public Result<Map<String, Object>> cleanExpiredData(
            @Parameter(description = "保留天数") @RequestParam(defaultValue = "7") int retentionDays) {
        return trafficMonitorService.cleanExpiredData(retentionDays);
    }

    /**
     * 导出流量数据
     */
    @GetMapping("/data/export")
    @Operation(summary = "导出流量数据", description = "导出指定时间范围的流量监控数据")
    public Result<String> exportTrafficData(
            @Parameter(description = "开始时间") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @Parameter(description = "客户端IP（可选）") @RequestParam(required = false) String clientIp) {
        return trafficMonitorService.exportTrafficData(startTime, endTime, clientIp);
    }

    /**
     * 更新拦截规则
     */
    @PutMapping("/rules/update")
    @Operation(summary = "更新拦截规则", description = "动态更新流量拦截规则")
    public Result<Void> updateBlockingRule(
            @Parameter(description = "规则类型") @RequestParam String ruleType,
            @Parameter(description = "规则值") @RequestParam String ruleValue) {
        return trafficMonitorService.updateBlockingRule(ruleType, ruleValue);
    }

    /**
     * 获取流量趋势
     */
    @GetMapping("/stats/trend")
    @Operation(summary = "获取流量趋势", description = "获取流量变化趋势数据")
    public Result<Map<String, Object>> getTrafficTrend(
            @Parameter(description = "时间范围（小时）") @RequestParam(defaultValue = "24") int timeRangeHours) {
        // 调用实时统计接口，转换时间单位
        return trafficMonitorService.getRealTimeTrafficStats(timeRangeHours * 60);
    }

    /**
     * 获取风险评估报告
     */
    @GetMapping("/risk/assessment")
    @Operation(summary = "获取风险评估报告", description = "获取当前系统的流量风险评估报告")
    public Result<Map<String, Object>> getRiskAssessmentReport() {
        // 获取异常流量统计作为风险评估
        return trafficMonitorService.getAnomalousTrafficStats(60);
    }

    /**
     * 批量操作黑名单
     */
    @PostMapping("/blacklist/batch")
    @Operation(summary = "批量操作黑名单", description = "批量添加或移除黑名单IP")
    public Result<Map<String, Object>> batchBlacklistOperation(
            @Parameter(description = "操作类型（add/remove）") @RequestParam String operation,
            @Parameter(description = "IP列表") @RequestBody List<String> ipList,
            @Parameter(description = "操作原因") @RequestParam(required = false) String reason,
            @Parameter(description = "持续时间（小时）") @RequestParam(defaultValue = "24") int durationHours) {
        
        Map<String, Object> result = Map.of(
                "operation", operation,
                "totalCount", ipList.size(),
                "successCount", 0,
                "failedCount", 0
        );

        int successCount = 0;
        int failedCount = 0;

        for (String ip : ipList) {
            try {
                if ("add".equals(operation)) {
                    trafficMonitorService.addToBlacklist(ip, reason != null ? reason : "批量操作", durationHours);
                } else if ("remove".equals(operation)) {
                    trafficMonitorService.removeFromBlacklist(ip);
                }
                successCount++;
            } catch (Exception e) {
                failedCount++;
                log.error("批量操作黑名单失败, IP: {}, 操作: {}", ip, operation, e);
            }
        }

        result = Map.of(
                "operation", operation,
                "totalCount", ipList.size(),
                "successCount", successCount,
                "failedCount", failedCount
        );

        return Result.success(result);
    }
}
