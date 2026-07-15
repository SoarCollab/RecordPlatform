package cn.flying.verifier;

import cn.flying.verifier.resolver.ChainRootResolver;
import cn.flying.verifier.resolver.ProofStatusResolver;
import cn.flying.verifier.resolver.Resolution;
import cn.flying.verifier.resolver.SigningKeyResolver;

import java.time.Clock;

/**
 * Explicit trust resolvers, resource limits, and clock used for one verification run.
 */
public record VerificationContext(
        VerificationLimits limits,
        SigningKeyResolver signingKeyResolver,
        ProofStatusResolver proofStatusResolver,
        ChainRootResolver chainRootResolver,
        Clock clock
) {

    /**
     * Creates an offline context that can establish local integrity but never a successful current-state result.
     *
     * @return offline context
     */
    public static VerificationContext offline() {
        return new VerificationContext(
                VerificationLimits.defaults(),
                (keyId, keyVersion) -> Resolution.unavailable("Signing key resolution is disabled"),
                proofId -> Resolution.unavailable("Proof status resolution is disabled"),
                query -> Resolution.unavailable("Live chain resolution is disabled"),
                Clock.systemUTC());
    }

    /**
     * Fills optional context values with fail-closed offline defaults.
     */
    public VerificationContext {
        limits = limits == null ? VerificationLimits.defaults() : limits;
        signingKeyResolver = signingKeyResolver == null
                ? (keyId, keyVersion) -> Resolution.unavailable("Signing key resolver is unavailable")
                : signingKeyResolver;
        proofStatusResolver = proofStatusResolver == null
                ? proofId -> Resolution.unavailable("Proof status resolver is unavailable")
                : proofStatusResolver;
        chainRootResolver = chainRootResolver == null
                ? query -> Resolution.unavailable("Live chain resolver is unavailable")
                : chainRootResolver;
        clock = clock == null ? Clock.systemUTC() : clock;
    }
}
