package cn.flying.service.proof.signed;

import cn.flying.common.exception.GeneralException;
import cn.flying.service.key.CryptoAgilityProperties;
import cn.flying.service.key.CryptoSuiteIds;
import cn.flying.service.key.CryptoSuitePolicyService;
import cn.flying.service.key.CryptoSuitePolicySnapshot;
import cn.flying.service.key.CryptoSuiteRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises proof-signing selection and exact historical verification routing.
 */
class ProofSigningProviderRegistryTest {

    private CryptoSuitePolicyService policyService;
    private LocalEd25519ProofSigningProvider localProvider;
    private ProofSigningProviderRegistry registry;

    /**
     * Creates a real Ed25519 provider while keeping tenant policy decisions explicit.
     */
    @BeforeEach
    void setUp() throws Exception {
        policyService = mock(CryptoSuitePolicyService.class);
        localProvider = new LocalEd25519ProofSigningProvider(
                signingProperties(), new ProofCanonicalizer());
        CryptoAgilityProperties agilityProperties = new CryptoAgilityProperties();
        registry = new ProofSigningProviderRegistry(
                List.of(localProvider),
                policyService,
                new CryptoSuiteRegistry(agilityProperties, new SimpleMeterRegistry()),
                new SimpleMeterRegistry());
    }

    /**
     * Proves current issuance dispatches through the tenant-selected real Ed25519 capability.
     */
    @Test
    void shouldDispatchCurrentSigningThroughSelectedProviderAndSuites() {
        when(policyService.currentPolicy()).thenReturn(localPolicy());
        byte[] manifest = "canonical-manifest".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        registry.requireExportEnabled();
        registry.validateConfiguration();
        ProofSigningKeyMetadata key = registry.currentKey();
        ProofSignature signature = registry.sign(manifest, key);

        assertThat(key.providerId()).isEqualTo(CryptoSuiteIds.LOCAL_ED25519_PROVIDER);
        assertThat(key.signatureSuite()).isEqualTo(CryptoSuiteIds.ED25519_JWS_V1);
        assertThat(registry.verify(manifest, signature.compactJws(), key)).isTrue();
    }

    /**
     * Proves null persisted identities and provider-returned identity drift fail before signing is accepted.
     */
    @Test
    void shouldRejectNullAndProviderMismatchedSigningResults() {
        when(policyService.currentPolicy()).thenReturn(localPolicy());
        assertThatThrownBy(() -> registry.sign(new byte[]{1}, null))
                .isInstanceOf(GeneralException.class);

        ProofSigningProviderAdapter provider = mock(ProofSigningProviderAdapter.class);
        when(provider.providerId()).thenReturn(CryptoSuiteIds.LOCAL_ED25519_PROVIDER);
        when(provider.contractVersion()).thenReturn(1);
        when(provider.signatureSuite()).thenReturn(CryptoSuiteIds.ED25519_JWS_V1);
        when(provider.proofSuites()).thenReturn(Set.of(CryptoSuiteIds.SIGNED_PROOF_ZIP_V2));
        ProofSigningKeyMetadata valid = localProvider.currentKey();
        ProofSigningKeyMetadata mismatched = new ProofSigningKeyMetadata(
                "other", 1, valid.signatureSuite(), valid.proofSuite(), valid.algorithm(),
                valid.keyId(), valid.keyVersion(), valid.publicKeySpki(), valid.publicKeyFingerprint());
        when(provider.currentKey()).thenReturn(mismatched);
        when(provider.sign(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ProofSignature("jws", mismatched));
        ProofSigningProviderRegistry mismatchedRegistry = new ProofSigningProviderRegistry(
                List.of(provider), policyService,
                new CryptoSuiteRegistry(new CryptoAgilityProperties(), new SimpleMeterRegistry()),
                new SimpleMeterRegistry());

        assertThatThrownBy(mismatchedRegistry::currentKey).isInstanceOf(GeneralException.class);
        assertThatThrownBy(() -> mismatchedRegistry.sign(new byte[]{1}, valid))
                .isInstanceOf(GeneralException.class);
    }

    /**
     * Proves changing the current default cannot reinterpret an already persisted proof identity.
     */
    @Test
    void shouldVerifyHistoricalProofAfterCurrentDefaultDrifts() {
        when(policyService.currentPolicy()).thenReturn(localPolicy());
        byte[] manifest = "historical-manifest".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ProofSigningKeyMetadata persisted = registry.currentKey();
        ProofSignature signature = registry.sign(manifest, persisted);
        when(policyService.currentPolicy()).thenReturn(new CryptoSuitePolicySnapshot(
                7L, 2L,
                CryptoSuiteIds.LEGACY_CHUNK_CHAIN,
                CryptoSuiteIds.UNSIGNED_V1,
                CryptoSuiteIds.NO_KEM_V1,
                CryptoSuiteIds.MERKLE_SHA256_V1,
                "local",
                1,
                CryptoSuiteIds.ED25519_JWS_V1,
                CryptoSuiteIds.SIGNED_PROOF_ZIP_V2,
                "missing-future-provider",
                2));

        assertThat(registry.verify(manifest, signature.compactJws(), persisted)).isTrue();
        assertThatThrownBy(registry::currentKey).isInstanceOf(GeneralException.class);
    }

    /**
     * Proves unknown or mismatched persisted identities fail closed without trying another provider.
     */
    @Test
    void shouldRejectUnknownAndMismatchedHistoricalIdentityWithoutFallback() {
        when(policyService.currentPolicy()).thenReturn(localPolicy());
        byte[] manifest = "historical-manifest".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ProofSigningKeyMetadata key = registry.currentKey();
        ProofSignature signature = registry.sign(manifest, key);
        ProofSigningKeyMetadata unknownProvider = new ProofSigningKeyMetadata(
                "unknown", 9, key.signatureSuite(), key.proofSuite(), key.algorithm(),
                key.keyId(), key.keyVersion(), key.publicKeySpki(), key.publicKeyFingerprint());
        ProofSigningKeyMetadata mismatchedSuite = new ProofSigningKeyMetadata(
                key.providerId(), key.providerContractVersion(), CryptoSuiteIds.UNSIGNED_V1,
                key.proofSuite(), key.algorithm(), key.keyId(), key.keyVersion(),
                key.publicKeySpki(), key.publicKeyFingerprint());

        assertThat(registry.verify(manifest, signature.compactJws(), unknownProvider)).isFalse();
        assertThat(registry.verify(manifest, signature.compactJws(), mismatchedSuite)).isFalse();
    }

    /**
     * Proves duplicate provider contracts stop registry construction deterministically.
     */
    @Test
    void shouldRejectDuplicateProviderContract() {
        CryptoAgilityProperties agilityProperties = new CryptoAgilityProperties();

        assertThatThrownBy(() -> new ProofSigningProviderRegistry(
                List.of(localProvider, localProvider),
                policyService,
                new CryptoSuiteRegistry(agilityProperties, new SimpleMeterRegistry()),
                new SimpleMeterRegistry()))
                .isInstanceOf(GeneralException.class);
    }

    /**
     * Proves signing diagnostics report executable readiness without publishing key identity or material.
     */
    @Test
    void shouldExposeSanitizedExecutableProviderDiagnostics() {
        ProofSigningProviderDiagnostic diagnostic = registry.diagnostics().getFirst();

        assertThat(diagnostic.available()).isTrue();
        assertThat(diagnostic.configurationState()).isEqualTo("configured");
        assertThat(diagnostic.toString())
                .doesNotContain("runtime-agility-test-key", "PRIVATE", "PUBLIC");
    }

    /**
     * Builds the valid current tenant policy used by new proof issuance.
     */
    private CryptoSuitePolicySnapshot localPolicy() {
        return new CryptoSuitePolicySnapshot(
                7L, 1L,
                CryptoSuiteIds.LEGACY_CHUNK_CHAIN,
                CryptoSuiteIds.UNSIGNED_V1,
                CryptoSuiteIds.NO_KEM_V1,
                CryptoSuiteIds.MERKLE_SHA256_V1,
                "local",
                1,
                CryptoSuiteIds.ED25519_JWS_V1,
                CryptoSuiteIds.SIGNED_PROOF_ZIP_V2,
                CryptoSuiteIds.LOCAL_ED25519_PROVIDER,
                1);
    }

    /**
     * Generates an isolated valid Ed25519 key pair for executable provider tests.
     */
    private ProofSigningProperties signingProperties() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        ProofSigningProperties properties = new ProofSigningProperties();
        properties.setEnabled(true);
        properties.setAlgorithm("Ed25519");
        properties.setKeyId("runtime-agility-test-key");
        properties.setKeyVersion(1);
        properties.setKeyStatus("ACTIVE");
        properties.setPrivateKeyPkcs8(Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
        properties.setPublicKeySpki(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        return properties;
    }
}
