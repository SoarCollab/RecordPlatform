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
        private int unwrapCalls;

        private TrackingProvider(String providerId, int contractVersion) {
            this.providerId = providerId;
            this.contractVersion = contractVersion;
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
            return Set.of(KeyWrappingCapability.WRAP, KeyWrappingCapability.UNWRAP);
        }

        @Override
        public KeyWrappingResult<WrappingKeyReference> activeKeyReference(Integer logicalKeyVersion) {
            return KeyWrappingResult.success(new WrappingKeyReference(
                    providerId, contractVersion, "test-key", "1", "TEST",
                    WrappingContext.LOCAL_AAD_V1));
        }

        @Override
        public KeyWrappingResult<WrappedDataKey> wrap(KeyWrapRequest request) {
            return KeyWrappingResult.failure(KeyWrappingFailure.of(KeyWrappingFailureCategory.UNSUPPORTED, false));
        }

        @Override
        public KeyWrappingResult<PlaintextDataKey> unwrap(KeyUnwrapRequest request) {
            unwrapCalls++;
            return KeyWrappingResult.success(PlaintextDataKey.of("key"));
        }

        @Override
        public KeyWrappingResult<WrappedDataKey> rewrap(KeyRewrapRequest request) {
            return KeyWrappingResult.failure(KeyWrappingFailure.of(KeyWrappingFailureCategory.UNSUPPORTED, false));
        }

        @Override
        public KeyWrappingProviderDiagnostics diagnostics() {
            return new KeyWrappingProviderDiagnostics(
                    providerId, contractVersion, capabilities(), true, "configured");
        }
    }
}
