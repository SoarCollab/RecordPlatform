package cn.flying.service.key;

/**
 * Closed categories used to prevent one cryptographic suite from being interpreted in another role.
 */
public enum CryptoSuiteType {
    CONTENT_ENCRYPTION,
    KEY_WRAPPING,
    SIGNATURE,
    KEM,
    PROOF
}
