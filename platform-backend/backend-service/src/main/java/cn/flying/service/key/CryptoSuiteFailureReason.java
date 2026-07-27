package cn.flying.service.key;

/**
 * Stable low-cardinality reasons emitted by runtime suite decisions.
 */
public enum CryptoSuiteFailureReason {
    NONE,
    UNKNOWN_SUITE,
    TYPE_MISMATCH,
    PROVIDER_MISMATCH,
    UNSUPPORTED,
    EXPERIMENTAL_NOT_ALLOWED,
    DEPRECATED_FOR_WRITE,
    DISABLED_FOR_READ,
    INVALID_LIFECYCLE,
    CAPABILITY_MISMATCH,
    DOWNGRADE_BLOCKED,
    REENCRYPT_REQUIRED,
    POLICY_VERSION_CONFLICT
}
