package cn.flying.filter.handler;

import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.exception.RetryableException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.apm.toolkit.trace.TraceContext;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常处理器 - 异常分类处理策略
 *
 * <p>异常层级：
 * <ul>
 *   <li>GeneralException → 业务异常（可预期）→ 200 OK（保持统一 Result.code 表达错误）</li>
 *   <li>RetryableException → 可重试异常（暂时性故障）→ 503 SERVICE_UNAVAILABLE</li>
 *   <li>Exception → 系统异常（不可预期）→ 500 INTERNAL_SERVER_ERROR</li>
 * </ul>
 */
@RestControllerAdvice(basePackages = "cn.flying.controller")
@ResponseBody
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 处理缺失必填请求参数（如 @RequestParam 缺失）。
     *
     * @param ex 缺失参数异常
     * @return 400 BAD_REQUEST 的统一错误响应
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleMissingServletRequestParameterException(MissingServletRequestParameterException ex) {
        String traceId = currentTraceId();
        String detail = "缺少参数: " + ex.getParameterName();
        log.warn("参数缺失(MissingServletRequestParameterException): detail={}, traceId={}", detail, traceId);
        return Result.error(ResultEnum.PARAM_NOT_COMPLETE, withTrace(detail));
    }

    /**
     * 处理 @Valid @RequestBody 校验失败（JSON Body 参数校验）。
     *
     * @param ex 校验异常
     * @return 400 BAD_REQUEST 的统一错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex) {
        String traceId = currentTraceId();
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));

        log.warn("参数校验失败(MethodArgumentNotValidException): detail={}, traceId={}", detail, traceId);
        return Result.error(ResultEnum.PARAM_IS_INVALID, withTrace(detail.isEmpty() ? "参数无效" : detail));
    }

    /**
     * 处理权限不足（如方法级鉴权抛出的 AccessDeniedException）。
     *
     * @param ex 权限异常
     * @return 403 FORBIDDEN 的统一错误响应
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<?> handleAccessDeniedException(AccessDeniedException ex) {
        String traceId = currentTraceId();
        String detail = ex.getMessage() != null ? ex.getMessage() : "权限不足";
        log.warn("权限不足(AccessDeniedException): detail={}, traceId={}", detail, traceId);
        return Result.error(ResultEnum.PERMISSION_UNAUTHORIZED, withTrace(detail));
    }

    /**
     * 处理表单/Query 参数绑定校验失败（如 @Valid + @ModelAttribute）。
     *
     * @param ex 绑定异常
     * @return 400 BAD_REQUEST 的统一错误响应
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleBindException(BindException ex) {
        String traceId = currentTraceId();
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));

        log.warn("参数绑定失败(BindException): detail={}, traceId={}", detail, traceId);
        return Result.error(ResultEnum.PARAM_IS_INVALID, withTrace(detail.isEmpty() ? "参数无效" : detail));
    }

    /**
     * 处理方法参数级校验失败（如 @RequestParam/@PathVariable 上的 @NotBlank/@Size 等）。
     *
     * @param ex 约束违反异常
     * @return 400 BAD_REQUEST 的统一错误响应
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleConstraintViolationException(ConstraintViolationException ex) {
        String traceId = currentTraceId();
        String detail = ex.getConstraintViolations().stream()
                .map(this::formatConstraintViolation)
                .collect(Collectors.joining("; "));

        log.warn("参数校验失败(ConstraintViolationException): detail={}, traceId={}", detail, traceId);
        return Result.error(ResultEnum.PARAM_IS_INVALID, withTrace(detail.isEmpty() ? "参数无效" : detail));
    }

    /**
     * 处理业务异常（可预期的错误，如参数验证失败、业务规则违反）
     */
    @ExceptionHandler(GeneralException.class)
    public ResponseEntity<Result<?>> handleBusinessException(GeneralException ex) {
        String traceId = currentTraceId();
        log.warn("业务异常: message={}, traceId={}", ex.getMessage(), traceId);

        Map<String, Object> payload = withTrace(ex.getData() != null ? ex.getData() : ex.getMessage());

        Result<?> result;
        if (ex.getResultEnum() != null) {
            result = Result.error(ex.getResultEnum(), payload);
        } else {
            result = new Result<>(ResultEnum.PARAM_IS_INVALID.getCode(), ex.getMessage(), payload);
        }

        // 约定：业务异常保持 HTTP 200，通过 Result.code 表达具体错误
        return ResponseEntity.ok(result);
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

    /**
     * 将 ConstraintViolation 格式化为可读文本。
     *
     * @param violation 单个约束违反
     * @return 格式化后的文本（包含路径与提示）
     */
    private String formatConstraintViolation(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath() != null ? violation.getPropertyPath().toString() : "";
        String message = violation.getMessage() != null ? violation.getMessage() : "";
        if (path.isEmpty()) {
            return message.isEmpty() ? "参数无效" : message;
        }
        return message.isEmpty() ? path : (path + ": " + message);
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
