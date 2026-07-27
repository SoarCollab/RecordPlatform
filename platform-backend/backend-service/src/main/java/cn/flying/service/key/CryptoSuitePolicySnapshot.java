package cn.flying.service.key;

/**
 * Immutable effective tenant policy used for one runtime cryptographic decision.
 *
 * @param tenantId tenant boundary, or null for validated operator defaults
 * @param policyVersion persisted tenant policy version, or zero for defaults
 * @param contentEncryptionSuite suite selected for new envelope metadata
 * @param envelopeSignatureSuite signature suite selected for envelope/proof metadata
 * @param kemSuite key-establishment suite selected for envelope/proof metadata
 * @param proofSuite proof suite selected for envelope/proof metadata
 * @param wrappingProvider exact wrapping provider ID
 * @param wrappingProviderContract exact wrapping provider contract
 * @param signedProofSignatureSuite signed proof signature suite
 * @param signedProofSuite signed proof format suite
 * @param signingProvider exact signing provider ID
 * @param signingProviderContract exact signing provider contract
 */
public record CryptoSuitePolicySnapshot(
        Long tenantId,
        long policyVersion,
        String contentEncryptionSuite,
        String envelopeSignatureSuite,
        String kemSuite,
        String proofSuite,
        String wrappingProvider,
        int wrappingProviderContract,
        String signedProofSignatureSuite,
        String signedProofSuite,
        String signingProvider,
        int signingProviderContract
) {
}
