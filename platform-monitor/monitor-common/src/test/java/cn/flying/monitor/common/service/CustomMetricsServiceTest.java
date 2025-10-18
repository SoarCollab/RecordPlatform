package cn.flying.monitor.common.service;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 自定义指标服务测试
 * 测试各类业务指标的记录和统计功能
 */
@ExtendWith(MockitoExtension.class)
class CustomMetricsServiceTest {

    private MeterRegistry meterRegistry;
    private CustomMetricsService customMetricsService;

    @BeforeEach
    void setUp() {
        // 使用 SimpleMeterRegistry 代替 Mock，避免复杂的泛型配置
        meterRegistry = new SimpleMeterRegistry();
        customMetricsService = new CustomMetricsService(meterRegistry);
    }

    @Test
    void shouldRecordDataIngestionMetrics() {
        // Given
        String clientId = "client-001";
        int recordCount = 100;
        Duration processingTime = Duration.ofMillis(500);

        // When
        assertDoesNotThrow(() ->
            customMetricsService.recordDataIngestion(clientId, recordCount, processingTime)
        );

        // Then - 验证没有抛出异常
    }

    @Test
    void shouldRecordQueryPerformanceMetrics() {
        // Given
        String queryType = "range-query";
        Duration executionTime = Duration.ofMillis(200);
        boolean cacheHit = true;

        // When
        assertDoesNotThrow(() ->
            customMetricsService.recordQueryPerformance(queryType, executionTime, cacheHit)
        );

        // Then - 验证没有抛出异常
    }

    @Test
    void shouldRecordQueryPerformanceMetricsWithCacheMiss() {
        // Given
        String queryType = "complex-query";
        Duration executionTime = Duration.ofMillis(800);
        boolean cacheHit = false;

        // When
        assertDoesNotThrow(() ->
            customMetricsService.recordQueryPerformance(queryType, executionTime, cacheHit)
        );

        // Then - 验证没有抛出异常
    }

    @Test
    void shouldRecordAlertProcessingMetrics() {
        // Given
        String alertType = "threshold";
        String severity = "critical";
        Duration responseTime = Duration.ofMillis(100);

        // When
        assertDoesNotThrow(() ->
            customMetricsService.recordAlertProcessing(alertType, severity, responseTime)
        );

        // Then - 验证没有抛出异常
    }

    @Test
    void shouldRecordWebSocketConnectionMetrics() {
        // Given
        String connectionType = "monitor";
        boolean connected = true;

        // When
        assertDoesNotThrow(() ->
            customMetricsService.recordWebSocketConnection(connectionType, connected)
        );

        // Then - 验证没有抛出异常
    }

    @Test
    void shouldRecordWebSocketDisconnectionMetrics() {
        // Given
        String connectionType = "monitor";

        // When
        assertDoesNotThrow(() -> {
            customMetricsService.recordWebSocketConnection(connectionType, true);
            customMetricsService.recordWebSocketConnection(connectionType, false);
        });

        // Then - 验证没有抛出异常
    }

    @Test
    void shouldRecordAuthenticationMetrics() {
        // Given
        String authType = "jwt";
        boolean success = true;
        Duration processingTime = Duration.ofMillis(50);

        // When
        assertDoesNotThrow(() ->
            customMetricsService.recordAuthentication(authType, success, processingTime)
        );

        // Then - 验证没有抛出异常
    }

    @Test
    void shouldRecordAuthenticationFailureMetrics() {
        // Given
        String authType = "certificate";
        boolean success = false;
        Duration processingTime = Duration.ofMillis(30);

        // When
        assertDoesNotThrow(() ->
            customMetricsService.recordAuthentication(authType, success, processingTime)
        );

        // Then - 验证没有抛出异常
    }

    @Test
    void shouldCreateCustomCounter() {
        // Given
        String name = "test.counter";
        String description = "Test counter description";

        // When
        Counter counter = customMetricsService.createCounter(
            name,
            description,
            "tag1", "value1",
            "tag2", "value2"
        );

        // Then
        assertNotNull(counter);
    }

    @Test
    void shouldCreateCustomCounterWithoutTags() {
        // Given
        String name = "test.counter.notags";
        String description = "Counter without tags";

        // When
        Counter counter = customMetricsService.createCounter(name, description);

        // Then
        assertNotNull(counter);
    }

    @Test
    void shouldCreateCustomTimer() {
        // Given
        String name = "test.timer";
        String description = "Test timer description";

        // When
        Timer timer = customMetricsService.createTimer(
            name,
            description,
            "tag1", "value1"
        );

        // Then
        assertNotNull(timer);
    }

    @Test
    void shouldCreateCustomTimerWithoutTags() {
        // Given
        String name = "test.timer.notags";
        String description = "Timer without tags";

        // When
        Timer timer = customMetricsService.createTimer(name, description);

        // Then
        assertNotNull(timer);
    }

    @Test
    void shouldCreateCustomGaugeWithoutTags() {
        // Given
        String name = "test.gauge";
        String description = "Test gauge description";

        // When
        Gauge gauge = customMetricsService.createGauge(
            name,
            description,
            this,
            () -> 42.0
        );

        // Then
        assertNotNull(gauge);
    }

    @Test
    void shouldCreateCustomGaugeWithTags() {
        // Given
        String name = "test.gauge.tagged";
        String description = "Tagged gauge";

        // When
        Gauge gauge = customMetricsService.createGauge(
            name,
            description,
            this,
            () -> 100.0,
            "tag1", "value1"
        );

        // Then
        assertNotNull(gauge);
    }

    @Test
    void shouldRecordSlaMetrics() {
        // Given
        String serviceName = "api-service";
        double availability = 99.9;
        double responseTimeP95 = 150.0;
        double errorRate = 0.1;
        boolean slaCompliant = true;

        // When
        assertDoesNotThrow(() ->
            customMetricsService.recordSlaMetrics(
                serviceName, availability, responseTimeP95, errorRate, slaCompliant
            )
        );

        // Then - 验证没有抛出异常
    }

    @Test
    void shouldRecordSlaMetricsForNonCompliantService() {
        // Given
        String serviceName = "slow-service";
        double availability = 95.0;
        double responseTimeP95 = 2000.0;
        double errorRate = 5.0;
        boolean slaCompliant = false;

        // When
        assertDoesNotThrow(() ->
            customMetricsService.recordSlaMetrics(
                serviceName, availability, responseTimeP95, errorRate, slaCompliant
            )
        );

        // Then - 验证没有抛出异常
    }

    @Test
    void shouldRecordPerformanceScoreMetrics() {
        // Given
        String serviceName = "data-service";
        double performanceScore = 85.0;
        Map<String, Double> subScores = new HashMap<>();
        subScores.put("latency", 90.0);
        subScores.put("throughput", 80.0);

        // When
        assertDoesNotThrow(() ->
            customMetricsService.recordPerformanceScore(serviceName, performanceScore, subScores)
        );

        // Then - 验证没有抛出异常
    }

    @Test
    void shouldRecordPerformanceScoreWithNullSubScores() {
        // Given
        String serviceName = "simple-service";
        double performanceScore = 90.0;

        // When
        assertDoesNotThrow(() ->
            customMetricsService.recordPerformanceScore(serviceName, performanceScore, null)
        );

        // Then - 验证没有抛出异常
    }

    @Test
    void shouldRecordDataQualityMetrics() {
        // Given
        String dataSource = "sensor-data";
        double completeness = 98.5;
        double accuracy = 99.0;
        double freshness = 95.0;
        int duplicates = 5;
        int invalidRecords = 2;

        // When
        assertDoesNotThrow(() ->
            customMetricsService.recordDataQualityMetrics(
                dataSource, completeness, accuracy, freshness, duplicates, invalidRecords
            )
        );

        // Then - 验证没有抛出异常
    }

    @Test
    void shouldRecordPoorDataQualityMetrics() {
        // Given
        String dataSource = "legacy-data";
        double completeness = 70.0;
        double accuracy = 75.0;
        double freshness = 50.0;
        int duplicates = 100;
        int invalidRecords = 50;

        // When
        assertDoesNotThrow(() ->
            customMetricsService.recordDataQualityMetrics(
                dataSource, completeness, accuracy, freshness, duplicates, invalidRecords
            )
        );

        // Then - 验证没有抛出异常
    }

    @Test
    void shouldRecordCachePerformanceMetrics() {
        // Given
        String cacheName = "user-cache";
        long hitCount = 800;
        long missCount = 200;
        double hitRatio = 0.8;
        long evictionCount = 50;
        Duration avgLoadTime = Duration.ofMillis(10);
        long cacheSize = 1000;

        // When
        assertDoesNotThrow(() ->
            customMetricsService.recordCachePerformanceMetrics(
                cacheName, hitCount, missCount, hitRatio, evictionCount, avgLoadTime, cacheSize
            )
        );

        // Then - 验证没有抛出异常
    }

    @Test
    void shouldRecordPoorCachePerformance() {
        // Given
        String cacheName = "inefficient-cache";
        long hitCount = 200;
        long missCount = 800;
        double hitRatio = 0.2;
        long evictionCount = 500;
        Duration avgLoadTime = Duration.ofMillis(200);
        long cacheSize = 100;

        // When
        assertDoesNotThrow(() ->
            customMetricsService.recordCachePerformanceMetrics(
                cacheName, hitCount, missCount, hitRatio, evictionCount, avgLoadTime, cacheSize
            )
        );

        // Then - 验证没有抛出异常
    }

    @Test
    void shouldRecordQueryStatistics() {
        // Given
        String queryType = "aggregation";
        int totalQueries = 1000;
        int successfulQueries = 980;
        int failedQueries = 20;
        Duration avgExecutionTime = Duration.ofMillis(150);
        Duration maxExecutionTime = Duration.ofMillis(500);
        int slowQueries = 30;

        // When
        assertDoesNotThrow(() ->
            customMetricsService.recordQueryStatistics(
                queryType, totalQueries, successfulQueries, failedQueries,
                avgExecutionTime, maxExecutionTime, slowQueries
            )
        );

        // Then - 验证没有抛出异常
    }

    @Test
    void shouldRecordQueryStatisticsWithHighFailureRate() {
        // Given
        String queryType = "complex-join";
        int totalQueries = 100;
        int successfulQueries = 50;
        int failedQueries = 50;
        Duration avgExecutionTime = Duration.ofMillis(2000);
        Duration maxExecutionTime = Duration.ofMillis(5000);
        int slowQueries = 70;

        // When
        assertDoesNotThrow(() ->
            customMetricsService.recordQueryStatistics(
                queryType, totalQueries, successfulQueries, failedQueries,
                avgExecutionTime, maxExecutionTime, slowQueries
            )
        );

        // Then - 验证没有抛出异常
    }

    @Test
    void shouldHandleZeroTotalQueriesInQueryStatistics() {
        // Given
        String queryType = "no-query";
        int totalQueries = 0;
        int successfulQueries = 0;
        int failedQueries = 0;
        Duration avgExecutionTime = Duration.ZERO;
        Duration maxExecutionTime = Duration.ZERO;
        int slowQueries = 0;

        // When & Then - 应该正确处理零除情况
        assertDoesNotThrow(() ->
            customMetricsService.recordQueryStatistics(
                queryType, totalQueries, successfulQueries, failedQueries,
                avgExecutionTime, maxExecutionTime, slowQueries
            )
        );
    }

    @Test
    void shouldRecordDatabaseConnectionPoolMetrics() {
        // Given
        String poolName = "hikari-pool";
        int activeConnections = 8;
        int idleConnections = 2;
        int maxConnections = 10;

        // When
        assertDoesNotThrow(() ->
            customMetricsService.recordDatabaseConnectionPool(
                poolName, activeConnections, idleConnections, maxConnections
            )
        );

        // Then - 验证没有抛出异常
    }

    @Test
    void shouldHandleZeroMaxConnectionsInPoolMetrics() {
        // Given
        String poolName = "empty-pool";
        int activeConnections = 0;
        int idleConnections = 0;
        int maxConnections = 0;

        // When & Then - 应该正确处理除零情况
        assertDoesNotThrow(() ->
            customMetricsService.recordDatabaseConnectionPool(
                poolName, activeConnections, idleConnections, maxConnections
            )
        );
    }

    @Test
    void shouldHandleFullConnectionPool() {
        // Given
        String poolName = "full-pool";
        int activeConnections = 20;
        int idleConnections = 0;
        int maxConnections = 20;

        // When
        assertDoesNotThrow(() ->
            customMetricsService.recordDatabaseConnectionPool(
                poolName, activeConnections, idleConnections, maxConnections
            )
        );

        // Then - 验证没有抛出异常
    }

    @Test
    void shouldHandleOddNumberOfTagsInCreateCounter() {
        // Given
        String name = "odd.tags.counter";
        String description = "Counter with odd number of tags";

        // When
        Counter counter = customMetricsService.createCounter(
            name,
            description,
            "tag1", "value1",
            "tag2" // 缺少对应值
        );

        // Then - 应该忽略不完整的标签对
        assertNotNull(counter);
    }

    @Test
    void shouldHandleOddNumberOfTagsInCreateTimer() {
        // Given
        String name = "odd.tags.timer";
        String description = "Timer with odd number of tags";

        // When
        Timer timer = customMetricsService.createTimer(
            name,
            description,
            "tag1", "value1",
            "tag2" // 缺少对应值
        );

        // Then - 应该忽略不完整的标签对
        assertNotNull(timer);
    }

    @Test
    void shouldHandleMultipleWebSocketConnections() {
        // Given
        String connectionType = "terminal";

        // When - 模拟多个连接
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 10; i++) {
                customMetricsService.recordWebSocketConnection(connectionType, true);
            }
        });

        // Then - 验证没有抛出异常
    }

    @Test
    void shouldHandleRapidConnectDisconnect() {
        // Given
        String connectionType = "monitor";

        // When - 模拟快速连接断开
        assertDoesNotThrow(() -> {
            customMetricsService.recordWebSocketConnection(connectionType, true);
            customMetricsService.recordWebSocketConnection(connectionType, false);
            customMetricsService.recordWebSocketConnection(connectionType, true);
            customMetricsService.recordWebSocketConnection(connectionType, false);
        });

        // Then - 验证没有抛出异常
    }

    @Test
    void shouldHandleVeryLongResponseTime() {
        // Given
        String serviceName = "timeout-prone-service";
        double availability = 80.0;
        double responseTimeP95 = 30000.0; // 30 秒
        double errorRate = 20.0;
        boolean slaCompliant = false;

        // When
        assertDoesNotThrow(() ->
            customMetricsService.recordSlaMetrics(
                serviceName, availability, responseTimeP95, errorRate, slaCompliant
            )
        );

        // Then - 验证没有抛出异常
    }

    @Test
    void shouldHandleVeryHighHitRatio() {
        // Given
        String cacheName = "perfect-cache";
        long hitCount = 10000;
        long missCount = 1;
        double hitRatio = 0.9999;
        long evictionCount = 0;
        Duration avgLoadTime = Duration.ofMillis(1);
        long cacheSize = 10000;

        // When
        assertDoesNotThrow(() ->
            customMetricsService.recordCachePerformanceMetrics(
                cacheName, hitCount, missCount, hitRatio, evictionCount, avgLoadTime, cacheSize
            )
        );

        // Then - 验证没有抛出异常
    }
}
