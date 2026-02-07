package cn.flying.filter.handler;

import cn.flying.common.constant.ErrorPayload;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.util.Const;
import cn.flying.common.util.ErrorPayloadFactory;
import jakarta.validation.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 用于接口参数校验处理的控制器
 */
@Slf4j
@RestControllerAdvice
public class ValidationController {

    /**
     * 与SpringBoot保持一致，校验不通过打印警告信息，而不是直接抛出异常
     * @param exception 验证异常
     * @return 校验结果
     */
    @ExceptionHandler(ValidationException.class)
    public Result<ErrorPayload> validateError(ValidationException exception) {
        log.warn("Resolved [{}: {}]", exception.getClass().getName(), exception.getMessage());
        ErrorPayload payload = ErrorPayloadFactory.of(MDC.get(Const.TRACE_ID), exception.getMessage());
        return Result.error(ResultEnum.PARAM_IS_INVALID, payload);
    }
}
