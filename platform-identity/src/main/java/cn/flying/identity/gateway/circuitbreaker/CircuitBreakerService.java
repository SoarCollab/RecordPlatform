package cn.flying.identity.gateway.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.SupplierUtils;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 熔断器服务
 * 基于Resilience4j实现熔断、降级、限流、重试等功能
 * <p>
 * 核心功能：
 * 1. 熔断器管理（三态：关闭、打开、半开）
 * 2. 降级策略配置
 * 3. 限流控制
 * 4. 自动重试机制
 * 5. 熔断器监控和统计
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
@Component
public class CircuitBreakerService {

    /**
     * 降级处理器映射
     */
    private final Map<String, FallbackHandler<?>> fallbackHandlers = new ConcurrentHashMap<>();

    /**
     * 失败率阈值（百分比）
     */
    @Value("${api.gateway.circuit.failure-rate-threshold:50}")
    private float failureRateThreshold;

    /**
     * 慢调用率阈值（百分比）
     */
    @Value("${api.gateway.circuit.slow-call-rate-threshold:50}")
    private float slowCallRateThreshold;

    /**
     * 慢调用时长阈值（毫秒）
     */
    @Value("${api.gateway.circuit.slow-call-duration-threshold:1000}")
    private long slowCallDurationThreshold;

    /**
     * 滑动窗口大小
     */
    @Value("${api.gateway.circuit.sliding-window-size:100}")
    private int slidingWindowSize;

    /**
     * 最小调用次数（在此之前不会触发熔断）
     */
    @Value("${api.gateway.circuit.minimum-number-of-calls:10}")
    private int minimumNumberOfCalls;

    /**
     * 熔断器打开时长（秒）
     */
    @Value("${api.gateway.circuit.wait-duration-in-open-state:60}")
    private long waitDurationInOpenState;

    /**
     * 半开状态允许的调用次数
     */
    @Value("${api.gateway.circuit.permitted-calls-in-half-open:10}")
    private int permittedCallsInHalfOpen;

    /**
     * 限流QPS
     */
    @Value("${api.gateway.circuit.rate-limit-qps:100}")
    private int rateLimitQps;

    /**
     * 重试次数
     */
    @Value("${api.gateway.circuit.max-retry-attempts:3}")
    private int maxRetryAttempts;

    /**
     * 重试间隔（毫秒）
     */
    @Value("${api.gateway.circuit.retry-interval:500}")
    private long retryInterval;

    /**
     * 熔断器注册中心
     */
    private CircuitBreakerRegistry circuitBreakerRegistry;

    /**
     * 限流器注册中心
     */
    private RateLimiterRegistry rateLimiterRegistry;

    /**
     * 重试注册中心
     */
    private RetryRegistry retryRegistry;


    /**
     * 初始化熔断器配置
     */
    @PostConstruct
    public void init() {
        log.info("初始化熔断器服务...");

        // 创建熔断器配置
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .slowCallRateThreshold(slowCallRateThreshold)
                .slowCallDurationThreshold(Duration.ofMillis(slowCallDurationThreshold))
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .waitDurationInOpenState(Duration.ofSeconds(waitDurationInOpenState))
                .permittedNumberOfCallsInHalfOpenState(permittedCallsInHalfOpen)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(Exception.class)
                .build();

        // 创建熔断器注册中心
        circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig);

        // 创建限流器配置
        RateLimiterConfig rateLimiterConfig = RateLimiterConfig.custom()
                .limitForPeriod(rateLimitQps)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofMillis(100))
                .build();

        // 创建限流器注册中心
        rateLimiterRegistry = RateLimiterRegistry.of(rateLimiterConfig);

        // 创建重试配置
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(maxRetryAttempts)
                .waitDuration(Duration.ofMillis(retryInterval))
                .retryExceptions(Exception.class)
                .build();

        // 创建重试注册中心
        retryRegistry = RetryRegistry.of(retryConfig);

        log.info("熔断器服务初始化完成");
    }

    /**
     * 执行带熔断保护的异步调用
     *
     * @param serviceName 服务名称
     * @param callable    异步业务逻辑
     * @param fallback    降级逻辑
     * @param <T>         返回类型
     * @return 执行结果
     */
    public <T> T executeAsync(String serviceName, Callable<T> callable, Supplier<T> fallback) {
        try {
            // 转换为Supplier
            Supplier<T> supplier = () -> {
                try {
                    return callable.call();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };

            return executeWithCircuitBreaker(serviceName, supplier, fallback);

        } catch (Exception e) {
            log.error("异步熔断器执行异常: service={}", serviceName, e);
            return fallback.get();
        }
    }

    /**
     * 执行带熔断保护的调用
     *
     * @param serviceName 服务名称
     * @param supplier    业务逻辑
     * @param fallback    降级逻辑
     * @param <T>         返回类型
     * @return 执行结果
     */
    public <T> T executeWithCircuitBreaker(String serviceName, Supplier<T> supplier, Supplier<T> fallback) {
        try {
            // 获取或创建熔断器
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(serviceName);

            // 添加事件监听
            addEventListeners(serviceName, circuitBreaker);

            // 获取或创建限流器
            RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter(serviceName);

            // 获取或创建重试器
            Retry retry = retryRegistry.retry(serviceName);

            // 装饰供应商：重试 -> 熔断器 -> 限流
            Supplier<T> decoratedSupplier = SupplierUtils.recover(
                    Retry.decorateSupplier(retry,
                            CircuitBreaker.decorateSupplier(circuitBreaker,
                                    RateLimiter.decorateSupplier(rateLimiter, supplier))),
                    throwable -> {
                        log.warn("服务调用失败，执行降级: service={}, error={}",
                                serviceName, throwable.getMessage());
                        return fallback.get();
                    }
            );

            // 执行调用
            return decoratedSupplier.get();

        } catch (Exception e) {
            log.error("熔断器执行异常: service={}", serviceName, e);
            // 执行降级逻辑
            return fallback.get();
        }
    }

    /**
     * 添加事件监听器
     *
     * @param serviceName    服务名称
     * @param circuitBreaker 熔断器
     */
    private void addEventListeners(String serviceName, CircuitBreaker circuitBreaker) {
        circuitBreaker.getEventPublisher()
                .onStateTransition(event ->
                        log.warn("熔断器状态转换: service={}, from={}, to={}",
                                serviceName, event.getStateTransition().getFromState(),
                                event.getStateTransition().getToState()))
                .onFailureRateExceeded(event ->
                        log.warn("熔断器失败率超过阈值: service={}, failureRate={}",
                                serviceName, event.getFailureRate()))
                .onSlowCallRateExceeded(event ->
                        log.warn("熔断器慢调用率超过阈值: service={}, slowCallRate={}",
                                serviceName, event.getSlowCallRate()));
    }

    /**
     * 注册降级处理器
     *
     * @param serviceName 服务名称
     * @param handler     降级处理器
     */
    public void registerFallbackHandler(String serviceName, FallbackHandler<?> handler) {
        fallbackHandlers.put(serviceName, handler);
        log.info("注册降级处理器: service={}", serviceName);
    }

    /**
     * 获取熔断器状态
     *
     * @param serviceName 服务名称
     * @return 熔断器状态
     */
    public CircuitBreaker.State getCircuitBreakerState(String serviceName) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(serviceName);
        return circuitBreaker.getState();
    }

    /**
     * 获取所有熔断器状态
     *
     * @return 所有熔断器状态映射
     */
    public Map<String, Map<String, Object>> getAllCircuitBreakerMetrics() {
        Map<String, Map<String, Object>> allMetrics = new HashMap<>();

        circuitBreakerRegistry.getAllCircuitBreakers().forEach(cb -> {
            allMetrics.put(cb.getName(), getCircuitBreakerMetrics(cb.getName()));
        });

        return allMetrics;
    }

    /**
     * 获取熔断器统计信息
     *
     * @param serviceName 服务名称
     * @return 统计信息
     */
    public Map<String, Object> getCircuitBreakerMetrics(String serviceName) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(serviceName);
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();

        Map<String, Object> stats = new HashMap<>();
        stats.put("state", circuitBreaker.getState().toString());
        stats.put("failureRate", metrics.getFailureRate());
        stats.put("slowCallRate", metrics.getSlowCallRate());
        stats.put("numberOfBufferedCalls", metrics.getNumberOfBufferedCalls());
        stats.put("numberOfFailedCalls", metrics.getNumberOfFailedCalls());
        stats.put("numberOfSuccessfulCalls", metrics.getNumberOfSuccessfulCalls());
        stats.put("numberOfSlowCalls", metrics.getNumberOfSlowCalls());
        stats.put("numberOfNotPermittedCalls", metrics.getNumberOfNotPermittedCalls());

        return stats;
    }

    /**
     * 手动打开熔断器
     *
     * @param serviceName 服务名称
     */
    public void openCircuitBreaker(String serviceName) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(serviceName);
        circuitBreaker.transitionToOpenState();
        log.warn("手动打开熔断器: service={}", serviceName);
    }

    /**
     * 手动关闭熔断器
     *
     * @param serviceName 服务名称
     */
    public void closeCircuitBreaker(String serviceName) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(serviceName);
        circuitBreaker.transitionToClosedState();
        log.info("手动关闭熔断器: service={}", serviceName);
    }

    /**
     * 重置熔断器
     *
     * @param serviceName 服务名称
     */
    public void resetCircuitBreaker(String serviceName) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(serviceName);
        circuitBreaker.reset();
        log.info("重置熔断器: service={}", serviceName);
    }

    /**
     * 获取限流器统计信息
     *
     * @param serviceName 服务名称
     * @return 统计信息
     */
    public Map<String, Object> getRateLimiterMetrics(String serviceName) {
        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter(serviceName);
        RateLimiter.Metrics metrics = rateLimiter.getMetrics();

        Map<String, Object> stats = new HashMap<>();
        stats.put("availablePermissions", metrics.getAvailablePermissions());
        stats.put("numberOfWaitingThreads", metrics.getNumberOfWaitingThreads());

        return stats;
    }

    /**
     * 动态更新熔断器配置
     *
     * @param serviceName 服务名称
     * @param config      新的配置
     */
    public void updateCircuitBreakerConfig(String serviceName, CircuitBreakerConfig config) {
        // 移除旧的熔断器
        circuitBreakerRegistry.remove(serviceName);

        // 创建新的熔断器
        circuitBreakerRegistry.circuitBreaker(serviceName, config);

        log.info("更新熔断器配置: service={}", serviceName);
    }

    /**
     * 动态更新限流器配置
     *
     * @param serviceName 服务名称
     * @param qps         新的QPS限制
     */
    public void updateRateLimiterConfig(String serviceName, int qps) {
        // 创建新配置
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(qps)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofMillis(100))
                .build();

        // 移除旧的限流器
        rateLimiterRegistry.remove(serviceName);

        // 创建新的限流器
        rateLimiterRegistry.rateLimiter(serviceName, config);

        log.info("更新限流器配置: service={}, qps={}", serviceName, qps);
    }

    /**
     * 降级处理器接口
     *
     * @param <T> 返回类型
     */
    public interface FallbackHandler<T> {
        /**
         * 执行降级逻辑
         *
         * @param throwable 异常信息
         * @return 降级返回值
         */
        T handle(Throwable throwable);
    }
}