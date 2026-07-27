package cn.flying.service.key;

import cn.flying.common.exception.GeneralException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyWrappingConfigurationValidatorTest {

    /**
     * 验证空 active provider 在 registry 启动阶段直接失败。
     */
    @Test
    void shouldRejectBlankActiveProviderAtStartup() {
        FileKeyEnvelopeProperties properties = localProperties("dedicated-local-key-value-1234567890");
        properties.setActiveProvider(" ");

        assertThatThrownBy(() -> registry(properties, new LocalKeyWrappingService(properties)))
                .isInstanceOf(GeneralException.class);
    }

    /**
     * 验证生产 local provider 缺少独立 master key 时启动失败。
     */
    @Test
    void shouldRejectMissingProductionLocalMasterKey() {
        FileKeyEnvelopeProperties properties = new FileKeyEnvelopeProperties();
        KeyWrappingProviderRegistry registry = registry(properties, new LocalKeyWrappingService(properties));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> new KeyWrappingConfigurationValidator(
                properties, registry, environment).afterPropertiesSet())
                .isInstanceOf(GeneralException.class);
    }

    /**
     * 验证生产 local provider 禁止复用 JWT key。
     */
    @Test
    void shouldRejectProductionJwtKeyReuse() {
        FileKeyEnvelopeProperties properties = localProperties("same-production-secret-key-value");
        KeyWrappingProviderRegistry registry = registry(properties, new LocalKeyWrappingService(properties));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.security.jwt.key", "same-production-secret-key-value");
        environment.setActiveProfiles("prod");

        KeyWrappingConfigurationValidator validator = new KeyWrappingConfigurationValidator(
                properties, registry, environment);

        assertThatThrownBy(validator::afterPropertiesSet).isInstanceOf(GeneralException.class);
    }

    /**
     * 验证生产 local provider 接受独立高熵 master key。
     */
    @Test
    void shouldAcceptDedicatedProductionLocalKey() {
        FileKeyEnvelopeProperties properties = localProperties(
                "dedicated-production-master-key-value-123456");
        KeyWrappingProviderRegistry registry = registry(properties, new LocalKeyWrappingService(properties));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.security.jwt.key", "different-production-jwt-key-value-123456");
        environment.setActiveProfiles("prod");

        new KeyWrappingConfigurationValidator(properties, registry, environment).afterPropertiesSet();
    }

    /**
     * 验证生产 Vault provider 拒绝 HTTP 地址，即使配置允许非生产 HTTP。
     */
    @Test
    void shouldRejectProductionVaultHttp() {
        FileKeyEnvelopeProperties properties = localProperties("unused-local-key-value-1234567890");
        properties.setActiveProvider(VaultTransitKeyWrappingProvider.PROVIDER_ID);
        FileKeyEnvelopeProperties.VaultTransit vault = properties.getProviders().getVaultTransit();
        vault.setAddress("http://vault.internal:8200");
        vault.setAllowHttp(true);
        vault.setToken("vault-token");
        vault.setKeyName("file-key");
        KeyWrappingProviderRegistry registry = registry(properties, new DiagnosticsProvider(
                VaultTransitKeyWrappingProvider.PROVIDER_ID, true));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> new KeyWrappingConfigurationValidator(
                properties, registry, environment).afterPropertiesSet())
                .isInstanceOf(GeneralException.class);
    }

    /**
     * 验证生产 Vault provider 缺少 token 时在启动阶段失败。
     */
    @Test
    void shouldRejectMissingProductionVaultToken() {
        FileKeyEnvelopeProperties properties = localProperties("unused-local-key-value-1234567890");
        properties.setActiveProvider(VaultTransitKeyWrappingProvider.PROVIDER_ID);
        FileKeyEnvelopeProperties.VaultTransit vault = properties.getProviders().getVaultTransit();
        vault.setAddress("https://vault.internal:8200");
        vault.setKeyName("file-key");
        vault.setToken(null);
        VaultTransitTransport transport = (uri, headers, body, timeout, maxResponseBytes) ->
                VaultTransitHttpResult.failure(KeyWrappingFailure.of(
                        KeyWrappingFailureCategory.UNAVAILABLE, true));
        KeyWrappingProviderRegistry registry = registry(properties,
                new VaultTransitKeyWrappingProvider(properties, transport, new ObjectMapper()));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> new KeyWrappingConfigurationValidator(
                properties, registry, environment).afterPropertiesSet())
                .isInstanceOf(GeneralException.class);
    }

    private FileKeyEnvelopeProperties localProperties(String masterKey) {
        FileKeyEnvelopeProperties properties = new FileKeyEnvelopeProperties();
        properties.setLocalMasterKey(masterKey);
        return properties;
    }

    private KeyWrappingProviderRegistry registry(FileKeyEnvelopeProperties properties,
                                                 KeyWrappingProvider provider) {
        return new KeyWrappingProviderRegistry(List.of(provider), properties, new SimpleMeterRegistry());
    }

    private record DiagnosticsProvider(String providerId, boolean available) implements KeyWrappingProvider {

        @Override
        public int contractVersion() {
            return 1;
        }

        @Override
        public Set<KeyWrappingCapability> capabilities() {
            return Set.of(KeyWrappingCapability.WRAP, KeyWrappingCapability.UNWRAP);
        }

        @Override
        public Set<String> supportedWrappingAlgorithms() {
            return Set.of("TEST");
        }

        @Override
        public KeyWrappingResult<WrappingKeyReference> activeKeyReference(Integer logicalKeyVersion) {
            return KeyWrappingResult.success(new WrappingKeyReference(
                    providerId, 1, "key", "1", "TEST", WrappingContext.EXTERNAL_CONTEXT_V2));
        }

        @Override
        public KeyWrappingResult<WrappedDataKey> wrap(KeyWrapRequest request) {
            return KeyWrappingResult.failure(KeyWrappingFailure.of(KeyWrappingFailureCategory.UNSUPPORTED, false));
        }

        @Override
        public KeyWrappingResult<PlaintextDataKey> unwrap(KeyUnwrapRequest request) {
            return KeyWrappingResult.failure(KeyWrappingFailure.of(KeyWrappingFailureCategory.UNSUPPORTED, false));
        }

        @Override
        public KeyWrappingResult<WrappedDataKey> rewrap(KeyRewrapRequest request) {
            return KeyWrappingResult.failure(KeyWrappingFailure.of(KeyWrappingFailureCategory.UNSUPPORTED, false));
        }

        @Override
        public KeyWrappingProviderDiagnostics diagnostics() {
            return new KeyWrappingProviderDiagnostics(
                    providerId, 1, capabilities(), available,
                    available ? "configured" : "invalid_configuration");
        }
    }
}
