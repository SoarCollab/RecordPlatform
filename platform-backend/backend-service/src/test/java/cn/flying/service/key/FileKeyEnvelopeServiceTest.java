package cn.flying.service.key;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.JsonConverter;
import cn.flying.dao.dto.File;
import cn.flying.dao.dto.FileShare;
import cn.flying.dao.entity.FriendFileShare;
import cn.flying.dao.entity.FileKeyAuditLog;
import cn.flying.dao.entity.FileKeyEnvelope;
import cn.flying.dao.mapper.FileKeyAuditLogMapper;
import cn.flying.dao.mapper.FileKeyEnvelopeMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

    private FileKeyEnvelopeProperties properties;
    private LocalKeyWrappingService wrappingService;
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
        properties = new FileKeyEnvelopeProperties();
        properties.setLocalMasterKey("test-master-key-with-enough-entropy");
        wrappingService = new LocalKeyWrappingService(properties);
        suitePolicy = new CryptoSuitePolicyService(properties);
        envelopeService = new FileKeyEnvelopeService(fileKeyEnvelopeMapper, fileKeyAuditLogMapper, wrappingService, properties, suitePolicy);
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
        ArgumentCaptor<FileKeyEnvelope> envelopeCaptor = ArgumentCaptor.forClass(FileKeyEnvelope.class);
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenReturn(1);

        envelopeService.saveOwnerEnvelope(file, "hash-1", 100L, result);

        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        FileKeyEnvelope envelope = envelopeCaptor.getValue();
        assertThat(envelope.getEncryptedDataKey()).isNotBlank();
        assertEquals("UNSIGNED-V1", envelope.getSignatureSuite());
        assertEquals("NONE-V1", envelope.getKemSuite());
        assertEquals("RP-MERKLE-SHA256-V1", envelope.getProofSuite());
        assertEquals(FileKeyEnvelopeService.STATUS_ACTIVE, envelope.getStatus());

        when(fileKeyEnvelopeMapper.selectOne(any())).thenReturn(envelope);
        Optional<String> unwrapped = envelopeService.unwrapActiveOwnerInitialKey(file, "hash-1", 100L);

        assertTrue(unwrapped.isPresent());
        assertEquals("serialized-key", unwrapped.get());
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
        when(fileKeyEnvelopeMapper.selectList(any())).thenReturn(java.util.List.of(shareEnvelope));
        ArgumentCaptor<FileKeyEnvelope> updateCaptor = ArgumentCaptor.forClass(FileKeyEnvelope.class);

        envelopeService.revokeShareEnvelopes(share, 100L, "USER_CANCEL_SHARE");

        verify(fileKeyEnvelopeMapper).updateById(updateCaptor.capture());
        assertEquals(FileKeyEnvelopeService.STATUS_REVOKED, updateCaptor.getValue().getStatus());
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
        when(fileKeyEnvelopeMapper.selectList(any())).thenReturn(java.util.List.of(friendShareEnvelope));
        ArgumentCaptor<FileKeyEnvelope> updateCaptor = ArgumentCaptor.forClass(FileKeyEnvelope.class);

        envelopeService.revokeFriendShareEnvelopes(share, 100L, "USER_CANCEL_FRIEND_SHARE");

        verify(fileKeyEnvelopeMapper).updateById(updateCaptor.capture());
        assertEquals(FileKeyEnvelopeService.STATUS_REVOKED, updateCaptor.getValue().getStatus());
        verify(fileKeyAuditLogMapper).insert(any(FileKeyAuditLog.class));
    }

    /**
     * Verifies that rotation rewraps old active envelopes and skips target-version envelopes.
     */
    @Test
    @DisplayName("should rotate envelopes idempotently")
    void shouldRotateEnvelopesIdempotently() {
        properties.setLocalMasterKeys(Map.of(
                1, "old-local-master-key",
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
        FileKeyEnvelope oldEnvelope = envelopeCaptor.getValue().setId(501L);

        clearInvocations(fileKeyEnvelopeMapper, fileKeyAuditLogMapper);
        properties.setKeyVersion(2);
        when(fileKeyEnvelopeMapper.selectList(any())).thenReturn(java.util.List.of(oldEnvelope));
        when(fileKeyEnvelopeMapper.selectCount(any())).thenReturn(0L);
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenReturn(1);

        KeyEnvelopeRotationResult rotation = envelopeService.rotateActiveFileEnvelopes(file, 900L, "ROTATE_TEST");

        assertEquals(1, rotation.rotatedCount());
        assertEquals(0, rotation.skippedCount());
        verify(fileKeyEnvelopeMapper).updateById(any(FileKeyEnvelope.class));
        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        FileKeyEnvelope rotatedEnvelope = envelopeCaptor.getValue().setId(502L);
        assertEquals(2, rotatedEnvelope.getKeyVersion());

        clearInvocations(fileKeyEnvelopeMapper, fileKeyAuditLogMapper);
        when(fileKeyEnvelopeMapper.selectList(any())).thenReturn(java.util.List.of(rotatedEnvelope));
        KeyEnvelopeRotationResult secondRotation = envelopeService.rotateActiveFileEnvelopes(file, 900L, "ROTATE_TEST");

        assertEquals(0, secondRotation.rotatedCount());
        assertEquals(1, secondRotation.skippedCount());
    }
}
