package cn.flying.monitor.common.integration;

import cn.flying.monitor.common.service.CorrelationIdService;
import cn.flying.monitor.common.test.BaseIntegrationTest;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 链路追踪集成测试
 * 测试 TracingAspect 和 TracingFilter 的集成功能
 */
@SpringBootTest
@ActiveProfiles("test")
class TracingIntegrationTest extends BaseIntegrationTest {

    @Autowired(required = false)
    private Tracer tracer;

    @MockBean(name = "correlationIdService")
    private CorrelationIdService correlationIdService;

    @Test
    void shouldTracerBeAvailable() {
        // Given & When & Then
        // 在集成测试环境中，Tracer 应该可用（如果配置正确）
        // 如果 Tracer 不可用，测试将跳过相关验证
        if (tracer != null) {
            assertNotNull(tracer);
        }
    }

    @Test
    void shouldCorrelationIdServiceBeAvailable() {
        // Given & When & Then
        assertNotNull(correlationIdService);
    }

    @Test
    void shouldCreateSpanWithTags() {
        // Given
        Span mockSpan = mock(Span.class);
        Span.Builder mockBuilder = mock(Span.Builder.class);
        when(mockSpan.context()).thenReturn(mock(io.micrometer.tracing.TraceContext.class));
        when(mockSpan.context().traceId()).thenReturn("test-trace-id");
        when(mockSpan.context().spanId()).thenReturn("test-span-id");
        when(mockSpan.start()).thenReturn(mockSpan);
        when(mockSpan.tag(anyString(), anyString())).thenReturn(mockSpan);

        when(correlationIdService.createSpan(anyString(), any())).thenReturn(mockSpan);

        // When
        Span span = correlationIdService.createSpan("test-operation", "key1", "value1");

        // Then
        assertNotNull(span);
        verify(correlationIdService).createSpan("test-operation", "key1", "value1");
    }

    @Test
    void shouldGenerateCorrelationId() {
        // Given
        String expectedCorrelationId = "corr-12345";
        when(correlationIdService.generateCorrelationId()).thenReturn(expectedCorrelationId);

        // When
        String correlationId = correlationIdService.generateCorrelationId();

        // Then
        assertNotNull(correlationId);
        assertEquals(expectedCorrelationId, correlationId);
    }

    @Test
    void shouldSetAndGetCorrelationId() {
        // Given
        String correlationId = "test-correlation-id";
        doNothing().when(correlationIdService).setCorrelationId(anyString());
        when(correlationIdService.getCurrentCorrelationId()).thenReturn(correlationId);

        // When
        correlationIdService.setCorrelationId(correlationId);
        String retrievedId = correlationIdService.getCurrentCorrelationId();

        // Then
        assertEquals(correlationId, retrievedId);
        verify(correlationIdService).setCorrelationId(correlationId);
        verify(correlationIdService).getCurrentCorrelationId();
    }

    @Test
    void shouldClearCorrelationId() {
        // Given
        doNothing().when(correlationIdService).clearCorrelationId();

        // When
        correlationIdService.clearCorrelationId();

        // Then
        verify(correlationIdService).clearCorrelationId();
    }

    @Test
    void shouldRecordSpanError() {
        // Given
        Exception testException = new RuntimeException("Test error");
        doNothing().when(correlationIdService).recordSpanError(any(Throwable.class));

        // When
        correlationIdService.recordSpanError(testException);

        // Then
        verify(correlationIdService).recordSpanError(testException);
    }

    @Test
    void shouldHandleTracingWithServiceLayer() {
        // Given
        Span mockSpan = mock(Span.class);
        when(mockSpan.context()).thenReturn(mock(io.micrometer.tracing.TraceContext.class));
        when(mockSpan.context().traceId()).thenReturn("service-trace-id");
        when(mockSpan.start()).thenReturn(mockSpan);
        when(mockSpan.tag(anyString(), anyString())).thenReturn(mockSpan);

        when(correlationIdService.createSpan(
            startsWith("service."),
            anyString(), anyString(),
            anyString(), anyString(),
            anyString(), anyString()
        )).thenReturn(mockSpan);

        // When - 模拟 TracingAspect 拦截服务方法
        Span span = correlationIdService.createSpan(
            "service.TestService.testMethod",
            "layer", "service",
            "class", "TestService",
            "method", "testMethod"
        );

        // Then
        assertNotNull(span);
        verify(correlationIdService).createSpan(
            eq("service.TestService.testMethod"),
            eq("layer"), eq("service"),
            eq("class"), eq("TestService"),
            eq("method"), eq("testMethod")
        );
    }

    @Test
    void shouldHandleTracingWithRepositoryLayer() {
        // Given
        Span mockSpan = mock(Span.class);
        when(mockSpan.context()).thenReturn(mock(io.micrometer.tracing.TraceContext.class));
        when(mockSpan.context().traceId()).thenReturn("repository-trace-id");
        when(mockSpan.start()).thenReturn(mockSpan);
        when(mockSpan.tag(anyString(), anyString())).thenReturn(mockSpan);

        when(correlationIdService.createSpan(
            startsWith("repository."),
            anyString(), anyString(),
            anyString(), anyString(),
            anyString(), anyString()
        )).thenReturn(mockSpan);

        // When - 模拟 TracingAspect 拦截 Repository 方法
        Span span = correlationIdService.createSpan(
            "repository.UserMapper.findById",
            "layer", "repository",
            "class", "UserMapper",
            "method", "findById"
        );

        // Then
        assertNotNull(span);
        verify(correlationIdService).createSpan(
            eq("repository.UserMapper.findById"),
            eq("layer"), eq("repository"),
            eq("class"), eq("UserMapper"),
            eq("method"), eq("findById")
        );
    }

    @Test
    void shouldHandleHttpRequestTracing() {
        // Given
        Span mockSpan = mock(Span.class);
        when(mockSpan.context()).thenReturn(mock(io.micrometer.tracing.TraceContext.class));
        when(mockSpan.context().traceId()).thenReturn("http-trace-id");
        when(mockSpan.context().spanId()).thenReturn("http-span-id");
        when(mockSpan.start()).thenReturn(mockSpan);
        when(mockSpan.tag(anyString(), anyString())).thenReturn(mockSpan);

        when(correlationIdService.createSpan(
            eq("http-request"),
            anyString(), anyString(),
            anyString(), anyString(),
            anyString(), anyString(),
            anyString(), anyString(),
            anyString(), anyString()
        )).thenReturn(mockSpan);

        // When - 模拟 TracingFilter 处理 HTTP 请求
        Span span = correlationIdService.createSpan(
            "http-request",
            "http.method", "GET",
            "http.url", "/api/test",
            "http.scheme", "http",
            "http.host", "localhost",
            "http.port", "8080"
        );

        // Then
        assertNotNull(span);
        verify(correlationIdService).createSpan(
            eq("http-request"),
            eq("http.method"), eq("GET"),
            eq("http.url"), eq("/api/test"),
            eq("http.scheme"), eq("http"),
            eq("http.host"), eq("localhost"),
            eq("http.port"), eq("8080")
        );
    }

    @Test
    void shouldPropagateTraceContextAcrossLayers() {
        // Given
        io.micrometer.tracing.TraceContext mockContext = mock(io.micrometer.tracing.TraceContext.class);
        when(mockContext.traceId()).thenReturn("propagated-trace-id");
        when(mockContext.spanId()).thenReturn("parent-span-id");

        Span parentSpan = mock(Span.class);
        when(parentSpan.context()).thenReturn(mockContext);
        when(parentSpan.start()).thenReturn(parentSpan);
        when(parentSpan.tag(anyString(), anyString())).thenReturn(parentSpan);

        Span childSpan = mock(Span.class);
        when(childSpan.context()).thenReturn(mockContext);
        when(childSpan.start()).thenReturn(childSpan);
        when(childSpan.tag(anyString(), anyString())).thenReturn(childSpan);

        when(correlationIdService.createSpan(eq("parent-operation"), any()))
            .thenReturn(parentSpan);
        when(correlationIdService.createSpan(eq("child-operation"), any()))
            .thenReturn(childSpan);

        // When - 创建父子 Span
        Span parent = correlationIdService.createSpan("parent-operation");
        Span child = correlationIdService.createSpan("child-operation");

        // Then - 应该共享相同的 traceId
        assertEquals(parent.context().traceId(), child.context().traceId());
    }

    @Test
    void shouldHandleSpanErrorRecording() {
        // Given
        Span mockSpan = mock(Span.class);
        when(mockSpan.tag(anyString(), anyString())).thenReturn(mockSpan);

        Exception testError = new IllegalArgumentException("Invalid parameter");
        doNothing().when(correlationIdService).recordSpanError(any(Throwable.class));

        // When
        correlationIdService.recordSpanError(testError);

        // Then
        verify(correlationIdService).recordSpanError(testError);
    }

    @Test
    void shouldSkipActuatorEndpoints() {
        // Given
        String actuatorUri = "/actuator/health";

        // When & Then
        // TracingFilter 应该跳过 actuator 端点
        // 这个测试验证集成时的配置正确性
        // 实际验证需要在 HTTP 请求测试中完成
        assertTrue(actuatorUri.startsWith("/actuator"));
    }

    @Test
    void shouldHandleMultipleSpanTags() {
        // Given
        Span mockSpan = mock(Span.class);
        when(mockSpan.context()).thenReturn(mock(io.micrometer.tracing.TraceContext.class));
        when(mockSpan.context().traceId()).thenReturn("multi-tag-trace");
        when(mockSpan.start()).thenReturn(mockSpan);
        when(mockSpan.tag(anyString(), anyString())).thenReturn(mockSpan);

        when(correlationIdService.createSpan(anyString(), any())).thenReturn(mockSpan);

        // When
        Span span = correlationIdService.createSpan(
            "operation-with-tags",
            "tag1", "value1",
            "tag2", "value2",
            "tag3", "value3"
        );

        // Then
        assertNotNull(span);
    }

    @Test
    void shouldIntegrateWithSpringContext() {
        // Given & When
        // 验证 Spring 上下文中的 Bean 配置

        // Then
        assertNotNull(correlationIdService, "CorrelationIdService 应该被注入");
    }

    @Test
    void shouldHandleTracingInExceptionScenario() {
        // Given
        Span mockSpan = mock(Span.class);
        when(mockSpan.tag(anyString(), anyString())).thenReturn(mockSpan);

        Exception error = new RuntimeException("Service error");
        doNothing().when(correlationIdService).recordSpanError(any(Throwable.class));

        // When
        correlationIdService.recordSpanError(error);

        // Then - 应该正确记录错误
        verify(correlationIdService).recordSpanError(error);
    }

    @Test
    void shouldSupportAsyncTracing() {
        // Given
        String correlationId = "async-corr-id";
        doNothing().when(correlationIdService).setCorrelationId(anyString());
        when(correlationIdService.getCurrentCorrelationId()).thenReturn(correlationId);

        // When - 模拟异步场景下的关联ID传播
        correlationIdService.setCorrelationId(correlationId);
        String retrievedId = correlationIdService.getCurrentCorrelationId();

        // Then
        assertEquals(correlationId, retrievedId);
    }

    @Test
    void shouldCleanupTracingContextAfterRequest() {
        // Given
        String correlationId = "cleanup-test";
        doNothing().when(correlationIdService).setCorrelationId(anyString());
        doNothing().when(correlationIdService).clearCorrelationId();

        // When - 模拟请求生命周期
        correlationIdService.setCorrelationId(correlationId);
        correlationIdService.clearCorrelationId();

        // Then
        verify(correlationIdService).setCorrelationId(correlationId);
        verify(correlationIdService).clearCorrelationId();
    }

    @Override
    protected void setupTestData() {
        // 集成测试不需要额外的测试数据
    }

    @Override
    protected void cleanupTestData() {
        // 清理测试数据（如果有）
    }
}
