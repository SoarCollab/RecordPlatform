package cn.flying.service.key;

import java.time.Instant;
import java.util.Set;

/**
 * Sanitized suite catalog view safe for administrator diagnostics.
 */
public record CryptoSuiteDiagnostic(
        String id,
        CryptoSuiteType type,
        String providerId,
        int providerContractVersion,
        CryptoSuiteStatus status,
        Instant introducedAt,
        Instant deprecatedAt,
        Instant disabledAt,
        Set<String> keyConstraints,
        Set<String> compatibleWith,
        boolean productionWriteAllowed,
        boolean transitionRequiresReencryption
) {
}
