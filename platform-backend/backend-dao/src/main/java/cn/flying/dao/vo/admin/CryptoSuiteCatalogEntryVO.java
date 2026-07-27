package cn.flying.dao.vo.admin;

import java.time.Instant;
import java.util.Set;

/**
 * Sanitized runtime crypto suite catalog entry.
 */
public record CryptoSuiteCatalogEntryVO(
        String id,
        String type,
        String providerId,
        int providerContractVersion,
        String status,
        Instant introducedAt,
        Instant deprecatedAt,
        Instant disabledAt,
        Set<String> keyConstraints,
        Set<String> compatibleWith,
        boolean productionWriteAllowed,
        boolean transitionRequiresReencryption
) {
}
