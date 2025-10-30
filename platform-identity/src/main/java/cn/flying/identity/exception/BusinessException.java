package cn.flying.identity.exception;

import cn.flying.platformapi.constant.ResultEnum;

/**
 * 业务异常类
 * 用于在业务逻辑中抛出自定义的异常
 *
 * @author 王贝强
 * @since 2025-10-11
 */
public class BusinessException extends RuntimeException {

    /** 错误码 */
    private final int code;

    /**
     * 构造函数
     * @param code 错误码
     * @param message 错误消息
     */
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 使用统一的业务枚举构造异常
     * @param resultEnum 业务结果枚举
     */
    public BusinessException(ResultEnum resultEnum) {
        super(resultEnum.getMessage());
        this.code = resultEnum.getCode();
    }

    /**
     * 使用业务枚举并自定义消息构造异常
     * @param resultEnum 业务结果枚举
     * @param message 自定义错误信息
     */
    public BusinessException(ResultEnum resultEnum, String message) {
        super(message);
        this.code = resultEnum.getCode();
    }

    /**
     * 构造函数
     * @param code 错误码
     * @param message 错误消息
     * @param cause 原因
     */
    public BusinessException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * 使用业务枚举并指定根因构造异常
     * @param resultEnum 业务结果枚举
     * @param cause 异常根因
     */
    public BusinessException(ResultEnum resultEnum, Throwable cause) {
        super(resultEnum.getMessage(), cause);
        this.code = resultEnum.getCode();
    }

    /**
     * 获取错误码
     * @return 错误码
     */
    public int getCode() {
        return code;
    }
}
