package cn.flying.filter.handler;

import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.apm.toolkit.trace.TraceContext;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global exception handler with traceId correlation for debugging.
 */
@RestControllerAdvice(basePackages = "cn.flying.controller")
@ResponseBody
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(GeneralException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> generalBusinessExceptionHandler(GeneralException ex) {
        Map<String, Object> payload = withTrace(ex.getData());
        if (ex.getResultEnum() != null) {
            return Result.error(ex.getResultEnum(), payload);
        }
        return new Result<>(ResultEnum.FAIL.getCode(), ex.getMessage(), payload);
    }

    @ExceptionHandler(Exception.class)
    public Result<?> exceptionHandler(Exception ex) {
        String traceId = currentTraceId();
        log.error("Unhandled exception, traceId={}", traceId, ex);
        return Result.error(ResultEnum.FAIL, withTrace(ex.getMessage()));
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
        return payload.isEmpty() ? null : payload;
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
