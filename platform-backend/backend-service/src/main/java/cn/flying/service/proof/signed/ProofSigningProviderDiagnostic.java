package cn.flying.service.proof.signed;

import java.util.Set;

/**
 * Sanitized proof-signing provider capability view.
 */
public record ProofSigningProviderDiagnostic(
        String providerId,
        int contractVersion,
        String signatureSuite,
        Set<String> proofSuites,
        boolean available,
        String configurationState
) {

    /**
     * Freezes provider-declared proof suites before exposing diagnostics.
     */
    public ProofSigningProviderDiagnostic {
        proofSuites = proofSuites == null ? Set.of() : Set.copyOf(proofSuites);
    }
}
