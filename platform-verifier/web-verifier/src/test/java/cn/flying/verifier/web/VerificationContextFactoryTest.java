package cn.flying.verifier.web;

import cn.flying.verifier.VerifierTestFixture;
import cn.flying.verifier.crypto.CanonicalJson;
import cn.flying.verifier.model.ChainQuery;
import cn.flying.verifier.resolver.HttpProofResolvers;
import cn.flying.verifier.resolver.ResolutionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Context-factory tests for explicit trust selection and fail-closed online configuration.
 */
class VerificationContextFactoryTest {

    @TempDir
    Path directory;

    /** Keeps every trust dependency unavailable when the operator leaves online mode disabled. */
    @Test
    void shouldBuildFailClosedOfflineContext() {
        VerifierProperties properties = properties(disabledOnline());
        VerificationContextFactory factory = new VerificationContextFactory(properties, Clock.systemUTC());

        var nullTrustContext = factory.create(null);
        var emptyTrustContext = factory.create(new byte[0]);

        assertThat(nullTrustContext.limits().maxOriginalFileBytes()).isEqualTo(1024L);
        assertThat(nullTrustContext.signingKeyResolver().resolve("missing", 1).state())
                .isEqualTo(ResolutionState.UNAVAILABLE);
        assertThat(nullTrustContext.proofStatusResolver().resolve("missing").state())
                .isEqualTo(ResolutionState.UNAVAILABLE);
        assertThat(nullTrustContext.chainRootResolver().resolve(new ChainQuery(
                "LOCAL_FISCO", "chain", "group", "0x1", "batch", null, null)).state())
                .isEqualTo(ResolutionState.UNAVAILABLE);
        assertThat(emptyTrustContext.signingKeyResolver().resolve("missing", 1).state())
                .isEqualTo(ResolutionState.UNAVAILABLE);
    }

    /** Prefers an explicitly uploaded signing key while status and chain resolution remain offline. */
    @Test
    void shouldUseExplicitMultipartTrustAnchor() throws Exception {
        var fixture = new VerifierTestFixture().create(directory);
        byte[] keyJson = new CanonicalJson().canonicalBytes(fixture.key());
        var context = new VerificationContextFactory(properties(disabledOnline()), Clock.systemUTC())
                .create(keyJson);

        var resolved = context.signingKeyResolver().resolve(
                fixture.key().keyId(), fixture.key().keyVersion());

        assertThat(resolved.state()).isEqualTo(ResolutionState.RESOLVED);
        assertThat(resolved.value().source()).isEqualTo("trusted-multipart");
        assertThat(context.signingKeyResolver().resolve("other", fixture.key().keyVersion()).state())
                .isEqualTo(ResolutionState.NOT_FOUND);
    }

    /** Reuses one hardened resolver for all trust dependencies only after explicit online opt-in. */
    @Test
    void shouldBuildEnabledOnlineContext() {
        VerifierProperties.Online online = enabledOnline(
                URI.create("https://verifier.example"),
                "https://verifier.example/chains/{chainType}/{chainId}/{groupId}/{contractAddress}/{batchNo}",
                Set.of("verifier.example"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2));

        var context = new VerificationContextFactory(properties(online), Clock.systemUTC()).create(null);

        assertThat(context.signingKeyResolver()).isInstanceOf(HttpProofResolvers.class);
        assertThat((Object) context.signingKeyResolver()).isSameAs(context.proofStatusResolver());
        assertThat((Object) context.signingKeyResolver()).isSameAs(context.chainRootResolver());
    }

    /** Rejects each missing online origin, template, or allowlist field before creating HTTP clients. */
    @Test
    void shouldRejectIncompleteEnabledOnlinePolicy() {
        List<VerifierProperties.Online> invalidPolicies = List.of(
                enabledOnline(null, "https://verifier.example/chain", Set.of("verifier.example"),
                        Duration.ofSeconds(1), Duration.ofSeconds(1)),
                enabledOnline(URI.create("https://verifier.example"), null, Set.of("verifier.example"),
                        Duration.ofSeconds(1), Duration.ofSeconds(1)),
                enabledOnline(URI.create("https://verifier.example"), " ", Set.of("verifier.example"),
                        Duration.ofSeconds(1), Duration.ofSeconds(1)),
                enabledOnline(URI.create("https://verifier.example"), "https://verifier.example/chain", Set.of(),
                        Duration.ofSeconds(1), Duration.ofSeconds(1)));

        for (VerifierProperties.Online online : invalidPolicies) {
            assertThatThrownBy(() -> new VerificationContextFactory(properties(online), Clock.systemUTC()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requires issuer, chain template, and allowed hosts");
        }
    }

    /** Rejects null, zero, and negative connect or request timeouts at the Web policy boundary. */
    @Test
    void shouldRejectNonPositiveOnlineTimeouts() {
        List<VerifierProperties.Online> invalidPolicies = List.of(
                enabledOnlinePolicy(null, Duration.ofSeconds(1)),
                enabledOnlinePolicy(Duration.ZERO, Duration.ofSeconds(1)),
                enabledOnlinePolicy(Duration.ofSeconds(-1), Duration.ofSeconds(1)),
                enabledOnlinePolicy(Duration.ofSeconds(1), null),
                enabledOnlinePolicy(Duration.ofSeconds(1), Duration.ZERO),
                enabledOnlinePolicy(Duration.ofSeconds(1), Duration.ofSeconds(-1)));

        for (VerifierProperties.Online online : invalidPolicies) {
            assertThatThrownBy(() -> new VerificationContextFactory(properties(online), Clock.systemUTC()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be positive");
        }
    }

    /** Creates one complete offline policy for trust-selection tests. */
    private VerifierProperties.Online disabledOnline() {
        return new VerifierProperties.Online(
                false, null, null, null, false, false,
                Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    /** Creates one complete enabled policy with overridable transport fields. */
    private VerifierProperties.Online enabledOnlinePolicy(Duration connectTimeout, Duration requestTimeout) {
        return enabledOnline(
                URI.create("https://verifier.example"),
                "https://verifier.example/chains/{chainType}/{chainId}/{groupId}/{contractAddress}/{batchNo}",
                Set.of("verifier.example"),
                connectTimeout,
                requestTimeout);
    }

    /** Creates one enabled online policy without applying Spring validation. */
    private VerifierProperties.Online enabledOnline(
            URI issuer,
            String chainTemplate,
            Set<String> hosts,
            Duration connectTimeout,
            Duration requestTimeout
    ) {
        return new VerifierProperties.Online(
                true, issuer, chainTemplate, hosts, false, false, connectTimeout, requestTimeout);
    }

    /** Wraps the selected online policy in otherwise valid Web resource limits. */
    private VerifierProperties properties(VerifierProperties.Online online) {
        return new VerifierProperties(
                1024,
                1,
                Duration.ofMillis(10),
                new VerifierProperties.RateLimit(20, Duration.ofMinutes(1), 100),
                online);
    }
}
