package cn.flying.service.key;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.JsonConverter;
import cn.flying.dao.dto.File;
import cn.flying.dao.dto.FileShare;
import cn.flying.dao.entity.FriendFileShare;
import cn.flying.dao.entity.FileKeyAuditLog;
import cn.flying.dao.entity.FileKeyEnvelope;
import cn.flying.dao.mapper.FileKeyAuditLogMapper;
import cn.flying.dao.mapper.FileKeyEnvelopeMapper;
import cn.flying.dao.mapper.TenantCryptoPolicyMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("FileKeyEnvelopeService")
@ExtendWith(MockitoExtension.class)
class FileKeyEnvelopeServiceTest {

    @Mock
    private FileKeyEnvelopeMapper fileKeyEnvelopeMapper;

    @Mock
    private FileKeyAuditLogMapper fileKeyAuditLogMapper;

    @Mock
    private KeyEnvelopeRotationActivationService rotationActivationService;

    @Mock
    private TenantCryptoPolicyMapper tenantCryptoPolicyMapper;

    private FileKeyEnvelopeProperties properties;
    private LocalKeyWrappingService wrappingService;
    private KeyWrappingProviderRegistry wrappingRegistry;
    private CryptoSuitePolicyService suitePolicy;
    private FileKeyEnvelopeService envelopeService;

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, FileKeyEnvelope.class);
    }

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(1L);
        properties = new FileKeyEnvelopeProperties();
        properties.setLocalMasterKey("test-master-key-with-enough-entropy");
        wrappingService = new LocalKeyWrappingService(properties);
        wrappingRegistry = new KeyWrappingProviderRegistry(
                java.util.List.of(wrappingService), properties, new SimpleMeterRegistry());
        CryptoAgilityProperties agilityProperties = new CryptoAgilityProperties();
        suitePolicy = new CryptoSuitePolicyService(
                properties,
                agilityProperties,
                new CryptoSuiteRegistry(agilityProperties, new SimpleMeterRegistry()),
                wrappingRegistry,
                tenantCryptoPolicyMapper);
        org.mockito.Mockito.lenient()
                .when(rotationActivationService.activateVerifiedCandidate(any(), any(), any(), any(), any()))
                .thenReturn("SUCCEEDED");
        envelopeService = new FileKeyEnvelopeService(
                fileKeyEnvelopeMapper, fileKeyAuditLogMapper, wrappingRegistry, properties, suitePolicy,
                rotationActivationService);
    }

    /**
     * Clears tenant state after every test so later suites cannot inherit an authorization boundary.
     */
    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    /**
     * Verifies that raw initialKey is removed before file_param persistence.
     */
    @Test
    @DisplayName("should sanitize file param and return envelope input")
    void shouldSanitizeFileParamAndReturnEnvelopeInput() {
        String fileParam = """
                {"fileName":"a.txt","fileSize":10,"initialKey":"serialized-key"}
                """;

        FileParamEnvelopeResult result = envelopeService.prepareFileParam(fileParam);
        @SuppressWarnings("unchecked")
        Map<String, Object> sanitized = JsonConverter.parse(result.sanitizedFileParam(), Map.class);

        assertTrue(result.requiresEnvelope());
        assertEquals("serialized-key", result.initialKey());
        assertFalse(sanitized.containsKey("initialKey"));
        assertEquals("ENVELOPED", sanitized.get("keyEnvelopeStatus"));
        assertEquals("RP-AES256-GCM-CHUNK-CHAIN-V1", sanitized.get("algorithmSuite"));
        assertEquals("UNSIGNED-V1", sanitized.get("signatureSuite"));
        assertEquals("NONE-V1", sanitized.get("kemSuite"));
        assertEquals("RP-MERKLE-SHA256-V1", sanitized.get("proofSuite"));
        assertEquals(1, ((Number) sanitized.get("keyVersion")).intValue());
    }

    /**
     * Proves one upload decision freezes its exact provider target instead of re-reading changed defaults.
     */
    @Test
    void shouldPersistFrozenWrappingTargetWhenDefaultsDriftAfterPreparation() {
        FileParamEnvelopeResult decision = envelopeService.prepareFileParam(
                "{\"fileName\":\"a.txt\",\"initialKey\":\"serialized-key\"}");
        File file = new File().setId(10L).setTenantId(1L).setUid(100L).setFileHash("hash-1");
        properties.setActiveProvider("unregistered-after-decision");
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenReturn(1);

        envelopeService.saveOwnerEnvelope(file, "hash-1", 100L, decision);

        ArgumentCaptor<FileKeyEnvelope> envelope = ArgumentCaptor.forClass(FileKeyEnvelope.class);
        verify(fileKeyEnvelopeMapper).insert(envelope.capture());
        assertThat(envelope.getValue().getKmsProvider()).isEqualTo(LocalKeyWrappingService.PROVIDER_ID);
        assertThat(envelope.getValue().getProviderContractVersion()).isEqualTo(1);
        assertThat(envelope.getValue().getWrappingAlgorithm()).isEqualTo(CryptoSuiteIds.LOCAL_WRAPPING);
    }

    /**
     * Verifies that unsupported configured suite identifiers are rejected before persistence.
     */
    @Test
    @DisplayName("should reject unsupported crypto suite")
    void shouldRejectUnsupportedCryptoSuite() {
        properties.setSupportedAlgorithmSuites(Set.of("OTHER-SUITE"));

        assertThatThrownBy(() -> envelopeService.prepareFileParam("""
                {"fileName":"a.txt","initialKey":"serialized-key"}
                """))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> {
                    GeneralException generalException = (GeneralException) ex;
                    assertThat(generalException.getResultEnum()).isEqualTo(ResultEnum.PARAM_ERROR);
                    assertThat(generalException.getData()).asString().contains("不支持的密码套件");
                });
    }

    /**
     * Verifies that deprecated active suites are rejected deterministically.
     */
    @Test
    @DisplayName("should reject deprecated crypto suite")
    void shouldRejectDeprecatedCryptoSuite() {
        properties.setDeprecatedAfter(Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> envelopeService.prepareFileParam("""
                {"fileName":"a.txt","initialKey":"serialized-key"}
                """))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> {
                    GeneralException generalException = (GeneralException) ex;
                    assertThat(generalException.getResultEnum()).isEqualTo(ResultEnum.PARAM_ERROR);
                    assertThat(generalException.getData()).asString().contains("当前密码套件已废弃");
                });
    }

    /**
     * Verifies that unencrypted direct-upload metadata does not create an envelope.
     */
    @Test
    @DisplayName("should skip envelope for direct multipart none encryption")
    void shouldSkipEnvelopeForDirectMultipartNoneEncryption() {
        String fileParam = """
                {"uploadMode":"DIRECT_MULTIPART","encryptionAlgorithm":"NONE","fileName":"a.txt","initialKey":"stale-key"}
                """;

        FileParamEnvelopeResult result = envelopeService.prepareFileParam(fileParam);
        @SuppressWarnings("unchecked")
        Map<String, Object> sanitized = JsonConverter.parse(result.sanitizedFileParam(), Map.class);

        assertFalse(result.requiresEnvelope());
        assertFalse(sanitized.containsKey("initialKey"));
        assertEquals("NONE", sanitized.get("encryptionAlgorithm"));
    }

    /**
     * Verifies that encrypted files cannot be persisted without envelope input.
     */
    @Test
    @DisplayName("should reject encrypted file param without initial key")
    void shouldRejectEncryptedFileParamWithoutInitialKey() {
        assertThatThrownBy(() -> envelopeService.prepareFileParam("""
                {"fileName":"a.txt","encryptionAlgorithm":"AES-GCM"}
                """))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> {
                    GeneralException generalException = (GeneralException) ex;
                    assertThat(generalException.getResultEnum()).isEqualTo(ResultEnum.PARAM_ERROR);
                    assertThat(generalException.getData()).asString().contains("文件数据密钥不能为空");
                });
    }

    /**
     * Verifies that file metadata must be present under the current upload contract.
     */
    @Test
    @DisplayName("should reject blank file param")
    void shouldRejectBlankFileParam() {
        assertThatThrownBy(() -> envelopeService.prepareFileParam(" "))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> {
                    GeneralException generalException = (GeneralException) ex;
                    assertThat(generalException.getResultEnum()).isEqualTo(ResultEnum.PARAM_ERROR);
                    assertThat(generalException.getData()).asString().contains("文件元数据不能为空");
                });
    }

    /**
     * Verifies that direct multipart uploads must explicitly declare NONE encryption.
     */
    @Test
    @DisplayName("should not infer none encryption from direct multipart upload mode")
    void shouldNotInferNoneEncryptionFromDirectMultipartUploadMode() {
        assertThatThrownBy(() -> envelopeService.prepareFileParam("""
                {"uploadMode":"DIRECT_MULTIPART","fileName":"a.txt"}
                """))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> {
                    GeneralException generalException = (GeneralException) ex;
                    assertThat(generalException.getResultEnum()).isEqualTo(ResultEnum.PARAM_ERROR);
                    assertThat(generalException.getData()).asString().contains("文件数据密钥不能为空");
                });
    }

    /**
     * Verifies that unencrypted files do not create share recipient envelopes.
     */
    @Test
    @DisplayName("should skip share envelope for unencrypted file")
    void shouldSkipShareEnvelopeForUnencryptedFile() {
        File file = new File()
                .setId(10L)
                .setTenantId(1L)
                .setUid(100L)
                .setFileHash("hash-1")
                .setFileParam("""
                        {"uploadMode":"DIRECT_MULTIPART","encryptionAlgorithm":"NONE","fileName":"a.txt"}
                        """);
        FileShare share = new FileShare()
                .setId(200L)
                .setTenantId(1L)
                .setUserId(100L)
                .setShareCode("ABC123");

        envelopeService.saveShareEnvelopes(share, java.util.List.of(file), 100L, "SHARE_CREATE");

        verify(fileKeyEnvelopeMapper, org.mockito.Mockito.never()).insert(any(FileKeyEnvelope.class));
    }

    /**
     * Verifies that legacy/plain persisted metadata without an explicit encryption algorithm does not block sharing.
     */
    @Test
    @DisplayName("should skip share envelope for legacy metadata without encryption algorithm")
    void shouldSkipShareEnvelopeForLegacyMetadataWithoutEncryptionAlgorithm() {
        File file = new File()
                .setId(10L)
                .setTenantId(1L)
                .setUid(100L)
                .setFileHash("hash-1")
                .setFileParam("""
                        {"fileName":"a.txt","fileSize":10,"contentType":"text/plain"}
                        """);
        FileShare share = new FileShare()
                .setId(200L)
                .setTenantId(1L)
                .setUserId(100L)
                .setShareCode("ABC123");

        envelopeService.saveShareEnvelopes(share, java.util.List.of(file), 100L, "SHARE_CREATE");

        verify(fileKeyEnvelopeMapper, org.mockito.Mockito.never()).insert(any(FileKeyEnvelope.class));
    }

    /**
     * Verifies that legacy encrypted metadata still creates a share envelope when plaintext key material exists.
     */
    @Test
    @DisplayName("should create share envelope for legacy metadata with initial key")
    void shouldCreateShareEnvelopeForLegacyMetadataWithInitialKey() {
        File file = new File()
                .setId(10L)
                .setTenantId(1L)
                .setUid(100L)
                .setFileHash("hash-1")
                .setFileParam("""
                        {"fileName":"a.txt","fileSize":10,"initialKey":"legacy-key"}
                        """);
        FileShare share = new FileShare()
                .setId(200L)
                .setTenantId(1L)
                .setUserId(100L)
                .setShareCode("ABC123");
        ArgumentCaptor<FileKeyEnvelope> envelopeCaptor = ArgumentCaptor.forClass(FileKeyEnvelope.class);
        when(fileKeyEnvelopeMapper.selectOne(any())).thenReturn(null);
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenReturn(1);

        envelopeService.saveShareEnvelopes(share, java.util.List.of(file), 100L, "SHARE_CREATE");

        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        FileKeyEnvelope shareEnvelope = envelopeCaptor.getValue();
        assertEquals(FileKeyEnvelopeService.RECIPIENT_TYPE_SHARE, shareEnvelope.getRecipientType());
        assertEquals(200L, shareEnvelope.getRecipientId());
    }

    /**
     * Verifies that explicitly encrypted files fail share creation when no owner or legacy key exists.
     */
    @Test
    @DisplayName("should reject encrypted share envelope when key is missing")
    void shouldRejectEncryptedShareEnvelopeWhenKeyIsMissing() {
        File file = new File()
                .setId(10L)
                .setTenantId(1L)
                .setUid(100L)
                .setFileHash("hash-1")
                .setFileParam("""
                        {"fileName":"a.txt","encryptionAlgorithm":"AES-GCM"}
                        """);
        FileShare share = new FileShare()
                .setId(200L)
                .setTenantId(1L)
                .setUserId(100L)
                .setShareCode("ABC123");
        when(fileKeyEnvelopeMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> envelopeService.saveShareEnvelopes(
                share,
                java.util.List.of(file),
                100L,
                "SHARE_CREATE"
        ))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getData())
                        .asString()
                        .contains("文件解密密钥不存在"));
    }

    /**
     * Verifies that owner decrypt remains compatible with pre-envelope file_param key material.
     */
    @Test
    @DisplayName("should resolve legacy initial key when owner envelope is missing")
    void shouldResolveLegacyInitialKeyWhenOwnerEnvelopeIsMissing() {
        File file = new File()
                .setId(10L)
                .setTenantId(1L)
                .setUid(100L)
                .setFileHash("hash-1")
                .setFileParam("""
                        {"fileName":"a.txt","initialKey":"legacy-key"}
                        """);
        when(fileKeyEnvelopeMapper.selectOne(any())).thenReturn(null);

        Optional<String> initialKey = envelopeService.unwrapActiveOwnerInitialKey(file, "hash-1", 100L);

        assertThat(initialKey).contains("legacy-key");
    }

    /**
     * 验证 owner grant 只投影非敏感路由字段，并可按完全一致的 ACTIVE 信封即时解封。
     */
    @Test
    void shouldResolveAndConsumeBoundOwnerGrant() {
        File file = new File()
                .setId(10L)
                .setTenantId(1L)
                .setUid(100L)
                .setVersion(3)
                .setFileHash("hash-1");
        FileParamEnvelopeResult result = envelopeService.prepareFileParam(
                "{\"initialKey\":\"serialized-key\"}");
        ArgumentCaptor<FileKeyEnvelope> envelopeCaptor = ArgumentCaptor.forClass(FileKeyEnvelope.class);
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenReturn(1);
        envelopeService.saveOwnerEnvelope(file, "hash-1", 100L, result);
        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        FileKeyEnvelope envelope = envelopeCaptor.getValue().setId(501L);

        clearInvocations(fileKeyEnvelopeMapper, fileKeyAuditLogMapper);
        when(fileKeyEnvelopeMapper.selectOne(any())).thenReturn(envelope);
        FileKeyGrantEnvelopeBinding binding = envelopeService
                .resolveOwnerGrantBinding(file, null, 100L)
                .orElseThrow();
        when(fileKeyEnvelopeMapper.selectById(501L)).thenReturn(envelope);

        Optional<String> initialKey = envelopeService.unwrapGrantBinding(
                file, binding, 100L, "DOWNLOAD_GRANT_CONSUME");

        assertThat(binding.fileVersion()).isEqualTo(3);
        assertThat(binding.fileHash()).isEqualTo("hash-1");
        assertThat(binding.envelopeId()).isEqualTo(501L);
        assertThat(binding.legacyPlaintextAtRest()).isFalse();
        assertThat(initialKey).contains("serialized-key");
        ArgumentCaptor<FileKeyAuditLog> audits = ArgumentCaptor.forClass(FileKeyAuditLog.class);
        verify(fileKeyAuditLogMapper, org.mockito.Mockito.times(2)).insert(audits.capture());
        assertThat(audits.getAllValues())
                .extracting(FileKeyAuditLog::getOperation)
                .containsExactly("UNWRAP", "GRANT_CONSUME");
    }

    /**
     * 验证分享与好友分享 grant 均冻结各自 recipient，空分享上下文直接失败关闭。
     */
    @Test
    void shouldResolveShareAndFriendGrantBindings() {
        File file = new File()
                .setId(10L)
                .setTenantId(1L)
                .setVersion(2)
                .setFileHash("hash-1");
        FileShare share = new FileShare().setId(201L);
        FriendFileShare friendShare = new FriendFileShare().setId(301L);
        FileKeyEnvelope shareEnvelope = grantEnvelope(
                601L, FileKeyEnvelopeService.RECIPIENT_TYPE_SHARE, 201L);
        FileKeyEnvelope friendEnvelope = grantEnvelope(
                602L, FileKeyEnvelopeService.RECIPIENT_TYPE_FRIEND_SHARE, 301L);
        when(fileKeyEnvelopeMapper.selectOne(any())).thenReturn(shareEnvelope, friendEnvelope);

        FileKeyGrantEnvelopeBinding shareBinding = envelopeService
                .resolveShareGrantBinding(file, "hash-1", share)
                .orElseThrow();
        FileKeyGrantEnvelopeBinding friendBinding = envelopeService
                .resolveFriendShareGrantBinding(file, "hash-1", friendShare)
                .orElseThrow();

        assertThat(shareBinding.recipientType())
                .isEqualTo(FileKeyEnvelopeService.RECIPIENT_TYPE_SHARE);
        assertThat(shareBinding.recipientId()).isEqualTo(201L);
        assertThat(friendBinding.recipientType())
                .isEqualTo(FileKeyEnvelopeService.RECIPIENT_TYPE_FRIEND_SHARE);
        assertThat(friendBinding.recipientId()).isEqualTo(301L);
        assertThat(envelopeService.resolveShareGrantBinding(file, "hash-1", null)).isEmpty();
        assertThat(envelopeService.resolveFriendShareGrantBinding(file, "hash-1", null)).isEmpty();
    }

    /**
     * 验证历史明文 grant 使用规范化文件哈希与版本，并在消费及兼容交付时留下无秘密审计。
     */
    @Test
    void shouldResolveConsumeAndAuditLegacyGrant() {
        File file = new File()
                .setId(10L)
                .setTenantId(1L)
                .setUid(100L)
                .setFileHash("hash-1")
                .setFileParam("""
                        {"initialKey":"legacy-key","keyVersion":0,
                         "algorithmSuite":"LEGACY-V1","signatureSuite":"UNSIGNED-V1",
                         "kemSuite":"NONE-V1","proofSuite":"LEGACY-PROOF",
                         "encryptionAlgorithm":"CHUNK_KEY_CHAIN"}
                        """);
        when(fileKeyEnvelopeMapper.selectOne(any())).thenReturn(null);

        FileKeyGrantEnvelopeBinding binding = envelopeService
                .resolveOwnerGrantBinding(file, "", 100L)
                .orElseThrow();
        Optional<String> initialKey = envelopeService.unwrapGrantBinding(
                file, binding, 100L, "PLAINTEXT_V0_COMPATIBILITY");
        envelopeService.auditGrantIssue(binding, 100L, "OWNER");
        envelopeService.auditLegacyPlaintextDelivery(binding, 100L);
        envelopeService.auditGrantDenial(binding, 100L, "SESSION_MISMATCH");
        envelopeService.auditGrantIssue(null, 100L, "IGNORED");

        assertThat(binding.envelopeId()).isNull();
        assertThat(binding.fileVersion()).isEqualTo(1);
        assertThat(binding.fileHash()).isEqualTo("hash-1");
        assertThat(binding.kmsProvider()).isEqualTo("legacy-plaintext");
        assertThat(binding.legacyPlaintextAtRest()).isTrue();
        assertThat(initialKey).contains("legacy-key");
        ArgumentCaptor<FileKeyAuditLog> audits = ArgumentCaptor.forClass(FileKeyAuditLog.class);
        verify(fileKeyAuditLogMapper, org.mockito.Mockito.times(4)).insert(audits.capture());
        assertThat(audits.getAllValues())
                .extracting(FileKeyAuditLog::getOperation)
                .containsExactly("GRANT_CONSUME", "GRANT_ISSUE", "PLAINTEXT_V0", "GRANT_DENY");
    }

    /**
     * 验证文件版本变化、信封轮换或路由字段变化都会让已签发 grant 失败关闭。
     */
    @Test
    void shouldRejectStaleFileAndEnvelopeGrantBindings() {
        File file = new File()
                .setId(10L)
                .setTenantId(1L)
                .setUid(100L)
                .setVersion(2)
                .setFileHash("hash-1");
        FileKeyGrantEnvelopeBinding binding = new FileKeyGrantEnvelopeBinding(
                501L, 1L, 10L, 1, "hash-1",
                FileKeyEnvelopeService.RECIPIENT_TYPE_OWNER, 100L, 1,
                properties.getAlgorithmSuite(), properties.getSignatureSuite(), properties.getKemSuite(),
                properties.getProofSuite(), properties.getEncryptionAlgorithm(),
                LocalKeyWrappingService.PROVIDER_ID, 1, "1", false);
        FileKeyEnvelope envelope = grantEnvelope(
                501L, FileKeyEnvelopeService.RECIPIENT_TYPE_OWNER, 100L);
        when(fileKeyEnvelopeMapper.selectById(501L)).thenReturn(envelope);

        assertThat(envelopeService.unwrapGrantBinding(
                file, binding, 100L, "DOWNLOAD_GRANT_CONSUME")).isEmpty();

        file.setVersion(1);
        envelope.setStatus(FileKeyEnvelopeService.STATUS_SUPERSEDED);
        assertThat(envelopeService.unwrapGrantBinding(
                file, binding, 100L, "DOWNLOAD_GRANT_CONSUME")).isEmpty();

        envelope.setStatus(FileKeyEnvelopeService.STATUS_ACTIVE).setProviderKeyVersion("2");
        assertThat(envelopeService.unwrapGrantBinding(
                file, binding, 100L, "DOWNLOAD_GRANT_CONSUME")).isEmpty();
        ArgumentCaptor<FileKeyAuditLog> audits = ArgumentCaptor.forClass(FileKeyAuditLog.class);
        verify(fileKeyAuditLogMapper, org.mockito.Mockito.times(3)).insert(audits.capture());
        assertThat(audits.getAllValues())
                .extracting(FileKeyAuditLog::getReason)
                .containsExactly("FILE_BINDING_MISMATCH", "ENVELOPE_NOT_ACTIVE", "ENVELOPE_NOT_ACTIVE");
    }

    /**
     * Verifies that legacy plaintext key fallback remains bound to the persisted file owner.
     */
    @Test
    @DisplayName("should not resolve legacy initial key for another owner")
    void shouldNotResolveLegacyInitialKeyForAnotherOwner() {
        File file = new File()
                .setId(10L)
                .setTenantId(1L)
                .setUid(100L)
                .setFileHash("hash-1")
                .setFileParam("""
                        {"fileName":"a.txt","initialKey":"legacy-key"}
                        """);
        when(fileKeyEnvelopeMapper.selectOne(any())).thenReturn(null);

        Optional<String> initialKey = envelopeService.unwrapActiveOwnerInitialKey(file, "hash-1", 200L);

        assertThat(initialKey).isEmpty();
    }

    /**
     * Verifies that stale legacy key material is ignored when metadata explicitly declares no encryption.
     */
    @Test
    @DisplayName("should ignore stale legacy key for explicitly unencrypted file")
    void shouldIgnoreStaleLegacyKeyForExplicitlyUnencryptedFile() {
        File file = new File()
                .setId(10L)
                .setTenantId(1L)
                .setUid(100L)
                .setFileHash("hash-1")
                .setFileParam("""
                        {"fileName":"a.txt","encryptionAlgorithm":"NONE","initialKey":"stale-key"}
                        """);
        when(fileKeyEnvelopeMapper.selectOne(any())).thenReturn(null);

        Optional<String> initialKey = envelopeService.unwrapActiveOwnerInitialKey(file, "hash-1", 100L);

        assertThat(initialKey).isEmpty();
    }

    /**
     * Verifies that missing persisted metadata returns no legacy owner key.
     */
    @Test
    @DisplayName("should return empty legacy key for blank file param")
    void shouldReturnEmptyLegacyKeyForBlankFileParam() {
        File file = new File()
                .setId(10L)
                .setTenantId(1L)
                .setUid(100L)
                .setFileHash("hash-1");
        when(fileKeyEnvelopeMapper.selectOne(any())).thenReturn(null);

        Optional<String> initialKey = envelopeService.unwrapActiveOwnerInitialKey(file, "hash-1", 100L);

        assertThat(initialKey).isEmpty();
    }

    /**
     * Verifies that an owner envelope can be saved and unwrapped later.
     */
    @Test
    @DisplayName("should save and unwrap owner envelope")
    void shouldSaveAndUnwrapOwnerEnvelope() {
        File file = new File()
                .setId(10L)
                .setTenantId(1L)
                .setUid(100L)
                .setFileHash("hash-1");
        FileParamEnvelopeResult result = envelopeService.prepareFileParam("""
                {"fileName":"a.txt","initialKey":"serialized-key"}
                """);
        file.setFileParam(result.sanitizedFileParam());
        ArgumentCaptor<FileKeyEnvelope> envelopeCaptor = ArgumentCaptor.forClass(FileKeyEnvelope.class);
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenReturn(1);

        envelopeService.saveOwnerEnvelope(file, "hash-1", 100L, result);

        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        FileKeyEnvelope envelope = envelopeCaptor.getValue();
        assertThat(envelope.getEncryptedDataKey()).isNotBlank();
        assertEquals("UNSIGNED-V1", envelope.getSignatureSuite());
        assertEquals("NONE-V1", envelope.getKemSuite());
        assertEquals("RP-MERKLE-SHA256-V1", envelope.getProofSuite());
        assertEquals("local", envelope.getKmsProvider());
        assertEquals(1, envelope.getProviderContractVersion());
        assertEquals("1", envelope.getProviderKeyVersion());
        assertEquals(WrappingContext.LOCAL_AAD_V1, envelope.getContextSchema());
        assertEquals(FileKeyEnvelopeService.STATUS_ACTIVE, envelope.getStatus());

        clearInvocations(fileKeyAuditLogMapper);
        when(fileKeyEnvelopeMapper.selectOne(any())).thenReturn(envelope);
        Optional<String> unwrapped = envelopeService.unwrapActiveOwnerInitialKey(file, "hash-1", 100L);

        assertTrue(unwrapped.isPresent());
        assertEquals("serialized-key", unwrapped.get());
        ArgumentCaptor<FileKeyAuditLog> auditCaptor = ArgumentCaptor.forClass(FileKeyAuditLog.class);
        verify(fileKeyAuditLogMapper).insert(auditCaptor.capture());
        assertEquals("local", auditCaptor.getValue().getKmsProvider());
        assertEquals("NONE", auditCaptor.getValue().getFailureCategory());
        assertThat(auditCaptor.getValue().getKeyIdFingerprint()).hasSize(64);
        assertThat(auditCaptor.getValue().getKeyIdFingerprint()).doesNotContain(envelope.getKmsKeyId());
    }

    /**
     * Verifies that historical envelopes remain readable when non-AAD suite metadata is absent.
     */
    @Test
    @DisplayName("should unwrap historical envelope without non aad suite metadata")
    void shouldFailClosedForHistoricalEnvelopeWithoutSuiteMetadata() {
        File file = new File()
                .setId(10L)
                .setTenantId(1L)
                .setUid(100L)
                .setFileHash("hash-1");
        FileParamEnvelopeResult result = envelopeService.prepareFileParam("""
                {"fileName":"a.txt","initialKey":"serialized-key"}
                """);
        file.setFileParam(result.sanitizedFileParam());
        ArgumentCaptor<FileKeyEnvelope> envelopeCaptor = ArgumentCaptor.forClass(FileKeyEnvelope.class);
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenReturn(1);
        envelopeService.saveOwnerEnvelope(file, "hash-1", 100L, result);
        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        FileKeyEnvelope historicalEnvelope = envelopeCaptor.getValue()
                .setSignatureSuite(null)
                .setKemSuite(null)
                .setProofSuite(null);

        clearInvocations(fileKeyEnvelopeMapper, fileKeyAuditLogMapper);
        when(fileKeyEnvelopeMapper.selectOne(any())).thenReturn(historicalEnvelope);

        assertThatThrownBy(() -> envelopeService.unwrapActiveOwnerInitialKey(file, "hash-1", 100L))
                .isInstanceOf(GeneralException.class);
        ArgumentCaptor<FileKeyAuditLog> auditCaptor = ArgumentCaptor.forClass(FileKeyAuditLog.class);
        verify(fileKeyAuditLogMapper).insert(auditCaptor.capture());
        assertEquals("CONFIGURATION", auditCaptor.getValue().getFailureCategory());
    }

    /**
     * Verifies that share recipient envelopes can be created and used for share decrypt metadata.
     */
    @Test
    @DisplayName("should save and unwrap share recipient envelope")
    void shouldSaveAndUnwrapShareRecipientEnvelope() {
        File file = new File()
                .setId(10L)
                .setTenantId(1L)
                .setUid(100L)
                .setFileHash("hash-1")
                .setFileParam("""
                        {"fileName":"a.txt"}
                        """);
        FileShare share = new FileShare()
                .setId(200L)
                .setTenantId(1L)
                .setUserId(100L)
                .setShareCode("ABC123");
        FileParamEnvelopeResult result = envelopeService.prepareFileParam("""
                {"fileName":"a.txt","initialKey":"serialized-key"}
                """);
        file.setFileParam(result.sanitizedFileParam());
        ArgumentCaptor<FileKeyEnvelope> envelopeCaptor = ArgumentCaptor.forClass(FileKeyEnvelope.class);
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenReturn(1);

        envelopeService.saveOwnerEnvelope(file, "hash-1", 100L, result);
        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        FileKeyEnvelope ownerEnvelope = envelopeCaptor.getValue();

        clearInvocations(fileKeyEnvelopeMapper, fileKeyAuditLogMapper);
        when(fileKeyEnvelopeMapper.selectOne(any())).thenReturn(ownerEnvelope);
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenReturn(1);

        envelopeService.saveShareEnvelopes(share, java.util.List.of(file), 100L, "SHARE_CREATE");

        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        FileKeyEnvelope shareEnvelope = envelopeCaptor.getValue();
        assertEquals(FileKeyEnvelopeService.RECIPIENT_TYPE_SHARE, shareEnvelope.getRecipientType());
        assertEquals(200L, shareEnvelope.getRecipientId());
        assertEquals(FileKeyEnvelopeService.STATUS_ACTIVE, shareEnvelope.getStatus());

        clearInvocations(fileKeyEnvelopeMapper, fileKeyAuditLogMapper);
        when(fileKeyEnvelopeMapper.selectOne(any())).thenReturn(shareEnvelope);
        Optional<String> unwrapped = envelopeService.unwrapActiveShareInitialKey(file, "hash-1", share, 300L, "SHARE_DECRYPT");

        assertTrue(unwrapped.isPresent());
        assertEquals("serialized-key", unwrapped.get());
        verify(fileKeyAuditLogMapper).insert(any(FileKeyAuditLog.class));
    }

    /**
     * Verifies that saving a shared encrypted file creates a new owner envelope bound to the copied file identity.
     */
    @Test
    @DisplayName("should rewrap shared key for copied file owner")
    void shouldRewrapSharedKeyForCopiedFileOwner() {
        File sourceFile = new File()
                .setId(10L)
                .setTenantId(1L)
                .setUid(100L)
                .setFileHash("hash-1");
        File copiedFile = new File()
                .setId(20L)
                .setTenantId(2L)
                .setUid(200L);
        FileShare share = new FileShare()
                .setId(300L)
                .setTenantId(1L)
                .setUserId(100L)
                .setShareCode("ABC123");
        FileParamEnvelopeResult result = envelopeService.prepareFileParam("""
                {"fileName":"a.txt","initialKey":"serialized-key"}
                """);
        sourceFile.setFileParam(result.sanitizedFileParam());
        copiedFile.setFileParam(result.sanitizedFileParam());
        ArgumentCaptor<FileKeyEnvelope> envelopeCaptor = ArgumentCaptor.forClass(FileKeyEnvelope.class);
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenReturn(1);

        envelopeService.saveOwnerEnvelope(sourceFile, "hash-1", 100L, result);
        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        FileKeyEnvelope ownerEnvelope = envelopeCaptor.getValue();

        clearInvocations(fileKeyEnvelopeMapper, fileKeyAuditLogMapper);
        when(fileKeyEnvelopeMapper.selectOne(any())).thenReturn(ownerEnvelope);
        envelopeService.saveShareEnvelopes(share, java.util.List.of(sourceFile), 100L, "SHARE_CREATE");
        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        FileKeyEnvelope shareEnvelope = envelopeCaptor.getValue();

        clearInvocations(fileKeyEnvelopeMapper, fileKeyAuditLogMapper);
        when(fileKeyEnvelopeMapper.selectOne(any())).thenAnswer(invocation -> {
            assertEquals(1L, TenantContext.getTenantIdOrDefault());
            return shareEnvelope;
        });
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenAnswer(invocation -> {
            assertEquals(2L, TenantContext.getTenantIdOrDefault());
            return 1;
        });
        TenantContext.setTenantId(2L);
        try {
            envelopeService.saveCopiedOwnerEnvelope(
                    sourceFile,
                    copiedFile,
                    share,
                    200L,
                    200L,
                    "SHARE_SAVE_OWNER_ENVELOPE"
            );
        } finally {
            TenantContext.clear();
        }

        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        FileKeyEnvelope copiedOwnerEnvelope = envelopeCaptor.getValue();
        assertEquals(2L, copiedOwnerEnvelope.getTenantId());
        assertEquals(20L, copiedOwnerEnvelope.getFileId());
        assertEquals(FileKeyEnvelopeService.RECIPIENT_TYPE_OWNER, copiedOwnerEnvelope.getRecipientType());
        assertEquals(200L, copiedOwnerEnvelope.getRecipientId());

        clearInvocations(fileKeyEnvelopeMapper, fileKeyAuditLogMapper);
        org.mockito.Mockito.doAnswer(invocation -> {
                    assertEquals(2L, TenantContext.getTenantIdOrDefault());
                    return copiedOwnerEnvelope;
                })
                .when(fileKeyEnvelopeMapper).selectOne(any());
        TenantContext.setTenantId(2L);
        Optional<String> copiedInitialKey;
        try {
            copiedInitialKey = envelopeService.unwrapActiveOwnerInitialKey(
                    copiedFile,
                    "hash-1",
                    200L
            );
        } finally {
            TenantContext.clear();
        }
        assertThat(copiedInitialKey).contains("serialized-key");
    }

    /**
     * Verifies that copied-envelope creation rejects missing call and persistence identities.
     */
    @Test
    @DisplayName("should reject incomplete copied owner envelope context")
    void shouldRejectIncompleteCopiedOwnerEnvelopeContext() {
        FileShare share = new FileShare()
                .setId(300L)
                .setTenantId(1L)
                .setUserId(100L)
                .setShareCode("ABC123");

        assertThatThrownBy(() -> envelopeService.saveCopiedOwnerEnvelope(
                null,
                new File(),
                share,
                200L,
                200L,
                "SHARE_SAVE_OWNER_ENVELOPE"
        ))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getData())
                        .asString()
                        .contains("上下文不完整"));

        File sourceFile = new File()
                .setId(10L)
                .setTenantId(1L)
                .setUid(100L)
                .setFileHash("hash-1");
        File copiedFileWithoutId = new File()
                .setTenantId(2L)
                .setUid(200L)
                .setFileHash("hash-1");

        assertThatThrownBy(() -> envelopeService.saveCopiedOwnerEnvelope(
                sourceFile,
                copiedFileWithoutId,
                share,
                200L,
                200L,
                "SHARE_SAVE_OWNER_ENVELOPE"
        ))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getData())
                        .asString()
                        .contains("上下文不完整"));
    }

    /**
     * Verifies that copied-envelope creation remains a no-op for explicitly unencrypted source files.
     */
    @Test
    @DisplayName("should skip copied owner envelope for unencrypted source")
    void shouldSkipCopiedOwnerEnvelopeForUnencryptedSource() {
        File sourceFile = new File()
                .setId(10L)
                .setTenantId(1L)
                .setUid(100L)
                .setFileHash("hash-1")
                .setFileParam("""
                        {"fileName":"a.txt","encryptionAlgorithm":"NONE"}
                        """);
        File copiedFile = new File()
                .setId(20L)
                .setTenantId(2L)
                .setUid(200L)
                .setFileHash("hash-1");
        FileShare share = new FileShare()
                .setId(300L)
                .setTenantId(1L)
                .setUserId(100L)
                .setShareCode("ABC123");

        envelopeService.saveCopiedOwnerEnvelope(
                sourceFile,
                copiedFile,
                share,
                200L,
                200L,
                "SHARE_SAVE_OWNER_ENVELOPE"
        );

        verify(fileKeyEnvelopeMapper, org.mockito.Mockito.never()).selectOne(any());
        verify(fileKeyEnvelopeMapper, org.mockito.Mockito.never()).insert(any(FileKeyEnvelope.class));
    }

    /**
     * Verifies that copied-envelope creation falls back to the authorized source owner envelope.
     */
    @Test
    @DisplayName("should rewrap source owner key when share envelope is missing")
    void shouldRewrapSourceOwnerKeyWhenShareEnvelopeIsMissing() {
        File sourceFile = new File()
                .setId(10L)
                .setTenantId(1L)
                .setUid(100L)
                .setFileHash("hash-1");
        File copiedFile = new File()
                .setId(20L)
                .setTenantId(2L)
                .setUid(200L)
                .setFileHash("hash-1");
        FileShare share = new FileShare()
                .setId(300L)
                .setTenantId(1L)
                .setUserId(100L)
                .setShareCode("ABC123");
        FileParamEnvelopeResult result = envelopeService.prepareFileParam("""
                {"fileName":"a.txt","initialKey":"serialized-key"}
                """);
        sourceFile.setFileParam(result.sanitizedFileParam());
        copiedFile.setFileParam(result.sanitizedFileParam());
        ArgumentCaptor<FileKeyEnvelope> envelopeCaptor = ArgumentCaptor.forClass(FileKeyEnvelope.class);
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenReturn(1);
        envelopeService.saveOwnerEnvelope(sourceFile, "hash-1", 100L, result);
        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        FileKeyEnvelope sourceOwnerEnvelope = envelopeCaptor.getValue();

        clearInvocations(fileKeyEnvelopeMapper, fileKeyAuditLogMapper);
        when(fileKeyEnvelopeMapper.selectOne(any())).thenReturn(null, sourceOwnerEnvelope);
        TenantContext.setTenantId(2L);
        try {
            envelopeService.saveCopiedOwnerEnvelope(
                    sourceFile,
                    copiedFile,
                    share,
                    200L,
                    200L,
                    "SHARE_SAVE_OWNER_ENVELOPE"
            );
        } finally {
            TenantContext.clear();
        }

        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        FileKeyEnvelope copiedOwnerEnvelope = envelopeCaptor.getValue();
        assertEquals(FileKeyEnvelopeService.RECIPIENT_TYPE_OWNER, copiedOwnerEnvelope.getRecipientType());
        assertEquals(200L, copiedOwnerEnvelope.getRecipientId());
    }

    /**
     * Verifies that an encrypted copied file is rejected when neither share nor owner key material exists.
     */
    @Test
    @DisplayName("should reject copied encrypted file when key is missing")
    void shouldRejectCopiedEncryptedFileWhenKeyIsMissing() {
        File sourceFile = new File()
                .setId(10L)
                .setTenantId(1L)
                .setUid(100L)
                .setFileHash("hash-1")
                .setFileParam("""
                        {"fileName":"a.txt","encryptionAlgorithm":"AES-GCM"}
                        """);
        File copiedFile = new File()
                .setId(20L)
                .setTenantId(2L)
                .setUid(200L)
                .setFileHash("hash-1");
        FileShare share = new FileShare()
                .setId(300L)
                .setTenantId(1L)
                .setUserId(100L)
                .setShareCode("ABC123");
        when(fileKeyEnvelopeMapper.selectOne(any())).thenReturn(null);

        TenantContext.setTenantId(2L);
        try {
            assertThatThrownBy(() -> envelopeService.saveCopiedOwnerEnvelope(
                    sourceFile,
                    copiedFile,
                    share,
                    200L,
                    200L,
                    "SHARE_SAVE_OWNER_ENVELOPE"
            ))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getData())
                            .asString()
                            .contains("文件解密密钥不存在"));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Verifies that a legacy/plain copied file remains saveable when no key material exists.
     */
    @Test
    @DisplayName("should skip copied legacy plain file when key is missing")
    void shouldSkipCopiedLegacyPlainFileWhenKeyIsMissing() {
        File sourceFile = new File()
                .setId(10L)
                .setTenantId(1L)
                .setUid(100L)
                .setFileHash("hash-1")
                .setFileParam("""
                        {"fileName":"a.txt","fileSize":10}
                        """);
        File copiedFile = new File()
                .setId(20L)
                .setTenantId(2L)
                .setUid(200L)
                .setFileHash("hash-1");
        FileShare share = new FileShare()
                .setId(300L)
                .setTenantId(1L)
                .setUserId(100L)
                .setShareCode("ABC123");
        when(fileKeyEnvelopeMapper.selectOne(any())).thenReturn(null);

        TenantContext.setTenantId(2L);
        try {
            envelopeService.saveCopiedOwnerEnvelope(
                    sourceFile,
                    copiedFile,
                    share,
                    200L,
                    200L,
                    "SHARE_SAVE_OWNER_ENVELOPE"
            );
        } finally {
            TenantContext.clear();
        }

        verify(fileKeyEnvelopeMapper, org.mockito.Mockito.never()).insert(any(FileKeyEnvelope.class));
    }

    /**
     * Verifies that friend-share recipient envelopes are created and unwrapped by friend-share id.
     */
    @Test
    @DisplayName("should save and unwrap friend share recipient envelope")
    void shouldSaveAndUnwrapFriendShareRecipientEnvelope() {
        File file = new File()
                .setId(10L)
                .setTenantId(1L)
                .setUid(100L)
                .setFileHash("hash-1")
                .setFileParam("""
                        {"fileName":"a.txt"}
                        """);
        FriendFileShare share = new FriendFileShare()
                .setId(300L)
                .setTenantId(1L)
                .setSharerId(100L)
                .setFriendId(200L)
                .setFileHashes("[\"hash-1\"]")
                .setStatus(FriendFileShare.STATUS_ACTIVE);
        FileParamEnvelopeResult result = envelopeService.prepareFileParam("""
                {"fileName":"a.txt","initialKey":"serialized-key"}
                """);
        file.setFileParam(result.sanitizedFileParam());
        ArgumentCaptor<FileKeyEnvelope> envelopeCaptor = ArgumentCaptor.forClass(FileKeyEnvelope.class);
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenReturn(1);

        envelopeService.saveOwnerEnvelope(file, "hash-1", 100L, result);
        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        FileKeyEnvelope ownerEnvelope = envelopeCaptor.getValue();

        clearInvocations(fileKeyEnvelopeMapper, fileKeyAuditLogMapper);
        when(fileKeyEnvelopeMapper.selectOne(any())).thenReturn(ownerEnvelope);
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenReturn(1);

        envelopeService.saveFriendShareEnvelopes(share, java.util.List.of(file), 100L, "FRIEND_SHARE_CREATE");

        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        FileKeyEnvelope friendShareEnvelope = envelopeCaptor.getValue();
        assertEquals(FileKeyEnvelopeService.RECIPIENT_TYPE_FRIEND_SHARE, friendShareEnvelope.getRecipientType());
        assertEquals(300L, friendShareEnvelope.getRecipientId());
        assertEquals(FileKeyEnvelopeService.STATUS_ACTIVE, friendShareEnvelope.getStatus());

        clearInvocations(fileKeyEnvelopeMapper, fileKeyAuditLogMapper);
        when(fileKeyEnvelopeMapper.selectOne(any())).thenReturn(friendShareEnvelope);
        Optional<String> unwrapped = envelopeService.unwrapActiveFriendShareInitialKey(
                file,
                "hash-1",
                share,
                200L,
                "FRIEND_SHARE_DECRYPT"
        );

        assertTrue(unwrapped.isPresent());
        assertEquals("serialized-key", unwrapped.get());
        verify(fileKeyAuditLogMapper).insert(any(FileKeyAuditLog.class));
    }

    /**
     * Verifies that revoking a share marks its recipient envelopes unusable.
     */
    @Test
    @DisplayName("should revoke active share recipient envelopes")
    void shouldRevokeActiveShareRecipientEnvelopes() {
        FileShare share = new FileShare()
                .setId(200L)
                .setTenantId(1L)
                .setUserId(100L)
                .setShareCode("ABC123");
        FileKeyEnvelope shareEnvelope = new FileKeyEnvelope()
                .setId(500L)
                .setTenantId(1L)
                .setFileId(10L)
                .setFileHash("hash-1")
                .setRecipientType(FileKeyEnvelopeService.RECIPIENT_TYPE_SHARE)
                .setRecipientId(200L)
                .setKeyVersion(1)
                .setStatus(FileKeyEnvelopeService.STATUS_ACTIVE);
        when(fileKeyEnvelopeMapper.selectActiveRecipientForUpdate(
                1L, FileKeyEnvelopeService.RECIPIENT_TYPE_SHARE, 200L))
                .thenReturn(java.util.List.of(shareEnvelope));
        when(fileKeyEnvelopeMapper.compareAndSetStatus(
                1L, 500L, FileKeyEnvelopeService.STATUS_ACTIVE, FileKeyEnvelopeService.STATUS_REVOKED))
                .thenReturn(1);

        envelopeService.revokeShareEnvelopes(share, 100L, "USER_CANCEL_SHARE");

        verify(fileKeyEnvelopeMapper).compareAndSetStatus(
                1L, 500L, FileKeyEnvelopeService.STATUS_ACTIVE, FileKeyEnvelopeService.STATUS_REVOKED);
        verify(fileKeyAuditLogMapper).insert(any(FileKeyAuditLog.class));
    }

    /**
     * Verifies that revoking a friend share marks its recipient envelopes unusable.
     */
    @Test
    @DisplayName("should revoke active friend share recipient envelopes")
    void shouldRevokeActiveFriendShareRecipientEnvelopes() {
        FriendFileShare share = new FriendFileShare()
                .setId(300L)
                .setTenantId(1L)
                .setSharerId(100L)
                .setFriendId(200L)
                .setFileHashes("[\"hash-1\"]")
                .setStatus(FriendFileShare.STATUS_ACTIVE);
        FileKeyEnvelope friendShareEnvelope = new FileKeyEnvelope()
                .setId(600L)
                .setTenantId(1L)
                .setFileId(10L)
                .setFileHash("hash-1")
                .setRecipientType(FileKeyEnvelopeService.RECIPIENT_TYPE_FRIEND_SHARE)
                .setRecipientId(300L)
                .setKeyVersion(1)
                .setStatus(FileKeyEnvelopeService.STATUS_ACTIVE);
        when(fileKeyEnvelopeMapper.selectActiveRecipientForUpdate(
                1L, FileKeyEnvelopeService.RECIPIENT_TYPE_FRIEND_SHARE, 300L))
                .thenReturn(java.util.List.of(friendShareEnvelope));
        when(fileKeyEnvelopeMapper.compareAndSetStatus(
                1L, 600L, FileKeyEnvelopeService.STATUS_ACTIVE, FileKeyEnvelopeService.STATUS_REVOKED))
                .thenReturn(1);

        envelopeService.revokeFriendShareEnvelopes(share, 100L, "USER_CANCEL_FRIEND_SHARE");

        verify(fileKeyEnvelopeMapper).compareAndSetStatus(
                1L, 600L, FileKeyEnvelopeService.STATUS_ACTIVE, FileKeyEnvelopeService.STATUS_REVOKED);
        verify(fileKeyAuditLogMapper).insert(any(FileKeyAuditLog.class));
    }

    /**
     * Verifies that rotation rewraps previous active envelopes and skips target-version envelopes.
     */
    @Test
    @DisplayName("should rotate envelopes idempotently")
    void shouldRotateEnvelopesIdempotently() {
        properties.setLocalMasterKeys(Map.of(
                1, "previous-local-master-key",
                2, "new-local-master-key"
        ));
        properties.setKeyVersion(1);
        File file = new File()
                .setId(10L)
                .setTenantId(1L)
                .setUid(100L)
                .setFileHash("hash-1");
        FileParamEnvelopeResult result = envelopeService.prepareFileParam("""
                {"fileName":"a.txt","initialKey":"serialized-key"}
                """);
        ArgumentCaptor<FileKeyEnvelope> envelopeCaptor = ArgumentCaptor.forClass(FileKeyEnvelope.class);
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenReturn(1);

        envelopeService.saveOwnerEnvelope(file, "hash-1", 100L, result);
        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        FileKeyEnvelope previousEnvelope = envelopeCaptor.getValue().setId(501L);

        clearInvocations(fileKeyEnvelopeMapper, fileKeyAuditLogMapper);
        properties.setKeyVersion(2);
        when(fileKeyEnvelopeMapper.selectList(any())).thenReturn(java.util.List.of(previousEnvelope));
        when(fileKeyEnvelopeMapper.selectById(501L)).thenReturn(previousEnvelope);
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenReturn(1);

        KeyEnvelopeRotationResult rotation = envelopeService.rotateActiveFileEnvelopes(file, 900L, "ROTATE_TEST");

        assertEquals(1, rotation.rotatedCount());
        assertEquals(0, rotation.skippedCount());
        verify(rotationActivationService).activateVerifiedCandidate(
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(501L),
                any(), any(), org.mockito.ArgumentMatchers.eq(2));
        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        FileKeyEnvelope rotatedEnvelope = envelopeCaptor.getValue().setId(502L);
        assertEquals(2, rotatedEnvelope.getKeyVersion());
        assertEquals(FileKeyEnvelopeService.STATUS_PENDING_VERIFICATION, rotatedEnvelope.getStatus());

        clearInvocations(fileKeyEnvelopeMapper, fileKeyAuditLogMapper);
        when(fileKeyEnvelopeMapper.selectList(any())).thenReturn(java.util.List.of(rotatedEnvelope));
        when(fileKeyEnvelopeMapper.selectById(502L)).thenReturn(rotatedEnvelope);
        KeyEnvelopeRotationResult secondRotation = envelopeService.rotateActiveFileEnvelopes(file, 900L, "ROTATE_TEST");

        assertEquals(0, secondRotation.rotatedCount());
        assertEquals(1, secondRotation.skippedCount());
    }

    /**
     * Verifies automated rotation returns stable idempotent outcomes before any provider operation.
     */
    @Test
    void shouldHandleInvalidMissingRevokedAndAlreadyActivatedAutomationSources() {
        WrappingKeyReference target = wrappingRegistry.activeKeyReference(1).requireValue();
        FileKeyEnvelope revoked = new FileKeyEnvelope()
                .setId(502L)
                .setTenantId(1L)
                .setStatus(FileKeyEnvelopeService.STATUS_REVOKED);
        FileKeyEnvelope superseded = new FileKeyEnvelope()
                .setId(503L)
                .setTenantId(1L)
                .setStatus(FileKeyEnvelopeService.STATUS_SUPERSEDED);
        FileKeyEnvelope activated = new FileKeyEnvelope()
                .setId(603L)
                .setTenantId(1L)
                .setStatus(FileKeyEnvelopeService.STATUS_ACTIVE);
        when(fileKeyEnvelopeMapper.selectById(501L)).thenReturn(null);
        when(fileKeyEnvelopeMapper.selectById(502L)).thenReturn(revoked);
        when(fileKeyEnvelopeMapper.selectById(503L)).thenReturn(superseded);
        when(fileKeyEnvelopeMapper.selectById(603L)).thenReturn(activated);

        assertEquals("INVALID_REQUEST", envelopeService.rotateEnvelopeForAutomation(
                null, 601L, target, 1, 900L, "ROTATE_INVALID").failureCategory().name());
        assertEquals("SKIPPED_SOURCE_CHANGED", envelopeService.rotateEnvelopeForAutomation(
                501L, 601L, target, 1, 900L, "ROTATE_MISSING").outcome());
        assertEquals("SKIPPED_REVOKED", envelopeService.rotateEnvelopeForAutomation(
                502L, 602L, target, 1, 900L, "ROTATE_REVOKED").outcome());
        AutomatedEnvelopeRotationResult replay = envelopeService.rotateEnvelopeForAutomation(
                503L, 603L, target, 1, 900L, "ROTATE_REPLAY");
        assertEquals("SUCCEEDED", replay.outcome());
        assertEquals(603L, replay.candidateEnvelopeId());
    }

    /**
     * 验证 AAD hash 篡改在 provider 调用前失败并写入稳定审计分类。
     */
    @Test
    void shouldRejectTamperedAadHashWithStableAuditCategory() {
        File file = new File().setId(10L).setTenantId(1L).setUid(100L).setFileHash("hash-1");
        FileParamEnvelopeResult result = envelopeService.prepareFileParam(
                "{\"initialKey\":\"serialized-key\"}");
        ArgumentCaptor<FileKeyEnvelope> envelopeCaptor = ArgumentCaptor.forClass(FileKeyEnvelope.class);
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenReturn(1);
        envelopeService.saveOwnerEnvelope(file, "hash-1", 100L, result);
        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        FileKeyEnvelope tampered = envelopeCaptor.getValue().setAadHash("0".repeat(64));

        clearInvocations(fileKeyEnvelopeMapper, fileKeyAuditLogMapper);
        when(fileKeyEnvelopeMapper.selectOne(any())).thenReturn(tampered);

        assertThatThrownBy(() -> envelopeService.unwrapActiveOwnerInitialKey(file, "hash-1", 100L))
                .isInstanceOf(GeneralException.class);
        ArgumentCaptor<FileKeyAuditLog> auditCaptor = ArgumentCaptor.forClass(FileKeyAuditLog.class);
        verify(fileKeyAuditLogMapper).insert(auditCaptor.capture());
        assertEquals("INVALID_CIPHERTEXT", auditCaptor.getValue().getFailureCategory());
        assertThat(auditCaptor.getValue().getErrorMessage()).isNull();
    }

    /**
     * 验证历史未知 provider 严格失败关闭且不回退 active local。
     */
    @Test
    void shouldFailClosedForUnknownPersistedProvider() {
        File file = new File().setId(10L).setTenantId(1L).setUid(100L).setFileHash("hash-1");
        FileParamEnvelopeResult result = envelopeService.prepareFileParam(
                "{\"initialKey\":\"serialized-key\"}");
        ArgumentCaptor<FileKeyEnvelope> envelopeCaptor = ArgumentCaptor.forClass(FileKeyEnvelope.class);
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenReturn(1);
        envelopeService.saveOwnerEnvelope(file, "hash-1", 100L, result);
        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        FileKeyEnvelope unknown = envelopeCaptor.getValue().setKmsProvider("unknown-provider");

        clearInvocations(fileKeyEnvelopeMapper, fileKeyAuditLogMapper);
        when(fileKeyEnvelopeMapper.selectOne(any())).thenReturn(unknown);

        assertThatThrownBy(() -> envelopeService.unwrapActiveOwnerInitialKey(file, "hash-1", 100L))
                .isInstanceOf(GeneralException.class);
        ArgumentCaptor<FileKeyAuditLog> auditCaptor = ArgumentCaptor.forClass(FileKeyAuditLog.class);
        verify(fileKeyAuditLogMapper).insert(auditCaptor.capture());
        assertEquals("CONFIGURATION", auditCaptor.getValue().getFailureCategory());
    }

    /**
     * 验证未知 context schema 在解封前失败关闭并写入稳定审计分类。
     */
    @Test
    void shouldFailClosedForUnknownPersistedContextSchema() {
        File file = new File().setId(10L).setTenantId(1L).setUid(100L).setFileHash("hash-1");
        FileParamEnvelopeResult result = envelopeService.prepareFileParam(
                "{\"initialKey\":\"serialized-key\"}");
        ArgumentCaptor<FileKeyEnvelope> envelopeCaptor = ArgumentCaptor.forClass(FileKeyEnvelope.class);
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenReturn(1);
        envelopeService.saveOwnerEnvelope(file, "hash-1", 100L, result);
        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        FileKeyEnvelope unknown = envelopeCaptor.getValue().setContextSchema("unknown-context-v99");

        clearInvocations(fileKeyEnvelopeMapper, fileKeyAuditLogMapper);
        when(fileKeyEnvelopeMapper.selectOne(any())).thenReturn(unknown);

        assertThatThrownBy(() -> envelopeService.unwrapActiveOwnerInitialKey(file, "hash-1", 100L))
                .isInstanceOf(GeneralException.class);
        ArgumentCaptor<FileKeyAuditLog> auditCaptor = ArgumentCaptor.forClass(FileKeyAuditLog.class);
        verify(fileKeyAuditLogMapper).insert(auditCaptor.capture());
        assertEquals("INVALID_REQUEST", auditCaptor.getValue().getFailureCategory());
    }

    /**
     * 验证 rotation 使用完整目标身份，同逻辑版本但 key id 变化仍会重包封。
     */
    @Test
    void shouldRotateWhenFullTargetIdentityChangesAtSameLogicalVersion() {
        properties.setKmsKeyId("local-key-a");
        File file = new File().setId(10L).setTenantId(1L).setUid(100L).setFileHash("hash-1");
        FileParamEnvelopeResult result = envelopeService.prepareFileParam(
                "{\"initialKey\":\"serialized-key\"}");
        ArgumentCaptor<FileKeyEnvelope> envelopeCaptor = ArgumentCaptor.forClass(FileKeyEnvelope.class);
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenReturn(1);
        envelopeService.saveOwnerEnvelope(file, "hash-1", 100L, result);
        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        FileKeyEnvelope previous = envelopeCaptor.getValue().setId(501L);

        clearInvocations(fileKeyEnvelopeMapper, fileKeyAuditLogMapper);
        properties.setKmsKeyId("local-key-b");
        properties.getProviders().getLocal().setHistoricalKeyIds(Set.of("local-key-a"));
        when(fileKeyEnvelopeMapper.selectList(any())).thenReturn(java.util.List.of(previous));
        when(fileKeyEnvelopeMapper.selectById(501L)).thenReturn(previous);
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenReturn(1);

        KeyEnvelopeRotationResult rotation = envelopeService.rotateActiveFileEnvelopes(file, 900L, "ROTATE_KEY_ID");

        assertEquals(1, rotation.rotatedCount());
        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        assertEquals("local-key-b", envelopeCaptor.getValue().getKmsKeyId());
    }

    /**
     * 验证远程/配置包封失败发生在 supersede 之前，旧 active 信封保持可用。
     */
    @Test
    void shouldNotSupersedeOldEnvelopeWhenTargetWrapFails() {
        properties.setLocalMasterKeys(Map.of(1, "previous-local-master-key"));
        properties.setLocalMasterKey(null);
        properties.setKeyVersion(1);
        File file = new File().setId(10L).setTenantId(1L).setUid(100L).setFileHash("hash-1");
        FileParamEnvelopeResult result = envelopeService.prepareFileParam(
                "{\"initialKey\":\"serialized-key\"}");
        ArgumentCaptor<FileKeyEnvelope> envelopeCaptor = ArgumentCaptor.forClass(FileKeyEnvelope.class);
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenReturn(1);
        envelopeService.saveOwnerEnvelope(file, "hash-1", 100L, result);
        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        FileKeyEnvelope previous = envelopeCaptor.getValue().setId(501L);

        clearInvocations(fileKeyEnvelopeMapper, fileKeyAuditLogMapper);
        properties.setKeyVersion(2);
        when(fileKeyEnvelopeMapper.selectList(any())).thenReturn(java.util.List.of(previous));
        when(fileKeyEnvelopeMapper.selectById(501L)).thenReturn(previous);

        assertThatThrownBy(() -> envelopeService.rotateActiveFileEnvelopes(file, 900L, "ROTATE_FAIL"))
                .isInstanceOf(GeneralException.class);
        verify(fileKeyEnvelopeMapper, org.mockito.Mockito.never()).updateById(any(FileKeyEnvelope.class));
        verify(fileKeyEnvelopeMapper, org.mockito.Mockito.never()).insert(any(FileKeyEnvelope.class));
    }

    /**
     * 验证相同外部 named key 版本升级优先使用 provider 原生 rewrap。
     */
    @Test
    void shouldPreferNativeProviderRewrapForSameExternalKey() {
        properties.setActiveProvider(VaultTransitKeyWrappingProvider.PROVIDER_ID);
        properties.setKeyVersion(2);
        NativeRewrapProvider provider = new NativeRewrapProvider();
        KeyWrappingProviderRegistry registry = new KeyWrappingProviderRegistry(
                java.util.List.of(provider), properties, new SimpleMeterRegistry());
        CryptoAgilityProperties agilityProperties = new CryptoAgilityProperties();
        CryptoSuitePolicyService nativeSuitePolicy = new CryptoSuitePolicyService(
                properties,
                agilityProperties,
                new CryptoSuiteRegistry(agilityProperties, new SimpleMeterRegistry()),
                registry,
                tenantCryptoPolicyMapper);
        FileKeyEnvelopeService service = new FileKeyEnvelopeService(
                fileKeyEnvelopeMapper,
                fileKeyAuditLogMapper,
                registry,
                properties,
                nativeSuitePolicy,
                rotationActivationService);
        File file = new File().setId(10L).setTenantId(1L).setUid(100L).setFileHash("hash-1");
        WrappingContext sourceContext = new WrappingContext(
                1L,
                10L,
                "hash-1",
                FileKeyEnvelopeService.RECIPIENT_TYPE_OWNER,
                100L,
                1,
                properties.getAlgorithmSuite(),
                WrappingContext.EXTERNAL_CONTEXT_V2);
        FileKeyEnvelope previous = new FileKeyEnvelope()
                .setId(501L)
                .setTenantId(1L)
                .setFileId(10L)
                .setFileHash("hash-1")
                .setRecipientType(FileKeyEnvelopeService.RECIPIENT_TYPE_OWNER)
                .setRecipientId(100L)
                .setKeyVersion(1)
                .setAlgorithmSuite(properties.getAlgorithmSuite())
                .setSignatureSuite(properties.getSignatureSuite())
                .setKemSuite(properties.getKemSuite())
                .setProofSuite(properties.getProofSuite())
                .setEncryptionAlgorithm(properties.getEncryptionAlgorithm())
                .setWrappingAlgorithm(CryptoSuiteIds.VAULT_TRANSIT_WRAPPING)
                .setKmsProvider(VaultTransitKeyWrappingProvider.PROVIDER_ID)
                .setProviderContractVersion(1)
                .setKmsKeyId("shared-named-key")
                .setProviderKeyVersion("1")
                .setContextSchema(WrappingContext.EXTERNAL_CONTEXT_V2)
                .setEncryptedDataKey("native:v1:ciphertext")
                .setAadHash(sourceContext.sha256Hex())
                .setStatus(FileKeyEnvelopeService.STATUS_ACTIVE)
                .setDeleted(0);
        when(fileKeyEnvelopeMapper.selectList(any())).thenReturn(java.util.List.of(previous));
        when(fileKeyEnvelopeMapper.selectById(501L)).thenReturn(previous);
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenReturn(1);

        KeyEnvelopeRotationResult result = service.rotateActiveFileEnvelopes(file, 900L, "NATIVE_REWRAP");

        assertEquals(1, result.rotatedCount());
        assertEquals(1, provider.rewrapCalls);
        assertEquals(2, provider.unwrapCalls);
        assertEquals(0, provider.wrapCalls);
        ArgumentCaptor<FileKeyEnvelope> inserted = ArgumentCaptor.forClass(FileKeyEnvelope.class);
        verify(fileKeyEnvelopeMapper).insert(inserted.capture());
        assertEquals("2", inserted.getValue().getProviderKeyVersion());
        assertEquals("native:v2:ciphertext", inserted.getValue().getEncryptedDataKey());
    }

    /**
     * 验证轮换遇到未知历史 context schema 时先审计失败且不替换旧 active 信封。
     */
    @Test
    void shouldAuditUnknownContextSchemaBeforeRotationMutation() {
        File file = new File().setId(10L).setTenantId(1L).setUid(100L).setFileHash("hash-1");
        FileParamEnvelopeResult result = envelopeService.prepareFileParam(
                "{\"initialKey\":\"serialized-key\"}");
        ArgumentCaptor<FileKeyEnvelope> envelopeCaptor = ArgumentCaptor.forClass(FileKeyEnvelope.class);
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenReturn(1);
        envelopeService.saveOwnerEnvelope(file, "hash-1", 100L, result);
        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        FileKeyEnvelope previous = envelopeCaptor.getValue()
                .setId(501L)
                .setContextSchema("unknown-context-v99");

        clearInvocations(fileKeyEnvelopeMapper, fileKeyAuditLogMapper);
        properties.setKeyVersion(2);
        when(fileKeyEnvelopeMapper.selectList(any())).thenReturn(java.util.List.of(previous));
        when(fileKeyEnvelopeMapper.selectById(501L)).thenReturn(previous);

        assertThatThrownBy(() -> envelopeService.rotateActiveFileEnvelopes(file, 900L, "UNKNOWN_CONTEXT"))
                .isInstanceOf(GeneralException.class);
        verify(fileKeyEnvelopeMapper, org.mockito.Mockito.never()).updateById(any(FileKeyEnvelope.class));
        verify(fileKeyEnvelopeMapper, org.mockito.Mockito.never()).insert(any(FileKeyEnvelope.class));
        ArgumentCaptor<FileKeyAuditLog> auditCaptor = ArgumentCaptor.forClass(FileKeyAuditLog.class);
        verify(fileKeyAuditLogMapper).insert(auditCaptor.capture());
        assertEquals("INVALID_REQUEST", auditCaptor.getValue().getFailureCategory());
    }

    /**
     * 验证目标身份已相等时仍先校验 AAD，篡改信封不能被幂等分支误报为跳过成功。
     */
    @Test
    void shouldRejectTamperedAadBeforeIdempotentRotationSkip() {
        File file = new File().setId(10L).setTenantId(1L).setUid(100L).setFileHash("hash-1");
        FileParamEnvelopeResult result = envelopeService.prepareFileParam(
                "{\"initialKey\":\"serialized-key\"}");
        ArgumentCaptor<FileKeyEnvelope> envelopeCaptor = ArgumentCaptor.forClass(FileKeyEnvelope.class);
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenReturn(1);
        envelopeService.saveOwnerEnvelope(file, "hash-1", 100L, result);
        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        FileKeyEnvelope tampered = envelopeCaptor.getValue()
                .setId(501L)
                .setAadHash("0".repeat(64));

        clearInvocations(fileKeyEnvelopeMapper, fileKeyAuditLogMapper);
        when(fileKeyEnvelopeMapper.selectList(any())).thenReturn(java.util.List.of(tampered));
        when(fileKeyEnvelopeMapper.selectById(501L)).thenReturn(tampered);

        assertThatThrownBy(() -> envelopeService.rotateActiveFileEnvelopes(
                file, 900L, "TAMPERED_IDEMPOTENT_ROTATION"))
                .isInstanceOf(GeneralException.class);
        verify(fileKeyEnvelopeMapper, org.mockito.Mockito.never()).updateById(any(FileKeyEnvelope.class));
        verify(fileKeyEnvelopeMapper, org.mockito.Mockito.never()).insert(any(FileKeyEnvelope.class));
        ArgumentCaptor<FileKeyAuditLog> auditCaptor = ArgumentCaptor.forClass(FileKeyAuditLog.class);
        verify(fileKeyAuditLogMapper).insert(auditCaptor.capture());
        assertEquals("INVALID_CIPHERTEXT", auditCaptor.getValue().getFailureCategory());
        assertEquals("FAILURE", auditCaptor.getValue().getResult());
    }

    /**
     * 构造 grant 路由测试使用的完整 ACTIVE 信封。
     */
    private FileKeyEnvelope grantEnvelope(Long envelopeId, String recipientType, Long recipientId) {
        return new FileKeyEnvelope()
                .setId(envelopeId)
                .setTenantId(1L)
                .setFileId(10L)
                .setFileHash("hash-1")
                .setRecipientType(recipientType)
                .setRecipientId(recipientId)
                .setKeyVersion(1)
                .setAlgorithmSuite(properties.getAlgorithmSuite())
                .setSignatureSuite(properties.getSignatureSuite())
                .setKemSuite(properties.getKemSuite())
                .setProofSuite(properties.getProofSuite())
                .setEncryptionAlgorithm(properties.getEncryptionAlgorithm())
                .setWrappingAlgorithm(CryptoSuiteIds.LOCAL_WRAPPING)
                .setKmsProvider(LocalKeyWrappingService.PROVIDER_ID)
                .setProviderContractVersion(1)
                .setKmsKeyId(properties.getKmsKeyId())
                .setProviderKeyVersion("1")
                .setContextSchema(WrappingContext.LOCAL_AAD_V1)
                .setEncryptedDataKey("test-ciphertext")
                .setAadHash("0".repeat(64))
                .setStatus(FileKeyEnvelopeService.STATUS_ACTIVE)
                .setDeleted(0);
    }

    /**
     * 记录原生 rewrap 调用并拒绝编排层明文往返的测试 provider。
     */
    private static final class NativeRewrapProvider implements KeyWrappingProvider {

        private int wrapCalls;
        private int unwrapCalls;
        private int rewrapCalls;

        @Override
        public String providerId() {
            return VaultTransitKeyWrappingProvider.PROVIDER_ID;
        }

        @Override
        public int contractVersion() {
            return 1;
        }

        @Override
        public Set<KeyWrappingCapability> capabilities() {
            return Set.of(
                    KeyWrappingCapability.WRAP,
                    KeyWrappingCapability.UNWRAP,
                    KeyWrappingCapability.NATIVE_REWRAP_SAME_KEY);
        }

        @Override
        public Set<String> supportedWrappingAlgorithms() {
            return Set.of(CryptoSuiteIds.VAULT_TRANSIT_WRAPPING);
        }

        @Override
        public KeyWrappingResult<WrappingKeyReference> activeKeyReference(Integer logicalKeyVersion) {
            return KeyWrappingResult.success(new WrappingKeyReference(
                    providerId(),
                    contractVersion(),
                    "shared-named-key",
                    "2",
                    CryptoSuiteIds.VAULT_TRANSIT_WRAPPING,
                    WrappingContext.EXTERNAL_CONTEXT_V2));
        }

        @Override
        public KeyWrappingResult<WrappedDataKey> wrap(KeyWrapRequest request) {
            wrapCalls++;
            return KeyWrappingResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.INTERNAL, false));
        }

        @Override
        public KeyWrappingResult<PlaintextDataKey> unwrap(KeyUnwrapRequest request) {
            unwrapCalls++;
            return KeyWrappingResult.success(PlaintextDataKey.of("serialized-key"));
        }

        @Override
        public KeyWrappingResult<WrappedDataKey> rewrap(KeyRewrapRequest request) {
            rewrapCalls++;
            return KeyWrappingResult.success(new WrappedDataKey(
                    "native:v2:ciphertext",
                    null,
                    request.target(),
                    request.logicalKeyVersion()));
        }

        @Override
        public KeyWrappingProviderDiagnostics diagnostics() {
            return new KeyWrappingProviderDiagnostics(
                    providerId(), contractVersion(), capabilities(), true, "configured");
        }
    }
}
