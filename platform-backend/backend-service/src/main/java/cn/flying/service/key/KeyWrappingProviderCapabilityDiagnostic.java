package cn.flying.service.key;

import java.util.Set;

/**
 * Sanitized wrapping provider operations and exact algorithm capabilities.
 */
public record KeyWrappingProviderCapabilityDiagnostic(
        String providerId,
        int contractVersion,
        Set<KeyWrappingCapability> capabilities,
        Set<String> wrappingAlgorithms,
        boolean available,
        String configurationState
) {
}
