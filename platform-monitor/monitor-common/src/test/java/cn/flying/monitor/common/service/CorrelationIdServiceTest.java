package cn.flying.monitor.common.service;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 关联ID服务测试
 */
@ExtendWith(MockitoExtension.class)
class CorrelationIdServiceTest {

    @Mock
    private Tracer tracer;

    @Mock
    private Span span;

    @Mock
    private TraceContext traceContext;

    private CorrelationIdService correlationIdService;

    @BeforeEach
    void setUp() {
        correlationIdService = new CorrelationIdService(tracer);
    }

    @Test
    void testGenerateCorrelationId() {
        // When
        String correlationId = correlationIdService.generateCorrelationId();

        // Then
        assertThat(correlationId).isNotNull();
        assertThat(correlationId).hasSize(32); // UUID without dashes
        assertThat(correlationId).doesNotContain("-");
        
        // 验证设置到ThreadLocal
        String currentId = correlationIdService.getCurrentCorrelationId();
        assertThat(currentId).isEqualTo(correlationId);
    }

    @Test
    void testGetCurrentCorrelationIdFromTraceContext() {
        // Given
        String traceId = "abc123def456";
        when(tracer.nextSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn(traceId);

        // When
        String correlationId = correlationIdService.getCurrentCorrelationId();

        // Then
        assertThat(correlationId).isEqualTo(traceId);
    }

    @Test
    void testGetCurrentCorrelationIdFromThreadLocal() {
        // Given
        String expectedId = "test-correlation-id";
        correlationIdService.setCorrelationId(expectedId);
        
        // Mock tracer to return null span
        when(tracer.nextSpan()).thenReturn(null);

        // When
        String correlationId = correlationIdService.getCurrentCorrelationId();

        // Then
        assertThat(correlationId).isEqualTo(expectedId);
    }

    @Test
    void testGetCurrentCorrelationIdGeneratesNewWhenNoneExists() {
        // Given
        when(tracer.nextSpan()).thenReturn(null);

        // When
        String correlationId = correlationIdService.getCurrentCorrelationId();

        // Then
        assertThat(correlationId).isNotNull();
        assertThat(correlationId).hasSize(32);
    }

    @Test
    void testSetAndClearCorrelationId() {
        // Given
        String testId = "test-id-123";

        // When
        correlationIdService.setCorrelationId(testId);

        // Then
        String currentId = correlationIdService.getCurrentCorrelationId();
        assertThat(currentId).isEqualTo(testId);

        // When
        correlationIdService.clearCorrelationId();

        // Then - 应该生成新的ID，因为ThreadLocal已清空
        when(tracer.nextSpan()).thenReturn(null);
        String newId = correlationIdService.getCurrentCorrelationId();
        assertThat(newId).isNotEqualTo(testId);
        assertThat(newId).hasSize(32);
    }

    @Test
    void testGetCurrentTraceId() {
        // Given
        String traceId = "trace-123";
        when(tracer.nextSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn(traceId);

        // When
        String result = correlationIdService.getCurrentTraceId();

        // Then
        assertThat(result).isEqualTo(traceId);
    }

    @Test
    void testGetCurrentTraceIdReturnsNullWhenNoSpan() {
        // Given
        when(tracer.nextSpan()).thenReturn(null);

        // When
        String result = correlationIdService.getCurrentTraceId();

        // Then
        assertThat(result).isNull();
    }

    @Test
    void testGetCurrentSpanId() {
        // Given
        String spanId = "span-456";
        when(tracer.nextSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.spanId()).thenReturn(spanId);

        // When
        String result = correlationIdService.getCurrentSpanId();

        // Then
        assertThat(result).isEqualTo(spanId);
    }

    @Test
    void testCreateSpan() {
        // Given
        String spanName = "test-span";
        when(tracer.nextSpan()).thenReturn(span);
        when(span.name(spanName)).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn("trace-123");
        when(traceContext.spanId()).thenReturn("span-456");

        // When
        Span result = correlationIdService.createSpan(spanName);

        // Then
        assertThat(result).isEqualTo(span);
        verify(span).name(spanName);
    }

    @Test
    void testCreateSpanWithTags() {
        // Given
        String spanName = "test-span";
        String[] tags = {"key1", "value1", "key2", "value2"};
        when(tracer.nextSpan()).thenReturn(span);
        when(span.name(spanName)).thenReturn(span);
        when(span.tag("key1", "value1")).thenReturn(span);
        when(span.tag("key2", "value2")).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn("trace-123");
        when(traceContext.spanId()).thenReturn("span-456");

        // When
        Span result = correlationIdService.createSpan(spanName, tags);

        // Then
        assertThat(result).isEqualTo(span);
        verify(span).name(spanName);
        verify(span).tag("key1", "value1");
        verify(span).tag("key2", "value2");
    }

    @Test
    void testAddSpanEvent() {
        // Given
        String eventName = "test-event";
        when(tracer.nextSpan()).thenReturn(span);

        // When
        correlationIdService.addSpanEvent(eventName);

        // Then
        verify(span).event(eventName);
    }

    @Test
    void testAddSpanTag() {
        // Given
        String key = "test-key";
        String value = "test-value";
        when(tracer.nextSpan()).thenReturn(span);

        // When
        correlationIdService.addSpanTag(key, value);

        // Then
        verify(span).tag(key, value);
    }

    @Test
    void testRecordSpanError() {
        // Given
        Throwable throwable = new RuntimeException("Test error");
        when(tracer.nextSpan()).thenReturn(span);

        // When
        correlationIdService.recordSpanError(throwable);

        // Then
        verify(span).error(throwable);
    }

    @Test
    void testSpanOperationsWithNullSpan() {
        // Given
        when(tracer.nextSpan()).thenReturn(null);

        // When & Then - 这些操作应该不抛异常
        correlationIdService.addSpanEvent("test-event");
        correlationIdService.addSpanTag("key", "value");
        correlationIdService.recordSpanError(new RuntimeException("test"));

        // 验证没有调用任何span方法
        verifyNoInteractions(span);
    }
}