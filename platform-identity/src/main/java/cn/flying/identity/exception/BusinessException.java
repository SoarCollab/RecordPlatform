package cn.flying.identity.exception;

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
     * 获取错误码
     * @return 错误码
     */
    public int getCode() {
        return code;
    }
}