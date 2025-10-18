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
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Instant;
import java.time.Duration;

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
     * 健康检查线程池
     */
    private ScheduledExecutorService healthCheckExecutor;

    /**
     * 连接池健康状态
     */
    private final AtomicBoolean isHealthy = new AtomicBoolean(true);

    /**
     * 连接池启动时间
     */
    private Instant startTime;

    /**
     * 连接池重启次数
     */
    private final AtomicInteger restartCount = new AtomicInteger(0);

    /**
     * 最后一次健康检查时间
     */
    private volatile Instant lastHealthCheck;

    /**
     * 连接泄漏检测阈值（分钟）
     */
    @Value("${api.gateway.pool.leak-detection-threshold:5}")
    private int leakDetectionThreshold;

    /**
     * 动态调整启用标志
     */
    @Value("${api.gateway.pool.dynamic-sizing:true}")
    private boolean dynamicSizingEnabled;

    /**
     * 熔断器状态映射
     */
    private final Map<String, CircuitBreakerState> circuitBreakers = new ConcurrentHashMap<>();

    /**
     * 连接租借时间跟踪
     */
    private final Map<String, Instant> connectionLeaseTracker = new ConcurrentHashMap<>();

    /**
     * 初始化连接池
     */
    @PostConstruct
    public void init() {
        log.info("初始化API网关连接池管理器...");
        
        startTime = Instant.now();
        lastHealthCheck = Instant.now();

        try {
            initializeConnectionPool();
            startMonitoring();
            startHealthChecking();
            
            log.info("API网关连接池管理器初始化完成: maxTotal={}, maxPerRoute={}, startTime={}",
                    maxTotal, maxPerRoute, startTime);
        } catch (Exception e) {
            log.error("初始化连接池失败", e);
            isHealthy.set(false);
            throw new RuntimeException("连接池初始化失败", e);
        }
    }

    /**
     * 初始化连接池
     */
    private void initializeConnectionPool() {
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

        isHealthy.set(true);
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

        // 定期收集性能指标
        monitorExecutor.scheduleWithFixedDelay(this::collectPerformanceMetrics,
                30, 30, TimeUnit.SECONDS);

        // 定期检测连接泄漏
        monitorExecutor.scheduleWithFixedDelay(this::detectConnectionLeaks,
                (long) leakDetectionThreshold, (long) leakDetectionThreshold, TimeUnit.MINUTES);

        // 定期动态调整连接池大小
        if (dynamicSizingEnabled) {
            monitorExecutor.scheduleWithFixedDelay(this::performDynamicSizing,
                    60L, 60L, TimeUnit.SECONDS);
        }
    }

    /**
     * 启动健康检查线程
     */
    private void startHealthChecking() {
        healthCheckExecutor = Executors.newSingleThreadScheduledExecutor(
                r -> new Thread(r, "ConnectionPoolHealthCheck"));

        // 定期进行健康检查
        healthCheckExecutor.scheduleWithFixedDelay(this::performHealthCheck,
                10, 10, TimeUnit.SECONDS);
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

            // 高使用率告警和自动扩容
            if (usage > 80) {
                log.warn("连接池使用率过高: {}%, 考虑增加连接池大小", usage);
                // 自动扩容逻辑
                if (usage > 90 && max < 1000) {
                    int newMaxTotal = Math.min(max + 50, 1000);
                    log.info("自动扩容连接池: {} -> {}", max, newMaxTotal);
                    resizePool(newMaxTotal, maxPerRoute);
                }
            }

            // 等待连接过多告警
            if (pending > 10) {
                log.warn("连接池等待队列过长: {}, 可能存在性能问题", pending);
                if (pending > 50) {
                    log.error("连接池等待队列严重阻塞: {}, 触发健康检查", pending);
                    isHealthy.set(false);
                }
            }

            // 更新统计信息
            updateGlobalStatistics(available, leased, pending, usage);

        } catch (Exception e) {
            log.error("监控连接池健康状态失败", e);
            isHealthy.set(false);
        }
    }

    /**
     * 执行健康检查
     */
    private void performHealthCheck() {
        try {
            lastHealthCheck = Instant.now();
            
            // 检查连接池管理器是否正常
            if (connectionManager == null || httpClient == null) {
                log.error("连接池组件为空，尝试重新初始化");
                attemptRecovery();
                return;
            }

            // 检查连接池统计信息
            var stats = connectionManager.getTotalStats();
            if (stats == null) {
                log.error("无法获取连接池统计信息，连接池可能异常");
                isHealthy.set(false);
                attemptRecovery();
                return;
            }

            // 检查是否有长时间未响应的连接
            int pending = stats.getPending();
            if (pending > 0) {
                Duration timeSinceLastCheck = Duration.between(lastHealthCheck.minusSeconds(10), Instant.now());
                if (timeSinceLastCheck.toSeconds() > 60) {
                    log.warn("检测到长时间等待的连接: {}, 可能需要重置连接池", pending);
                }
            }

            // 如果之前不健康，现在检查通过则恢复健康状态
            if (!isHealthy.get()) {
                log.info("连接池健康检查通过，恢复健康状态");
                isHealthy.set(true);
            }

        } catch (Exception e) {
            log.error("连接池健康检查失败", e);
            isHealthy.set(false);
            attemptRecovery();
        }
    }

    /**
     * 尝试恢复连接池
     */
    private void attemptRecovery() {
        try {
            log.info("尝试恢复连接池，重启次数: {}", restartCount.get());
            
            // 关闭现有连接
            if (httpClient != null) {
                try {
                    httpClient.close();
                } catch (Exception e) {
                    log.warn("关闭旧HTTP客户端失败", e);
                }
            }
            
            if (connectionManager != null) {
                try {
                    connectionManager.close();
                } catch (Exception e) {
                    log.warn("关闭旧连接池管理器失败", e);
                }
            }

            // 重新初始化
            initializeConnectionPool();
            restartCount.incrementAndGet();
            
            log.info("连接池恢复成功，重启次数: {}", restartCount.get());
            
        } catch (Exception e) {
            log.error("连接池恢复失败", e);
            isHealthy.set(false);
        }
    }

    /**
     * 收集性能指标
     */
    private void collectPerformanceMetrics() {
        try {
            var stats = connectionManager.getTotalStats();
            
            // 记录全局性能指标
            PoolStatistics globalStats = poolStatistics.computeIfAbsent("global", PoolStatistics::new);
            globalStats.updatePoolMetrics(
                stats.getAvailable(),
                stats.getLeased(),
                stats.getPending(),
                connectionManager.getMaxTotal()
            );

            // 计算运行时间
            Duration uptime = Duration.between(startTime, Instant.now());
            
            log.debug("连接池性能指标 - 运行时间: {}分钟, 重启次数: {}, 健康状态: {}",
                    uptime.toMinutes(), restartCount.get(), isHealthy.get());

        } catch (Exception e) {
            log.error("收集性能指标失败", e);
        }
    }

    /**
     * 更新全局统计信息
     */
    private void updateGlobalStatistics(int available, int leased, int pending, double usage) {
        PoolStatistics globalStats = poolStatistics.computeIfAbsent("global", PoolStatistics::new);
        globalStats.updateConnectionStats(available, leased, pending, usage);
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
        return getHttpClientWithCircuitBreaker("default");
    }

    /**
     * 带熔断器的HTTP客户端获取
     *
     * @param serviceName 服务名称
     * @return HTTP客户端实例
     */
    private CloseableHttpClient getHttpClientWithCircuitBreaker(String serviceName) {
        CircuitBreakerState circuitBreaker = circuitBreakers.computeIfAbsent(serviceName, 
                k -> new CircuitBreakerState(serviceName));

        // 检查熔断器状态
        if (circuitBreaker.isOpen()) {
            if (circuitBreaker.shouldAttemptReset()) {
                log.info("熔断器 {} 尝试半开状态", serviceName);
                circuitBreaker.halfOpen();
            } else {
                log.warn("熔断器 {} 处于开启状态，拒绝请求", serviceName);
                throw new RuntimeException("Circuit breaker is open for service: " + serviceName);
            }
        }

        recordUsage(serviceName);
        trackConnectionLease(serviceName);
        
        return serviceName.equals("default") ? httpClient : 
               serviceHttpClients.computeIfAbsent(serviceName, this::createServiceHttpClient);
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
        return getHttpClientWithCircuitBreaker(serviceName);
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

        try {
            // 默认连接池统计
            stats.put("maxTotal", connectionManager.getMaxTotal());
            stats.put("defaultMaxPerRoute", connectionManager.getDefaultMaxPerRoute());
            stats.put("availableConnections", connectionManager.getTotalStats().getAvailable());
            stats.put("leasedConnections", connectionManager.getTotalStats().getLeased());
            stats.put("pendingConnections", connectionManager.getTotalStats().getPending());
            
            // 健康状态和运行时信息
            stats.put("isHealthy", isHealthy.get());
            stats.put("startTime", startTime);
            stats.put("uptime", Duration.between(startTime, Instant.now()).toMinutes());
            stats.put("restartCount", restartCount.get());
            stats.put("lastHealthCheck", lastHealthCheck);

            // 服务连接池统计
            Map<String, Map<String, Object>> serviceStats = new HashMap<>();
            poolStatistics.forEach((service, statistics) -> {
                Map<String, Object> serviceStat = new HashMap<>();
                serviceStat.put("totalRequests", statistics.getTotalRequests());
                serviceStat.put("successRequests", statistics.getSuccessRequests());
                serviceStat.put("failedRequests", statistics.getFailedRequests());
                serviceStat.put("avgResponseTime", statistics.getAvgResponseTime());
                serviceStat.put("activeRequests", statistics.getActiveRequests());
                serviceStat.put("successRate", statistics.getSuccessRate());
                serviceStats.put(service, serviceStat);
            });
            stats.put("serviceStatistics", serviceStats);

        } catch (Exception e) {
            log.error("获取连接池统计信息失败", e);
            stats.put("error", "Failed to collect statistics: " + e.getMessage());
        }

        return stats;
    }

    /**
     * 检查连接池是否健康
     *
     * @return 健康状态
     */
    public boolean isHealthy() {
        return isHealthy.get() && connectionManager != null && httpClient != null;
    }

    /**
     * 获取连接池运行时间
     *
     * @return 运行时间（分钟）
     */
    public long getUptimeMinutes() {
        return Duration.between(startTime, Instant.now()).toMinutes();
    }

    /**
     * 获取重启次数
     *
     * @return 重启次数
     */
    public int getRestartCount() {
        return restartCount.get();
    }

    /**
     * 手动触发健康检查
     *
     * @return 检查结果
     */
    public boolean triggerHealthCheck() {
        try {
            performHealthCheck();
            return isHealthy.get();
        } catch (Exception e) {
            log.error("手动健康检查失败", e);
            return false;
        }
    }

    /**
     * 强制重启连接池
     */
    public void forceRestart() {
        log.info("强制重启连接池");
        isHealthy.set(false);
        attemptRecovery();
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

        // 更新熔断器状态
        CircuitBreakerState circuitBreaker = circuitBreakers.computeIfAbsent(serviceName, 
                k -> new CircuitBreakerState(serviceName));
        if (success) {
            circuitBreaker.recordSuccess();
        } else {
            circuitBreaker.recordFailure();
        }

        // 释放连接租借跟踪
        releaseConnectionLease(serviceName);
    }

    /**
     * 跟踪连接租借
     */
    private void trackConnectionLease(String serviceName) {
        String leaseKey = serviceName + "-" + Thread.currentThread().getId() + "-" + System.nanoTime();
        connectionLeaseTracker.put(leaseKey, Instant.now());
    }

    /**
     * 释放连接租借跟踪
     */
    private void releaseConnectionLease(String serviceName) {
        String threadId = String.valueOf(Thread.currentThread().getId());
        connectionLeaseTracker.entrySet().removeIf(entry -> 
            entry.getKey().startsWith(serviceName + "-" + threadId));
    }

    /**
     * 检测连接泄漏
     */
    private void detectConnectionLeaks() {
        try {
            Instant threshold = Instant.now().minusSeconds(leakDetectionThreshold * 60L);
            
            connectionLeaseTracker.entrySet().removeIf(entry -> {
                if (entry.getValue().isBefore(threshold)) {
                    String[] parts = entry.getKey().split("-");
                    if (parts.length >= 2) {
                        String serviceName = parts[0];
                        log.warn("检测到连接泄漏: service={}, leaseTime={}, duration={}分钟", 
                                serviceName, entry.getValue(), 
                                Duration.between(entry.getValue(), Instant.now()).toMinutes());
                    }
                    return true;
                }
                return false;
            });

            // 如果检测到大量泄漏，触发连接池清理
            if (connectionLeaseTracker.size() > maxTotal * 0.8) {
                log.error("检测到大量连接泄漏 ({}), 触发连接池清理", connectionLeaseTracker.size());
                performConnectionCleanup();
            }

        } catch (Exception e) {
            log.error("连接泄漏检测失败", e);
        }
    }

    /**
     * 执行连接清理
     */
    private void performConnectionCleanup() {
        try {
            log.info("执行连接池清理操作");
            
            // 清理空闲连接
            evictIdleConnections();
            
            // 清理泄漏跟踪记录
            connectionLeaseTracker.clear();
            
            // 如果问题严重，重启连接池
            var stats = connectionManager.getTotalStats();
            if (stats.getLeased() > maxTotal * 0.9) {
                log.warn("连接池使用率过高，执行强制重启");
                attemptRecovery();
            }
            
        } catch (Exception e) {
            log.error("连接池清理失败", e);
        }
    }

    /**
     * 动态调整连接池大小
     */
    private void performDynamicSizing() {
        try {
            if (!dynamicSizingEnabled) {
                return;
            }

            var stats = connectionManager.getTotalStats();
            int currentMax = connectionManager.getMaxTotal();
            int leased = stats.getLeased();
            int pending = stats.getPending();
            
            double usage = (double) leased / currentMax;
            
            // 获取最近的请求统计
            PoolStatistics globalStats = poolStatistics.get("global");
            if (globalStats == null) {
                return;
            }

            // 扩容条件：使用率高且有等待连接
            if (usage > 0.8 && pending > 5 && currentMax < 1000) {
                int newMax = Math.min(currentMax + Math.max(10, pending), 1000);
                log.info("动态扩容连接池: {} -> {}, 使用率: {}%, 等待: {}", 
                        currentMax, newMax, usage * 100, pending);
                resizePool(newMax, maxPerRoute);
            }
            // 缩容条件：使用率低且无等待连接
            else if (usage < 0.3 && pending == 0 && currentMax > maxTotal) {
                int newMax = Math.max(currentMax - 10, maxTotal);
                log.info("动态缩容连接池: {} -> {}, 使用率: {}%", 
                        currentMax, newMax, usage * 100);
                resizePool(newMax, maxPerRoute);
            }

        } catch (Exception e) {
            log.error("动态调整连接池大小失败", e);
        }
    }

    /**
     * 获取熔断器状态
     *
     * @param serviceName 服务名称
     * @return 熔断器状态
     */
    public Map<String, Object> getCircuitBreakerStatus(String serviceName) {
        CircuitBreakerState circuitBreaker = circuitBreakers.get(serviceName);
        if (circuitBreaker == null) {
            return Map.of("status", "NOT_FOUND");
        }
        return circuitBreaker.getStatus();
    }

    /**
     * 获取所有熔断器状态
     *
     * @return 所有熔断器状态
     */
    public Map<String, Map<String, Object>> getAllCircuitBreakerStatus() {
        Map<String, Map<String, Object>> result = new HashMap<>();
        circuitBreakers.forEach((service, breaker) -> 
            result.put(service, breaker.getStatus()));
        return result;
    }

    /**
     * 手动重置熔断器
     *
     * @param serviceName 服务名称
     */
    public void resetCircuitBreaker(String serviceName) {
        CircuitBreakerState circuitBreaker = circuitBreakers.get(serviceName);
        if (circuitBreaker != null) {
            circuitBreaker.reset();
            log.info("手动重置熔断器: {}", serviceName);
        }
    }

    /**
     * 销毁连接池
     */
    @PreDestroy
    public void destroy() {
        log.info("销毁API网关连接池管理器...");

        // 标记为不健康状态
        isHealthy.set(false);

        // 关闭健康检查线程
        shutdownExecutor(healthCheckExecutor, "HealthCheckExecutor");

        // 关闭监控线程
        shutdownExecutor(monitorExecutor, "MonitorExecutor");

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

        // 清理统计信息
        poolStatistics.clear();
        serviceHttpClients.clear();

        log.info("API网关连接池管理器销毁完成，运行时间: {}分钟，重启次数: {}",
                getUptimeMinutes(), restartCount.get());
    }

    /**
     * 安全关闭线程池
     */
    private void shutdownExecutor(ScheduledExecutorService executor, String name) {
        if (executor != null && !executor.isShutdown()) {
            log.info("关闭线程池: {}", name);
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("线程池 {} 未能在10秒内正常关闭，强制关闭", name);
                    executor.shutdownNow();
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        log.error("线程池 {} 强制关闭失败", name);
                    }
                }
            } catch (InterruptedException e) {
                log.warn("等待线程池 {} 关闭时被中断", name);
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
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
        
        // 连接池指标
        private volatile int availableConnections = 0;
        private volatile int leasedConnections = 0;
        private volatile int pendingConnections = 0;
        private volatile double connectionUsage = 0.0;
        private volatile Instant lastUpdate = Instant.now();

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

        public void updatePoolMetrics(int available, int leased, int pending, int maxTotal) {
            this.availableConnections = available;
            this.leasedConnections = leased;
            this.pendingConnections = pending;
            this.connectionUsage = maxTotal > 0 ? (double) leased / maxTotal * 100 : 0;
            this.lastUpdate = Instant.now();
        }

        public void updateConnectionStats(int available, int leased, int pending, double usage) {
            this.availableConnections = available;
            this.leasedConnections = leased;
            this.pendingConnections = pending;
            this.connectionUsage = usage;
            this.lastUpdate = Instant.now();
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

        public double getSuccessRate() {
            long total = totalRequests.get();
            if (total == 0) {
                return 0;
            }
            return (double) successRequests.get() / total * 100;
        }

        public int getAvailableConnections() {
            return availableConnections;
        }

        public int getLeasedConnections() {
            return leasedConnections;
        }

        public int getPendingConnections() {
            return pendingConnections;
        }

        public double getConnectionUsage() {
            return connectionUsage;
        }

        public Instant getLastUpdate() {
            return lastUpdate;
        }
    }

    /**
     * 熔断器状态管理类
     */
    private static class CircuitBreakerState {
        private final String serviceName;
        private volatile State state = State.CLOSED;
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final AtomicInteger successCount = new AtomicInteger(0);
        private volatile Instant lastFailureTime;
        private volatile Instant stateChangeTime = Instant.now();
        
        // 熔断器配置
        private final int failureThreshold = 5;
        private final int successThreshold = 3;
        private final Duration timeout = Duration.ofMinutes(1);

        public CircuitBreakerState(String serviceName) {
            this.serviceName = serviceName;
        }

        public void recordSuccess() {
            if (state == State.HALF_OPEN) {
                int successes = successCount.incrementAndGet();
                if (successes >= successThreshold) {
                    close();
                }
            } else {
                failureCount.set(0);
            }
        }

        public void recordFailure() {
            lastFailureTime = Instant.now();
            int failures = failureCount.incrementAndGet();
            
            if (state == State.CLOSED && failures >= failureThreshold) {
                open();
            } else if (state == State.HALF_OPEN) {
                open();
            }
        }

        public boolean isOpen() {
            return state == State.OPEN;
        }

        public boolean shouldAttemptReset() {
            return state == State.OPEN && 
                   Instant.now().isAfter(stateChangeTime.plus(timeout));
        }

        public void halfOpen() {
            state = State.HALF_OPEN;
            stateChangeTime = Instant.now();
            successCount.set(0);
        }

        private void open() {
            state = State.OPEN;
            stateChangeTime = Instant.now();
            successCount.set(0);
        }

        private void close() {
            state = State.CLOSED;
            stateChangeTime = Instant.now();
            failureCount.set(0);
            successCount.set(0);
        }

        public void reset() {
            close();
        }

        public Map<String, Object> getStatus() {
            Map<String, Object> status = new HashMap<>();
            status.put("serviceName", serviceName);
            status.put("state", state.name());
            status.put("failureCount", failureCount.get());
            status.put("successCount", successCount.get());
            status.put("lastFailureTime", lastFailureTime);
            status.put("stateChangeTime", stateChangeTime);
            status.put("failureThreshold", failureThreshold);
            status.put("successThreshold", successThreshold);
            status.put("timeout", timeout.toSeconds());
            return status;
        }

        private enum State {
            CLOSED, OPEN, HALF_OPEN
        }
    }
}