package cn.flying.service.proof.signed;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.service.key.CryptoSuiteFailureReason;
import cn.flying.service.key.CryptoSuitePolicyService;
import cn.flying.service.key.CryptoSuitePolicySnapshot;
import cn.flying.service.key.CryptoSuiteRegistry;
import cn.flying.service.key.CryptoSuiteType;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tenant-aware proof-signing dispatcher that routes historical verification by persisted identity.
 */
@Primary
@Service
public class ProofSigningProviderRegistry implements ProofSigningProvider {

    private static final String DECISION_METRIC = "app.crypto.proof_signing.decision";

    private final Map<ProviderKey, ProofSigningProviderAdapter> providers;
    private final CryptoSuitePolicyService policyService;
    private final CryptoSuiteRegistry suiteRegistry;
    private final MeterRegistry meterRegistry;

    /**
     * Registers versioned provider adapters and rejects duplicate or invalid identities.
     */
    public ProofSigningProviderRegistry(List<ProofSigningProviderAdapter> providerList,
                                        CryptoSuitePolicyService policyService,
                                        CryptoSuiteRegistry suiteRegistry,
                                        MeterRegistry meterRegistry) {
        this.policyService = policyService;
        this.suiteRegistry = suiteRegistry;
        this.meterRegistry = meterRegistry;
        Map<ProviderKey, ProofSigningProviderAdapter> discovered = new LinkedHashMap<>();
        for (ProofSigningProviderAdapter provider : providerList) {
            ProviderKey key = new ProviderKey(provider.providerId(), provider.contractVersion());
            if (!StringUtils.hasText(key.providerId()) || key.contractVersion() <= 0
                    || !StringUtils.hasText(provider.signatureSuite())
                    || provider.proofSuites() == null || provider.proofSuites().isEmpty()
                    || discovered.putIfAbsent(key, provider) != null) {
                throw failure(CryptoSuiteFailureReason.CAPABILITY_MISMATCH, false);
            }
        }
        this.providers = Map.copyOf(discovered);
    }

    /**
     * Enforces the global export switch through the tenant-selected provider.
     */
    @Override
    public void requireExportEnabled() {
        CryptoSuitePolicySnapshot policy = policyService.currentPolicy();
        requireProvider(policy.signingProvider(), policy.signingProviderContract())
                .requireExportEnabled();
    }

    /**
     * Returns current public key metadata bound to the tenant-selected provider and suites.
     */
    @Override
    public ProofSigningKeyMetadata currentKey() {
        CryptoSuitePolicySnapshot policy = policyService.currentPolicy();
        ProofSigningProviderAdapter provider = requireProvider(
                policy.signingProvider(), policy.signingProviderContract());
        validateWriteSuites(provider, policy.signedProofSignatureSuite(), policy.signedProofSuite());
        ProofSigningKeyMetadata key = provider.currentKey();
        if (!matches(provider, key, policy.signedProofSignatureSuite(), policy.signedProofSuite())) {
            throw failure(CryptoSuiteFailureReason.CAPABILITY_MISMATCH, false);
        }
        record("current", "success", CryptoSuiteFailureReason.NONE);
        return key;
    }

    /**
     * Routes signing by the provider and suite identity frozen in the expected key metadata.
     */
    @Override
    public ProofSignature sign(byte[] manifest, ProofSigningKeyMetadata expectedKey) {
        ProofSigningProviderAdapter provider = requireProvider(expectedKey);
        validateWriteSuites(provider, expectedKey.signatureSuite(), expectedKey.proofSuite());
        ProofSignature signature = provider.sign(manifest, expectedKey);
        if (signature == null || !expectedKey.equals(signature.key())) {
            throw failure(CryptoSuiteFailureReason.CAPABILITY_MISMATCH, false);
        }
        record("sign", "success", CryptoSuiteFailureReason.NONE);
        return signature;
    }

    /**
     * Routes historical verification strictly by persisted provider, contract, and suites.
     */
    @Override
    public boolean verify(byte[] manifest, String compactJws, ProofSigningKeyMetadata expectedKey) {
        try {
            ProofSigningProviderAdapter provider = requireProvider(expectedKey);
            suiteRegistry.requireForRead(CryptoSuiteType.SIGNATURE,
                    expectedKey.signatureSuite(), expectedKey.providerId(), expectedKey.providerContractVersion());
            suiteRegistry.requireForRead(CryptoSuiteType.PROOF,
                    expectedKey.proofSuite(), expectedKey.providerId(), expectedKey.providerContractVersion());
            if (!matches(provider, expectedKey, expectedKey.signatureSuite(), expectedKey.proofSuite())) {
                record("verify", "failure", CryptoSuiteFailureReason.CAPABILITY_MISMATCH);
                return false;
            }
            boolean valid = provider.verify(manifest, compactJws, expectedKey);
            record("verify", valid ? "success" : "failure",
                    valid ? CryptoSuiteFailureReason.NONE : CryptoSuiteFailureReason.CAPABILITY_MISMATCH);
            return valid;
        } catch (GeneralException exception) {
            return false;
        }
    }

    /**
     * Validates configured provider capability declarations without loading private key material.
     */
    public void validateConfiguration() {
        CryptoSuitePolicySnapshot policy = policyService.currentPolicy();
        ProofSigningProviderAdapter provider = requireProvider(
                policy.signingProvider(), policy.signingProviderContract());
        validateWriteSuites(provider, policy.signedProofSignatureSuite(), policy.signedProofSuite());
    }

    /**
     * Returns sanitized provider capabilities for administrator diagnostics.
     */
    public List<ProofSigningProviderDiagnostic> diagnostics() {
        return providers.values().stream()
                .map(ProofSigningProviderAdapter::diagnostics)
                .toList();
    }

    /**
     * Requires an exact provider identity from persisted key metadata.
     */
    private ProofSigningProviderAdapter requireProvider(ProofSigningKeyMetadata key) {
        if (key == null || key.providerContractVersion() == null) {
            throw failure(CryptoSuiteFailureReason.PROVIDER_MISMATCH, true);
        }
        return requireProvider(key.providerId(), key.providerContractVersion());
    }

    /**
     * Requires an exact registered provider contract without fallback.
     */
    private ProofSigningProviderAdapter requireProvider(String providerId, int contractVersion) {
        ProofSigningProviderAdapter provider = providers.get(new ProviderKey(providerId, contractVersion));
        if (provider == null) {
            throw failure(CryptoSuiteFailureReason.PROVIDER_MISMATCH, false);
        }
        return provider;
    }

    /**
     * Requires suite catalog and provider declarations to agree for a new signature.
     */
    private void validateWriteSuites(ProofSigningProviderAdapter provider,
                                     String signatureSuite,
                                     String proofSuite) {
        suiteRegistry.requireForWrite(CryptoSuiteType.SIGNATURE,
                signatureSuite, provider.providerId(), provider.contractVersion());
        suiteRegistry.requireForWrite(CryptoSuiteType.PROOF,
                proofSuite, provider.providerId(), provider.contractVersion());
        if (!Objects.equals(provider.signatureSuite(), signatureSuite)
                || !provider.proofSuites().contains(proofSuite)) {
            throw failure(CryptoSuiteFailureReason.CAPABILITY_MISMATCH, false);
        }
    }

    /**
     * Compares provider declarations with complete persisted public key routing metadata.
     */
    private boolean matches(ProofSigningProviderAdapter provider,
                            ProofSigningKeyMetadata key,
                            String signatureSuite,
                            String proofSuite) {
        return key != null
                && Objects.equals(provider.providerId(), key.providerId())
                && Objects.equals(provider.contractVersion(), key.providerContractVersion())
                && Objects.equals(signatureSuite, key.signatureSuite())
                && Objects.equals(proofSuite, key.proofSuite())
                && Objects.equals(provider.signatureSuite(), key.signatureSuite())
                && provider.proofSuites().contains(key.proofSuite());
    }

    /**
     * Creates a sanitized failure without exposing provider key material.
     */
    private GeneralException failure(CryptoSuiteFailureReason reason, boolean historicalRead) {
        record(historicalRead ? "read" : "write", "failure", reason);
        return new GeneralException(
                historicalRead ? ResultEnum.FILE_RECORD_ERROR : ResultEnum.PERMISSION_SIGNATURE_ERROR,
                Map.of("reason", reason.name()));
    }

    /**
     * Records only closed decision values to prevent metric cardinality growth.
     */
    private void record(String operation, String outcome, CryptoSuiteFailureReason reason) {
        meterRegistry.counter(DECISION_METRIC,
                "operation", operation,
                "outcome", outcome,
                "reason", reason.name()).increment();
    }

    private record ProviderKey(String providerId, int contractVersion) {
    }
}
