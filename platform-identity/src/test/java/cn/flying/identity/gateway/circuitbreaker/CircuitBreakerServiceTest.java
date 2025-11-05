package cn.flying.identity.gateway.circuitbreaker;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CircuitBreakerService 单元测试
 * 验证限流触发与异常降级分支
 */
class CircuitBreakerServiceTest {

    @Test
    void executeWithCircuitBreaker_shouldFallbackWhenRateLimitExceeded() {
        CircuitBreakerService service = createService(1);
        String serviceName = "rate-limit-service";

        String first = service.executeWithCircuitBreaker(serviceName, () -> "SUCCESS", () -> "FALLBACK");
        String second = service.executeWithCircuitBreaker(serviceName, () -> "SUCCESS", () -> "FALLBACK");

        Assertions.assertEquals("SUCCESS", first);
        Assertions.assertEquals("FALLBACK", second, "第二次在限流窗口内调用应走降级");

        Map<String, Object> rateLimiterMetrics = service.getRateLimiterMetrics(serviceName);
        Assertions.assertTrue(((Number) rateLimiterMetrics.get("availablePermissions")).intValue() <= 1,
                "限流后剩余可用许可应被耗尽或减少");
    }

    @Test
    void executeWithCircuitBreaker_shouldFallbackWhenSupplierThrowsException() {
        CircuitBreakerService service = createService(5);
        String serviceName = "exception-service";
        AtomicInteger fallbackCounter = new AtomicInteger();

        String result = service.executeWithCircuitBreaker(serviceName,
                () -> {
                    throw new IllegalStateException("boom");
                },
                () -> {
                    fallbackCounter.incrementAndGet();
                    return "FALLBACK";
                });

        Assertions.assertEquals("FALLBACK", result);
        Assertions.assertEquals(1, fallbackCounter.get(), "异常后需执行一次降级逻辑");

        Map<String, Object> metrics = service.getCircuitBreakerMetrics(serviceName);
        Assertions.assertTrue(((Number) metrics.get("numberOfFailedCalls")).intValue() >= 1,
                "熔断器应记录失败调用次数");
    }

    private CircuitBreakerService createService(int rateLimit) {
        CircuitBreakerService service = new CircuitBreakerService();
        ReflectionTestUtils.setField(service, "failureRateThreshold", 10f);
        ReflectionTestUtils.setField(service, "slowCallRateThreshold", 10f);
        ReflectionTestUtils.setField(service, "slowCallDurationThreshold", 10L);
        ReflectionTestUtils.setField(service, "slidingWindowSize", 10);
        ReflectionTestUtils.setField(service, "minimumNumberOfCalls", 1);
        ReflectionTestUtils.setField(service, "waitDurationInOpenState", 1L);
        ReflectionTestUtils.setField(service, "permittedCallsInHalfOpen", 1);
        ReflectionTestUtils.setField(service, "rateLimitQps", rateLimit);
        ReflectionTestUtils.setField(service, "maxRetryAttempts", 1);
        ReflectionTestUtils.setField(service, "retryInterval", 10L);
        service.init();
        return service;
    }
}
