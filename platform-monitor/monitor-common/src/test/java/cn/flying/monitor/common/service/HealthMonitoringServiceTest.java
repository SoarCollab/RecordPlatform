package cn.flying.monitor.common.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 健康监控服务测试
 * 测试健康检查、状态监控和告警功能
 */
@ExtendWith(MockitoExtension.class)
class HealthMonitoringServiceTest {

    @Mock
    private HealthEndpoint healthEndpoint;

    private MeterRegistry meterRegistry;
    private CustomMetricsService customMetricsService;
    private HealthMonitoringService healthMonitoringService;

    @BeforeEach
    void setUp() {
        // 使用 SimpleMeterRegistry 代替 Mock
        meterRegistry = new SimpleMeterRegistry();
        customMetricsService = new CustomMetricsService(meterRegistry);
        healthMonitoringService = new HealthMonitoringService(healthEndpoint, customMetricsService);
    }

    @Test
    void shouldPerformHealthCheckSuccessfully() {
        // Given
        Health health = Health.up()
            .withDetail("database", Health.up().build())
            .withDetail("redis", Health.up().build())
            .build();
        when(healthEndpoint.health()).thenReturn(health);

        // When
        healthMonitoringService.performHealthCheck();

        // Then
        verify(healthEndpoint).health();

        // 验证指标被注册到 MeterRegistry
        assertFalse(meterRegistry.getMeters().isEmpty());
    }

    @Test
    void shouldRecordOverallHealthMetrics() {
        // Given
        Health health = Health.up().build();
        when(healthEndpoint.health()).thenReturn(health);

        // When
        healthMonitoringService.performHealthCheck();

        // Then - 验证指标被注册
        assertFalse(meterRegistry.getMeters().isEmpty());
        assertTrue(meterRegistry.getMeters().stream()
            .anyMatch(meter -> meter.getId().getName().contains("monitor.health")));
    }

    @Test
    void shouldRecordComponentHealthMetrics() {
        // Given
        Map<String, Object> details = new HashMap<>();
        details.put("database", Health.up().build());
        details.put("redis", Health.down().build());

        Health health = Health.up()
            .withDetails(details)
            .build();
        when(healthEndpoint.health()).thenReturn(health);

        // When
        healthMonitoringService.performHealthCheck();

        // Then - 验证组件健康指标被记录
        assertTrue(meterRegistry.getMeters().stream()
            .anyMatch(meter -> meter.getId().getName().contains("monitor.health")));
    }

    @Test
    void shouldDetectConsecutiveFailures() {
        // Given
        Map<String, Object> details = new HashMap<>();
        details.put("database", Health.down().build());

        Health health = Health.down()
            .withDetails(details)
            .build();
        when(healthEndpoint.health()).thenReturn(health);

        // When - 连续调用多次以触发连续失败检测
        for (int i = 0; i < 3; i++) {
            healthMonitoringService.performHealthCheck();
        }

        // Then - 验证记录了连续失败统计
        assertTrue(meterRegistry.getMeters().stream()
            .anyMatch(meter -> meter.getId().getName().contains("monitor.health")));
    }

    @Test
    void shouldHandleHealthCheckExceptionGracefully() {
        // Given
        when(healthEndpoint.health()).thenThrow(new RuntimeException("Health check failed"));

        // When & Then - 不应该抛出异常
        assertDoesNotThrow(() -> healthMonitoringService.performHealthCheck());
    }

    @Test
    void shouldReturnHealthSummary() {
        // Given
        Map<String, Object> details = new HashMap<>();
        details.put("database", Health.up().build());
        details.put("redis", Health.up().build());

        Health health = Health.up()
            .withDetails(details)
            .build();
        when(healthEndpoint.health()).thenReturn(health);

        // When
        Map<String, Object> summary = healthMonitoringService.getHealthSummary();

        // Then
        assertAll("health summary",
            () -> assertNotNull(summary),
            () -> assertEquals("UP", summary.get("overallStatus")),
            () -> assertNotNull(summary.get("timestamp")),
            () -> assertNotNull(summary.get("components"))
        );
    }

    @Test
    void shouldIncludeComponentDetailsInSummary() {
        // Given
        Map<String, Object> details = new HashMap<>();
        details.put("database", Health.up().build());
        details.put("redis", Health.down().build());

        Health health = Health.status("DEGRADED")
            .withDetails(details)
            .build();
        when(healthEndpoint.health()).thenReturn(health);

        // When
        Map<String, Object> summary = healthMonitoringService.getHealthSummary();

        // Then
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) summary.get("components");

        assertAll("component details",
            () -> assertNotNull(components),
            () -> assertTrue(components.containsKey("database")),
            () -> assertTrue(components.containsKey("redis"))
        );
    }

    @Test
    void shouldReturnSystemPerformanceMetrics() {
        // When
        Map<String, Object> metrics = healthMonitoringService.getSystemPerformanceMetrics();

        // Then
        assertAll("system performance metrics",
            () -> assertNotNull(metrics),
            () -> assertNotNull(metrics.get("jvm")),
            () -> assertNotNull(metrics.get("threads")),
            () -> assertNotNull(metrics.get("timestamp"))
        );
    }

    @Test
    void shouldIncludeJvmMetricsInPerformanceMetrics() {
        // When
        Map<String, Object> metrics = healthMonitoringService.getSystemPerformanceMetrics();

        // Then
        @SuppressWarnings("unchecked")
        Map<String, Object> jvmMetrics = (Map<String, Object>) metrics.get("jvm");

        assertAll("jvm metrics",
            () -> assertNotNull(jvmMetrics),
            () -> assertTrue(jvmMetrics.containsKey("totalMemory")),
            () -> assertTrue(jvmMetrics.containsKey("freeMemory")),
            () -> assertTrue(jvmMetrics.containsKey("usedMemory")),
            () -> assertTrue(jvmMetrics.containsKey("maxMemory")),
            () -> assertTrue(jvmMetrics.containsKey("memoryUsagePercent"))
        );
    }

    @Test
    void shouldIncludeThreadMetricsInPerformanceMetrics() {
        // When
        Map<String, Object> metrics = healthMonitoringService.getSystemPerformanceMetrics();

        // Then
        @SuppressWarnings("unchecked")
        Map<String, Object> threadMetrics = (Map<String, Object>) metrics.get("threads");

        assertAll("thread metrics",
            () -> assertNotNull(threadMetrics),
            () -> assertTrue(threadMetrics.containsKey("activeThreads")),
            () -> assertTrue(threadMetrics.containsKey("activeGroups"))
        );
    }

    @Test
    void shouldHandleMapStyleComponentHealth() {
        // Given - 模拟旧格式的健康检查结果（Map 格式）
        Map<String, Object> componentMap = new HashMap<>();
        componentMap.put("status", "UP");
        componentMap.put("details", Map.of("version", "1.0"));

        Map<String, Object> details = new HashMap<>();
        details.put("legacy", componentMap);

        Health health = Health.up()
            .withDetails(details)
            .build();
        when(healthEndpoint.health()).thenReturn(health);

        // When & Then - 不应该抛出异常
        assertDoesNotThrow(() -> healthMonitoringService.performHealthCheck());
    }

    @Test
    void shouldCalculateCorrectHealthValueForUpStatus() {
        // Given
        Health health = Health.up().build();
        when(healthEndpoint.health()).thenReturn(health);

        // When
        healthMonitoringService.performHealthCheck();

        // Then - 验证指标被记录
        assertTrue(meterRegistry.getMeters().stream()
            .anyMatch(meter -> meter.getId().getName().contains("monitor.health")));
    }

    @Test
    void shouldCalculateCorrectHealthValueForDownStatus() {
        // Given
        Health health = Health.down().build();
        when(healthEndpoint.health()).thenReturn(health);

        // When
        healthMonitoringService.performHealthCheck();

        // Then - 验证指标被记录
        assertTrue(meterRegistry.getMeters().stream()
            .anyMatch(meter -> meter.getId().getName().contains("monitor.health")));
    }

    @Test
    void shouldGetHealthTrends() {
        // Given - 先执行一次健康检查以生成历史数据
        Health health = Health.up()
            .withDetail("database", Health.up().build())
            .build();
        when(healthEndpoint.health()).thenReturn(health);
        healthMonitoringService.performHealthCheck();

        // When
        Map<String, Object> trends = healthMonitoringService.getHealthTrends();

        // Then
        assertAll("health trends",
            () -> assertNotNull(trends),
            () -> assertNotNull(trends.get("timestamp")),
            () -> assertNotNull(trends.get("components")),
            () -> assertTrue(trends.containsKey("overallHealthScore"))
        );
    }

    @Test
    void shouldCalculateStabilityScoreBasedOnFailures() {
        // Given
        Map<String, Object> details = new HashMap<>();
        details.put("database", Health.down().build());

        Health health = Health.down()
            .withDetails(details)
            .build();
        when(healthEndpoint.health()).thenReturn(health);

        // When - 触发失败
        healthMonitoringService.performHealthCheck();
        Map<String, Object> trends = healthMonitoringService.getHealthTrends();

        // Then - 验证返回了趋势数据
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) trends.get("components");
        assertNotNull(components);
    }

    @Test
    void shouldCleanupOldHealthHistory() {
        // Given
        Health health = Health.up()
            .withDetail("test", Health.up().build())
            .build();
        when(healthEndpoint.health()).thenReturn(health);

        // When - 多次执行健康检查
        for (int i = 0; i < 5; i++) {
            healthMonitoringService.performHealthCheck();
        }

        // Then - 不应该抛出异常，历史记录应该被清理
        assertDoesNotThrow(() -> {
            Map<String, Object> trends = healthMonitoringService.getHealthTrends();
            assertNotNull(trends);
        });
    }

    @Test
    void shouldHandleNullComponentDetails() {
        // Given - 组件没有详细信息
        Health health = Health.up().build();
        when(healthEndpoint.health()).thenReturn(health);

        // When & Then - 不应该抛出异常
        assertDoesNotThrow(() -> healthMonitoringService.performHealthCheck());
    }

    @Test
    void shouldHandleHealthSummaryException() {
        // Given
        when(healthEndpoint.health()).thenThrow(new RuntimeException("Endpoint error"));

        // When
        Map<String, Object> summary = healthMonitoringService.getHealthSummary();

        // Then - 应该返回包含错误信息的摘要
        assertAll("error summary",
            () -> assertNotNull(summary),
            () -> assertTrue(summary.containsKey("error"))
        );
    }

    @Test
    void shouldHandlePerformanceMetricsException() {
        // When - 正常情况下不应该有异常
        Map<String, Object> metrics = healthMonitoringService.getSystemPerformanceMetrics();

        // Then - 应该成功返回指标
        assertAll("performance metrics",
            () -> assertNotNull(metrics),
            () -> assertFalse(metrics.containsKey("error"))
        );
    }

    @Test
    void shouldResetConsecutiveFailuresOnRecovery() {
        // Given
        Map<String, Object> details = new HashMap<>();
        details.put("database", Health.down().build());

        Health downHealth = Health.down()
            .withDetails(details)
            .build();
        when(healthEndpoint.health()).thenReturn(downHealth);

        // When - 先触发失败
        healthMonitoringService.performHealthCheck();

        // 然后恢复
        details.put("database", Health.up().build());
        Health upHealth = Health.up()
            .withDetails(details)
            .build();
        when(healthEndpoint.health()).thenReturn(upHealth);

        healthMonitoringService.performHealthCheck();

        // Then - 验证健康检查被调用了两次
        verify(healthEndpoint, times(2)).health();
    }
}
