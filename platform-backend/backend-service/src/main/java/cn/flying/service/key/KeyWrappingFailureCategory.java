package cn.flying.service.key;

/**
 * 跨 provider 稳定的密钥包封失败分类。
 */
public enum KeyWrappingFailureCategory {
    NONE,
    CONFIGURATION,
    TIMEOUT,
    THROTTLED,
    UNAVAILABLE,
    PERMISSION_DENIED,
    KEY_DISABLED,
    KEY_NOT_FOUND,
    INVALID_CIPHERTEXT,
    INVALID_REQUEST,
    INVALID_RESPONSE,
    UNSUPPORTED,
    INTERNAL
}
