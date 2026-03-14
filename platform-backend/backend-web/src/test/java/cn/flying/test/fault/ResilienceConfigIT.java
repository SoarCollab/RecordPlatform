package cn.flying.test.fault;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

// Resilience4j 2.3.0 API 说明:
// CircuitBreakerConfig.getWaitIntervalFunctionInOpenState() → IntervalFunction = Function<Integer, Long> (ms)
// RetryConfig.getIntervalBiFunction() → IntervalBiFunction<T>，apply() 返回 Long (ms)

/**
 * Resilience4j 配置正确性校验。
 * 验证 application.yml 中 circuitbreaker / retry 实例的配置值已正确加载，
 * 防止配置拼写错误或配置文件未加载导致默认值覆盖期望阈值。
 * 注意：Resilience4j 注解在 mock 上不生效（AOP 被绕过），行为验证由单元测试负责。
 */
class ResilienceConfigIT extends FaultInjectionBaseIT {

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private RetryRegistry retryRegistry;

    // ──────────────────────────── Test 1 ────────────────────────────

    /**
     * circuitBreakerRegistry 中存在 blockChainService 和 storageService 实例。
     */
    @Test
    void circuitBreakerInstances_exist() {
        // circuitBreaker(name) 会使用已注册配置创建实例（若尚未创建）
        CircuitBreaker blockChain = circuitBreakerRegistry.circuitBreaker("blockChainService");
        CircuitBreaker storage = circuitBreakerRegistry.circuitBreaker("storageService");

        assertNotNull(blockChain, "blockChainService 断路器实例应存在");
        assertNotNull(storage, "storageService 断路器实例应存在");
    }

    // ──────────────────────────── Test 2 ────────────────────────────

    /**
     * 断路器通用配置（继承自 default）：滑动窗口大小=50，失败率阈值=50%，
     * open 状态等待时间=30s。
     */
    @Test
    void circuitBreakerConfig_defaultValues_matchYaml() {
        CircuitBreakerConfig config = circuitBreakerRegistry
                .circuitBreaker("blockChainService")
                .getCircuitBreakerConfig();

        assertEquals(50, config.getSlidingWindowSize(),
                "slidingWindowSize 应为 50（继承自 default）");
        assertEquals(50.0f, config.getFailureRateThreshold(), 0.01f,
                "failureRateThreshold 应为 50%（继承自 default）");
        // getWaitIntervalFunctionInOpenState() 返回 IntervalFunction (Function<Integer,Long>)，单位 ms
        long waitMs = config.getWaitIntervalFunctionInOpenState().apply(1);
        assertEquals(30_000L, waitMs,
                "waitDurationInOpenState 应为 30s = 30000ms（继承自 default）");
    }

    // ──────────────────────────── Test 3 ────────────────────────────

    /**
     * storageService 实例级别慢调用阈值覆盖：8s。
     */
    @Test
    void circuitBreakerConfig_storageService_slowCallThreshold8s() {
        CircuitBreakerConfig config = circuitBreakerRegistry
                .circuitBreaker("storageService")
                .getCircuitBreakerConfig();

        assertEquals(Duration.ofSeconds(8), config.getSlowCallDurationThreshold(),
                "storageService slowCallDurationThreshold 应为 8s");
    }

    // ──────────────────────────── Test 4 ────────────────────────────

    /**
     * blockChainService 实例级别慢调用阈值覆盖：5s。
     */
    @Test
    void circuitBreakerConfig_blockChainService_slowCallThreshold5s() {
        CircuitBreakerConfig config = circuitBreakerRegistry
                .circuitBreaker("blockChainService")
                .getCircuitBreakerConfig();

        assertEquals(Duration.ofSeconds(5), config.getSlowCallDurationThreshold(),
                "blockChainService slowCallDurationThreshold 应为 5s");
    }

    // ──────────────────────────── Test 5 ────────────────────────────

    /**
     * 重试配置：maxAttempts=3，waitDuration=200ms，指数退避倍数=2。
     */
    @Test
    void retryConfig_defaultValues_matchYaml() {
        // blockChainService 和 storageService 均继承 default retry 配置
        Retry blockChainRetry = retryRegistry.retry("blockChainService");
        RetryConfig config = blockChainRetry.getRetryConfig();

        assertEquals(3, config.getMaxAttempts(),
                "maxAttempts 应为 3");

        // getIntervalBiFunction() 返回 IntervalBiFunction<T>（BiFunction<Integer, Either, Long>）
        // apply() 直接返回 Long（毫秒），指数退避：第1次=200ms，第2次=200*2=400ms
        long firstIntervalMs = (Long) config.getIntervalBiFunction().apply(1, null);
        assertEquals(200L, firstIntervalMs,
                "第一次重试等待时间应为 200ms");

        long secondIntervalMs = (Long) config.getIntervalBiFunction().apply(2, null);
        assertEquals(400L, secondIntervalMs,
                "第二次重试等待时间应为 400ms（200ms × 倍数 2）");
    }
}
