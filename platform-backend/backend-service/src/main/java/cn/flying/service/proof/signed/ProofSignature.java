package cn.flying.service.proof.signed;

/**
 * Compact JWS 与实际用于签名的公开 key 元数据。
 */
public record ProofSignature(
        String compactJws,
        ProofSigningKeyMetadata key
) {
}
