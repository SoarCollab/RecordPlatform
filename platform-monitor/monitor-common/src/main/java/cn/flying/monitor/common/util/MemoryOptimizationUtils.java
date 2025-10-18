package cn.flying.monitor.common.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存优化工具类
 * 提供内存监控、优化建议和自动清理功能
 */
@Slf4j
@Component
public class MemoryOptimizationUtils {

    private final MemoryMXBean memoryMXBean;
    private final List<GarbageCollectorMXBean> gcBeans;
    private final ConcurrentHashMap<String, AtomicLong> memoryMetrics;
    
    // 内存阈值配置
    private static final double HIGH_MEMORY_THRESHOLD = 0.85; // 85%
    private static final double CRITICAL_MEMORY_THRESHOLD = 0.95; // 95%
    private static final long MIN_FREE_MEMORY_MB = 100; // 100MB
    
    // GC统计
    private long lastGcCollectionCount = 0;
    private long lastGcCollectionTime = 0;

    public MemoryOptimizationUtils() {
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
        this.gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        this.memoryMetrics = new ConcurrentHashMap<>();
        
        initializeMetrics();
    }

    private void initializeMetrics() {
        memoryMetrics.put("heap.used", new AtomicLong(0));
        memoryMetrics.put("heap.max", new AtomicLong(0));
        memoryMetrics.put("nonheap.used", new AtomicLong(0));
        memoryMetrics.put("gc.collections", new AtomicLong(0));
        memoryMetrics.put("gc.time", new AtomicLong(0));
    }

    /**
     * 定期内存监控和优化（每2分钟执行一次）
     */
    @Scheduled(fixedRate = 120000)
    public void performMemoryOptimization() {
        try {
            MemoryStatus status = getCurrentMemoryStatus();
            updateMemoryMetrics(status);
            
            log.debug("内存状态: {}", status);
            
            // 检查内存使用情况并采取相应措施
            if (status.getHeapUsagePercent() > CRITICAL_MEMORY_THRESHOLD) {
                handleCriticalMemoryUsage(status);
            } else if (status.getHeapUsagePercent() > HIGH_MEMORY_THRESHOLD) {
                handleHighMemoryUsage(status);
            }
            
            // 检查GC频率
            checkGarbageCollectionFrequency();
            
        } catch (Exception e) {
            log.error("内存优化过程中发生异常", e);
        }
    }

    /**
     * 获取当前内存状态
     */
    public MemoryStatus getCurrentMemoryStatus() {
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();
        
        long heapUsed = heapUsage.getUsed();
        long heapMax = heapUsage.getMax();
        long heapCommitted = heapUsage.getCommitted();
        long nonHeapUsed = nonHeapUsage.getUsed();
        long nonHeapMax = nonHeapUsage.getMax();
        
        double heapUsagePercent = heapMax > 0 ? (double) heapUsed / heapMax : 0;
        double nonHeapUsagePercent = nonHeapMax > 0 ? (double) nonHeapUsed / nonHeapMax : 0;
        
        return new MemoryStatus(heapUsed, heapMax, heapCommitted, heapUsagePercent,
                               nonHeapUsed, nonHeapMax, nonHeapUsagePercent);
    }

    /**
     * 处理关键内存使用情况
     */
    private void handleCriticalMemoryUsage(MemoryStatus status) {
        log.error("内存使用率达到临界水平: {:.2f}%, 已用: {} MB, 最大: {} MB", 
                 status.getHeapUsagePercent() * 100,
                 status.getHeapUsed() / 1024 / 1024,
                 status.getHeapMax() / 1024 / 1024);
        
        // 强制垃圾回收
        forceGarbageCollection();
        
        // 记录内存转储（如果配置了）
        suggestMemoryDump();
        
        // 发送告警
        log.error("建议立即检查内存泄漏或增加堆内存大小");
    }

    /**
     * 处理高内存使用情况
     */
    private void handleHighMemoryUsage(MemoryStatus status) {
        log.warn("内存使用率较高: {:.2f}%, 已用: {} MB, 最大: {} MB", 
                status.getHeapUsagePercent() * 100,
                status.getHeapUsed() / 1024 / 1024,
                status.getHeapMax() / 1024 / 1024);
        
        // 建议垃圾回收
        suggestGarbageCollection();
        
        // 提供优化建议
        provideOptimizationSuggestions(status);
    }

    /**
     * 检查垃圾回收频率
     */
    private void checkGarbageCollectionFrequency() {
        long totalCollections = 0;
        long totalTime = 0;
        
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            totalCollections += gcBean.getCollectionCount();
            totalTime += gcBean.getCollectionTime();
        }
        
        long collectionsSinceLastCheck = totalCollections - lastGcCollectionCount;
        long timeSinceLastCheck = totalTime - lastGcCollectionTime;
        
        if (collectionsSinceLastCheck > 10) { // 2分钟内超过10次GC
            log.warn("GC频率过高: 最近2分钟内发生了 {} 次垃圾回收，耗时 {} ms", 
                    collectionsSinceLastCheck, timeSinceLastCheck);
        }
        
        lastGcCollectionCount = totalCollections;
        lastGcCollectionTime = totalTime;
        
        // 更新GC指标
        memoryMetrics.get("gc.collections").set(totalCollections);
        memoryMetrics.get("gc.time").set(totalTime);
    }

    /**
     * 强制垃圾回收
     */
    public void forceGarbageCollection() {
        log.info("执行强制垃圾回收...");
        long beforeUsed = memoryMXBean.getHeapMemoryUsage().getUsed();
        
        System.gc();
        System.runFinalization();
        
        // 等待一小段时间让GC完成
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        long afterUsed = memoryMXBean.getHeapMemoryUsage().getUsed();
        long freedMemory = beforeUsed - afterUsed;
        
        log.info("垃圾回收完成，释放内存: {} MB", freedMemory / 1024 / 1024);
    }

    /**
     * 建议垃圾回收
     */
    private void suggestGarbageCollection() {
        log.debug("建议执行垃圾回收以释放内存");
        System.gc();
    }

    /**
     * 建议内存转储
     */
    private void suggestMemoryDump() {
        log.error("建议生成内存转储文件进行分析");
        // 这里可以集成内存转储工具，如jmap
    }

    /**
     * 提供优化建议
     */
    private void provideOptimizationSuggestions(MemoryStatus status) {
        StringBuilder suggestions = new StringBuilder("内存优化建议:\n");
        
        if (status.getHeapUsagePercent() > 0.8) {
            suggestions.append("- 考虑增加堆内存大小 (-Xmx)\n");
        }
        
        if (status.getNonHeapUsagePercent() > 0.8) {
            suggestions.append("- 考虑增加元空间大小 (-XX:MaxMetaspaceSize)\n");
        }
        
        suggestions.append("- 检查是否存在内存泄漏\n");
        suggestions.append("- 优化数据结构和缓存策略\n");
        suggestions.append("- 考虑使用对象池减少GC压力\n");
        
        log.info(suggestions.toString());
    }

    /**
     * 更新内存指标
     */
    private void updateMemoryMetrics(MemoryStatus status) {
        memoryMetrics.get("heap.used").set(status.getHeapUsed());
        memoryMetrics.get("heap.max").set(status.getHeapMax());
        memoryMetrics.get("nonheap.used").set(status.getNonHeapUsed());
    }

    /**
     * 获取内存指标
     */
    public ConcurrentHashMap<String, AtomicLong> getMemoryMetrics() {
        return new ConcurrentHashMap<>(memoryMetrics);
    }

    /**
     * 检查内存是否健康
     */
    public boolean isMemoryHealthy() {
        MemoryStatus status = getCurrentMemoryStatus();
        return status.getHeapUsagePercent() < HIGH_MEMORY_THRESHOLD &&
               status.getNonHeapUsagePercent() < HIGH_MEMORY_THRESHOLD;
    }

    /**
     * 内存状态数据类
     */
    public static class MemoryStatus {
        private final long heapUsed;
        private final long heapMax;
        private final long heapCommitted;
        private final double heapUsagePercent;
        private final long nonHeapUsed;
        private final long nonHeapMax;
        private final double nonHeapUsagePercent;

        public MemoryStatus(long heapUsed, long heapMax, long heapCommitted, double heapUsagePercent,
                           long nonHeapUsed, long nonHeapMax, double nonHeapUsagePercent) {
            this.heapUsed = heapUsed;
            this.heapMax = heapMax;
            this.heapCommitted = heapCommitted;
            this.heapUsagePercent = heapUsagePercent;
            this.nonHeapUsed = nonHeapUsed;
            this.nonHeapMax = nonHeapMax;
            this.nonHeapUsagePercent = nonHeapUsagePercent;
        }

        // Getters
        public long getHeapUsed() { return heapUsed; }
        public long getHeapMax() { return heapMax; }
        public long getHeapCommitted() { return heapCommitted; }
        public double getHeapUsagePercent() { return heapUsagePercent; }
        public long getNonHeapUsed() { return nonHeapUsed; }
        public long getNonHeapMax() { return nonHeapMax; }
        public double getNonHeapUsagePercent() { return nonHeapUsagePercent; }

        @Override
        public String toString() {
            return String.format("Memory[Heap: %d/%d MB (%.2f%%), NonHeap: %d/%d MB (%.2f%%), Committed: %d MB]",
                    heapUsed / 1024 / 1024, heapMax / 1024 / 1024, heapUsagePercent * 100,
                    nonHeapUsed / 1024 / 1024, nonHeapMax / 1024 / 1024, nonHeapUsagePercent * 100,
                    heapCommitted / 1024 / 1024);
        }
    }
}