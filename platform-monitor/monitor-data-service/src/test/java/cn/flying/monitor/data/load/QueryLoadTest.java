package cn.flying.monitor.data.load;

import cn.flying.monitor.data.dto.QueryRequestDTO;
import cn.flying.monitor.data.dto.QueryResultDTO;
import cn.flying.monitor.data.dto.AggregationRequestDTO;
import cn.flying.monitor.data.service.QueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Load tests for query service
 * Tests system behavior under high load and stress conditions
 * 
 * Note: These tests are disabled by default as they require significant resources
 * Enable them for load testing by removing @Disabled annotations
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.influx.url=http://localhost:8086",
    "spring.redis.host=localhost",
    "spring.redis.port=6379",
    "logging.level.cn.flying.monitor=INFO"
})
public class QueryLoadTest {
    
    @Autowired
    private QueryService queryService;
    
    private List<String> testClientIds;
    private Instant testStartTime;
    private Instant testEndTime;
    
    @BeforeEach
    void setUp() {
        // Create more test clients for load testing
        testClientIds = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            testClientIds.add("load-test-client-" + i);
        }
        
        testEndTime = Instant.now();
        testStartTime = testEndTime.minus(24, ChronoUnit.HOURS);
    }
    
    @Test
    @DisplayName("High Throughput Query Load Test - 1000 RPS")
    @Disabled("Enable for load testing")
    void testHighThroughputQueryLoad() throws InterruptedException {
        int targetRps = 1000; // Requests per second
        int testDurationSeconds = 60;
        int totalRequests = targetRps * testDurationSeconds;
        
        int numberOfThreads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(numberOfThreads);
        
        AtomicInteger completedRequests = new AtomicInteger(0);
        AtomicInteger failedRequests = new AtomicInteger(0);
        AtomicLong totalResponseTime = new AtomicLong(0);
        AtomicLong minResponseTime = new AtomicLong(Long.MAX_VALUE);
        AtomicLong maxResponseTime = new AtomicLong(0);
        
        // Response time buckets for percentile calculation
        ConcurrentHashMap<Integer, AtomicInteger> responseTimeBuckets = new ConcurrentHashMap<>();
        
        long testStartTime = System.currentTimeMillis();
        
        for (int i = 0; i < numberOfThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    
                    int requestsPerThread = totalRequests / numberOfThreads;
                    long threadStartTime = System.currentTimeMillis();
                    
                    for (int j = 0; j < requestsPerThread; j++) {
                        try {
                            // Distribute load across different clients
                            String clientId = testClientIds.get(j % testClientIds.size());
                            QueryRequestDTO request = createLoadTestQueryRequest(clientId);
                            Pageable pageable = PageRequest.of(0, 50);
                            
                            long requestStart = System.currentTimeMillis();
                            Page<QueryResultDTO> result = queryService.queryHistoricalMetrics(request, pageable);
                            long responseTime = System.currentTimeMillis() - requestStart;
                            
                            // Update statistics
                            completedRequests.incrementAndGet();
                            totalResponseTime.addAndGet(responseTime);
                            
                            // Update min/max response times
                            minResponseTime.updateAndGet(current -> Math.min(current, responseTime));
                            maxResponseTime.updateAndGet(current -> Math.max(current, responseTime));
                            
                            // Update response time buckets for percentiles
                            int bucket = (int) (responseTime / 10) * 10; // 10ms buckets
                            responseTimeBuckets.computeIfAbsent(bucket, k -> new AtomicInteger(0)).incrementAndGet();
                            
                            assertNotNull(result);
                            
                            // Rate limiting to achieve target RPS
                            long expectedTime = threadStartTime + (j * 1000L / (requestsPerThread / testDurationSeconds));
                            long currentTime = System.currentTimeMillis();
                            if (currentTime < expectedTime) {
                                Thread.sleep(expectedTime - currentTime);
                            }
                            
                        } catch (Exception e) {
                            failedRequests.incrementAndGet();
                            System.err.printf("Request failed in thread %d: %s%n", threadId, e.getMessage());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            });
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for completion
        assertTrue(completionLatch.await(testDurationSeconds + 30, TimeUnit.SECONDS), 
                  "Load test should complete within expected time");
        
        long actualTestDuration = System.currentTimeMillis() - testStartTime;
        
        // Calculate statistics
        int completed = completedRequests.get();
        int failed = failedRequests.get();
        double successRate = (double) completed / (completed + failed) * 100;
        double actualRps = (double) completed / (actualTestDuration / 1000.0);
        double avgResponseTime = completed > 0 ? (double) totalResponseTime.get() / completed : 0;
        
        // Calculate percentiles
        double p95ResponseTime = calculatePercentile(responseTimeBuckets, completed, 95);
        double p99ResponseTime = calculatePercentile(responseTimeBuckets, completed, 99);
        
        // Assertions
        assertTrue(successRate >= 99, "Success rate should be at least 99%, was: " + successRate + "%");
        assertTrue(actualRps >= targetRps * 0.9, "Should achieve at least 90% of target RPS, was: " + actualRps);
        assertTrue(avgResponseTime < 100, "Average response time should be under 100ms, was: " + avgResponseTime + "ms");
        assertTrue(p95ResponseTime < 200, "95th percentile should be under 200ms, was: " + p95ResponseTime + "ms");
        
        // Print detailed results
        System.out.printf("High Throughput Load Test Results:%n");
        System.out.printf("  Target RPS: %d, Actual RPS: %.2f%n", targetRps, actualRps);
        System.out.printf("  Total requests: %d, Completed: %d, Failed: %d%n", totalRequests, completed, failed);
        System.out.printf("  Success rate: %.2f%%%n", successRate);
        System.out.printf("  Response times - Min: %d ms, Max: %d ms, Avg: %.2f ms%n", 
                         minResponseTime.get(), maxResponseTime.get(), avgResponseTime);
        System.out.printf("  Percentiles - 95th: %.2f ms, 99th: %.2f ms%n", p95ResponseTime, p99ResponseTime);
        System.out.printf("  Test duration: %.2f seconds%n", actualTestDuration / 1000.0);
        
        executor.shutdown();
    }
    
    @Test
    @DisplayName("Sustained Load Test - 30 Minutes")
    @Disabled("Enable for extended load testing")
    void testSustainedLoad() throws InterruptedException {
        int targetRps = 500;
        int testDurationMinutes = 30;
        int testDurationSeconds = testDurationMinutes * 60;
        
        int numberOfThreads = 25;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(numberOfThreads);
        
        AtomicInteger completedRequests = new AtomicInteger(0);
        AtomicInteger failedRequests = new AtomicInteger(0);
        AtomicLong totalResponseTime = new AtomicLong(0);
        
        // Track performance over time
        ConcurrentHashMap<Integer, AtomicInteger> minutelyStats = new ConcurrentHashMap<>();
        
        long testStartTime = System.currentTimeMillis();
        
        for (int i = 0; i < numberOfThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    long threadStartTime = System.currentTimeMillis();
                    int requestCount = 0;
                    
                    while (System.currentTimeMillis() - threadStartTime < testDurationSeconds * 1000) {
                        try {
                            String clientId = testClientIds.get(requestCount % testClientIds.size());
                            QueryRequestDTO request = createLoadTestQueryRequest(clientId);
                            Pageable pageable = PageRequest.of(0, 30);
                            
                            long requestStart = System.currentTimeMillis();
                            Page<QueryResultDTO> result = queryService.queryHistoricalMetrics(request, pageable);
                            long responseTime = System.currentTimeMillis() - requestStart;
                            
                            completedRequests.incrementAndGet();
                            totalResponseTime.addAndGet(responseTime);
                            
                            // Track requests per minute
                            int minute = (int) ((System.currentTimeMillis() - testStartTime) / 60000);
                            minutelyStats.computeIfAbsent(minute, k -> new AtomicInteger(0)).incrementAndGet();
                            
                            assertNotNull(result);
                            
                            requestCount++;
                            
                            // Rate limiting
                            Thread.sleep(1000 / (targetRps / numberOfThreads));
                            
                        } catch (Exception e) {
                            failedRequests.incrementAndGet();
                            if (failedRequests.get() % 100 == 0) { // Log every 100th failure
                                System.err.printf("Request failed in thread %d (total failures: %d): %s%n", 
                                                 threadId, failedRequests.get(), e.getMessage());
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        
        // Monitor progress
        monitorTestProgress(completionLatch, completedRequests, failedRequests, testDurationMinutes);
        
        assertTrue(completionLatch.await(testDurationMinutes + 5, TimeUnit.MINUTES), 
                  "Sustained load test should complete within expected time");
        
        long actualTestDuration = System.currentTimeMillis() - testStartTime;
        
        // Calculate final statistics
        int completed = completedRequests.get();
        int failed = failedRequests.get();
        double successRate = (double) completed / (completed + failed) * 100;
        double actualRps = (double) completed / (actualTestDuration / 1000.0);
        double avgResponseTime = completed > 0 ? (double) totalResponseTime.get() / completed : 0;
        
        // Assertions for sustained load
        assertTrue(successRate >= 98, "Success rate should be at least 98% for sustained load, was: " + successRate + "%");
        assertTrue(actualRps >= targetRps * 0.8, "Should maintain at least 80% of target RPS, was: " + actualRps);
        assertTrue(avgResponseTime < 150, "Average response time should be under 150ms for sustained load, was: " + avgResponseTime + "ms");
        
        System.out.printf("Sustained Load Test Results (%d minutes):%n", testDurationMinutes);
        System.out.printf("  Target RPS: %d, Actual RPS: %.2f%n", targetRps, actualRps);
        System.out.printf("  Total requests: %d, Failed: %d%n", completed, failed);
        System.out.printf("  Success rate: %.2f%%%n", successRate);
        System.out.printf("  Average response time: %.2f ms%n", avgResponseTime);
        
        // Print per-minute statistics
        System.out.println("  Per-minute request counts:");
        minutelyStats.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> System.out.printf("    Minute %d: %d requests%n", 
                                                   entry.getKey(), entry.getValue().get()));
        
        executor.shutdown();
    }
    
    @Test
    @DisplayName("Spike Load Test")
    @Disabled("Enable for spike testing")
    void testSpikeLoad() throws InterruptedException {
        // Normal load: 100 RPS for 2 minutes
        // Spike load: 2000 RPS for 30 seconds
        // Recovery: 100 RPS for 2 minutes
        
        System.out.println("Starting Spike Load Test...");
        
        // Phase 1: Normal load
        System.out.println("Phase 1: Normal load (100 RPS for 2 minutes)");
        LoadTestResult normalLoad1 = executeLoadPhase(100, 120, 10);
        assertTrue(normalLoad1.successRate >= 99, "Normal load phase 1 should have high success rate");
        
        // Phase 2: Spike load
        System.out.println("Phase 2: Spike load (2000 RPS for 30 seconds)");
        LoadTestResult spikeLoad = executeLoadPhase(2000, 30, 100);
        assertTrue(spikeLoad.successRate >= 90, "Spike load should maintain reasonable success rate");
        
        // Phase 3: Recovery
        System.out.println("Phase 3: Recovery (100 RPS for 2 minutes)");
        LoadTestResult normalLoad2 = executeLoadPhase(100, 120, 10);
        assertTrue(normalLoad2.successRate >= 99, "Recovery phase should return to high success rate");
        
        System.out.printf("Spike Load Test Results:%n");
        System.out.printf("  Normal Load 1 - RPS: %.2f, Success: %.2f%%, Avg Response: %.2f ms%n",
                         normalLoad1.actualRps, normalLoad1.successRate, normalLoad1.avgResponseTime);
        System.out.printf("  Spike Load    - RPS: %.2f, Success: %.2f%%, Avg Response: %.2f ms%n",
                         spikeLoad.actualRps, spikeLoad.successRate, spikeLoad.avgResponseTime);
        System.out.printf("  Normal Load 2 - RPS: %.2f, Success: %.2f%%, Avg Response: %.2f ms%n",
                         normalLoad2.actualRps, normalLoad2.successRate, normalLoad2.avgResponseTime);
    }
    
    @Test
    @DisplayName("Memory Stress Test")
    @Disabled("Enable for memory stress testing")
    void testMemoryStressLoad() throws InterruptedException {
        Runtime runtime = Runtime.getRuntime();
        
        // Get baseline memory
        System.gc();
        long baselineMemory = runtime.totalMemory() - runtime.freeMemory();
        
        int targetRps = 800;
        int testDurationSeconds = 300; // 5 minutes
        int numberOfThreads = 40;
        
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(numberOfThreads);
        
        AtomicInteger completedRequests = new AtomicInteger(0);
        AtomicInteger failedRequests = new AtomicInteger(0);
        
        // Memory monitoring
        List<Long> memorySnapshots = Collections.synchronizedList(new ArrayList<>());
        ScheduledExecutorService memoryMonitor = Executors.newScheduledThreadPool(1);
        memoryMonitor.scheduleAtFixedRate(() -> {
            long currentMemory = runtime.totalMemory() - runtime.freeMemory();
            memorySnapshots.add(currentMemory);
        }, 0, 10, TimeUnit.SECONDS);
        
        long testStartTime = System.currentTimeMillis();
        
        for (int i = 0; i < numberOfThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    long threadStartTime = System.currentTimeMillis();
                    int requestCount = 0;
                    
                    while (System.currentTimeMillis() - threadStartTime < testDurationSeconds * 1000) {
                        try {
                            // Use larger page sizes to stress memory
                            String clientId = testClientIds.get(requestCount % testClientIds.size());
                            QueryRequestDTO request = createLoadTestQueryRequest(clientId);
                            Pageable pageable = PageRequest.of(0, 200); // Larger page size
                            
                            Page<QueryResultDTO> result = queryService.queryHistoricalMetrics(request, pageable);
                            completedRequests.incrementAndGet();
                            
                            assertNotNull(result);
                            
                            requestCount++;
                            Thread.sleep(1000 / (targetRps / numberOfThreads));
                            
                        } catch (Exception e) {
                            failedRequests.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        
        assertTrue(completionLatch.await(testDurationSeconds + 60, TimeUnit.SECONDS), 
                  "Memory stress test should complete within expected time");
        
        memoryMonitor.shutdown();
        
        long actualTestDuration = System.currentTimeMillis() - testStartTime;
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = memorySnapshots.stream().mapToLong(Long::longValue).max().orElse(finalMemory);
        
        int completed = completedRequests.get();
        int failed = failedRequests.get();
        double successRate = (double) completed / (completed + failed) * 100;
        double actualRps = (double) completed / (actualTestDuration / 1000.0);
        
        // Memory should not grow excessively
        long memoryIncrease = maxMemory - baselineMemory;
        assertTrue(memoryIncrease < 500 * 1024 * 1024, // 500MB limit
                  "Memory increase should be under 500MB, was: " + (memoryIncrease / 1024 / 1024) + "MB");
        
        assertTrue(successRate >= 95, "Success rate should be at least 95% under memory stress");
        
        System.out.printf("Memory Stress Test Results:%n");
        System.out.printf("  RPS: %.2f, Success rate: %.2f%%%n", actualRps, successRate);
        System.out.printf("  Memory - Baseline: %d MB, Max: %d MB, Final: %d MB%n",
                         baselineMemory / 1024 / 1024, maxMemory / 1024 / 1024, finalMemory / 1024 / 1024);
        System.out.printf("  Memory increase: %d MB%n", memoryIncrease / 1024 / 1024);
        
        executor.shutdown();
    }
    
    // Helper methods
    
    private QueryRequestDTO createLoadTestQueryRequest(String clientId) {
        QueryRequestDTO request = new QueryRequestDTO();
        request.setClientId(clientId);
        request.setStartTime(testStartTime);
        request.setEndTime(testEndTime);
        request.setMetricNames(Arrays.asList("cpu_usage", "memory_usage"));
        return request;
    }
    
    private LoadTestResult executeLoadPhase(int targetRps, int durationSeconds, int numberOfThreads) 
            throws InterruptedException {
        
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(numberOfThreads);
        
        AtomicInteger completedRequests = new AtomicInteger(0);
        AtomicInteger failedRequests = new AtomicInteger(0);
        AtomicLong totalResponseTime = new AtomicLong(0);
        
        long phaseStartTime = System.currentTimeMillis();
        
        for (int i = 0; i < numberOfThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    long threadStartTime = System.currentTimeMillis();
                    int requestCount = 0;
                    
                    while (System.currentTimeMillis() - threadStartTime < durationSeconds * 1000) {
                        try {
                            String clientId = testClientIds.get(requestCount % testClientIds.size());
                            QueryRequestDTO request = createLoadTestQueryRequest(clientId);
                            Pageable pageable = PageRequest.of(0, 50);
                            
                            long requestStart = System.currentTimeMillis();
                            Page<QueryResultDTO> result = queryService.queryHistoricalMetrics(request, pageable);
                            long responseTime = System.currentTimeMillis() - requestStart;
                            
                            completedRequests.incrementAndGet();
                            totalResponseTime.addAndGet(responseTime);
                            
                            assertNotNull(result);
                            
                            requestCount++;
                            Thread.sleep(1000 / (targetRps / numberOfThreads));
                            
                        } catch (Exception e) {
                            failedRequests.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        completionLatch.await(durationSeconds + 30, TimeUnit.SECONDS);
        
        long actualDuration = System.currentTimeMillis() - phaseStartTime;
        
        int completed = completedRequests.get();
        int failed = failedRequests.get();
        double successRate = (double) completed / (completed + failed) * 100;
        double actualRps = (double) completed / (actualDuration / 1000.0);
        double avgResponseTime = completed > 0 ? (double) totalResponseTime.get() / completed : 0;
        
        executor.shutdown();
        
        return new LoadTestResult(actualRps, successRate, avgResponseTime);
    }
    
    private void monitorTestProgress(CountDownLatch latch, AtomicInteger completed, 
                                   AtomicInteger failed, int durationMinutes) {
        ScheduledExecutorService monitor = Executors.newScheduledThreadPool(1);
        monitor.scheduleAtFixedRate(() -> {
            if (latch.getCount() != 0) {
                System.out.printf("Progress: %d completed, %d failed%n", completed.get(), failed.get());
            }
        }, 1, 1, TimeUnit.MINUTES);
        
        // Stop monitoring when test completes
        CompletableFuture.runAsync(() -> {
            try {
                latch.await();
                monitor.shutdown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
    
    private double calculatePercentile(ConcurrentHashMap<Integer, AtomicInteger> buckets, 
                                     int totalRequests, double percentile) {
        int targetCount = (int) (totalRequests * percentile / 100.0);
        int currentCount = 0;
        
        List<Integer> sortedBuckets = new ArrayList<>(buckets.keySet());
        Collections.sort(sortedBuckets);
        
        for (Integer bucket : sortedBuckets) {
            currentCount += buckets.get(bucket).get();
            if (currentCount >= targetCount) {
                return bucket;
            }
        }
        
        return sortedBuckets.isEmpty() ? 0 : sortedBuckets.get(sortedBuckets.size() - 1);
    }
    
    // Inner class for load test results
    private static class LoadTestResult {
        final double actualRps;
        final double successRate;
        final double avgResponseTime;
        
        LoadTestResult(double actualRps, double successRate, double avgResponseTime) {
            this.actualRps = actualRps;
            this.successRate = successRate;
            this.avgResponseTime = avgResponseTime;
        }
    }
}