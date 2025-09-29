package cn.flying.identity.exception;

import lombok.Getter;

/**
 * 速率限制异常
 * 当请求频率超过限制时抛出
 *
 * @author 王贝强
 * @since 2025-01-16
 */
@Getter
public class RateLimitException extends RuntimeException {

    private final Integer code;

    public RateLimitException(String message) {
        super(message);
        this.code = 70005; // PERMISSION_LIMIT
    }

    public RateLimitException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public RateLimitException(String message, Throwable cause) {
        super(message, cause);
        this.code = 70005;
    }

}
