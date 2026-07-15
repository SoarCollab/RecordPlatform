package cn.flying.verifier.resolver;

import cn.flying.verifier.model.PublicProofStatus;

/** Resolves the current proof lifecycle state. */
@FunctionalInterface
public interface ProofStatusResolver {

    /** Resolves current status or preserves its unknown/unavailable state. */
    Resolution<PublicProofStatus> resolve(String proofId);
}
