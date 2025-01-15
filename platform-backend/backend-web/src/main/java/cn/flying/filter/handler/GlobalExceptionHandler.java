package cn.flying.filter.handler;

import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * @program: RecordPlatform
 * @description:
 * @author: 王贝强
 * @create: 2025-01-15 16:25
 */
@RestControllerAdvice(basePackages = "cn.flying.controller")
@ResponseBody
@Slf4j
public class GlobalExceptionHandler {


    /**
     *
     * 通用业务异常处理
     */
    @ExceptionHandler(GeneralException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result generalBusinessExceptionHandler(GeneralException ex) {
        if(ex.getData() != null) {
            return Result.error(ex.getResultEnum(),ex.getData());
        } else if(ex.getResultEnum() != null){
            return Result.error(ex.getResultEnum());
        }
        return Result.error(ex.getMessage());
    }

    /**
     *
     * 通用异常处理(用于处理不可预知的异常)
     */
    @ExceptionHandler(Exception.class)
    public Result<String> exceptionHandler(Exception ex) {
        log.error(ex.getMessage());
        return Result.error(ResultEnum.FAIL);
    }
}
