package cn.flying.monitor.common.pool;

import cn.flying.monitor.common.util.ResourceCleanupUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.apache.hc.core5.util.TimeValue;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 优化的HTTP连接池管理器
 * 提供连接池监控、自动清理和性能优化功能
 */
@Slf4j
@Component
public class OptimizedConnectionPoolManager implements DisposableBean {

    private PoolingHttpClientConnectionManager connectionManager;
    private CloseableHttpClient httpClient;
    private ScheduledExecutorService monitoringExecutor;
    
    // 连接池配置
    private static final int DEFAULT_MAX_TOTAL = 200;
    private static final int DEFAULT_MAX_PER_ROUTE = 50;
    private static final int CONNECTION_TIMEOUT_MS = 5000;
    private static final int SOCKET_TIMEOUT_MS = 30000;
    private static final int CONNECTION_REQUEST_TIMEOUT_MS = 3000;
    private static final int VALIDATE_AFTER_INACTIVITY_MS = 30000;
    private static final int EVICT_IDLE_CONNECTIONS_SECONDS = 60;
    
    // 监控配置
    private static final int MONITORING_INTERVAL_SECONDS = 30;
    private static final double HIGH_USAGE_THRESHOLD = 0.8;

    public OptimizedConnectionPoolManager() {
        initialize();
    }

    private void initialize() {
        try {
            log.info("初始化优化的HTTP连接池管理器");
            
            // 创建连接池管理器
            connectionManager = new PoolingHttpClientConnectionManager();
            connectionManager.setMaxTotal(DEFAULT_MAX_TOTAL);
            connectionManager.setDefaultMaxPerRoute(DEFAULT_MAX_PER_ROUTE);
            connectionManager.setValidateAfterInactivity(Timeout.ofMilliseconds(VALIDATE_AFTER_INACTIVITY_MS));

            // 配置请求超时
            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectionRequestTimeout(Timeout.ofMilliseconds(CONNECTION_REQUEST_TIMEOUT_MS))
                    .setConnectTimeout(Timeout.ofMilliseconds(CONNECTION_TIMEOUT_MS))
                    .setResponseTimeout(Timeout.ofMilliseconds(SOCKET_TIMEOUT_MS))
                    .build();

            // 创建HTTP客户端
            httpClient = HttpClients.custom()
                    .setConnectionManager(connectionManager)
                    .setDefaultRequestConfig(requestConfig)
                    .evictIdleConnections(TimeValue.ofSeconds(EVICT_IDLE_CONNECTIONS_SECONDS))
                    .build();

            // 启动连接池监控
            startConnectionPoolMonitoring();
            
            log.info("HTTP连接池管理器初始化完成 - 最大连接数: {}, 每路由最大连接数: {}", 
                    DEFAULT_MAX_TOTAL, DEFAULT_MAX_PER_ROUTE);
            
        } catch (Exception e) {
            log.error("初始化HTTP连接池管理器失败", e);
            throw new RuntimeException("Failed to initialize connection pool manager", e);
        }
    }

    /**
     * 获取HTTP客户端
     */
    public CloseableHttpClient getHttpClient() {
        if (httpClient == null) {
            throw new IllegalStateException("HTTP客户端未初始化");
        }
        return httpClient;
    }

    /**
     * 获取连接池统计信息
     */
    public ConnectionPoolStats getConnectionPoolStats() {
        if (connectionManager == null) {
            return new ConnectionPoolStats(0, 0, 0, 0, 0);
        }

        int maxTotal = connectionManager.getMaxTotal();
        int maxPerRoute = connectionManager.getDefaultMaxPerRoute();
        int totalStats = connectionManager.getTotalStats().getLeased() + 
                        connectionManager.getTotalStats().getPending() + 
                        connectionManager.getTotalStats().getAvailable();
        int leased = connectionManager.getTotalStats().getLeased();
        int available = connectionManager.getTotalStats().getAvailable();

        return new ConnectionPoolStats(maxTotal, maxPerRoute, totalStats, leased, available);
    }

    /**
     * 启动连接池监控
     */
    private void startConnectionPoolMonitoring() {
        monitoringExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ConnectionPool-Monitor");
            t.setDaemon(true);
            return t;
        });

        monitoringExecutor.scheduleAtFixedRate(this::monitorConnectionPool, 
                MONITORING_INTERVAL_SECONDS, MONITORING_INTERVAL_SECONDS, TimeUnit.SECONDS);
        
        log.debug("连接池监控已启动，监控间隔: {}秒", MONITORING_INTERVAL_SECONDS);
    }

    /**
     * 监控连接池状态
     */
    private void monitorConnectionPool() {
        try {
            ConnectionPoolStats stats = getConnectionPoolStats();
            
            // 计算使用率
            double usageRate = stats.getMaxTotal() > 0 ? 
                    (double) stats.getLeased() / stats.getMaxTotal() : 0;
            
            log.debug("连接池状态: {}", stats);
            
            // 检查高使用率警告
            if (usageRate > HIGH_USAGE_THRESHOLD) {
                log.warn("连接池使用率过高: {:.2f}%, 当前租用: {}, 最大连接数: {}", 
                        usageRate * 100, stats.getLeased(), stats.getMaxTotal());
            }
            
            // 检查内存使用情况
            if (ResourceCleanupUtils.isMemoryUsageHigh(0.85)) {
                log.warn("内存使用率过高，建议检查连接池配置: {}", 
                        ResourceCleanupUtils.getMemoryUsage());
            }
            
        } catch (Exception e) {
            log.error("监控连接池状态时发生异常", e);
        }
    }

    /**
     * 动态调整连接池大小
     */
    public void adjustPoolSize(int maxTotal, int maxPerRoute) {
        if (connectionManager != null) {
            try {
                log.info("调整连接池大小: 最大连接数 {} -> {}, 每路由最大连接数 {} -> {}", 
                        connectionManager.getMaxTotal(), maxTotal,
                        connectionManager.getDefaultMaxPerRoute(), maxPerRoute);
                
                connectionManager.setMaxTotal(maxTotal);
                connectionManager.setDefaultMaxPerRoute(maxPerRoute);
                
                log.info("连接池大小调整完成");
            } catch (Exception e) {
                log.error("调整连接池大小失败", e);
            }
        }
    }

    /**
     * 清理空闲连接
     */
    public void evictIdleConnections() {
        if (connectionManager != null) {
            try {
                connectionManager.closeExpired();
                connectionManager.closeIdle(Timeout.ofSeconds(EVICT_IDLE_CONNECTIONS_SECONDS));
                log.debug("已清理空闲连接");
            } catch (Exception e) {
                log.error("清理空闲连接失败", e);
            }
        }
    }

    /**
     * 获取连接池健康状态
     */
    public boolean isHealthy() {
        if (connectionManager == null || httpClient == null) {
            return false;
        }
        
        try {
            ConnectionPoolStats stats = getConnectionPoolStats();
            double usageRate = stats.getMaxTotal() > 0 ? 
                    (double) stats.getLeased() / stats.getMaxTotal() : 0;
            
            // 连接池健康标准：使用率 < 90%，有可用连接
            return usageRate < 0.9 && stats.getAvailable() > 0;
        } catch (Exception e) {
            log.error("检查连接池健康状态失败", e);
            return false;
        }
    }

    @PreDestroy
    @Override
    public void destroy() {
        log.info("正在关闭HTTP连接池管理器");
        
        // 停止监控
        ResourceCleanupUtils.shutdownExecutorService(monitoringExecutor, 5);
        
        // 关闭HTTP客户端
        ResourceCleanupUtils.closeQuietly(httpClient);
        
        // 关闭连接管理器
        ResourceCleanupUtils.closeQuietly(connectionManager);
        
        log.info("HTTP连接池管理器已关闭");
    }

    /**
     * 连接池统计信息
     */
    public static class ConnectionPoolStats {
        private final int maxTotal;
        private final int maxPerRoute;
        private final int total;
        private final int leased;
        private final int available;

        public ConnectionPoolStats(int maxTotal, int maxPerRoute, int total, int leased, int available) {
            this.maxTotal = maxTotal;
            this.maxPerRoute = maxPerRoute;
            this.total = total;
            this.leased = leased;
            this.available = available;
        }

        public int getMaxTotal() { return maxTotal; }
        public int getMaxPerRoute() { return maxPerRoute; }
        public int getTotal() { return total; }
        public int getLeased() { return leased; }
        public int getAvailable() { return available; }

        public double getUsageRate() {
            return maxTotal > 0 ? (double) leased / maxTotal : 0;
        }

        @Override
        public String toString() {
            return String.format("ConnectionPool[Max: %d, PerRoute: %d, Total: %d, Leased: %d, Available: %d, Usage: %.2f%%]",
                    maxTotal, maxPerRoute, total, leased, available, getUsageRate() * 100);
        }
    }
}