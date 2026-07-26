package cn.flying.service.key;

/**
 * Provider-neutral 的已包封文件数据密钥结果。
 */
public record WrappedDataKey(
        String encryptedDataKey,
        String wrappingIv,
        WrappingKeyReference keyReference,
        Integer logicalKeyVersion
) {

    /**
     * 返回持久化 provider id。
     */
    public String kmsProvider() {
        return keyReference.providerId();
    }

    /**
     * 返回持久化 provider key id。
     */
    public String kmsKeyId() {
        return keyReference.keyId();
    }

    /**
     * 返回兼容既有调用方的逻辑 key version。
     */
    public Integer keyVersion() {
        return logicalKeyVersion;
    }

    /**
     * 返回 provider 包封算法。
     */
    public String wrappingAlgorithm() {
        return keyReference.wrappingAlgorithm();
    }

    @Override
    public String toString() {
        return "WrappedDataKey[encryptedDataKey=REDACTED, wrappingIv=REDACTED, keyReference="
                + keyReference + ", logicalKeyVersion=" + logicalKeyVersion + "]";
    }
}
