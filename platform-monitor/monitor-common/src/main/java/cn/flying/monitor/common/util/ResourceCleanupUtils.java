package cn.flying.monitor.common.util;

import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 资源清理工具类
 * 提供统一的资源清理方法，确保资源正确释放
 */
@Slf4j
public class ResourceCleanupUtils {

    /**
     * 安全关闭Closeable资源
     */
    public static void closeQuietly(Closeable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception e) {
                log.warn("关闭资源时发生异常", e);
            }
        }
    }

    /**
     * 安全关闭多个Closeable资源
     */
    public static void closeQuietly(Closeable... resources) {
        if (resources != null) {
            for (Closeable resource : resources) {
                closeQuietly(resource);
            }
        }
    }

    /**
     * 优雅关闭ExecutorService
     */
    public static void shutdownExecutorService(ExecutorService executorService, long timeoutSeconds) {
        if (executorService != null && !executorService.isShutdown()) {
            try {
                log.debug("正在关闭ExecutorService...");
                executorService.shutdown();
                
                if (!executorService.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                    log.warn("ExecutorService未在{}秒内正常关闭，强制关闭", timeoutSeconds);
                    executorService.shutdownNow();
                    
                    if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                        log.error("ExecutorService强制关闭失败");
                    }
                }
                log.debug("ExecutorService已成功关闭");
            } catch (InterruptedException e) {
                log.warn("等待ExecutorService关闭时被中断", e);
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 安全执行清理操作
     */
    public static void safeCleanup(Runnable cleanupOperation, String operationName) {
        try {
            if (cleanupOperation != null) {
                cleanupOperation.run();
            }
        } catch (Exception e) {
            log.warn("执行清理操作 {} 时发生异常", operationName, e);
        }
    }

    /**
     * 清理系统资源（强制GC）
     */
    public static void forceGarbageCollection() {
        try {
            System.gc();
            System.runFinalization();
            log.debug("已执行垃圾回收");
        } catch (Exception e) {
            log.warn("执行垃圾回收时发生异常", e);
        }
    }

    /**
     * 获取内存使用情况
     */
    public static MemoryUsage getMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();
        
        return new MemoryUsage(totalMemory, freeMemory, usedMemory, maxMemory);
    }

    /**
     * 检查内存使用率是否过高
     */
    public static boolean isMemoryUsageHigh(double threshold) {
        MemoryUsage usage = getMemoryUsage();
        double usagePercent = (double) usage.getUsedMemory() / usage.getMaxMemory();
        return usagePercent > threshold;
    }

    /**
     * 内存使用情况数据类
     */
    public static class MemoryUsage {
        private final long totalMemory;
        private final long freeMemory;
        private final long usedMemory;
        private final long maxMemory;

        public MemoryUsage(long totalMemory, long freeMemory, long usedMemory, long maxMemory) {
            this.totalMemory = totalMemory;
            this.freeMemory = freeMemory;
            this.usedMemory = usedMemory;
            this.maxMemory = maxMemory;
        }

        public long getTotalMemory() { return totalMemory; }
        public long getFreeMemory() { return freeMemory; }
        public long getUsedMemory() { return usedMemory; }
        public long getMaxMemory() { return maxMemory; }
        
        public double getUsagePercent() {
            return maxMemory > 0 ? (double) usedMemory / maxMemory * 100 : 0;
        }

        @Override
        public String toString() {
            return String.format("Memory[Used: %d MB, Free: %d MB, Total: %d MB, Max: %d MB, Usage: %.2f%%]",
                    usedMemory / 1024 / 1024,
                    freeMemory / 1024 / 1024,
                    totalMemory / 1024 / 1024,
                    maxMemory / 1024 / 1024,
                    getUsagePercent());
        }
    }
}