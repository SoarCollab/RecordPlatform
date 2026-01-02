package cn.flying.filter.handler;

import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.exception.RetryableException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.apm.toolkit.trace.TraceContext;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理器 - 异常分类处理策略
 *
 * <p>异常层级：
 * <ul>
 *   <li>GeneralException → 业务异常（可预期）→ 400 BAD_REQUEST</li>
 *   <li>RetryableException → 可重试异常（暂时性故障）→ 503 SERVICE_UNAVAILABLE</li>
 *   <li>Exception → 系统异常（不可预期）→ 500 INTERNAL_SERVER_ERROR</li>
 * </ul>
 */
@RestControllerAdvice(basePackages = "cn.flying.controller")
@ResponseBody
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 处理业务异常（可预期的错误，如参数验证失败、业务规则违反）
     */
    @ExceptionHandler(GeneralException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleBusinessException(GeneralException ex) {
        String traceId = currentTraceId();
        log.warn("业务异常: message={}, traceId={}", ex.getMessage(), traceId);

        Map<String, Object> payload = withTrace(ex.getData());
        if (ex.getResultEnum() != null) {
            return Result.error(ex.getResultEnum(), payload);
        }
        return new Result<>(ResultEnum.FAIL.getCode(), ex.getMessage(), payload);
    }

    /**
     * 处理可重试异常（暂时性故障，如服务不可用、网络超时）
     * 返回 503 并建议重试时间
     */
    @ExceptionHandler(RetryableException.class)
    public ResponseEntity<Result<?>> handleRetryableException(RetryableException ex) {
        String traceId = currentTraceId();
        log.warn("可重试异常: message={}, suggestedRetryAfter={}s, traceId={}",
                ex.getMessage(), ex.getSuggestedRetryAfterSeconds(), traceId);

        Map<String, Object> payload = withTrace(ex.getData());
        payload.put("retryable", true);
        payload.put("retryAfterSeconds", ex.getSuggestedRetryAfterSeconds());

        Result<?> result;
        if (ex.getResultEnum() != null) {
            result = Result.error(ex.getResultEnum(), payload);
        } else {
            result = new Result<>(ResultEnum.SERVICE_UNAVAILABLE.getCode(),
                    ex.getMessage() != null ? ex.getMessage() : "服务暂时不可用，请稍后重试",
                    payload);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", String.valueOf(ex.getSuggestedRetryAfterSeconds()));

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .headers(headers)
                .body(result);
    }

    /**
     * 处理 IO 异常（如 SSE 连接断开）
     * SSE 连接断开是正常行为，只记录 debug 日志，不返回响应体
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Void> handleIOException(IOException ex, HttpServletRequest request) {
        String contentType = request.getHeader("Accept");
        String uri = request.getRequestURI();

        // SSE 连接断开是正常行为，只记录 debug 日志
        if (uri.contains("/sse/") || (contentType != null && contentType.contains("text/event-stream"))) {
            log.debug("SSE 连接断开: uri={}, error={}", uri, ex.getMessage());
            return ResponseEntity.ok().build();
        }

        // 其他 IO 异常记录 warn 日志
        log.warn("IO 异常: uri={}, error={}", uri, ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    /**
     * 处理系统异常（不可预期的错误）
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<?> handleSystemException(Exception ex) {
        String traceId = currentTraceId();
        log.error("系统异常: traceId={}", traceId, ex);

        Map<String, Object> payload = withTrace("服务器内部错误，请联系管理员");
        return Result.error(ResultEnum.FAIL, payload);
    }

    private Map<String, Object> withTrace(Object detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String traceId = currentTraceId();
        if (traceId != null) {
            payload.put("traceId", traceId);
        }
        if (detail != null) {
            payload.put("detail", detail);
        }
        return payload;
    }

    private String currentTraceId() {
        try {
            String swTraceId = TraceContext.traceId();
            if (!"N/A".equalsIgnoreCase(swTraceId) && !swTraceId.isEmpty()) {
                return swTraceId;
            }
        } catch (Throwable ignored) {
            // SkyWalking agent/toolkit not present
        }
        return MDC.get("traceId");
    }
}
