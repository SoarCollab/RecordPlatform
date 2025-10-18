package cn.flying.monitor.common.service;

import cn.flying.monitor.common.util.ErrorHandlingUtils;
import cn.flying.monitor.common.util.ResourceCleanupUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务指标服务
 * 负责计算和收集业务相关的指标，如SLA、性能评分等
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessMetricsService {

    private final CustomMetricsService customMetricsService;
    
    // 服务统计数据
    private final Map<String, ServiceStats> serviceStats = new ConcurrentHashMap<>();
    
    // 数据质量统计
    private final Map<String, DataQualityStats> dataQualityStats = new ConcurrentHashMap<>();
    
    // 缓存统计
    private final Map<String, CacheStats> cacheStats = new ConcurrentHashMap<>();

    /**
     * 记录服务调用
     */
    public void recordServiceCall(String serviceName, Duration responseTime, boolean success) {
        ServiceStats stats = serviceStats.computeIfAbsent(serviceName, k -> new ServiceStats());
        
        stats.totalCalls.incrementAndGet();
        if (success) {
            stats.successfulCalls.incrementAndGet();
        } else {
            stats.failedCalls.incrementAndGet();
        }
        
        // 更新响应时间统计
        long responseTimeMs = responseTime.toMillis();
        stats.totalResponseTime.addAndGet(responseTimeMs);
        
        if (responseTimeMs > stats.maxResponseTime.get()) {
            stats.maxResponseTime.set(responseTimeMs);
        }
        
        // 记录慢调用
        if (responseTimeMs > 1000) { // 超过1秒视为慢调用
            stats.slowCalls.incrementAndGet();
        }
        
        stats.lastCallTime = Instant.now();
        
        log.debug("记录服务调用: 服务={}, 响应时间={}ms, 成功={}", serviceName, responseTimeMs, success);
    }

    /**
     * 记录数据质量事件
     */
    public void recordDataQualityEvent(String dataSource, DataQualityEventType eventType, int count) {
        DataQualityStats stats = dataQualityStats.computeIfAbsent(dataSource, k -> new DataQualityStats());
        
        switch (eventType) {
            case COMPLETE_RECORD -> stats.completeRecords.addAndGet(count);
            case INCOMPLETE_RECORD -> stats.incompleteRecords.addAndGet(count);
            case ACCURATE_RECORD -> stats.accurateRecords.addAndGet(count);
            case INACCURATE_RECORD -> stats.inaccurateRecords.addAndGet(count);
            case DUPLICATE_RECORD -> stats.duplicateRecords.addAndGet(count);
            case INVALID_RECORD -> stats.invalidRecords.addAndGet(count);
            case FRESH_RECORD -> stats.freshRecords.addAndGet(count);
            case STALE_RECORD -> stats.staleRecords.addAndGet(count);
        }
        
        stats.totalRecords.addAndGet(count);
        stats.lastUpdateTime = Instant.now();
        
        log.debug("记录数据质量事件: 数据源={}, 事件类型={}, 数量={}", dataSource, eventType, count);
    }

    /**
     * 记录缓存事件
     */
    public void recordCacheEvent(String cacheName, CacheEventType eventType, Duration loadTime) {
        CacheStats stats = cacheStats.computeIfAbsent(cacheName, k -> new CacheStats());
        
        switch (eventType) {
            case HIT -> stats.hitCount.incrementAndGet();
            case MISS -> {
                stats.missCount.incrementAndGet();
                if (loadTime != null) {
                    stats.totalLoadTime.addAndGet(loadTime.toMillis());
                    stats.loadCount.incrementAndGet();
                }
            }
            case EVICTION -> stats.evictionCount.incrementAndGet();
        }
        
        stats.lastAccessTime = Instant.now();
        
        log.debug("记录缓存事件: 缓存={}, 事件类型={}, 加载时间={}ms", 
                 cacheName, eventType, loadTime != null ? loadTime.toMillis() : 0);
    }

    /**
     * 定期计算和发布业务指标（每分钟执行一次）
     */
    @Scheduled(fixedRate = 60000)
    public void calculateAndPublishMetrics() {
        ErrorHandlingUtils.executeWithErrorHandling(() -> {
            log.debug("开始计算业务指标");
            
            calculateSlaMetrics();
            calculateDataQualityMetrics();
            calculateCachePerformanceMetrics();
            
            log.debug("业务指标计算完成");
        }, "计算业务指标");
    }

    /**
     * 计算SLA指标
     */
    private void calculateSlaMetrics() {
        for (Map.Entry<String, ServiceStats> entry : serviceStats.entrySet()) {
            String serviceName = entry.getKey();
            ServiceStats stats = entry.getValue();
            
            try {
                // 计算可用性
                long totalCalls = stats.totalCalls.get();
                long successfulCalls = stats.successfulCalls.get();
                double availability = totalCalls > 0 ? (double) successfulCalls / totalCalls * 100 : 100;
                
                // 计算平均响应时间
                double avgResponseTime = totalCalls > 0 ? (double) stats.totalResponseTime.get() / totalCalls : 0;
                
                // 计算错误率
                long failedCalls = stats.failedCalls.get();
                double errorRate = totalCalls > 0 ? (double) failedCalls / totalCalls * 100 : 0;
                
                // 计算P95响应时间（简化计算，使用最大响应时间的80%作为近似值）
                double p95ResponseTime = stats.maxResponseTime.get() * 0.8;
                
                // 判断SLA合规性（可用性>99%，P95<1000ms，错误率<1%）
                boolean slaCompliant = availability > 99.0 && p95ResponseTime < 1000 && errorRate < 1.0;
                
                // 发布SLA指标
                customMetricsService.recordSlaMetrics(serviceName, availability, p95ResponseTime, errorRate, slaCompliant);
                
                // 计算性能评分
                Map<String, Double> subScores = new HashMap<>();
                subScores.put("availability", availability);
                subScores.put("responseTime", Math.max(0, 100 - avgResponseTime / 10)); // 响应时间越低分数越高
                subScores.put("errorRate", Math.max(0, 100 - errorRate * 10)); // 错误率越低分数越高
                
                double overallScore = subScores.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
                customMetricsService.recordPerformanceScore(serviceName, overallScore, subScores);
                
            } catch (Exception e) {
                ErrorHandlingUtils.logMetricsCalculationError("SLA指标", serviceName, e);
            }
        }
    }

    /**
     * 计算数据质量指标
     */
    private void calculateDataQualityMetrics() {
        for (Map.Entry<String, DataQualityStats> entry : dataQualityStats.entrySet()) {
            String dataSource = entry.getKey();
            DataQualityStats stats = entry.getValue();
            
            try {
                long totalRecords = stats.totalRecords.get();
                if (totalRecords == 0) continue;
                
                // 计算完整性
                long completeRecords = stats.completeRecords.get();
                double completeness = (double) completeRecords / totalRecords * 100;
                
                // 计算准确性
                long accurateRecords = stats.accurateRecords.get();
                double accuracy = (double) accurateRecords / totalRecords * 100;
                
                // 计算新鲜度（基于最近更新时间）
                long minutesSinceUpdate = Duration.between(stats.lastUpdateTime, Instant.now()).toMinutes();
                double freshness = Math.max(0, 100 - minutesSinceUpdate); // 每分钟扣1分
                
                // 获取问题记录数量
                int duplicates = stats.duplicateRecords.intValue();
                int invalidRecords = stats.invalidRecords.intValue();
                
                // 发布数据质量指标
                customMetricsService.recordDataQualityMetrics(dataSource, completeness, accuracy, 
                                                            freshness, duplicates, invalidRecords);
                
            } catch (Exception e) {
                ErrorHandlingUtils.logMetricsCalculationError("数据质量指标", dataSource, e);
            }
        }
    }

    /**
     * 计算缓存性能指标
     */
    private void calculateCachePerformanceMetrics() {
        for (Map.Entry<String, CacheStats> entry : cacheStats.entrySet()) {
            String cacheName = entry.getKey();
            CacheStats stats = entry.getValue();
            
            try {
                long hitCount = stats.hitCount.get();
                long missCount = stats.missCount.get();
                long totalAccess = hitCount + missCount;
                
                if (totalAccess == 0) continue;
                
                // 计算命中率
                double hitRatio = (double) hitCount / totalAccess;
                
                // 计算平均加载时间
                long loadCount = stats.loadCount.get();
                Duration avgLoadTime = loadCount > 0 ? 
                    Duration.ofMillis(stats.totalLoadTime.get() / loadCount) : Duration.ZERO;
                
                // 获取驱逐次数
                long evictionCount = stats.evictionCount.get();
                
                // 估算缓存大小（基于命中次数，这是一个简化的估算）
                long estimatedSize = hitCount + missCount - evictionCount;
                
                // 发布缓存性能指标
                customMetricsService.recordCachePerformanceMetrics(cacheName, hitCount, missCount, 
                                                                 hitRatio, evictionCount, avgLoadTime, estimatedSize);
                
            } catch (Exception e) {
                ErrorHandlingUtils.logMetricsCalculationError("缓存性能指标", cacheName, e);
            }
        }
    }

    /**
     * 获取业务指标摘要
     */
    public Map<String, Object> getBusinessMetricsSummary() {
        Map<String, Object> summary = new HashMap<>();
        
        try {
            // 服务指标摘要
            Map<String, Object> serviceSummary = new HashMap<>();
            for (Map.Entry<String, ServiceStats> entry : serviceStats.entrySet()) {
                String serviceName = entry.getKey();
                ServiceStats stats = entry.getValue();
                
                Map<String, Object> serviceInfo = new HashMap<>();
                serviceInfo.put("totalCalls", stats.totalCalls.get());
                serviceInfo.put("successRate", stats.totalCalls.get() > 0 ? 
                    (double) stats.successfulCalls.get() / stats.totalCalls.get() * 100 : 0);
                serviceInfo.put("avgResponseTime", stats.totalCalls.get() > 0 ? 
                    (double) stats.totalResponseTime.get() / stats.totalCalls.get() : 0);
                serviceInfo.put("lastCallTime", stats.lastCallTime);
                
                serviceSummary.put(serviceName, serviceInfo);
            }
            summary.put("services", serviceSummary);
            
            // 数据质量摘要
            Map<String, Object> dataQualitySummary = new HashMap<>();
            for (Map.Entry<String, DataQualityStats> entry : dataQualityStats.entrySet()) {
                String dataSource = entry.getKey();
                DataQualityStats stats = entry.getValue();
                
                Map<String, Object> qualityInfo = new HashMap<>();
                long totalRecords = stats.totalRecords.get();
                qualityInfo.put("totalRecords", totalRecords);
                qualityInfo.put("completeness", totalRecords > 0 ? 
                    (double) stats.completeRecords.get() / totalRecords * 100 : 0);
                qualityInfo.put("accuracy", totalRecords > 0 ? 
                    (double) stats.accurateRecords.get() / totalRecords * 100 : 0);
                qualityInfo.put("lastUpdateTime", stats.lastUpdateTime);
                
                dataQualitySummary.put(dataSource, qualityInfo);
            }
            summary.put("dataQuality", dataQualitySummary);
            
            // 缓存性能摘要
            Map<String, Object> cacheSummary = new HashMap<>();
            for (Map.Entry<String, CacheStats> entry : cacheStats.entrySet()) {
                String cacheName = entry.getKey();
                CacheStats stats = entry.getValue();
                
                Map<String, Object> cacheInfo = new HashMap<>();
                long totalAccess = stats.hitCount.get() + stats.missCount.get();
                cacheInfo.put("totalAccess", totalAccess);
                cacheInfo.put("hitRatio", totalAccess > 0 ? 
                    (double) stats.hitCount.get() / totalAccess : 0);
                cacheInfo.put("evictionCount", stats.evictionCount.get());
                cacheInfo.put("lastAccessTime", stats.lastAccessTime);
                
                cacheSummary.put(cacheName, cacheInfo);
            }
            summary.put("cache", cacheSummary);
            
            summary.put("timestamp", Instant.now());
            
        } catch (Exception e) {
            ErrorHandlingUtils.executeWithErrorHandling(() -> {}, summary, "获取业务指标摘要");
        }
        
        return summary;
    }

    /**
     * 服务统计数据
     */
    private static class ServiceStats {
        final AtomicLong totalCalls = new AtomicLong(0);
        final AtomicLong successfulCalls = new AtomicLong(0);
        final AtomicLong failedCalls = new AtomicLong(0);
        final AtomicLong slowCalls = new AtomicLong(0);
        final AtomicLong totalResponseTime = new AtomicLong(0);
        final AtomicLong maxResponseTime = new AtomicLong(0);
        volatile Instant lastCallTime = Instant.now();
    }

    /**
     * 数据质量统计数据
     */
    private static class DataQualityStats {
        final AtomicLong totalRecords = new AtomicLong(0);
        final AtomicLong completeRecords = new AtomicLong(0);
        final AtomicLong incompleteRecords = new AtomicLong(0);
        final AtomicLong accurateRecords = new AtomicLong(0);
        final AtomicLong inaccurateRecords = new AtomicLong(0);
        final AtomicLong duplicateRecords = new AtomicLong(0);
        final AtomicLong invalidRecords = new AtomicLong(0);
        final AtomicLong freshRecords = new AtomicLong(0);
        final AtomicLong staleRecords = new AtomicLong(0);
        volatile Instant lastUpdateTime = Instant.now();
    }

    /**
     * 缓存统计数据
     */
    private static class CacheStats {
        final AtomicLong hitCount = new AtomicLong(0);
        final AtomicLong missCount = new AtomicLong(0);
        final AtomicLong evictionCount = new AtomicLong(0);
        final AtomicLong totalLoadTime = new AtomicLong(0);
        final AtomicLong loadCount = new AtomicLong(0);
        volatile Instant lastAccessTime = Instant.now();
    }

    /**
     * 数据质量事件类型
     */
    public enum DataQualityEventType {
        COMPLETE_RECORD,
        INCOMPLETE_RECORD,
        ACCURATE_RECORD,
        INACCURATE_RECORD,
        DUPLICATE_RECORD,
        INVALID_RECORD,
        FRESH_RECORD,
        STALE_RECORD
    }

    /**
     * 缓存事件类型
     */
    public enum CacheEventType {
        HIT,
        MISS,
        EVICTION
    }

    /**
     * 清理资源
     */
    @PreDestroy
    public void cleanup() {
        log.info("正在清理BusinessMetricsService资源...");
        
        ResourceCleanupUtils.safeCleanup(() -> {
            serviceStats.clear();
            dataQualityStats.clear();
            cacheStats.clear();
        }, "清理统计数据");
        
        log.info("BusinessMetricsService资源清理完成");
    }
}