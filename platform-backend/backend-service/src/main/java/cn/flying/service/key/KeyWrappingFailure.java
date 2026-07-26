package cn.flying.service.key;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.exception.RetryableException;

/**
 * 不携带 provider 原始错误文本的稳定失败结果。
 */
public record KeyWrappingFailure(
        KeyWrappingFailureCategory category,
        boolean retryable,
        String providerCode
) {

    /**
     * 将失败结果转换为项目允许的异常边界。
     */
    public RuntimeException toException() {
        if (retryable) {
            return new RetryableException(ResultEnum.SERVICE_UNAVAILABLE, 5);
        }
        return new GeneralException(ResultEnum.ENCRYPTION_ERROR);
    }

    /**
     * 创建不包含外部错误文本的失败结果。
     */
    public static KeyWrappingFailure of(KeyWrappingFailureCategory category, boolean retryable) {
        return new KeyWrappingFailure(category, retryable, null);
    }

    @Override
    public String toString() {
        return "KeyWrappingFailure[category=" + category
                + ", retryable=" + retryable
                + ", providerCode=" + (providerCode == null ? "none" : providerCode) + "]";
    }
}
