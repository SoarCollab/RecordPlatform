package cn.flying.monitor.common.filter;

import cn.flying.monitor.common.service.CorrelationIdService;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 链路追踪过滤器
 * 自动为HTTP请求创建和传播链路追踪信息
 */
@Slf4j
@Component
@Order(0) // 确保在其他过滤器之前执行
public class TracingFilter implements Filter {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String SPAN_ID_HEADER = "X-Span-Id";
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private final Tracer tracer;
    private final CorrelationIdService correlationIdService;

    public TracingFilter(Tracer tracer, CorrelationIdService correlationIdService) {
        this.tracer = tracer;
        this.correlationIdService = correlationIdService;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpRequest) || 
            !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        // 跳过actuator端点
        String requestURI = httpRequest.getRequestURI();
        if (requestURI.startsWith("/actuator")) {
            chain.doFilter(request, response);
            return;
        }

        String method = httpRequest.getMethod();
        String uri = httpRequest.getRequestURI();
        
        // 从请求头获取现有的追踪信息
        String existingTraceId = httpRequest.getHeader(TRACE_ID_HEADER);
        String existingCorrelationId = httpRequest.getHeader(CORRELATION_ID_HEADER);

        // 创建或继续Span
        Span span = null;
        try {
            // 创建新的Span
            span = correlationIdService.createSpan("http-request",
                "http.method", method,
                "http.url", uri,
                "http.scheme", httpRequest.getScheme(),
                "http.host", httpRequest.getServerName(),
                "http.port", String.valueOf(httpRequest.getServerPort())
            );

            span.start();

            // 设置关联ID
            String correlationId = existingCorrelationId != null ? 
                existingCorrelationId : correlationIdService.generateCorrelationId();
            correlationIdService.setCorrelationId(correlationId);

            // 添加追踪信息到响应头
            String traceId = span.context().traceId();
            String spanId = span.context().spanId();
            
            httpResponse.setHeader(TRACE_ID_HEADER, traceId);
            httpResponse.setHeader(SPAN_ID_HEADER, spanId);
            httpResponse.setHeader(CORRELATION_ID_HEADER, correlationId);

            log.debug("开始HTTP请求追踪: {} {}, TraceId: {}, SpanId: {}, CorrelationId: {}", 
                     method, uri, traceId, spanId, correlationId);

            // 继续处理请求
            chain.doFilter(request, response);

            // 记录响应状态
            int statusCode = httpResponse.getStatus();
            span.tag("http.status_code", String.valueOf(statusCode));
            
            if (statusCode >= 400) {
                span.tag("error", "true");
                if (statusCode >= 500) {
                    span.tag("error.kind", "server_error");
                } else {
                    span.tag("error.kind", "client_error");
                }
            }

            log.debug("完成HTTP请求追踪: {} {} - {}, TraceId: {}", 
                     method, uri, statusCode, traceId);

        } catch (Exception e) {
            if (span != null) {
                span.tag("error", "true");
                span.tag("error.message", e.getMessage());
                correlationIdService.recordSpanError(e);
            }
            log.error("HTTP请求追踪异常: {} {}", method, uri, e);
            throw e;
        } finally {
            if (span != null) {
                span.end();
            }
            // 清理ThreadLocal
            correlationIdService.clearCorrelationId();
        }
    }
}