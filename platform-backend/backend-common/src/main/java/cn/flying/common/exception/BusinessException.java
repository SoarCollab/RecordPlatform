package cn.flying.common.exception;

import cn.flying.common.constant.ResultEnum;
import lombok.Getter;

/**
 * 业务异常类
 * 用于处理业务逻辑中的异常情况
 *
 * @author 王贝强
 * @create 2025-01-12
 */
@Getter
public class BusinessException extends RuntimeException {

    /**
     * 错误码
     */
    private final Integer code;

    /**
     * 错误消息
     */
    private final String message;

    /**
     * 错误数据
     */
    private final Object data;

    /**
     * 构造函数 - 使用ResultEnum
     *
     * @param resultEnum 结果枚举
     */
    public BusinessException(ResultEnum resultEnum) {
        super(resultEnum.getMessage());
        this.code = resultEnum.getCode();
        this.message = resultEnum.getMessage();
        this.data = null;
    }

    /**
     * 构造函数 - 使用ResultEnum和自定义消息
     *
     * @param resultEnum 结果枚举
     * @param message    自定义错误消息
     */
    public BusinessException(ResultEnum resultEnum, String message) {
        super(message);
        this.code = resultEnum.getCode();
        this.message = message;
        this.data = null;
    }

    /**
     * 构造函数 - 使用ResultEnum、自定义消息和数据
     *
     * @param resultEnum 结果枚举
     * @param message    自定义错误消息
     * @param data       错误相关数据
     */
    public BusinessException(ResultEnum resultEnum, String message, Object data) {
        super(message);
        this.code = resultEnum.getCode();
        this.message = message;
        this.data = data;
    }

    /**
     * 构造函数 - 自定义错误码和消息
     *
     * @param code    错误码
     * @param message 错误消息
     */
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
        this.message = message;
        this.data = null;
    }

    /**
     * 构造函数 - 自定义错误码、消息和数据
     *
     * @param code    错误码
     * @param message 错误消息
     * @param data    错误相关数据
     */
    public BusinessException(Integer code, String message, Object data) {
        super(message);
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 构造函数 - 包装原始异常
     *
     * @param resultEnum 结果枚举
     * @param cause      原始异常
     */
    public BusinessException(ResultEnum resultEnum, Throwable cause) {
        super(resultEnum.getMessage(), cause);
        this.code = resultEnum.getCode();
        this.message = resultEnum.getMessage();
        this.data = null;
    }

    /**
     * 构造函数 - 包装原始异常，自定义消息
     *
     * @param resultEnum 结果枚举
     * @param message    自定义错误消息
     * @param cause      原始异常
     */
    public BusinessException(ResultEnum resultEnum, String message, Throwable cause) {
        super(message, cause);
        this.code = resultEnum.getCode();
        this.message = message;
        this.data = null;
    }

    /**
     * 静态工厂方法 - 创建业务异常
     *
     * @param resultEnum 结果枚举
     * @return BusinessException实例
     */
    public static BusinessException of(ResultEnum resultEnum) {
        return new BusinessException(resultEnum);
    }

    /**
     * 静态工厂方法 - 创建业务异常（自定义消息）
     *
     * @param resultEnum 结果枚举
     * @param message    自定义消息
     * @return BusinessException实例
     */
    public static BusinessException of(ResultEnum resultEnum, String message) {
        return new BusinessException(resultEnum, message);
    }

    /**
     * 静态工厂方法 - 创建业务异常（自定义消息和数据）
     *
     * @param resultEnum 结果枚举
     * @param message    自定义消息
     * @param data       相关数据
     * @return BusinessException实例
     */
    public static BusinessException of(ResultEnum resultEnum, String message, Object data) {
        return new BusinessException(resultEnum, message, data);
    }

    @Override
    public String toString() {
        return "BusinessException{" +
                "code=" + code +
                ", message='" + message + '\'' +
                ", data=" + data +
                '}';
    }
}