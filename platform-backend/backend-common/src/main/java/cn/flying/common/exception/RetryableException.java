package cn.flying.common.exception;

import cn.flying.common.constant.ResultEnum;
import lombok.Getter;

/**
 * 可重试异常 - 用于暂时性故障（如网络超时、服务不可用等）
 * 客户端可以安全地重试该操作。
 */
@Getter
public class RetryableException extends RuntimeException {

    private final ResultEnum resultEnum;
    private final Object data;
    private final int suggestedRetryAfterSeconds;

    public RetryableException(String message) {
        super(message);
        this.resultEnum = null;
        this.data = null;
        this.suggestedRetryAfterSeconds = 5;
    }

    public RetryableException(ResultEnum resultEnum) {
        super(resultEnum.getMessage());
        this.resultEnum = resultEnum;
        this.data = null;
        this.suggestedRetryAfterSeconds = 5;
    }

    public RetryableException(ResultEnum resultEnum, Object data) {
        super(resultEnum.getMessage());
        this.resultEnum = resultEnum;
        this.data = data;
        this.suggestedRetryAfterSeconds = 5;
    }

    public RetryableException(String message, int suggestedRetryAfterSeconds) {
        super(message);
        this.resultEnum = null;
        this.data = null;
        this.suggestedRetryAfterSeconds = suggestedRetryAfterSeconds;
    }

    public RetryableException(ResultEnum resultEnum, int suggestedRetryAfterSeconds) {
        super(resultEnum.getMessage());
        this.resultEnum = resultEnum;
        this.data = null;
        this.suggestedRetryAfterSeconds = suggestedRetryAfterSeconds;
    }

    public RetryableException(String message, Throwable cause) {
        super(message, cause);
        this.resultEnum = null;
        this.data = null;
        this.suggestedRetryAfterSeconds = 5;
    }

    public RetryableException(ResultEnum resultEnum, Throwable cause) {
        super(resultEnum.getMessage(), cause);
        this.resultEnum = resultEnum;
        this.data = null;
        this.suggestedRetryAfterSeconds = 5;
    }
}
