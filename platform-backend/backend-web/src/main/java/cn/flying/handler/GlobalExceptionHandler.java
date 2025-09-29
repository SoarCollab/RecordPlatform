package cn.flying.handler;

import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * 统一处理所有控制器抛出的异常
 *
 * @author 王贝强
 * @create 2025-01-12
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final Environment environment;

    public GlobalExceptionHandler(Environment environment) {
        this.environment = environment;
    }

    /**
     * 处理自定义业务异常
     */
    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusinessException(BusinessException e, HttpServletRequest request) {
        log.error("业务异常 - 路径: {}, 错误码: {}, 消息: {}",
                request.getRequestURI(), e.getCode(), e.getMessage());
        return new Result<>(e.getCode(), e.getMessage(), null);
    }

    /**
     * 处理参数校验异常 - @RequestBody 参数校验
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleMethodArgumentNotValidException(MethodArgumentNotValidException e, HttpServletRequest request) {
        Map<String, String> errors = new HashMap<>();
        e.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        String message = "参数校验失败: " + errors.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));

        log.warn("参数校验失败 - 路径: {}, 错误: {}", request.getRequestURI(), errors);
        return Result.error(ResultEnum.PARAM_IS_INVALID, errors);
    }

    /**
     * 处理参数校验异常 - @RequestParam 参数校验
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleConstraintViolationException(ConstraintViolationException e, HttpServletRequest request) {
        Set<ConstraintViolation<?>> violations = e.getConstraintViolations();
        Map<String, String> errors = violations.stream()
                .collect(Collectors.toMap(
                        v -> v.getPropertyPath().toString(),
                        ConstraintViolation::getMessage
                ));

        String message = "参数校验失败: " + errors.values().stream()
                .collect(Collectors.joining(", "));

        log.warn("参数校验失败 - 路径: {}, 错误: {}", request.getRequestURI(), errors);
        return Result.error(ResultEnum.PARAM_IS_INVALID, errors);
    }

    /**
     * 处理参数绑定异常
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleBindException(BindException e, HttpServletRequest request) {
        Map<String, String> errors = new HashMap<>();
        e.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        log.warn("参数绑定失败 - 路径: {}, 错误: {}", request.getRequestURI(), errors);
        return Result.error(ResultEnum.PARAM_IS_INVALID, errors);
    }

    /**
     * 处理缺少请求参数异常
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleMissingServletRequestParameterException(MissingServletRequestParameterException e, HttpServletRequest request) {
        String message = String.format("缺少必需的参数: %s", e.getParameterName());
        log.warn("缺少请求参数 - 路径: {}, 参数名: {}", request.getRequestURI(), e.getParameterName());
        return new Result<>(ResultEnum.PARAM_IS_BLANK.getCode(), message, null);
    }

    /**
     * 处理HTTP请求方法不支持异常
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<?> handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        String message = String.format("不支持的请求方法: %s, 支持的方法: %s",
                e.getMethod(), String.join(", ", e.getSupportedMethods()));
        log.warn("请求方法不支持 - 路径: {}, 方法: {}", request.getRequestURI(), e.getMethod());
        return new Result<>(ResultEnum.INTERFACE_METHOD_NOT_ALLOWED.getCode(), message, null);
    }

    /**
     * 处理HTTP媒体类型不支持异常
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public Result<?> handleHttpMediaTypeNotSupportedException(HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        String message = String.format("不支持的Content-Type: %s", e.getContentType());
        log.warn("媒体类型不支持 - 路径: {}, Content-Type: {}", request.getRequestURI(), e.getContentType());
        return new Result<>(ResultEnum.PARAM_TYPE_ERROR.getCode(), message, null);
    }

    /**
     * 处理404异常
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<?> handleNoHandlerFoundException(NoHandlerFoundException e, HttpServletRequest request) {
        String message = String.format("接口不存在: %s %s", e.getHttpMethod(), e.getRequestURL());
        log.warn("接口不存在 - 方法: {}, 路径: {}", e.getHttpMethod(), e.getRequestURL());
        return new Result<>(ResultEnum.INTERFACE_NOT_FOUND.getCode(), message, null);
    }

    /**
     * 处理文件上传大小超限异常
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public Result<?> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e, HttpServletRequest request) {
        String message = String.format("文件大小超过限制: 最大允许 %d MB", e.getMaxUploadSize() / 1024 / 1024);
        log.warn("文件上传超限 - 路径: {}, 限制: {} bytes", request.getRequestURI(), e.getMaxUploadSize());
        return new Result<>(ResultEnum.FILE_SIZE_EXCEED.getCode(), message, null);
    }

    /**
     * 处理数据库相关异常
     */
    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<?> handleDataAccessException(DataAccessException e, HttpServletRequest request) {
        String message = "数据库操作失败";

        if (e instanceof DuplicateKeyException) {
            message = "数据已存在，请勿重复提交";
            log.warn("数据重复 - 路径: {}, 错误: {}", request.getRequestURI(), e.getMessage());
            return new Result<>(ResultEnum.DATA_ALREADY_EXISTED.getCode(), message, null);
        }

        log.error("数据库异常 - 路径: {}", request.getRequestURI(), e);
        return new Result<>(ResultEnum.DATABASE_ERROR.getCode(), message, null);
    }

    /**
     * 处理SQL异常
     */
    @ExceptionHandler(SQLException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<?> handleSQLException(SQLException e, HttpServletRequest request) {
        log.error("SQL异常 - 路径: {}, SQL状态: {}, 错误码: {}",
                request.getRequestURI(), e.getSQLState(), e.getErrorCode(), e);
        return Result.error(ResultEnum.DATABASE_ERROR, null);
    }

    /**
     * 处理空指针异常
     */
    @ExceptionHandler(NullPointerException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<?> handleNullPointerException(NullPointerException e, HttpServletRequest request) {
        log.error("空指针异常 - 路径: {}", request.getRequestURI(), e);
        return Result.error(ResultEnum.SYSTEM_ERROR, null);
    }

    /**
     * 处理运行时异常
     */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<?> handleRuntimeException(RuntimeException e, HttpServletRequest request) {
        log.error("运行时异常 - 路径: {}, 异常类型: {}",
                request.getRequestURI(), e.getClass().getSimpleName(), e);
        return new Result<>(ResultEnum.SYSTEM_ERROR.getCode(), "系统运行异常: " + e.getMessage(), null);
    }

    /**
     * 处理其他所有异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<?> handleException(Exception e, HttpServletRequest request) {
        log.error("未处理异常 - 路径: {}, 异常类型: {}",
                request.getRequestURI(), e.getClass().getName(), e);

        // 生产环境不要暴露详细错误信息
        String message = isProductionEnvironment() ?
                "系统繁忙，请稍后再试" :
                "系统异常: " + e.getMessage();

        return new Result<>(ResultEnum.SYSTEM_ERROR.getCode(), message, null);
    }

    /**
     * 判断是否为生产环境
     */
    private boolean isProductionEnvironment() {
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles.length == 0) {
            // 如果没有激活的profile，使用默认profile
            activeProfiles = environment.getDefaultProfiles();
        }

        for (String profile : activeProfiles) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }
}