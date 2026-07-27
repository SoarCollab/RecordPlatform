package cn.flying.dao.vo.admin;

import java.util.List;

/**
 * Sanitized administrator diagnostics for suites, providers, and the effective tenant policy.
 */
public record CryptoAgilityDiagnosticsVO(
        CryptoAgilityPolicyVO policy,
        List<CryptoSuiteCatalogEntryVO> suites,
        List<CryptoProviderCapabilityVO> wrappingProviders,
        List<CryptoProviderCapabilityVO> signingProviders
) {
}
