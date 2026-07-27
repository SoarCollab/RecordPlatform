package cn.flying.service.key;

import cn.flying.service.proof.signed.ProofSigningProviderRegistry;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Fails application startup before runtime registries are usable when defaults and capabilities disagree.
 */
@Component
public class CryptoAgilityConfigurationValidator implements SmartInitializingSingleton {

    private final CryptoSuitePolicyService policyService;
    private final ProofSigningProviderRegistry signingProviderRegistry;

    /**
     * Creates the startup validator from the two executable provider boundaries.
     */
    public CryptoAgilityConfigurationValidator(CryptoSuitePolicyService policyService,
                                               ProofSigningProviderRegistry signingProviderRegistry) {
        this.policyService = policyService;
        this.signingProviderRegistry = signingProviderRegistry;
    }

    /**
     * Validates global defaults and declared capabilities after all singleton providers are registered.
     */
    @Override
    public void afterSingletonsInstantiated() {
        policyService.validateDefaults();
        signingProviderRegistry.validateConfiguration();
    }
}
