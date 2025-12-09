package cn.flying.service.encryption;

/**
 * 加密算法枚举
 *
 * <p>定义支持的加密算法类型。</p>
 */
public enum EncryptionAlgorithm {

    /**
     * AES-256-GCM - 推荐用于有 AES-NI 硬件加速的服务器
     */
    AES_GCM("aes-gcm", "AES-256-GCM"),

    /**
     * ChaCha20-Poly1305 - 推荐用于无硬件加速或混合环境
     */
    CHACHA20("chacha20", "ChaCha20-Poly1305"),

    /**
     * 自动检测 - 根据硬件环境自动选择最优算法
     */
    AUTO("auto", "Auto-detect");

    private final String configValue;
    private final String displayName;

    EncryptionAlgorithm(String configValue, String displayName) {
        this.configValue = configValue;
        this.displayName = displayName;
    }

    public String getConfigValue() {
        return configValue;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 从配置值解析枚举
     *
     * @param value 配置值（如 "aes-gcm", "chacha20", "auto"）
     * @return 对应的枚举，默认返回 AUTO
     */
    public static EncryptionAlgorithm fromConfigValue(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        String normalized = value.toLowerCase().trim();
        for (EncryptionAlgorithm alg : values()) {
            if (alg.configValue.equals(normalized)) {
                return alg;
            }
        }
        // 兼容旧配置
        if (normalized.contains("aes") || normalized.contains("gcm")) {
            return AES_GCM;
        }
        if (normalized.contains("chacha") || normalized.contains("poly")) {
            return CHACHA20;
        }
        return AUTO;
    }
}
