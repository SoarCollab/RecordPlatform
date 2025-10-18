package cn.flying.monitor.common.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 综合指标收集服务
 * 负责收集和聚合各种系统指标
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsCollectorService {

    private final CustomMetricsService customMetricsService;
    
    // 查询统计数据
    private final Map<String, QueryStats> queryStats = new ConcurrentHashMap<>();
    
    // 系统资源统计
    private final Map<String, ResourceStats> resourceStats = new ConcurrentHashMap<>();

    /**
     * 记录查询执行
     */
    public void recordQueryExecution(String queryType, Duration executionTime, boolean success, boolean isSlow) {
        QueryStats stats = queryStats.computeIfAbsent(queryType, k -> new QueryStats());
        
        stats.totalQueries.incrementAndGet();
        if (success) {
            stats.successfulQueries.incrementAndGet();
        } else {
            stats.failedQueries.incrementAndGet();
        }
        
        if (isSlow) {
            stats.slowQueries.incrementAndGet();
        }
        
        long executionTimeMs = executionTime.toMillis();
        stats.totalExecutionTime.addAndGet(executionTimeMs);
        
        if (executionTimeMs > stats.maxExecutionTime.get()) {
            stats.maxExecutionTime.set(executionTimeMs);
        }
        
        stats.lastQueryTime = Instant.now();
        
        log.debug("记录查询执行: 类型={}, 执行时间={}ms, 成功={}, 慢查询={}", 
                 queryType, executionTimeMs, success, isSlow);
    }

    /**
     * 记录系统资源使用情况
     */
    public void recordResourceUsage(String resourceType, double usagePercent, long totalAmount, long usedAmount) {
        ResourceStats stats = resourceStats.computeIfAbsent(resourceType, k -> new ResourceStats());
        
        stats.currentUsagePercent = usagePercent;
        stats.totalAmount = totalAmount;
        stats.usedAmount = usedAmount;
        stats.lastUpdateTime = Instant.now();
        
        // 记录峰值使用率
        if (usagePercent > stats.peakUsagePercent) {
            stats.peakUsagePercent = usagePercent;
            stats.peakUsageTime = Instant.now();
        }
        
        // 统计高使用率次数
        if (usagePercent > 80) {
            stats.highUsageCount.incrementAndGet();
        }
        
        log.debug("记录资源使用: 类型={}, 使用率={:.2f}%, 总量={}, 已用={}", 
                 resourceType, usagePercent, totalAmount, usedAmount);
    }

    /**
     * 定期发布查询统计指标（每2分钟执行一次）
     */
    @Scheduled(fixedRate = 120000)
    public void publishQueryStatistics() {
        try {
            log.debug("开始发布查询统计指标");
            
            for (Map.Entry<String, QueryStats> entry : queryStats.entrySet()) {
                String queryType = entry.getKey();
                QueryStats stats = entry.getValue();
                
                try {
                    int totalQueries = stats.totalQueries.get();
                    if (totalQueries == 0) continue;
                    
                    int successfulQueries = stats.successfulQueries.get();
                    int failedQueries = stats.failedQueries.get();
                    int slowQueries = stats.slowQueries.get();
                    
                    Duration avgExecutionTime = Duration.ofMillis(
                        stats.totalExecutionTime.get() / totalQueries);
                    Duration maxExecutionTime = Duration.ofMillis(stats.maxExecutionTime.get());
                    
                    // 发布查询统计指标
                    customMetricsService.recordQueryStatistics(queryType, totalQueries, 
                        successfulQueries, failedQueries, avgExecutionTime, maxExecutionTime, slowQueries);
                    
                    // 重置统计数据（保留历史峰值）
                    resetQueryStats(stats);
                    
                } catch (Exception e) {
                    log.error("发布查询类型 {} 的统计指标异常", queryType, e);
                }
            }
            
            log.debug("查询统计指标发布完成");
            
        } catch (Exception e) {
            log.error("发布查询统计指标异常", e);
        }
    }

    /**
     * 定期发布资源使用指标（每30秒执行一次）
     */
    @Scheduled(fixedRate = 30000)
    public void publishResourceMetrics() {
        try {
            log.debug("开始发布资源使用指标");
            
            for (Map.Entry<String, ResourceStats> entry : resourceStats.entrySet()) {
                String resourceType = entry.getKey();
                ResourceStats stats = entry.getValue();
                
                try {
                    // 发布当前使用率
                    customMetricsService.createGauge(
                        "monitor.resource.usage.percent",
                        "资源使用率",
                        stats,
                        () -> stats.currentUsagePercent,
                        "resource", resourceType
                    );
                    
                    // 发布峰值使用率
                    customMetricsService.createGauge(
                        "monitor.resource.peak.usage.percent",
                        "资源峰值使用率",
                        stats,
                        () -> stats.peakUsagePercent,
                        "resource", resourceType
                    );
                    
                    // 发布总量和已用量
                    customMetricsService.createGauge(
                        "monitor.resource.total.amount",
                        "资源总量",
                        stats,
                        () -> stats.totalAmount,
                        "resource", resourceType
                    );
                    
                    customMetricsService.createGauge(
                        "monitor.resource.used.amount",
                        "资源已用量",
                        stats,
                        () -> stats.usedAmount,
                        "resource", resourceType
                    );
                    
                    // 发布高使用率次数
                    customMetricsService.createCounter(
                        "monitor.resource.high.usage.count",
                        "资源高使用率次数",
                        "resource", resourceType
                    ).increment(stats.highUsageCount.getAndSet(0));
                    
                } catch (Exception e) {
                    log.error("发布资源类型 {} 的使用指标异常", resourceType, e);
                }
            }
            
            log.debug("资源使用指标发布完成");
            
        } catch (Exception e) {
            log.error("发布资源使用指标异常", e);
        }
    }

    /**
     * 收集JVM指标
     */
    @Scheduled(fixedRate = 60000) // 每分钟执行一次
    public void collectJvmMetrics() {
        try {
            Runtime runtime = Runtime.getRuntime();
            
            // 内存使用情况
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            long maxMemory = runtime.maxMemory();
            
            double memoryUsagePercent = (double) usedMemory / maxMemory * 100;
            recordResourceUsage("jvm_memory", memoryUsagePercent, maxMemory, usedMemory);
            
            // 垃圾回收信息
            java.lang.management.MemoryMXBean memoryBean = 
                java.lang.management.ManagementFactory.getMemoryMXBean();
            
            java.lang.management.MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
            java.lang.management.MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
            
            // 堆内存使用率
            double heapUsagePercent = (double) heapUsage.getUsed() / heapUsage.getMax() * 100;
            recordResourceUsage("jvm_heap", heapUsagePercent, heapUsage.getMax(), heapUsage.getUsed());
            
            // 非堆内存使用率
            if (nonHeapUsage.getMax() > 0) {
                double nonHeapUsagePercent = (double) nonHeapUsage.getUsed() / nonHeapUsage.getMax() * 100;
                recordResourceUsage("jvm_non_heap", nonHeapUsagePercent, nonHeapUsage.getMax(), nonHeapUsage.getUsed());
            }
            
            // 线程数量
            java.lang.management.ThreadMXBean threadBean = 
                java.lang.management.ManagementFactory.getThreadMXBean();
            int threadCount = threadBean.getThreadCount();
            int peakThreadCount = threadBean.getPeakThreadCount();
            
            customMetricsService.createGauge(
                "monitor.jvm.thread.count",
                "JVM线程数量",
                threadCount,
                () -> (double) threadCount
            );
            
            customMetricsService.createGauge(
                "monitor.jvm.thread.peak.count",
                "JVM峰值线程数量",
                peakThreadCount,
                () -> (double) peakThreadCount
            );
            
            log.debug("JVM指标收集完成: 内存使用率={:.2f}%, 堆使用率={:.2f}%, 线程数={}", 
                     memoryUsagePercent, heapUsagePercent, threadCount);
            
        } catch (Exception e) {
            log.error("收集JVM指标异常", e);
        }
    }

    /**
     * 获取指标收集摘要
     */
    public Map<String, Object> getMetricsCollectionSummary() {
        Map<String, Object> summary = new HashMap<>();
        
        try {
            // 查询统计摘要
            Map<String, Object> querySummary = new HashMap<>();
            for (Map.Entry<String, QueryStats> entry : queryStats.entrySet()) {
                String queryType = entry.getKey();
                QueryStats stats = entry.getValue();
                
                Map<String, Object> queryInfo = new HashMap<>();
                int totalQueries = stats.totalQueries.get();
                queryInfo.put("totalQueries", totalQueries);
                queryInfo.put("successRate", totalQueries > 0 ? 
                    (double) stats.successfulQueries.get() / totalQueries * 100 : 0);
                queryInfo.put("avgExecutionTime", totalQueries > 0 ? 
                    stats.totalExecutionTime.get() / totalQueries : 0);
                queryInfo.put("slowQueryRate", totalQueries > 0 ? 
                    (double) stats.slowQueries.get() / totalQueries * 100 : 0);
                queryInfo.put("lastQueryTime", stats.lastQueryTime);
                
                querySummary.put(queryType, queryInfo);
            }
            summary.put("queries", querySummary);
            
            // 资源使用摘要
            Map<String, Object> resourceSummary = new HashMap<>();
            for (Map.Entry<String, ResourceStats> entry : resourceStats.entrySet()) {
                String resourceType = entry.getKey();
                ResourceStats stats = entry.getValue();
                
                Map<String, Object> resourceInfo = new HashMap<>();
                resourceInfo.put("currentUsage", stats.currentUsagePercent);
                resourceInfo.put("peakUsage", stats.peakUsagePercent);
                resourceInfo.put("peakUsageTime", stats.peakUsageTime);
                resourceInfo.put("totalAmount", stats.totalAmount);
                resourceInfo.put("usedAmount", stats.usedAmount);
                resourceInfo.put("lastUpdateTime", stats.lastUpdateTime);
                
                resourceSummary.put(resourceType, resourceInfo);
            }
            summary.put("resources", resourceSummary);
            
            summary.put("timestamp", Instant.now());
            
        } catch (Exception e) {
            log.error("获取指标收集摘要异常", e);
            summary.put("error", e.getMessage());
        }
        
        return summary;
    }

    /**
     * 重置查询统计数据
     */
    private void resetQueryStats(QueryStats stats) {
        stats.totalQueries.set(0);
        stats.successfulQueries.set(0);
        stats.failedQueries.set(0);
        stats.slowQueries.set(0);
        stats.totalExecutionTime.set(0);
        stats.maxExecutionTime.set(0);
        // 保留lastQueryTime不重置
    }

    /**
     * 查询统计数据
     */
    private static class QueryStats {
        final AtomicInteger totalQueries = new AtomicInteger(0);
        final AtomicInteger successfulQueries = new AtomicInteger(0);
        final AtomicInteger failedQueries = new AtomicInteger(0);
        final AtomicInteger slowQueries = new AtomicInteger(0);
        final AtomicLong totalExecutionTime = new AtomicLong(0);
        final AtomicLong maxExecutionTime = new AtomicLong(0);
        volatile Instant lastQueryTime = Instant.now();
    }

    /**
     * 资源统计数据
     */
    private static class ResourceStats {
        volatile double currentUsagePercent = 0.0;
        volatile double peakUsagePercent = 0.0;
        volatile long totalAmount = 0;
        volatile long usedAmount = 0;
        volatile Instant lastUpdateTime = Instant.now();
        volatile Instant peakUsageTime = Instant.now();
        final AtomicInteger highUsageCount = new AtomicInteger(0);
    }
}