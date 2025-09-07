package cn.flying.identity.service.impl;

import cn.flying.identity.service.GatewayMonitorService;
import cn.flying.identity.util.FlowUtils;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 网关监控服务实现类
 * 基于 Redis 实现流量监控、性能统计等功能
 * 
 * @author 王贝强
 */
@Slf4j
@Service
public class GatewayMonitorServiceImpl implements GatewayMonitorService {

    @Autowired
    private StringRedisTemplate redisTemplate;
    
    @Autowired
    private FlowUtils flowUtils;
    
    // 流量限制配置
    @Value("${gateway.rate-limit.requests-per-minute:60}")
    private int requestsPerMinute;
    
    @Value("${gateway.rate-limit.requests-per-hour:1000}")
    private int requestsPerHour;
    
    @Value("${gateway.rate-limit.block-time:300}")
    private int blockTime;
    
    // Redis 键前缀
    private static final String REQUEST_PREFIX = "gateway:request:";
    private static final String TRAFFIC_PREFIX = "gateway:traffic:";
    private static final String API_STATS_PREFIX = "gateway:api:";
    private static final String ERROR_STATS_PREFIX = "gateway:error:";
    private static final String PERFORMANCE_PREFIX = "gateway:performance:";
    private static final String USER_ACTIVITY_PREFIX = "gateway:user:";
    private static final String RATE_LIMIT_PREFIX = "gateway:limit:";
    
    @Override
    public Result<Void> recordRequestStart(String requestId, String method, String uri, 
                                          String clientIp, String userAgent, Long userId) {
        try {
            String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now());
            
            // 记录请求基本信息
            Map<String, String> requestInfo = new HashMap<>();
            requestInfo.put("method", method);
            requestInfo.put("uri", uri);
            requestInfo.put("client_ip", clientIp);
            requestInfo.put("user_agent", userAgent);
            requestInfo.put("user_id", userId != null ? userId.toString() : "");
            requestInfo.put("start_time", timestamp);
            requestInfo.put("status", "processing");
            
            String requestKey = REQUEST_PREFIX + requestId;
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
            
            return Result.success(null);
        } catch (Exception e) {
            log.error("记录请求开始失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Void> recordRequestEnd(String requestId, int statusCode, long responseSize, 
                                        long executionTime, String errorMessage) {
        try {
            String requestKey = REQUEST_PREFIX + requestId;
            
            // 更新请求信息
            Map<String, String> updateInfo = new HashMap<>();
            updateInfo.put("status_code", String.valueOf(statusCode));
            updateInfo.put("response_size", String.valueOf(responseSize));
            updateInfo.put("execution_time", String.valueOf(executionTime));
            updateInfo.put("end_time", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()));
            updateInfo.put("status", "completed");
            
            if (StrUtil.isNotBlank(errorMessage)) {
                updateInfo.put("error_message", errorMessage);
            }
            
            redisTemplate.opsForHash().putAll(requestKey, updateInfo);
            
            // 获取请求信息用于统计
            Map<Object, Object> requestInfo = redisTemplate.opsForHash().entries(requestKey);
            String method = (String) requestInfo.get("method");
            String uri = (String) requestInfo.get("uri");
            
            // 更新性能统计
            updatePerformanceStats(method, uri, executionTime);
            
            // 更新错误统计
            if (statusCode >= 400) {
                updateErrorStats(method, uri, statusCode, errorMessage);
            }
            
            return Result.success(null);
        } catch (Exception e) {
            log.error("记录请求结束失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Boolean> checkRateLimit(String clientIp, Long userId, String uri) {
        try {
            // IP级别限流
            String ipMinuteKey = RATE_LIMIT_PREFIX + "ip:minute:" + clientIp;
            String ipHourKey = RATE_LIMIT_PREFIX + "ip:hour:" + clientIp;
            
            if (!flowUtils.limitPeriodCountCheck(ipMinuteKey, requestsPerMinute, 60)) {
                log.warn("IP {} 超出每分钟请求限制", clientIp);
                return Result.success(false);
            }
            
            if (!flowUtils.limitPeriodCountCheck(ipHourKey, requestsPerHour, 3600)) {
                log.warn("IP {} 超出每小时请求限制", clientIp);
                return Result.success(false);
            }
            
            // 用户级别限流
            if (userId != null) {
                String userMinuteKey = RATE_LIMIT_PREFIX + "user:minute:" + userId;
                String userHourKey = RATE_LIMIT_PREFIX + "user:hour:" + userId;
                
                if (!flowUtils.limitPeriodCountCheck(userMinuteKey, requestsPerMinute * 2, 60)) {
                    log.warn("用户 {} 超出每分钟请求限制", userId);
                    return Result.success(false);
                }
                
                if (!flowUtils.limitPeriodCountCheck(userHourKey, requestsPerHour * 2, 3600)) {
                    log.warn("用户 {} 超出每小时请求限制", userId);
                    return Result.success(false);
                }
            }
            
            // API级别限流
            String apiKey = RATE_LIMIT_PREFIX + "api:minute:" + uri;
            if (!flowUtils.limitPeriodCountCheck(apiKey, requestsPerMinute * 10, 60)) {
                log.warn("API {} 超出每分钟请求限制", uri);
                return Result.success(false);
            }
            
            return Result.success(true);
        } catch (Exception e) {
            log.error("检查流量限制失败", e);
            // 异常情况下允许通过，避免影响正常业务
            return Result.success(true);
        }
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
            
            for (int i = 0; i < timeRange; i++) {
                LocalDateTime time = LocalDateTime.now().minusMinutes(i);
                String timeKey = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(time);
                
                String trafficKey = TRAFFIC_PREFIX + timeKey;
                Map<Object, Object> trafficData = redisTemplate.opsForHash().entries(trafficKey);
                
                if (!trafficData.isEmpty()) {
                    totalRequests += Long.parseLong((String) trafficData.getOrDefault("total", "0"));
                    successRequests += Long.parseLong((String) trafficData.getOrDefault("success", "0"));
                    errorRequests += Long.parseLong((String) trafficData.getOrDefault("error", "0"));
                }
            }
            
            stats.put("total_requests", totalRequests);
            stats.put("success_requests", successRequests);
            stats.put("error_requests", errorRequests);
            stats.put("success_rate", totalRequests > 0 ? (double) successRequests / totalRequests * 100 : 0);
            stats.put("error_rate", totalRequests > 0 ? (double) errorRequests / totalRequests * 100 : 0);
            stats.put("time_range", timeRange);
            stats.put("current_time", currentMinute);
            
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

                String apiStatsKey = API_STATS_PREFIX + timeKey;
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
            long totalErrors = 0;

            for (int i = 0; i < timeRange; i++) {
                LocalDateTime time = LocalDateTime.now().minusMinutes(i);
                String timeKey = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(time);

                String errorStatsKey = ERROR_STATS_PREFIX + timeKey;
                Map<Object, Object> errorData = redisTemplate.opsForHash().entries(errorStatsKey);

                for (Map.Entry<Object, Object> entry : errorData.entrySet()) {
                    String errorType = (String) entry.getKey();
                    Long count = Long.parseLong((String) entry.getValue());
                    errorCounts.merge(errorType, count, Long::sum);
                    totalErrors += count;
                }
            }

            stats.put("error_types", errorCounts);
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

                String performanceKey = PERFORMANCE_PREFIX + timeKey;
                Map<Object, Object> performanceData = redisTemplate.opsForHash().entries(performanceKey);

                for (Map.Entry<Object, Object> entry : performanceData.entrySet()) {
                    String responseTime = (String) entry.getValue();
                    responseTimes.add(Long.parseLong(responseTime));
                }
            }

            if (!responseTimes.isEmpty()) {
                responseTimes.sort(Long::compareTo);

                double avgResponseTime = responseTimes.stream().mapToLong(Long::longValue).average().orElse(0);
                long minResponseTime = responseTimes.get(0);
                long maxResponseTime = responseTimes.get(responseTimes.size() - 1);
                long p95ResponseTime = responseTimes.get((int) (responseTimes.size() * 0.95));
                long p99ResponseTime = responseTimes.get((int) (responseTimes.size() * 0.99));

                stats.put("avg_response_time", avgResponseTime);
                stats.put("min_response_time", minResponseTime);
                stats.put("max_response_time", maxResponseTime);
                stats.put("p95_response_time", p95ResponseTime);
                stats.put("p99_response_time", p99ResponseTime);
                stats.put("total_requests", responseTimes.size());
            } else {
                stats.put("avg_response_time", 0);
                stats.put("min_response_time", 0);
                stats.put("max_response_time", 0);
                stats.put("p95_response_time", 0);
                stats.put("p99_response_time", 0);
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

                String userActivityKey = USER_ACTIVITY_PREFIX + timeKey;
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
            String ipKey = RATE_LIMIT_PREFIX + "ip:minute:" + clientIp;
            String ipCount = redisTemplate.opsForValue().get(ipKey);
            if (ipCount != null && Integer.parseInt(ipCount) > requestsPerMinute * 0.8) {
                isAbnormal = true;
                reasons.add("IP请求频率过高");
            }

            // 检查用户请求频率
            if (userId != null) {
                String userKey = RATE_LIMIT_PREFIX + "user:minute:" + userId;
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

            // 这里可以实现具体的清理逻辑
            // 由于使用Redis存储，可以通过设置TTL自动过期

            result.put("cleaned_count", cleanedCount);
            result.put("retention_days", retentionDays);
            result.put("cutoff_time", cutoffTimeStr);

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

                String performanceKey = PERFORMANCE_PREFIX + timeKey;
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

            // 这里可以根据IP地址解析地理位置
            // 目前简化处理，返回空统计

            stats.put("country_stats", countryStats);
            stats.put("city_stats", cityStats);
            stats.put("time_range", timeRange);

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取地理位置统计失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 更新流量统计
     */
    private void updateTrafficStats(String method, String uri, String clientIp, Long userId) {
        try {
            String timeKey = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(LocalDateTime.now());
            String trafficKey = TRAFFIC_PREFIX + timeKey;

            redisTemplate.opsForHash().increment(trafficKey, "total", 1);
            redisTemplate.expire(trafficKey, 24, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("更新流量统计失败", e);
        }
    }

    /**
     * 更新API统计
     */
    private void updateApiStats(String method, String uri) {
        try {
            String timeKey = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(LocalDateTime.now());
            String apiStatsKey = API_STATS_PREFIX + timeKey;
            String apiKey = method + " " + uri;

            redisTemplate.opsForHash().increment(apiStatsKey, apiKey, 1);
            redisTemplate.expire(apiStatsKey, 24, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("更新API统计失败", e);
        }
    }

    /**
     * 更新用户活跃度统计
     */
    private void updateUserActivityStats(Long userId) {
        try {
            String timeKey = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(LocalDateTime.now());
            String userActivityKey = USER_ACTIVITY_PREFIX + timeKey;

            redisTemplate.opsForSet().add(userActivityKey, userId.toString());
            redisTemplate.expire(userActivityKey, 24, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("更新用户活跃度统计失败", e);
        }
    }

    /**
     * 更新性能统计
     */
    private void updatePerformanceStats(String method, String uri, long executionTime) {
        try {
            String timeKey = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(LocalDateTime.now());
            String performanceKey = PERFORMANCE_PREFIX + timeKey;
            String apiKey = method + " " + uri;

            redisTemplate.opsForHash().put(performanceKey, apiKey, String.valueOf(executionTime));
            redisTemplate.expire(performanceKey, 24, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("更新性能统计失败", e);
        }
    }

    /**
     * 更新错误统计
     */
    private void updateErrorStats(String method, String uri, int statusCode, String errorMessage) {
        try {
            String timeKey = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(LocalDateTime.now());
            String errorStatsKey = ERROR_STATS_PREFIX + timeKey;
            String errorType = "HTTP_" + statusCode;

            redisTemplate.opsForHash().increment(errorStatsKey, errorType, 1);
            redisTemplate.expire(errorStatsKey, 24, TimeUnit.HOURS);

            // 更新流量统计中的错误计数
            String trafficKey = TRAFFIC_PREFIX + timeKey;
            redisTemplate.opsForHash().increment(trafficKey, "error", 1);
        } catch (Exception e) {
            log.error("更新错误统计失败", e);
        }
    }
}
