package cn.flying.identity.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * 统一处理Sa-Token相关异常和其他业务异常
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理Sa-Token未登录异常
     *
     * @param e 未登录异常
     * @return 错误响应
     */
    @ExceptionHandler(NotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleNotLoginException(NotLoginException e) {
        logger.warn("用户未登录: {}", e.getMessage());

        // 根据不同的未登录类型返回不同的错误信息
        return switch (e.getType()) {
            case NotLoginException.NOT_TOKEN, NotLoginException.INVALID_TOKEN ->
                    Result.error(ResultEnum.PERMISSION_TOKEN_INVALID, null);
            case NotLoginException.TOKEN_TIMEOUT -> Result.error(ResultEnum.PERMISSION_TOKEN_EXPIRED, null);
            case NotLoginException.BE_REPLACED, NotLoginException.KICK_OUT ->
                    Result.error(ResultEnum.PERMISSION_EXPIRE, null);
            default -> Result.error(ResultEnum.USER_NOT_LOGGED_IN, null);
        };
    }

    /**
     * 处理Sa-Token权限不足异常
     *
     * @param e 权限不足异常
     * @return 错误响应
     */
    @ExceptionHandler(NotPermissionException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleNotPermissionException(NotPermissionException e) {
        logger.warn("用户权限不足: 缺少权限 [{}]", e.getPermission());
        return Result.error(ResultEnum.PERMISSION_UNAUTHORIZED, null);
    }

    /**
     * 处理Sa-Token角色不足异常
     *
     * @param e 角色不足异常
     * @return 错误响应
     */
    @ExceptionHandler(NotRoleException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleNotRoleException(NotRoleException e) {
        logger.warn("用户角色不足: 缺少角色 [{}]", e.getRole());
        return Result.error(ResultEnum.PERMISSION_UNAUTHORIZED, null);
    }

    /**
     * 处理参数校验异常（@Valid注解）
     *
     * @param e 方法参数校验异常
     * @return 错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        List<FieldError> fieldErrors = e.getBindingResult().getFieldErrors();
        String errorMessage = fieldErrors.stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));

        logger.warn("参数校验失败: {}", errorMessage);
        return Result.error(ResultEnum.PARAM_IS_INVALID, null);
    }

    /**
     * 处理绑定异常
     *
     * @param e 绑定异常
     * @return 错误响应
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBindException(BindException e) {
        List<FieldError> fieldErrors = e.getBindingResult().getFieldErrors();
        String errorMessage = fieldErrors.stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));

        logger.warn("参数绑定失败: {}", errorMessage);
        return Result.error(ResultEnum.PARAM_TYPE_BIND_ERROR, null);
    }

    /**
     * 处理非法参数异常
     *
     * @param e 非法参数异常
     * @return 错误响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        logger.warn("非法参数: {}", e.getMessage());
        return Result.error(ResultEnum.PARAM_IS_INVALID, null);
    }

    /**
     * 处理运行时异常
     *
     * @param e 运行时异常
     * @return 错误响应
     */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleRuntimeException(RuntimeException e) {
        logger.error("运行时异常: ", e);
        return Result.error(ResultEnum.SYSTEM_ERROR, null);
    }

    /**
     * 处理其他未知异常
     *
     * @param e 异常
     * @return 错误响应
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        logger.error("未知异常: ", e);
        return Result.error(ResultEnum.SYSTEM_ERROR, null);
    }
}