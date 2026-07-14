package cn.flying.service.proof.signed;

/**
 * 可以安全进入证明包和数据库的公开签名 key 元数据。
 */
public record ProofSigningKeyMetadata(
        String algorithm,
        String keyId,
        Integer keyVersion,
        String publicKeySpki,
        String publicKeyFingerprint
) {
}
