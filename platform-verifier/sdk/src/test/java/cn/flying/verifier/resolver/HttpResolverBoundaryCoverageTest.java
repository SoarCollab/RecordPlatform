package cn.flying.verifier.resolver;

import cn.flying.verifier.VerifierTestFixture;
import cn.flying.verifier.crypto.CanonicalJson;
import cn.flying.verifier.model.ChainQuery;
import cn.flying.verifier.model.ChainRootEvidence;
import cn.flying.verifier.model.PublicProofStatus;
import cn.flying.verifier.model.PublicSigningKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused boundary coverage for resolver normalization, parsing, address policy, and bounded caches.
 */
class HttpResolverBoundaryCoverageTest {

    private static final URI HTTPS_ISSUER = URI.create("https://example.com/issuer");

    @TempDir
    Path directory;

    /** Covers nullable defaults, host normalization, and every independent numeric limit rejection. */
    @Test
    void shouldNormalizeDefaultsAndRejectEveryInvalidBound() {
        Set<String> hosts = new HashSet<>();
        hosts.add(null);
        hosts.add(" ");
        hosts.add(" EXAMPLE.COM ");
        HttpResolverConfiguration defaults = configuration(
                HTTPS_ISSUER, null, hosts, false,
                null, null, 4096, null, null, 8);

        assertThat(defaults.allowedHosts()).containsExactly("example.com");
        assertThat(defaults.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(defaults.requestTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults.signingKeyCacheTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(defaults.chainCacheTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(HttpResolverConfiguration.secureDefaults(
                HTTPS_ISSUER,
                "https://example.com/chain/{batchNo}",
                Set.of("example.com")).chainCacheTtl()).isEqualTo(Duration.ofSeconds(30));

        assertInvalidConfiguration(null, Duration.ofSeconds(1), 4096,
                Duration.ZERO, Duration.ZERO, 8, Set.of());
        assertInvalidConfiguration(Duration.ofSeconds(1), Duration.ofSeconds(1), 4096,
                Duration.ZERO, Duration.ZERO, 8, null);
        assertInvalidConfiguration(Duration.ZERO, Duration.ofSeconds(1), 4096,
                Duration.ZERO, Duration.ZERO, 8, Set.of("example.com"));
        assertInvalidConfiguration(Duration.ofSeconds(-1), Duration.ofSeconds(1), 4096,
                Duration.ZERO, Duration.ZERO, 8, Set.of("example.com"));
        assertInvalidConfiguration(Duration.ofSeconds(61), Duration.ofSeconds(1), 4096,
                Duration.ZERO, Duration.ZERO, 8, Set.of("example.com"));
        assertInvalidConfiguration(Duration.ofSeconds(1), Duration.ZERO, 4096,
                Duration.ZERO, Duration.ZERO, 8, Set.of("example.com"));
        assertInvalidConfiguration(Duration.ofSeconds(1), Duration.ofSeconds(-1), 4096,
                Duration.ZERO, Duration.ZERO, 8, Set.of("example.com"));
        assertInvalidConfiguration(Duration.ofSeconds(1), Duration.ofMinutes(6), 4096,
                Duration.ZERO, Duration.ZERO, 8, Set.of("example.com"));
        assertInvalidConfiguration(Duration.ofSeconds(1), Duration.ofSeconds(1), 0,
                Duration.ZERO, Duration.ZERO, 8, Set.of("example.com"));
        assertInvalidConfiguration(Duration.ofSeconds(1), Duration.ofSeconds(1), 1024 * 1024 + 1,
                Duration.ZERO, Duration.ZERO, 8, Set.of("example.com"));
        assertInvalidConfiguration(Duration.ofSeconds(1), Duration.ofSeconds(1), 4096,
                Duration.ofSeconds(-1), Duration.ZERO, 8, Set.of("example.com"));
        assertInvalidConfiguration(Duration.ofSeconds(1), Duration.ofSeconds(1), 4096,
                Duration.ofDays(2), Duration.ZERO, 8, Set.of("example.com"));
        assertInvalidConfiguration(Duration.ofSeconds(1), Duration.ofSeconds(1), 4096,
                Duration.ZERO, Duration.ofSeconds(-1), 8, Set.of("example.com"));
        assertInvalidConfiguration(Duration.ofSeconds(1), Duration.ofSeconds(1), 4096,
                Duration.ZERO, Duration.ofHours(2), 8, Set.of("example.com"));
        assertInvalidConfiguration(Duration.ofSeconds(1), Duration.ofSeconds(1), 4096,
                Duration.ZERO, Duration.ZERO, 0, Set.of("example.com"));
        assertInvalidConfiguration(Duration.ofSeconds(1), Duration.ofSeconds(1), 4096,
                Duration.ZERO, Duration.ZERO, 4097, Set.of("example.com"));

        HttpResolverConfiguration noEndpoints = configuration(
                null, null, Set.of("example.com"), false,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 4096,
                Duration.ZERO, Duration.ZERO, 8);
        assertThat(noEndpoints.issuerBaseUri()).isNull();
        HttpResolverConfiguration blankChainTemplate = configuration(
                HTTPS_ISSUER, " ", Set.of("example.com"), false,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 4096,
                Duration.ZERO, Duration.ZERO, 8);
        assertThat(blankChainTemplate.chainUrlTemplate()).isBlank();
    }

    /** Exercises every URI authority rejection after a valid baseline reaches the final query check. */
    @Test
    void shouldRejectEveryDisallowedEndpointShape() {
        assertEndpointRejected(URI.create("relative/path"), false, Set.of("example.com"));
        assertEndpointRejected(URI.create("http://example.com"), false, Set.of("example.com"));
        assertEndpointRejected(URI.create("file:/tmp/key"), false, Set.of("example.com"));
        assertEndpointRejected(URI.create("https:/missing-authority"), false, Set.of("example.com"));
        assertEndpointRejected(HTTPS_ISSUER, false, Set.of("other.example"));
        assertEndpointRejected(URI.create("https://user@example.com/issuer"), false, Set.of("example.com"));
        assertEndpointRejected(URI.create("https://example.com/issuer#fragment"), false, Set.of("example.com"));
        assertEndpointRejected(URI.create("https://example.com/issuer?query=1"), false, Set.of("example.com"));

        HttpResolverConfiguration httpAllowed = configuration(
                URI.create("http://example.com/issuer"), null, Set.of("example.com"), true,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 4096,
                Duration.ZERO, Duration.ZERO, 8);
        assertThat(httpAllowed.allowHttp()).isTrue();
    }

    /** Covers all explicitly reserved IPv4/IPv6 ranges plus one genuinely global address per family. */
    @Test
    void shouldClassifyReservedAndGlobalNetworkAddresses() throws Exception {
        HttpProofResolvers resolver = resolver(Clock.systemUTC(), 8);
        String[] reservedIpv4 = {
                "0.1.2.3", "100.64.0.1", "100.127.255.255",
                "192.0.0.1", "192.0.2.1", "192.31.196.1", "192.52.193.1", "192.88.99.1",
                "192.175.48.1", "198.18.0.1", "198.19.0.1", "198.51.100.1",
                "203.0.113.1", "240.0.0.1"
        };
        for (String address : reservedIpv4) {
            assertThat(privateAddress(resolver, address)).as(address).isTrue();
        }
        for (String address : new String[]{"8.8.8.8", "100.63.0.1", "100.128.0.1", "198.20.0.1"}) {
            assertThat(privateAddress(resolver, address)).as(address).isFalse();
        }

        String[] reservedIpv6 = {
                "::1", "fc00::1", "2001:0000::1", "2001:0002::1", "2001:0010::1",
                "2001:0020::1", "2001:db8::1", "3fff::1", "2002::1"
        };
        for (String address : reservedIpv6) {
            assertThat(privateAddress(resolver, address)).as(address).isTrue();
        }
        for (String address : new String[]{"2001:0030::1", "2001:4860:4860::8888"}) {
            assertThat(privateAddress(resolver, address)).as(address).isFalse();
        }
    }

    /** Covers null, standard JSON, structured-suffix JSON, and deceptive media-type inputs. */
    @Test
    void shouldAcceptOnlyBoundedJsonMediaTypes() throws Exception {
        HttpProofResolvers resolver = resolver(Clock.systemUTC(), 8);

        assertThat(invokeBoolean(resolver, "isJsonContentType", new Class<?>[]{String.class}, (Object) null))
                .isFalse();
        assertThat(invokeBoolean(resolver, "isJsonContentType", new Class<?>[]{String.class},
                "Application/JSON; Charset=UTF-8")).isTrue();
        assertThat(invokeBoolean(resolver, "isJsonContentType", new Class<?>[]{String.class},
                "application/problem+json")).isTrue();
        assertThat(invokeBoolean(resolver, "isJsonContentType", new Class<?>[]{String.class},
                "text/json")).isFalse();
        assertThat(invokeBoolean(resolver, "isJsonContentType", new Class<?>[]{String.class},
                "application/json-seq")).isFalse();
    }

    /** Covers every scalar/timestamp node family including null, containers, oversize text, and huge integers. */
    @Test
    void shouldNormalizeOnlySupportedBoundedJsonScalars() throws Exception {
        HttpProofResolvers resolver = resolver(Clock.systemUTC(), 8);
        JsonNodeFactory nodes = JsonNodeFactory.instance;

        assertThat(invoke(resolver, "nodeScalarText", new Class<?>[]{JsonNode.class}, (Object) null)).isNull();
        assertThat(invoke(resolver, "nodeScalarText", new Class<?>[]{JsonNode.class}, nodes.nullNode())).isNull();
        assertThat(invoke(resolver, "nodeScalarText", new Class<?>[]{JsonNode.class}, nodes.objectNode())).isNull();
        assertThat(invoke(resolver, "nodeScalarText", new Class<?>[]{JsonNode.class}, nodes.numberNode(7)))
                .isEqualTo("7");
        assertThat((String) invoke(resolver, "nodeScalarText", new Class<?>[]{JsonNode.class},
                nodes.textNode("x".repeat(129)))).hasSize(128);

        assertThat(invoke(resolver, "nodeTimestampText", new Class<?>[]{JsonNode.class}, (Object) null)).isNull();
        assertThat(invoke(resolver, "nodeTimestampText", new Class<?>[]{JsonNode.class}, nodes.nullNode())).isNull();
        assertThat(invoke(resolver, "nodeTimestampText", new Class<?>[]{JsonNode.class}, nodes.objectNode())).isNull();
        assertThat(invoke(resolver, "nodeTimestampText", new Class<?>[]{JsonNode.class},
                nodes.numberNode(BigInteger.ONE.shiftLeft(80)))).isNull();
        assertThat(invoke(resolver, "nodeTimestampText", new Class<?>[]{JsonNode.class}, nodes.numberNode(0L)))
                .isEqualTo(Instant.EPOCH.toString());
        assertThat(invoke(resolver, "nodeTimestampText", new Class<?>[]{JsonNode.class}, nodes.booleanNode(true)))
                .isNull();
        assertThat(invoke(resolver, "nodeTimestampText", new Class<?>[]{JsonNode.class}, nodes.textNode("now")))
                .isEqualTo("now");
        assertThat(invoke(resolver, "nodeTimestampText", new Class<?>[]{JsonNode.class},
                nodes.textNode("x".repeat(129)))).isNull();
    }

    /** Exercises every chain placeholder plus null, blank, control, and oversized query values. */
    @Test
    void shouldValidateEveryRenderedChainQueryValue() throws Exception {
        HttpProofResolvers resolver = resolver(Clock.systemUTC(), 8);
        ChainQuery valid = query("LOCAL_FISCO", "chain", "group0", "0x" + "a".repeat(40), "batch");

        assertThat(queryValue(resolver, valid, "{chainType}")).isEqualTo("LOCAL_FISCO");
        assertThat(queryValue(resolver, valid, "{chainId}")).isEqualTo("chain");
        assertThat(queryValue(resolver, valid, "{groupId}")).isEqualTo("group0");
        assertThat(queryValue(resolver, valid, "{contractAddress}")).startsWith("0x");
        assertThat(queryValue(resolver, valid, "{batchNo}")).isEqualTo("batch");
        assertThat(queryValue(resolver,
                query("BSN_BESU", "chain", null, "0x" + "a".repeat(40), "batch"),
                "{groupId}")).isEmpty();

        assertInvocationRejected(() -> queryValue(resolver, valid, "{unknown}"));
        assertInvocationRejected(() -> queryValue(resolver,
                query("LOCAL_FISCO", null, "group0", "0x" + "a".repeat(40), "batch"), "{chainId}"));
        assertInvocationRejected(() -> queryValue(resolver,
                query("LOCAL_FISCO", " ", "group0", "0x" + "a".repeat(40), "batch"), "{chainId}"));
        assertInvocationRejected(() -> queryValue(resolver,
                query("LOCAL_FISCO", "x".repeat(193), "group0", "0x" + "a".repeat(40), "batch"),
                "{chainId}"));
        assertInvocationRejected(() -> queryValue(resolver,
                query("LOCAL_FISCO", "line\nbreak", "group0", "0x" + "a".repeat(40), "batch"),
                "{chainId}"));
    }

    /** Covers disabled, live, expired, expired-on-insert, and capacity-clearing cache paths. */
    @Test
    void shouldEnforceEveryCacheLifetimeAndCapacityBoundary() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-15T00:00:00Z"));
        HttpProofResolvers resolver = resolver(clock, 1);
        Map<Object, Object> cache = new HashMap<>();

        invokeCacheValue(resolver, cache, "disabled", "value", 0L);
        assertThat(cache).isEmpty();
        invokeCacheValue(resolver, cache, "first", "one", 1_000L);
        assertThat(invokeCachedValue(resolver, cache, "first")).isEqualTo("one");
        invokeCacheValue(resolver, cache, "second", "two", 1_000L);
        assertThat(cache).hasSize(1).containsKey("second");

        clock.advance(Duration.ofSeconds(2));
        assertThat(invokeCachedValue(resolver, cache, "second")).isNull();
        invokeCacheValue(resolver, cache, "expired", "old", 1L);
        clock.advance(Duration.ofMillis(2));
        invokeCacheValue(resolver, cache, "fresh", "new", 1_000L);
        assertThat(cache).hasSize(1).containsKey("fresh");
        assertThat(invokeCachedValue(resolver, cache, "missing")).isNull();
    }

    /** Maps all internal fetch states and verifies response bytes are cloned at both record boundaries. */
    @Test
    void shouldMapEveryFetchStateWithoutSharingResponseBytes() throws Exception {
        HttpProofResolvers resolver = resolver(Clock.systemUTC(), 8);
        byte[] source = {1, 2, 3};
        Object resolved = fetchResult(ResolutionState.RESOLVED, source, null);
        source[0] = 9;
        byte[] first = (byte[]) invoke(resolved, "body", new Class<?>[0]);
        first[1] = 9;
        byte[] second = (byte[]) invoke(resolved, "body", new Class<?>[0]);
        assertThat(second).containsExactly(1, 2, 3);
        assertThat(invoke(fetchResult(ResolutionState.ERROR, null, "empty"), "body", new Class<?>[0]))
                .isNull();

        assertThat(mapFetchState(resolver, ResolutionState.NOT_FOUND).state()).isEqualTo(ResolutionState.NOT_FOUND);
        assertThat(mapFetchState(resolver, ResolutionState.UNAVAILABLE).state())
                .isEqualTo(ResolutionState.UNAVAILABLE);
        assertThat(mapFetchState(resolver, ResolutionState.ERROR).state()).isEqualTo(ResolutionState.ERROR);
        assertThat(mapFetchState(resolver, ResolutionState.RESOLVED).state()).isEqualTo(ResolutionState.ERROR);
    }

    /** Drives key, status, and chain endpoints through identity, transport, parsing, and interruption failures. */
    @Test
    void shouldMapEveryPublicResolverFailureBoundary() throws Exception {
        VerifierTestFixture.Fixture fixture = new VerifierTestFixture().create(directory);
        AtomicReference<StubResponse> response = new AtomicReference<>(
                new StubResponse(404, "application/json", new byte[0], 0L));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> writeStub(exchange, response.get()));
        server.start();
        try {
            URI issuer = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/issuer");
            String chainTemplate = issuer.resolve("/")
                    + "chain/{chainType}/{chainId}/{groupId}/{contractAddress}/{batchNo}";
            HttpProofResolvers resolver = httpResolver(issuer, chainTemplate, true, Clock.systemUTC());

            assertThat(resolver.resolve((String) null, 1).state()).isEqualTo(ResolutionState.UNAVAILABLE);
            assertThat(resolver.resolve("bad/key", 1).state()).isEqualTo(ResolutionState.UNAVAILABLE);
            assertThat(resolver.resolve(fixture.key().keyId(), 0).state()).isEqualTo(ResolutionState.UNAVAILABLE);
            assertThat(resolver.resolve((String) null).state()).isEqualTo(ResolutionState.UNAVAILABLE);
            assertThat(resolver.resolve("bad-proof").state()).isEqualTo(ResolutionState.UNAVAILABLE);
            assertThat(resolver.resolve((ChainQuery) null).state()).isEqualTo(ResolutionState.UNAVAILABLE);

            assertThat(resolver.resolve(fixture.key().keyId(), fixture.key().keyVersion()).state())
                    .isEqualTo(ResolutionState.NOT_FOUND);
            assertThat(resolver.resolve(query(fixture)).state()).isEqualTo(ResolutionState.NOT_FOUND);

            response.set(new StubResponse(429, "application/json", new byte[0], 0L));
            assertThat(resolver.resolve(fixture.status().proofId()).state()).isEqualTo(ResolutionState.UNAVAILABLE);

            response.set(jsonResponse(keyEnvelope(500, fixture.key(), fixture.key().keyId(), fixture.key().keyVersion())));
            assertThat(resolver.resolve(fixture.key().keyId(), fixture.key().keyVersion()).state())
                    .isEqualTo(ResolutionState.ERROR);
            response.set(jsonResponse(keyEnvelope(
                    200, fixture.key(), "different-key", fixture.key().keyVersion())));
            assertThat(resolver.resolve(fixture.key().keyId(), fixture.key().keyVersion()).state())
                    .isEqualTo(ResolutionState.ERROR);
            response.set(jsonResponse(keyEnvelope(
                    200, fixture.key(), fixture.key().keyId(), fixture.key().keyVersion() + 1)));
            assertThat(resolver.resolve(fixture.key().keyId(), fixture.key().keyVersion()).state())
                    .isEqualTo(ResolutionState.ERROR);

            response.set(jsonResponse(statusEnvelope(500, fixture.status(), fixture.status().proofId(), true)));
            assertThat(resolver.resolve(fixture.status().proofId()).state()).isEqualTo(ResolutionState.ERROR);
            response.set(jsonResponse(statusEnvelope(200, fixture.status(), fixture.status().proofId(), false)));
            assertThat(resolver.resolve(fixture.status().proofId()).state()).isEqualTo(ResolutionState.ERROR);
            response.set(jsonResponse(statusEnvelope(200, fixture.status(), "rp-proof-" + "2".repeat(64), true)));
            assertThat(resolver.resolve(fixture.status().proofId()).state()).isEqualTo(ResolutionState.ERROR);

            response.set(new StubResponse(200, "application/json", "{".getBytes(StandardCharsets.UTF_8), 0L));
            assertThat(resolver.resolve(query(fixture)).state()).isEqualTo(ResolutionState.ERROR);

            response.set(jsonResponse(statusEnvelope(200, fixture.status(), fixture.status().proofId(), true)));
            Thread.currentThread().interrupt();
            try {
                assertThat(resolver.resolve(fixture.status().proofId()).state())
                        .isEqualTo(ResolutionState.UNAVAILABLE);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        } finally {
            server.stop(0);
        }
    }

    /** Invokes target validation across scheme, authority, allowlist, private, and public-address outcomes. */
    @Test
    void shouldValidateEveryRequestTargetBoundary() throws Exception {
        HttpProofResolvers httpsResolver = httpResolver(
                HTTPS_ISSUER, null, false, Clock.systemUTC());
        assertThatThrownBy(() -> validateTarget(httpsResolver, URI.create("relative/path")))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> validateTarget(httpsResolver, URI.create("http://example.com/path")))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> validateTarget(httpsResolver, URI.create("https:/missing-host")))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> validateTarget(httpsResolver, URI.create("https://other.example/path")))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> validateTarget(httpsResolver, URI.create("https://user@example.com/path")))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> validateTarget(httpsResolver, URI.create("https://example.com/path#fragment")))
                .isInstanceOf(IOException.class);

        HttpProofResolvers privateResolver = httpResolver(
                URI.create("http://127.0.0.1/issuer"), null, false, Clock.systemUTC());
        assertThatThrownBy(() -> validateTarget(privateResolver, URI.create("http://127.0.0.1/path")))
                .isInstanceOf(IOException.class);

        HttpProofResolvers publicResolver = httpResolver(
                URI.create("https://8.8.8.8/issuer"), null, false, Clock.systemUTC());
        validateTarget(publicResolver, URI.create("https://8.8.8.8/path"));
    }

    /** Builds one valid resolver configuration with injectable clock and cache capacity. */
    private HttpProofResolvers resolver(Clock clock, int maxCacheEntries) {
        HttpResolverConfiguration configuration = configuration(
                null, null, Set.of("example.com"), false,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 4096,
                Duration.ofMinutes(1), Duration.ofMinutes(1), maxCacheEntries);
        return new HttpProofResolvers(
                configuration,
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                new CanonicalJson(),
                clock);
    }

    /** Builds one resolver for a selected HTTP origin and explicit private-address policy. */
    private HttpProofResolvers httpResolver(
            URI issuer,
            String chainTemplate,
            boolean allowPrivateAddresses,
            Clock clock
    ) {
        boolean allowHttp = "http".equalsIgnoreCase(issuer.getScheme());
        HttpResolverConfiguration configuration = new HttpResolverConfiguration(
                issuer,
                chainTemplate,
                Set.of(issuer.getHost()),
                allowHttp,
                allowPrivateAddresses,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                4096,
                Duration.ZERO,
                Duration.ZERO,
                8);
        return new HttpProofResolvers(
                configuration,
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                new CanonicalJson(),
                clock);
    }

    /** Builds a configuration while keeping every unrelated bound valid. */
    private HttpResolverConfiguration configuration(
            URI issuer,
            String chainTemplate,
            Set<String> hosts,
            boolean allowHttp,
            Duration connectTimeout,
            Duration requestTimeout,
            int maxResponseBytes,
            Duration signingKeyTtl,
            Duration chainTtl,
            int maxCacheEntries
    ) {
        return new HttpResolverConfiguration(
                issuer, chainTemplate, hosts, allowHttp, false,
                connectTimeout, requestTimeout, maxResponseBytes,
                signingKeyTtl, chainTtl, maxCacheEntries);
    }

    /** Requires one selected constructor-bound configuration to fail closed. */
    private void assertInvalidConfiguration(
            Duration connectTimeout,
            Duration requestTimeout,
            int maxResponseBytes,
            Duration signingKeyTtl,
            Duration chainTtl,
            int maxCacheEntries,
            Set<String> hosts
    ) {
        assertThatThrownBy(() -> configuration(
                HTTPS_ISSUER, null, hosts, false,
                connectTimeout, requestTimeout, maxResponseBytes,
                signingKeyTtl, chainTtl, maxCacheEntries))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Requires a single malformed endpoint to fail strict constructor validation. */
    private void assertEndpointRejected(URI uri, boolean allowHttp, Set<String> hosts) {
        assertThatThrownBy(() -> configuration(
                uri, null, hosts, allowHttp,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 4096,
                Duration.ZERO, Duration.ZERO, 8))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Invokes the private address policy with literal bytes and no DNS dependency. */
    private boolean privateAddress(HttpProofResolvers resolver, String address) throws Exception {
        return invokeBoolean(
                resolver,
                "isPrivateAddress",
                new Class<?>[]{InetAddress.class},
                InetAddress.getByName(address));
    }

    /** Builds one query whose unrelated fields remain valid for a focused placeholder check. */
    private ChainQuery query(
            String chainType,
            String chainId,
            String groupId,
            String contractAddress,
            String batchNo
    ) {
        return new ChainQuery(chainType, chainId, groupId, contractAddress, batchNo, null, "a".repeat(64));
    }

    /** Copies a generated fixture into the exact live-chain lookup identity. */
    private ChainQuery query(VerifierTestFixture.Fixture fixture) {
        ChainRootEvidence chain = fixture.chain();
        return new ChainQuery(
                chain.chainType(),
                chain.chainId(),
                chain.groupId(),
                chain.contractAddress(),
                chain.batchNo(),
                chain.transactionHash(),
                chain.merkleRoot());
    }

    /** Builds one public-key endpoint envelope with independently selectable response identity. */
    private Map<String, Object> keyEnvelope(
            Integer code,
            PublicSigningKey key,
            String responseKeyId,
            Integer responseKeyVersion
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("algorithm", key.algorithm());
        data.put("keyId", responseKeyId);
        data.put("keyVersion", responseKeyVersion);
        data.put("publicKeyFingerprint", key.publicKeyFingerprint());
        data.put("publicKeySpki", key.publicKeySpki());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("code", code);
        envelope.put("data", data);
        envelope.put("message", "bounded");
        return envelope;
    }

    /** Builds one lifecycle endpoint envelope with optional data and independently selectable identity. */
    private Map<String, Object> statusEnvelope(
            Integer code,
            PublicProofStatus status,
            String responseProofId,
            boolean includeData
    ) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("code", code);
        envelope.put("message", "bounded");
        if (!includeData) {
            envelope.put("data", null);
            return envelope;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("issuedAt", status.issuedAt());
        data.put("issuedStatus", status.issuedStatus());
        data.put("keyId", status.keyId());
        data.put("keyVersion", status.keyVersion());
        data.put("proofId", responseProofId);
        data.put("reason", status.reason());
        data.put("status", status.status());
        data.put("statusVersion", status.statusVersion());
        data.put("updatedAt", status.updatedAt());
        envelope.put("data", data);
        return envelope;
    }

    /** Serializes one strict JSON stub response. */
    private StubResponse jsonResponse(Object body) {
        return new StubResponse(
                200,
                "application/json",
                new CanonicalJson().canonicalBytes(body),
                0L);
    }

    /** Writes and closes one deterministic in-process HTTP response. */
    private void writeStub(HttpExchange exchange, StubResponse response) throws IOException {
        if (response.delayMillis() > 0) {
            try {
                Thread.sleep(response.delayMillis());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
        }
        exchange.getResponseHeaders().set("Content-Type", response.contentType());
        exchange.sendResponseHeaders(response.status(), response.body().length);
        exchange.getResponseBody().write(response.body());
        exchange.close();
    }

    /** Invokes private request-target validation while retaining its checked I/O failure. */
    private void validateTarget(HttpProofResolvers resolver, URI uri) throws Exception {
        invoke(resolver, "validateTarget", new Class<?>[]{URI.class}, uri);
    }

    /** Invokes the private query-value validator and returns its normalized value. */
    private String queryValue(HttpProofResolvers resolver, ChainQuery query, String placeholder) throws Exception {
        return (String) invoke(
                resolver,
                "queryValue",
                new Class<?>[]{ChainQuery.class, String.class},
                query,
                placeholder);
    }

    /** Invokes cache insertion through the erased generic boundary. */
    private void invokeCacheValue(
            HttpProofResolvers resolver,
            Map<Object, Object> cache,
            Object key,
            Object value,
            long ttlMillis
    ) throws Exception {
        invoke(
                resolver,
                "cacheValue",
                new Class<?>[]{Map.class, Object.class, Object.class, long.class},
                cache,
                key,
                value,
                ttlMillis);
    }

    /** Invokes cache lookup through the erased generic boundary. */
    private Object invokeCachedValue(
            HttpProofResolvers resolver,
            Map<Object, Object> cache,
            Object key
    ) throws Exception {
        return invoke(
                resolver,
                "cachedValue",
                new Class<?>[]{Map.class, Object.class},
                cache,
                key);
    }

    /** Constructs one private immutable fetch result for state-mapping coverage. */
    private Object fetchResult(ResolutionState state, byte[] body, String message) throws Exception {
        Class<?> type = Class.forName(HttpProofResolvers.class.getName() + "$FetchResult");
        Constructor<?> constructor = type.getDeclaredConstructor(ResolutionState.class, byte[].class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(state, body, message);
    }

    /** Converts one selected private fetch state through the production mapper. */
    private Resolution<?> mapFetchState(HttpProofResolvers resolver, ResolutionState state) throws Exception {
        Object fetch = fetchResult(state, null, "bounded message");
        Method method = HttpProofResolvers.class.getDeclaredMethod("resolutionWithoutValue", fetch.getClass());
        method.setAccessible(true);
        return (Resolution<?>) method.invoke(resolver, fetch);
    }

    /** Invokes one private boolean helper with an explicit signature. */
    private boolean invokeBoolean(
            Object target,
            String name,
            Class<?>[] parameterTypes,
            Object... arguments
    ) throws Exception {
        return (boolean) invoke(target, name, parameterTypes, arguments);
    }

    /** Invokes a private helper while preserving its target exception for test assertions. */
    private Object invoke(
            Object target,
            String name,
            Class<?>[] parameterTypes,
            Object... arguments
    ) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception exception) {
                throw exception;
            }
            throw error;
        }
    }

    /** Requires one reflective production boundary to reject an invalid value. */
    private void assertInvocationRejected(ThrowingInvocation invocation) {
        assertThatThrownBy(invocation::invoke).isInstanceOf(IllegalArgumentException.class);
    }

    /** Allows checked reflective calls inside fluent exception assertions. */
    @FunctionalInterface
    private interface ThrowingInvocation {
        /** Executes one reflective boundary call. */
        void invoke() throws Exception;
    }

    /** Immutable response selected by one local resolver-boundary test. */
    private record StubResponse(int status, String contentType, byte[] body, long delayMillis) {
        /** Protects mutable response bytes against accidental handler-side mutation. */
        private StubResponse {
            body = body.clone();
        }

        /** Returns independent response bytes for every exchange. */
        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    /** Mutable deterministic clock used to cross cache expiry boundaries without sleeping. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        /** Advances the clock by the selected deterministic duration. */
        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        /** Returns the fixed UTC zone used by resolver cache timestamps. */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /** Keeps the deterministic clock because only UTC is used in this test. */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /** Returns the currently controlled instant. */
        @Override
        public Instant instant() {
            return instant;
        }
    }
}
