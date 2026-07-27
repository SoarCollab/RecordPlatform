package cn.flying.service.key;

/**
 * Runtime lifecycle states for a registered cryptographic suite.
 */
public enum CryptoSuiteStatus {
    ACTIVE,
    DEPRECATED,
    DISABLED,
    UNSUPPORTED,
    EXPERIMENTAL
}
