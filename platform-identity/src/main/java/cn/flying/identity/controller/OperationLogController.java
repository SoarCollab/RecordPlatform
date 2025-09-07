package cn.flying.identity.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.dto.OperationLogEntity;
import cn.flying.identity.service.OperationLogService;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 操作日志控制器
 * 提供操作日志的查询、统计、管理等功能
 * 
 * @author 王贝强
 */
@RestController
@RequestMapping("/api/admin/operation-logs")
@Tag(name = "操作日志管理", description = "操作日志的查询、统计、管理功能（管理员专用）")
@SaCheckRole("admin")
public class OperationLogController {

    @Autowired
    private OperationLogService operationLogService;

    /**
     * 分页查询操作日志
     * 
     * @param page 页码
     * @param size 页大小
     * @param userId 用户ID
     * @param module 模块
     * @param operationType 操作类型
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 分页结果
     */
    @GetMapping("/list")
    @Operation(summary = "分页查询操作日志", description = "分页查询操作日志，支持多种条件筛选")
    public Result<Map<String, Object>> getOperationLogs(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "页大小") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "用户ID") @RequestParam(required = false) Long userId,
            @Parameter(description = "模块") @RequestParam(required = false) String module,
            @Parameter(description = "操作类型") @RequestParam(required = false) String operationType,
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        
        return operationLogService.getOperationLogs(page, size, userId, module, operationType, startTime, endTime);
    }

    /**
     * 获取操作日志统计
     * 
     * @param days 统计天数
     * @return 统计结果
     */
    @GetMapping("/stats")
    @Operation(summary = "获取操作日志统计", description = "获取指定天数内的操作日志统计信息")
    public Result<Map<String, Object>> getOperationLogStats(
            @Parameter(description = "统计天数") @RequestParam(defaultValue = "30") int days) {
        
        return operationLogService.getOperationLogStats(days);
    }

    /**
     * 获取用户操作统计
     * 
     * @param userId 用户ID
     * @param days 统计天数
     * @return 用户操作统计
     */
    @GetMapping("/user-stats/{userId}")
    @Operation(summary = "获取用户操作统计", description = "获取指定用户的操作统计信息")
    public Result<Map<String, Object>> getUserOperationStats(
            @Parameter(description = "用户ID") @PathVariable Long userId,
            @Parameter(description = "统计天数") @RequestParam(defaultValue = "30") int days) {
        
        return operationLogService.getUserOperationStats(userId, days);
    }

    /**
     * 获取当前用户操作统计
     * 
     * @param days 统计天数
     * @return 当前用户操作统计
     */
    @GetMapping("/my-stats")
    @Operation(summary = "获取当前用户操作统计", description = "获取当前登录用户的操作统计信息")
    public Result<Map<String, Object>> getMyOperationStats(
            @Parameter(description = "统计天数") @RequestParam(defaultValue = "30") int days) {
        
        Long userId = StpUtil.getLoginIdAsLong();
        return operationLogService.getUserOperationStats(userId, days);
    }

    /**
     * 获取高风险操作日志
     * 
     * @param days 查询天数
     * @return 高风险操作日志
     */
    @GetMapping("/high-risk")
    @Operation(summary = "获取高风险操作日志", description = "获取指定天数内的高风险操作日志")
    public Result<Map<String, Object>> getHighRiskOperations(
            @Parameter(description = "查询天数") @RequestParam(defaultValue = "7") int days) {
        
        return operationLogService.getHighRiskOperations(days);
    }

    /**
     * 获取操作日志详情
     * 
     * @param logId 日志ID
     * @return 日志详情
     */
    @GetMapping("/{logId}")
    @Operation(summary = "获取操作日志详情", description = "获取指定ID的操作日志详细信息")
    public Result<OperationLogEntity> getOperationLogDetail(
            @Parameter(description = "日志ID") @PathVariable Long logId) {
        
        return operationLogService.getOperationLogDetail(logId);
    }

    /**
     * 导出操作日志
     * 
     * @param userId 用户ID
     * @param module 模块
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 导出文件路径
     */
    @PostMapping("/export")
    @Operation(summary = "导出操作日志", description = "导出符合条件的操作日志到文件")
    public Result<String> exportOperationLogs(
            @Parameter(description = "用户ID") @RequestParam(required = false) Long userId,
            @Parameter(description = "模块") @RequestParam(required = false) String module,
            @Parameter(description = "开始时间") @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        
        return operationLogService.exportOperationLogs(userId, module, startTime, endTime);
    }

    /**
     * 批量删除操作日志
     * 
     * @param logIds 日志ID列表
     * @return 删除结果
     */
    @DeleteMapping("/batch")
    @Operation(summary = "批量删除操作日志", description = "批量删除指定的操作日志")
    public Result<Void> batchDeleteOperationLogs(
            @Parameter(description = "日志ID列表") @RequestBody List<Long> logIds) {
        
        return operationLogService.batchDeleteOperationLogs(logIds);
    }

    /**
     * 清理过期的操作日志
     * 
     * @param retentionDays 保留天数
     * @return 清理结果
     */
    @DeleteMapping("/cleanup")
    @Operation(summary = "清理过期操作日志", description = "清理超过保留期限的操作日志")
    public Result<Map<String, Object>> cleanExpiredLogs(
            @Parameter(description = "保留天数") @RequestParam(defaultValue = "90") int retentionDays) {
        
        return operationLogService.cleanExpiredLogs(retentionDays);
    }

    /**
     * 获取操作日志仪表板数据
     * 
     * @return 仪表板数据
     */
    @GetMapping("/dashboard")
    @Operation(summary = "获取操作日志仪表板", description = "获取操作日志仪表板的综合数据")
    public Result<Map<String, Object>> getOperationLogDashboard() {
        try {
            Map<String, Object> dashboard = new java.util.HashMap<>();
            
            // 获取各项统计数据
            Result<Map<String, Object>> stats30 = operationLogService.getOperationLogStats(30);
            Result<Map<String, Object>> stats7 = operationLogService.getOperationLogStats(7);
            Result<Map<String, Object>> highRisk = operationLogService.getHighRiskOperations(7);
            
            // 组装仪表板数据
            if (stats30.getCode() == 200) {
                dashboard.put("stats_30_days", stats30.getData());
            }
            if (stats7.getCode() == 200) {
                dashboard.put("stats_7_days", stats7.getData());
            }
            if (highRisk.getCode() == 200) {
                dashboard.put("high_risk_operations", highRisk.getData());
            }
            
            dashboard.put("last_updated", System.currentTimeMillis());
            
            return Result.success(dashboard);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }
}
