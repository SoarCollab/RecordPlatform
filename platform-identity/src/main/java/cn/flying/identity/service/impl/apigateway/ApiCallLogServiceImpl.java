package cn.flying.identity.service.impl.apigateway;

import cn.flying.identity.dto.apigateway.ApiCallLog;
import cn.flying.identity.mapper.apigateway.ApiCallLogMapper;
import cn.flying.identity.service.BaseService;
import cn.flying.identity.service.apigateway.ApiCallLogService;
import cn.flying.identity.util.IdUtils;
import cn.flying.platformapi.constant.Result;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * API调用日志服务实现类
 * 提供高性能的日志记录、查询和统计功能
 * <p>
 * 优化特性：
 * 1. 异步批量日志记录
 * 2. 线程池管理和资源控制
 * 3. 实时统计缓存
 * 4. 自动批量刷新机制
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
@Service
public class ApiCallLogServiceImpl extends BaseService implements ApiCallLogService {

    /**
     * Redis缓存键前缀
     */
    private static final String STATS_CACHE_PREFIX = "api:stats:";
    private static final String REALTIME_STATS_KEY = "api:stats:realtime";

    /**
     * 批量写入阈值
     */
    private static final int BATCH_SIZE = 100;

    /**
     * 批量写入间隔（毫秒）
     */
    private static final long BATCH_INTERVAL = 5000;

    /**
     * 批量写入缓冲区
     */
    private final Queue<ApiCallLog> logBuffer = new ConcurrentLinkedQueue<>();

    @Resource
    private ApiCallLogMapper callLogMapper;

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    /**
     * 异步执行器（专门用于日志记录）
     */
    private ThreadPoolExecutor logExecutor;

    /**
     * 批量写入定时器
     */
    private ScheduledExecutorService batchScheduler;

    /**
     * 初始化服务
     */
    @PostConstruct
    public void init() {
        log.info("初始化API调用日志服务（增强版）...");

        // 创建异步执行器
        logExecutor = new ThreadPoolExecutor(
                2, // 核心线程数
                5, // 最大线程数
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000), // 限制队列大小防止OOM
                r -> new Thread(r, "ApiCallLog-Executor"),
                new ThreadPoolExecutor.CallerRunsPolicy() // 队列满时在调用者线程执行
        );

        // 创建批量写入调度器
        batchScheduler = Executors.newSingleThreadScheduledExecutor(
                r -> new Thread(r, "ApiCallLog-BatchWriter"));

        // 启动批量写入任务
        startBatchWriteTask();

        log.info("API调用日志服务初始化完成（增强版）");
    }

    /**
     * 启动批量写入任务
     */
    private void startBatchWriteTask() {
        batchScheduler.scheduleWithFixedDelay(() -> {
            try {
                if (!logBuffer.isEmpty()) {
                    flushLogs();
                }
            } catch (Exception e) {
                log.error("批量写入调用日志失败", e);
            }
        }, BATCH_INTERVAL, BATCH_INTERVAL, TimeUnit.MILLISECONDS);
    }

    /**
     * 批量写入日志
     */
    private void flushLogs() {
        List<ApiCallLog> batch = new ArrayList<>();
        ApiCallLog callLog;

        // 从缓冲区取出日志
        while ((callLog = logBuffer.poll()) != null && batch.size() < BATCH_SIZE) {
            batch.add(callLog);
        }

        if (!batch.isEmpty()) {
            try {
                // 批量插入数据库（使用MyBatis-Plus的批量插入）
                for (ApiCallLog logItem : batch) {
                    callLogMapper.insert(logItem);
                }
                log.info("批量写入API调用日志: size={}", batch.size());
            } catch (Exception e) {
                log.error("批量写入调用日志失败，尝试逐条写入", e);
                // 降级为逐条写入
                for (ApiCallLog logItem : batch) {
                    try {
                        callLogMapper.insert(logItem);
                    } catch (Exception ex) {
                        log.error("写入单条调用日志失败: requestId={}", logItem.getRequestId(), ex);
                    }
                }
            }
        }
    }

    /**
     * 销毁服务
     */
    @PreDestroy
    public void destroy() {
        log.info("关闭API调用日志服务...");

        // 处理剩余的日志
        flushLogs();

        // 关闭执行器
        if (logExecutor != null) {
            logExecutor.shutdown();
            try {
                if (!logExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    logExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                logExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (batchScheduler != null) {
            batchScheduler.shutdown();
            try {
                if (!batchScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    batchScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                batchScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("API调用日志服务已关闭");
    }

    @Override
    public Result<Void> recordCallLog(ApiCallLog log) {
        return safeExecuteAction(() -> {
            requireNonNull(log, "调用日志不能为空");

            if (log.getId() == null) {
                log.setId(IdUtils.nextEntityId());
            }

            int inserted = callLogMapper.insert(log);
            requireCondition(inserted, count -> count > 0, "记录调用日志失败");

            // 更新实时统计
            updateRealtimeStats(log);

            logDebug("记录API调用日志: requestId={}, appId={}, path={}, responseCode={}",
                    log.getRequestId(), log.getAppId(), log.getInterfacePath(), log.getResponseCode());
        }, "记录调用日志失败");
    }

    @Async
    @Override
    public void recordCallLogAsync(ApiCallLog log) {
        if (log == null) {
            return;
        }

        try {
            // 设置默认值
            if (log.getId() == null) {
                log.setId(IdUtils.nextEntityId());
            }
            if (log.getRequestTime() == null) {
                log.setRequestTime(LocalDateTime.now());
            }

            // 添加到缓冲区
            logBuffer.offer(log);

            // 如果缓冲区达到阈值，立即触发批量写入
            if (logBuffer.size() >= BATCH_SIZE) {
                logExecutor.execute(this::flushLogs);
            }

            // 更新实时统计
            updateRealtimeStats(log);

            logDebug("异步记录API调用日志（批量模式）: requestId={}, bufferSize={}",
                    log.getRequestId(), logBuffer.size());
        } catch (Exception e) {
            logError("异步记录API调用日志失败", e);
            // 降级：直接写入数据库
            try {
                callLogMapper.insert(log);
            } catch (Exception ex) {
                logError("降级写入调用日志也失败", ex);
            }
        }
    }

    @Override
    public Result<ApiCallLog> getCallLogById(Long logId) {
        return safeExecuteData(() -> {
            requireNonNull(logId, "日志ID不能为空");

            ApiCallLog log = callLogMapper.selectById(logId);
            requireNonNull(log, "调用日志不存在");

            return log;
        }, "查询调用日志失败");
    }

    @Override
    public Result<Page<ApiCallLog>> getCallLogsPage(int pageNum, int pageSize,
                                                    Long appId, String apiKey,
                                                    LocalDateTime startTime, LocalDateTime endTime,
                                                    Integer responseCode) {
        return safeExecuteData(() -> {
            requireCondition(pageNum, num -> num > 0, "页码必须大于0");
            requireCondition(pageSize, size -> size > 0 && size <= 100, "每页大小必须在1-100之间");

            Page<ApiCallLog> page = new Page<>(pageNum, pageSize);
            LambdaQueryWrapper<ApiCallLog> wrapper = new LambdaQueryWrapper<>();

            // 应用ID过滤
            if (appId != null) {
                wrapper.eq(ApiCallLog::getAppId, appId);
            }

            // API密钥过滤
            if (isNotBlank(apiKey)) {
                wrapper.eq(ApiCallLog::getApiKey, apiKey);
            }

            // 时间范围过滤
            if (startTime != null) {
                wrapper.ge(ApiCallLog::getRequestTime, startTime);
            }
            if (endTime != null) {
                wrapper.le(ApiCallLog::getRequestTime, endTime);
            }

            // 响应状态码过滤
            if (responseCode != null) {
                wrapper.eq(ApiCallLog::getResponseCode, responseCode);
            }

            wrapper.orderByDesc(ApiCallLog::getRequestTime);

            return callLogMapper.selectPage(page, wrapper);
        }, "查询调用日志分页列表失败");
    }

    @Override
    public Result<Page<ApiCallLog>> getCallLogsByAppId(Long appId, int days, int pageNum, int pageSize) {
        return safeExecuteData(() -> {
            requireNonNull(appId, "应用ID不能为空");
            requireCondition(days, d -> d > 0 && d <= 90, "查询天数必须在1-90之间");
            requireCondition(pageNum, num -> num > 0, "页码必须大于0");
            requireCondition(pageSize, size -> size > 0 && size <= 100, "每页大小必须在1-100之间");

            LocalDateTime startTime = LocalDateTime.now().minusDays(days);

            Page<ApiCallLog> page = new Page<>(pageNum, pageSize);
            LambdaQueryWrapper<ApiCallLog> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiCallLog::getAppId, appId)
                    .ge(ApiCallLog::getRequestTime, startTime)
                    .orderByDesc(ApiCallLog::getRequestTime);

            return callLogMapper.selectPage(page, wrapper);
        }, "查询应用调用日志失败");
    }

    @Override
    public Result<Page<ApiCallLog>> getCallLogsByApiKey(String apiKey, int days, int pageNum, int pageSize) {
        return safeExecuteData(() -> {
            requireNonBlank(apiKey, "API密钥不能为空");
            requireCondition(days, d -> d > 0 && d <= 90, "查询天数必须在1-90之间");
            requireCondition(pageNum, num -> num > 0, "页码必须大于0");
            requireCondition(pageSize, size -> size > 0 && size <= 100, "每页大小必须在1-100之间");

            LocalDateTime startTime = LocalDateTime.now().minusDays(days);

            Page<ApiCallLog> page = new Page<>(pageNum, pageSize);
            LambdaQueryWrapper<ApiCallLog> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiCallLog::getApiKey, apiKey)
                    .ge(ApiCallLog::getRequestTime, startTime)
                    .orderByDesc(ApiCallLog::getRequestTime);

            return callLogMapper.selectPage(page, wrapper);
        }, "查询API密钥调用日志失败");
    }

    @Override
    public Result<Map<String, Object>> getAppCallStatistics(Long appId, int days) {
        return safeExecuteData(() -> {
            requireNonNull(appId, "应用ID不能为空");
            requireCondition(days, d -> d > 0 && d <= 90, "统计天数必须在1-90之间");

            // 尝试从缓存获取
            String cacheKey = STATS_CACHE_PREFIX + "app:" + appId + ":" + days;
            String cachedStats = redisTemplate.opsForValue().get(cacheKey);
            if (isNotBlank(cachedStats)) {
                return parseStatsFromCache(cachedStats);
            }

            LocalDateTime startTime = LocalDateTime.now().minusDays(days);

            LambdaQueryWrapper<ApiCallLog> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiCallLog::getAppId, appId)
                    .ge(ApiCallLog::getRequestTime, startTime);

            List<ApiCallLog> logs = callLogMapper.selectList(wrapper);

            Map<String, Object> stats = calculateStatistics(logs);
            stats.put("app_id", appId);
            stats.put("stat_days", days);
            stats.put("stat_time", LocalDateTime.now());

            // 缓存统计结果(5分钟)
            cacheStatistics(cacheKey, stats, 300);

            return stats;
        }, "获取应用调用统计失败");
    }

    @Override
    public Result<Map<String, Object>> getApiKeyCallStatistics(String apiKey, int days) {
        return safeExecuteData(() -> {
            requireNonBlank(apiKey, "API密钥不能为空");
            requireCondition(days, d -> d > 0 && d <= 90, "统计天数必须在1-90之间");

            LocalDateTime startTime = LocalDateTime.now().minusDays(days);

            LambdaQueryWrapper<ApiCallLog> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiCallLog::getApiKey, apiKey)
                    .ge(ApiCallLog::getRequestTime, startTime);

            List<ApiCallLog> logs = callLogMapper.selectList(wrapper);

            Map<String, Object> stats = calculateStatistics(logs);
            stats.put("api_key", apiKey);
            stats.put("stat_days", days);
            stats.put("stat_time", LocalDateTime.now());

            return stats;
        }, "获取API密钥调用统计失败");
    }

    @Override
    public Result<Map<String, Object>> getInterfaceCallStatistics(Long interfaceId, int days) {
        return safeExecuteData(() -> {
            requireNonNull(interfaceId, "接口ID不能为空");
            requireCondition(days, d -> d > 0 && d <= 90, "统计天数必须在1-90之间");

            LocalDateTime startTime = LocalDateTime.now().minusDays(days);

            LambdaQueryWrapper<ApiCallLog> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiCallLog::getInterfaceId, interfaceId)
                    .ge(ApiCallLog::getRequestTime, startTime);

            List<ApiCallLog> logs = callLogMapper.selectList(wrapper);

            Map<String, Object> stats = calculateStatistics(logs);
            stats.put("interface_id", interfaceId);
            stats.put("stat_days", days);
            stats.put("stat_time", LocalDateTime.now());

            return stats;
        }, "获取接口调用统计失败");
    }

    @Override
    public Result<Integer> cleanExpiredLogs(int days) {
        return safeExecuteData(() -> {
            requireCondition(days, d -> d > 0, "保留天数必须大于0");

            LocalDateTime expireTime = LocalDateTime.now().minusDays(days);

            LambdaQueryWrapper<ApiCallLog> wrapper = new LambdaQueryWrapper<>();
            wrapper.lt(ApiCallLog::getCreateTime, expireTime);

            int deleted = callLogMapper.delete(wrapper);

            logInfo("清理过期调用日志: 保留{}天, 清理{}条", days, deleted);
            return deleted;
        }, "清理过期日志失败");
    }

    @Override
    public Result<Map<String, Object>> getRealtimeStatistics() {
        return safeExecuteData(() -> {
            Map<String, Object> stats = new HashMap<>();

            // 从Redis获取实时统计
            Map<Object, Object> realtimeData = redisTemplate.opsForHash().entries(REALTIME_STATS_KEY);

            stats.put("total_requests", getLongValue(realtimeData, "total_requests"));
            stats.put("success_requests", getLongValue(realtimeData, "success_requests"));
            stats.put("failed_requests", getLongValue(realtimeData, "failed_requests"));
            stats.put("avg_response_time", getDoubleValue(realtimeData, "avg_response_time"));
            stats.put("last_update_time", realtimeData.get("last_update_time"));

            return stats;
        }, "获取实时统计失败");
    }

    @Override
    public Result<Page<ApiCallLog>> getErrorLogs(int pageNum, int pageSize, int hours) {
        return safeExecuteData(() -> {
            requireCondition(pageNum, num -> num > 0, "页码必须大于0");
            requireCondition(pageSize, size -> size > 0 && size <= 100, "每页大小必须在1-100之间");
            requireCondition(hours, h -> h > 0 && h <= 72, "查询小时数必须在1-72之间");

            LocalDateTime startTime = LocalDateTime.now().minusHours(hours);

            Page<ApiCallLog> page = new Page<>(pageNum, pageSize);
            LambdaQueryWrapper<ApiCallLog> wrapper = new LambdaQueryWrapper<>();
            wrapper.ge(ApiCallLog::getResponseCode, 400) // 4xx和5xx错误
                    .ge(ApiCallLog::getRequestTime, startTime)
                    .orderByDesc(ApiCallLog::getRequestTime);

            return callLogMapper.selectPage(page, wrapper);
        }, "查询错误日志失败");
    }

    /**
     * 从Map中获取Long值
     */
    private long getLongValue(Map<Object, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 从Map中获取Double值
     */
    private double getDoubleValue(Map<Object, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * 从缓存解析统计数据
     *
     * @param cachedStats 缓存的统计字符串
     * @return 统计数据Map
     */
    private Map<String, Object> parseStatsFromCache(String cachedStats) {
        Map<String, Object> stats = new HashMap<>();
        if (isBlank(cachedStats)) {
            return stats;
        }

        String[] pairs = cachedStats.split(";");
        for (String pair : pairs) {
            String[] kv = pair.split(":");
            if (kv.length == 2) {
                stats.put(kv[0], kv[1]);
            }
        }
        return stats;
    }

    /**
     * 计算统计数据
     *
     * @param logs 调用日志列表
     * @return 统计结果
     */
    private Map<String, Object> calculateStatistics(List<ApiCallLog> logs) {
        Map<String, Object> stats = new HashMap<>();

        if (logs == null || logs.isEmpty()) {
            stats.put("total_requests", 0);
            stats.put("success_requests", 0);
            stats.put("failed_requests", 0);
            stats.put("avg_response_time", 0);
            stats.put("max_response_time", 0);
            stats.put("min_response_time", 0);
            stats.put("total_response_size", 0L);
            return stats;
        }

        long totalRequests = logs.size();
        long successRequests = logs.stream()
                .filter(log -> log.getResponseCode() != null && log.getResponseCode() >= 200 && log.getResponseCode() < 300)
                .count();
        long failedRequests = totalRequests - successRequests;

        double avgResponseTime = logs.stream()
                .filter(log -> log.getResponseTime() != null)
                .mapToInt(ApiCallLog::getResponseTime)
                .average()
                .orElse(0.0);

        int maxResponseTime = logs.stream()
                .filter(log -> log.getResponseTime() != null)
                .mapToInt(ApiCallLog::getResponseTime)
                .max()
                .orElse(0);

        int minResponseTime = logs.stream()
                .filter(log -> log.getResponseTime() != null)
                .mapToInt(ApiCallLog::getResponseTime)
                .min()
                .orElse(0);

        long totalResponseSize = logs.stream()
                .filter(log -> log.getResponseSize() != null)
                .mapToLong(ApiCallLog::getResponseSize)
                .sum();

        stats.put("total_requests", totalRequests);
        stats.put("success_requests", successRequests);
        stats.put("failed_requests", failedRequests);
        stats.put("success_rate", (double) successRequests / totalRequests * 100);
        stats.put("avg_response_time", Math.round(avgResponseTime));
        stats.put("max_response_time", maxResponseTime);
        stats.put("min_response_time", minResponseTime);
        stats.put("total_response_size", totalResponseSize);

        return stats;
    }

    /**
     * 缓存统计数据
     *
     * @param cacheKey 缓存键
     * @param stats    统计数据
     * @param seconds  过期秒数
     */
    private void cacheStatistics(String cacheKey, Map<String, Object> stats, int seconds) {
        try {
            // 简化：将统计数据转换为字符串缓存
            StringBuilder sb = new StringBuilder();
            stats.forEach((key, value) -> sb.append(key).append(":").append(value).append(";"));
            redisTemplate.opsForValue().set(cacheKey, sb.toString(), seconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            logError("缓存统计数据失败", e);
        }
    }

    /**
     * 更新实时统计
     *
     * @param log 调用日志
     */
    private void updateRealtimeStats(ApiCallLog log) {
        try {
            redisTemplate.opsForHash().increment(REALTIME_STATS_KEY, "total_requests", 1);

            if (log.getResponseCode() != null && log.getResponseCode() >= 200 && log.getResponseCode() < 300) {
                redisTemplate.opsForHash().increment(REALTIME_STATS_KEY, "success_requests", 1);
            } else {
                redisTemplate.opsForHash().increment(REALTIME_STATS_KEY, "failed_requests", 1);
            }

            if (log.getResponseTime() != null) {
                // 更新平均响应时间（简化计算）
                redisTemplate.opsForHash().put(REALTIME_STATS_KEY, "avg_response_time",
                        String.valueOf(log.getResponseTime()));
            }

            redisTemplate.opsForHash().put(REALTIME_STATS_KEY, "last_update_time",
                    LocalDateTime.now().toString());

            // 设置过期时间为1小时
            redisTemplate.expire(REALTIME_STATS_KEY, 1, TimeUnit.HOURS);
        } catch (Exception e) {
            logError("更新实时统计失败", e);
        }
    }
}
