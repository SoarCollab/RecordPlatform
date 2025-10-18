package cn.flying.monitor.common.exception;

import cn.flying.monitor.common.dto.BaseResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<BaseResponse<Void>> handleBadCredentials(BadCredentialsException e) {
        String traceId = UUID.randomUUID().toString();
        log.warn("Authentication failed: {}, traceId: {}", e.getMessage(), traceId);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(BaseResponse.error("认证失败", traceId));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<BaseResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        String traceId = UUID.randomUUID().toString();
        log.warn("Access denied: {}, traceId: {}", e.getMessage(), traceId);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(BaseResponse.error("访问被拒绝", traceId));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<BaseResponse<Void>> handleValidation(Exception e) {
        String traceId = UUID.randomUUID().toString();
        log.warn("Validation error: {}, traceId: {}", e.getMessage(), traceId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(BaseResponse.error("参数验证失败", traceId));
    }

    @ExceptionHandler(MonitorException.class)
    public ResponseEntity<BaseResponse<Void>> handleMonitorException(MonitorException e) {
        String traceId = UUID.randomUUID().toString();
        log.error("Monitor exception: {}, traceId: {}", e.getMessage(), traceId, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(BaseResponse.error(e.getMessage(), traceId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<BaseResponse<Void>> handleGeneral(Exception e) {
        String traceId = UUID.randomUUID().toString();
        log.error("Unexpected error: {}, traceId: {}", e.getMessage(), traceId, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(BaseResponse.error("系统内部错误", traceId));
    }
}