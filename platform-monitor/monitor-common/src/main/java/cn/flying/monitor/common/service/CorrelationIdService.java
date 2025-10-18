package cn.flying.monitor.common.service;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 关联ID服务
 * 提供分布式链路追踪的关联ID管理功能
 */
@Slf4j
@Service
public class CorrelationIdService {

    private final Tracer tracer;
    private final ThreadLocal<String> correlationIdHolder = new ThreadLocal<>();

    public CorrelationIdService(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * 生成新的关联ID
     */
    public String generateCorrelationId() {
        String correlationId = UUID.randomUUID().toString().replace("-", "");
        setCorrelationId(correlationId);
        log.debug("生成新的关联ID: {}", correlationId);
        return correlationId;
    }

    /**
     * 获取当前关联ID
     * 优先从链路追踪上下文获取，其次从ThreadLocal获取
     */
    public String getCurrentCorrelationId() {
        // 首先尝试从链路追踪上下文获取
        Span currentSpan = tracer.nextSpan();
        if (currentSpan != null) {
            TraceContext traceContext = currentSpan.context();
            if (traceContext != null) {
                String traceId = traceContext.traceId();
                if (traceId != null && !traceId.isEmpty()) {
                    return traceId;
                }
            }
        }

        // 其次从ThreadLocal获取
        String correlationId = correlationIdHolder.get();
        if (correlationId != null) {
            return correlationId;
        }

        // 如果都没有，生成一个新的
        return generateCorrelationId();
    }

    /**
     * 设置关联ID到ThreadLocal
     */
    public void setCorrelationId(String correlationId) {
        correlationIdHolder.set(correlationId);
        log.debug("设置关联ID到ThreadLocal: {}", correlationId);
    }

    /**
     * 清除当前线程的关联ID
     */
    public void clearCorrelationId() {
        String correlationId = correlationIdHolder.get();
        correlationIdHolder.remove();
        log.debug("清除ThreadLocal关联ID: {}", correlationId);
    }

    /**
     * 获取当前链路追踪ID
     */
    public String getCurrentTraceId() {
        Span currentSpan = tracer.nextSpan();
        if (currentSpan != null && currentSpan.context() != null) {
            return currentSpan.context().traceId();
        }
        return null;
    }

    /**
     * 获取当前Span ID
     */
    public String getCurrentSpanId() {
        Span currentSpan = tracer.nextSpan();
        if (currentSpan != null && currentSpan.context() != null) {
            return currentSpan.context().spanId();
        }
        return null;
    }

    /**
     * 创建新的Span
     */
    public Span createSpan(String name) {
        Span span = tracer.nextSpan().name(name);
        log.debug("创建新的Span: {}, TraceId: {}, SpanId: {}", 
                 name, span.context().traceId(), span.context().spanId());
        return span;
    }

    /**
     * 创建带标签的Span
     */
    public Span createSpan(String name, String... tags) {
        Span span = tracer.nextSpan().name(name);
        
        // 添加标签
        for (int i = 0; i < tags.length; i += 2) {
            if (i + 1 < tags.length) {
                span.tag(tags[i], tags[i + 1]);
            }
        }
        
        log.debug("创建带标签的Span: {}, TraceId: {}, SpanId: {}", 
                 name, span.context().traceId(), span.context().spanId());
        return span;
    }

    /**
     * 为当前Span添加事件
     */
    public void addSpanEvent(String eventName) {
        Span currentSpan = tracer.nextSpan();
        if (currentSpan != null) {
            currentSpan.event(eventName);
            log.debug("为Span添加事件: {}", eventName);
        }
    }

    /**
     * 为当前Span添加标签
     */
    public void addSpanTag(String key, String value) {
        Span currentSpan = tracer.nextSpan();
        if (currentSpan != null) {
            currentSpan.tag(key, value);
            log.debug("为Span添加标签: {}={}", key, value);
        }
    }

    /**
     * 记录Span错误
     */
    public void recordSpanError(Throwable throwable) {
        Span currentSpan = tracer.nextSpan();
        if (currentSpan != null) {
            currentSpan.error(throwable);
            log.debug("记录Span错误: {}", throwable.getMessage());
        }
    }
}