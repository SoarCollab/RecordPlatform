package cn.flying.service.key;

import cn.flying.common.exception.GeneralException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyWrappingProviderRegistryTest {

    /**
     * 验证 local AAD v1 字节顺序永久保持不变。
     */
    @Test
    void shouldPreserveLegacyLocalAadBytes() {
        WrappingContext context = context(WrappingContext.LOCAL_AAD_V1, 1);

        assertThat(new String(context.canonicalBytes(), StandardCharsets.UTF_8))
                .isEqualTo("7|11|hash-1|OWNER|13|1|RP-AES256-GCM-CHUNK-CHAIN-V1");
    }

    /**
     * 验证 external v2 context 不随逻辑版本变化，provider 版本由 ciphertext 认证。
     */
    @Test
    void shouldKeepExternalContextStableAcrossLogicalRotation() {
        WrappingContext source = context(WrappingContext.EXTERNAL_CONTEXT_V2, 1);
        WrappingContext target = context(WrappingContext.EXTERNAL_CONTEXT_V2, 2);

        assertThat(MessageDigest.isEqual(source.canonicalBytes(), target.canonicalBytes())).isTrue();
        assertThat(new String(source.canonicalBytes(), StandardCharsets.UTF_8))
                .doesNotContain("|1|")
                .contains("rp-file-envelope-context-v2");
    }

    /**
     * 验证同 provider id 的多个 contract 可共存，并按显式 active version 精确选择。
     */
    @Test
    void shouldResolveExplicitActiveContractVersion() {
        FileKeyEnvelopeProperties properties = new FileKeyEnvelopeProperties();
        properties.setActiveProvider("test-provider");
        properties.setActiveProviderContractVersion(2);
        TrackingProvider v1 = new TrackingProvider("test-provider", 1);
        TrackingProvider v2 = new TrackingProvider("test-provider", 2);

        KeyWrappingProviderRegistry registry = new KeyWrappingProviderRegistry(
                List.of(v1, v2), properties, new SimpleMeterRegistry());

        assertThat(registry.activeKeyReference(3).requireValue().providerContractVersion()).isEqualTo(2);
    }

    /**
     * 验证重复复合身份在启动阶段失败。
     */
    @Test
    void shouldRejectDuplicateProviderContract() {
        FileKeyEnvelopeProperties properties = new FileKeyEnvelopeProperties();
        properties.setActiveProvider("test-provider");

        assertThatThrownBy(() -> new KeyWrappingProviderRegistry(
                List.of(new TrackingProvider("test-provider", 1), new TrackingProvider("test-provider", 1)),
                properties,
                new SimpleMeterRegistry()))
                .isInstanceOf(GeneralException.class);
    }

    /**
     * 验证 provider 必须声明非空的闭集算法能力，不能通过空集合充当通配符。
     */
    @Test
    void shouldRejectProviderWithoutDeclaredAlgorithms() {
        FileKeyEnvelopeProperties properties = new FileKeyEnvelopeProperties();
        properties.setActiveProvider("test-provider");

        assertThatThrownBy(() -> new KeyWrappingProviderRegistry(
                List.of(new TrackingProvider("test-provider", 1, Set.of())),
                properties,
                new SimpleMeterRegistry()))
                .isInstanceOf(GeneralException.class);
    }

    /**
     * 验证未知历史 provider 失败关闭且不会路由 active provider。
     */
    @Test
    void shouldFailClosedForUnknownPersistedProvider() {
        FileKeyEnvelopeProperties properties = new FileKeyEnvelopeProperties();
        properties.setActiveProvider("test-provider");
        TrackingProvider provider = new TrackingProvider("test-provider", 1);
        KeyWrappingProviderRegistry registry = new KeyWrappingProviderRegistry(
                List.of(provider), properties, new SimpleMeterRegistry());
        WrappingKeyReference unknown = new WrappingKeyReference(
                "unknown", 1, "key", "1", "TEST", WrappingContext.LOCAL_AAD_V1);

        KeyWrappingResult<PlaintextDataKey> result = registry.unwrap(new KeyUnwrapRequest(
                new PersistedWrappedDataKey("cipher", "iv", unknown, 1),
                context(WrappingContext.LOCAL_AAD_V1, 1)));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.failure().category()).isEqualTo(KeyWrappingFailureCategory.CONFIGURATION);
        assertThat(provider.unwrapCalls).isZero();
    }

    /**
     * 验证已知 provider 的未知历史 contract version 同样严格失败关闭。
     */
    @Test
    void shouldFailClosedForUnknownPersistedContractVersion() {
        FileKeyEnvelopeProperties properties = new FileKeyEnvelopeProperties();
        properties.setActiveProvider("test-provider");
        TrackingProvider provider = new TrackingProvider("test-provider", 1);
        KeyWrappingProviderRegistry registry = new KeyWrappingProviderRegistry(
                List.of(provider), properties, new SimpleMeterRegistry());
        WrappingKeyReference unknownVersion = new WrappingKeyReference(
                "test-provider", 99, "key", "1", "TEST", WrappingContext.LOCAL_AAD_V1);

        KeyWrappingResult<PlaintextDataKey> result = registry.unwrap(new KeyUnwrapRequest(
                new PersistedWrappedDataKey("cipher", "iv", unknownVersion, 1),
                context(WrappingContext.LOCAL_AAD_V1, 1)));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.failure().category()).isEqualTo(KeyWrappingFailureCategory.CONFIGURATION);
        assertThat(provider.unwrapCalls).isZero();
    }

    /**
     * 验证已知 provider 与 contract 也不能解封未声明的持久化算法。
     */
    @Test
    void shouldFailClosedForUnsupportedPersistedAlgorithm() {
        FileKeyEnvelopeProperties properties = new FileKeyEnvelopeProperties();
        properties.setActiveProvider("test-provider");
        TrackingProvider provider = new TrackingProvider("test-provider", 1);
        KeyWrappingProviderRegistry registry = new KeyWrappingProviderRegistry(
                List.of(provider), properties, new SimpleMeterRegistry());
        WrappingKeyReference unsupported = new WrappingKeyReference(
                "test-provider", 1, "key", "1", "UNDECLARED", WrappingContext.LOCAL_AAD_V1);

        KeyWrappingResult<PlaintextDataKey> result = registry.unwrap(new KeyUnwrapRequest(
                new PersistedWrappedDataKey("cipher", "iv", unsupported, 1),
                context(WrappingContext.LOCAL_AAD_V1, 1)));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.failure().category()).isEqualTo(KeyWrappingFailureCategory.CONFIGURATION);
        assertThat(provider.unwrapCalls).isZero();
    }

    /**
     * 验证 provider 指标只使用冻结的低基数标签且不包含 key id。
     */
    @Test
    void shouldRecordOnlyLowCardinalityMetricTags() {
        FileKeyEnvelopeProperties properties = new FileKeyEnvelopeProperties();
        properties.setActiveProvider("test-provider");
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        KeyWrappingProviderRegistry registry = new KeyWrappingProviderRegistry(
                List.of(new TrackingProvider("test-provider", 1)), properties, meterRegistry);

        registry.activeKeyReference(1).requireValue();

        assertThat(meterRegistry.getMeters()).hasSize(1);
        var meterId = meterRegistry.getMeters().getFirst().getId();
        assertThat(meterId.getTags().stream()
                .map(tag -> tag.getKey())
                .collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("provider", "operation", "outcome", "failure_category");
        assertThat(meterId.getTags().stream().map(tag -> tag.getValue()).toList())
                .doesNotContain("test-key");
    }

    /**
     * Proves every declared provider operation is routed by exact identity and exposed through sanitized diagnostics.
     */
    @Test
    void shouldRouteDeclaredCapabilitiesAndExposeSanitizedDiagnostics() {
        FileKeyEnvelopeProperties properties = new FileKeyEnvelopeProperties();
        properties.setActiveProvider("test-provider");
        TrackingProvider provider = new TrackingProvider("test-provider", 1, Set.of("TEST"), true);
        KeyWrappingProviderRegistry registry = new KeyWrappingProviderRegistry(
                List.of(provider), properties, new SimpleMeterRegistry());
        WrappingContext context = context(WrappingContext.LOCAL_AAD_V1, 1);
        WrappingKeyReference reference = registry.keyReference("test-provider", 1, 1).requireValue();
        KeyWrapRequest wrapRequest = new KeyWrapRequest(
                PlaintextDataKey.of("plain-key"), context, reference, 1);
        WrappedDataKey wrapped = registry.wrap(wrapRequest).requireValue();
        PersistedWrappedDataKey persisted = new PersistedWrappedDataKey(
                wrapped.encryptedDataKey(), wrapped.wrappingIv(), reference, 1);

        assertThat(registry.unwrap(new KeyUnwrapRequest(persisted, context)).requireValue().reveal())
                .isEqualTo("key");
        assertThat(registry.rewrap(new KeyRewrapRequest(
                persisted, context, reference, context, 2)).requireValue().keyVersion()).isEqualTo(2);
        assertThat(registry.supports("test-provider", 1, KeyWrappingCapability.WRAP, "TEST")).isTrue();
        assertThat(registry.supports("test-provider", 1, KeyWrappingCapability.UNWRAP, "UNKNOWN")).isFalse();
        assertThat(registry.activeProviderSupports(KeyWrappingCapability.NATIVE_REWRAP_SAME_KEY)).isTrue();
        assertThat(registry.activeDiagnostics().configurationState()).isEqualTo("configured");
        assertThat(registry.diagnostics()).singleElement().satisfies(diagnostic ->
                assertThat(diagnostic.providerId()).isEqualTo("test-provider"));
        assertThat(registry.capabilityDiagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.wrappingAlgorithms()).containsExactly("TEST");
            assertThat(diagnostic.capabilities()).contains(KeyWrappingCapability.NATIVE_REWRAP_SAME_KEY);
        });
        assertThat(provider.wrapCalls).isEqualTo(1);
        assertThat(provider.unwrapCalls).isEqualTo(1);
        assertThat(provider.rewrapCalls).isEqualTo(1);
    }

    /**
     * Proves null requests and cross-provider native rewrap attempts fail before invoking a provider.
     */
    @Test
    void shouldRejectMalformedRequestsAndCrossProviderRewrap() {
        FileKeyEnvelopeProperties properties = new FileKeyEnvelopeProperties();
        properties.setActiveProvider("test-provider");
        TrackingProvider provider = new TrackingProvider("test-provider", 1, Set.of("TEST"), true);
        KeyWrappingProviderRegistry registry = new KeyWrappingProviderRegistry(
                List.of(provider), properties, new SimpleMeterRegistry());
        WrappingContext context = context(WrappingContext.LOCAL_AAD_V1, 1);
        WrappingKeyReference sourceReference = provider.activeKeyReference(1).requireValue();
        PersistedWrappedDataKey source = new PersistedWrappedDataKey(
                "cipher", "iv", sourceReference, 1);
        WrappingKeyReference otherProvider = new WrappingKeyReference(
                "other", 1, "key", "1", "TEST", WrappingContext.LOCAL_AAD_V1);

        assertThat(registry.wrap(null).failure().category())
                .isEqualTo(KeyWrappingFailureCategory.INVALID_REQUEST);
        assertThat(registry.unwrap(null).failure().category())
                .isEqualTo(KeyWrappingFailureCategory.INVALID_REQUEST);
        assertThat(registry.rewrap(null).failure().category())
                .isEqualTo(KeyWrappingFailureCategory.INVALID_REQUEST);
        assertThat(registry.rewrap(new KeyRewrapRequest(
                source, context, otherProvider, context, 2)).failure().category())
                .isEqualTo(KeyWrappingFailureCategory.UNSUPPORTED);
        assertThat(provider.rewrapCalls).isZero();
    }

    /**
     * 验证 provider-neutral 请求对象的字符串表示不泄漏原始上下文或密钥材料。
     */
    @Test
    void shouldRedactProviderRequestStringRepresentations() {
        WrappingContext context = context(WrappingContext.EXTERNAL_CONTEXT_V2, 1);
        WrappingKeyReference reference = new WrappingKeyReference(
                "test-provider", 1, "sensitive-key-id", "1", "TEST",
                WrappingContext.EXTERNAL_CONTEXT_V2);
        PersistedWrappedDataKey persisted = new PersistedWrappedDataKey(
                "sensitive-ciphertext", null, reference, 1);

        assertThat(context.toString())
                .doesNotContain("hash-1", "OWNER", "7", "11", "13");
        assertThat(new KeyUnwrapRequest(persisted, context).toString())
                .doesNotContain("sensitive-key-id", "sensitive-ciphertext", "hash-1");
        assertThat(new KeyRewrapRequest(persisted, context, reference, context, 1).toString())
                .doesNotContain("sensitive-key-id", "sensitive-ciphertext", "hash-1");
    }

    private WrappingContext context(String schema, int version) {
        return new WrappingContext(
                7L, 11L, "hash-1", "OWNER", 13L, version,
                "RP-AES256-GCM-CHUNK-CHAIN-V1", schema);
    }

    private static final class TrackingProvider implements KeyWrappingProvider {

        private final String providerId;
        private final int contractVersion;
        private final Set<String> algorithms;
        private final boolean nativeRewrap;
        private int wrapCalls;
        private int unwrapCalls;
        private int rewrapCalls;

        private TrackingProvider(String providerId, int contractVersion) {
            this(providerId, contractVersion, Set.of("TEST"), false);
        }

        private TrackingProvider(String providerId, int contractVersion, Set<String> algorithms) {
            this(providerId, contractVersion, algorithms, false);
        }

        private TrackingProvider(String providerId,
                                 int contractVersion,
                                 Set<String> algorithms,
                                 boolean nativeRewrap) {
            this.providerId = providerId;
            this.contractVersion = contractVersion;
            this.algorithms = algorithms;
            this.nativeRewrap = nativeRewrap;
        }

        @Override
        public String providerId() {
            return providerId;
        }

        @Override
        public int contractVersion() {
            return contractVersion;
        }

        @Override
        public Set<KeyWrappingCapability> capabilities() {
            return nativeRewrap
                    ? Set.of(KeyWrappingCapability.WRAP, KeyWrappingCapability.UNWRAP,
                    KeyWrappingCapability.NATIVE_REWRAP_SAME_KEY)
                    : Set.of(KeyWrappingCapability.WRAP, KeyWrappingCapability.UNWRAP);
        }

        @Override
        public Set<String> supportedWrappingAlgorithms() {
            return algorithms;
        }

        @Override
        public KeyWrappingResult<WrappingKeyReference> activeKeyReference(Integer logicalKeyVersion) {
            return KeyWrappingResult.success(new WrappingKeyReference(
                    providerId, contractVersion, "test-key", "1", "TEST",
                    WrappingContext.LOCAL_AAD_V1));
        }

        @Override
        public KeyWrappingResult<WrappedDataKey> wrap(KeyWrapRequest request) {
            wrapCalls++;
            return KeyWrappingResult.success(new WrappedDataKey(
                    "cipher", "iv", request.target(), request.logicalKeyVersion()));
        }

        @Override
        public KeyWrappingResult<PlaintextDataKey> unwrap(KeyUnwrapRequest request) {
            unwrapCalls++;
            return KeyWrappingResult.success(PlaintextDataKey.of("key"));
        }

        @Override
        public KeyWrappingResult<WrappedDataKey> rewrap(KeyRewrapRequest request) {
            rewrapCalls++;
            return KeyWrappingResult.success(new WrappedDataKey(
                    "rewrapped", "iv", request.target(), request.logicalKeyVersion()));
        }

        @Override
        public KeyWrappingProviderDiagnostics diagnostics() {
            return new KeyWrappingProviderDiagnostics(
                    providerId, contractVersion, capabilities(), true, "configured");
        }
    }
}
