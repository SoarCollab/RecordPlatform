package cn.flying.service.key;

/**
 * Provider-neutral 的包封目标完整身份。
 */
public record WrappingKeyReference(
        String providerId,
        int providerContractVersion,
        String keyId,
        String providerKeyVersion,
        String wrappingAlgorithm,
        String contextSchema
) {

    @Override
    public String toString() {
        return "WrappingKeyReference[providerId=" + providerId
                + ", providerContractVersion=" + providerContractVersion
                + ", keyId=REDACTED, providerKeyVersion=" + providerKeyVersion
                + ", wrappingAlgorithm=" + wrappingAlgorithm
                + ", contextSchema=" + contextSchema + "]";
    }
}
