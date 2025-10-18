package cn.flying.monitor.common.performance;

import cn.flying.monitor.common.service.CustomMetricsService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 指标收集性能测试
 */
class MetricsPerformanceTest {

    private MeterRegistry meterRegistry;
    private CustomMetricsService customMetricsService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        customMetricsService = new CustomMetricsService(meterRegistry);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testHighVolumeMetricsCollection() {
        // Given
        int numberOfOperations = 10000;
        long startTime = System.currentTimeMillis();

        // When - 执行大量指标收集操作
        for (int i = 0; i < numberOfOperations; i++) {
            customMetricsService.recordDataIngestion(
                "client-" + (i % 100), // 100个不同的客户端
                i % 1000 + 1, // 1-1000条记录
                Duration.ofMillis(i % 100 + 1) // 1-100ms处理时间
            );
        }

        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;

        // Then - 验证性能
        assertThat(totalTime).isLessThan(3000); // 应该在3秒内完成
        
        double operationsPerSecond = (double) numberOfOperations / (totalTime / 1000.0);
        assertThat(operationsPerSecond).isGreaterThan(3000); // 至少3000 ops/sec

        System.out.printf("指标收集性能: %d 操作在 %d ms 内完成 (%.2f ops/sec)%n", 
                         numberOfOperations, totalTime, operationsPerSecond);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testConcurrentMetricsCollection() throws InterruptedException {
        // Given
        int numberOfThreads = 10;
        int operationsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        
        long startTime = System.currentTimeMillis();

        // When - 并发执行指标收集
        for (int t = 0; t < numberOfThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        // 混合不同类型的指标操作
                        customMetricsService.recordDataIngestion(
                            "client-" + threadId + "-" + i,
                            i + 1,
                            Duration.ofMillis(i % 50 + 1)
                        );
                        
                        customMetricsService.recordQueryPerformance(
                            "query-type-" + (i % 5),
                            Duration.ofMillis(i % 100 + 1),
                            i % 2 == 0
                        );
                        
                        customMetricsService.recordAuthentication(
                            "jwt",
                            i % 10 != 0, // 90%成功率
                            Duration.ofMillis(i % 20 + 1)
                        );
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Then - 等待所有线程完成
        boolean completed = latch.await(8, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        
        executor.shutdown();
        boolean terminated = executor.awaitTermination(2, TimeUnit.SECONDS);
        assertThat(terminated).isTrue();

        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;
        int totalOperations = numberOfThreads * operationsPerThread * 3; // 3种类型的操作

        double operationsPerSecond = (double) totalOperations / (totalTime / 1000.0);
        assertThat(operationsPerSecond).isGreaterThan(1000); // 至少1000 ops/sec

        System.out.printf("并发指标收集性能: %d 操作在 %d ms 内完成 (%.2f ops/sec)%n", 
                         totalOperations, totalTime, operationsPerSecond);
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.SECONDS)
    void testMetricsMemoryUsage() {
        // Given
        Runtime runtime = Runtime.getRuntime();
        runtime.gc(); // 强制垃圾回收
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();

        // When - 创建大量指标
        for (int i = 0; i < 5000; i++) {
            customMetricsService.recordDataIngestion(
                "client-" + i, // 每个客户端都不同，增加内存使用
                100,
                Duration.ofMillis(50)
            );
        }

        runtime.gc(); // 再次垃圾回收
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryUsed = finalMemory - initialMemory;

        // Then - 验证内存使用合理
        double memoryPerOperation = (double) memoryUsed / 5000;
        assertThat(memoryPerOperation).isLessThan(1024); // 每个操作少于1KB内存

        System.out.printf("内存使用: %d bytes 用于 5000 操作 (%.2f bytes/op)%n", 
                         memoryUsed, memoryPerOperation);
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void testCustomCounterPerformance() {
        // Given
        int numberOfCounters = 1000;
        long startTime = System.currentTimeMillis();

        // When - 创建和使用大量自定义计数器
        for (int i = 0; i < numberOfCounters; i++) {
            var counter = customMetricsService.createCounter(
                "test.counter." + i,
                "Test counter " + i,
                "type", "performance",
                "index", String.valueOf(i)
            );
            counter.increment(i + 1);
        }

        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;

        // Then - 验证性能
        assertThat(totalTime).isLessThan(1000); // 应该在1秒内完成
        
        double countersPerSecond = (double) numberOfCounters / (totalTime / 1000.0);
        assertThat(countersPerSecond).isGreaterThan(500); // 至少500 counters/sec

        System.out.printf("计数器创建性能: %d 计数器在 %d ms 内完成 (%.2f counters/sec)%n", 
                         numberOfCounters, totalTime, countersPerSecond);
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void testTimerPerformance() {
        // Given
        int numberOfTimers = 500;
        long startTime = System.currentTimeMillis();

        // When - 创建和使用大量计时器
        for (int i = 0; i < numberOfTimers; i++) {
            var timer = customMetricsService.createTimer(
                "test.timer." + i,
                "Test timer " + i,
                "operation", "test",
                "index", String.valueOf(i)
            );
            timer.record(Duration.ofMillis(i % 100 + 1));
        }

        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;

        // Then - 验证性能
        assertThat(totalTime).isLessThan(1000); // 应该在1秒内完成
        
        double timersPerSecond = (double) numberOfTimers / (totalTime / 1000.0);
        assertThat(timersPerSecond).isGreaterThan(250); // 至少250 timers/sec

        System.out.printf("计时器创建性能: %d 计时器在 %d ms 内完成 (%.2f timers/sec)%n", 
                         numberOfTimers, totalTime, timersPerSecond);
    }

    @Test
    void testMetricsAccuracy() {
        // Given
        String clientId = "accuracy-test-client";
        int expectedRecords = 1000;
        Duration expectedTotalTime = Duration.ZERO;

        // When - 记录精确的指标数据
        for (int i = 1; i <= expectedRecords; i++) {
            Duration processingTime = Duration.ofMillis(i);
            expectedTotalTime = expectedTotalTime.plus(processingTime);
            
            customMetricsService.recordDataIngestion(clientId, 1, processingTime);
        }

        // Then - 验证指标准确性
        var recordCounter = meterRegistry.find("monitor.data.ingestion.records")
            .tag("client_id", clientId)
            .counter();
        assertThat(recordCounter).isNotNull();
        assertThat(recordCounter.count()).isEqualTo(expectedRecords);

        var processingTimer = meterRegistry.find("monitor.data.ingestion.processing.time")
            .tag("client_id", clientId)
            .timer();
        assertThat(processingTimer).isNotNull();
        assertThat(processingTimer.count()).isEqualTo(expectedRecords);
        assertThat(processingTimer.totalTime(TimeUnit.MILLISECONDS))
            .isEqualTo(expectedTotalTime.toMillis());
    }
}