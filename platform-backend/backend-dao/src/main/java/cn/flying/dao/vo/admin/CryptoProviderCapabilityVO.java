package cn.flying.dao.vo.admin;

import java.util.Set;

/**
 * Sanitized runtime cryptographic provider capability declaration.
 */
public record CryptoProviderCapabilityVO(
        String providerType,
        String providerId,
        int contractVersion,
        Set<String> capabilities,
        Set<String> suites,
        Boolean available,
        String configurationState
) {
}
