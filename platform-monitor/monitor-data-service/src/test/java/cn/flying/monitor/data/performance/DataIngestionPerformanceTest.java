package cn.flying.monitor.data.performance;

import cn.flying.monitor.data.dto.BatchMetricsDTO;
import cn.flying.monitor.data.dto.MetricsDataDTO;
import cn.flying.monitor.data.service.DataIngestionService;
import cn.flying.monitor.data.service.InfluxDBService;
import cn.flying.monitor.data.service.MetricsCompressionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests for data ingestion service
 * Tests 1000+ RPS throughput and <100ms response time requirements
 */
@SpringBootTest
@ActiveProfiles("test")
public class DataIngestionPerformanceTest {
    
    private DataIngestionService dataIngestionService;
    private InfluxDBService influxDBService;
    private MetricsCompressionService compressionService;
    
    private ExecutorService executorService;
    private final int TARGET_RPS = 1000;
    private final int TEST_DURATION_SECONDS = 30;
    private final int MAX_RESPONSE_TIME_MS = 100;
    
    @BeforeEach
    void setUp() {
        // Initialize services (would be injected in real Spring context)
        executorService = Executors.newFixedThreadPool(50);
    }
    
    @Test
    void testSingleMetricsIngestionPerformance() throws InterruptedException {
        // Test individual metrics processing performance
        int totalRequests = TARGET_RPS * 10; // 10 seconds worth
        CountDownLatch latch = new CountDownLatch(totalRequests);
        AtomicLong totalResponseTime = new AtomicLong(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < totalRequests; i++) {
            final int requestId = i;
            executorService.submit(() -> {
                try {
                    long requestStart = System.nanoTime();
                    
                    MetricsDataDTO metrics = createTestMetrics("client-" + (requestId % 100));
                    Map<String, Object> result = dataIngestionService.processMetrics(metrics, false, false);
                    
                    long requestEnd = System.nanoTime();
                    long responseTimeMs = (requestEnd - requestStart) / 1_000_000;
                    
                    totalResponseTime.addAndGet(responseTimeMs);
                    
                    if ((Boolean) result.get("processed")) {
                        successCount.incrementAndGet();
                    } else {
                        errorCount.incrementAndGet();
                    }
                    
                    // Verify response time requirement
                    if (responseTimeMs > MAX_RESPONSE_TIME_MS) {
                        System.err.println("Response time exceeded: " + responseTimeMs + "ms for request " + requestId);
                    }
                    
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    System.err.println("Request failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(60, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();
        
        // Calculate performance metrics
        long totalTime = endTime - startTime;
        double actualRPS = (double) totalRequests / (totalTime / 1000.0);
        double averageResponseTime = (double) totalResponseTime.get() / totalRequests;
        double successRate = (double) successCount.get() / totalRequests * 100.0;
        
        // Performance assertions
        assertTrue(actualRPS >= TARGET_RPS * 0.8, 
                  "Actual RPS (" + actualRPS + ") should be at least 80% of target (" + TARGET_RPS + ")");
        assertTrue(averageResponseTime <= MAX_RESPONSE_TIME_MS, 
                  "Average response time (" + averageResponseTime + "ms) should be <= " + MAX_RESPONSE_TIME_MS + "ms");
        assertTrue(successRate >= 95.0, 
                  "Success rate (" + successRate + "%) should be >= 95%");
        
        System.out.println("Single Metrics Performance Results:");
        System.out.println("Total Requests: " + totalRequests);
        System.out.println("Actual RPS: " + String.format("%.2f", actualRPS));
        System.out.println("Average Response Time: " + String.format("%.2f", averageResponseTime) + "ms");
        System.out.println("Success Rate: " + String.format("%.2f", successRate) + "%");
        System.out.println("Error Count: " + errorCount.get());
    }
    
    @Test
    void testBatchMetricsIngestionPerformance() throws InterruptedException {
        // Test batch processing performance
        int batchSize = 100;
        int totalBatches = TARGET_RPS / 10; // Fewer batches but larger size
        CountDownLatch latch = new CountDownLatch(totalBatches);
        AtomicLong totalResponseTime = new AtomicLong(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < totalBatches; i++) {
            final int batchId = i;
            executorService.submit(() -> {
                try {
                    long requestStart = System.nanoTime();
                    
                    BatchMetricsDTO batchMetrics = createTestBatch(batchSize, "batch-" + batchId);
                    Map<String, Object> result = dataIngestionService.processBatchMetrics(batchMetrics, true, true);
                    
                    long requestEnd = System.nanoTime();
                    long responseTimeMs = (requestEnd - requestStart) / 1_000_000;
                    
                    totalResponseTime.addAndGet(responseTimeMs);
                    
                    if ((Boolean) result.get("processed")) {
                        successCount.incrementAndGet();
                    } else {
                        errorCount.incrementAndGet();
                    }
                    
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    System.err.println("Batch request failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(60, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();
        
        // Calculate performance metrics
        long totalTime = endTime - startTime;
        int totalMetrics = totalBatches * batchSize;
        double actualRPS = (double) totalMetrics / (totalTime / 1000.0);
        double averageResponseTime = (double) totalResponseTime.get() / totalBatches;
        double successRate = (double) successCount.get() / totalBatches * 100.0;
        
        // Performance assertions
        assertTrue(actualRPS >= TARGET_RPS * 0.8, 
                  "Batch RPS (" + actualRPS + ") should be at least 80% of target (" + TARGET_RPS + ")");
        assertTrue(successRate >= 95.0, 
                  "Batch success rate (" + successRate + "%) should be >= 95%");
        
        System.out.println("Batch Metrics Performance Results:");
        System.out.println("Total Batches: " + totalBatches);
        System.out.println("Total Metrics: " + totalMetrics);
        System.out.println("Actual RPS: " + String.format("%.2f", actualRPS));
        System.out.println("Average Batch Response Time: " + String.format("%.2f", averageResponseTime) + "ms");
        System.out.println("Success Rate: " + String.format("%.2f", successRate) + "%");
        System.out.println("Error Count: " + errorCount.get());
    }
    
    @Test
    void testCompressionPerformance() {
        // Test compression efficiency and performance
        int testIterations = 1000;
        long totalCompressionTime = 0;
        long totalOriginalSize = 0;
        long totalCompressedSize = 0;
        
        for (int i = 0; i < testIterations; i++) {
            MetricsDataDTO metrics = createTestMetrics("compression-test-" + i);
            
            long compressionStart = System.nanoTime();
            MetricsCompressionService.OptimizedMetricsData optimized = 
                compressionService.optimizeForTransmission(metrics);
            long compressionEnd = System.nanoTime();
            
            totalCompressionTime += (compressionEnd - compressionStart) / 1_000_000; // Convert to ms
            totalOriginalSize += optimized.getOriginalSize();
            totalCompressedSize += optimized.getCompressedSize();
        }
        
        double averageCompressionTime = (double) totalCompressionTime / testIterations;
        double compressionRatio = (double) totalCompressedSize / totalOriginalSize;
        double compressionSavings = (1.0 - compressionRatio) * 100.0;
        
        // Performance assertions
        assertTrue(averageCompressionTime <= 10.0, 
                  "Average compression time (" + averageCompressionTime + "ms) should be <= 10ms");
        assertTrue(compressionRatio <= 0.8, 
                  "Compression ratio (" + compressionRatio + ") should achieve at least 20% reduction");
        
        System.out.println("Compression Performance Results:");
        System.out.println("Test Iterations: " + testIterations);
        System.out.println("Average Compression Time: " + String.format("%.2f", averageCompressionTime) + "ms");
        System.out.println("Compression Ratio: " + String.format("%.3f", compressionRatio));
        System.out.println("Compression Savings: " + String.format("%.2f", compressionSavings) + "%");
        System.out.println("Total Original Size: " + totalOriginalSize + " bytes");
        System.out.println("Total Compressed Size: " + totalCompressedSize + " bytes");
    }
    
    @Test
    void testMemoryUsageUnderLoad() throws InterruptedException {
        // Test memory usage during high-load processing
        Runtime runtime = Runtime.getRuntime();
        
        // Force garbage collection before test
        System.gc();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();
        
        int totalRequests = 5000;
        CountDownLatch latch = new CountDownLatch(totalRequests);
        
        for (int i = 0; i < totalRequests; i++) {
            final int requestId = i;
            executorService.submit(() -> {
                try {
                    MetricsDataDTO metrics = createTestMetrics("memory-test-" + requestId);
                    dataIngestionService.processMetrics(metrics, true, false);
                } catch (Exception e) {
                    System.err.println("Memory test request failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(60, TimeUnit.SECONDS);
        
        // Force garbage collection after test
        System.gc();
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        
        long memoryIncrease = finalMemory - initialMemory;
        double memoryIncreasePerRequest = (double) memoryIncrease / totalRequests;
        
        // Memory usage assertions
        assertTrue(memoryIncreasePerRequest <= 1024, // 1KB per request max
                  "Memory increase per request (" + memoryIncreasePerRequest + " bytes) should be <= 1KB");
        
        System.out.println("Memory Usage Results:");
        System.out.println("Initial Memory: " + initialMemory / 1024 + " KB");
        System.out.println("Final Memory: " + finalMemory / 1024 + " KB");
        System.out.println("Memory Increase: " + memoryIncrease / 1024 + " KB");
        System.out.println("Memory per Request: " + String.format("%.2f", memoryIncreasePerRequest) + " bytes");
    }
    
    @Test
    void testConcurrentClientLoad() throws InterruptedException {
        // Test handling multiple concurrent clients
        int clientCount = 100;
        int requestsPerClient = 50;
        CountDownLatch latch = new CountDownLatch(clientCount * requestsPerClient);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        
        long startTime = System.currentTimeMillis();
        
        for (int clientId = 0; clientId < clientCount; clientId++) {
            final String client = "concurrent-client-" + clientId;
            
            for (int request = 0; request < requestsPerClient; request++) {
                executorService.submit(() -> {
                    try {
                        MetricsDataDTO metrics = createTestMetrics(client);
                        Map<String, Object> result = dataIngestionService.processMetrics(metrics, false, false);
                        
                        if ((Boolean) result.get("processed")) {
                            successCount.incrementAndGet();
                        } else {
                            errorCount.incrementAndGet();
                        }
                        
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }
        
        latch.await(120, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();
        
        int totalRequests = clientCount * requestsPerClient;
        long totalTime = endTime - startTime;
        double actualRPS = (double) totalRequests / (totalTime / 1000.0);
        double successRate = (double) successCount.get() / totalRequests * 100.0;
        
        // Concurrent load assertions
        assertTrue(actualRPS >= TARGET_RPS * 0.7, 
                  "Concurrent RPS (" + actualRPS + ") should be at least 70% of target under concurrent load");
        assertTrue(successRate >= 95.0, 
                  "Concurrent success rate (" + successRate + "%) should be >= 95%");
        
        System.out.println("Concurrent Client Load Results:");
        System.out.println("Client Count: " + clientCount);
        System.out.println("Requests per Client: " + requestsPerClient);
        System.out.println("Total Requests: " + totalRequests);
        System.out.println("Actual RPS: " + String.format("%.2f", actualRPS));
        System.out.println("Success Rate: " + String.format("%.2f", successRate) + "%");
        System.out.println("Error Count: " + errorCount.get());
    }
    
    private MetricsDataDTO createTestMetrics(String clientId) {
        MetricsDataDTO metrics = new MetricsDataDTO();
        metrics.setClientId(clientId);
        metrics.setTimestamp(Instant.now());
        metrics.setCpuUsage(Math.random() * 100);
        metrics.setMemoryUsage(Math.random() * 100);
        metrics.setDiskUsage(Math.random() * 100);
        metrics.setNetworkUpload(Math.random() * 1000000);
        metrics.setNetworkDownload(Math.random() * 1000000);
        metrics.setDiskRead(Math.random() * 100000);
        metrics.setDiskWrite(Math.random() * 100000);
        metrics.setLoadAverage(Math.random() * 4);
        metrics.setProcessCount((int) (Math.random() * 500) + 50);
        metrics.setNetworkConnections((int) (Math.random() * 1000) + 10);
        return metrics;
    }
    
    private BatchMetricsDTO createTestBatch(int size, String batchId) {
        BatchMetricsDTO batch = new BatchMetricsDTO();
        batch.setBatchId(batchId);
        batch.setBatchTimestamp(System.currentTimeMillis());
        batch.setCompressionEnabled(true);
        
        List<MetricsDataDTO> metricsList = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            metricsList.add(createTestMetrics(batchId + "-client-" + i));
        }
        batch.setMetrics(metricsList);
        
        return batch;
    }
}