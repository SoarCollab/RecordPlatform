package cn.flying.service.key;

import cn.flying.service.proof.signed.ProofSigningProviderRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Verifies that startup validates every executable crypto-agility boundary and fails closed.
 */
class CryptoAgilityConfigurationValidatorTest {

    /**
     * Proves global suite defaults are validated before signing provider capabilities.
     */
    @Test
    void shouldValidateDefaultsAndSigningCapabilitiesInOrder() {
        CryptoSuitePolicyService policyService = mock(CryptoSuitePolicyService.class);
        ProofSigningProviderRegistry signingRegistry = mock(ProofSigningProviderRegistry.class);
        CryptoAgilityConfigurationValidator validator = new CryptoAgilityConfigurationValidator(
                policyService, signingRegistry);

        validator.afterSingletonsInstantiated();

        var ordered = inOrder(policyService, signingRegistry);
        ordered.verify(policyService).validateDefaults();
        ordered.verify(signingRegistry).validateConfiguration();
    }

    /**
     * Proves an invalid suite or wrapping default aborts startup before signing validation.
     */
    @Test
    void shouldFailStartupImmediatelyForInvalidDefaults() {
        CryptoSuitePolicyService policyService = mock(CryptoSuitePolicyService.class);
        ProofSigningProviderRegistry signingRegistry = mock(ProofSigningProviderRegistry.class);
        RuntimeException failure = new IllegalStateException("invalid crypto defaults");
        org.mockito.Mockito.doThrow(failure).when(policyService).validateDefaults();
        CryptoAgilityConfigurationValidator validator = new CryptoAgilityConfigurationValidator(
                policyService, signingRegistry);

        assertThatThrownBy(validator::afterSingletonsInstantiated).isSameAs(failure);

        verifyNoInteractions(signingRegistry);
    }

    /**
     * Proves an incompatible signing provider capability also aborts application startup.
     */
    @Test
    void shouldFailStartupForInvalidSigningCapability() {
        CryptoSuitePolicyService policyService = mock(CryptoSuitePolicyService.class);
        ProofSigningProviderRegistry signingRegistry = mock(ProofSigningProviderRegistry.class);
        RuntimeException failure = new IllegalStateException("invalid signing capability");
        org.mockito.Mockito.doThrow(failure).when(signingRegistry).validateConfiguration();
        CryptoAgilityConfigurationValidator validator = new CryptoAgilityConfigurationValidator(
                policyService, signingRegistry);

        assertThatThrownBy(validator::afterSingletonsInstantiated).isSameAs(failure);
    }
}
