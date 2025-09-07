package cn.flying.identity.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.dto.TokenMonitor;
import cn.flying.identity.service.TokenMonitorService;
import cn.flying.platformapi.constant.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Token监控控制器
 * 提供Token监控的查询、统计、告警等功能
 */
@Tag(name = "Token监控", description = "Token监控管理接口")
@RestController
@RequestMapping("/api/token/monitor")
@RequiredArgsConstructor
@SaCheckLogin
public class TokenMonitorController {

    private final TokenMonitorService tokenMonitorService;

    /**
     * 记录Token事件
     *
     * @param tokenMonitor Token监控对象
     * @return 操作结果
     */
    @PostMapping("/record")
    @Operation(summary = "记录Token事件", description = "记录Token相关事件信息")
    @SaCheckPermission("token:monitor:record")
    public Result<Void> recordTokenEvent(@Valid @RequestBody TokenMonitor tokenMonitor) {
        return tokenMonitorService.recordTokenEvent(tokenMonitor);
    }

    /**
     * 异步记录Token事件
     *
     * @param tokenMonitor Token监控对象
     * @return 操作结果
     */
    @PostMapping("/record/async")
    @Operation(summary = "异步记录Token事件", description = "异步记录Token相关事件信息")
    @SaCheckPermission("token:monitor:record")
    public Result<Void> recordTokenEventAsync(@Valid @RequestBody TokenMonitor tokenMonitor) {
        tokenMonitorService.recordTokenEventAsync(tokenMonitor);
        return Result.success();
    }

    /**
     * 记录Token创建事件
     *
     * @param tokenId   Token ID
     * @param tokenType Token类型
     * @param userId    用户ID
     * @param clientId  客户端ID
     * @param request   HTTP请求对象
     * @param expiresAt 过期时间
     * @return 操作结果
     */
    @PostMapping("/record/creation")
    @Operation(summary = "记录Token创建事件", description = "记录Token创建相关事件")
    @SaCheckPermission("token:monitor:record")
    public Result<Void> recordTokenCreation(
            @Parameter(description = "Token ID") @RequestParam String tokenId,
            @Parameter(description = "Token类型") @RequestParam String tokenType,
            @Parameter(description = "用户ID") @RequestParam Long userId,
            @Parameter(description = "客户端ID") @RequestParam String clientId,
            @Parameter(description = "过期时间") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime expiresAt,
            HttpServletRequest request) {
        String clientIp = getClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        return tokenMonitorService.recordTokenCreation(tokenId, tokenType, userId, clientId, clientIp, userAgent, expiresAt);
    }

    /**
     * 记录Token使用事件
     *
     * @param tokenId       Token ID
     * @param tokenType     Token类型
     * @param userId        用户ID
     * @param clientId      客户端ID
     * @param requestUrl    请求URL
     * @param requestMethod 请求方法
     * @param request       HTTP请求对象
     * @return 操作结果
     */
    @PostMapping("/record/usage")
    @Operation(summary = "记录Token使用事件", description = "记录Token使用相关事件")
    @SaCheckPermission("token:monitor:record")
    public Result<Void> recordTokenUsage(
            @Parameter(description = "Token ID") @RequestParam String tokenId,
            @Parameter(description = "Token类型") @RequestParam String tokenType,
            @Parameter(description = "用户ID") @RequestParam Long userId,
            @Parameter(description = "客户端ID") @RequestParam String clientId,
            @Parameter(description = "请求URL") @RequestParam String requestUrl,
            @Parameter(description = "请求方法") @RequestParam String requestMethod,
            HttpServletRequest request) {
        String clientIp = getClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        return tokenMonitorService.recordTokenUsage(tokenId, tokenType, userId, clientId, clientIp, userAgent, requestUrl, requestMethod);
    }

    /**
     * 记录Token刷新事件
     *
     * @param oldTokenId 旧Token ID
     * @param newTokenId 新Token ID
     * @param tokenType  Token类型
     * @param userId     用户ID
     * @param clientId   客户端ID
     * @param request    HTTP请求对象
     * @return 操作结果
     */
    @PostMapping("/record/refresh")
    @Operation(summary = "记录Token刷新事件", description = "记录Token刷新相关事件")
    @SaCheckPermission("token:monitor:record")
    public Result<Void> recordTokenRefresh(
            @Parameter(description = "旧Token ID") @RequestParam String oldTokenId,
            @Parameter(description = "新Token ID") @RequestParam String newTokenId,
            @Parameter(description = "Token类型") @RequestParam String tokenType,
            @Parameter(description = "用户ID") @RequestParam Long userId,
            @Parameter(description = "客户端ID") @RequestParam String clientId,
            HttpServletRequest request) {
        String clientIp = getClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        return tokenMonitorService.recordTokenRefresh(oldTokenId, newTokenId, tokenType, userId, clientId, clientIp, userAgent);
    }

    /**
     * 记录Token撤销事件
     *
     * @param tokenId   Token ID
     * @param tokenType Token类型
     * @param userId    用户ID
     * @param clientId  客户端ID
     * @param reason    撤销原因
     * @param request   HTTP请求对象
     * @return 操作结果
     */
    @PostMapping("/record/revocation")
    @Operation(summary = "记录Token撤销事件", description = "记录Token撤销相关事件")
    @SaCheckPermission("token:monitor:record")
    public Result<Void> recordTokenRevocation(
            @Parameter(description = "Token ID") @RequestParam String tokenId,
            @Parameter(description = "Token类型") @RequestParam String tokenType,
            @Parameter(description = "用户ID") @RequestParam Long userId,
            @Parameter(description = "客户端ID") @RequestParam String clientId,
            @Parameter(description = "撤销原因") @RequestParam String reason,
            HttpServletRequest request) {
        String clientIp = getClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        return tokenMonitorService.recordTokenRevocation(tokenId, tokenType, userId, clientId, clientIp, userAgent, reason);
    }

    /**
     * 记录Token过期事件
     *
     * @param tokenId   Token ID
     * @param tokenType Token类型
     * @param userId    用户ID
     * @param clientId  客户端ID
     * @return 操作结果
     */
    @PostMapping("/record/expiration")
    @Operation(summary = "记录Token过期事件", description = "记录Token过期相关事件")
    @SaCheckPermission("token:monitor:record")
    public Result<Void> recordTokenExpiration(
            @Parameter(description = "Token ID") @RequestParam String tokenId,
            @Parameter(description = "Token类型") @RequestParam String tokenType,
            @Parameter(description = "用户ID") @RequestParam Long userId,
            @Parameter(description = "客户端ID") @RequestParam String clientId) {
        return tokenMonitorService.recordTokenExpiration(tokenId, tokenType, userId, clientId);
    }

    /**
     * 记录Token异常事件
     *
     * @param tokenId      Token ID
     * @param tokenType    Token类型
     * @param userId       用户ID
     * @param clientId     客户端ID
     * @param abnormalType 异常类型
     * @param description  异常描述
     * @param riskScore    风险评分
     * @param request      HTTP请求对象
     * @return 操作结果
     */
    @PostMapping("/record/abnormal")
    @Operation(summary = "记录Token异常事件", description = "记录Token异常相关事件")
    @SaCheckPermission("token:monitor:record")
    public Result<Void> recordTokenAbnormal(
            @Parameter(description = "Token ID") @RequestParam String tokenId,
            @Parameter(description = "Token类型") @RequestParam String tokenType,
            @Parameter(description = "用户ID") @RequestParam Long userId,
            @Parameter(description = "客户端ID") @RequestParam String clientId,
            @Parameter(description = "异常类型") @RequestParam String abnormalType,
            @Parameter(description = "异常描述") @RequestParam String description,
            @Parameter(description = "风险评分") @RequestParam Integer riskScore,
            HttpServletRequest request) {
        String clientIp = getClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        return tokenMonitorService.recordTokenAbnormal(tokenId, tokenType, userId, clientId, clientIp, userAgent, abnormalType, description, riskScore);
    }

    // ==================== 查询接口 ====================

    /**
     * 根据Token ID查询监控记录
     *
     * @param tokenId   Token ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param pageNum   页码
     * @param pageSize  页大小
     * @return 监控记录列表
     */
    @GetMapping("/query/token/{tokenId}")
    @Operation(summary = "根据Token ID查询监控记录", description = "查询指定Token的所有监控记录")
    @SaCheckPermission("token:monitor:query")
    public Result<List<TokenMonitor>> getMonitorsByTokenId(
            @Parameter(description = "Token ID") @PathVariable String tokenId,
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int pageNum,
            @Parameter(description = "页大小") @RequestParam(defaultValue = "10") int pageSize) {
        return tokenMonitorService.getMonitorsByTokenId(tokenId, startTime, endTime, pageNum, pageSize);
    }

    /**
     * 根据用户ID查询监控记录
     *
     * @param userId    用户ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param pageNum   页码
     * @param pageSize  页大小
     * @return 监控记录列表
     */
    @GetMapping("/query/user/{userId}")
    @Operation(summary = "根据用户ID查询监控记录", description = "查询指定用户的监控记录")
    @SaCheckPermission("token:monitor:query")
    public Result<List<TokenMonitor>> getMonitorsByUserId(
            @Parameter(description = "用户ID") @PathVariable Long userId,
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int pageNum,
            @Parameter(description = "页大小") @RequestParam(defaultValue = "10") int pageSize) {
        return tokenMonitorService.getMonitorsByUserId(userId, startTime, endTime, pageNum, pageSize);
    }

    /**
     * 根据客户端ID查询监控记录
     *
     * @param clientId  客户端ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param pageNum   页码
     * @param pageSize  页大小
     * @return 监控记录列表
     */
    @GetMapping("/query/client/{clientId}")
    @Operation(summary = "根据客户端ID查询监控记录", description = "查询指定客户端的监控记录")
    @SaCheckPermission("token:monitor:query")
    public Result<List<TokenMonitor>> getMonitorsByClientId(
            @Parameter(description = "客户端ID") @PathVariable String clientId,
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int pageNum,
            @Parameter(description = "页大小") @RequestParam(defaultValue = "10") int pageSize) {
        return tokenMonitorService.getMonitorsByClientId(clientId, startTime, endTime, pageNum, pageSize);
    }

    /**
     * 根据事件类型查询监控记录
     *
     * @param eventType 事件类型
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param pageNum   页码
     * @param pageSize  页大小
     * @return 监控记录列表
     */
    @GetMapping("/query/event/{eventType}")
    @Operation(summary = "根据事件类型查询监控记录", description = "查询指定事件类型的监控记录")
    @SaCheckPermission("token:monitor:query")
    public Result<List<TokenMonitor>> getMonitorsByEventType(
            @Parameter(description = "事件类型") @PathVariable String eventType,
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int pageNum,
            @Parameter(description = "页大小") @RequestParam(defaultValue = "10") int pageSize) {
        return tokenMonitorService.getMonitorsByEventType(eventType, startTime, endTime, pageNum, pageSize);
    }

    /**
     * 根据异常类型查询监控记录
     *
     * @param abnormalType 异常类型
     * @param startTime    开始时间
     * @param endTime      结束时间
     * @param pageNum      页码
     * @param pageSize     页大小
     * @return 监控记录列表
     */
    @GetMapping("/query/abnormal/{abnormalType}")
    @Operation(summary = "根据异常类型查询监控记录", description = "查询指定异常类型的监控记录")
    @SaCheckPermission("token:monitor:query")
    public Result<List<TokenMonitor>> getMonitorsByAbnormalType(
            @Parameter(description = "异常类型") @PathVariable String abnormalType,
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int pageNum,
            @Parameter(description = "页大小") @RequestParam(defaultValue = "10") int pageSize) {
        return tokenMonitorService.getMonitorsByAbnormalType(abnormalType, startTime, endTime, pageNum, pageSize);
    }

    /**
     * 根据风险评分查询监控记录
     *
     * @param minScore  最小风险评分
     * @param maxScore  最大风险评分
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param pageNum   页码
     * @param pageSize  页大小
     * @return 监控记录列表
     */
    @GetMapping("/query/risk")
    @Operation(summary = "根据风险评分查询监控记录", description = "查询指定风险评分范围的监控记录")
    @SaCheckPermission("token:monitor:query")
    public Result<List<TokenMonitor>> getMonitorsByRiskScore(
            @Parameter(description = "最小风险评分") @RequestParam Integer minScore,
            @Parameter(description = "最大风险评分") @RequestParam Integer maxScore,
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int pageNum,
            @Parameter(description = "页大小") @RequestParam(defaultValue = "10") int pageSize) {
        return tokenMonitorService.getMonitorsByRiskScore(minScore, maxScore, startTime, endTime, pageNum, pageSize);
    }

    /**
     * 获取异常事件
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param pageNum   页码
     * @param pageSize  页大小
     * @return 异常事件列表
     */
    @GetMapping("/query/abnormal-events")
    @Operation(summary = "获取异常事件", description = "查询异常事件记录")
    @SaCheckPermission("token:monitor:query")
    public Result<List<TokenMonitor>> getAbnormalEvents(
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int pageNum,
            @Parameter(description = "页大小") @RequestParam(defaultValue = "10") int pageSize) {
        return tokenMonitorService.getAbnormalEvents(startTime, endTime, pageNum, pageSize);
    }

    /**
     * 获取高风险事件
     *
     * @param minRiskScore 最小风险评分
     * @param startTime    开始时间
     * @param endTime      结束时间
     * @param pageNum      页码
     * @param pageSize     页大小
     * @return 高风险事件列表
     */
    @GetMapping("/query/high-risk")
    @Operation(summary = "获取高风险事件", description = "查询高风险事件记录")
    @SaCheckPermission("token:monitor:query")
    public Result<List<TokenMonitor>> getHighRiskEvents(
            @Parameter(description = "最小风险评分") @RequestParam Integer minRiskScore,
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int pageNum,
            @Parameter(description = "页大小") @RequestParam(defaultValue = "10") int pageSize) {
        return tokenMonitorService.getHighRiskEvents(minRiskScore, startTime, endTime, pageNum, pageSize);
    }

    /**
     * 获取未处理的异常事件
     *
     * @param pageNum  页码
     * @param pageSize 页大小
     * @return 未处理异常事件列表
     */
    @GetMapping("/query/unhandled")
    @Operation(summary = "获取未处理的异常事件", description = "查询未处理的异常事件记录")
    @SaCheckPermission("token:monitor:query")
    public Result<List<TokenMonitor>> getUnhandledAbnormalEvents(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int pageNum,
            @Parameter(description = "页大小") @RequestParam(defaultValue = "10") int pageSize) {
        return tokenMonitorService.getUnhandledAbnormalEvents(pageNum, pageSize);
    }

    /**
     * 获取可疑活动
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param pageNum   页码
     * @param pageSize  页大小
     * @return 可疑活动列表
     */
    @GetMapping("/query/suspicious")
    @Operation(summary = "获取可疑活动", description = "查询可疑活动记录")
    @SaCheckPermission("token:monitor:query")
    public Result<List<TokenMonitor>> getSuspiciousActivities(
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int pageNum,
            @Parameter(description = "页大小") @RequestParam(defaultValue = "10") int pageSize) {
        return tokenMonitorService.getSuspiciousActivities(startTime, endTime, pageNum, pageSize);
    }

    /**
     * 获取Token生命周期
     *
     * @param tokenId Token ID
     * @return Token生命周期记录
     */
    @GetMapping("/query/lifecycle/{tokenId}")
    @Operation(summary = "获取Token生命周期", description = "查询Token的完整生命周期记录")
    @SaCheckPermission("token:monitor:query")
    public Result<List<TokenMonitor>> getTokenLifecycle(
            @Parameter(description = "Token ID") @PathVariable String tokenId) {
        return tokenMonitorService.getTokenLifecycle(tokenId);
    }

    // ==================== 统计相关接口 ====================

    /**
     * 获取事件类型统计
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 事件类型统计数据
     */
    @GetMapping("/stats/event-types")
    @Operation(summary = "获取事件类型统计", description = "统计各种事件类型的数量")
    @SaCheckPermission("token:monitor:stats")
    public Result<List<Map<String, Object>>> getEventTypeStats(
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return tokenMonitorService.getEventTypeStats(startTime, endTime);
    }

    /**
     * 获取Token类型统计
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return Token类型统计数据
     */
    @GetMapping("/stats/token-types")
    @Operation(summary = "获取Token类型统计", description = "统计各种Token类型的数量")
    @SaCheckPermission("token:monitor:stats")
    public Result<List<Map<String, Object>>> getTokenTypeStats(
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return tokenMonitorService.getTokenTypeStats(startTime, endTime);
    }

    /**
     * 获取异常类型统计
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 异常类型统计数据
     */
    @GetMapping("/stats/abnormal-types")
    @Operation(summary = "获取异常类型统计", description = "统计各种异常类型的数量")
    @SaCheckPermission("token:monitor:stats")
    public Result<List<Map<String, Object>>> getAbnormalTypeStats(
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return tokenMonitorService.getAbnormalTypeStats(startTime, endTime);
    }

    /**
     * 获取用户Token统计
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param limit     返回数量限制
     * @return 用户Token统计数据
     */
    @GetMapping("/stats/user-tokens")
    @Operation(summary = "获取用户Token统计", description = "统计用户的Token使用情况")
    @SaCheckPermission("token:monitor:stats")
    public Result<List<Map<String, Object>>> getUserTokenStats(
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @Parameter(description = "返回数量限制") @RequestParam(defaultValue = "100") Integer limit) {
        return tokenMonitorService.getUserTokenStats(startTime, endTime, limit);
    }

    /**
     * 获取客户端Token统计
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param limit     返回数量限制
     * @return 客户端Token统计数据
     */
    @GetMapping("/stats/client-tokens")
    @Operation(summary = "获取客户端Token统计", description = "统计客户端的Token使用情况")
    @SaCheckPermission("token:monitor:stats")
    public Result<List<Map<String, Object>>> getClientTokenStats(
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @Parameter(description = "返回数量限制") @RequestParam(defaultValue = "100") Integer limit) {
        return tokenMonitorService.getClientTokenStats(startTime, endTime, limit);
    }

    /**
     * 获取IP访问统计
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param limit     返回数量限制
     * @return IP访问统计数据
     */
    @GetMapping("/stats/ip-access")
    @Operation(summary = "获取IP访问统计", description = "统计IP地址的访问情况")
    @SaCheckPermission("token:monitor:stats")
    public Result<List<Map<String, Object>>> getIpAccessStats(
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @Parameter(description = "返回数量限制") @RequestParam(defaultValue = "100") int limit) {
        return tokenMonitorService.getIpAccessStats(startTime, endTime, limit);
    }

    /**
     * 获取每日事件统计
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 每日事件统计数据
     */
    @GetMapping("/stats/daily-events")
    @Operation(summary = "获取每日事件统计", description = "统计每日的事件数量")
    @SaCheckPermission("token:monitor:stats")
    public Result<List<Map<String, Object>>> getDailyEventStats(
            @Parameter(description = "开始时间") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return tokenMonitorService.getDailyEventStats(startTime, endTime);
    }

    /**
     * 获取每小时事件统计
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 每小时事件统计数据
     */
    @GetMapping("/stats/hourly-events")
    @Operation(summary = "获取每小时事件统计", description = "统计指定时间范围内每小时的事件数量")
    @SaCheckPermission("token:monitor:stats")
    public Result<List<Map<String, Object>>> getHourlyEventStats(
            @Parameter(description = "开始时间") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return tokenMonitorService.getHourlyEventStats(startTime, endTime);
    }

    // ==================== 风险评估和异常检测 ====================

    /**
     * Token风险评估
     *
     * @param tokenId    Token ID
     * @param userId     用户ID
     * @param userAgent  用户代理
     * @param requestUrl 请求URL
     * @param request    HTTP请求对象
     * @return 风险评分
     */
    @PostMapping("/risk/assess")
    @Operation(summary = "Token风险评估", description = "评估Token的使用风险")
    @SaCheckPermission("token:monitor:risk")
    public Result<Integer> assessTokenRisk(
            @Parameter(description = "Token ID") @RequestParam String tokenId,
            @Parameter(description = "用户ID") @RequestParam Long userId,
            @Parameter(description = "用户代理") @RequestParam(required = false) String userAgent,
            @Parameter(description = "请求URL") @RequestParam(required = false) String requestUrl,
            HttpServletRequest request) {
        String clientIp = getClientIp(request);
        return tokenMonitorService.assessTokenRisk(tokenId, userId, clientIp, userAgent, requestUrl);
    }

    /**
     * 检测Token异常使用模式
     *
     * @param tokenId    Token ID
     * @param timeWindow 时间窗口（分钟）
     * @return 异常检测结果
     */
    @GetMapping("/abnormal/detect/{tokenId}")
    @Operation(summary = "检测Token异常使用", description = "检测Token的异常使用模式")
    @SaCheckPermission("token:monitor:detect")
    public Result<Map<String, Object>> detectAbnormalUsage(
            @Parameter(description = "Token ID") @PathVariable String tokenId,
            @Parameter(description = "时间窗口（分钟）") @RequestParam(defaultValue = "60") int timeWindow) {
        return tokenMonitorService.detectAbnormalUsage(tokenId, timeWindow);
    }

    // ==================== 事件处理 ====================

    /**
     * 处理异常事件
     *
     * @param eventId      事件ID
     * @param handleResult 处理结果
     * @param handleRemark 处理备注
     * @return 操作结果
     */
    @PostMapping("/abnormal/handle/{eventId}")
    @Operation(summary = "处理异常事件", description = "处理Token异常事件")
    @SaCheckPermission("token:monitor:handle")
    public Result<Void> handleAbnormalEvent(
            @Parameter(description = "事件ID") @PathVariable Long eventId,
            @Parameter(description = "处理结果") @RequestParam String handleResult,
            @Parameter(description = "处理备注") @RequestParam(required = false) String handleRemark) {
        Long handlerId = StpUtil.getLoginIdAsLong();
        return tokenMonitorService.handleAbnormalEvent(eventId, handlerId, handleResult, handleRemark);
    }

    // ==================== 报告和告警 ====================

    /**
     * 生成Token监控报告
     *
     * @param startTime  开始时间
     * @param endTime    结束时间
     * @param reportType 报告类型
     * @return 报告内容
     */
    @GetMapping("/report/generate")
    @Operation(summary = "生成监控报告", description = "生成Token监控报告")
    @SaCheckPermission("token:monitor:report")
    public Result<Map<String, Object>> generateMonitorReport(
            @Parameter(description = "开始时间") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @Parameter(description = "报告类型") @RequestParam String reportType) {
        return tokenMonitorService.generateMonitorReport(startTime, endTime, reportType);
    }

    /**
     * 实时Token告警
     *
     * @param riskThreshold 风险阈值
     * @param timeWindow    时间窗口（分钟）
     * @return 告警信息
     */
    @GetMapping("/alert/realtime")
    @Operation(summary = "实时Token告警", description = "获取实时Token告警信息")
    @SaCheckPermission("token:monitor:alert")
    public Result<List<Map<String, Object>>> realtimeTokenAlert(
            @Parameter(description = "风险阈值") @RequestParam(defaultValue = "80") Integer riskThreshold,
            @Parameter(description = "时间窗口（分钟）") @RequestParam(defaultValue = "30") int timeWindow) {
        return tokenMonitorService.realtimeTokenAlert(riskThreshold, timeWindow);
    }

    // ==================== 数据管理 ====================

    /**
     * 清理过期的监控记录
     *
     * @param retentionDays 保留天数
     * @return 清理结果
     */
    @DeleteMapping("/data/clean")
    @Operation(summary = "清理过期记录", description = "清理过期的Token监控记录")
    @SaCheckPermission("token:monitor:clean")
    public Result<Integer> cleanExpiredRecords(
            @Parameter(description = "保留天数") @RequestParam(defaultValue = "90") int retentionDays) {
        return tokenMonitorService.cleanExpiredRecords(retentionDays);
    }

    /**
     * 导出Token监控数据
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param eventType 事件类型
     * @param tokenType Token类型
     * @param userId    用户ID
     * @return 导出文件路径
     */
    @GetMapping("/data/export")
    @Operation(summary = "导出监控数据", description = "导出Token监控数据")
    @SaCheckPermission("token:monitor:export")
    public Result<String> exportMonitorData(
            @Parameter(description = "开始时间") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @Parameter(description = "事件类型") @RequestParam(required = false) String eventType,
            @Parameter(description = "Token类型") @RequestParam(required = false) String tokenType,
            @Parameter(description = "用户ID") @RequestParam(required = false) Long userId) {
        return tokenMonitorService.exportMonitorData(startTime, endTime, eventType, tokenType, userId);
    }

    /**
     * 分页查询Token监控记录
     *
     * @param page      页码
     * @param size      页大小
     * @param tokenId   Token ID
     * @param userId    用户ID
     * @param clientId  客户端ID
     * @param eventType 事件类型
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 分页查询结果
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询监控记录", description = "分页查询Token监控记录")
    @SaCheckPermission("token:monitor:query")
    public Result<Map<String, Object>> getTokenMonitorPage(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "页大小") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "Token ID") @RequestParam(required = false) String tokenId,
            @Parameter(description = "用户ID") @RequestParam(required = false) String userId,
            @Parameter(description = "客户端ID") @RequestParam(required = false) String clientId,
            @Parameter(description = "事件类型") @RequestParam(required = false) String eventType,
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return tokenMonitorService.getTokenMonitorPage(page, size, tokenId, userId, clientId, eventType, startTime, endTime);
    }

    /**
     * 获取客户端IP地址
     *
     * @param request HTTP请求对象
     * @return 客户端IP地址
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty() && !"unknown".equalsIgnoreCase(xForwardedFor)) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty() && !"unknown".equalsIgnoreCase(xRealIp)) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }
}