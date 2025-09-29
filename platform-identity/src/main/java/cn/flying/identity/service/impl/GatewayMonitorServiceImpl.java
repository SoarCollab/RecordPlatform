package cn.flying.identity.service.impl;

import cn.flying.identity.service.BaseService;
import cn.flying.identity.service.GatewayMonitorService;
import cn.flying.identity.util.FlowUtils;
import cn.flying.identity.util.ValidationUtils;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 网关监控服务实现类
 * 基于 Redis 实现流量监控、性能统计、异常检测等功能
 * <p>
 * 主要功能：
 * 1. 请求流量统计：记录请求数量、成功率、错误率等指标
 * 2. API调用统计：统计各API的调用频次和热点排行
 * 3. 性能监控：记录响应时间、慢查询统计等性能指标
 * 4. 错误统计：记录错误类型、错误详情等异常信息
 * 5. 用户活跃度：统计独立用户数和活跃度
 * 6. 地理位置统计：基于IP地址的地理分布统计
 * 7. 流量限制：实现IP、用户、API级别的流量控制
 * 8. 异常检测：检测异常流量模式和攻击行为
 * 9. 数据清理：自动清理过期的监控数据
 * <p>
 * 数据存储结构：
 * - 使用Redis Hash存储统计数据，按时间分片（分钟级别）
 * - 使用Redis Set存储独立IP和用户统计
 * - 使用Redis List存储错误详情，支持FIFO队列
 * - 支持配置化的数据保留时间和存储前缀
 *
 * @author 王贝强
 */
@Slf4j
@Service
public class GatewayMonitorServiceImpl extends BaseService implements GatewayMonitorService {

    // Redis 键前缀配置
    @Value("${gateway.redis.prefix:gateway:}")
    private String redisPrefix;

    // 数据保留时间配置
    @Value("${gateway.data.retention-hours:24}")
    private int dataRetentionHours;

    // 错误详情保留数量配置
    @Value("${gateway.error.max-details:100}")
    private int maxErrorDetails;

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    @Resource
    private FlowUtils flowUtils;

    // 流量限制配置
    @Value("${gateway.rate-limit.requests-per-minute:60}")
    private int requestsPerMinute;

    @Value("${gateway.rate-limit.requests-per-hour:1000}")
    private int requestsPerHour;

    @Value("${gateway.rate-limit.block-time:300}")
    private int blockTime;

    @Override
    public Result<Void> recordRequestStart(String requestId, String method, String uri,
                                           String clientIp, String userAgent, Long userId) {
        try {
            // 参数验证
            Result<?> validation = ValidationUtils.validateAll(
                    requireNonBlank(requestId, "请求ID不能为空"),
                    requireNonBlank(method, "请求方法不能为空"),
                    requireNonBlank(uri, "请求URI不能为空"),
                    requireNonBlank(clientIp, "客户端IP不能为空")
            );
            if (!validation.isSuccess()) {
                return new Result<>(ResultEnum.PARAM_IS_INVALID.getCode(), validation.getMessage(), null);
            }

            String timestamp = formatDateTime(LocalDateTime.now(), "yyyy-MM-dd HH:mm:ss");

            // 记录请求基本信息
            Map<String, String> requestInfo = new HashMap<>();
            requestInfo.put("method", method);
            requestInfo.put("uri", uri);
            requestInfo.put("client_ip", clientIp);
            requestInfo.put("user_agent", getOrElse(userAgent, ""));
            requestInfo.put("user_id", userId != null ? userId.toString() : "");
            requestInfo.put("start_time", timestamp);
            requestInfo.put("status", "processing");

            String requestKey = getRequestPrefix() + requestId;
            redisTemplate.opsForHash().putAll(requestKey, requestInfo);
            redisTemplate.expire(requestKey, 1, TimeUnit.HOURS);

            // 更新实时流量统计
            updateTrafficStats(method, uri, clientIp, userId);

            // 更新API调用统计
            updateApiStats(method, uri);

            // 更新用户活跃度统计
            if (userId != null) {
                updateUserActivityStats(userId);
            }

            return Result.success();
        } catch (Exception e) {
            logError("记录请求开始失败", e);
            return new Result<>(ResultEnum.SYSTEM_ERROR.getCode(), "记录请求开始失败: " + e.getMessage(), null);
        }
    }

    // 动态生成Redis键前缀
    private String getRequestPrefix() {
        return redisPrefix + "request:";
    }

    /**
     * 更新流量统计
     * 记录总请求数、IP统计、用户统计等详细信息
     */
    private void updateTrafficStats(String method, String uri, String clientIp, Long userId) {
        safeExecuteAction(() -> {
            String timeKey = formatDateTime(LocalDateTime.now(), "yyyy-MM-dd HH:mm");
            String trafficKey = getTrafficPrefix() + timeKey;

            // 更新总请求数
            redisTemplate.opsForHash().increment(trafficKey, "total", 1);

            // 更新IP统计
            if (isNotBlank(clientIp)) {
                redisTemplate.opsForHash().increment(trafficKey, "ip:" + clientIp, 1);
                // 记录独立IP数量
                String ipSetKey = trafficKey + ":unique_ips";
                redisTemplate.opsForSet().add(ipSetKey, clientIp);
                redisTemplate.expire(ipSetKey, 24, TimeUnit.HOURS);
            }

            // 更新用户统计
            if (userId != null) {
                redisTemplate.opsForHash().increment(trafficKey, "user:" + userId, 1);
                // 记录独立用户数量
                String userSetKey = trafficKey + ":unique_users";
                redisTemplate.opsForSet().add(userSetKey, userId.toString());
                redisTemplate.expire(userSetKey, 24, TimeUnit.HOURS);
            }

            // 更新方法统计
            redisTemplate.opsForHash().increment(trafficKey, "method:" + method, 1);

            redisTemplate.expire(trafficKey, dataRetentionHours, TimeUnit.HOURS);
        }, "更新流量统计失败");
    }

    /**
     * 更新API统计
     */
    private void updateApiStats(String method, String uri) {
        safeExecuteAction(() -> {
            String timeKey = formatDateTime(LocalDateTime.now(), "yyyy-MM-dd HH:mm");
            String apiStatsKey = getApiStatsPrefix() + timeKey;
            String apiKey = method + " " + uri;

            redisTemplate.opsForHash().increment(apiStatsKey, apiKey, 1);
            redisTemplate.expire(apiStatsKey, dataRetentionHours, TimeUnit.HOURS);
        }, "更新API统计失败");
    }

    /**
     * 更新用户活跃度统计
     */
    private void updateUserActivityStats(Long userId) {
        safeExecuteAction(() -> {
            String timeKey = formatDateTime(LocalDateTime.now(), "yyyy-MM-dd HH:mm");
            String userActivityKey = getUserActivityPrefix() + timeKey;

            redisTemplate.opsForSet().add(userActivityKey, userId.toString());
            redisTemplate.expire(userActivityKey, dataRetentionHours, TimeUnit.HOURS);
        }, "更新用户活跃度统计失败");
    }

    private String getTrafficPrefix() {
        return redisPrefix + "traffic:";
    }

    private String getApiStatsPrefix() {
        return redisPrefix + "api:";
    }

    private String getUserActivityPrefix() {
        return redisPrefix + "user:";
    }

    @Override
    public Result<Void> recordRequestEnd(String requestId, int statusCode, long responseSize,
                                         long executionTime, String errorMessage) {
        try {
            // 参数验证
            Result<?> validation = ValidationUtils.validateAll(
                    requireNonBlank(requestId, "请求ID不能为空"),
                    requireCondition(statusCode, code -> code >= 100 && code < 600, "状态码必须在100-599之间"),
                    requireCondition(responseSize, size -> size >= 0, "响应大小不能为负数"),
                    requireCondition(executionTime, time -> time >= 0, "执行时间不能为负数")
            );
            if (!validation.isSuccess()) {
                return new Result<>(ResultEnum.PARAM_IS_INVALID.getCode(), validation.getMessage(), null);
            }

            String requestKey = getRequestPrefix() + requestId;

            // 更新请求信息
            Map<String, String> updateInfo = new HashMap<>();
            updateInfo.put("status_code", String.valueOf(statusCode));
            updateInfo.put("response_size", String.valueOf(responseSize));
            updateInfo.put("execution_time", String.valueOf(executionTime));
            updateInfo.put("end_time", formatDateTime(LocalDateTime.now(), "yyyy-MM-dd HH:mm:ss"));
            updateInfo.put("status", "completed");

            if (isNotBlank(errorMessage)) {
                updateInfo.put("error_message", errorMessage);
            }

            redisTemplate.opsForHash().putAll(requestKey, updateInfo);

            // 获取请求信息用于统计
            Map<Object, Object> requestInfo = redisTemplate.opsForHash().entries(requestKey);
            String method = (String) requestInfo.get("method");
            String uri = (String) requestInfo.get("uri");

            // 更新性能统计
            updatePerformanceStats(method, uri, executionTime);

            // 更新错误统计或成功统计
            if (statusCode >= 400) {
                updateErrorStats(method, uri, statusCode, errorMessage);
            } else {
                // 更新成功请求统计
                String timeKey = formatDateTime(LocalDateTime.now(), "yyyy-MM-dd HH:mm");
                String trafficKey = getTrafficPrefix() + timeKey;
                redisTemplate.opsForHash().increment(trafficKey, "success", 1);
            }

            return Result.success();
        } catch (Exception e) {
            logError("记录请求结束失败", e);
            return new Result<>(ResultEnum.SYSTEM_ERROR.getCode(), "记录请求结束失败: " + e.getMessage(), null);
        }
    }

    @Override
    public Result<Boolean> checkRateLimit(String clientIp, Long userId, String uri) {
        try {
            // 参数验证
            if (isBlank(clientIp)) {
                logWarn("客户端IP为空，拒绝请求");
                return success(false);
            }

            // 检查IP是否在黑名单中
            String blacklistKey = getRateLimitPrefix() + "blacklist:" + clientIp;
            if (existsCache(blacklistKey)) {
                logWarn("IP {} 在黑名单中，拒绝请求", clientIp);
                return success(false);
            }

            // IP级别限流 - 使用滑动窗口算法
            String ipMinuteKey = getRateLimitPrefix() + "ip:minute:" + clientIp;
            String ipHourKey = getRateLimitPrefix() + "ip:hour:" + clientIp;

            // 检查是否超过每分钟限制
            if (!flowUtils.limitPeriodCountCheck(ipMinuteKey, requestsPerMinute, 60)) {
                logWarn("IP {} 超出每分钟请求限制 {}", clientIp, requestsPerMinute);
                // 触发临时封禁机制
                String tempBanKey = getRateLimitPrefix() + "temp_ban:" + clientIp;
                setCache(tempBanKey, "1", blockTime);
                return success(false);
            }

            // 检查是否超过每小时限制
            if (!flowUtils.limitPeriodCountCheck(ipHourKey, requestsPerHour, 3600)) {
                logWarn("IP {} 超出每小时请求限制 {}", clientIp, requestsPerHour);
                // 触发长期封禁机制
                String longBanKey = getRateLimitPrefix() + "long_ban:" + clientIp;
                setCache(longBanKey, "1", blockTime * 4L);
                return success(false);
            }

            // 检查是否处于临时封禁状态
            String tempBanKey = getRateLimitPrefix() + "temp_ban:" + clientIp;
            if (existsCache(tempBanKey)) {
                logWarn("IP {} 处于临时封禁状态", clientIp);
                return success(false);
            }

            // 用户级别限流
            if (userId != null) {
                String userMinuteKey = getRateLimitPrefix() + "user:minute:" + userId;
                String userHourKey = getRateLimitPrefix() + "user:hour:" + userId;

                if (!flowUtils.limitPeriodCountCheck(userMinuteKey, requestsPerMinute * 2, 60)) {
                    logWarn("用户 {} 超出每分钟请求限制", userId);
                    return success(false);
                }

                if (!flowUtils.limitPeriodCountCheck(userHourKey, requestsPerHour * 2, 3600)) {
                    logWarn("用户 {} 超出每小时请求限制", userId);
                    return success(false);
                }
            }

            // API级别限流
            String apiKey = getRateLimitPrefix() + "api:minute:" + uri;
            if (!flowUtils.limitPeriodCountCheck(apiKey, requestsPerMinute * 10, 60)) {
                logWarn("API {} 超出每分钟请求限制", uri);
                return success(false);
            }

            return success(true);
        } catch (Exception e) {
            logError("检查流量限制失败", e);
            // 异常情况下允许通过，避免影响正常业务
            return success(true);
        }
    }

    private String getRateLimitPrefix() {
        return redisPrefix + "limit:";
    }

    @Override
    public Result<Map<String, Object>> getRealTimeTrafficStats(int timeRange) {
        try {
            Map<String, Object> stats = new HashMap<>();

            // 获取指定时间范围内的流量数据
            String currentMinute = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(LocalDateTime.now());

            long totalRequests = 0;
            long successRequests = 0;
            long errorRequests = 0;
            Set<String> uniqueIps = new HashSet<>();
            Set<String> uniqueUsers = new HashSet<>();
            Map<String, Long> methodStats = new HashMap<>();
            List<Map<String, Object>> timeSeriesData = new ArrayList<>();

            for (int i = 0; i < timeRange; i++) {
                LocalDateTime time = LocalDateTime.now().minusMinutes(i);
                String timeKey = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(time);

                String trafficKey = getTrafficPrefix() + timeKey;
                Map<Object, Object> trafficData = redisTemplate.opsForHash().entries(trafficKey);

                long minuteTotal = 0;
                long minuteError = 0;

                if (!trafficData.isEmpty()) {
                    minuteTotal = Long.parseLong((String) trafficData.getOrDefault("total", "0"));
                    minuteError = Long.parseLong((String) trafficData.getOrDefault("error", "0"));

                    totalRequests += minuteTotal;
                    errorRequests += minuteError;

                    // 统计方法分布
                    for (Map.Entry<Object, Object> entry : trafficData.entrySet()) {
                        String key = (String) entry.getKey();
                        if (key.startsWith("method:")) {
                            String method = key.substring(7);
                            long count = Long.parseLong((String) entry.getValue());
                            methodStats.merge(method, count, Long::sum);
                        }
                    }

                    // 获取独立IP和用户数
                    String ipSetKey = trafficKey + ":unique_ips";
                    String userSetKey = trafficKey + ":unique_users";

                    Set<String> ips = redisTemplate.opsForSet().members(ipSetKey);
                    Set<String> users = redisTemplate.opsForSet().members(userSetKey);

                    if (ips != null) uniqueIps.addAll(ips);
                    if (users != null) uniqueUsers.addAll(users);
                }

                // 构建时间序列数据
                Map<String, Object> timePoint = new HashMap<>();
                timePoint.put("time", timeKey);
                timePoint.put("total", minuteTotal);
                timePoint.put("error", minuteError);
                timePoint.put("success", minuteTotal - minuteError);
                timeSeriesData.add(timePoint);
            }

            successRequests = totalRequests - errorRequests;

            stats.put("total_requests", totalRequests);
            stats.put("success_requests", successRequests);
            stats.put("error_requests", errorRequests);
            stats.put("success_rate", totalRequests > 0 ? (double) successRequests / totalRequests * 100 : 0);
            stats.put("error_rate", totalRequests > 0 ? (double) errorRequests / totalRequests * 100 : 0);
            stats.put("unique_ips", uniqueIps.size());
            stats.put("unique_users", uniqueUsers.size());
            stats.put("method_stats", methodStats);
            stats.put("time_series", timeSeriesData);
            stats.put("time_range", timeRange);
            stats.put("current_time", currentMinute);
            stats.put("avg_requests_per_minute", timeRange > 0 ? (double) totalRequests / timeRange : 0);

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取实时流量统计失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getApiCallStats(int timeRange, int limit) {
        try {
            Map<String, Object> stats = new HashMap<>();
            Map<String, Long> apiCounts = new HashMap<>();

            for (int i = 0; i < timeRange; i++) {
                LocalDateTime time = LocalDateTime.now().minusMinutes(i);
                String timeKey = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(time);

                String apiStatsKey = getApiStatsPrefix() + timeKey;
                Map<Object, Object> apiData = redisTemplate.opsForHash().entries(apiStatsKey);

                for (Map.Entry<Object, Object> entry : apiData.entrySet()) {
                    String api = (String) entry.getKey();
                    Long count = Long.parseLong((String) entry.getValue());
                    apiCounts.merge(api, count, Long::sum);
                }
            }

            // 排序并限制返回数量
            List<Map<String, Object>> topApis = apiCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(limit)
                    .map(entry -> {
                        Map<String, Object> apiInfo = new HashMap<>();
                        apiInfo.put("api", entry.getKey());
                        apiInfo.put("count", entry.getValue());
                        return apiInfo;
                    })
                    .toList();

            stats.put("top_apis", topApis);
            stats.put("total_apis", apiCounts.size());
            stats.put("time_range", timeRange);

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取API调用统计失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getErrorStats(int timeRange) {
        try {
            Map<String, Object> stats = new HashMap<>();
            Map<String, Long> errorCounts = new HashMap<>();
            Map<String, Long> apiErrorCounts = new HashMap<>();
            List<String> recentErrors = new ArrayList<>();
            long totalErrors = 0;

            for (int i = 0; i < timeRange; i++) {
                LocalDateTime time = LocalDateTime.now().minusMinutes(i);
                String timeKey = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(time);

                String errorStatsKey = getErrorStatsPrefix() + timeKey;
                Map<Object, Object> errorData = redisTemplate.opsForHash().entries(errorStatsKey);

                for (Map.Entry<Object, Object> entry : errorData.entrySet()) {
                    String key = (String) entry.getKey();
                    Long count = Long.parseLong((String) entry.getValue());

                    if (key.startsWith("api:")) {
                        // API错误统计
                        String api = key.substring(4);
                        apiErrorCounts.merge(api, count, Long::sum);
                    } else {
                        // 错误类型统计
                        errorCounts.merge(key, count, Long::sum);
                    }
                    totalErrors += count;
                }

                // 获取错误详情
                String errorDetailsKey = errorStatsKey + ":details";
                List<String> details = redisTemplate.opsForList().range(errorDetailsKey, 0, 19); // 获取最近20条
                if (details != null && !details.isEmpty()) {
                    recentErrors.addAll(details);
                }
            }

            // 按错误数量排序API错误统计
            List<Map<String, Object>> topErrorApis = apiErrorCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(10)
                    .map(entry -> {
                        Map<String, Object> apiError = new HashMap<>();
                        apiError.put("api", entry.getKey());
                        apiError.put("error_count", entry.getValue());
                        return apiError;
                    })
                    .toList();

            stats.put("error_types", errorCounts);
            stats.put("api_errors", topErrorApis);
            stats.put("recent_errors", recentErrors.stream().limit(20).toList());
            stats.put("total_errors", totalErrors);
            stats.put("time_range", timeRange);

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取错误统计失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getPerformanceStats(int timeRange) {
        try {
            Map<String, Object> stats = new HashMap<>();
            List<Long> responseTimes = new ArrayList<>();

            for (int i = 0; i < timeRange; i++) {
                LocalDateTime time = LocalDateTime.now().minusMinutes(i);
                String timeKey = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(time);

                String performanceKey = getPerformancePrefix() + timeKey;
                Map<Object, Object> performanceData = redisTemplate.opsForHash().entries(performanceKey);

                for (Map.Entry<Object, Object> entry : performanceData.entrySet()) {
                    String responseTime = (String) entry.getValue();
                    responseTimes.add(Long.parseLong(responseTime));
                }
            }

            if (!responseTimes.isEmpty()) {
                responseTimes.sort(Long::compareTo);

                double avgResponseTime = responseTimes.stream().mapToLong(Long::longValue).average().orElse(0);
                long minResponseTime = responseTimes.getFirst();
                long maxResponseTime = responseTimes.getLast();
                long p95ResponseTime = responseTimes.get((int) (responseTimes.size() * 0.95));
                long p99ResponseTime = responseTimes.get((int) (responseTimes.size() * 0.99));

                stats.put("avg_response_time", avgResponseTime);
                stats.put("min_response_time", minResponseTime);
                stats.put("max_response_time", maxResponseTime);
                stats.put("p95_response_time", p95ResponseTime);
                stats.put("p99_response_time", p99ResponseTime);
                stats.put("total_requests", responseTimes.size());
            } else {
                stats.put("avg_response_time", 0.0);
                stats.put("min_response_time", 0L);
                stats.put("max_response_time", 0L);
                stats.put("p95_response_time", 0L);
                stats.put("p99_response_time", 0L);
                stats.put("total_requests", 0);
            }

            stats.put("time_range", timeRange);

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取性能统计失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getUserActivityStats(int timeRange) {
        try {
            Map<String, Object> stats = new HashMap<>();
            Set<String> activeUsers = new HashSet<>();

            for (int i = 0; i < timeRange; i++) {
                LocalDateTime time = LocalDateTime.now().minusMinutes(i);
                String timeKey = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(time);

                String userActivityKey = getUserActivityPrefix() + timeKey;
                Set<String> users = redisTemplate.opsForSet().members(userActivityKey);
                if (users != null) {
                    activeUsers.addAll(users);
                }
            }

            stats.put("active_users", activeUsers.size());
            stats.put("time_range", timeRange);

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取用户活跃度统计失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> detectAbnormalTraffic(String clientIp, Long userId) {
        try {
            Map<String, Object> result = new HashMap<>();
            boolean isAbnormal = false;
            List<String> reasons = new ArrayList<>();

            // 检查IP请求频率
            String ipKey = getRateLimitPrefix() + "ip:minute:" + clientIp;
            String ipCount = redisTemplate.opsForValue().get(ipKey);
            if (ipCount != null && Integer.parseInt(ipCount) > requestsPerMinute * 0.8) {
                isAbnormal = true;
                reasons.add("IP请求频率过高");
            }

            // 检查用户请求频率
            if (userId != null) {
                String userKey = getRateLimitPrefix() + "user:minute:" + userId;
                String userCount = redisTemplate.opsForValue().get(userKey);
                if (userCount != null && Integer.parseInt(userCount) > requestsPerMinute * 1.6) {
                    isAbnormal = true;
                    reasons.add("用户请求频率过高");
                }
            }

            result.put("is_abnormal", isAbnormal);
            result.put("reasons", reasons);
            result.put("client_ip", clientIp);
            result.put("user_id", userId);

            return Result.success(result);
        } catch (Exception e) {
            log.error("检测异常流量失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getSystemHealth() {
        try {
            Map<String, Object> health = new HashMap<>();

            // 检查Redis连接
            try {
                redisTemplate.opsForValue().get("health_check");
                health.put("redis_status", "healthy");
            } catch (Exception e) {
                health.put("redis_status", "unhealthy");
                health.put("redis_error", e.getMessage());
            }

            // 获取系统负载信息
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;

            health.put("memory_total", totalMemory);
            health.put("memory_used", usedMemory);
            health.put("memory_free", freeMemory);
            health.put("memory_usage_percent", (double) usedMemory / totalMemory * 100);

            // 获取当前时间
            health.put("current_time", LocalDateTime.now().toString());
            health.put("status", "healthy");

            return Result.success(health);
        } catch (Exception e) {
            log.error("获取系统健康状态失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> cleanExpiredData(int retentionDays) {
        try {
            Map<String, Object> result = new HashMap<>();
            int cleanedCount = 0;

            // 清理过期的请求记录
            LocalDateTime cutoffTime = LocalDateTime.now().minusDays(retentionDays);
            String cutoffTimeStr = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(cutoffTime);

            // 清理过期的流量统计数据
            for (int i = retentionDays * 24 * 60; i < retentionDays * 24 * 60 + 1440; i++) {
                LocalDateTime expiredTime = LocalDateTime.now().minusMinutes(i);
                String timeKey = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(expiredTime);

                // 清理各类统计数据
                String[] prefixes = {getTrafficPrefix(), getApiStatsPrefix(), getErrorStatsPrefix(),
                        getPerformancePrefix(), getUserActivityPrefix()};

                for (String prefix : prefixes) {
                    String key = prefix + timeKey;
                    if (redisTemplate.hasKey(key)) {
                        redisTemplate.delete(key);
                        cleanedCount++;
                    }

                    // 清理相关的详细数据
                    String detailKey = key + ":details";
                    if (redisTemplate.hasKey(detailKey)) {
                        redisTemplate.delete(detailKey);
                        cleanedCount++;
                    }

                    String uniqueIpKey = key + ":unique_ips";
                    if (redisTemplate.hasKey(uniqueIpKey)) {
                        redisTemplate.delete(uniqueIpKey);
                        cleanedCount++;
                    }

                    String uniqueUserKey = key + ":unique_users";
                    if (redisTemplate.hasKey(uniqueUserKey)) {
                        redisTemplate.delete(uniqueUserKey);
                        cleanedCount++;
                    }
                }
            }

            // 清理过期的请求详情
            Set<String> requestKeys = redisTemplate.keys(getRequestPrefix() + "*");
            for (String requestKey : requestKeys) {
                Long ttl = redisTemplate.getExpire(requestKey);
                if (ttl <= 0) {
                    redisTemplate.delete(requestKey);
                    cleanedCount++;
                }
            }

            // 清理过期的限流数据
            Set<String> rateLimitKeys = redisTemplate.keys(getRateLimitPrefix() + "*");
            for (String rateLimitKey : rateLimitKeys) {
                Long ttl = redisTemplate.getExpire(rateLimitKey);
                if (ttl <= 0) {
                    redisTemplate.delete(rateLimitKey);
                    cleanedCount++;
                }
            }

            result.put("cleaned_count", cleanedCount);
            result.put("retention_days", retentionDays);
            result.put("cutoff_time", cutoffTimeStr);
            result.put("cleanup_time", LocalDateTime.now().toString());

            log.info("清理过期数据完成，清理了 {} 个键", cleanedCount);
            return Result.success(result);
        } catch (Exception e) {
            log.error("清理过期数据失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getHotApiRanking(int timeRange, int limit) {
        // 复用 getApiCallStats 方法
        return getApiCallStats(timeRange, limit);
    }

    @Override
    public Result<Map<String, Object>> getSlowQueryStats(int timeRange, long threshold) {
        try {
            Map<String, Object> stats = new HashMap<>();
            List<Map<String, Object>> slowQueries = new ArrayList<>();

            for (int i = 0; i < timeRange; i++) {
                LocalDateTime time = LocalDateTime.now().minusMinutes(i);
                String timeKey = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(time);

                String performanceKey = getPerformancePrefix() + timeKey;
                Map<Object, Object> performanceData = redisTemplate.opsForHash().entries(performanceKey);

                for (Map.Entry<Object, Object> entry : performanceData.entrySet()) {
                    String api = (String) entry.getKey();
                    long responseTime = Long.parseLong((String) entry.getValue());

                    if (responseTime > threshold) {
                        Map<String, Object> slowQuery = new HashMap<>();
                        slowQuery.put("api", api);
                        slowQuery.put("response_time", responseTime);
                        slowQuery.put("time", timeKey);
                        slowQueries.add(slowQuery);
                    }
                }
            }

            // 按响应时间排序
            slowQueries.sort((a, b) -> Long.compare((Long) b.get("response_time"), (Long) a.get("response_time")));

            stats.put("slow_queries", slowQueries);
            stats.put("total_count", slowQueries.size());
            stats.put("threshold", threshold);
            stats.put("time_range", timeRange);

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取慢查询统计失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getGeographicStats(int timeRange) {
        try {
            Map<String, Object> stats = new HashMap<>();
            Map<String, Integer> countryStats = new HashMap<>();
            Map<String, Integer> cityStats = new HashMap<>();
            Map<String, Integer> ipStats = new HashMap<>();
            Set<String> allIps = new HashSet<>();

            // 收集所有IP地址
            for (int i = 0; i < timeRange; i++) {
                LocalDateTime time = LocalDateTime.now().minusMinutes(i);
                String timeKey = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(time);
                String trafficKey = getTrafficPrefix() + timeKey;

                Map<Object, Object> trafficData = redisTemplate.opsForHash().entries(trafficKey);
                for (Map.Entry<Object, Object> entry : trafficData.entrySet()) {
                    String key = (String) entry.getKey();
                    if (key.startsWith("ip:")) {
                        String ip = key.substring(3);
                        Integer count = Integer.parseInt((String) entry.getValue());
                        ipStats.merge(ip, count, Integer::sum);
                        allIps.add(ip);
                    }
                }
            }

            // 简化的地理位置解析（基于IP地址模式）
            for (String ip : allIps) {
                Integer count = ipStats.get(ip);

                // 简单的地理位置推断（实际应用中应使用专业的IP地理位置数据库）
                String country = getCountryByIp(ip);
                String city = getCityByIp(ip);

                countryStats.merge(country, count, Integer::sum);
                cityStats.merge(city, count, Integer::sum);
            }

            // 按访问量排序
            List<Map<String, Object>> topCountries = countryStats.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10)
                    .map(entry -> {
                        Map<String, Object> countryInfo = new HashMap<>();
                        countryInfo.put("country", entry.getKey());
                        countryInfo.put("count", entry.getValue());
                        return countryInfo;
                    })
                    .toList();

            List<Map<String, Object>> topCities = cityStats.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10)
                    .map(entry -> {
                        Map<String, Object> cityInfo = new HashMap<>();
                        cityInfo.put("city", entry.getKey());
                        cityInfo.put("count", entry.getValue());
                        return cityInfo;
                    })
                    .toList();

            stats.put("top_countries", topCountries);
            stats.put("top_cities", topCities);
            stats.put("total_ips", allIps.size());
            stats.put("time_range", timeRange);

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取地理位置统计失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 更新性能统计
     */
    private void updatePerformanceStats(String method, String uri, long executionTime) {
        safeExecuteAction(() -> {
            String timeKey = formatDateTime(LocalDateTime.now(), "yyyy-MM-dd HH:mm");
            String performanceKey = getPerformancePrefix() + timeKey;
            String apiKey = method + " " + uri;

            redisTemplate.opsForHash().put(performanceKey, apiKey, String.valueOf(executionTime));
            redisTemplate.expire(performanceKey, dataRetentionHours, TimeUnit.HOURS);
        }, "更新性能统计失败");
    }

    /**
     * 更新错误统计
     * 记录错误类型、API错误统计等详细信息
     */
    private void updateErrorStats(String method, String uri, int statusCode, String errorMessage) {
        safeExecuteAction(() -> {
            String timeKey = formatDateTime(LocalDateTime.now(), "yyyy-MM-dd HH:mm");
            String errorStatsKey = getErrorStatsPrefix() + timeKey;
            String errorType = "HTTP_" + statusCode;
            String apiKey = method + " " + uri;

            // 更新错误类型统计
            redisTemplate.opsForHash().increment(errorStatsKey, errorType, 1);

            // 更新API错误统计
            redisTemplate.opsForHash().increment(errorStatsKey, "api:" + apiKey, 1);

            // 记录具体错误信息（如果有）
            if (isNotBlank(errorMessage)) {
                String errorKey = errorStatsKey + ":details";
                String errorDetail = apiKey + ":" + statusCode + ":" + errorMessage;
                redisTemplate.opsForList().leftPush(errorKey, errorDetail);
                redisTemplate.opsForList().trim(errorKey, 0, maxErrorDetails - 1); // 保留配置数量的错误详情
                redisTemplate.expire(errorKey, dataRetentionHours, TimeUnit.HOURS);
            }

            redisTemplate.expire(errorStatsKey, dataRetentionHours, TimeUnit.HOURS);

            // 更新流量统计中的错误计数
            String trafficKey = getTrafficPrefix() + timeKey;
            redisTemplate.opsForHash().increment(trafficKey, "error", 1);
        }, "更新错误统计失败");
    }

    private String getPerformancePrefix() {
        return redisPrefix + "performance:";
    }

    private String getErrorStatsPrefix() {
        return redisPrefix + "error:";
    }

    /**
     * 根据IP地址推断国家（简化实现）
     * 实际应用中应使用专业的IP地理位置数据库如MaxMind GeoIP
     */
    private String getCountryByIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return "未知";
        }

        // 内网IP判断
        if (isInternalIp(ip)) {
            return "内网";
        }

        // 基于IP段的地理位置推断（简化实现）
        // 实际应用中应使用专业的IP地理位置数据库如MaxMind GeoIP2
        try {
            String[] parts = ip.split("\\.");
            if (parts.length != 4) {
                return "未知";
            }

            int firstOctet = Integer.parseInt(parts[0]);
            int secondOctet = Integer.parseInt(parts[1]);

            // 中国大陆常见IP段
            if ((firstOctet >= 1 && firstOctet <= 2) ||
                    (firstOctet >= 14 && firstOctet <= 15) ||
                    (firstOctet >= 27 && firstOctet <= 28) ||
                    (firstOctet >= 36 && firstOctet <= 37) ||
                    (firstOctet >= 42 && firstOctet <= 43) ||
                    (firstOctet >= 58 && firstOctet <= 63) ||
                    (firstOctet >= 101 && firstOctet <= 106) ||
                    (firstOctet >= 110 && firstOctet <= 125) ||
                    (firstOctet >= 180 && firstOctet <= 183) ||
                    (firstOctet >= 202 && firstOctet <= 203) ||
                    (firstOctet >= 210 && firstOctet <= 222)) {
                return "中国";
            }

            // 美国常见IP段
            if ((firstOctet >= 3 && firstOctet <= 11) ||
                    (firstOctet >= 12 && firstOctet <= 13) ||
                    (firstOctet >= 16 && firstOctet <= 26) ||
                    (firstOctet >= 29 && firstOctet <= 35) ||
                    (firstOctet >= 38 && firstOctet <= 41) ||
                    (firstOctet >= 44 && firstOctet <= 57) ||
                    (firstOctet >= 64 && firstOctet <= 100)) {
                return "美国";
            }

            // 其他常见国家/地区
            if (firstOctet == 128 || firstOctet == 129) {
                return "日本";
            }
            if (firstOctet >= 130 && firstOctet <= 132) {
                return "韩国";
            }
            if (firstOctet >= 133 && firstOctet <= 139) {
                return "新加坡";
            }
            if (firstOctet >= 140 && firstOctet <= 149) {
                return "香港";
            }
            if (firstOctet >= 150 && firstOctet <= 159) {
                return "台湾";
            }

            return "其他";
        } catch (NumberFormatException e) {
            return "未知";
        }
    }

    /**
     * 根据IP地址推断城市（简化实现）
     */
    private String getCityByIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return "未知";
        }

        if (isInternalIp(ip)) {
            return "本地网络";
        }

        // 基于国家进一步推断城市（简化实现）
        String country = getCountryByIp(ip);

        return switch (country) {
            case "中国" ->
                // 简化的中国城市推断
                    getChinaCityByIp(ip);
            case "美国" -> "美国主要城市";
            case "日本" -> "东京";
            case "韩国" -> "首尔";
            case "新加坡" -> "新加坡";
            case "香港" -> "香港";
            case "台湾" -> "台北";
            default -> "未知城市";
        };
    }

    /**
     * 根据IP推断中国城市（简化实现）
     */
    private String getChinaCityByIp(String ip) {
        try {
            String[] parts = ip.split("\\.");
            int firstOctet = Integer.parseInt(parts[0]);
            int secondOctet = Integer.parseInt(parts[1]);

            // 简化的城市推断（实际应使用专业数据库）
            if (firstOctet == 1 || (firstOctet == 42 && secondOctet >= 120)) {
                return "北京";
            }
            if (firstOctet == 14 || (firstOctet == 101 && secondOctet >= 80)) {
                return "上海";
            }
            if (firstOctet == 27 || (firstOctet == 183 && secondOctet >= 1 && secondOctet <= 63)) {
                return "深圳";
            }
            if (firstOctet == 36 || (firstOctet == 202 && secondOctet >= 96)) {
                return "广州";
            }
            if (firstOctet == 58 || (firstOctet == 110 && secondOctet >= 80)) {
                return "杭州";
            }

            return "其他城市";
        } catch (NumberFormatException e) {
            return "未知城市";
        }
    }

    /**
     * 判断是否为内网IP
     */
    private boolean isInternalIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }

        return ip.startsWith("127.") ||
                ip.startsWith("192.168.") ||
                ip.startsWith("10.") ||
                (ip.startsWith("172.") && isValidPrivateIp172(ip)) ||
                ip.equals("::1") ||
                ip.equals("localhost");
    }

    /**
     * 检查172网段的私有IP
     */
    private boolean isValidPrivateIp172(String ip) {
        try {
            String[] parts = ip.split("\\.");
            if (parts.length >= 2) {
                int secondOctet = Integer.parseInt(parts[1]);
                return secondOctet >= 16 && secondOctet <= 31;
            }
        } catch (NumberFormatException e) {
            // 忽略异常
        }
        return false;
    }
}
