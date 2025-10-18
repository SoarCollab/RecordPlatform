package cn.flying.monitor.data.integration;

import cn.flying.monitor.data.dto.BatchMetricsDTO;
import cn.flying.monitor.data.dto.MetricsDataDTO;
import cn.flying.monitor.data.service.DataIngestionService;
import cn.flying.monitor.data.service.InfluxDBService;
import cn.flying.monitor.data.validation.BatchValidationResult;
import cn.flying.monitor.data.validation.ValidationResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.InfluxDBContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for data ingestion with real infrastructure components
 * Uses Testcontainers for isolated testing environment
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public class DataIngestionIntegrationTest {
    
    @Container
    static InfluxDBContainer<?> influxDB = new InfluxDBContainer<>("influxdb:2.7")
            .withDatabase("test-monitor")
            .withUsername("test")
            .withPassword("test123");
    
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("test_monitor")
            .withUsername("test")
            .withPassword("test123");
    
    @Container
    static RabbitMQContainer rabbitMQ = new RabbitMQContainer("rabbitmq:3.12-management")
            .withUser("test", "test123");
    
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);
    
    private DataIngestionService dataIngestionService;
    private InfluxDBService influxDBService;
    
    @Test
    void testCompleteDataIngestionFlow() {
        // Test complete flow from metrics input to storage and streaming
        MetricsDataDTO metrics = createValidMetrics();
        
        Map<String, Object> result = dataIngestionService.processMetrics(metrics, true, false);
        
        assertNotNull(result);
        assertTrue((Boolean) result.get("processed"));
        assertEquals(metrics.getClientId(), result.get("client_id"));
        assertNotNull(result.get("processing_time_ms"));
        
        // Verify compression was applied
        if (result.containsKey("compression")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> compressionInfo = (Map<String, Object>) result.get("compression");
            assertTrue((Boolean) compressionInfo.get("compressed"));
            assertTrue((Double) compressionInfo.get("compression_ratio") < 1.0);
        }
    }
    
    @Test
    void testBatchProcessingIntegration() {
        // Test batch processing with validation and storage
        BatchMetricsDTO batch = createValidBatch(50);
        
        Map<String, Object> result = dataIngestionService.processBatchMetrics(batch, true, true);
        
        assertNotNull(result);
        assertTrue((Boolean) result.get("processed"));
        assertEquals(50, (Integer) result.get("total_metrics"));
        assertEquals(50, (Integer) result.get("processed_metrics"));
        assertEquals(0, (Integer) result.get("error_metrics"));
        assertEquals(100.0, (Double) result.get("success_rate"));
    }
    
    @Test
    void testValidationIntegration() {
        // Test validation with various data quality scenarios
        
        // Valid metrics
        MetricsDataDTO validMetrics = createValidMetrics();
        ValidationResult validResult = dataIngestionService.validateMetrics(validMetrics);
        assertTrue(validResult.isValid());
        assertTrue(validResult.getErrors().isEmpty());
        assertEquals(100.0, validResult.getDataQualityScore());
        
        // Invalid metrics
        MetricsDataDTO invalidMetrics = createInvalidMetrics();
        ValidationResult invalidResult = dataIngestionService.validateMetrics(invalidMetrics);
        assertFalse(invalidResult.isValid());
        assertFalse(invalidResult.getErrors().isEmpty());
        assertTrue(invalidResult.getDataQualityScore() < 100.0);
    }
    
    @Test
    void testInfluxDBIntegration() {
        // Test direct InfluxDB operations
        MetricsDataDTO metrics = createValidMetrics();
        
        // Write metrics
        assertDoesNotThrow(() -> influxDBService.writeMetrics(metrics));
        
        // Verify health
        assertTrue(influxDBService.isHealthy());
        
        // Check statistics
        Map<String, Object> stats = influxDBService.getStatistics();
        assertNotNull(stats);
        assertTrue((Long) stats.get("total_points_written") >= 0);
        assertTrue((Boolean) stats.get("healthy"));
    }
    
    @Test
    void testErrorHandlingIntegration() {
        // Test error handling with malformed data
        MetricsDataDTO malformedMetrics = new MetricsDataDTO();
        malformedMetrics.setClientId(""); // Invalid empty client ID
        malformedMetrics.setTimestamp(Instant.now().plusSeconds(3600)); // Future timestamp
        malformedMetrics.setCpuUsage(150.0); // Invalid percentage
        
        ValidationResult result = dataIngestionService.validateMetrics(malformedMetrics);
        assertFalse(result.isValid());
        assertFalse(result.getErrors().isEmpty());
        
        // Verify specific error messages
        assertTrue(result.getErrors().stream()
                .anyMatch(error -> error.contains("Client ID")));
        assertTrue(result.getErrors().stream()
                .anyMatch(error -> error.contains("future")));
    }
    
    @Test
    void testCompressionIntegration() {
        // Test compression with various data sizes
        MetricsDataDTO smallMetrics = createValidMetrics();
        Map<String, Object> compressionTest = dataIngestionService.testCompression(smallMetrics);
        
        assertNotNull(compressionTest);
        assertNotNull(compressionTest.get("compression_ratio"));
        assertNotNull(compressionTest.get("should_compress"));
        
        // Large metrics with custom data should compress better
        MetricsDataDTO largeMetrics = createLargeMetrics();
        Map<String, Object> largeCompressionTest = dataIngestionService.testCompression(largeMetrics);
        
        assertTrue((Boolean) largeCompressionTest.get("should_compress"));
        assertTrue((Double) largeCompressionTest.get("compression_ratio") < 0.8);
    }
    
    @Test
    void testConcurrentProcessing() throws InterruptedException {
        // Test concurrent processing with multiple threads
        int threadCount = 10;
        int metricsPerThread = 20;
        List<Thread> threads = new ArrayList<>();
        List<Exception> exceptions = new ArrayList<>();
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            Thread thread = new Thread(() -> {
                try {
                    for (int j = 0; j < metricsPerThread; j++) {
                        MetricsDataDTO metrics = createValidMetrics();
                        metrics.setClientId("concurrent-client-" + threadId + "-" + j);
                        
                        Map<String, Object> result = dataIngestionService.processMetrics(metrics, false, false);
                        assertTrue((Boolean) result.get("processed"));
                    }
                } catch (Exception e) {
                    synchronized (exceptions) {
                        exceptions.add(e);
                    }
                }
            });
            threads.add(thread);
            thread.start();
        }
        
        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join(30000); // 30 second timeout
        }
        
        // Verify no exceptions occurred
        assertTrue(exceptions.isEmpty(), 
                  "Concurrent processing failed with exceptions: " + exceptions);
    }
    
    private MetricsDataDTO createValidMetrics() {
        MetricsDataDTO metrics = new MetricsDataDTO();
        metrics.setClientId("test-client-" + System.currentTimeMillis());
        metrics.setTimestamp(Instant.now().minusSeconds(1));
        metrics.setCpuUsage(45.5);
        metrics.setMemoryUsage(67.8);
        metrics.setDiskUsage(23.4);
        metrics.setNetworkUpload(1024000.0);
        metrics.setNetworkDownload(2048000.0);
        metrics.setDiskRead(512000.0);
        metrics.setDiskWrite(256000.0);
        metrics.setLoadAverage(1.5);
        metrics.setProcessCount(150);
        metrics.setNetworkConnections(45);
        return metrics;
    }
    
    private MetricsDataDTO createInvalidMetrics() {
        MetricsDataDTO metrics = new MetricsDataDTO();
        metrics.setClientId(""); // Invalid
        metrics.setTimestamp(Instant.now().plusSeconds(3600)); // Future
        metrics.setCpuUsage(150.0); // Invalid percentage
        metrics.setMemoryUsage(-10.0); // Negative
        metrics.setDiskUsage(null); // Missing required field
        return metrics;
    }
    
    private MetricsDataDTO createLargeMetrics() {
        MetricsDataDTO metrics = createValidMetrics();
        
        // Add large custom metrics to test compression
        Map<String, Object> customMetrics = new java.util.HashMap<>();
        for (int i = 0; i < 100; i++) {
            customMetrics.put("custom_metric_" + i, "Large string value for testing compression efficiency " + i);
        }
        metrics.setCustomMetrics(customMetrics);
        
        return metrics;
    }
    
    private BatchMetricsDTO createValidBatch(int size) {
        BatchMetricsDTO batch = new BatchMetricsDTO();
        batch.setBatchId("test-batch-" + System.currentTimeMillis());
        batch.setBatchTimestamp(System.currentTimeMillis());
        batch.setCompressionEnabled(true);
        
        List<MetricsDataDTO> metricsList = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            MetricsDataDTO metrics = createValidMetrics();
            metrics.setClientId("batch-client-" + i);
            metricsList.add(metrics);
        }
        batch.setMetrics(metricsList);
        
        return batch;
    }
}