package cn.flying.verifier.resolver;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Explicit security and cache configuration for optional online proof resolution.
 */
public record HttpResolverConfiguration(
        URI issuerBaseUri,
        String chainUrlTemplate,
        Set<String> allowedHosts,
        boolean allowHttp,
        boolean allowPrivateAddresses,
        Duration connectTimeout,
        Duration requestTimeout,
        int maxResponseBytes,
        Duration signingKeyCacheTtl,
        Duration chainCacheTtl,
        int maxCacheEntries
) {

    /**
     * Normalizes and validates explicit resolver security configuration.
     */
    public HttpResolverConfiguration {
        allowedHosts = allowedHosts == null
                ? Set.of()
                : allowedHosts.stream()
                .filter(host -> host != null && !host.isBlank())
                .map(host -> host.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(5) : requestTimeout;
        signingKeyCacheTtl = signingKeyCacheTtl == null ? Duration.ofMinutes(5) : signingKeyCacheTtl;
        chainCacheTtl = chainCacheTtl == null ? Duration.ofSeconds(30) : chainCacheTtl;
        if (allowedHosts.isEmpty()
                || connectTimeout.isZero()
                || connectTimeout.isNegative()
                || connectTimeout.compareTo(Duration.ofMinutes(1)) > 0
                || requestTimeout.isZero()
                || requestTimeout.isNegative()
                || requestTimeout.compareTo(Duration.ofMinutes(5)) > 0
                || maxResponseBytes <= 0
                || maxResponseBytes > 1024 * 1024
                || signingKeyCacheTtl.isNegative()
                || signingKeyCacheTtl.compareTo(Duration.ofDays(1)) > 0
                || chainCacheTtl.isNegative()
                || chainCacheTtl.compareTo(Duration.ofHours(1)) > 0
                || maxCacheEntries <= 0
                || maxCacheEntries > 4096) {
            throw new IllegalArgumentException("HTTP resolver limits or host allowlist are invalid");
        }
        validateBaseUri(issuerBaseUri, allowHttp, allowedHosts, "issuerBaseUri");
        if (chainUrlTemplate != null && !chainUrlTemplate.isBlank()) {
            String sample = chainUrlTemplate
                    .replace("{chainType}", "LOCAL_FISCO")
                    .replace("{chainId}", "chain")
                    .replace("{groupId}", "group")
                    .replace("{contractAddress}", "0x0000000000000000000000000000000000000000")
                    .replace("{batchNo}", "batch");
            validateBaseUri(URI.create(sample), allowHttp, allowedHosts, "chainUrlTemplate");
        }
    }

    /**
     * Returns secure production-oriented default timeouts and cache bounds for explicit hosts.
     */
    public static HttpResolverConfiguration secureDefaults(
            URI issuerBaseUri,
            String chainUrlTemplate,
            Set<String> allowedHosts
    ) {
        return new HttpResolverConfiguration(
                issuerBaseUri,
                chainUrlTemplate,
                allowedHosts,
                false,
                false,
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                256 * 1024,
                Duration.ofMinutes(5),
                Duration.ofSeconds(30),
                256);
    }

    /** Validates scheme, authority, and explicit host allowlist for a configured endpoint. */
    private static void validateBaseUri(
            URI uri,
            boolean allowHttp,
            Set<String> allowedHosts,
            String field
    ) {
        if (uri == null) {
            return;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        boolean schemeAllowed = "https".equalsIgnoreCase(scheme)
                || (allowHttp && "http".equalsIgnoreCase(scheme));
        if (!uri.isAbsolute()
                || !schemeAllowed
                || host == null
                || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getRawQuery() != null) {
            throw new IllegalArgumentException(field + " is not an allowed absolute HTTP endpoint");
        }
    }
}
