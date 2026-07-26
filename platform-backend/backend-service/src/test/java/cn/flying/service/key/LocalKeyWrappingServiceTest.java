package cn.flying.service.key;

import cn.flying.common.exception.GeneralException;
import cn.flying.dao.entity.FileKeyEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("LocalKeyWrappingService")
class LocalKeyWrappingServiceTest {

    private FileKeyEnvelopeProperties properties;
    private LocalKeyWrappingService wrappingService;

    @BeforeEach
    void setUp() {
        properties = new FileKeyEnvelopeProperties();
        properties.setLocalMasterKey("test-master-key-with-enough-entropy");
        wrappingService = new LocalKeyWrappingService(properties);
    }

    /**
     * Verifies that wrapping and unwrapping preserves the serialized file data key.
     */
    @Test
    @DisplayName("should wrap and unwrap serialized data key")
    void shouldWrapAndUnwrapSerializedDataKey() {
        byte[] aad = "tenant|file|hash|OWNER|100|1|suite".getBytes();

        WrappedDataKey wrapped = wrappingService.wrap("base64-initial-key", aad);
        FileKeyEnvelope envelope = new FileKeyEnvelope()
                .setEncryptedDataKey(wrapped.encryptedDataKey())
                .setWrappingIv(wrapped.wrappingIv())
                .setKeyVersion(wrapped.keyVersion());

        assertNotEquals("base64-initial-key", wrapped.encryptedDataKey());
        assertEquals("base64-initial-key", wrappingService.unwrap(envelope, aad));
    }

    /**
     * Verifies that AES-GCM AAD binds the envelope to its file context.
     */
    @Test
    @DisplayName("should reject unwrap when aad is tampered")
    void shouldRejectUnwrapWhenAadIsTampered() {
        WrappedDataKey wrapped = wrappingService.wrap("base64-initial-key", "aad-1".getBytes());
        FileKeyEnvelope envelope = new FileKeyEnvelope()
                .setEncryptedDataKey(wrapped.encryptedDataKey())
                .setWrappingIv(wrapped.wrappingIv())
                .setKeyVersion(wrapped.keyVersion());

        assertThrows(GeneralException.class, () -> wrappingService.unwrap(envelope, "aad-2".getBytes()));
    }

    /**
     * Verifies that persisted key versions select the matching local master key.
     */
    @Test
    @DisplayName("should unwrap with persisted local master key version")
    void shouldUnwrapWithPersistedLocalMasterKeyVersion() {
        properties.setKeyVersion(2);
        properties.setLocalMasterKeys(Map.of(
                1, "previous-local-master-key",
                2, "new-local-master-key"
        ));
        byte[] aad = "tenant|file|hash|OWNER|100|1|suite".getBytes();

        WrappedDataKey wrapped = wrappingService.wrap("serialized-key", aad, 1);
        FileKeyEnvelope envelope = new FileKeyEnvelope()
                .setEncryptedDataKey(wrapped.encryptedDataKey())
                .setWrappingIv(wrapped.wrappingIv())
                .setKeyVersion(1);

        assertEquals(1, wrapped.keyVersion());
        assertEquals("serialized-key", wrappingService.unwrap(envelope, aad));
    }

    /**
     * Verifies that previous envelope versions fail when their local master key is no longer configured.
     */
    @Test
    @DisplayName("should reject unwrap when persisted local master key version is missing")
    void shouldRejectUnwrapWhenPersistedLocalMasterKeyVersionIsMissing() {
        properties.setKeyVersion(1);
        properties.setLocalMasterKeys(Map.of(1, "previous-local-master-key"));
        byte[] aad = "tenant|file|hash|OWNER|100|1|suite".getBytes();
        WrappedDataKey wrapped = wrappingService.wrap("serialized-key", aad, 1);

        FileKeyEnvelopeProperties missingOldKeyProperties = new FileKeyEnvelopeProperties();
        missingOldKeyProperties.setKeyVersion(2);
        missingOldKeyProperties.setLocalMasterKeys(Map.of(2, "new-local-master-key"));
        LocalKeyWrappingService missingOldKeyService = new LocalKeyWrappingService(missingOldKeyProperties);
        FileKeyEnvelope envelope = new FileKeyEnvelope()
                .setEncryptedDataKey(wrapped.encryptedDataKey())
                .setWrappingIv(wrapped.wrappingIv())
                .setKeyVersion(1);

        assertThrows(GeneralException.class, () -> missingOldKeyService.unwrap(envelope, aad));
    }

    /**
     * 验证旧版本代码生成的固定 local ciphertext 在 SPI 改造后仍可解封。
     */
    @Test
    void shouldUnwrapHistoricalLocalCiphertextFixture() {
        properties.setLocalMasterKey("historical-local-master-key-v1");
        WrappingContext context = context(1);
        WrappingKeyReference reference = new WrappingKeyReference(
                LocalKeyWrappingService.PROVIDER_ID,
                LocalKeyWrappingService.CONTRACT_VERSION,
                "local-file-key-v1",
                "1",
                "AES-256-GCM",
                WrappingContext.LOCAL_AAD_V1);
        PersistedWrappedDataKey persisted = new PersistedWrappedDataKey(
                "VGfagq6PCRhevk8UPRgBXAbweKOc3C7OJrB++fMH2WOq1b5UKdLcaB8=",
                "AAECAwQFBgcICQoL",
                reference,
                1);

        KeyWrappingResult<PlaintextDataKey> result = wrappingService.unwrap(
                new KeyUnwrapRequest(persisted, context));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().reveal()).isEqualTo("historical-serialized-key");
    }

    /**
     * 验证 local 持久化 key id、provider version 与算法被篡改后均失败关闭。
     */
    @Test
    void shouldRejectTamperedPersistedLocalProviderMetadata() {
        WrappingContext context = context(1);
        WrappingKeyReference target = wrappingService.activeKeyReference(1).requireValue();
        WrappedDataKey wrapped = wrappingService.wrap(new KeyWrapRequest(
                PlaintextDataKey.of("serialized-key"), context, target, 1)).requireValue();

        assertRejectedMetadata(wrapped, context, new WrappingKeyReference(
                target.providerId(), target.providerContractVersion(), "tampered-key-id",
                target.providerKeyVersion(), target.wrappingAlgorithm(), target.contextSchema()));
        assertRejectedMetadata(wrapped, context, new WrappingKeyReference(
                target.providerId(), target.providerContractVersion(), target.keyId(),
                "2", target.wrappingAlgorithm(), target.contextSchema()));
        assertRejectedMetadata(wrapped, context, new WrappingKeyReference(
                target.providerId(), target.providerContractVersion(), target.keyId(),
                target.providerKeyVersion(), "AES/GCM/NoPadding", target.contextSchema()));
    }

    /**
     * 验证显式保留的历史 local key id 在切换 active key id 后仍可读取。
     */
    @Test
    void shouldAllowExplicitHistoricalLocalKeyIdDuringRotation() {
        properties.setKmsKeyId("local-key-old");
        WrappingContext context = context(1);
        WrappedDataKey wrapped = wrappingService.wrap(new KeyWrapRequest(
                PlaintextDataKey.of("serialized-key"), context,
                wrappingService.activeKeyReference(1).requireValue(), 1)).requireValue();

        properties.setKmsKeyId("local-key-new");
        properties.getProviders().getLocal().setHistoricalKeyIds(Set.of("local-key-old"));
        KeyWrappingResult<PlaintextDataKey> result = wrappingService.unwrap(new KeyUnwrapRequest(
                new PersistedWrappedDataKey(wrapped.encryptedDataKey(), wrapped.wrappingIv(),
                        wrapped.keyReference(), wrapped.logicalKeyVersion()),
                context));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().reveal()).isEqualTo("serialized-key");
    }

    /**
     * 验证 local provider 的启动诊断覆盖 key id 与包封算法完整性。
     */
    @Test
    void shouldReportIncompleteLocalTargetConfiguration() {
        properties.setWrappingAlgorithm(" ");
        assertThat(wrappingService.diagnostics().available()).isFalse();

        properties.setWrappingAlgorithm("AES-256-GCM");
        properties.setKmsKeyId(" ");
        assertThat(wrappingService.diagnostics().available()).isFalse();
    }

    /**
     * 断言指定 local metadata 无法通过 provider 校验。
     */
    private void assertRejectedMetadata(WrappedDataKey wrapped,
                                        WrappingContext context,
                                        WrappingKeyReference reference) {
        KeyWrappingResult<PlaintextDataKey> result = wrappingService.unwrap(new KeyUnwrapRequest(
                new PersistedWrappedDataKey(wrapped.encryptedDataKey(), wrapped.wrappingIv(),
                        reference, wrapped.logicalKeyVersion()),
                context));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.failure().category()).isEqualTo(KeyWrappingFailureCategory.INVALID_REQUEST);
    }

    /**
     * 构造与历史 local AAD v1 一致的认证上下文。
     */
    private WrappingContext context(int logicalVersion) {
        return new WrappingContext(
                7L, 11L, "hash-1", "OWNER", 13L, logicalVersion,
                "RP-AES256-GCM-CHUNK-CHAIN-V1", WrappingContext.LOCAL_AAD_V1);
    }
}
