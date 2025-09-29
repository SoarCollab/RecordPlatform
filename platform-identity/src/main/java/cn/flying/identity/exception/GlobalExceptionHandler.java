package cn.flying.identity.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.flying.identity.vo.RestResponse;
import cn.flying.platformapi.constant.ResultEnum;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * 统一处理所有异常并返回符合RESTful规范的响应
 * 
 * @author 王贝强
 * @since 2025-01-16
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Value("${spring.profiles.active:prod}")
    private String activeProfile;

    /**
     * 处理404 - 资源未找到
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<RestResponse<Void>> handleNoHandlerFoundException(
            NoHandlerFoundException e, HttpServletRequest request) {
        logger.warn("404 - 资源未找到: {}", request.getRequestURI());
        
        RestResponse<Void> response = RestResponse.notFound(
            ResultEnum.RESULT_DATA_NONE.getCode(), 
            "请求的资源不存在: " + request.getRequestURI()
        );
        response.withPath(request.getRequestURI());
        
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    /**
     * 处理401 - 未认证（Sa-Token未登录异常）
     */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<RestResponse<Void>> handleNotLoginException(
            NotLoginException e, HttpServletRequest request) {
        logger.warn("401 - 用户未认证: {}", e.getMessage());

        RestResponse<Void> response = switch (e.getType()) {
            case NotLoginException.NOT_TOKEN, NotLoginException.INVALID_TOKEN -> RestResponse.unauthorized(
                    ResultEnum.PERMISSION_TOKEN_INVALID.getCode(),
                    "无效的认证令牌，请重新登录"
            );
            case NotLoginException.TOKEN_TIMEOUT -> RestResponse.unauthorized(
                    ResultEnum.PERMISSION_TOKEN_EXPIRED.getCode(),
                    "认证令牌已过期，请重新登录"
            );
            case NotLoginException.BE_REPLACED, NotLoginException.KICK_OUT -> RestResponse.unauthorized(
                    ResultEnum.PERMISSION_EXPIRE.getCode(),
                    "账号已在其他地方登录，请重新登录"
            );
            default -> RestResponse.unauthorized(
                    ResultEnum.USER_NOT_LOGGED_IN.getCode(),
                    "请先登录系统"
            );
        };

        response.withPath(request.getRequestURI());
        response.withTraceId(MDC.get("traceId"));
        
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    /**
     * 处理403 - 无权限
     */
    @ExceptionHandler({NotPermissionException.class, NotRoleException.class})
    public ResponseEntity<RestResponse<Void>> handlePermissionException(
            Exception e, HttpServletRequest request) {
        
        String message;
        if (e instanceof NotPermissionException npe) {
            logger.warn("403 - 权限不足: 缺少权限 [{}]", npe.getPermission());
            message = "权限不足，缺少权限: " + npe.getPermission();
        } else {
            NotRoleException nre = (NotRoleException) e;
            logger.warn("403 - 角色不足: 缺少角色 [{}]", nre.getRole());
            message = "权限不足，缺少角色: " + nre.getRole();
        }

        RestResponse<Void> response = RestResponse.forbidden(
            ResultEnum.PERMISSION_UNAUTHORIZED.getCode(),
            message
        );
        response.withPath(request.getRequestURI());
        response.withTraceId(MDC.get("traceId"));

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    /**
     * 处理400 - 参数校验失败
     */
    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        BindException.class,
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class
    })
    public ResponseEntity<RestResponse<Void>> handleValidationException(
            Exception e, HttpServletRequest request) {
        
        String message;
        int code = ResultEnum.PARAM_IS_INVALID.getCode();

        switch (e) {
            case MethodArgumentNotValidException methodArgumentNotValidException -> {
                List<FieldError> fieldErrors = methodArgumentNotValidException
                        .getBindingResult().getFieldErrors();
                message = fieldErrors.stream()
                        .map(error -> error.getField() + ": " + error.getDefaultMessage())
                        .collect(Collectors.joining(", "));
            }
            case BindException bindException -> {
                List<FieldError> fieldErrors = bindException
                        .getBindingResult().getFieldErrors();
                message = fieldErrors.stream()
                        .map(error -> error.getField() + ": " + error.getDefaultMessage())
                        .collect(Collectors.joining(", "));
                code = ResultEnum.PARAM_TYPE_BIND_ERROR.getCode();
            }
            case MissingServletRequestParameterException ex -> {
                message = "缺少必需参数: " + ex.getParameterName();
                code = ResultEnum.PARAM_IS_BLANK.getCode();
            }
            case MethodArgumentTypeMismatchException ex -> message = "参数类型错误: " + ex.getName();
            case null, default -> message = "请求参数格式错误";
        }

        logger.warn("400 - 参数错误: {}", message);
        
        RestResponse<Void> response = RestResponse.badRequest(code, message);
        response.withPath(request.getRequestURI());
        response.withTraceId(MDC.get("traceId"));

        if (!"prod".equals(activeProfile)) {
            if (e != null) {
                response.withError(e.getMessage());
            }
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * 处理405 - 请求方法不支持
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<RestResponse<Void>> handleMethodNotSupportedException(
            HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        
        logger.warn("405 - 请求方法不支持: {} {}", e.getMethod(), request.getRequestURI());
        
        String message = String.format("不支持的请求方法: %s, 支持的方法: %s",
            e.getMethod(), 
            String.join(", ", e.getSupportedMethods() != null ? e.getSupportedMethods() : new String[0])
        );
        
        RestResponse<Void> response = RestResponse.methodNotAllowed(
            ResultEnum.PARAM_IS_INVALID.getCode(),
            message
        );
        response.withPath(request.getRequestURI());
        response.withTraceId(MDC.get("traceId"));

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(response);
    }

    /**
     * 处理413 - 上传文件过大
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<RestResponse<Void>> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException e, HttpServletRequest request) {
        
        logger.warn("413 - 上传文件过大: {}", e.getMessage());
        
        RestResponse<Void> response = RestResponse.unprocessableEntity(
            ResultEnum.FILE_MAX_SIZE_OVERFLOW.getCode(),
            "上传文件大小超过限制"
        );
        response.withPath(request.getRequestURI());
        response.withTraceId(MDC.get("traceId"));

        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(response);
    }

    /**
     * 处理415 - 不支持的媒体类型
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<RestResponse<Void>> handleMediaTypeNotSupportedException(
            HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        
        logger.warn("415 - 不支持的媒体类型: {}", e.getMessage());
        
        RestResponse<Void> response = RestResponse.unprocessableEntity(
            ResultEnum.FILE_ACCEPT_NOT_SUPPORT.getCode(),
            "不支持的内容类型: " + e.getContentType()
        );
        response.withPath(request.getRequestURI());
        response.withTraceId(MDC.get("traceId"));

        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(response);
    }

    /**
     * 处理429 - 请求过于频繁
     */
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<RestResponse<Void>> handleRateLimitException(
            RateLimitException e, HttpServletRequest request) {
        
        logger.warn("429 - 请求过于频繁: {}", e.getMessage());
        
        RestResponse<Void> response = RestResponse.tooManyRequests(
            ResultEnum.PERMISSION_LIMIT.getCode(),
            "请求过于频繁，请稍后再试"
        );
        response.withPath(request.getRequestURI());
        response.withTraceId(MDC.get("traceId"));

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(response);
    }

    /**
     * 处理业务异常
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<RestResponse<Void>> handleBusinessException(
            BusinessException e, HttpServletRequest request) {
        
        logger.warn("业务异常: {}", e.getMessage());
        
        // 根据业务异常码映射到合适的HTTP状态码
        HttpStatus status = mapBusinessCodeToHttpStatus(e.getCode());
        
        RestResponse<Void> response = new RestResponse<>(
            status,
            e.getCode(),
            e.getMessage(),
            null
        );
        response.withPath(request.getRequestURI());
        response.withTraceId(MDC.get("traceId"));

        return ResponseEntity.status(status).body(response);
    }

    /**
     * 处理501 - 功能未实现
     */
    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<RestResponse<Void>> handleNotImplementedException(
            UnsupportedOperationException e, HttpServletRequest request) {
        
        logger.warn("501 - 功能未实现: {}", e.getMessage());
        
        RestResponse<Void> response = RestResponse.notImplemented(
            ResultEnum.SYSTEM_ERROR.getCode(),
            "该功能暂未实现"
        );
        response.withPath(request.getRequestURI());
        response.withTraceId(MDC.get("traceId"));

        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(response);
    }

    /**
     * 处理503 - 服务不可用
     */
    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<RestResponse<Void>> handleServiceUnavailableException(
            ServiceUnavailableException e, HttpServletRequest request) {
        
        logger.warn("503 - 服务不可用: {}", e.getMessage());
        
        RestResponse<Void> response = RestResponse.serviceUnavailable(
            ResultEnum.SERVICE_UNAVAILABLE.getCode(),
            "服务暂时不可用，请稍后再试"
        );
        response.withPath(request.getRequestURI());
        response.withTraceId(MDC.get("traceId"));

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }

    /**
     * 处理数据库异常
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<RestResponse<Void>> handleDataAccessException(
            DataAccessException e, HttpServletRequest request) {
        
        logger.error("数据库访问异常: ", e);
        
        RestResponse<Void> response = RestResponse.internalServerError(
            ResultEnum.SYSTEM_ERROR.getCode(),
            "数据库访问异常"
        );
        response.withPath(request.getRequestURI());
        response.withTraceId(MDC.get("traceId"));

        if (!"prod".equals(activeProfile)) {
            response.withError(e.getMostSpecificCause().getMessage());
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * 处理500 - 其他运行时异常
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<RestResponse<Void>> handleRuntimeException(
            RuntimeException e, HttpServletRequest request) {
        
        logger.error("500 - 运行时异常: ", e);
        
        RestResponse<Void> response = RestResponse.internalServerError(
            ResultEnum.SYSTEM_ERROR.getCode(),
            "系统内部错误"
        );
        response.withPath(request.getRequestURI());
        response.withTraceId(MDC.get("traceId"));

        if (!"prod".equals(activeProfile)) {
            response.withError(e.getMessage());
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * 处理所有未知异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<RestResponse<Void>> handleException(
            Exception e, HttpServletRequest request) {
        
        logger.error("500 - 未知异常: ", e);
        
        RestResponse<Void> response = RestResponse.internalServerError(
            ResultEnum.SYSTEM_ERROR.getCode(),
            "系统繁忙，请稍后再试"
        );
        response.withPath(request.getRequestURI());
        response.withTraceId(MDC.get("traceId"));

        if (!"prod".equals(activeProfile)) {
            response.withError(e.getMessage());
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * 业务错误码到HTTP状态码的映射
     */
    private HttpStatus mapBusinessCodeToHttpStatus(Integer code) {
        if (code == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }

        // 参数错误
        if (code >= 10001 && code <= 19999) {
            return HttpStatus.BAD_REQUEST;
        }

        // 用户相关错误
        if (code >= 20001 && code <= 29999) {
            if (code == 20001 || code == 20002) {
                return HttpStatus.UNAUTHORIZED;
            } else if (code == 20003) {
                return HttpStatus.FORBIDDEN;
            } else if (code == 20004) {
                return HttpStatus.NOT_FOUND;
            } else if (code == 20005) {
                return HttpStatus.CONFLICT;
            }
            return HttpStatus.BAD_REQUEST;
        }

        // 权限错误
        if (code >= 70001 && code <= 79999) {
            if (code == 70002 || code == 70005) {
                return HttpStatus.FORBIDDEN;
            }
            return HttpStatus.UNAUTHORIZED;
        }

        // 默认500
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
