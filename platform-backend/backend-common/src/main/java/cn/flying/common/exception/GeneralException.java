package cn.flying.common.exception;

import cn.flying.common.constant.ResultEnum;
import lombok.Getter;
import lombok.Setter;

/**
 * @program: RecordPlatform
 * @description: 通用异常
 * @author: 王贝强
 * @create: 2025-01-15 16:00
 */
@Getter
@Setter
public class GeneralException extends RuntimeException {
    private int code = 0;
    private ResultEnum resultEnum;
    private String message;
    private Object data;

    public GeneralException(String message) {
        this.message = message;
    }
    public GeneralException(ResultEnum resultEnum) {
        this.resultEnum = resultEnum;
    }
    public GeneralException(ResultEnum resultEnum, Object data) {
        this.resultEnum = resultEnum;
        this.data = data;
    }

}