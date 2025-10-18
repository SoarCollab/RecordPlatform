package cn.flying.monitor.common.filter;

import cn.flying.monitor.common.service.CustomMetricsService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * HTTP请求指标收集过滤器
 * 自动收集所有HTTP请求的性能指标
 */
@Slf4j
@Component
@Order(1)
public class MetricsFilter implements Filter {

    private final MeterRegistry meterRegistry;
    private final CustomMetricsService customMetricsService;

    public MetricsFilter(MeterRegistry meterRegistry, CustomMetricsService customMetricsService) {
        this.meterRegistry = meterRegistry;
        this.customMetricsService = customMetricsService;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        if (!(request instanceof HttpServletRequest httpRequest) || 
            !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        // 跳过actuator端点的指标收集，避免循环
        String requestURI = httpRequest.getRequestURI();
        if (requestURI.startsWith("/actuator")) {
            chain.doFilter(request, response);
            return;
        }

        Instant startTime = Instant.now();
        String method = httpRequest.getMethod();
        String uri = normalizeUri(requestURI);

        try {
            chain.doFilter(request, response);
        } finally {
            Duration duration = Duration.between(startTime, Instant.now());
            int statusCode = httpResponse.getStatus();
            String statusClass = getStatusClass(statusCode);

            // 记录HTTP请求指标
            Timer.builder("monitor.http.requests")
                .description("HTTP请求统计")
                .tag("method", method)
                .tag("uri", uri)
                .tag("status", String.valueOf(statusCode))
                .tag("status_class", statusClass)
                .register(meterRegistry)
                .record(duration);

            // 记录请求大小（如果有Content-Length）
            String contentLength = httpRequest.getHeader("Content-Length");
            if (contentLength != null) {
                try {
                    long requestSize = Long.parseLong(contentLength);
                    io.micrometer.core.instrument.DistributionSummary.builder("monitor.http.request.size")
                        .description("HTTP请求大小")
                        .tags("method", method, "uri", uri)
                        .register(meterRegistry)
                        .record(requestSize);
                } catch (NumberFormatException e) {
                    log.debug("无法解析Content-Length: {}", contentLength);
                }
            }

            // 记录慢请求
            if (duration.toMillis() > 1000) { // 超过1秒的请求
                customMetricsService.createCounter(
                    "monitor.http.slow.requests",
                    "慢HTTP请求统计",
                    "method", method,
                    "uri", uri,
                    "duration_range", getDurationRange(duration)
                ).increment();
            }

            log.debug("HTTP请求指标: {} {} - {}ms - {}", method, uri, duration.toMillis(), statusCode);
        }
    }

    /**
     * 标准化URI，移除路径参数
     */
    private String normalizeUri(String uri) {
        if (uri == null) {
            return "unknown";
        }

        // 移除查询参数
        int queryIndex = uri.indexOf('?');
        if (queryIndex != -1) {
            uri = uri.substring(0, queryIndex);
        }

        // 替换数字ID为占位符，减少指标基数
        uri = uri.replaceAll("/\\d+", "/{id}");
        
        // 替换UUID为占位符
        uri = uri.replaceAll("/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "/{uuid}");

        return uri;
    }

    /**
     * 获取HTTP状态码类别
     */
    private String getStatusClass(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return "2xx";
        } else if (statusCode >= 300 && statusCode < 400) {
            return "3xx";
        } else if (statusCode >= 400 && statusCode < 500) {
            return "4xx";
        } else if (statusCode >= 500) {
            return "5xx";
        } else {
            return "1xx";
        }
    }

    /**
     * 获取请求持续时间范围
     */
    private String getDurationRange(Duration duration) {
        long millis = duration.toMillis();
        if (millis < 1000) {
            return "0-1s";
        } else if (millis < 5000) {
            return "1-5s";
        } else if (millis < 10000) {
            return "5-10s";
        } else {
            return "10s+";
        }
    }
}