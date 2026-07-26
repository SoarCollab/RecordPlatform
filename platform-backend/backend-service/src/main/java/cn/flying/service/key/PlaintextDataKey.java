package cn.flying.service.key;

import org.springframework.util.StringUtils;

/**
 * 避免 plaintext data key 被默认 toString 输出的短生命周期持有对象。
 */
public final class PlaintextDataKey {

    private final String value;

    private PlaintextDataKey(String value) {
        this.value = value;
    }

    /**
     * 创建非空明文数据密钥持有对象。
     */
    public static PlaintextDataKey of(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("plaintext data key must not be blank");
        }
        return new PlaintextDataKey(value);
    }

    /**
     * 仅在密码操作边界内读取明文值。
     */
    String reveal() {
        return value;
    }

    @Override
    public String toString() {
        return "PlaintextDataKey[REDACTED]";
    }
}
