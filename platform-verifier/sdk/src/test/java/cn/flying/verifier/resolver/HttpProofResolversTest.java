package cn.flying.verifier.resolver;

import cn.flying.verifier.VerifierTestFixture;
import cn.flying.verifier.crypto.CanonicalJson;
import cn.flying.verifier.model.ChainQuery;
import cn.flying.verifier.model.ChainRootEvidence;
import cn.flying.verifier.model.PublicProofStatus;
import cn.flying.verifier.model.PublicSigningKey;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HTTP resolver tests for success, caching, status freshness, failure mapping, and SSRF policy.
 */
class HttpProofResolversTest {

    @TempDir
    Path directory;

    private HttpServer server;
    private URI baseUri;
    private VerifierTestFixture.Fixture fixture;
    private AtomicReference<ResponseMode> mode;
    private AtomicInteger keyRequests;
    private AtomicInteger statusRequests;
    private AtomicInteger chainRequests;

    /** Starts a loopback dependency that returns strict fixture-shaped JSON. */
    @BeforeEach
    void setUp() throws Exception {
        fixture = new VerifierTestFixture().create(directory);
        mode = new AtomicReference<>(ResponseMode.SUCCESS);
        keyRequests = new AtomicInteger();
        statusRequests = new AtomicInteger();
        chainRequests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/issuer");
    }

    /** Stops the in-process dependency even when a resolver assertion fails. */
    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /** Resolves exact identities, caches keys/chain roots, and deliberately does not cache status. */
    @Test
    void shouldResolveTrustedEvidenceWithBoundedCachePolicy() {
        HttpProofResolvers resolvers = resolver(true);

        Resolution<PublicSigningKey> firstKey = resolvers.resolve(
                VerifierTestFixture.KEY_ID, VerifierTestFixture.KEY_VERSION);
        Resolution<PublicSigningKey> secondKey = resolvers.resolve(
                VerifierTestFixture.KEY_ID, VerifierTestFixture.KEY_VERSION);
        Resolution<PublicProofStatus> firstStatus = resolvers.resolve(VerifierTestFixture.PROOF_ID);
        Resolution<PublicProofStatus> secondStatus = resolvers.resolve(VerifierTestFixture.PROOF_ID);
        ChainQuery query = query();
        Resolution<ChainRootEvidence> firstChain = resolvers.resolve(query);
        Resolution<ChainRootEvidence> secondChain = resolvers.resolve(query);

        assertThat(firstKey.state()).isEqualTo(ResolutionState.RESOLVED);
        assertThat(secondKey.value()).isEqualTo(firstKey.value());
        assertThat(firstKey.value().source()).contains("/issuer/api/v1/public/proof-keys/");
        assertThat(firstStatus.state()).isEqualTo(ResolutionState.RESOLVED);
        assertThat(secondStatus.value().statusVersion()).isEqualTo("1");
        assertThat(firstStatus.value().issuedAt()).isEqualTo("2026-07-14T00:00:00Z");
        assertThat(firstStatus.value().updatedAt()).isEqualTo("2026-07-14T00:00:00Z");
        assertThat(firstChain.state()).isEqualTo(ResolutionState.RESOLVED);
        assertThat(secondChain.value()).isEqualTo(firstChain.value());
        assertThat(firstChain.value().source()).contains("batch-public-1");
        assertThat(keyRequests).hasValue(1);
        assertThat(statusRequests).hasValue(2);
        assertThat(chainRequests).hasValue(1);
    }

    /** Maps endpoint 404, dependency failure, unexpected status, wrong media type, and bad JSON safely. */
    @Test
    void shouldMapTransportAndPayloadFailuresWithoutResponseDisclosure() {
        mode.set(ResponseMode.NOT_FOUND);
        assertThat(resolver(true).resolve(VerifierTestFixture.PROOF_ID).state())
                .isEqualTo(ResolutionState.NOT_FOUND);

        mode.set(ResponseMode.UNAVAILABLE);
        assertThat(resolver(true).resolve(VerifierTestFixture.PROOF_ID).state())
                .isEqualTo(ResolutionState.UNAVAILABLE);

        mode.set(ResponseMode.UNEXPECTED);
        assertThat(resolver(true).resolve(VerifierTestFixture.PROOF_ID).state())
                .isEqualTo(ResolutionState.ERROR);

        mode.set(ResponseMode.WRONG_MEDIA_TYPE);
        assertThat(resolver(true).resolve(VerifierTestFixture.PROOF_ID).state())
                .isEqualTo(ResolutionState.ERROR);

        mode.set(ResponseMode.BAD_JSON);
        assertThat(resolver(true).resolve(VerifierTestFixture.PROOF_ID).state())
                .isEqualTo(ResolutionState.ERROR);

        mode.set(ResponseMode.OVERSIZED);
        assertThat(resolver(true).resolve(VerifierTestFixture.PROOF_ID).state())
                .isEqualTo(ResolutionState.UNAVAILABLE);

        mode.set(ResponseMode.BAD_KEY_RESPONSE);
        assertThat(resolver(true).resolve(
                VerifierTestFixture.KEY_ID, VerifierTestFixture.KEY_VERSION).state())
                .isEqualTo(ResolutionState.ERROR);
    }

    /** Never follows redirects and maps an exceeded request deadline to an unavailable dependency. */
    @Test
    void shouldRejectRedirectsAndBoundRequestDuration() {
        mode.set(ResponseMode.REDIRECT);
        assertThat(resolver(true).resolve(VerifierTestFixture.PROOF_ID).state())
                .isEqualTo(ResolutionState.ERROR);
        assertThat(statusRequests).hasValue(1);

        mode.set(ResponseMode.DELAYED);
        HttpResolverConfiguration shortTimeout = new HttpResolverConfiguration(
                baseUri,
                baseUri.resolve("/")
                        + "chain/{chainType}/{chainId}/{groupId}/{contractAddress}/{batchNo}",
                Set.of("127.0.0.1"),
                true,
                true,
                Duration.ofSeconds(1),
                Duration.ofMillis(50),
                4096,
                Duration.ZERO,
                Duration.ZERO,
                8);
        HttpProofResolvers resolvers = new HttpProofResolvers(
                shortTimeout,
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                new CanonicalJson(),
                Clock.fixed(VerifierTestFixture.NOW, ZoneOffset.UTC));

        assertThat(resolvers.resolve(VerifierTestFixture.PROOF_ID).state())
                .isEqualTo(ResolutionState.UNAVAILABLE);
    }

    /** Rejects loopback resolution unless the operator explicitly enables private test targets. */
    @Test
    void shouldRejectPrivateAddressByDefaultAtRequestTime() {
        HttpProofResolvers resolvers = resolver(false);

        Resolution<PublicProofStatus> result = resolvers.resolve(VerifierTestFixture.PROOF_ID);

        assertThat(result.state()).isEqualTo(ResolutionState.UNAVAILABLE);
        assertThat(statusRequests).hasValue(0);
    }

    /** Rejects invalid identities before HTTP and malformed chain query values before template rendering. */
    @Test
    void shouldRejectInvalidResolverInputsBeforeNetworkAccess() {
        HttpProofResolvers resolvers = resolver(true);

        assertThat(resolvers.resolve("bad/key", 0).state()).isEqualTo(ResolutionState.UNAVAILABLE);
        assertThat(resolvers.resolve("bad-proof").state()).isEqualTo(ResolutionState.UNAVAILABLE);
        assertThat(resolvers.resolve((ChainQuery) null).state()).isEqualTo(ResolutionState.UNAVAILABLE);
        ChainQuery invalid = new ChainQuery(
                "LOCAL_FISCO", "chain\nvalue", "group0", VerifierTestFixture.CONTRACT_ADDRESS,
                VerifierTestFixture.BATCH_NO, VerifierTestFixture.TRANSACTION_HASH, fixture.chain().merkleRoot());
        assertThat(resolvers.resolve(invalid).state()).isEqualTo(ResolutionState.ERROR);
        assertThat(keyRequests).hasValue(0);
        assertThat(statusRequests).hasValue(0);
        assertThat(chainRequests).hasValue(0);
    }

    /** Rejects insecure, non-allowlisted, query-bearing, and unbounded configurations at construction. */
    @Test
    void shouldValidateResolverSecurityConfiguration() {
        assertThatThrownBy(() -> HttpResolverConfiguration.secureDefaults(
                baseUri,
                baseUri + "/chain/{batchNo}",
                Set.of("127.0.0.1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> configuration(true, true, Set.of("example.com"), 4096))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpResolverConfiguration(
                URI.create(baseUri + "?query=1"),
                null,
                Set.of("127.0.0.1"),
                true,
                true,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                4096,
                Duration.ZERO,
                Duration.ZERO,
                1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpProofResolvers(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HttpResolverConfiguration(
                baseUri,
                null,
                Set.of("127.0.0.1"),
                true,
                true,
                Duration.ofMinutes(2),
                Duration.ofSeconds(1),
                4096,
                Duration.ZERO,
                Duration.ZERO,
                1))
                .isInstanceOf(IllegalArgumentException.class);

        URI carrierGradeNat = URI.create("http://100.64.0.1");
        HttpProofResolvers reservedAddressResolver = new HttpProofResolvers(
                new HttpResolverConfiguration(
                        carrierGradeNat,
                        null,
                        Set.of("100.64.0.1"),
                        true,
                        false,
                        Duration.ofMillis(10),
                        Duration.ofMillis(10),
                        4096,
                        Duration.ZERO,
                        Duration.ZERO,
                        1));
        assertThat(reservedAddressResolver.resolve(VerifierTestFixture.PROOF_ID).state())
                .isEqualTo(ResolutionState.UNAVAILABLE);

        URI documentationAddress = URI.create("http://192.0.2.1");
        HttpProofResolvers documentationAddressResolver = new HttpProofResolvers(
                new HttpResolverConfiguration(
                        documentationAddress,
                        null,
                        Set.of("192.0.2.1"),
                        true,
                        false,
                        Duration.ofMillis(10),
                        Duration.ofMillis(10),
                        4096,
                        Duration.ZERO,
                        Duration.ZERO,
                        1));
        assertThat(documentationAddressResolver.resolve(VerifierTestFixture.PROOF_ID).state())
                .isEqualTo(ResolutionState.UNAVAILABLE);
    }

    /** Creates one resolver using an injectable fixed clock and no-redirect JDK transport. */
    private HttpProofResolvers resolver(boolean allowPrivate) {
        return new HttpProofResolvers(
                configuration(true, allowPrivate, Set.of("127.0.0.1"), 4096),
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                new CanonicalJson(),
                Clock.fixed(VerifierTestFixture.NOW, ZoneOffset.UTC));
    }

    /** Builds the local issuer and chain template under explicit test-only HTTP policy. */
    private HttpResolverConfiguration configuration(
            boolean allowHttp,
            boolean allowPrivate,
            Set<String> hosts,
            int maxBytes
    ) {
        return new HttpResolverConfiguration(
                baseUri,
                baseUri.resolve("/")
                        + "chain/{chainType}/{chainId}/{groupId}/{contractAddress}/{batchNo}",
                hosts,
                allowHttp,
                allowPrivate,
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                maxBytes,
                Duration.ofMinutes(5),
                Duration.ofSeconds(30),
                8);
    }

    /** Returns the exact chain query expected by the generated proof. */
    private ChainQuery query() {
        return new ChainQuery(
                fixture.chain().chainType(),
                fixture.chain().chainId(),
                fixture.chain().groupId(),
                fixture.chain().contractAddress(),
                fixture.chain().batchNo(),
                fixture.chain().transactionHash(),
                fixture.chain().merkleRoot());
    }

    /** Routes fixture endpoint requests and applies the currently selected negative response mode. */
    private void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.contains("/proof-keys/")) {
            keyRequests.incrementAndGet();
        } else if (path.contains("/proofs/")) {
            statusRequests.incrementAndGet();
        } else if (path.startsWith("/chain/")) {
            chainRequests.incrementAndGet();
        }
        ResponseMode selected = mode.get();
        if (selected != ResponseMode.SUCCESS) {
            writeMode(exchange, selected);
            return;
        }
        byte[] body;
        if (path.contains("/proof-keys/")) {
            body = keyBody();
        } else if (path.contains("/proofs/")) {
            body = statusBody();
        } else {
            body = chainBody();
        }
        write(exchange, 200, "application/json; charset=utf-8", body);
    }

    /** Returns one successful key endpoint envelope. */
    private byte[] keyBody() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("algorithm", fixture.key().algorithm());
        data.put("keyId", fixture.key().keyId());
        data.put("keyVersion", fixture.key().keyVersion());
        data.put("publicKeyFingerprint", fixture.key().publicKeyFingerprint());
        data.put("publicKeySpki", fixture.key().publicKeySpki());
        return envelope(data);
    }

    /** Returns one successful current-status endpoint envelope. */
    private byte[] statusBody() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("issuedAt", OffsetDateTime.parse(fixture.status().issuedAt()).toInstant().toEpochMilli());
        data.put("issuedStatus", fixture.status().issuedStatus());
        data.put("keyId", fixture.status().keyId());
        data.put("keyVersion", fixture.status().keyVersion());
        data.put("proofId", fixture.status().proofId());
        data.put("reason", null);
        data.put("status", fixture.status().status());
        data.put("statusVersion", 1);
        data.put("updatedAt", OffsetDateTime.parse(fixture.status().updatedAt()).toInstant().toEpochMilli());
        return envelope(data);
    }

    /** Returns one successful direct chain-gateway response. */
    private byte[] chainBody() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchNo", fixture.chain().batchNo());
        data.put("blockNumber", fixture.chain().blockNumber());
        data.put("chainId", fixture.chain().chainId());
        data.put("chainType", fixture.chain().chainType());
        data.put("contractAddress", fixture.chain().contractAddress());
        data.put("groupId", fixture.chain().groupId());
        data.put("merkleRoot", fixture.chain().merkleRoot());
        data.put("schemaVersion", fixture.chain().schemaVersion());
        data.put("transactionHash", fixture.chain().transactionHash());
        return new CanonicalJson().canonicalBytes(data);
    }

    /** Wraps one data object in the platform's successful public endpoint envelope. */
    private byte[] envelope(Map<String, Object> data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("code", 200);
        envelope.put("data", data);
        envelope.put("message", "success");
        return new CanonicalJson().canonicalBytes(envelope);
    }

    /** Writes a selected failure response without exposing it to resolver callers. */
    private void writeMode(HttpExchange exchange, ResponseMode selected) throws IOException {
        switch (selected) {
            case NOT_FOUND -> write(exchange, 404, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            case UNAVAILABLE -> write(exchange, 503, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            case UNEXPECTED -> write(exchange, 401, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            case WRONG_MEDIA_TYPE -> write(exchange, 200, "text/plain", "{}".getBytes(StandardCharsets.UTF_8));
            case BAD_JSON -> write(exchange, 200, "application/json", "{".getBytes(StandardCharsets.UTF_8));
            case OVERSIZED -> write(exchange, 200, "application/json", "x".repeat(5000).getBytes(StandardCharsets.UTF_8));
            case REDIRECT -> {
                exchange.getResponseHeaders().set("Location", baseUri + "/redirect-target");
                write(exchange, 302, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            }
            case DELAYED -> {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    exchange.close();
                    return;
                }
                write(exchange, 200, "application/json", statusBody());
            }
            case BAD_KEY_RESPONSE -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("algorithm", fixture.key().algorithm());
                data.put("keyId", fixture.key().keyId());
                data.put("keyVersion", fixture.key().keyVersion());
                data.put("publicKeyFingerprint", "sha256:" + "0".repeat(64));
                data.put("publicKeySpki", fixture.key().publicKeySpki());
                write(exchange, 200, "application/json", envelope(data));
            }
            case SUCCESS -> throw new IllegalStateException("Success mode must use the fixture response");
        }
    }

    /** Writes and closes one local dependency response. */
    private void write(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    /** Selectable dependency behavior for failure mapping tests. */
    private enum ResponseMode {
        SUCCESS,
        NOT_FOUND,
        UNAVAILABLE,
        UNEXPECTED,
        WRONG_MEDIA_TYPE,
        BAD_JSON,
        OVERSIZED,
        REDIRECT,
        DELAYED,
        BAD_KEY_RESPONSE
    }
}
