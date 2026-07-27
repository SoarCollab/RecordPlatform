package cn.flying.service.key;

/**
 * Validated administrator intent for one tenant crypto policy update.
 */
public record TenantCryptoPolicyCommand(
        Long expectedVersion,
        String contentEncryptionSuite,
        String envelopeSignatureSuite,
        String kemSuite,
        String proofSuite,
        String wrappingProvider,
        Integer wrappingProviderContract,
        String signedProofSignatureSuite,
        String signedProofSuite,
        String signingProvider,
        Integer signingProviderContract
) {
}
