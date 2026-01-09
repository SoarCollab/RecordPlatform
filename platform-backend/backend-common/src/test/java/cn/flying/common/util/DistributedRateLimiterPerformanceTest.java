package cn.flying.common.util;

import cn.flying.test.logging.LogbackSilencerExtension;
import cn.flying.test.logging.SilenceLoggers;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("DistributedRateLimiter Performance Tests")
@ExtendWith(MockitoExtension.class)
@ExtendWith(LogbackSilencerExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DistributedRateLimiterPerformanceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private DistributedRateLimiter rateLimiter;

    private static final int THREAD_COUNT = 20;
    private static final int REQUESTS_PER_THREAD = 100;


    @Nested
    @DisplayName("Concurrent Rate Limiting")
    class ConcurrentRateLimiting {

        @Test
        @DisplayName("should handle concurrent rate limit checks")
        void shouldHandleConcurrentRateLimitChecks() throws Exception {
            AtomicInteger allowedCount = new AtomicInteger(0);
            int limit = 100;

            doAnswer(inv -> {
                int current = allowedCount.incrementAndGet();
                return current <= limit ? 1L : 0L;
            }).when(redisTemplate).execute(any(), anyList(), any(Object[].class));

            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(THREAD_COUNT * REQUESTS_PER_THREAD);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger rateLimitedCount = new AtomicInteger(0);
            AtomicReference<Throwable> firstError = new AtomicReference<>();

            for (int t = 0; t < THREAD_COUNT; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < REQUESTS_PER_THREAD; i++) {
                            boolean allowed = rateLimiter.tryAcquire("test:key", limit, 60);
                            if (allowed) {
                                successCount.incrementAndGet();
                            } else {
                                rateLimitedCount.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        firstError.compareAndSet(null, e);
                    } finally {
                        for (int i = 0; i < REQUESTS_PER_THREAD; i++) {
                            endLatch.countDown();
                        }
                    }
                });
            }

            startLatch.countDown();
            endLatch.await(60, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(firstError.get()).isNull();
            int totalRequests = THREAD_COUNT * REQUESTS_PER_THREAD;
            assertThat(successCount.get() + rateLimitedCount.get()).isEqualTo(totalRequests);
            assertThat(successCount.get()).isLessThanOrEqualTo(limit);
        }

        @Test
        @DisplayName("should handle concurrent requests from multiple keys")
        void shouldHandleConcurrentRequestsFromMultipleKeys() throws Exception {
            Map<String, AtomicInteger> keyCounters = new ConcurrentHashMap<>();
            int limitPerKey = 50;

            doAnswer(inv -> {
                List<String> keys = inv.getArgument(1);
                String key = keys.get(0);
                AtomicInteger counter = keyCounters.computeIfAbsent(key, k -> new AtomicInteger(0));
                int current = counter.incrementAndGet();
                return current <= limitPerKey ? 1L : 0L;
            }).when(redisTemplate).execute(any(), anyList(), any(Object[].class));

            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            CountDownLatch latch = new CountDownLatch(THREAD_COUNT * REQUESTS_PER_THREAD);
            Map<String, AtomicInteger> successByKey = new ConcurrentHashMap<>();
            Map<String, AtomicInteger> limitedByKey = new ConcurrentHashMap<>();
            AtomicReference<Throwable> firstError = new AtomicReference<>();

            for (int t = 0; t < THREAD_COUNT; t++) {
                final String key = "user:" + (t % 5);
                successByKey.computeIfAbsent(key, k -> new AtomicInteger(0));
                limitedByKey.computeIfAbsent(key, k -> new AtomicInteger(0));

                executor.submit(() -> {
                    for (int i = 0; i < REQUESTS_PER_THREAD; i++) {
                        try {
                            boolean allowed = rateLimiter.tryAcquire(key, limitPerKey, 60);
                            if (allowed) {
                                successByKey.get(key).incrementAndGet();
                            } else {
                                limitedByKey.get(key).incrementAndGet();
                            }
                        } catch (Exception e) {
                            firstError.compareAndSet(null, e);
                        } finally {
                            latch.countDown();
                        }
                    }
                });
            }

            latch.await(60, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(firstError.get()).isNull();
            successByKey.forEach((key, count) -> {
                assertThat(count.get()).isLessThanOrEqualTo(limitPerKey);
            });
        }
    }

    @Nested
    @DisplayName("Blocking Rate Limiter Performance")
    class BlockingRateLimiterPerformance {

        @Test
        @DisplayName("should handle concurrent blocking rate limit checks")
        void shouldHandleConcurrentBlockingRateLimitChecks() throws Exception {
            AtomicInteger requestCount = new AtomicInteger(0);
            int limit = 50;
            int blockThreshold = 100;

            doAnswer(inv -> {
                int current = requestCount.incrementAndGet();
                if (current > blockThreshold) {
                    return -1L;
                } else if (current > limit) {
                    return 0L;
                }
                return 1L;
            }).when(redisTemplate).execute(any(), anyList(), any(Object[].class));

            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            CountDownLatch latch = new CountDownLatch(THREAD_COUNT * REQUESTS_PER_THREAD);
            AtomicInteger allowedCount = new AtomicInteger(0);
            AtomicInteger rateLimitedCount = new AtomicInteger(0);
            AtomicInteger blockedCount = new AtomicInteger(0);
            AtomicReference<Throwable> firstError = new AtomicReference<>();

            for (int t = 0; t < THREAD_COUNT; t++) {
                executor.submit(() -> {
                    for (int i = 0; i < REQUESTS_PER_THREAD; i++) {
                        try {
                            var result = rateLimiter.tryAcquireWithBlock(
                                    "counter:key", "block:key", limit, 60, 300);
                            switch (result) {
                                case ALLOWED -> allowedCount.incrementAndGet();
                                case RATE_LIMITED -> rateLimitedCount.incrementAndGet();
                                case BLOCKED -> blockedCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            firstError.compareAndSet(null, e);
                        } finally {
                            latch.countDown();
                        }
                    }
                });
            }

            latch.await(60, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(firstError.get()).isNull();
            int total = allowedCount.get() + rateLimitedCount.get() + blockedCount.get();
            assertThat(total).isEqualTo(THREAD_COUNT * REQUESTS_PER_THREAD);
            assertThat(allowedCount.get()).isLessThanOrEqualTo(limit);
            assertThat(blockedCount.get()).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("Throughput Measurement")
    class ThroughputMeasurement {

        @Test
        @DisplayName("should measure rate limiter throughput")
        void shouldMeasureRateLimiterThroughput() throws Exception {
            doReturn(1L).when(redisTemplate).execute(any(), anyList(), any(Object[].class));

            int totalOperations = 10000;
            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(totalOperations);
            AtomicInteger completedCount = new AtomicInteger(0);
            AtomicReference<Throwable> firstError = new AtomicReference<>();

            long startTime = System.nanoTime();

            for (int i = 0; i < totalOperations; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        rateLimiter.tryAcquire("throughput:key", 1000, 60);
                        completedCount.incrementAndGet();
                    } catch (Exception e) {
                        firstError.compareAndSet(null, e);
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            endLatch.await(60, TimeUnit.SECONDS);
            executor.shutdown();

            long endTime = System.nanoTime();
            double durationMs = (endTime - startTime) / 1_000_000.0;
            double throughput = completedCount.get() / (durationMs / 1000.0);

            assertThat(firstError.get()).isNull();
            assertThat(completedCount.get()).isEqualTo(totalOperations);
            assertThat(throughput).isGreaterThan(1000.0);
        }

        @Test
        @DisplayName("should measure latency distribution")
        void shouldMeasureLatencyDistribution() throws Exception {
            doAnswer(inv -> {
                Thread.sleep(1);
                return 1L;
            }).when(redisTemplate).execute(any(), anyList(), any(Object[].class));

            int operationCount = 1000;
            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            CountDownLatch latch = new CountDownLatch(operationCount);
            List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
            AtomicReference<Throwable> firstError = new AtomicReference<>();

            for (int i = 0; i < operationCount; i++) {
                executor.submit(() -> {
                    try {
                        long start = System.nanoTime();
                        rateLimiter.tryAcquire("latency:key", 1000, 60);
                        long end = System.nanoTime();
                        latencies.add((end - start) / 1_000);
                    } catch (Exception e) {
                        firstError.compareAndSet(null, e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(60, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(firstError.get()).isNull();
            assertThat(latencies).hasSize(operationCount);

            Collections.sort(latencies);
            long p50 = latencies.get(latencies.size() / 2);
            long p95 = latencies.get((int) (latencies.size() * 0.95));
            long p99 = latencies.get((int) (latencies.size() * 0.99));
            long max = latencies.get(latencies.size() - 1);

            assertThat(p50).isLessThan(10_000);
            assertThat(p95).isLessThan(50_000);
            assertThat(p99).isLessThan(100_000);
        }
    }

    @Nested
    @DisplayName("Graceful Degradation Under Failure")
    class GracefulDegradationUnderFailure {

        @Test
        @DisplayName("should handle Redis failures gracefully under load")
        @SilenceLoggers("cn.flying.common.util.DistributedRateLimiter")
        void shouldHandleRedisFailuresGracefullyUnderLoad() throws Exception {
            AtomicInteger callCount = new AtomicInteger(0);
            doAnswer(inv -> {
                int count = callCount.incrementAndGet();
                if (count % 10 == 0) {
                    throw new RuntimeException("Simulated Redis failure");
                }
                return 1L;
            }).when(redisTemplate).execute(any(), anyList(), any(Object[].class));

            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            CountDownLatch latch = new CountDownLatch(THREAD_COUNT * REQUESTS_PER_THREAD);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);

            for (int t = 0; t < THREAD_COUNT; t++) {
                executor.submit(() -> {
                    for (int i = 0; i < REQUESTS_PER_THREAD; i++) {
                        try {
                            boolean result = rateLimiter.tryAcquire("failure:key", 1000, 60);
                            if (result) {
                                successCount.incrementAndGet();
                            } else {
                                failureCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            failureCount.incrementAndGet();
                        } finally {
                            latch.countDown();
                        }
                    }
                });
            }

            latch.await(60, TimeUnit.SECONDS);
            executor.shutdown();

            int total = successCount.get() + failureCount.get();
            assertThat(total).isEqualTo(THREAD_COUNT * REQUESTS_PER_THREAD);
            assertThat(successCount.get()).isGreaterThan((int) (total * 0.8));
        }

        @Test
        @DisplayName("should recover after Redis reconnection")
        @SilenceLoggers("cn.flying.common.util.DistributedRateLimiter")
        void shouldRecoverAfterRedisReconnection() throws Exception {
            AtomicInteger callCount = new AtomicInteger(0);
            AtomicInteger failurePhase = new AtomicInteger(0);

            doAnswer(inv -> {
                int count = callCount.incrementAndGet();
                int phase = failurePhase.get();

                if (phase == 1 && count > 50 && count < 150) {
                    throw new RuntimeException("Redis unavailable");
                }
                return 1L;
            }).when(redisTemplate).execute(any(), anyList(), any(Object[].class));

            ExecutorService executor = Executors.newFixedThreadPool(5);
            AtomicInteger successCount = new AtomicInteger(0);

            failurePhase.set(0);
            CountDownLatch phase1Latch = new CountDownLatch(50);
            for (int i = 0; i < 50; i++) {
                executor.submit(() -> {
                    try {
                        if (rateLimiter.tryAcquire("recovery:key", 1000, 60)) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // Expected during failure phase
                    } finally {
                        phase1Latch.countDown();
                    }
                });
            }
            phase1Latch.await(30, TimeUnit.SECONDS);
            int phase1Success = successCount.get();

            failurePhase.set(1);
            successCount.set(0);
            CountDownLatch phase2Latch = new CountDownLatch(100);
            for (int i = 0; i < 100; i++) {
                executor.submit(() -> {
                    try {
                        if (rateLimiter.tryAcquire("recovery:key", 1000, 60)) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // Expected during failure phase
                    } finally {
                        phase2Latch.countDown();
                    }
                });
            }
            phase2Latch.await(30, TimeUnit.SECONDS);
            int phase2Success = successCount.get();

            failurePhase.set(0);
            successCount.set(0);
            CountDownLatch phase3Latch = new CountDownLatch(50);
            for (int i = 0; i < 50; i++) {
                executor.submit(() -> {
                    try {
                        if (rateLimiter.tryAcquire("recovery:key", 1000, 60)) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // Should not happen in recovery phase
                    } finally {
                        phase3Latch.countDown();
                    }
                });
            }
            phase3Latch.await(30, TimeUnit.SECONDS);
            int phase3Success = successCount.get();

            executor.shutdown();

            assertThat(phase1Success).isEqualTo(50);
            assertThat(phase2Success).isGreaterThan(0);
            assertThat(phase3Success).isEqualTo(50);
        }
    }

    @Nested
    @DisplayName("Sliding Window Behavior")
    class SlidingWindowBehavior {

        @Test
        @DisplayName("should simulate sliding window rate limiting")
        void shouldSimulateSlidingWindowRateLimiting() throws Exception {
            int windowSeconds = 10;
            int limit = 100;
            AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
            AtomicInteger windowCount = new AtomicInteger(0);

            doAnswer(inv -> {
                long now = System.currentTimeMillis();
                long start = windowStart.get();

                if (now - start > windowSeconds * 1000) {
                    windowStart.set(now);
                    windowCount.set(0);
                }

                int count = windowCount.incrementAndGet();
                return count <= limit ? 1L : 0L;
            }).when(redisTemplate).execute(any(), anyList(), any(Object[].class));

            ExecutorService executor = Executors.newFixedThreadPool(10);
            AtomicInteger allowedInWindow = new AtomicInteger(0);
            AtomicInteger deniedInWindow = new AtomicInteger(0);
            AtomicReference<Throwable> firstError = new AtomicReference<>();

            CountDownLatch latch = new CountDownLatch(200);
            for (int i = 0; i < 200; i++) {
                executor.submit(() -> {
                    try {
                        boolean allowed = rateLimiter.tryAcquire("window:key", limit, windowSeconds);
                        if (allowed) {
                            allowedInWindow.incrementAndGet();
                        } else {
                            deniedInWindow.incrementAndGet();
                        }
                    } catch (Exception e) {
                        firstError.compareAndSet(null, e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(firstError.get()).isNull();
            assertThat(allowedInWindow.get()).isLessThanOrEqualTo(limit);
            assertThat(deniedInWindow.get()).isGreaterThan(0);
        }
    }
}
