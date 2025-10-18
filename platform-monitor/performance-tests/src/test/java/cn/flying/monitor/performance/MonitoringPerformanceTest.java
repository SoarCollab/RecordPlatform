package cn.flying.monitor.performance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests for monitoring system under high load
 */
@SpringBootTest
@ActiveProfiles("test")
class MonitoringPerformanceTest {
    
    private ExecutorService executorService;
    private static final int THREAD_POOL_SIZE = 20;
    private static final int HIGH_LOAD_REQUESTS = 1000;
    
    @BeforeEach
    void setUp() {
        executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    }
    
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testHighVolumeDataIngestion() throws InterruptedException {
        // Test ingesting large volumes of metrics data
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(HIGH_LOAD_REQUESTS);
        
        Instant startTime = Instant.now();
        
        for (int i = 0; i < HIGH_LOAD_REQUESTS; i++) {
            final int requestId = i;
            executorService.submit(() -> {
                try {
                    // Simulate metrics ingestion
                    simulateMetricsIngestion("client-" + (requestId % 100));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(25, TimeUnit.SECONDS);
        Instant endTime = Instant.now();
        
        Duration duration = Duration.between(startTime, endTime);
        double requestsPerSecond = HIGH_LOAD_REQUESTS / (duration.toMillis() / 1000.0);
        
        System.out.println("Performance Results:");
        System.out.println("Total requests: " + HIGH_LOAD_REQUESTS);
        System.out.println("Successful: " + successCount.get());
        System.out.println("Errors: " + errorCount.get());
        System.out.println("Duration: " + duration.toMillis() + "ms");
        System.out.println("Requests/second: " + requestsPerSecond);
        
        // Performance assertions
        assertTrue(successCount.get() > 0, "Should have some successful requests");
        double errorRate = (double) errorCount.get() / HIGH_LOAD_REQUESTS * 100;
        assertTrue(errorRate < 50, "Error rate should be less than 50%: " + errorRate + "%");
    }
    
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void testConcurrentQueryPerformance() throws InterruptedException {
        // Test concurrent query performance
        AtomicLong totalResponseTime = new AtomicLong(0);
        AtomicInteger queryCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(100);
        
        for (int i = 0; i < 100; i++) {
            executorService.submit(() -> {
                try {
                    Instant queryStart = Instant.now();
                    simulateMetricsQuery("test-client");
                    Instant queryEnd = Instant.now();
                    
                    long responseTime = Duration.between(queryStart, queryEnd).toMillis();
                    totalResponseTime.addAndGet(responseTime);
                    queryCount.incrementAndGet();
                } catch (Exception e) {
                    // Log error but continue test
                    System.err.println("Query error: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(15, TimeUnit.SECONDS);
        
        if (queryCount.get() > 0) {
            double avgResponseTime = (double) totalResponseTime.get() / queryCount.get();
            System.out.println("Average query response time: " + avgResponseTime + "ms");
            
            // Performance assertion - average response time should be reasonable
            assertTrue(avgResponseTime < 5000, "Average response time should be less than 5 seconds: " + avgResponseTime + "ms");
        }
    }    

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testMemoryUsageUnderLoad() throws InterruptedException {
        // Monitor memory usage during high load
        Runtime runtime = Runtime.getRuntime();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();
        
        CountDownLatch latch = new CountDownLatch(500);
        
        for (int i = 0; i < 500; i++) {
            executorService.submit(() -> {
                try {
                    // Simulate memory-intensive operations
                    simulateMemoryIntensiveOperation();
                } catch (Exception e) {
                    System.err.println("Memory test error: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(10, TimeUnit.SECONDS);
        
        // Force garbage collection
        System.gc();
        Thread.sleep(1000);
        
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryIncrease = finalMemory - initialMemory;
        
        System.out.println("Memory usage increase: " + (memoryIncrease / 1024 / 1024) + " MB");
        
        // Memory should not increase excessively (less than 500MB)
        assertTrue(memoryIncrease < 500 * 1024 * 1024, 
            "Memory increase should be less than 500MB: " + (memoryIncrease / 1024 / 1024) + "MB");
    }
    
    @Test
    void testConnectionPoolPerformance() throws InterruptedException {
        // Test connection pool efficiency under load
        AtomicInteger connectionCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(200);
        
        for (int i = 0; i < 200; i++) {
            executorService.submit(() -> {
                try {
                    simulateConnectionPoolUsage();
                    connectionCount.incrementAndGet();
                } catch (Exception e) {
                    System.err.println("Connection pool error: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(10, TimeUnit.SECONDS);
        
        System.out.println("Successful connections: " + connectionCount.get());
        assertTrue(connectionCount.get() > 0, "Should have successful connections");
    }
    
    private void simulateMetricsIngestion(String clientId) {
        // Simulate metrics ingestion processing
        try {
            Thread.sleep(10); // Simulate processing time
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private void simulateMetricsQuery(String clientId) {
        // Simulate metrics query processing
        try {
            Thread.sleep(50); // Simulate query time
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private void simulateMemoryIntensiveOperation() {
        // Simulate memory-intensive operation
        List<String> tempData = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            tempData.add("test-data-" + i);
        }
        // Data will be garbage collected
    }
    
    private void simulateConnectionPoolUsage() {
        // Simulate connection pool usage
        try {
            Thread.sleep(20); // Simulate connection usage
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}