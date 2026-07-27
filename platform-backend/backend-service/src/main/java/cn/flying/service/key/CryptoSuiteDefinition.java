package cn.flying.service.key;

import java.time.Instant;
import java.util.Set;

/**
 * Immutable catalog entry for one implemented or explicitly non-implemented cryptographic suite.
 *
 * @param id stable persisted identifier
 * @param type non-interchangeable usage category
 * @param providerId implementation provider identifier
 * @param providerContractVersion implementation provider contract
 * @param status baseline lifecycle status
 * @param introducedAt first supported instant
 * @param deprecatedAt optional write-deprecation instant
 * @param disabledAt optional read-disable instant
 * @param keyConstraints sanitized constraint names
 * @param compatibleWith suites reachable without changing protected content bytes
 * @param productionWriteAllowed whether production may select the suite for new writes
 * @param transitionRequiresReencryption whether changing away from this suite changes content bytes
 */
public record CryptoSuiteDefinition(
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

    /**
     * Freezes collection fields so registry callers cannot mutate the catalog.
     */
    public CryptoSuiteDefinition {
        keyConstraints = keyConstraints == null ? Set.of() : Set.copyOf(keyConstraints);
        compatibleWith = compatibleWith == null ? Set.of() : Set.copyOf(compatibleWith);
    }
}
