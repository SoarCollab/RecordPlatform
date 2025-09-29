package cn.flying.identity.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.flying.identity.service.TrafficMonitorService;
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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 流量监控管理Controller
 * 提供网关流量监控、异常检测和拦截管理功能（管理员专用）
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
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getRealTimeTrafficStats(
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") int timeRangeMinutes) {
        
        Result<Map<String, Object>> result = trafficMonitorService.getRealTimeTrafficStats(timeRangeMinutes);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取异常流量统计
     */
    @GetMapping("/stats/anomalous")
    @Operation(summary = "获取异常流量统计", description = "获取异常流量的统计分析数据")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getAnomalousTrafficStats(
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") int timeRangeMinutes) {
        
        Result<Map<String, Object>> result = trafficMonitorService.getAnomalousTrafficStats(timeRangeMinutes);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取IP流量排行
     */
    @GetMapping("/stats/top-ips")
    @Operation(summary = "获取IP流量排行", description = "获取访问量最高的IP地址排行")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<List<Map<String, Object>>>> getTopTrafficIps(
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") int timeRangeMinutes,
            @Parameter(description = "返回数量限制") @RequestParam(defaultValue = "20") int limit) {
        
        Result<List<Map<String, Object>>> result = trafficMonitorService.getTopTrafficIps(timeRangeMinutes, limit);
        RestResponse<List<Map<String, Object>>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取API访问排行
     */
    @GetMapping("/stats/top-apis")
    @Operation(summary = "获取API访问排行", description = "获取访问量最高的API接口排行")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<List<Map<String, Object>>>> getTopApis(
            @Parameter(description = "时间范围（分钟）") @RequestParam(defaultValue = "60") int timeRangeMinutes,
            @Parameter(description = "返回数量限制") @RequestParam(defaultValue = "20") int limit) {
        
        Result<List<Map<String, Object>>> result = trafficMonitorService.getTopApis(timeRangeMinutes, limit);
        RestResponse<List<Map<String, Object>>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取黑名单列表
     */
    @GetMapping("/blacklist")
    @Operation(summary = "获取黑名单列表", description = "获取当前生效的IP黑名单信息")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<List<Map<String, Object>>>> getBlacklistInfo() {
        Result<List<Map<String, Object>>> result = trafficMonitorService.getBlacklistInfo();
        RestResponse<List<Map<String, Object>>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 添加IP黑名单
     */
    @PostMapping("/blacklist")
    @Operation(summary = "添加IP黑名单", description = "手动将IP地址添加到黑名单")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "添加成功"),
        @ApiResponse(responseCode = "400", description = "参数错误"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Void>> addToBlacklist(
            @Parameter(description = "客户端IP") @RequestParam String clientIp,
            @Parameter(description = "拉黑原因") @RequestParam String reason,
            @Parameter(description = "持续时间（小时）") @RequestParam(defaultValue = "24") int durationHours) {
        
        Result<Void> result = trafficMonitorService.addToBlacklist(clientIp, reason, durationHours);
        RestResponse<Void> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 移除IP黑名单
     */
    @DeleteMapping("/blacklist/{clientIp}")
    @Operation(summary = "移除IP黑名单", description = "从黑名单中移除指定IP地址")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "移除成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "404", description = "IP不在黑名单中")
    })
    public ResponseEntity<RestResponse<Void>> removeFromBlacklist(
            @Parameter(description = "客户端IP") @PathVariable String clientIp) {
        
        Result<Void> result = trafficMonitorService.removeFromBlacklist(clientIp);
        RestResponse<Void> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 检查IP黑名单状态
     */
    @GetMapping("/blacklist/check")
    @Operation(summary = "检查IP黑名单状态", description = "检查指定IP是否在黑名单中")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "检查完成"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Boolean>> isBlacklisted(
            @Parameter(description = "客户端IP") @RequestParam String clientIp) {
        
        Result<Boolean> result = trafficMonitorService.isBlacklisted(clientIp);
        RestResponse<Boolean> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 触发异常检测
     */
    @PostMapping("/detection/trigger")
    @Operation(summary = "触发异常检测", description = "手动触发流量异常检测")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "检测完成"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> triggerAnomalyDetection(
            @Parameter(description = "客户端IP（可选）") @RequestParam(required = false) String clientIp) {
        
        Result<Map<String, Object>> result = trafficMonitorService.triggerAnomalyDetection(clientIp);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 检查拦截状态
     */
    @GetMapping("/block/check")
    @Operation(summary = "检查拦截状态", description = "检查指定IP的流量拦截状态")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "检查完成"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> checkTrafficBlock(
            @Parameter(description = "客户端IP") @RequestParam String clientIp,
            @Parameter(description = "用户ID") @RequestParam(required = false) Long userId,
            @Parameter(description = "请求路径") @RequestParam(required = false) String requestPath,
            @Parameter(description = "用户代理") @RequestParam(required = false) String userAgent) {
        
        Result<Map<String, Object>> result = trafficMonitorService.checkTrafficBlock(clientIp, userId, requestPath, userAgent);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取监控仪表板
     */
    @GetMapping("/dashboard")
    @Operation(summary = "获取监控仪表板", description = "获取流量监控仪表板的综合数据")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "500", description = "系统错误")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getTrafficDashboard() {
        Result<Map<String, Object>> result = trafficMonitorService.getTrafficDashboard();
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取系统健康状态
     */
    @GetMapping("/health")
    @Operation(summary = "获取系统健康状态", description = "获取流量监控系统的健康状态")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "系统正常"),
        @ApiResponse(responseCode = "503", description = "系统异常")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getSystemHealthStatus() {
        Result<Map<String, Object>> result = trafficMonitorService.getSystemHealthStatus();
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 清理过期数据
     */
    @DeleteMapping("/data")
    @Operation(summary = "清理过期数据", description = "清理超过保留期限的流量监控数据")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "清理成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> cleanExpiredData(
            @Parameter(description = "保留天数") @RequestParam(defaultValue = "7") int retentionDays) {
        
        Result<Map<String, Object>> result = trafficMonitorService.cleanExpiredData(retentionDays);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 导出流量数据
     */
    @GetMapping("/data/export")
    @Operation(summary = "导出流量数据", description = "导出指定时间范围的流量监控数据")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "导出成功"),
        @ApiResponse(responseCode = "400", description = "参数错误"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<String>> exportTrafficData(
            @Parameter(description = "开始时间") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @Parameter(description = "客户端IP（可选）") @RequestParam(required = false) String clientIp) {
        
        Result<String> result = trafficMonitorService.exportTrafficData(startTime, endTime, clientIp);
        RestResponse<String> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 更新拦截规则
     */
    @PutMapping("/rules")
    @Operation(summary = "更新拦截规则", description = "动态更新流量拦截规则")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "更新成功"),
        @ApiResponse(responseCode = "400", description = "参数错误"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Void>> updateBlockingRule(
            @Parameter(description = "规则类型") @RequestParam String ruleType,
            @Parameter(description = "规则值") @RequestParam String ruleValue) {
        
        Result<Void> result = trafficMonitorService.updateBlockingRule(ruleType, ruleValue);
        RestResponse<Void> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取流量趋势
     */
    @GetMapping("/stats/trend")
    @Operation(summary = "获取流量趋势", description = "获取流量变化趋势数据")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getTrafficTrend(
            @Parameter(description = "时间范围（小时）") @RequestParam(defaultValue = "24") int timeRangeHours) {
        
        // 转换小时为分钟
        Result<Map<String, Object>> result = trafficMonitorService.getRealTimeTrafficStats(timeRangeHours * 60);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取风险评估报告
     */
    @GetMapping("/risk/assessment")
    @Operation(summary = "获取风险评估报告", description = "获取当前系统的流量风险评估报告")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getRiskAssessmentReport() {
        // 使用异常流量统计作为风险评估
        Result<Map<String, Object>> result = trafficMonitorService.getAnomalousTrafficStats(60);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 批量操作黑名单
     */
    @PostMapping("/blacklist/batch")
    @Operation(summary = "批量操作黑名单", description = "批量添加或移除黑名单IP")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "操作成功"),
        @ApiResponse(responseCode = "400", description = "参数错误"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> batchBlacklistOperation(
            @Parameter(description = "操作类型（add/remove）") @RequestParam String operation,
            @Parameter(description = "IP列表") @RequestBody List<String> ipList,
            @Parameter(description = "操作原因") @RequestParam(required = false) String reason,
            @Parameter(description = "持续时间（小时）") @RequestParam(defaultValue = "24") int durationHours) {
        
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

        Map<String, Object> resultData = Map.of(
                "operation", operation,
                "totalCount", ipList.size(),
                "successCount", successCount,
                "failedCount", failedCount
        );

        Result<Map<String, Object>> result = Result.success(resultData);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
