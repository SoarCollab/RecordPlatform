package cn.flying.service.proof.signed;

import java.util.Set;

/**
 * Versioned signing provider SPI behind the tenant-aware runtime dispatcher.
 */
public interface ProofSigningProviderAdapter extends ProofSigningProvider {

    /**
     * Returns the stable provider identifier persisted with proof records.
     */
    String providerId();

    /**
     * Returns the provider contract version persisted with proof records.
     */
    int contractVersion();

    /**
     * Returns the exact signature suite implemented by this contract.
     */
    String signatureSuite();

    /**
     * Returns the proof formats this provider can sign and verify.
     */
    Set<String> proofSuites();

    /**
     * Returns sanitized runtime readiness without exposing key identifiers or key material.
     */
    ProofSigningProviderDiagnostic diagnostics();
}
