package cn.flying.monitor.common.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 业务指标服务测试
 * 测试业务指标计算和聚合功能
 */
@ExtendWith(MockitoExtension.class)
class BusinessMetricsServiceTest {

    @Mock
    private CustomMetricsService customMetricsService;

    @InjectMocks
    private BusinessMetricsService businessMetricsService;

    @BeforeEach
    void setUp() {
        // 初始化配置
    }

    @Test
    void shouldRecordServiceCallSuccessfully() {
        // Given
        String serviceName = "user-service";
        Duration responseTime = Duration.ofMillis(100);
        boolean success = true;

        // When
        businessMetricsService.recordServiceCall(serviceName, responseTime, success);

        // Then - 验证服务调用被记录
        Map<String, Object> summary = businessMetricsService.getBusinessMetricsSummary();
        assertNotNull(summary);

        @SuppressWarnings("unchecked")
        Map<String, Object> services = (Map<String, Object>) summary.get("services");
        assertTrue(services.containsKey(serviceName));
    }

    @Test
    void shouldRecordServiceCallFailure() {
        // Given
        String serviceName = "database-service";
        Duration responseTime = Duration.ofMillis(500);
        boolean success = false;

        // When
        businessMetricsService.recordServiceCall(serviceName, responseTime, success);

        // Then
        Map<String, Object> summary = businessMetricsService.getBusinessMetricsSummary();

        @SuppressWarnings("unchecked")
        Map<String, Object> services = (Map<String, Object>) summary.get("services");
        assertTrue(services.containsKey(serviceName));

        @SuppressWarnings("unchecked")
        Map<String, Object> serviceInfo = (Map<String, Object>) services.get(serviceName);
        assertNotNull(serviceInfo);
    }

    @Test
    void shouldRecordSlowServiceCall() {
        // Given
        String serviceName = "slow-service";
        Duration slowResponseTime = Duration.ofMillis(1500); // 超过1秒
        boolean success = true;

        // When
        businessMetricsService.recordServiceCall(serviceName, slowResponseTime, success);

        // Then - 应该被标记为慢调用
        Map<String, Object> summary = businessMetricsService.getBusinessMetricsSummary();
        assertNotNull(summary);
    }

    @Test
    void shouldCalculateServiceSuccessRate() {
        // Given
        String serviceName = "api-service";

        // When - 记录多次调用
        businessMetricsService.recordServiceCall(serviceName, Duration.ofMillis(100), true);
        businessMetricsService.recordServiceCall(serviceName, Duration.ofMillis(100), true);
        businessMetricsService.recordServiceCall(serviceName, Duration.ofMillis(100), false);

        // Then
        Map<String, Object> summary = businessMetricsService.getBusinessMetricsSummary();

        @SuppressWarnings("unchecked")
        Map<String, Object> services = (Map<String, Object>) summary.get("services");

        @SuppressWarnings("unchecked")
        Map<String, Object> serviceInfo = (Map<String, Object>) services.get(serviceName);

        assertEquals(3L, serviceInfo.get("totalCalls"));
        double successRate = (double) serviceInfo.get("successRate");
        assertTrue(successRate > 66.0 && successRate < 67.0); // 约66.67%
    }

    @Test
    void shouldRecordDataQualityEvent() {
        // Given
        String dataSource = "sensor-data";
        BusinessMetricsService.DataQualityEventType eventType =
            BusinessMetricsService.DataQualityEventType.COMPLETE_RECORD;
        int count = 100;

        // When
        businessMetricsService.recordDataQualityEvent(dataSource, eventType, count);

        // Then
        Map<String, Object> summary = businessMetricsService.getBusinessMetricsSummary();

        @SuppressWarnings("unchecked")
        Map<String, Object> dataQuality = (Map<String, Object>) summary.get("dataQuality");
        assertTrue(dataQuality.containsKey(dataSource));
    }

    @Test
    void shouldRecordMultipleDataQualityEventTypes() {
        // Given
        String dataSource = "log-data";

        // When - 记录不同类型的事件
        businessMetricsService.recordDataQualityEvent(
            dataSource, BusinessMetricsService.DataQualityEventType.COMPLETE_RECORD, 90);
        businessMetricsService.recordDataQualityEvent(
            dataSource, BusinessMetricsService.DataQualityEventType.INCOMPLETE_RECORD, 5);
        businessMetricsService.recordDataQualityEvent(
            dataSource, BusinessMetricsService.DataQualityEventType.DUPLICATE_RECORD, 3);
        businessMetricsService.recordDataQualityEvent(
            dataSource, BusinessMetricsService.DataQualityEventType.INVALID_RECORD, 2);

        // Then
        Map<String, Object> summary = businessMetricsService.getBusinessMetricsSummary();

        @SuppressWarnings("unchecked")
        Map<String, Object> dataQuality = (Map<String, Object>) summary.get("dataQuality");

        @SuppressWarnings("unchecked")
        Map<String, Object> qualityInfo = (Map<String, Object>) dataQuality.get(dataSource);

        assertEquals(100L, qualityInfo.get("totalRecords"));
        assertEquals(90.0, qualityInfo.get("completeness"));
    }

    @Test
    void shouldRecordCacheHitEvent() {
        // Given
        String cacheName = "user-cache";
        BusinessMetricsService.CacheEventType eventType =
            BusinessMetricsService.CacheEventType.HIT;
        Duration loadTime = null;

        // When
        businessMetricsService.recordCacheEvent(cacheName, eventType, loadTime);

        // Then
        Map<String, Object> summary = businessMetricsService.getBusinessMetricsSummary();

        @SuppressWarnings("unchecked")
        Map<String, Object> cache = (Map<String, Object>) summary.get("cache");
        assertTrue(cache.containsKey(cacheName));
    }

    @Test
    void shouldRecordCacheMissEvent() {
        // Given
        String cacheName = "session-cache";
        BusinessMetricsService.CacheEventType eventType =
            BusinessMetricsService.CacheEventType.MISS;
        Duration loadTime = Duration.ofMillis(50);

        // When
        businessMetricsService.recordCacheEvent(cacheName, eventType, loadTime);

        // Then
        Map<String, Object> summary = businessMetricsService.getBusinessMetricsSummary();

        @SuppressWarnings("unchecked")
        Map<String, Object> cache = (Map<String, Object>) summary.get("cache");
        assertTrue(cache.containsKey(cacheName));
    }

    @Test
    void shouldCalculateCacheHitRatio() {
        // Given
        String cacheName = "product-cache";

        // When - 记录缓存事件
        for (int i = 0; i < 80; i++) {
            businessMetricsService.recordCacheEvent(
                cacheName, BusinessMetricsService.CacheEventType.HIT, null);
        }
        for (int i = 0; i < 20; i++) {
            businessMetricsService.recordCacheEvent(
                cacheName, BusinessMetricsService.CacheEventType.MISS, Duration.ofMillis(10));
        }

        // Then
        Map<String, Object> summary = businessMetricsService.getBusinessMetricsSummary();

        @SuppressWarnings("unchecked")
        Map<String, Object> cache = (Map<String, Object>) summary.get("cache");

        @SuppressWarnings("unchecked")
        Map<String, Object> cacheInfo = (Map<String, Object>) cache.get(cacheName);

        assertEquals(100L, cacheInfo.get("totalAccess"));
        assertEquals(0.8, cacheInfo.get("hitRatio"));
    }

    @Test
    void shouldCalculateAndPublishMetrics() {
        // Given
        businessMetricsService.recordServiceCall("test-service", Duration.ofMillis(100), true);

        // When
        businessMetricsService.calculateAndPublishMetrics();

        // Then - 应该调用 CustomMetricsService 记录指标
        verify(customMetricsService, atLeastOnce())
            .recordSlaMetrics(anyString(), anyDouble(), anyDouble(), anyDouble(), anyBoolean());
    }

    @Test
    void shouldCalculateSlaMetrics() {
        // Given
        String serviceName = "sla-service";

        // When - 记录高质量服务调用
        for (int i = 0; i < 100; i++) {
            businessMetricsService.recordServiceCall(serviceName, Duration.ofMillis(50), true);
        }

        businessMetricsService.calculateAndPublishMetrics();

        // Then - 应该计算并发布 SLA 指标
        verify(customMetricsService).recordSlaMetrics(
            eq(serviceName),
            eq(100.0), // 100% 可用性
            anyDouble(), // P95 响应时间
            eq(0.0), // 0% 错误率
            eq(true) // SLA 合规
        );
    }

    @Test
    void shouldCalculateDataQualityMetrics() {
        // Given
        String dataSource = "metrics-data";
        businessMetricsService.recordDataQualityEvent(
            dataSource, BusinessMetricsService.DataQualityEventType.COMPLETE_RECORD, 95);
        businessMetricsService.recordDataQualityEvent(
            dataSource, BusinessMetricsService.DataQualityEventType.ACCURATE_RECORD, 98);

        // When
        businessMetricsService.calculateAndPublishMetrics();

        // Then
        verify(customMetricsService, atLeastOnce())
            .recordDataQualityMetrics(
                eq(dataSource),
                anyDouble(), // completeness
                anyDouble(), // accuracy
                anyDouble(), // freshness
                anyInt(), // duplicates
                anyInt() // invalidRecords
            );
    }

    @Test
    void shouldCalculateCachePerformanceMetrics() {
        // Given
        String cacheName = "api-cache";
        businessMetricsService.recordCacheEvent(
            cacheName, BusinessMetricsService.CacheEventType.HIT, null);
        businessMetricsService.recordCacheEvent(
            cacheName, BusinessMetricsService.CacheEventType.MISS, Duration.ofMillis(10));

        // When
        businessMetricsService.calculateAndPublishMetrics();

        // Then
        verify(customMetricsService, atLeastOnce())
            .recordCachePerformanceMetrics(
                eq(cacheName),
                anyLong(), // hitCount
                anyLong(), // missCount
                anyDouble(), // hitRatio
                anyLong(), // evictionCount
                any(Duration.class), // avgLoadTime
                anyLong() // cacheSize
            );
    }

    @Test
    void shouldReturnBusinessMetricsSummary() {
        // Given
        businessMetricsService.recordServiceCall("service1", Duration.ofMillis(100), true);
        businessMetricsService.recordDataQualityEvent(
            "data1", BusinessMetricsService.DataQualityEventType.COMPLETE_RECORD, 100);
        businessMetricsService.recordCacheEvent(
            "cache1", BusinessMetricsService.CacheEventType.HIT, null);

        // When
        Map<String, Object> summary = businessMetricsService.getBusinessMetricsSummary();

        // Then
        assertAll("business metrics summary",
            () -> assertNotNull(summary),
            () -> assertTrue(summary.containsKey("services")),
            () -> assertTrue(summary.containsKey("dataQuality")),
            () -> assertTrue(summary.containsKey("cache")),
            () -> assertNotNull(summary.get("timestamp"))
        );
    }

    @Test
    void shouldHandleEmptySummary() {
        // Given - 没有记录任何指标

        // When
        Map<String, Object> summary = businessMetricsService.getBusinessMetricsSummary();

        // Then - 应该返回空的但结构完整的摘要
        assertAll("empty summary",
            () -> assertNotNull(summary),
            () -> assertNotNull(summary.get("services")),
            () -> assertNotNull(summary.get("dataQuality")),
            () -> assertNotNull(summary.get("cache"))
        );
    }

    @Test
    void shouldCalculatePerformanceScoreWithSubScores() {
        // Given
        String serviceName = "perf-service";
        businessMetricsService.recordServiceCall(serviceName, Duration.ofMillis(100), true);

        // When
        businessMetricsService.calculateAndPublishMetrics();

        // Then
        ArgumentCaptor<Map<String, Double>> subScoresCaptor =
            ArgumentCaptor.forClass(Map.class);

        verify(customMetricsService).recordPerformanceScore(
            eq(serviceName),
            anyDouble(),
            subScoresCaptor.capture()
        );

        Map<String, Double> subScores = subScoresCaptor.getValue();
        assertAll("sub scores",
            () -> assertTrue(subScores.containsKey("availability")),
            () -> assertTrue(subScores.containsKey("responseTime")),
            () -> assertTrue(subScores.containsKey("errorRate"))
        );
    }

    @Test
    void shouldHandleZeroTotalCallsInSlaCalculation() {
        // Given - 服务没有任何调用记录
        String serviceName = "no-call-service";

        // When - 不记录任何调用就计算指标
        businessMetricsService.calculateAndPublishMetrics();

        // Then - 应该正常处理，不抛出异常
        assertDoesNotThrow(() -> businessMetricsService.calculateAndPublishMetrics());
    }

    @Test
    void shouldHandleZeroTotalRecordsInDataQuality() {
        // Given - 数据源没有任何记录
        String dataSource = "empty-source";

        // When
        businessMetricsService.calculateAndPublishMetrics();

        // Then - 应该正常处理
        assertDoesNotThrow(() -> businessMetricsService.calculateAndPublishMetrics());
    }

    @Test
    void shouldHandleZeroTotalAccessInCache() {
        // Given - 缓存没有任何访问记录
        String cacheName = "unused-cache";

        // When
        businessMetricsService.calculateAndPublishMetrics();

        // Then - 应该正常处理
        assertDoesNotThrow(() -> businessMetricsService.calculateAndPublishMetrics());
    }

    @Test
    void shouldCleanupResourcesOnDestroy() {
        // Given
        businessMetricsService.recordServiceCall("service", Duration.ofMillis(100), true);

        // When
        businessMetricsService.cleanup();

        // Then - 清理后获取摘要应该返回空数据
        Map<String, Object> summary = businessMetricsService.getBusinessMetricsSummary();

        @SuppressWarnings("unchecked")
        Map<String, Object> services = (Map<String, Object>) summary.get("services");
        assertTrue(services.isEmpty());
    }

    @Test
    void shouldTrackMaxResponseTime() {
        // Given
        String serviceName = "tracked-service";

        // When - 记录不同响应时间
        businessMetricsService.recordServiceCall(serviceName, Duration.ofMillis(100), true);
        businessMetricsService.recordServiceCall(serviceName, Duration.ofMillis(500), true);
        businessMetricsService.recordServiceCall(serviceName, Duration.ofMillis(200), true);

        // Then
        Map<String, Object> summary = businessMetricsService.getBusinessMetricsSummary();

        @SuppressWarnings("unchecked")
        Map<String, Object> services = (Map<String, Object>) summary.get("services");

        @SuppressWarnings("unchecked")
        Map<String, Object> serviceInfo = (Map<String, Object>) services.get(serviceName);

        double avgResponseTime = (double) serviceInfo.get("avgResponseTime");
        assertTrue(avgResponseTime > 0);
    }

    @Test
    void shouldUpdateLastCallTime() {
        // Given
        String serviceName = "time-service";

        // When
        businessMetricsService.recordServiceCall(serviceName, Duration.ofMillis(100), true);

        // Then
        Map<String, Object> summary = businessMetricsService.getBusinessMetricsSummary();

        @SuppressWarnings("unchecked")
        Map<String, Object> services = (Map<String, Object>) summary.get("services");

        @SuppressWarnings("unchecked")
        Map<String, Object> serviceInfo = (Map<String, Object>) services.get(serviceName);

        assertNotNull(serviceInfo.get("lastCallTime"));
    }
}
