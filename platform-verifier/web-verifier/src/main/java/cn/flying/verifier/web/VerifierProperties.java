package cn.flying.verifier.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

/**
 * Resource, rate-limit, and optional online resolver configuration for the Web verifier.
 */
@Validated
@ConfigurationProperties("verifier")
public record VerifierProperties(
        @Min(1) @Max(4_294_967_296L) long maxOriginalFileBytes,
        @Min(1) @Max(128) int maxConcurrentVerifications,
        @NotNull Duration acquireTimeout,
        @Valid @NotNull RateLimit rateLimit,
        @Valid @NotNull Online online
) {

    /** Bounded fixed-window request policy keyed by the direct peer address. */
    public record RateLimit(
            @Min(1) @Max(10_000) int requests,
            @NotNull Duration window,
            @Min(1) @Max(100_000) int maxClients
    ) {
    }

    /** Explicit opt-in resolver configuration; disabled mode never performs outbound requests. */
    public record Online(
            boolean enabled,
            URI issuerBaseUri,
            String chainUrlTemplate,
            Set<String> allowedHosts,
            boolean allowHttp,
            boolean allowPrivateAddresses,
            @NotNull Duration connectTimeout,
            @NotNull Duration requestTimeout
    ) {

        /** Normalizes a missing host collection to an immutable empty set. */
        public Online {
            allowedHosts = allowedHosts == null ? Set.of() : Set.copyOf(allowedHosts);
        }
    }
}
