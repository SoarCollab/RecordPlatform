package cn.flying.service.key;

/**
 * Wrapped file data-key payload returned by the local wrapping service.
 */
public record WrappedDataKey(
        String encryptedDataKey,
        String wrappingIv,
        String kmsProvider,
        String kmsKeyId,
        Integer keyVersion,
        String wrappingAlgorithm
) {
}
