package cn.flying.identity.gateway.pool;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * API网关HTTP连接池管理器
 * 提供高性能的HTTP连接池管理，支持动态配置和监控
 * <p>
 * 核心功能：
 * 1. 连接池生命周期管理
 * 2. 连接池参数动态调整
 * 3. 连接池使用统计和监控
 * 4. 自动清理空闲连接
 * 5. 连接池健康检查
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
@Component
public class ApiGatewayConnectionPoolManager {

    /**
     * 服务特定的连接池映射
     */
    private final Map<String, CloseableHttpClient> serviceHttpClients = new ConcurrentHashMap<>();

    /**
     * 连接池统计信息
     */
    private final Map<String, PoolStatistics> poolStatistics = new ConcurrentHashMap<>();

    /**
     * 连接池最大连接数
     */
    @Value("${api.gateway.pool.max-total:500}")
    private int maxTotal;

    /**
     * 每个路由最大连接数
     */
    @Value("${api.gateway.pool.max-per-route:50}")
    private int maxPerRoute;

    /**
     * 连接超时时间（毫秒）
     */
    @Value("${api.gateway.pool.connect-timeout:5000}")
    private int connectTimeout;

    /**
     * Socket超时时间（毫秒）
     */
    @Value("${api.gateway.pool.socket-timeout:30000}")
    private int socketTimeout;

    /**
     * 请求超时时间（毫秒）
     */
    @Value("${api.gateway.pool.request-timeout:30000}")
    private int requestTimeout;

    /**
     * 空闲连接存活时间（秒）
     */
    @Value("${api.gateway.pool.idle-timeout:60}")
    private int idleTimeout;

    /**
     * 连接池验证间隔（秒）
     */
    @Value("${api.gateway.pool.validate-interval:30}")
    private int validateInterval;

    /**
     * 主连接池管理器
     */
    private PoolingHttpClientConnectionManager connectionManager;

    /**
     * HTTP客户端实例
     */
    private CloseableHttpClient httpClient;

    /**
     * 监控线程池
     */
    private ScheduledExecutorService monitorExecutor;

    /**
     * 初始化连接池
     */
    @PostConstruct
    public void init() {
        log.info("初始化API网关连接池管理器...");

        // 创建连接池管理器
        connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(maxTotal);
        connectionManager.setDefaultMaxPerRoute(maxPerRoute);

        // 设置Socket配置
        SocketConfig socketConfig = SocketConfig.custom()
                .setSoTimeout(Timeout.ofMilliseconds(socketTimeout))
                .build();
        connectionManager.setDefaultSocketConfig(socketConfig);

        // 设置请求配置
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeout))
                .setResponseTimeout(Timeout.ofMilliseconds(requestTimeout))
                .build();

        // 创建HTTP客户端
        httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(idleTimeout))
                .build();

        // 启动监控线程
        startMonitoring();

        log.info("API网关连接池管理器初始化完成: maxTotal={}, maxPerRoute={}",
                maxTotal, maxPerRoute);
    }

    /**
     * 启动监控线程
     */
    private void startMonitoring() {
        monitorExecutor = Executors.newSingleThreadScheduledExecutor(
                r -> new Thread(r, "ConnectionPoolMonitor"));

        // 定期检查连接池状态
        monitorExecutor.scheduleWithFixedDelay(this::monitorPoolHealth,
                validateInterval, validateInterval, TimeUnit.SECONDS);

        // 定期清理空闲连接
        monitorExecutor.scheduleWithFixedDelay(this::evictIdleConnections,
                idleTimeout, idleTimeout, TimeUnit.SECONDS);
    }

    /**
     * 监控连接池健康状态
     */
    private void monitorPoolHealth() {
        try {
            var stats = connectionManager.getTotalStats();
            int available = stats.getAvailable();
            int leased = stats.getLeased();
            int pending = stats.getPending();
            int max = connectionManager.getMaxTotal();

            // 计算使用率
            double usage = (double) leased / max * 100;

            log.debug("连接池状态 - 可用: {}, 已用: {}, 等待: {}, 使用率: {}%",
                    available, leased, pending, usage);

            // 高使用率告警
            if (usage > 80) {
                log.warn("连接池使用率过高: {}%, 考虑增加连接池大小", usage);
            }

            // 等待连接过多告警
            if (pending > 10) {
                log.warn("连接池等待队列过长: {}, 可能存在性能问题", pending);
            }
        } catch (Exception e) {
            log.error("监控连接池健康状态失败", e);
        }
    }

    /**
     * 清理空闲连接
     */
    private void evictIdleConnections() {
        try {
            // 使用 connectionManager 的统计信息来监控，但不直接调用清理方法
            // 因为HttpClient已经配置了自动清理
            var stats = connectionManager.getTotalStats();
            log.debug("连接池空闲连接检查 - 可用: {}, 已用: {}",
                    stats.getAvailable(), stats.getLeased());
        } catch (Exception e) {
            log.error("检查空闲连接失败", e);
        }
    }

    /**
     * 获取默认HTTP客户端
     *
     * @return HTTP客户端实例
     */
    public CloseableHttpClient getDefaultHttpClient() {
        recordUsage("default");
        return httpClient;
    }

    /**
     * 记录连接池使用情况
     *
     * @param serviceName 服务名称
     */
    private void recordUsage(String serviceName) {
        poolStatistics.computeIfAbsent(serviceName, PoolStatistics::new)
                .recordRequest();
    }

    /**
     * 获取或创建服务专用的HTTP客户端
     * 支持为不同的后端服务创建独立的连接池
     *
     * @param serviceName 服务名称
     * @return 服务专用的HTTP客户端
     */
    public CloseableHttpClient getServiceHttpClient(String serviceName) {
        return serviceHttpClients.computeIfAbsent(serviceName, this::createServiceHttpClient);
    }

    /**
     * 创建服务专用的HTTP客户端
     *
     * @param serviceName 服务名称
     * @return 新的HTTP客户端实例
     */
    private CloseableHttpClient createServiceHttpClient(String serviceName) {
        log.info("为服务创建专用连接池: serviceName={}", serviceName);

        // 创建服务专用的连接池管理器
        PoolingHttpClientConnectionManager serviceConnectionManager =
                new PoolingHttpClientConnectionManager();
        serviceConnectionManager.setMaxTotal(maxPerRoute * 2);
        serviceConnectionManager.setDefaultMaxPerRoute(maxPerRoute);

        // 设置Socket配置
        SocketConfig socketConfig = SocketConfig.custom()
                .setSoTimeout(Timeout.ofMilliseconds(socketTimeout))
                .build();
        serviceConnectionManager.setDefaultSocketConfig(socketConfig);

        // 设置请求配置
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeout))
                .setResponseTimeout(Timeout.ofMilliseconds(requestTimeout))
                .build();

        // 创建HTTP客户端
        CloseableHttpClient serviceHttpClient = HttpClients.custom()
                .setConnectionManager(serviceConnectionManager)
                .setDefaultRequestConfig(requestConfig)
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(idleTimeout))
                .build();

        // 初始化统计信息
        poolStatistics.put(serviceName, new PoolStatistics(serviceName));

        return serviceHttpClient;
    }

    /**
     * 动态调整连接池大小
     *
     * @param newMaxTotal    新的最大连接数
     * @param newMaxPerRoute 新的每路由最大连接数
     */
    public void resizePool(int newMaxTotal, int newMaxPerRoute) {
        log.info("动态调整连接池大小: maxTotal {} -> {}, maxPerRoute {} -> {}",
                maxTotal, newMaxTotal, maxPerRoute, newMaxPerRoute);

        this.maxTotal = newMaxTotal;
        this.maxPerRoute = newMaxPerRoute;

        connectionManager.setMaxTotal(newMaxTotal);
        connectionManager.setDefaultMaxPerRoute(newMaxPerRoute);
    }

    /**
     * 获取连接池统计信息
     *
     * @return 统计信息映射
     */
    public Map<String, Object> getPoolStatistics() {
        Map<String, Object> stats = new HashMap<>();

        // 默认连接池统计
        stats.put("maxTotal", connectionManager.getMaxTotal());
        stats.put("defaultMaxPerRoute", connectionManager.getDefaultMaxPerRoute());
        stats.put("availableConnections", connectionManager.getTotalStats().getAvailable());
        stats.put("leasedConnections", connectionManager.getTotalStats().getLeased());
        stats.put("pendingConnections", connectionManager.getTotalStats().getPending());

        // 服务连接池统计
        Map<String, Map<String, Object>> serviceStats = new HashMap<>();
        poolStatistics.forEach((service, statistics) -> {
            Map<String, Object> serviceStat = new HashMap<>();
            serviceStat.put("totalRequests", statistics.getTotalRequests());
            serviceStat.put("successRequests", statistics.getSuccessRequests());
            serviceStat.put("failedRequests", statistics.getFailedRequests());
            serviceStat.put("avgResponseTime", statistics.getAvgResponseTime());
            serviceStats.put(service, serviceStat);
        });
        stats.put("serviceStatistics", serviceStats);

        return stats;
    }

    /**
     * 记录请求结果
     *
     * @param serviceName  服务名称
     * @param success      是否成功
     * @param responseTime 响应时间
     */
    public void recordRequestResult(String serviceName, boolean success, long responseTime) {
        PoolStatistics statistics = poolStatistics.get(serviceName);
        if (statistics != null) {
            if (success) {
                statistics.recordSuccess(responseTime);
            } else {
                statistics.recordFailure();
            }
        }
    }

    /**
     * 销毁连接池
     */
    @PreDestroy
    public void destroy() {
        log.info("销毁API网关连接池管理器...");

        // 关闭监控线程
        if (monitorExecutor != null && !monitorExecutor.isShutdown()) {
            monitorExecutor.shutdown();
            try {
                if (!monitorExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    monitorExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                monitorExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // 关闭HTTP客户端
        try {
            if (httpClient != null) {
                httpClient.close();
            }

            // 关闭服务专用的HTTP客户端
            serviceHttpClients.values().forEach(client -> {
                try {
                    client.close();
                } catch (Exception e) {
                    log.error("关闭服务HTTP客户端失败", e);
                }
            });
        } catch (Exception e) {
            log.error("关闭HTTP客户端失败", e);
        }

        // 关闭连接池管理器
        if (connectionManager != null) {
            connectionManager.close();
        }

        log.info("API网关连接池管理器销毁完成");
    }

    /**
     * 连接池统计信息内部类
     */
    private static class PoolStatistics {
        private final String serviceName;
        private final AtomicLong totalRequests = new AtomicLong(0);
        private final AtomicLong successRequests = new AtomicLong(0);
        private final AtomicLong failedRequests = new AtomicLong(0);
        private final AtomicLong totalResponseTime = new AtomicLong(0);
        private final AtomicInteger activeRequests = new AtomicInteger(0);

        public PoolStatistics(String serviceName) {
            this.serviceName = serviceName;
        }

        public void recordRequest() {
            totalRequests.incrementAndGet();
            activeRequests.incrementAndGet();
        }

        public void recordSuccess(long responseTime) {
            successRequests.incrementAndGet();
            totalResponseTime.addAndGet(responseTime);
            activeRequests.decrementAndGet();
        }

        public void recordFailure() {
            failedRequests.incrementAndGet();
            activeRequests.decrementAndGet();
        }

        public long getTotalRequests() {
            return totalRequests.get();
        }

        public long getSuccessRequests() {
            return successRequests.get();
        }

        public long getFailedRequests() {
            return failedRequests.get();
        }

        public double getAvgResponseTime() {
            long success = successRequests.get();
            if (success == 0) {
                return 0;
            }
            return (double) totalResponseTime.get() / success;
        }

        public int getActiveRequests() {
            return activeRequests.get();
        }
    }
}