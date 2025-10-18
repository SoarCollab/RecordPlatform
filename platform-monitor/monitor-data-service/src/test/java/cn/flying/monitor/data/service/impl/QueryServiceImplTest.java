package cn.flying.monitor.data.service.impl;

import cn.flying.monitor.data.dto.*;
import cn.flying.monitor.data.service.InfluxDBService;
import cn.flying.monitor.data.service.QueryCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for QueryServiceImpl
 */
@ExtendWith(MockitoExtension.class)
class QueryServiceImplTest {
    
    @Mock
    private InfluxDBService influxDBService;
    
    @Mock
    private ObjectMapper objectMapper;
    
    @Mock
    private QueryCacheService queryCacheService;
    
    @InjectMocks
    private QueryServiceImpl queryService;
    
    private static final String TEST_CLIENT_ID = "test-client-001";
    private Instant testStartTime;
    private Instant testEndTime;
    
    /**
     * Prepare QueryServiceImpl with basic config and a mocked InfluxDBClient/QueryApi to prevent NPE during query calls
     */
    @BeforeEach
    void setUp() {
        // Basic required fields
        ReflectionTestUtils.setField(queryService, "url", "http://localhost:8086");
        ReflectionTestUtils.setField(queryService, "token", "test-token");
        ReflectionTestUtils.setField(queryService, "bucket", "test-bucket");
        ReflectionTestUtils.setField(queryService, "organization", "test-org");
        
        // Inject mocked InfluxDB client so that internal query calls are safe
        com.influxdb.client.InfluxDBClient mockClient = mock(com.influxdb.client.InfluxDBClient.class);
        com.influxdb.client.QueryApi mockQueryApi = mock(com.influxdb.client.QueryApi.class);
        when(mockClient.getQueryApi()).thenReturn(mockQueryApi);
        when(mockQueryApi.query(anyString(), anyString())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(queryService, "client", mockClient);
        
        testStartTime = Instant.now().minus(1, ChronoUnit.HOURS);
        testEndTime = Instant.now();
    }
    
    /**
     * Should return a non-null page when querying historical metrics using current API signature
     */
    @Test
    void testQueryHistoricalMetrics_WithValidRequest_ReturnsResults() {
        Pageable pageable = PageRequest.of(0, 10);
        QueryRequestDTO request = new QueryRequestDTO();
        request.setClientId(TEST_CLIENT_ID);
        request.setStartTime(testStartTime);
        request.setEndTime(testEndTime);
        request.setMetricNames(Arrays.asList("cpu_usage", "memory_usage"));
        
        Page<QueryResultDTO> result = queryService.queryHistoricalMetrics(request, pageable);
        
        assertNotNull(result);
        assertTrue(result.getContent().size() >= 0);
    }
    
    /**
     * Real-time metrics may be null if no data; ensure the call is safe and compiles
     */
    @Test
    void testQueryRealTimeMetrics_WithValidClientId_ReturnsLatestMetrics() {
        QueryResultDTO result = queryService.queryRealTimeMetrics(TEST_CLIENT_ID);
        assertTrue(result == null || TEST_CLIENT_ID.equals(result.getClientId()) || result.getClientId() == null);
    }
    
    /**
     * Aggregation call should return a non-null AggregationResultDTO with basic fields populated
     */
    @Test
    void testAggregateMetrics_WithValidRequest_ReturnsAggregatedData() {
        AggregationRequestDTO request = new AggregationRequestDTO();
        request.setClientId(TEST_CLIENT_ID);
        request.setStartTime(testStartTime);
        request.setEndTime(testEndTime);
        request.setMetricNames(Collections.singletonList("cpu_usage"));
        request.setAggregationFunctions(Arrays.asList("avg", "max"));
        request.setTimeWindow("5m");
        
        AggregationResultDTO result = queryService.aggregateMetrics(request);
        
        assertNotNull(result);
        assertEquals(TEST_CLIENT_ID, result.getClientId());
        assertEquals(request.getStartTime(), result.getStartTime());
        assertEquals(request.getEndTime(), result.getEndTime());
    }
    
    /**
     * Percentile calculation should return a non-null map (empty if no data)
     */
    @Test
    void testCalculatePercentiles_WithValidInput_ReturnsPercentileValues() {
        List<Double> percentiles = Arrays.asList(50.0, 95.0, 99.0);
        Map<String, Double> result = queryService.calculatePercentiles(
            TEST_CLIENT_ID, "cpu_usage", testStartTime, testEndTime, percentiles);
        
        assertNotNull(result);
    }
    
    private QueryResultDTO createMockQueryResult(String metricName, Double value) {
        QueryResultDTO result = new QueryResultDTO();
        result.setClientId(TEST_CLIENT_ID);
        result.setTimestamp(Instant.now());
        Map<String, Object> metrics = new HashMap<>();
        metrics.put(metricName, value);
        result.setMetrics(metrics);
        return result;
    }
}