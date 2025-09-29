package cn.flying.identity.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.flying.identity.dto.OperationLog;
import cn.flying.identity.service.OperationLogService;
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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 操作日志管理控制器
 * 提供符合RESTful规范的操作日志查询、统计、管理功能（管理员专用）
 *
 * @author 王贝强
 * @since 2025-01-16
 */
@RestController
@RequestMapping("/api/admin/operation-logs")
@Tag(name = "操作日志管理", description = "操作日志的查询、统计、管理功能（管理员专用）")
@SaCheckRole("admin")
public class OperationLogController {

    @Resource
    private OperationLogService operationLogService;

    /**
     * 分页查询操作日志
     * GET /api/admin/operation-logs
     */
    @GetMapping
    @Operation(summary = "分页查询操作日志", description = "分页查询操作日志，支持多种条件筛选")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限"),
            @ApiResponse(responseCode = "400", description = "参数错误")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getOperationLogs(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "页大小") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "用户ID") @RequestParam(required = false) Long userId,
            @Parameter(description = "模块") @RequestParam(required = false) String module,
            @Parameter(description = "操作类型") @RequestParam(required = false) String operationType,
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {

        Result<Map<String, Object>> result = operationLogService.getOperationLogs(page, size, userId, module, operationType, startTime, endTime);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取操作日志统计
     * GET /api/admin/operation-logs/stats
     */
    @GetMapping("/stats")
    @Operation(summary = "获取操作日志统计", description = "获取指定天数内的操作日志统计信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getOperationLogStats(
            @Parameter(description = "统计天数") @RequestParam(defaultValue = "30") int days) {

        Result<Map<String, Object>> result = operationLogService.getOperationLogStats(days);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取用户操作统计
     * GET /api/admin/operation-logs/users/{userId}/stats
     */
    @GetMapping("/users/{userId}/stats")
    @Operation(summary = "获取用户操作统计", description = "获取指定用户的操作统计信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限"),
            @ApiResponse(responseCode = "404", description = "用户不存在")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getUserOperationStats(
            @Parameter(description = "用户ID") @PathVariable Long userId,
            @Parameter(description = "统计天数") @RequestParam(defaultValue = "30") int days) {

        Result<Map<String, Object>> result = operationLogService.getUserOperationStats(userId, days);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取当前用户操作统计
     * GET /api/admin/operation-logs/me/stats
     */
    @GetMapping("/me/stats")
    @Operation(summary = "获取当前用户操作统计", description = "获取当前登录用户的操作统计信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getMyOperationStats(
            @Parameter(description = "统计天数") @RequestParam(defaultValue = "30") int days) {

        Long userId = cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
        Result<Map<String, Object>> result = operationLogService.getUserOperationStats(userId, days);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取高风险操作日志
     * GET /api/admin/operation-logs/high-risk
     */
    @GetMapping("/high-risk")
    @Operation(summary = "获取高风险操作日志", description = "获取指定天数内的高风险操作日志")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getHighRiskOperations(
            @Parameter(description = "查询天数") @RequestParam(defaultValue = "7") int days) {

        Result<Map<String, Object>> result = operationLogService.getHighRiskOperations(days);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取操作日志详情
     * GET /api/admin/operation-logs/{logId}
     */
    @GetMapping("/{logId}")
    @Operation(summary = "获取操作日志详情", description = "获取指定ID的操作日志详细信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限"),
            @ApiResponse(responseCode = "404", description = "日志不存在")
    })
    public ResponseEntity<RestResponse<OperationLog>> getOperationLogDetail(
            @Parameter(description = "日志ID") @PathVariable Long logId) {

        Result<OperationLog> result = operationLogService.getOperationLogDetail(logId);
        RestResponse<OperationLog> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 导出操作日志
     * POST /api/admin/operation-logs/exports
     */
    @PostMapping("/exports")
    @Operation(summary = "导出操作日志", description = "导出符合条件的操作日志到文件")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "导出成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限"),
            @ApiResponse(responseCode = "400", description = "参数错误")
    })
    public ResponseEntity<RestResponse<String>> exportOperationLogs(
            @Parameter(description = "用户ID") @RequestParam(required = false) Long userId,
            @Parameter(description = "模块") @RequestParam(required = false) String module,
            @Parameter(description = "开始时间") @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {

        Result<String> result = operationLogService.exportOperationLogs(userId, module, startTime, endTime);
        RestResponse<String> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 批量删除操作日志
     * DELETE /api/admin/operation-logs/batch
     */
    @DeleteMapping("/batch")
    @Operation(summary = "批量删除操作日志", description = "批量删除指定的操作日志")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "删除成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限"),
            @ApiResponse(responseCode = "400", description = "参数错误")
    })
    public ResponseEntity<RestResponse<Void>> batchDeleteOperationLogs(
            @Parameter(description = "日志ID列表") @RequestBody List<Long> logIds) {

        Result<Void> result = operationLogService.batchDeleteOperationLogs(logIds);
        RestResponse<Void> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 清理过期操作日志
     * DELETE /api/admin/operation-logs/expired
     */
    @DeleteMapping("/expired")
    @Operation(summary = "清理过期操作日志", description = "清理超过保留期限的操作日志")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "清理成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> cleanExpiredLogs(
            @Parameter(description = "保留天数") @RequestParam(defaultValue = "90") int retentionDays) {

        Result<Map<String, Object>> result = operationLogService.cleanExpiredLogs(retentionDays);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取操作日志仪表板
     * GET /api/admin/operation-logs/dashboard
     */
    @GetMapping("/dashboard")
    @Operation(summary = "获取操作日志仪表板", description = "获取操作日志仪表板的综合数据")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限"),
            @ApiResponse(responseCode = "500", description = "系统错误")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getOperationLogDashboard() {
        try {
            Map<String, Object> dashboard = new java.util.HashMap<>();

            // 获取各项统计数据
            Result<Map<String, Object>> stats30 = operationLogService.getOperationLogStats(30);
            Result<Map<String, Object>> stats7 = operationLogService.getOperationLogStats(7);
            Result<Map<String, Object>> highRisk = operationLogService.getHighRiskOperations(7);

            // 组装仪表板数据
            if (stats30.isSuccess()) {
                dashboard.put("stats30Days", stats30.getData());
            }
            if (stats7.isSuccess()) {
                dashboard.put("stats7Days", stats7.getData());
            }
            if (highRisk.isSuccess()) {
                dashboard.put("highRiskOperations", highRisk.getData());
            }

            dashboard.put("lastUpdated", System.currentTimeMillis());

            return ResponseEntity.ok(RestResponse.ok(dashboard));
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(RestResponse.internalServerError(ResultEnum.SYSTEM_ERROR.getCode(),
                            "获取仪表板数据失败"));
        }
    }
}
