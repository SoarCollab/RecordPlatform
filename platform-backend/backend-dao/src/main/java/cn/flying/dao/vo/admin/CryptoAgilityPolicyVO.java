package cn.flying.dao.vo.admin;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Sanitized effective tenant crypto policy.
 */
public record CryptoAgilityPolicyVO(
        @Schema(description = "Persisted policy version; zero means operator defaults") long policyVersion,
        String contentEncryptionSuite,
        String envelopeSignatureSuite,
        String kemSuite,
        String proofSuite,
        String wrappingProvider,
        int wrappingProviderContract,
        String signedProofSignatureSuite,
        String signedProofSuite,
        String signingProvider,
        int signingProviderContract,
        @Schema(description = "SHA-256 fingerprint of the non-secret policy") String policyFingerprint
) {
}
