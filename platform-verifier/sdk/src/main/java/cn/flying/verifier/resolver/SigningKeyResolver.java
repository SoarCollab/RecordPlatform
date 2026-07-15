package cn.flying.verifier.resolver;

import cn.flying.verifier.model.PublicSigningKey;

/** Resolves a trusted historical signing key for one manifest identity. */
@FunctionalInterface
public interface SigningKeyResolver {

    /** Resolves a key or preserves its unknown/unavailable state. */
    Resolution<PublicSigningKey> resolve(String keyId, int keyVersion);
}
