package cn.flying.monitor.data.performance;

import cn.flying.monitor.data.dto.QueryRequestDTO;
import cn.flying.monitor.data.dto.QueryResultDTO;
import cn.flying.monitor.data.dto.AggregationResultDTO;
import cn.flying.monitor.data.service.QueryCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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
 * Cache performance and behavior tests
 * Tests cache hit rates, TTL behavior, and cache invalidation performance
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.redis.host=localhost",
    "spring.redis.port=6379",
    "spring.cache.redis.time-to-live=300000"
})
public class CachePerformanceTest {
    
    @Autowired
    private QueryCacheService queryCacheService;
    
    private List<String> testClientIds;
    private Instant testStartTime;
    private Instant testEndTime;
    
    @BeforeEach
    void setUp() {
        testClientIds = Arrays.asList("cache-test-1", "cache-test-2", "cache-test-3");
        testEndTime = Instant.now();
        testStartTime = testEndTime.minus(1, ChronoUnit.HOURS);
        
        // Clear all test caches
        for (String clientId : testClientIds) {
            queryCacheService.invalidateClientCache(clientId);
        }
    }
    
    @Test
    @DisplayName("Cache Hit Rate Performance Test")
    void testCacheHitRatePerformance() {
        String clientId = testClientIds.get(0);
        QueryRequestDTO request = createTestQueryRequest(clientId);
        Pageable pageable = PageRequest.of(0, 100);
        
        // Create test data
        List<QueryResultDTO> testData = createTestQueryResults(clientId, 100);
        Page<QueryResultDTO> testPage = new PageImpl<>(testData, pageable, testData.size());
        
        String cacheKey = queryCacheService.generateCacheKey(request, pageable);
        
        // Cache the data
        long cacheStart = System.currentTimeMillis();
        queryCacheService.cacheHistoricalMetrics(cacheKey, testPage, 300);
        long cacheTime = System.currentTimeMillis() - cacheStart;
        
        assertTrue(cacheTime < 50, "Caching should be under 50ms, was: " + cacheTime + "ms");
        
        // Test cache retrieval performance
        long retrievalStart = System.currentTimeMillis();
        Optional<Page<QueryResultDTO>> cachedResult = queryCacheService.getCachedHistoricalMetrics(cacheKey);
        long retrievalTime = System.currentTimeMillis() - retrievalStart;
        
        assertTrue(cachedResult.isPresent(), "Cached data should be present");
        assertTrue(retrievalTime < 10, "Cache retrieval should be under 10ms, was: " + retrievalTime + "ms");
        assertEquals(testData.size(), cachedResult.get().getContent().size(), "Cached data size should match");
        
        System.out.printf("Cache performance - Store: %d ms, Retrieve: %d ms%n", cacheTime, retrievalTime);
    }
    
    @Test
    @DisplayName("Concurrent Cache Access Performance Test")
    void testConcurrentCacheAccessPerformance() throws InterruptedException {
        int numberOfThreads = 20;
        int operationsPerThread = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        
        AtomicLong totalCacheTime = new AtomicLong(0);
        AtomicLong totalRetrievalTime = new AtomicLong(0);
        AtomicInteger cacheOperations = new AtomicInteger(0);
        AtomicInteger retrievalOperations = new AtomicInteger(0);
        AtomicInteger cacheHits = new AtomicInteger(0);
        AtomicInteger cacheMisses = new AtomicInteger(0);
        
        for (int i = 0; i < numberOfThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        String clientId = testClientIds.get(j % testClientIds.size());
                        QueryRequestDTO request = createTestQueryRequest(clientId);
                        Pageable pageable = PageRequest.of(0, 50);
                        String cacheKey = queryCacheService.generateCacheKey(request, pageable);
                        
                        // Try to retrieve from cache first
                        long retrievalStart = System.currentTimeMillis();
                        Optional<Page<QueryResultDTO>> cached = queryCacheService.getCachedHistoricalMetrics(cacheKey);
                        long retrievalTime = System.currentTimeMillis() - retrievalStart;
                        
                        totalRetrievalTime.addAndGet(retrievalTime);
                        retrievalOperations.incrementAndGet();
                        
                        if (cached.isPresent()) {
                            cacheHits.incrementAndGet();
                        } else {
                            cacheMisses.incrementAndGet();
                            
                            // Cache miss - store data
                            List<QueryResultDTO> testData = createTestQueryResults(clientId, 50);
                            Page<QueryResultDTO> testPage = new PageImpl<>(testData, pageable, testData.size());
                            
                            long cacheStart = System.currentTimeMillis();
                            queryCacheService.cacheHistoricalMetrics(cacheKey, testPage, 300);
                            long cacheTime = System.currentTimeMillis() - cacheStart;
                            
                            totalCacheTime.addAndGet(cacheTime);
                            cacheOperations.incrementAndGet();
                        }
                        
                        // Small delay to simulate realistic usage
                        Thread.sleep(10);
                    }
                } catch (Exception e) {
                    System.err.printf("Cache operation failed in thread %d: %s%n", threadId, e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(30, TimeUnit.SECONDS), "All cache operations should complete within 30 seconds");
        
        double avgCacheTime = cacheOperations.get() > 0 ? (double) totalCacheTime.get() / cacheOperations.get() : 0;
        double avgRetrievalTime = (double) totalRetrievalTime.get() / retrievalOperations.get();
        double hitRate = (double) cacheHits.get() / (cacheHits.get() + cacheMisses.get()) * 100;
        
        assertTrue(avgCacheTime < 100, "Average cache time should be under 100ms, was: " + avgCacheTime + "ms");
        assertTrue(avgRetrievalTime < 20, "Average retrieval time should be under 20ms, was: " + avgRetrievalTime + "ms");
        
        System.out.printf("Concurrent cache performance:%n");
        System.out.printf("  Cache operations: %d, avg time: %.2f ms%n", cacheOperations.get(), avgCacheTime);
        System.out.printf("  Retrieval operations: %d, avg time: %.2f ms%n", retrievalOperations.get(), avgRetrievalTime);
        System.out.printf("  Cache hits: %d, misses: %d, hit rate: %.2f%%%n", 
                         cacheHits.get(), cacheMisses.get(), hitRate);
        
        executor.shutdown();
    }
    
    @Test
    @DisplayName("Cache TTL Behavior Test")
    void testCacheTtlBehavior() throws InterruptedException {
        String clientId = testClientIds.get(0);
        QueryRequestDTO request = createTestQueryRequest(clientId);
        Pageable pageable = PageRequest.of(0, 50);
        String cacheKey = queryCacheService.generateCacheKey(request, pageable);
        
        // Cache data with short TTL
        List<QueryResultDTO> testData = createTestQueryResults(clientId, 50);
        Page<QueryResultDTO> testPage = new PageImpl<>(testData, pageable, testData.size());
        
        long shortTtl = 2; // 2 seconds
        queryCacheService.cacheHistoricalMetrics(cacheKey, testPage, shortTtl);
        
        // Verify data is cached
        Optional<Page<QueryResultDTO>> cached = queryCacheService.getCachedHistoricalMetrics(cacheKey);
        assertTrue(cached.isPresent(), "Data should be cached immediately");
        
        // Check TTL
        long ttl = queryCacheService.getCacheTtl(cacheKey);
        assertTrue(ttl > 0 && ttl <= shortTtl, "TTL should be positive and not exceed set value, was: " + ttl);
        
        // Wait for expiration
        Thread.sleep((shortTtl + 1) * 1000);
        
        // Verify data is expired
        Optional<Page<QueryResultDTO>> expiredCached = queryCacheService.getCachedHistoricalMetrics(cacheKey);
        assertFalse(expiredCached.isPresent(), "Data should be expired after TTL");
        
        System.out.printf("Cache TTL test - Initial TTL: %d seconds, Expired after: %d seconds%n", 
                         ttl, shortTtl + 1);
    }
    
    @Test
    @DisplayName("Cache Invalidation Performance Test")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testCacheInvalidationPerformance() {
        String clientId = testClientIds.get(0);
        
        // Cache multiple types of data for the client
        cacheMultipleDataTypes(clientId);
        
        // Test invalidation performance
        long invalidationStart = System.currentTimeMillis();
        queryCacheService.invalidateClientCache(clientId);
        long invalidationTime = System.currentTimeMillis() - invalidationStart;
        
        assertTrue(invalidationTime < 500, "Cache invalidation should be under 500ms, was: " + invalidationTime + "ms");
        
        // Verify all data is invalidated
        QueryRequestDTO request = createTestQueryRequest(clientId);
        Pageable pageable = PageRequest.of(0, 50);
        String cacheKey = queryCacheService.generateCacheKey(request, pageable);
        
        Optional<Page<QueryResultDTO>> cached = queryCacheService.getCachedHistoricalMetrics(cacheKey);
        assertFalse(cached.isPresent(), "Historical metrics should be invalidated");
        
        Optional<QueryResultDTO> realtimeCached = queryCacheService.getCachedRealTimeMetrics(clientId);
        assertFalse(realtimeCached.isPresent(), "Real-time metrics should be invalidated");
        
        System.out.printf("Cache invalidation performance: %d ms%n", invalidationTime);
    }
    
    @Test
    @DisplayName("Cache Memory Usage Test")
    void testCacheMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        
        // Get baseline memory
        System.gc();
        long baselineMemory = runtime.totalMemory() - runtime.freeMemory();
        
        // Cache large amount of data
        int numberOfClients = 10;
        int recordsPerClient = 1000;
        
        for (int i = 0; i < numberOfClients; i++) {
            String clientId = "memory-test-" + i;
            QueryRequestDTO request = createTestQueryRequest(clientId);
            Pageable pageable = PageRequest.of(0, recordsPerClient);
            String cacheKey = queryCacheService.generateCacheKey(request, pageable);
            
            List<QueryResultDTO> testData = createTestQueryResults(clientId, recordsPerClient);
            Page<QueryResultDTO> testPage = new PageImpl<>(testData, pageable, testData.size());
            
            queryCacheService.cacheHistoricalMetrics(cacheKey, testPage, 300);
        }
        
        // Check memory usage after caching
        long afterCachingMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryIncrease = afterCachingMemory - baselineMemory;
        
        // Memory increase should be reasonable (less than 200MB for test data)
        assertTrue(memoryIncrease < 200 * 1024 * 1024, 
                  "Memory increase should be reasonable, was: " + (memoryIncrease / 1024 / 1024) + "MB");
        
        System.out.printf("Cache memory usage - Baseline: %d MB, After caching: %d MB, Increase: %d MB%n",
                         baselineMemory / 1024 / 1024,
                         afterCachingMemory / 1024 / 1024,
                         memoryIncrease / 1024 / 1024);
    }
    
    @Test
    @DisplayName("Cache Statistics Accuracy Test")
    void testCacheStatisticsAccuracy() {
        // Get initial statistics
        Map<String, Object> initialStats = queryCacheService.getCacheStatistics();
        long initialHits = (Long) initialStats.get("cache_hits");
        long initialMisses = (Long) initialStats.get("cache_misses");
        
        String clientId = testClientIds.get(0);
        QueryRequestDTO request = createTestQueryRequest(clientId);
        Pageable pageable = PageRequest.of(0, 50);
        String cacheKey = queryCacheService.generateCacheKey(request, pageable);
        
        // Generate cache miss
        Optional<Page<QueryResultDTO>> missed = queryCacheService.getCachedHistoricalMetrics(cacheKey);
        assertFalse(missed.isPresent());
        
        // Cache data
        List<QueryResultDTO> testData = createTestQueryResults(clientId, 50);
        Page<QueryResultDTO> testPage = new PageImpl<>(testData, pageable, testData.size());
        queryCacheService.cacheHistoricalMetrics(cacheKey, testPage, 300);
        
        // Generate cache hit
        Optional<Page<QueryResultDTO>> hit = queryCacheService.getCachedHistoricalMetrics(cacheKey);
        assertTrue(hit.isPresent());
        
        // Check updated statistics
        Map<String, Object> finalStats = queryCacheService.getCacheStatistics();
        long finalHits = (Long) finalStats.get("cache_hits");
        long finalMisses = (Long) finalStats.get("cache_misses");
        
        assertEquals(initialHits + 1, finalHits, "Cache hits should be incremented");
        assertEquals(initialMisses + 1, finalMisses, "Cache misses should be incremented");
        
        double hitRate = (Double) finalStats.get("hit_rate");
        assertTrue(hitRate >= 0 && hitRate <= 1, "Hit rate should be between 0 and 1");
        
        System.out.printf("Cache statistics - Hits: %d->%d, Misses: %d->%d, Hit rate: %.2f%n",
                         initialHits, finalHits, initialMisses, finalMisses, hitRate);
    }
    
    @Test
    @DisplayName("Cache Warming Performance Test")
    void testCacheWarmingPerformance() {
        // Clear all caches
        for (String clientId : testClientIds) {
            queryCacheService.invalidateClientCache(clientId);
        }
        
        long warmingStart = System.currentTimeMillis();
        queryCacheService.warmCache(testClientIds, testStartTime, testEndTime);
        long warmingInitTime = System.currentTimeMillis() - warmingStart;
        
        // Cache warming initiation should be fast (it's async)
        assertTrue(warmingInitTime < 100, "Cache warming initiation should be under 100ms, was: " + warmingInitTime + "ms");
        
        // Wait for warming to complete
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Test that cache is warmed by checking for cached real-time data
        int warmedCaches = 0;
        for (String clientId : testClientIds) {
            Optional<QueryResultDTO> cached = queryCacheService.getCachedRealTimeMetrics(clientId);
            if (cached.isPresent()) {
                warmedCaches++;
            }
        }
        
        System.out.printf("Cache warming performance - Initiation: %d ms, Warmed caches: %d/%d%n",
                         warmingInitTime, warmedCaches, testClientIds.size());
    }
    
    @Test
    @DisplayName("Cache Key Generation Performance Test")
    void testCacheKeyGenerationPerformance() {
        QueryRequestDTO request = createTestQueryRequest(testClientIds.get(0));
        Pageable pageable = PageRequest.of(0, 100);
        
        int numberOfGenerations = 10000;
        
        long generationStart = System.currentTimeMillis();
        Set<String> generatedKeys = new HashSet<>();
        
        for (int i = 0; i < numberOfGenerations; i++) {
            String key = queryCacheService.generateCacheKey(request, pageable);
            generatedKeys.add(key);
        }
        
        long generationTime = System.currentTimeMillis() - generationStart;
        double avgGenerationTime = (double) generationTime / numberOfGenerations;
        
        assertTrue(avgGenerationTime < 1, "Average key generation should be under 1ms, was: " + avgGenerationTime + "ms");
        assertEquals(1, generatedKeys.size(), "Same request should generate same key");
        
        System.out.printf("Cache key generation - Total: %d ms, Average: %.3f ms, Unique keys: %d%n",
                         generationTime, avgGenerationTime, generatedKeys.size());
    }
    
    @Test
    @DisplayName("Cache Cleanup Performance Test")
    void testCacheCleanupPerformance() {
        // Cache data with various TTLs
        for (int i = 0; i < 10; i++) {
            String clientId = "cleanup-test-" + i;
            QueryRequestDTO request = createTestQueryRequest(clientId);
            Pageable pageable = PageRequest.of(0, 50);
            String cacheKey = queryCacheService.generateCacheKey(request, pageable);
            
            List<QueryResultDTO> testData = createTestQueryResults(clientId, 50);
            Page<QueryResultDTO> testPage = new PageImpl<>(testData, pageable, testData.size());
            
            // Cache with different TTLs
            queryCacheService.cacheHistoricalMetrics(cacheKey, testPage, i + 1);
        }
        
        long cleanupStart = System.currentTimeMillis();
        queryCacheService.cleanExpiredEntries();
        long cleanupTime = System.currentTimeMillis() - cleanupStart;
        
        assertTrue(cleanupTime < 1000, "Cache cleanup should be under 1 second, was: " + cleanupTime + "ms");
        
        System.out.printf("Cache cleanup performance: %d ms%n", cleanupTime);
    }
    
    // Helper methods
    
    private QueryRequestDTO createTestQueryRequest(String clientId) {
        QueryRequestDTO request = new QueryRequestDTO();
        request.setClientId(clientId);
        request.setStartTime(testStartTime);
        request.setEndTime(testEndTime);
        request.setMetricNames(Arrays.asList("cpu_usage", "memory_usage"));
        return request;
    }
    
    private List<QueryResultDTO> createTestQueryResults(String clientId, int count) {
        List<QueryResultDTO> results = new ArrayList<>();
        
        for (int i = 0; i < count; i++) {
            QueryResultDTO result = new QueryResultDTO();
            result.setClientId(clientId);
            result.setTimestamp(testStartTime.plus(i, ChronoUnit.MINUTES));
            
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("cpu_usage", 50.0 + (Math.random() * 40));
            metrics.put("memory_usage", 60.0 + (Math.random() * 30));
            result.setMetrics(metrics);
            
            results.add(result);
        }
        
        return results;
    }
    
    private void cacheMultipleDataTypes(String clientId) {
        // Cache historical metrics
        QueryRequestDTO request = createTestQueryRequest(clientId);
        Pageable pageable = PageRequest.of(0, 50);
        String cacheKey = queryCacheService.generateCacheKey(request, pageable);
        
        List<QueryResultDTO> testData = createTestQueryResults(clientId, 50);
        Page<QueryResultDTO> testPage = new PageImpl<>(testData, pageable, testData.size());
        queryCacheService.cacheHistoricalMetrics(cacheKey, testPage, 300);
        
        // Cache real-time metrics
        QueryResultDTO realtimeResult = new QueryResultDTO();
        realtimeResult.setClientId(clientId);
        realtimeResult.setTimestamp(Instant.now());
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("cpu_usage", 45.0);
        metrics.put("memory_usage", 65.0);
        realtimeResult.setMetrics(metrics);
        queryCacheService.cacheRealTimeMetrics(clientId, realtimeResult);
        
        // Cache client metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("hostname", "test-host");
        metadata.put("region", "us-east-1");
        queryCacheService.cacheClientMetadata(clientId, metadata);
    }
}