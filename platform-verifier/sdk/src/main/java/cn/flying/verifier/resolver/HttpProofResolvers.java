package cn.flying.verifier.resolver;

import cn.flying.verifier.crypto.CanonicalJson;
import cn.flying.verifier.model.ChainQuery;
import cn.flying.verifier.model.ChainRootEvidence;
import cn.flying.verifier.model.PublicProofStatus;
import cn.flying.verifier.model.PublicSigningKey;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hardened optional HTTP resolvers for issuer keys/status and a configured live chain gateway.
 */
public final class HttpProofResolvers implements SigningKeyResolver, ProofStatusResolver, ChainRootResolver {

    private static final String USER_AGENT = "RecordPlatform-Public-Verifier/0.0.2";
    private static final Set<String> ALLOWED_PLACEHOLDERS = Set.of(
            "{chainType}", "{chainId}", "{groupId}", "{contractAddress}", "{batchNo}");

    private final HttpResolverConfiguration configuration;
    private final HttpClient client;
    private final CanonicalJson json;
    private final Clock clock;
    private final Map<String, CacheEntry<PublicSigningKey>> keyCache = new ConcurrentHashMap<>();
    private final Map<ChainQuery, CacheEntry<ChainRootEvidence>> chainCache = new ConcurrentHashMap<>();

    /** Creates resolvers with no redirects and the configured connection timeout. */
    public HttpProofResolvers(HttpResolverConfiguration configuration) {
        this(configuration, createClient(configuration),
                new CanonicalJson(),
                Clock.systemUTC());
    }

    /** Creates a no-redirect client after validating the supplied configuration. */
    private static HttpClient createClient(HttpResolverConfiguration configuration) {
        HttpResolverConfiguration required = Objects.requireNonNull(configuration, "configuration");
        return HttpClient.newBuilder()
                .connectTimeout(required.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Creates resolvers with injectable transport and clock for deterministic tests. */
    HttpProofResolvers(
            HttpResolverConfiguration configuration,
            HttpClient client,
            CanonicalJson json,
            Clock clock
    ) {
        this.configuration = Objects.requireNonNull(configuration);
        this.client = Objects.requireNonNull(client);
        this.json = Objects.requireNonNull(json);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Resolves and caches a versioned public signing key for at most five configured minutes. */
    @Override
    public Resolution<PublicSigningKey> resolve(String keyId, int keyVersion) {
        if (configuration.issuerBaseUri() == null
                || keyId == null
                || !keyId.matches("^[A-Za-z0-9._-]{1,64}$")
                || keyVersion <= 0) {
            return Resolution.unavailable("Issuer signing-key endpoint is not configured");
        }
        String cacheKey = keyId + ":" + keyVersion;
        PublicSigningKey cached = cachedValue(keyCache, cacheKey);
        if (cached != null) {
            return Resolution.resolved(cached);
        }
        URI uri = joinIssuerPath("/api/v1/public/proof-keys/" + keyId + "/versions/" + keyVersion);
        FetchResult fetch = fetchJson(uri);
        if (fetch.state() != ResolutionState.RESOLVED) {
            return resolutionWithoutValue(fetch);
        }
        try {
            KeyEnvelope envelope = json.read(fetch.body(), KeyEnvelope.class);
            KeyData data = envelope.data();
            if (!success(envelope.code())
                    || data == null
                    || !Objects.equals(keyId, data.keyId())
                    || !Objects.equals(keyVersion, data.keyVersion())) {
                return Resolution.error("Issuer signing-key response identity is invalid");
            }
            PublicSigningKey key = TrustedEvidenceLoader.validateSigningKey(new PublicSigningKey(
                    data.keyId(),
                    data.keyVersion(),
                    data.algorithm(),
                    data.publicKeySpki(),
                    data.publicKeyFingerprint(),
                    uri.toString()));
            cacheValue(keyCache, cacheKey, key, configuration.signingKeyCacheTtl().toMillis());
            return Resolution.resolved(key);
        } catch (IllegalArgumentException e) {
            return Resolution.error("Issuer signing-key response is not strict bounded JSON");
        }
    }

    /** Resolves current proof status without caching so revocation changes are not hidden. */
    @Override
    public Resolution<PublicProofStatus> resolve(String proofId) {
        if (configuration.issuerBaseUri() == null
                || proofId == null
                || !proofId.matches("^rp-proof-[0-9a-f]{64}$")) {
            return Resolution.unavailable("Issuer proof-status endpoint is not configured");
        }
        URI uri = joinIssuerPath("/api/v1/public/proofs/" + proofId + "/status");
        FetchResult fetch = fetchJson(uri);
        if (fetch.state() != ResolutionState.RESOLVED) {
            return resolutionWithoutValue(fetch);
        }
        try {
            StatusEnvelope envelope = json.read(fetch.body(), StatusEnvelope.class);
            StatusData data = envelope.data();
            if (!success(envelope.code()) || data == null || !Objects.equals(proofId, data.proofId())) {
                return Resolution.error("Issuer proof-status response identity is invalid");
            }
            return Resolution.resolved(new PublicProofStatus(
                    data.proofId(),
                    data.status(),
                    nodeScalarText(data.statusVersion()),
                    data.issuedStatus(),
                    data.keyId(),
                    data.keyVersion(),
                    data.reason(),
                    nodeTimestampText(data.issuedAt()),
                    nodeTimestampText(data.updatedAt()),
                    uri.toString()));
        } catch (IllegalArgumentException e) {
            return Resolution.error("Issuer proof-status response is not strict bounded JSON");
        }
    }

    /** Resolves and briefly caches one live chain root from the configured fixed-origin template. */
    @Override
    public Resolution<ChainRootEvidence> resolve(ChainQuery query) {
        if (query == null
                || configuration.chainUrlTemplate() == null
                || configuration.chainUrlTemplate().isBlank()) {
            return Resolution.unavailable("Live chain gateway is not configured");
        }
        ChainRootEvidence cached = cachedValue(chainCache, query);
        if (cached != null) {
            return Resolution.resolved(cached);
        }
        URI uri;
        try {
            uri = renderChainUri(query);
        } catch (IllegalArgumentException e) {
            return Resolution.error("Live chain query identity or URL template is invalid");
        }
        FetchResult fetch = fetchJson(uri);
        if (fetch.state() != ResolutionState.RESOLVED) {
            return resolutionWithoutValue(fetch);
        }
        try {
            ChainData data = json.read(fetch.body(), ChainData.class);
            ChainRootEvidence evidence = new ChainRootEvidence(
                    data.schemaVersion(),
                    data.chainType(),
                    data.chainId(),
                    data.groupId(),
                    data.contractAddress(),
                    data.batchNo(),
                    data.merkleRoot(),
                    data.transactionHash(),
                    data.blockNumber(),
                    uri.toString());
            cacheValue(chainCache, query, evidence, configuration.chainCacheTtl().toMillis());
            return Resolution.resolved(evidence);
        } catch (IllegalArgumentException e) {
            return Resolution.error("Live chain response is not strict bounded JSON");
        }
    }

    /** Performs one no-redirect bounded JSON request after host and resolved-address checks. */
    private FetchResult fetchJson(URI uri) {
        try {
            validateTarget(uri);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(configuration.requestTimeout())
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() == 404) {
                    return new FetchResult(ResolutionState.NOT_FOUND, null, "Resolver record was not found");
                }
                if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    return new FetchResult(ResolutionState.UNAVAILABLE, null, "Resolver dependency is unavailable");
                }
                if (response.statusCode() != 200) {
                    return new FetchResult(ResolutionState.ERROR, null, "Resolver returned an unexpected HTTP status");
                }
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                if (!isJsonContentType(contentType)) {
                    return new FetchResult(ResolutionState.ERROR, null, "Resolver response is not JSON");
                }
                return new FetchResult(ResolutionState.RESOLVED, readBounded(body), null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new FetchResult(ResolutionState.UNAVAILABLE, null, "Resolver request was interrupted");
        } catch (IOException | RuntimeException e) {
            return new FetchResult(ResolutionState.UNAVAILABLE, null, "Resolver request failed or timed out");
        }
    }

    /** Validates scheme, allowlisted host, authority fields, and resolved public addresses. */
    private void validateTarget(URI uri) throws IOException {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        boolean schemeAllowed = "https".equalsIgnoreCase(scheme)
                || (configuration.allowHttp() && "http".equalsIgnoreCase(scheme));
        if (!uri.isAbsolute()
                || !schemeAllowed
                || host == null
                || !configuration.allowedHosts().contains(host.toLowerCase(Locale.ROOT))
                || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IOException("Resolver target is outside the configured allowlist");
        }
        if (!configuration.allowPrivateAddresses()) {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isPrivateAddress(address)) {
                    throw new IOException("Resolver target maps to a private or local address");
                }
            }
        }
    }

    /** Detects local, private, link-local, multicast, and IPv6 unique-local addresses. */
    private boolean isPrivateAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        boolean ipv6UniqueLocal = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        boolean ipv4NonPublic = bytes.length == 4 && isNonPublicIpv4(bytes);
        boolean ipv6NonPublic = bytes.length == 16 && isNonPublicIpv6(bytes);
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || ipv6UniqueLocal
                || ipv4NonPublic
                || ipv6NonPublic;
    }

    /** Rejects IPv4 carrier-grade NAT, benchmark, protocol-reserved, and future-use ranges. */
    private boolean isNonPublicIpv4(byte[] address) {
        int first = Byte.toUnsignedInt(address[0]);
        int second = Byte.toUnsignedInt(address[1]);
        int third = Byte.toUnsignedInt(address[2]);
        return first == 0
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 192 && second == 0 && third == 0)
                || (first == 192 && second == 0 && third == 2)
                || (first == 192 && second == 31 && third == 196)
                || (first == 192 && second == 52 && third == 193)
                || (first == 192 && second == 88 && third == 99)
                || (first == 192 && second == 175 && third == 48)
                || (first == 198 && (second == 18 || second == 19))
                || (first == 198 && second == 51 && third == 100)
                || (first == 203 && second == 0 && third == 113)
                || first >= 240;
    }

    /** Rejects non-global IPv6 space, transition mechanisms, and documentation/benchmark prefixes. */
    private boolean isNonPublicIpv6(byte[] address) {
        boolean globalUnicast = (Byte.toUnsignedInt(address[0]) & 0xe0) == 0x20;
        boolean teredo = prefixMatches(address, new int[]{0x20, 0x01, 0x00, 0x00}, 32);
        boolean benchmarking = prefixMatches(address, new int[]{0x20, 0x01, 0x00, 0x02, 0x00, 0x00}, 48);
        boolean orchid = prefixMatches(address, new int[]{0x20, 0x01, 0x00, 0x10}, 28)
                || prefixMatches(address, new int[]{0x20, 0x01, 0x00, 0x20}, 28);
        boolean documentation = prefixMatches(address, new int[]{0x20, 0x01, 0x0d, 0xb8}, 32)
                || prefixMatches(address, new int[]{0x3f, 0xff, 0x00}, 20);
        boolean sixToFour = prefixMatches(address, new int[]{0x20, 0x02}, 16);
        return !globalUnicast || teredo || benchmarking || orchid || documentation || sixToFour;
    }

    /** Tests an arbitrary whole- or partial-byte network prefix without string parsing. */
    private boolean prefixMatches(byte[] address, int[] prefix, int prefixBits) {
        int fullBytes = prefixBits / 8;
        int remainingBits = prefixBits % 8;
        for (int index = 0; index < fullBytes; index++) {
            if (Byte.toUnsignedInt(address[index]) != prefix[index]) {
                return false;
            }
        }
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xff << (8 - remainingBits) & 0xff;
        return (Byte.toUnsignedInt(address[fullBytes]) & mask) == (prefix[fullBytes] & mask);
    }

    /** Accepts application/json and structured application/*+json media types only. */
    private boolean isJsonContentType(String contentType) {
        String mediaType = contentType == null
                ? ""
                : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return "application/json".equals(mediaType)
                || (mediaType.startsWith("application/") && mediaType.endsWith("+json"));
    }

    /** Reads a response into memory only up to the configured bounded JSON size. */
    private byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total = Math.addExact(total, read);
            if (total > configuration.maxResponseBytes()) {
                throw new IOException("Resolver response exceeds the configured byte limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    /** Joins a fixed API path to an issuer base that may contain a deployment context path. */
    private URI joinIssuerPath(String path) {
        String base = configuration.issuerBaseUri().toString().replaceAll("/+$", "");
        return URI.create(base + path);
    }

    /** Renders one configured chain URL template from validated path-safe query values. */
    private URI renderChainUri(ChainQuery query) {
        String rendered = configuration.chainUrlTemplate();
        for (String placeholder : ALLOWED_PLACEHOLDERS) {
            if (rendered.contains(placeholder)) {
                rendered = rendered.replace(placeholder, encoded(queryValue(query, placeholder)));
            }
        }
        if (rendered.contains("{") || rendered.contains("}")) {
            throw new IllegalArgumentException("Unknown chain URL template placeholder");
        }
        URI uri = URI.create(rendered);
        try {
            validateTarget(uri);
        } catch (IOException e) {
            throw new IllegalArgumentException("Chain URL is outside the configured allowlist", e);
        }
        return uri;
    }

    /** Returns one bounded query field for a known chain URL placeholder. */
    private String queryValue(ChainQuery query, String placeholder) {
        String value = switch (placeholder) {
            case "{chainType}" -> query.chainType();
            case "{chainId}" -> query.chainId();
            case "{groupId}" -> query.groupId() == null ? "" : query.groupId();
            case "{contractAddress}" -> query.contractAddress();
            case "{batchNo}" -> query.batchNo();
            default -> throw new IllegalArgumentException("Unknown chain URL template placeholder");
        };
        if ("{groupId}".equals(placeholder) && value.isEmpty()) {
            return value;
        }
        if (value == null || value.isBlank() || value.length() > 192 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Chain query value is invalid");
        }
        return value;
    }

    /** Percent-encodes a single template path value. */
    private String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Returns one unexpired bounded cache value. */
    private <K, V> V cachedValue(Map<K, CacheEntry<V>> cache, K key) {
        CacheEntry<V> entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (!entry.expiresAt().isAfter(clock.instant())) {
            cache.remove(key, entry);
            return null;
        }
        return entry.value();
    }

    /** Inserts one cache value while bounding cardinality and clearing expired entries. */
    private <K, V> void cacheValue(Map<K, CacheEntry<V>> cache, K key, V value, long ttlMillis) {
        if (ttlMillis <= 0) {
            return;
        }
        synchronized (cache) {
            Instant now = clock.instant();
            cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
            if (cache.size() >= configuration.maxCacheEntries()) {
                cache.clear();
            }
            cache.put(key, new CacheEntry<>(value, now.plusMillis(ttlMillis)));
        }
    }

    /** Converts a fetch state into a typed resolution without response content. */
    private <T> Resolution<T> resolutionWithoutValue(FetchResult fetch) {
        return switch (fetch.state()) {
            case NOT_FOUND -> Resolution.notFound(fetch.message());
            case UNAVAILABLE -> Resolution.unavailable(fetch.message());
            case ERROR -> Resolution.error(fetch.message());
            case RESOLVED -> Resolution.error("Resolver response value is missing");
        };
    }

    /** Recognizes the platform success envelope code. */
    private boolean success(Integer code) {
        return code != null && code == 200;
    }

    /** Converts an arbitrary scalar JSON node to a bounded string representation. */
    private String nodeScalarText(JsonNode node) {
        if (node == null || node.isNull() || node.isContainerNode()) {
            return null;
        }
        String value = node.asText();
        return value.length() <= 128 ? value : value.substring(0, 128);
    }

    /** Converts the backend's Date epoch-millis or an RFC 3339 string into one timestamp string. */
    private String nodeTimestampText(JsonNode node) {
        if (node == null || node.isNull() || node.isContainerNode()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            if (!node.canConvertToLong()) {
                return null;
            }
            try {
                return Instant.ofEpochMilli(node.longValue()).toString();
            } catch (java.time.DateTimeException e) {
                return null;
            }
        }
        if (!node.isTextual()) {
            return null;
        }
        String value = node.textValue();
        return value != null && value.length() <= 128 ? value : null;
    }

    /** Internal bounded transport result. */
    private record FetchResult(ResolutionState state, byte[] body, String message) {
        private FetchResult {
            body = body == null ? null : body.clone();
        }

        @Override
        public byte[] body() {
            return body == null ? null : body.clone();
        }
    }

    /** One immutable cache value and expiry. */
    private record CacheEntry<T>(T value, Instant expiresAt) {
    }

    /** Strict platform key endpoint envelope. */
    private record KeyEnvelope(Integer code, String message, KeyData data) {
    }

    /** Strict platform key endpoint data. */
    private record KeyData(
            String keyId,
            Integer keyVersion,
            String algorithm,
            String publicKeySpki,
            String publicKeyFingerprint
    ) {
    }

    /** Strict platform status endpoint envelope. */
    private record StatusEnvelope(Integer code, String message, StatusData data) {
    }

    /** Strict platform status endpoint data with format-agnostic date/long scalars. */
    private record StatusData(
            String proofId,
            String status,
            JsonNode statusVersion,
            String issuedStatus,
            String keyId,
            Integer keyVersion,
            String reason,
            JsonNode issuedAt,
            JsonNode updatedAt
    ) {
    }

    /** Strict configured chain-gateway response. */
    private record ChainData(
            String schemaVersion,
            String chainType,
            String chainId,
            String groupId,
            String contractAddress,
            String batchNo,
            String merkleRoot,
            String transactionHash,
            Long blockNumber
    ) {
    }
}
