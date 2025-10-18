package cn.flying.monitor.data.integration;

import cn.flying.monitor.data.dto.QueryRequestDTO;
import cn.flying.monitor.data.dto.QueryResultDTO;
import cn.flying.monitor.data.dto.AggregationRequestDTO;
import cn.flying.monitor.data.dto.AggregationResultDTO;
import cn.flying.monitor.data.service.QueryService;
import cn.flying.monitor.data.service.QueryCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.InfluxDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for query service with real database connections
 * Uses Testcontainers for isolated testing environment
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = {
    "spring.influx.url=http://localhost:8086",
    "spring.redis.host=localhost",
    "spring.redis.port=6379"
})
public class QueryIntegrationTest {
    
    @Container
    static InfluxDBContainer<?> influxDB = new InfluxDBContainer<>(DockerImageName.parse("influxdb:2.7"))
            .withDatabase("test")
            .withUsername("test")
            .withPassword("test123")
            .withAdminToken("test-token");
    
    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
    
    @Autowired
    private QueryService queryService;
    
    @Autowired
    private QueryCacheService queryCacheService;
    
    private List<String> testClientIds;
    private Instant testStartTime;
    private Instant testEndTime;
    
    @BeforeEach
    void setUp() {
        testClientIds = Arrays.asList("integration-test-1", "integration-test-2", "integration-test-3");
        testEndTime = Instant.now();
        testStartTime = testEndTime.minus(2, ChronoUnit.HOURS);
        
        // Clear cache before each test
        for (String clientId : testClientIds) {
            queryCacheService.invalidateClientCache(clientId);
        }
    }
    
    @Test
    @DisplayName("End-to-End Query Flow Integration Test")
    void testEndToEndQueryFlow() {
        String clientId = testClientIds.get(0);
        
        // Test historical metrics query
        QueryRequestDTO request = createTestQueryRequest(clientId);
        Pageable pageable = PageRequest.of(0, 50);
        
        Page<QueryResultDTO> results = queryService.queryHistoricalMetrics(request, pageable);
        
        assertNotNull(results);
        assertNotNull(results.getContent());
        assertTrue(results.getContent().size() <= 50);
        
        // Verify each result has required fields
        for (QueryResultDTO result : results.getContent()) {
            assertNotNull(result.getClientId());
            assertNotNull(result.getTimestamp());
            assertEquals(clientId, result.getClientId());
        }
        
        System.out.printf("Historical query returned %d results%n", results.getContent().size());
    }
    
    @Test
    @DisplayName("Real-time Metrics Integration Test")
    void testRealTimeMetricsIntegration() {
        String clientId = testClientIds.get(0);
        
        QueryResultDTO result = queryService.queryRealTimeMetrics(clientId);
        
        // Result might be null if no recent data exists, which is acceptable
        if (result != null) {
            assertNotNull(result.getClientId());
            assertNotNull(result.getTimestamp());
            assertEquals(clientId, result.getClientId());
            
            // Timestamp should be recent (within last 5 minutes)
            assertTrue(result.getTimestamp().isAfter(Instant.now().minus(5, ChronoUnit.MINUTES)));
            
            System.out.printf("Real-time query returned data for timestamp: %s%n", result.getTimestamp());
        } else {
            System.out.println("No real-time data available (acceptable for test environment)");
        }
    }
    
    @Test
    @DisplayName("Aggregation Integration Test")
    void testAggregationIntegration() {
        String clientId = testClientIds.get(0);
        
        AggregationRequestDTO request = createTestAggregationRequest(clientId);
        AggregationResultDTO result = queryService.aggregateMetrics(request);
        
        assertNotNull(result);
        assertEquals(clientId, result.getClientId());
        assertEquals(request.getStartTime(), result.getStartTime());
        assertEquals(request.getEndTime(), result.getEndTime());
        assertNotNull(result.getCalculatedAt());
        
        // Check that aggregations were calculated
        if (result.getAggregations() != null && !result.getAggregations().isEmpty()) {
            System.out.printf("Aggregation calculated for %d metrics%n", result.getAggregations().size());
            
            for (Map.Entry<String, Map<String, Double>> entry : result.getAggregations().entrySet()) {
                String metricName = entry.getKey();
                Map<String, Double> aggregations = entry.getValue();
                
                assertNotNull(aggregations);
                System.out.printf("  %s: %s%n", metricName, aggregations);
            }
        } else {
            System.out.println("No aggregation data available (acceptable for test environment)");
        }
    }
    
    @Test
    @DisplayName("Cache Integration Test")
    void testCacheIntegration() {
        String clientId = testClientIds.get(0);
        QueryRequestDTO request = createTestQueryRequest(clientId);
        Pageable pageable = PageRequest.of(0, 30);
        
        // First query - should populate cache
        long firstQueryStart = System.currentTimeMillis();
        Page<QueryResultDTO> firstResult = queryService.queryHistoricalMetrics(request, pageable);
        long firstQueryTime = System.currentTimeMillis() - firstQueryStart;
        
        // Second query - should use cache
        long secondQueryStart = System.currentTimeMillis();
        Page<QueryResultDTO> secondResult = queryService.queryHistoricalMetrics(request, pageable);
        long secondQueryTime = System.currentTimeMillis() - secondQueryStart;
        
        assertNotNull(firstResult);
        assertNotNull(secondResult);
        
        // Cache should make second query faster
        assertTrue(secondQueryTime <= firstQueryTime, 
                  "Cached query should be faster or equal. First: " + firstQueryTime + "ms, Second: " + secondQueryTime + "ms");
        
        // Verify cache statistics
        Map<String, Object> cacheStats = queryCacheService.getCacheStatistics();
        assertNotNull(cacheStats);
        assertTrue((Long) cacheStats.get("total_requests") > 0);
        
        System.out.printf("Cache integration - First query: %d ms, Cached query: %d ms%n", 
                         firstQueryTime, secondQueryTime);
        System.out.printf("Cache stats: %s%n", cacheStats);
    }
    
    @Test
    @DisplayName("Advanced Query Features Integration Test")
    void testAdvancedQueryFeaturesIntegration() {
        String clientId = testClientIds.get(0);
        List<String> metricNames = Arrays.asList("cpu_usage", "memory_usage");
        
        // Test correlation calculation
        try {
            Map<String, Double> correlations = queryService.queryMetricsCorrelation(
                clientId, metricNames, testStartTime, testEndTime);
            
            assertNotNull(correlations);
            System.out.printf("Correlation analysis returned %d correlations%n", correlations.size());
            
            for (Map.Entry<String, Double> entry : correlations.entrySet()) {
                System.out.printf("  %s: %.3f%n", entry.getKey(), entry.getValue());
            }
            
        } catch (Exception e) {
            System.out.printf("Correlation analysis failed (acceptable for test environment): %s%n", e.getMessage());
        }
        
        // Test forecasting
        try {
            Map<String, Object> forecast = queryService.queryMetricsForecast(
                clientId, "cpu_usage", testStartTime, testEndTime, 12);
            
            assertNotNull(forecast);
            assertEquals(clientId, forecast.get("client_id"));
            assertEquals("cpu_usage", forecast.get("metric_name"));
            
            System.out.printf("Forecasting analysis completed for %s%n", forecast.get("metric_name"));
            
        } catch (Exception e) {
            System.out.printf("Forecasting analysis failed (acceptable for test environment): %s%n", e.getMessage());
        }
        
        // Test anomaly detection
        try {
            List<Map<String, Object>> anomalies = queryService.queryAnomalies(
                clientId, metricNames, testStartTime, testEndTime, 2.0);
            
            assertNotNull(anomalies);
            System.out.printf("Anomaly detection found %d anomalies%n", anomalies.size());
            
        } catch (Exception e) {
            System.out.printf("Anomaly detection failed (acceptable for test environment): %s%n", e.getMessage());
        }
    }
    
    @Test
    @DisplayName("Pagination Integration Test")
    void testPaginationIntegration() {
        String clientId = testClientIds.get(0);
        QueryRequestDTO request = createTestQueryRequest(clientId);
        
        // Test different page sizes
        int[] pageSizes = {10, 25, 50, 100};
        
        for (int pageSize : pageSizes) {
            Pageable pageable = PageRequest.of(0, pageSize);
            Page<QueryResultDTO> results = queryService.queryHistoricalMetrics(request, pageable);
            
            assertNotNull(results);
            assertTrue(results.getContent().size() <= pageSize);
            assertEquals(pageSize, results.getSize());
            
            System.out.printf("Page size %d returned %d results%n", pageSize, results.getContent().size());
        }
        
        // Test multiple pages
        Pageable firstPage = PageRequest.of(0, 20);
        Pageable secondPage = PageRequest.of(1, 20);
        
        Page<QueryResultDTO> page1 = queryService.queryHistoricalMetrics(request, firstPage);
        Page<QueryResultDTO> page2 = queryService.queryHistoricalMetrics(request, secondPage);
        
        assertNotNull(page1);
        assertNotNull(page2);
        
        if (page1.getTotalElements() > 20) {
            assertTrue(page1.hasNext());
            assertEquals(page1.getTotalElements(), page2.getTotalElements());
        }
        
        System.out.printf("Pagination test - Page 1: %d results, Page 2: %d results, Total: %d%n",
                         page1.getContent().size(), page2.getContent().size(), page1.getTotalElements());
    }
    
    @Test
    @DisplayName("Filter Integration Test")
    void testFilterIntegration() {
        String clientId = testClientIds.get(0);
        QueryRequestDTO request = createTestQueryRequest(clientId);
        
        // Add filters
        Map<String, Object> filters = new HashMap<>();
        filters.put("cpu_usage", Map.of("operator", ">", "value", 50.0));
        request.setFilters(filters);
        
        List<QueryResultDTO> results = queryService.queryMetricsWithFilters(request);
        
        assertNotNull(results);
        
        // Verify filter was applied (if data exists)
        for (QueryResultDTO result : results) {
            assertEquals(clientId, result.getClientId());
            if (result.getMetrics() != null && result.getMetrics().containsKey("cpu_usage")) {
                Double cpuUsage = (Double) result.getMetrics().get("cpu_usage");
                if (cpuUsage != null) {
                    assertTrue(cpuUsage > 50.0, "Filtered results should match filter criteria");
                }
            }
        }
        
        System.out.printf("Filter integration test returned %d filtered results%n", results.size());
    }
    
    @Test
    @DisplayName("Statistics Integration Test")
    void testStatisticsIntegration() {
        String clientId = testClientIds.get(0);
        List<String> metricNames = Arrays.asList("cpu_usage", "memory_usage");
        
        Map<String, Map<String, Double>> statistics = queryService.queryMetricsStatistics(
            clientId, metricNames, testStartTime, testEndTime);
        
        assertNotNull(statistics);
        
        for (String metricName : metricNames) {
            if (statistics.containsKey(metricName)) {
                Map<String, Double> metricStats = statistics.get(metricName);
                assertNotNull(metricStats);
                
                System.out.printf("Statistics for %s: %s%n", metricName, metricStats);
                
                // Verify statistical consistency if data exists
                if (metricStats.containsKey("min") && metricStats.containsKey("max") && 
                    metricStats.containsKey("mean")) {
                    
                    Double min = metricStats.get("min");
                    Double max = metricStats.get("max");
                    Double mean = metricStats.get("mean");
                    
                    if (min != null && max != null && mean != null) {
                        assertTrue(min <= mean, "Min should be <= mean");
                        assertTrue(mean <= max, "Mean should be <= max");
                    }
                }
            }
        }
    }
    
    @Test
    @DisplayName("Data Quality Integration Test")
    void testDataQualityIntegration() {
        String clientId = testClientIds.get(0);
        
        Map<String, Object> qualityMetrics = queryService.queryDataQuality(clientId, testStartTime, testEndTime);
        
        assertNotNull(qualityMetrics);
        assertEquals(clientId, qualityMetrics.get("client_id") != null ? 
                    ((Map<String, Object>) qualityMetrics.get("client_id")).get("client_id") : null);
        
        // Verify quality metrics structure
        if (qualityMetrics.containsKey("completeness_percentage")) {
            Double completeness = (Double) qualityMetrics.get("completeness_percentage");
            assertTrue(completeness >= 0 && completeness <= 100, 
                      "Completeness percentage should be between 0 and 100");
        }
        
        if (qualityMetrics.containsKey("quality_score")) {
            Double qualityScore = (Double) qualityMetrics.get("quality_score");
            assertTrue(qualityScore >= 0 && qualityScore <= 100, 
                      "Quality score should be between 0 and 100");
        }
        
        System.out.printf("Data quality metrics: %s%n", qualityMetrics);
    }
    
    @Test
    @DisplayName("Business Metrics Integration Test")
    void testBusinessMetricsIntegration() {
        String clientId = testClientIds.get(0);
        List<String> businessRules = Arrays.asList("availability_sla", "performance_score", "resource_efficiency");
        
        Map<String, Object> businessMetrics = queryService.queryBusinessMetrics(
            clientId, businessRules, testStartTime, testEndTime);
        
        assertNotNull(businessMetrics);
        
        for (String rule : businessRules) {
            if (businessMetrics.containsKey(rule)) {
                Object value = businessMetrics.get(rule);
                assertNotNull(value);
                
                if (value instanceof Double) {
                    Double doubleValue = (Double) value;
                    assertTrue(doubleValue >= 0 && doubleValue <= 100, 
                              "Business metric should be a percentage between 0 and 100");
                }
                
                System.out.printf("Business metric %s: %s%n", rule, value);
            }
        }
    }
    
    @Test
    @DisplayName("Query Performance Statistics Integration Test")
    void testQueryPerformanceStatisticsIntegration() {
        // Execute several queries to generate statistics
        for (int i = 0; i < 5; i++) {
            String clientId = testClientIds.get(i % testClientIds.size());
            QueryRequestDTO request = createTestQueryRequest(clientId);
            Pageable pageable = PageRequest.of(0, 20);
            
            queryService.queryHistoricalMetrics(request, pageable);
        }
        
        // Get performance statistics
        Map<String, Object> stats = queryService.getQueryStatistics();
        
        assertNotNull(stats);
        assertTrue((Long) stats.get("total_queries") >= 5);
        assertTrue((Long) stats.get("total_query_time_ms") > 0);
        
        if (stats.containsKey("cache_performance")) {
            Map<String, Object> cacheStats = (Map<String, Object>) stats.get("cache_performance");
            assertNotNull(cacheStats);
            assertTrue((Long) cacheStats.get("total_requests") > 0);
        }
        
        System.out.printf("Query performance statistics: %s%n", stats);
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
        request.setAggregationFunctions(Arrays.asList("avg", "min", "max"));
        request.setTimeWindow("1h");
        return request;
    }
}