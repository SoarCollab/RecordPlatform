package cn.flying.service.proof.signed;

import cn.flying.service.key.CryptoSuiteIds;

/**
 * 可以安全进入证明包和数据库的公开签名 key 元数据。
 */
public record ProofSigningKeyMetadata(
        String providerId,
        Integer providerContractVersion,
        String signatureSuite,
        String proofSuite,
        String algorithm,
        String keyId,
        Integer keyVersion,
        String publicKeySpki,
        String publicKeyFingerprint
) {

    /**
     * Preserves source compatibility for tests and historical adapters that predate explicit routing fields.
     */
    public ProofSigningKeyMetadata(String algorithm,
                                   String keyId,
                                   Integer keyVersion,
                                   String publicKeySpki,
                                   String publicKeyFingerprint) {
        this(CryptoSuiteIds.LOCAL_ED25519_PROVIDER, CryptoSuiteIds.PROVIDER_CONTRACT_V1,
                CryptoSuiteIds.ED25519_JWS_V1, CryptoSuiteIds.SIGNED_PROOF_ZIP_V2,
                algorithm, keyId, keyVersion, publicKeySpki, publicKeyFingerprint);
    }
}
