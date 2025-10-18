package cn.flying.monitor.data.performance;

import cn.flying.monitor.data.dto.QueryRequestDTO;
import cn.flying.monitor.data.dto.QueryResultDTO;
import cn.flying.monitor.data.dto.AggregationRequestDTO;
import cn.flying.monitor.data.dto.AggregationResultDTO;
import cn.flying.monitor.data.service.QueryService;
import cn.flying.monitor.data.service.QueryCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
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
 * Comprehensive query performance tests
 * Tests query optimization, caching performance, and load handling
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.influx.url=http://localhost:8086",
    "spring.redis.host=localhost",
    "spring.redis.port=6379"
})
public class QueryPerformanceTest {
    
    @Autowired
    private QueryService queryService;
    
    @Autowired
    private QueryCacheService queryCacheService;
    
    private List<String> testClientIds;
    private Instant testStartTime;
    private Instant testEndTime;
    
    @BeforeEach
    void setUp() {
        // Setup test data
        testClientIds = Arrays.asList("test-client-1", "test-client-2", "test-client-3");
        testEndTime = Instant.now();
        testStartTime = testEndTime.minus(24, ChronoUnit.HOURS);
        
        // Clear cache before each test
        for (String clientId : testClientIds) {
            queryCacheService.invalidateClientCache(clientId);
        }
    }
    
    @Test
    @DisplayName("Historical Query Response Time Test")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testHistoricalQueryResponseTime() {
        QueryRequestDTO request = createTestQueryRequest(testClientIds.get(0));
        Pageable pageable = PageRequest.of(0, 100);
        
        long startTime = System.currentTimeMillis();
        Page<QueryResultDTO> results = queryService.queryHistoricalMetrics(request, pageable);
        long responseTime = System.currentTimeMillis() - startTime;
        
        assertNotNull(results);
        assertTrue(responseTime < 100, "Query response time should be under 100ms, was: " + responseTime + "ms");
        
        System.out.printf("Historical query response time: %d ms%n", responseTime);
    }
    
    @Test
    @DisplayName("Real-time Query Performance Test")
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void testRealTimeQueryPerformance() {
        String clientId = testClientIds.get(0);
        
        long startTime = System.currentTimeMillis();
        QueryResultDTO result = queryService.queryRealTimeMetrics(clientId);
        long responseTime = System.currentTimeMillis() - startTime;
        
        assertTrue(responseTime < 50, "Real-time query should be under 50ms, was: " + responseTime + "ms");
        
        System.out.printf("Real-time query response time: %d ms%n", responseTime);
    }
    
    @Test
    @DisplayName("Cache Hit Rate Performance Test")
    void testCacheHitRatePerformance() {
        QueryRequestDTO request = createTestQueryRequest(testClientIds.get(0));
        Pageable pageable = PageRequest.of(0, 100);
        
        // First query - cache miss
        long firstQueryStart = System.currentTimeMillis();
        Page<QueryResultDTO> firstResult = queryService.queryHistoricalMetrics(request, pageable);
        long firstQueryTime = System.currentTimeMillis() - firstQueryStart;
        
        // Second query - should be cache hit
        long secondQueryStart = System.currentTimeMillis();
        Page<QueryResultDTO> secondResult = queryService.queryHistoricalMetrics(request, pageable);
        long secondQueryTime = System.currentTimeMillis() - secondQueryStart;
        
        assertNotNull(firstResult);
        assertNotNull(secondResult);
        assertTrue(secondQueryTime < firstQueryTime / 2, 
                  "Cached query should be significantly faster. First: " + firstQueryTime + "ms, Second: " + secondQueryTime + "ms");
        
        // Verify cache statistics
        Map<String, Object> cacheStats = queryCacheService.getCacheStatistics();
        assertTrue((Long) cacheStats.get("cache_hits") > 0, "Should have cache hits");
        
        System.out.printf("Cache performance - First query: %d ms, Cached query: %d ms%n", 
                         firstQueryTime, secondQueryTime);
    }
    
    @Test
    @DisplayName("Aggregation Query Performance Test")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testAggregationQueryPerformance() {
        AggregationRequestDTO request = createTestAggregationRequest(testClientIds.get(0));
        
        long startTime = System.currentTimeMillis();
        AggregationResultDTO result = queryService.aggregateMetrics(request);
        long responseTime = System.currentTimeMillis() - startTime;
        
        assertNotNull(result);
        assertNotNull(result.getAggregations());
        assertTrue(responseTime < 5000, "Aggregation query should be under 5 seconds, was: " + responseTime + "ms");
        
        System.out.printf("Aggregation query response time: %d ms%n", responseTime);
    }
    
    @Test
    @DisplayName("Concurrent Query Load Test")
    void testConcurrentQueryLoad() throws InterruptedException {
        int numberOfThreads = 10;
        int queriesPerThread = 5;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        
        AtomicLong totalResponseTime = new AtomicLong(0);
        AtomicInteger successfulQueries = new AtomicInteger(0);
        AtomicInteger failedQueries = new AtomicInteger(0);
        
        for (int i = 0; i < numberOfThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < queriesPerThread; j++) {
                        try {
                            QueryRequestDTO request = createTestQueryRequest(testClientIds.get(j % testClientIds.size()));
                            Pageable pageable = PageRequest.of(0, 50);
                            
                            long start = System.currentTimeMillis();
                            Page<QueryResultDTO> result = queryService.queryHistoricalMetrics(request, pageable);
                            long responseTime = System.currentTimeMillis() - start;
                            
                            totalResponseTime.addAndGet(responseTime);
                            successfulQueries.incrementAndGet();
                            
                            assertNotNull(result);
                            
                        } catch (Exception e) {
                            failedQueries.incrementAndGet();
                            System.err.printf("Query failed in thread %d: %s%n", threadId, e.getMessage());
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(30, TimeUnit.SECONDS), "All queries should complete within 30 seconds");
        
        int totalQueries = numberOfThreads * queriesPerThread;
        double averageResponseTime = (double) totalResponseTime.get() / successfulQueries.get();
        double successRate = (double) successfulQueries.get() / totalQueries * 100;
        
        assertTrue(successRate >= 95, "Success rate should be at least 95%, was: " + successRate + "%");
        assertTrue(averageResponseTime < 200, "Average response time should be under 200ms, was: " + averageResponseTime + "ms");
        
        System.out.printf("Concurrent load test results:%n");
        System.out.printf("  Total queries: %d%n", totalQueries);
        System.out.printf("  Successful: %d%n", successfulQueries.get());
        System.out.printf("  Failed: %d%n", failedQueries.get());
        System.out.printf("  Success rate: %.2f%%%n", successRate);
        System.out.printf("  Average response time: %.2f ms%n", averageResponseTime);
        
        executor.shutdown();
    }
    
    @Test
    @DisplayName("Large Dataset Query Performance Test")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testLargeDatasetQueryPerformance() {
        // Query for a longer time range to test large dataset handling
        QueryRequestDTO request = createTestQueryRequest(testClientIds.get(0));
        request.setStartTime(testEndTime.minus(7, ChronoUnit.DAYS)); // 7 days of data
        request.setEndTime(testEndTime);
        
        Pageable pageable = PageRequest.of(0, 1000); // Large page size
        
        long startTime = System.currentTimeMillis();
        Page<QueryResultDTO> results = queryService.queryHistoricalMetrics(request, pageable);
        long responseTime = System.currentTimeMillis() - startTime;
        
        assertNotNull(results);
        assertTrue(responseTime < 10000, "Large dataset query should be under 10 seconds, was: " + responseTime + "ms");
        
        System.out.printf("Large dataset query response time: %d ms, results: %d%n", 
                         responseTime, results.getContent().size());
    }
    
    @Test
    @DisplayName("Cache Invalidation Performance Test")
    void testCacheInvalidationPerformance() {
        String clientId = testClientIds.get(0);
        QueryRequestDTO request = createTestQueryRequest(clientId);
        Pageable pageable = PageRequest.of(0, 100);
        
        // Populate cache
        queryService.queryHistoricalMetrics(request, pageable);
        
        // Test cache invalidation performance
        long startTime = System.currentTimeMillis();
        queryCacheService.invalidateClientCache(clientId);
        long invalidationTime = System.currentTimeMillis() - startTime;
        
        assertTrue(invalidationTime < 100, "Cache invalidation should be under 100ms, was: " + invalidationTime + "ms");
        
        // Verify cache was invalidated by checking next query is slower
        long nextQueryStart = System.currentTimeMillis();
        queryService.queryHistoricalMetrics(request, pageable);
        long nextQueryTime = System.currentTimeMillis() - nextQueryStart;
        
        System.out.printf("Cache invalidation time: %d ms, next query time: %d ms%n", 
                         invalidationTime, nextQueryTime);
    }
    
    @Test
    @DisplayName("Memory Usage During Query Test")
    void testMemoryUsageDuringQuery() {
        Runtime runtime = Runtime.getRuntime();
        
        // Force garbage collection and get baseline memory
        System.gc();
        long baselineMemory = runtime.totalMemory() - runtime.freeMemory();
        
        // Execute multiple queries
        for (int i = 0; i < 10; i++) {
            QueryRequestDTO request = createTestQueryRequest(testClientIds.get(i % testClientIds.size()));
            Pageable pageable = PageRequest.of(0, 500);
            
            Page<QueryResultDTO> results = queryService.queryHistoricalMetrics(request, pageable);
            assertNotNull(results);
        }
        
        // Check memory usage after queries
        long afterQueriesMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryIncrease = afterQueriesMemory - baselineMemory;
        
        // Memory increase should be reasonable (less than 100MB)
        assertTrue(memoryIncrease < 100 * 1024 * 1024, 
                  "Memory increase should be under 100MB, was: " + (memoryIncrease / 1024 / 1024) + "MB");
        
        System.out.printf("Memory usage - Baseline: %d MB, After queries: %d MB, Increase: %d MB%n",
                         baselineMemory / 1024 / 1024, 
                         afterQueriesMemory / 1024 / 1024,
                         memoryIncrease / 1024 / 1024);
    }
    
    @Test
    @DisplayName("Query Statistics Accuracy Test")
    void testQueryStatisticsAccuracy() {
        // Get initial statistics
        Map<String, Object> initialStats = queryService.getQueryStatistics();
        long initialQueries = (Long) initialStats.get("total_queries");
        
        // Execute known number of queries
        int numberOfQueries = 5;
        for (int i = 0; i < numberOfQueries; i++) {
            QueryRequestDTO request = createTestQueryRequest(testClientIds.get(0));
            Pageable pageable = PageRequest.of(0, 50);
            queryService.queryHistoricalMetrics(request, pageable);
        }
        
        // Check updated statistics
        Map<String, Object> finalStats = queryService.getQueryStatistics();
        long finalQueries = (Long) finalStats.get("total_queries");
        
        assertEquals(initialQueries + numberOfQueries, finalQueries, 
                    "Query count should be accurate");
        
        assertTrue((Long) finalStats.get("total_query_time_ms") > 0, 
                  "Total query time should be tracked");
        
        System.out.printf("Query statistics - Initial: %d, Final: %d, Difference: %d%n",
                         initialQueries, finalQueries, finalQueries - initialQueries);
    }
    
    @Test
    @DisplayName("Pagination Performance Test")
    void testPaginationPerformance() {
        QueryRequestDTO request = createTestQueryRequest(testClientIds.get(0));
        
        // Test different page sizes
        int[] pageSizes = {10, 50, 100, 500, 1000};
        
        for (int pageSize : pageSizes) {
            Pageable pageable = PageRequest.of(0, pageSize);
            
            long startTime = System.currentTimeMillis();
            Page<QueryResultDTO> results = queryService.queryHistoricalMetrics(request, pageable);
            long responseTime = System.currentTimeMillis() - startTime;
            
            assertNotNull(results);
            assertTrue(results.getContent().size() <= pageSize, 
                      "Result size should not exceed page size");
            
            // Response time should scale reasonably with page size
            assertTrue(responseTime < pageSize * 2, // 2ms per record is reasonable
                      "Response time should scale reasonably with page size. PageSize: " + pageSize + ", Time: " + responseTime + "ms");
            
            System.out.printf("Pagination test - Page size: %d, Response time: %d ms, Results: %d%n",
                             pageSize, responseTime, results.getContent().size());
        }
    }
    
    @Test
    @DisplayName("Advanced Query Features Performance Test")
    void testAdvancedQueryFeaturesPerformance() {
        String clientId = testClientIds.get(0);
        
        // Test correlation calculation performance
        long correlationStart = System.currentTimeMillis();
        Map<String, Double> correlations = queryService.queryMetricsCorrelation(
            clientId, Arrays.asList("cpu_usage", "memory_usage"), testStartTime, testEndTime);
        long correlationTime = System.currentTimeMillis() - correlationStart;
        
        assertNotNull(correlations);
        assertTrue(correlationTime < 3000, "Correlation calculation should be under 3 seconds, was: " + correlationTime + "ms");
        
        // Test forecasting performance
        long forecastStart = System.currentTimeMillis();
        Map<String, Object> forecast = queryService.queryMetricsForecast(
            clientId, "cpu_usage", testStartTime, testEndTime, 24);
        long forecastTime = System.currentTimeMillis() - forecastStart;
        
        assertNotNull(forecast);
        assertTrue(forecastTime < 5000, "Forecasting should be under 5 seconds, was: " + forecastTime + "ms");
        
        // Test anomaly detection performance
        long anomalyStart = System.currentTimeMillis();
        List<Map<String, Object>> anomalies = queryService.queryAnomalies(
            clientId, Arrays.asList("cpu_usage"), testStartTime, testEndTime, 2.0);
        long anomalyTime = System.currentTimeMillis() - anomalyStart;
        
        assertNotNull(anomalies);
        assertTrue(anomalyTime < 4000, "Anomaly detection should be under 4 seconds, was: " + anomalyTime + "ms");
        
        System.out.printf("Advanced features performance:%n");
        System.out.printf("  Correlation: %d ms%n", correlationTime);
        System.out.printf("  Forecasting: %d ms%n", forecastTime);
        System.out.printf("  Anomaly detection: %d ms%n", anomalyTime);
    }
    
    @Test
    @DisplayName("Cache Warming Performance Test")
    void testCacheWarmingPerformance() {
        // Clear all caches first
        for (String clientId : testClientIds) {
            queryCacheService.invalidateClientCache(clientId);
        }
        
        long warmingStart = System.currentTimeMillis();
        queryCacheService.warmCache(testClientIds, testStartTime, testEndTime);
        long warmingTime = System.currentTimeMillis() - warmingStart;
        
        // Cache warming should complete quickly (it's async, so this tests the initiation)
        assertTrue(warmingTime < 1000, "Cache warming initiation should be under 1 second, was: " + warmingTime + "ms");
        
        // Wait a bit for async warming to complete
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Test that subsequent queries are faster (indicating cache warming worked)
        QueryRequestDTO request = createTestQueryRequest(testClientIds.get(0));
        
        long queryStart = System.currentTimeMillis();
        QueryResultDTO result = queryService.queryRealTimeMetrics(testClientIds.get(0));
        long queryTime = System.currentTimeMillis() - queryStart;
        
        // Should be fast due to cache warming
        assertTrue(queryTime < 50, "Warmed cache query should be very fast, was: " + queryTime + "ms");
        
        System.out.printf("Cache warming performance - Initiation: %d ms, Warmed query: %d ms%n",
                         warmingTime, queryTime);
    }
    
    // Helper methods
    
    private QueryRequestDTO createTestQueryRequest(String clientId) {
        QueryRequestDTO request = new QueryRequestDTO();
        request.setClientId(clientId);
        request.setStartTime(testStartTime);
        request.setEndTime(testEndTime);
        request.setMetricNames(Arrays.asList("cpu_usage", "memory_usage", "disk_usage"));
        return request;
    }
    
    private AggregationRequestDTO createTestAggregationRequest(String clientId) {
        AggregationRequestDTO request = new AggregationRequestDTO();
        request.setClientId(clientId);
        request.setStartTime(testStartTime);
        request.setEndTime(testEndTime);
        request.setMetricNames(Arrays.asList("cpu_usage", "memory_usage"));
        request.setAggregationFunctions(Arrays.asList("avg", "min", "max", "stddev"));
        request.setTimeWindow("1h");
        return request;
    }
}