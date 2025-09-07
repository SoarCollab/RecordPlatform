package cn.flying.identity.controller;

import cn.flying.identity.dto.AuditLog;
import cn.flying.identity.service.AuditLogService;
import cn.flying.platformapi.constant.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 操作审计日志控制器
 * 提供审计日志的查询、统计、导出等功能
 * 
 * @author flying
 * @date 2024
 */
@Tag(name = "操作审计日志", description = "操作审计日志管理接口")
@RestController
@RequestMapping("/api/audit/log")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    /**
     * 分页查询审计日志
     * 
     * @param page 页码
     * @param size 页大小
     * @param userId 用户ID
     * @param operationType 操作类型
     * @param module 模块
     * @param riskLevel 风险等级
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 审计日志分页结果
     */
    @Operation(summary = "分页查询审计日志")
    @GetMapping("/page")
    public Result<List<AuditLog>> getAuditLogPage(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "页大小") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "用户ID") @RequestParam(required = false) String userId,
            @Parameter(description = "操作类型") @RequestParam(required = false) String operationType,
            @Parameter(description = "模块") @RequestParam(required = false) String module,
            @Parameter(description = "风险等级") @RequestParam(required = false) String riskLevel,
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        // 根据不同条件查询审计日志
        if (userId != null && !userId.isEmpty()) {
            return auditLogService.getLogsByUserId(Long.valueOf(userId), startTime, endTime, page, size);
        } else if (operationType != null && !operationType.isEmpty()) {
            return auditLogService.getLogsByOperationType(operationType, startTime, endTime, page, size);
        } else if (module != null && !module.isEmpty()) {
            return auditLogService.getLogsByModule(module, startTime, endTime, page, size);
        } else if (riskLevel != null && !riskLevel.isEmpty()) {
            return auditLogService.getLogsByRiskLevel(riskLevel, startTime, endTime, page, size);
        } else {
            // 默认查询失败日志
            return auditLogService.getFailedLogs(startTime, endTime, page, size);
        }
    }

    /**
     * 根据用户ID查询审计日志
     * 
     * @param userId 用户ID
     * @param page 页码
     * @param size 页大小
     * @return 用户审计日志
     */
    @Operation(summary = "根据用户ID查询审计日志")
    @GetMapping("/user/{userId}")
    public Result<List<AuditLog>> getAuditLogsByUserId(
            @Parameter(description = "用户ID") @PathVariable String userId,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "页大小") @RequestParam(defaultValue = "20") int size) {
        return auditLogService.getLogsByUserId(Long.valueOf(userId), null, null, page, size);
    }

    /**
     * 根据操作类型查询审计日志
     * 
     * @param operationType 操作类型
     * @param page 页码
     * @param size 页大小
     * @return 操作类型审计日志
     */
    @Operation(summary = "根据操作类型查询审计日志")
    @GetMapping("/operation/{operationType}")
    public Result<List<AuditLog>> getAuditLogsByOperationType(
            @Parameter(description = "操作类型") @PathVariable String operationType,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "页大小") @RequestParam(defaultValue = "20") int size) {
        return auditLogService.getLogsByOperationType(operationType, null, null, page, size);
    }

    /**
     * 查询失败操作日志
     * 
     * @param page 页码
     * @param size 页大小
     * @return 失败操作日志
     */
    @Operation(summary = "查询失败操作日志")
    @GetMapping("/failed")
    public Result<List<AuditLog>> getFailedOperationLogs(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "页大小") @RequestParam(defaultValue = "20") int size) {
        return auditLogService.getFailedLogs(null, null, page, size);
    }

    /**
     * 查询高风险操作日志
     * 
     * @param page 页码
     * @param size 页大小
     * @return 高风险操作日志
     */
    @Operation(summary = "查询高风险操作日志")
    @GetMapping("/high-risk")
    public Result<List<AuditLog>> getHighRiskOperationLogs(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "页大小") @RequestParam(defaultValue = "20") int size) {
        return auditLogService.getHighRiskLogs(null, null, page, size);
    }

    /**
     * 统计操作类型分布
     * 
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 操作类型统计
     */
    @Operation(summary = "统计操作类型分布")
    @GetMapping("/stats/operation-type")
    public Result<List<Map<String, Object>>> getOperationTypeStats(
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        return auditLogService.getOperationTypeStats(startTime, endTime);
    }

    /**
     * 统计用户操作次数
     * 
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param limit 限制数量
     * @return 用户操作统计
     */
    @Operation(summary = "统计用户操作次数")
    @GetMapping("/stats/user-operation")
    public Result<List<Map<String, Object>>> getUserOperationStats(
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
            @Parameter(description = "限制数量") @RequestParam(defaultValue = "10") int limit) {
        return auditLogService.getUserOperationStats(startTime, endTime, limit);
    }

    /**
     * 统计IP访问次数
     * 
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param limit 限制数量
     * @return IP访问统计
     */
    @Operation(summary = "统计IP访问次数")
    @GetMapping("/stats/ip-access")
    public Result<List<Map<String, Object>>> getIpAccessStats(
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
            @Parameter(description = "限制数量") @RequestParam(defaultValue = "10") int limit) {
        return auditLogService.getIpAccessStats(startTime, endTime, limit);
    }

    /**
     * 统计每日操作数量
     * 
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 每日操作统计
     */
    @Operation(summary = "统计每日操作数量")
    @GetMapping("/stats/daily-operation")
    public Result<List<Map<String, Object>>> getDailyOperationStats(
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDateTime endTime) {
        return auditLogService.getDailyOperationStats(startTime, endTime);
    }

    /**
     * 统计每小时操作数量
     * 
     * @param date 日期
     * @return 每小时操作统计
     */
    @Operation(summary = "统计每小时操作数量")
    @GetMapping("/stats/hourly-operation")
    public Result<List<Map<String, Object>>> getHourlyOperationStats(
            @Parameter(description = "日期") @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDateTime date) {
        return auditLogService.getHourlyOperationStats(date, date.plusDays(1));
    }

    /**
     * 导出审计日志
     * 
     * @param userId 用户ID
     * @param operationType 操作类型
     * @param module 模块
     * @param riskLevel 风险等级
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 导出结果
     */
    @Operation(summary = "导出审计日志")
    @PostMapping("/export")
    public Result<String> exportAuditLogs(
            @Parameter(description = "用户ID") @RequestParam(required = false) String userId,
            @Parameter(description = "操作类型") @RequestParam(required = false) String operationType,
            @Parameter(description = "模块") @RequestParam(required = false) String module,
            @Parameter(description = "风险等级") @RequestParam(required = false) String riskLevel,
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        Long userIdLong = userId != null && !userId.isEmpty() ? Long.valueOf(userId) : null;
        return auditLogService.exportLogs(startTime, endTime, operationType, module, userIdLong);
    }

    /**
     * 清理过期审计日志
     * 
     * @param days 保留天数
     * @return 清理结果
     */
    @Operation(summary = "清理过期审计日志")
    @DeleteMapping("/cleanup")
    public Result<Integer> cleanupExpiredLogs(
            @Parameter(description = "保留天数") @RequestParam(defaultValue = "90") int days) {
        return auditLogService.cleanExpiredLogs(days);
    }

    /**
     * 检测异常操作模式
     * 
     * @param timeWindow 时间窗口（分钟）
     * @return 异常检测结果
     */
    @Operation(summary = "检测异常操作模式")
    @GetMapping("/detect-abnormal")
    public Result<Map<String, Object>> detectAbnormalPatterns(
            @Parameter(description = "时间窗口（分钟）") @RequestParam(defaultValue = "60") int timeWindow) {
        return auditLogService.detectAbnormalPatterns(null, timeWindow);
    }

    /**
     * 生成审计报告
     * 
     * @param reportType 报告类型
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 审计报告
     */
    @Operation(summary = "生成审计报告")
    @PostMapping("/report")
    public Result<Map<String, Object>> generateAuditReport(
            @Parameter(description = "报告类型") @RequestParam String reportType,
            @Parameter(description = "开始时间") @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        return auditLogService.generateAuditReport(startTime, endTime, reportType);
    }

    /**
     * 获取实时监控告警
     * 
     * @return 实时告警信息
     */
    @Operation(summary = "获取实时监控告警")
    @GetMapping("/alerts")
    public Result<List<Map<String, Object>>> getRealtimeAlerts() {
        return auditLogService.realtimeAlert("HIGH", 60);
    }

    /**
     * 查询异常登录记录
     * 
     * @param page 页码
     * @param size 页大小
     * @return 异常登录记录
     */
    @Operation(summary = "查询异常登录记录")
    @GetMapping("/abnormal-login")
    public Result<List<AuditLog>> getAbnormalLoginRecords(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "页大小") @RequestParam(defaultValue = "20") int size) {
        return auditLogService.getAbnormalLogins(null, null, page, size);
    }
}