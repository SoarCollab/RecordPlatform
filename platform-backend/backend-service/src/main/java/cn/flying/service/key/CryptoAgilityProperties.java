package cn.flying.service.key;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operator-controlled defaults and lifecycle tightening for the closed crypto suite catalog.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "crypto.agility")
public class CryptoAgilityProperties {

    private boolean productionMode = true;

    private boolean allowExperimentalWrites;

    private String signingProvider = CryptoSuiteIds.LOCAL_ED25519_PROVIDER;

    private Integer signingProviderContractVersion = CryptoSuiteIds.PROVIDER_CONTRACT_V1;

    private String signedProofSignatureSuite = CryptoSuiteIds.ED25519_JWS_V1;

    private String signedProofSuite = CryptoSuiteIds.SIGNED_PROOF_ZIP_V2;

    private Map<String, Lifecycle> suiteLifecycle = new LinkedHashMap<>();

    /**
     * Optional lifecycle override for a known built-in suite.
     */
    @Getter
    @Setter
    public static class Lifecycle {

        private CryptoSuiteStatus status;

        private Instant deprecatedAt;

        private Instant disabledAt;
    }
}
