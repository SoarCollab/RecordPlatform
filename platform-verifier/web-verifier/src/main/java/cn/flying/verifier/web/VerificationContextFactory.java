package cn.flying.verifier.web;

import cn.flying.verifier.VerificationContext;
import cn.flying.verifier.VerificationLimits;
import cn.flying.verifier.model.PublicSigningKey;
import cn.flying.verifier.resolver.ChainRootResolver;
import cn.flying.verifier.resolver.HttpProofResolvers;
import cn.flying.verifier.resolver.HttpResolverConfiguration;
import cn.flying.verifier.resolver.ProofStatusResolver;
import cn.flying.verifier.resolver.Resolution;
import cn.flying.verifier.resolver.SigningKeyResolver;
import cn.flying.verifier.resolver.TrustedEvidenceLoader;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Builds one fail-closed SDK context from server policy and an optional caller trust anchor.
 */
@Component
public final class VerificationContextFactory {

    private final VerifierProperties properties;
    private final Clock clock;
    private final HttpProofResolvers onlineResolvers;

    /** Validates online settings once at startup and creates the reusable bounded HTTP resolvers. */
    public VerificationContextFactory(VerifierProperties properties, Clock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
        this.onlineResolvers = createOnlineResolvers(properties.online());
    }

    /** Creates resolvers and limits for one request without enabling undeclared network access. */
    public VerificationContext create(byte[] trustedKeyJson) {
        SigningKeyResolver keyResolver;
        if (trustedKeyJson != null && trustedKeyJson.length > 0) {
            PublicSigningKey key = TrustedEvidenceLoader.loadSigningKey(
                    trustedKeyJson, "trusted-multipart");
            keyResolver = TrustedEvidenceLoader.resolver(key);
        } else if (onlineResolvers != null) {
            keyResolver = onlineResolvers;
        } else {
            keyResolver = (keyId, keyVersion) ->
                    Resolution.unavailable("Trusted signing key resolution is disabled");
        }
        ProofStatusResolver statusResolver = onlineResolvers == null
                ? proofId -> Resolution.unavailable("Current proof status resolution is disabled")
                : onlineResolvers;
        ChainRootResolver chainResolver = onlineResolvers == null
                ? query -> Resolution.unavailable("Live chain root resolution is disabled")
                : onlineResolvers;
        VerificationLimits defaults = VerificationLimits.defaults();
        VerificationLimits limits = new VerificationLimits(
                properties.maxOriginalFileBytes(),
                defaults.maxArchiveBytes(),
                defaults.maxEntryBytes(),
                defaults.maxTotalEntryBytes(),
                defaults.maxChunks(),
                defaults.maxProofNodes());
        return new VerificationContext(limits, keyResolver, statusResolver, chainResolver, clock);
    }

    /** Creates hardened online resolvers only when the operator explicitly enables them. */
    private HttpProofResolvers createOnlineResolvers(VerifierProperties.Online online) {
        if (!online.enabled()) {
            return null;
        }
        if (online.issuerBaseUri() == null
                || online.chainUrlTemplate() == null
                || online.chainUrlTemplate().isBlank()
                || online.allowedHosts().isEmpty()) {
            throw new IllegalArgumentException(
                    "Enabled verifier online mode requires issuer, chain template, and allowed hosts");
        }
        return new HttpProofResolvers(new HttpResolverConfiguration(
                online.issuerBaseUri(),
                online.chainUrlTemplate(),
                online.allowedHosts(),
                online.allowHttp(),
                online.allowPrivateAddresses(),
                positive(online.connectTimeout(), "connect timeout"),
                positive(online.requestTimeout(), "request timeout"),
                256 * 1024,
                Duration.ofMinutes(5),
                Duration.ofSeconds(30),
                256));
    }

    /** Rejects zero or negative durations before constructing transport clients. */
    private Duration positive(Duration duration, String field) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Verifier " + field + " must be positive");
        }
        return duration;
    }
}
