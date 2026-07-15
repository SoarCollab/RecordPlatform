package cn.flying.verifier.model;

/** Trusted public verification key resolved by a local trust store or issuer endpoint. */
public record PublicSigningKey(
        String keyId,
        Integer keyVersion,
        String algorithm,
        String publicKeySpki,
        String publicKeyFingerprint,
        String source
) {
}
