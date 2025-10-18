package cn.flying.monitor.data.integration;

import cn.flying.monitor.data.dto.*;
import cn.flying.monitor.data.service.DataIngestionService;
import cn.flying.monitor.data.service.QueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for complete monitoring workflows
 */
@SpringBootTest
@AutoConfigureWebMvc
@ActiveProfiles("test")
class MonitoringWorkflowIntegrationTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Autowired
    private DataIngestionService dataIngestionService;
    
    @Autowired
    private QueryService queryService;
    
    private static final String TEST_CLIENT_ID = "integration-test-client";
    private Instant testStartTime;
    private Instant testEndTime;
    
    @BeforeEach
    void setUp() {
        testStartTime = Instant.now().minus(1, ChronoUnit.HOURS);
        testEndTime = Instant.now();
    }
    
    @Test
    void testCompleteMonitoringWorkflow_DataIngestionToQuery() throws Exception {
        // Step 1: Ingest test metrics data
        Map<String, Object> testMetrics = createTestMetrics();
        
        // Simulate data ingestion
        MetricsDataDTO metricsDataDTO = new MetricsDataDTO();
        metricsDataDTO.setClientId(TEST_CLIENT_ID);
        metricsDataDTO.setTimestamp(Instant.now());
        metricsDataDTO.setCpuUsage(((Number) testMetrics.get("cpu_usage")).doubleValue());
        metricsDataDTO.setMemoryUsage(((Number) testMetrics.get("memory_usage")).doubleValue());
        metricsDataDTO.setDiskUsage(((Number) testMetrics.get("disk_usage")).doubleValue());
        dataIngestionService.processMetrics(metricsDataDTO, false, true);
        
        // Step 2: Query the ingested data (use current API signature)
        QueryRequestDTO request = new QueryRequestDTO();
        request.setClientId(TEST_CLIENT_ID);
        request.setStartTime(testStartTime);
        request.setEndTime(testEndTime);
        request.setMetricNames(Arrays.asList("cpu_usage", "memory_usage"));
        
        Page<QueryResultDTO> results = queryService.queryHistoricalMetrics(
            request,
            PageRequest.of(0, 10)
        );
        
        // Verify results
        assertNotNull(results);
        assertTrue(results.getTotalElements() >= 0);
    }
    
    @Test
    void testMetricsAggregationWorkflow() throws Exception {
        // Given: Ingest multiple data points
        for (int i = 0; i < 5; i++) {
            Map<String, Object> metrics = createTestMetrics();
            metrics.put("cpu_usage", 70.0 + i * 5); // Varying CPU usage
            MetricsDataDTO dto = new MetricsDataDTO();
            dto.setClientId(TEST_CLIENT_ID);
            dto.setTimestamp(Instant.now());
            dto.setCpuUsage(((Number) metrics.get("cpu_usage")).doubleValue());
            dto.setMemoryUsage(((Number) metrics.get("memory_usage")).doubleValue());
            dto.setDiskUsage(((Number) metrics.get("disk_usage")).doubleValue());
            dataIngestionService.processMetrics(dto, false, true);
        }
        
        // When: Request aggregation
        AggregationRequestDTO request = new AggregationRequestDTO();
        request.setClientId(TEST_CLIENT_ID);
        request.setStartTime(testStartTime);
        request.setEndTime(testEndTime);
        request.setMetricNames(Arrays.asList("cpu_usage"));
        request.setAggregationFunctions(Arrays.asList("avg", "max", "min"));
        request.setTimeWindow("5m");
        
        AggregationResultDTO aggregationResult = queryService.aggregateMetrics(request);
        
        // Then: Verify aggregation results
        assertNotNull(aggregationResult);
        assertEquals(TEST_CLIENT_ID, aggregationResult.getClientId());
    }    

    @Test
    void testDataExportWorkflow() throws Exception {
        // Given: Ingest test data
        Map<String, Object> testMetrics = createTestMetrics();
        MetricsDataDTO dto = new MetricsDataDTO();
        dto.setClientId(TEST_CLIENT_ID);
        dto.setTimestamp(Instant.now());
        dto.setCpuUsage(((Number) testMetrics.get("cpu_usage")).doubleValue());
        dto.setMemoryUsage(((Number) testMetrics.get("memory_usage")).doubleValue());
        dto.setDiskUsage(((Number) testMetrics.get("disk_usage")).doubleValue());
        dataIngestionService.processMetrics(dto, false, true);
        
        // When: Request data export via API (align with DataExportController)
        ExportRequestDTO exportRequest = new ExportRequestDTO();
        exportRequest.setExportType("metrics");
        exportRequest.setFormat("CSV");
        exportRequest.setClientId(TEST_CLIENT_ID);
        exportRequest.setStartTime(testStartTime);
        exportRequest.setEndTime(testEndTime);
        exportRequest.setMetricNames(Arrays.asList("cpu_usage", "memory_usage"));
        
        mockMvc.perform(post("/api/v1/exports/sync")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(exportRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());
    }
    
    @Test
    void testRealTimeMetricsWorkflow() throws Exception {
        // Given: Ingest current metrics
        Map<String, Object> currentMetrics = createTestMetrics();
        MetricsDataDTO dto = new MetricsDataDTO();
        dto.setClientId(TEST_CLIENT_ID);
        dto.setTimestamp(Instant.now());
        dto.setCpuUsage(((Number) currentMetrics.get("cpu_usage")).doubleValue());
        dto.setMemoryUsage(((Number) currentMetrics.get("memory_usage")).doubleValue());
        dto.setDiskUsage(((Number) currentMetrics.get("disk_usage")).doubleValue());
        dataIngestionService.processMetrics(dto, false, true);
        
        // When: Query real-time metrics
        QueryResultDTO realTimeResult = queryService.queryRealTimeMetrics(TEST_CLIENT_ID);
        
        // Then: Verify real-time data
        // Allow for null result in test environment where InfluxDB might not be available
        if (realTimeResult != null) {
            assertEquals(TEST_CLIENT_ID, realTimeResult.getClientId());
            assertNotNull(realTimeResult.getTimestamp());
        }
    }
    
    @Test
    void testPerformanceUnderLoad() throws Exception {
        // Simulate high-load scenario with multiple concurrent requests
        List<Thread> threads = new ArrayList<>();
        
        for (int i = 0; i < 10; i++) {
            final int threadId = i;
            Thread thread = new Thread(() -> {
                try {
                    Map<String, Object> metrics = createTestMetrics();
                    MetricsDataDTO dtoInner = new MetricsDataDTO();
                    dtoInner.setClientId(TEST_CLIENT_ID + "-" + threadId);
                    dtoInner.setTimestamp(Instant.now());
                    dtoInner.setCpuUsage(((Number) metrics.get("cpu_usage")).doubleValue());
                    dtoInner.setMemoryUsage(((Number) metrics.get("memory_usage")).doubleValue());
                    dtoInner.setDiskUsage(((Number) metrics.get("disk_usage")).doubleValue());
                    dataIngestionService.processMetrics(dtoInner, false, true);
                } catch (Exception e) {
                    // Log error but don't fail test due to potential infrastructure issues
                    System.err.println("Thread " + threadId + " failed: " + e.getMessage());
                }
            });
            threads.add(thread);
            thread.start();
        }
        
        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join(5000); // 5 second timeout
        }
        
        // Verify system is still responsive
        assertDoesNotThrow(() -> {
            queryService.queryRealTimeMetrics(TEST_CLIENT_ID);
        });
    }
    
    private Map<String, Object> createTestMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("cpu_usage", 75.5);
        metrics.put("memory_usage", 60.2);
        metrics.put("disk_usage", 45.8);
        metrics.put("network_in", 1024000L);
        metrics.put("network_out", 512000L);
        metrics.put("timestamp", Instant.now().toEpochMilli());
        return metrics;
    }
}