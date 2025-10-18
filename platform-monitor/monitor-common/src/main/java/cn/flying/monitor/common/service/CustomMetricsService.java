package cn.flying.monitor.common.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 自定义业务指标服务
 * 提供业务相关的指标收集功能
 */
@Slf4j
@Service
public class CustomMetricsService {

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, AtomicLong> gaugeValues = new ConcurrentHashMap<>();

    public CustomMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        log.info("初始化自定义指标服务");
    }

    /**
     * 记录数据摄取指标
     */
    public void recordDataIngestion(String clientId, int recordCount, Duration processingTime) {
        // 数据摄取计数器
        Counter.builder("monitor.data.ingestion.records")
            .description("数据摄取记录数")
            .tag("client_id", clientId)
            .register(meterRegistry)
            .increment(recordCount);

        // 数据摄取处理时间
        Timer.builder("monitor.data.ingestion.processing.time")
            .description("数据摄取处理时间")
            .tag("client_id", clientId)
            .register(meterRegistry)
            .record(processingTime);

        log.debug("记录数据摄取指标: 客户端={}, 记录数={}, 处理时间={}ms", 
                 clientId, recordCount, processingTime.toMillis());
    }

    /**
     * 记录查询性能指标
     */
    public void recordQueryPerformance(String queryType, Duration executionTime, boolean cacheHit) {
        // 查询执行时间
        Timer.builder("monitor.query.execution.time")
            .description("查询执行时间")
            .tag("query_type", queryType)
            .tag("cache_hit", String.valueOf(cacheHit))
            .register(meterRegistry)
            .record(executionTime);

        // 缓存命中率计数器
        Counter.builder("monitor.query.cache")
            .description("查询缓存统计")
            .tag("query_type", queryType)
            .tag("result", cacheHit ? "hit" : "miss")
            .register(meterRegistry)
            .increment();

        log.debug("记录查询性能指标: 类型={}, 执行时间={}ms, 缓存命中={}", 
                 queryType, executionTime.toMillis(), cacheHit);
    }

    /**
     * 记录告警处理指标
     */
    public void recordAlertProcessing(String alertType, String severity, Duration responseTime) {
        // 告警处理计数器
        Counter.builder("monitor.alert.processed")
            .description("告警处理数量")
            .tag("alert_type", alertType)
            .tag("severity", severity)
            .register(meterRegistry)
            .increment();

        // 告警响应时间
        Timer.builder("monitor.alert.response.time")
            .description("告警响应时间")
            .tag("alert_type", alertType)
            .tag("severity", severity)
            .register(meterRegistry)
            .record(responseTime);

        log.debug("记录告警处理指标: 类型={}, 严重程度={}, 响应时间={}ms", 
                 alertType, severity, responseTime.toMillis());
    }

    /**
     * 记录WebSocket连接指标
     */
    public void recordWebSocketConnection(String connectionType, boolean connected) {
        String action = connected ? "connected" : "disconnected";
        
        Counter.builder("monitor.websocket.connections")
            .description("WebSocket连接统计")
            .tag("connection_type", connectionType)
            .tag("action", action)
            .register(meterRegistry)
            .increment();

        // 更新活跃连接数
        String gaugeKey = "websocket.active." + connectionType;
        AtomicLong activeConnections = gaugeValues.computeIfAbsent(gaugeKey, k -> {
            AtomicLong value = new AtomicLong(0);
            Gauge.builder("monitor.websocket.active.connections", value, AtomicLong::get)
                .description("活跃WebSocket连接数")
                .tag("connection_type", connectionType)
                .register(meterRegistry);
            return value;
        });

        if (connected) {
            activeConnections.incrementAndGet();
        } else {
            activeConnections.decrementAndGet();
        }

        log.debug("记录WebSocket连接指标: 类型={}, 动作={}, 活跃连接数={}", 
                 connectionType, action, activeConnections.get());
    }

    /**
     * 记录认证指标
     */
    public void recordAuthentication(String authType, boolean success, Duration processingTime) {
        // 认证结果计数器
        Counter.builder("monitor.authentication.attempts")
            .description("认证尝试统计")
            .tag("auth_type", authType)
            .tag("result", success ? "success" : "failure")
            .register(meterRegistry)
            .increment();

        // 认证处理时间
        Timer.builder("monitor.authentication.processing.time")
            .description("认证处理时间")
            .tag("auth_type", authType)
            .tag("result", success ? "success" : "failure")
            .register(meterRegistry)
            .record(processingTime);

        log.debug("记录认证指标: 类型={}, 成功={}, 处理时间={}ms", 
                 authType, success, processingTime.toMillis());
    }

    /**
     * 记录数据库连接池指标
     */
    public void recordDatabaseConnectionPool(String poolName, int activeConnections, int idleConnections, int maxConnections) {
        // 活跃连接数
        Gauge.builder("monitor.database.pool.active", activeConnections, Number::doubleValue)
            .description("数据库连接池活跃连接数")
            .tag("pool_name", poolName)
            .register(meterRegistry);

        // 空闲连接数
        Gauge.builder("monitor.database.pool.idle", idleConnections, Number::doubleValue)
            .description("数据库连接池空闲连接数")
            .tag("pool_name", poolName)
            .register(meterRegistry);

        // 连接池使用率
        double utilizationRate = maxConnections > 0 ? (double) activeConnections / maxConnections : 0;
        Gauge.builder("monitor.database.pool.utilization", utilizationRate, Number::doubleValue)
            .description("数据库连接池使用率")
            .tag("pool_name", poolName)
            .register(meterRegistry);

        log.debug("记录数据库连接池指标: 池名={}, 活跃={}, 空闲={}, 使用率={:.2f}", 
                 poolName, activeConnections, idleConnections, utilizationRate);
    }

    /**
     * 创建自定义计数器
     */
    public Counter createCounter(String name, String description, String... tags) {
        Counter.Builder builder = Counter.builder(name).description(description);
        
        // 添加标签
        for (int i = 0; i < tags.length; i += 2) {
            if (i + 1 < tags.length) {
                builder.tag(tags[i], tags[i + 1]);
            }
        }
        
        return builder.register(meterRegistry);
    }

    /**
     * 创建自定义计时器
     */
    public Timer createTimer(String name, String description, String... tags) {
        Timer.Builder builder = Timer.builder(name).description(description);
        
        // 添加标签
        for (int i = 0; i < tags.length; i += 2) {
            if (i + 1 < tags.length) {
                builder.tag(tags[i], tags[i + 1]);
            }
        }
        
        return builder.register(meterRegistry);
    }

    /**
     * 创建自定义仪表
     */
    public <T> Gauge createGauge(String name, String description, T obj, Supplier<Number> valueFunction, String... tags) {
        if (tags.length == 0) {
            return Gauge.builder(name, obj, o -> valueFunction.get().doubleValue())
                .description(description)
                .register(meterRegistry);
        }
        
        // 添加标签
        for (int i = 0; i < tags.length; i += 2) {
            if (i + 1 < tags.length) {
                return Gauge.builder(name, obj, o -> valueFunction.get().doubleValue())
                    .description(description)
                    .tag(tags[i], tags[i + 1])
                    .register(meterRegistry);
            }
        }
        
        return null;
    }

    /**
     * 记录业务SLA指标
     */
    public void recordSlaMetrics(String serviceName, double availabilityPercent, double responseTimeP95, 
                                double errorRate, boolean slaCompliant) {
        // SLA可用性
        Gauge.builder("monitor.sla.availability", availabilityPercent, Number::doubleValue)
            .description("服务可用性百分比")
            .tag("service", serviceName)
            .register(meterRegistry);

        // SLA响应时间P95
        Gauge.builder("monitor.sla.response.time.p95", responseTimeP95, Number::doubleValue)
            .description("服务响应时间P95")
            .tag("service", serviceName)
            .register(meterRegistry);

        // SLA错误率
        Gauge.builder("monitor.sla.error.rate", errorRate, Number::doubleValue)
            .description("服务错误率")
            .tag("service", serviceName)
            .register(meterRegistry);

        // SLA合规性
        Counter.builder("monitor.sla.compliance")
            .description("SLA合规性统计")
            .tag("service", serviceName)
            .tag("compliant", String.valueOf(slaCompliant))
            .register(meterRegistry)
            .increment();

        log.debug("记录SLA指标: 服务={}, 可用性={:.2f}%, P95={:.2f}ms, 错误率={:.2f}%, 合规={}", 
                 serviceName, availabilityPercent, responseTimeP95, errorRate, slaCompliant);
    }

    /**
     * 记录性能评分指标
     */
    public void recordPerformanceScore(String serviceName, double performanceScore, 
                                     java.util.Map<String, Double> subScores) {
        // 整体性能评分
        Gauge.builder("monitor.performance.overall.score", performanceScore, Number::doubleValue)
            .description("服务整体性能评分")
            .tag("service", serviceName)
            .register(meterRegistry);

        // 子项评分
        if (subScores != null) {
            for (java.util.Map.Entry<String, Double> entry : subScores.entrySet()) {
                String metric = entry.getKey();
                Double score = entry.getValue();
                
                Gauge.builder("monitor.performance.sub.score", score, Number::doubleValue)
                    .description("服务性能子项评分")
                    .tag("service", serviceName)
                    .tag("metric", metric)
                    .register(meterRegistry);
            }
        }

        // 性能等级分类
        String performanceGrade = calculatePerformanceGrade(performanceScore);
        Counter.builder("monitor.performance.grade")
            .description("性能等级统计")
            .tag("service", serviceName)
            .tag("grade", performanceGrade)
            .register(meterRegistry)
            .increment();

        log.debug("记录性能评分: 服务={}, 评分={:.2f}, 等级={}", 
                 serviceName, performanceScore, performanceGrade);
    }

    /**
     * 记录数据质量指标
     */
    public void recordDataQualityMetrics(String dataSource, double completenessPercent, 
                                       double accuracyPercent, double freshnessScore, 
                                       int duplicateCount, int invalidRecords) {
        // 数据完整性
        Gauge.builder("monitor.data.quality.completeness", completenessPercent, Number::doubleValue)
            .description("数据完整性百分比")
            .tag("source", dataSource)
            .register(meterRegistry);

        // 数据准确性
        Gauge.builder("monitor.data.quality.accuracy", accuracyPercent, Number::doubleValue)
            .description("数据准确性百分比")
            .tag("source", dataSource)
            .register(meterRegistry);

        // 数据新鲜度
        Gauge.builder("monitor.data.quality.freshness", freshnessScore, Number::doubleValue)
            .description("数据新鲜度评分")
            .tag("source", dataSource)
            .register(meterRegistry);

        // 重复数据计数
        Counter.builder("monitor.data.quality.duplicates")
            .description("重复数据统计")
            .tag("source", dataSource)
            .register(meterRegistry)
            .increment(duplicateCount);

        // 无效记录计数
        Counter.builder("monitor.data.quality.invalid.records")
            .description("无效记录统计")
            .tag("source", dataSource)
            .register(meterRegistry)
            .increment(invalidRecords);

        // 计算综合数据质量评分
        double overallQualityScore = (completenessPercent + accuracyPercent + freshnessScore) / 3.0;
        Gauge.builder("monitor.data.quality.overall.score", overallQualityScore, Number::doubleValue)
            .description("数据质量综合评分")
            .tag("source", dataSource)
            .register(meterRegistry);

        log.debug("记录数据质量指标: 数据源={}, 完整性={:.2f}%, 准确性={:.2f}%, 新鲜度={:.2f}, 重复={}, 无效={}", 
                 dataSource, completenessPercent, accuracyPercent, freshnessScore, duplicateCount, invalidRecords);
    }

    /**
     * 记录缓存性能指标
     */
    public void recordCachePerformanceMetrics(String cacheName, long hitCount, long missCount, 
                                            double hitRatio, long evictionCount, 
                                            Duration averageLoadTime, long cacheSize) {
        // 缓存命中次数
        Counter.builder("monitor.cache.hits")
            .description("缓存命中次数")
            .tag("cache", cacheName)
            .register(meterRegistry)
            .increment(hitCount);

        // 缓存未命中次数
        Counter.builder("monitor.cache.misses")
            .description("缓存未命中次数")
            .tag("cache", cacheName)
            .register(meterRegistry)
            .increment(missCount);

        // 缓存命中率
        Gauge.builder("monitor.cache.hit.ratio", hitRatio, Number::doubleValue)
            .description("缓存命中率")
            .tag("cache", cacheName)
            .register(meterRegistry);

        // 缓存驱逐次数
        Counter.builder("monitor.cache.evictions")
            .description("缓存驱逐次数")
            .tag("cache", cacheName)
            .register(meterRegistry)
            .increment(evictionCount);

        // 平均加载时间
        Gauge.builder("monitor.cache.average.load.time", averageLoadTime.toMillis(), Number::doubleValue)
            .description("缓存平均加载时间(ms)")
            .tag("cache", cacheName)
            .register(meterRegistry);

        // 缓存大小
        Gauge.builder("monitor.cache.size", cacheSize, Number::doubleValue)
            .description("缓存条目数量")
            .tag("cache", cacheName)
            .register(meterRegistry);

        // 缓存效率评分
        double efficiencyScore = calculateCacheEfficiencyScore(hitRatio, averageLoadTime);
        Gauge.builder("monitor.cache.efficiency.score", efficiencyScore, Number::doubleValue)
            .description("缓存效率评分")
            .tag("cache", cacheName)
            .register(meterRegistry);

        log.debug("记录缓存性能指标: 缓存={}, 命中率={:.2f}%, 平均加载时间={}ms, 大小={}, 效率评分={:.2f}", 
                 cacheName, hitRatio * 100, averageLoadTime.toMillis(), cacheSize, efficiencyScore);
    }

    /**
     * 记录查询统计指标
     */
    public void recordQueryStatistics(String queryType, int totalQueries, int successfulQueries, 
                                    int failedQueries, Duration averageExecutionTime, 
                                    Duration maxExecutionTime, int slowQueries) {
        // 查询总数
        Counter.builder("monitor.query.total")
            .description("查询总数")
            .tag("type", queryType)
            .register(meterRegistry)
            .increment(totalQueries);

        // 成功查询数
        Counter.builder("monitor.query.successful")
            .description("成功查询数")
            .tag("type", queryType)
            .register(meterRegistry)
            .increment(successfulQueries);

        // 失败查询数
        Counter.builder("monitor.query.failed")
            .description("失败查询数")
            .tag("type", queryType)
            .register(meterRegistry)
            .increment(failedQueries);

        // 查询成功率
        double successRate = totalQueries > 0 ? (double) successfulQueries / totalQueries * 100 : 0;
        Gauge.builder("monitor.query.success.rate", successRate, Number::doubleValue)
            .description("查询成功率")
            .tag("type", queryType)
            .register(meterRegistry);

        // 平均执行时间
        Gauge.builder("monitor.query.average.execution.time", averageExecutionTime.toMillis(), Number::doubleValue)
            .description("查询平均执行时间(ms)")
            .tag("type", queryType)
            .register(meterRegistry);

        // 最大执行时间
        Gauge.builder("monitor.query.max.execution.time", maxExecutionTime.toMillis(), Number::doubleValue)
            .description("查询最大执行时间(ms)")
            .tag("type", queryType)
            .register(meterRegistry);

        // 慢查询数
        Counter.builder("monitor.query.slow")
            .description("慢查询数")
            .tag("type", queryType)
            .register(meterRegistry)
            .increment(slowQueries);

        // 查询性能评分
        double performanceScore = calculateQueryPerformanceScore(successRate, averageExecutionTime, slowQueries, totalQueries);
        Gauge.builder("monitor.query.performance.score", performanceScore, Number::doubleValue)
            .description("查询性能评分")
            .tag("type", queryType)
            .register(meterRegistry);

        log.debug("记录查询统计指标: 类型={}, 总数={}, 成功率={:.2f}%, 平均时间={}ms, 慢查询={}, 性能评分={:.2f}", 
                 queryType, totalQueries, successRate, averageExecutionTime.toMillis(), slowQueries, performanceScore);
    }

    /**
     * 计算性能等级
     */
    private String calculatePerformanceGrade(double score) {
        if (score >= 90) return "A";
        if (score >= 80) return "B";
        if (score >= 70) return "C";
        if (score >= 60) return "D";
        return "F";
    }

    /**
     * 计算缓存效率评分
     */
    private double calculateCacheEfficiencyScore(double hitRatio, Duration averageLoadTime) {
        // 基础分数基于命中率
        double baseScore = hitRatio * 100;
        
        // 根据加载时间调整分数
        long loadTimeMs = averageLoadTime.toMillis();
        if (loadTimeMs <= 10) {
            // 10ms以内，不扣分
            return Math.min(100, baseScore);
        } else if (loadTimeMs <= 50) {
            // 10-50ms，轻微扣分
            return Math.min(100, baseScore - 5);
        } else if (loadTimeMs <= 100) {
            // 50-100ms，中等扣分
            return Math.min(100, baseScore - 10);
        } else {
            // 100ms以上，重度扣分
            return Math.min(100, baseScore - 20);
        }
    }

    /**
     * 计算查询性能评分
     */
    private double calculateQueryPerformanceScore(double successRate, Duration averageExecutionTime, 
                                                int slowQueries, int totalQueries) {
        // 基础分数基于成功率
        double baseScore = successRate;
        
        // 根据平均执行时间调整
        long avgTimeMs = averageExecutionTime.toMillis();
        if (avgTimeMs > 1000) {
            baseScore -= 20; // 超过1秒扣20分
        } else if (avgTimeMs > 500) {
            baseScore -= 10; // 超过500ms扣10分
        } else if (avgTimeMs > 200) {
            baseScore -= 5;  // 超过200ms扣5分
        }
        
        // 根据慢查询比例调整
        if (totalQueries > 0) {
            double slowQueryRatio = (double) slowQueries / totalQueries;
            if (slowQueryRatio > 0.1) {
                baseScore -= 15; // 慢查询超过10%扣15分
            } else if (slowQueryRatio > 0.05) {
                baseScore -= 10; // 慢查询超过5%扣10分
            }
        }
        
        return Math.max(0, Math.min(100, baseScore));
    }
}