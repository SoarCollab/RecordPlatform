package cn.flying.service.key;

/**
 * 从持久化信封恢复的 provider-neutral 包封材料。
 */
public record PersistedWrappedDataKey(
        String encryptedDataKey,
        String wrappingIv,
        WrappingKeyReference keyReference,
        Integer logicalKeyVersion
) {

    @Override
    public String toString() {
        return "PersistedWrappedDataKey[encryptedDataKey=REDACTED, wrappingIv=REDACTED, keyReference="
                + keyReference + ", logicalKeyVersion=" + logicalKeyVersion + "]";
    }
}
