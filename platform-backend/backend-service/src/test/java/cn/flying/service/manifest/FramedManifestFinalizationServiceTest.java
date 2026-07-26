package cn.flying.service.manifest;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.dao.dto.File;
import cn.flying.dao.vo.file.FileUploadState;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.service.encryption.FramedAeadCrypto;
import cn.flying.service.encryption.FramedAeadWriter;
import cn.flying.service.remote.FileRemoteClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证普通 v2 manifest 的生成、DB SUCCESS 恢复和失败证据保留边界。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FramedManifestFinalizationService")
class FramedManifestFinalizationServiceTest {

    private static final Long USER_ID = 100L;
    private static final Long TENANT_ID = 77L;
    private static final Long FILE_ID = 9001L;
    private static final String FILE_HASH = "chain-file-hash";
    private static final int FRAME_SIZE = 64 * 1024;
    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] FILE_DEK = HEX.parseHex(
            "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf");
    private static final byte[] FILE_NONCE = HEX.parseHex("0102030405060708090a0b0c0d0e0f10");
    private static final String PLAIN_HASH =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String CIPHER_HASH =
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String MANIFEST_HASH =
            "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

    @Mock
    private FileRemoteClient fileRemoteClient;

    @Mock
    private ChunkManifestService chunkManifestService;

    @Mock
    private FramedAeadWriter framedAeadWriter;

    @InjectMocks
    private FramedManifestFinalizationService finalizationService;

    @TempDir
    Path tempDir;

    /**
     * 验证 DB SUCCESS 重试在临时目录已经清理时复用并严格校验 active manifest。
     */
    @Test
    void ensureManifest_shouldReuseValidatedActiveManifestDuringDbSuccessRecovery() {
        File file = framedFile();
        FileUploadState state = framedState();
        ChunkManifestView active = activeManifest();
        when(chunkManifestService.findActiveManifest(USER_ID, FILE_ID)).thenReturn(Optional.of(active));
        when(chunkManifestService.calculateManifestHash(any())).thenReturn(MANIFEST_HASH);

        Optional<ChunkManifestView> result = finalizationService.ensureManifest(
                USER_ID, file, state, List.of(), List.of());

        assertThat(result).containsSame(active);
        verify(chunkManifestService).calculateManifestHash(any());
        verify(chunkManifestService, never()).saveManifest(anyLong(), anyLong(), any());
        verifyNoInteractions(fileRemoteClient, framedAeadWriter);
    }

    /**
     * 验证 active manifest 的 storagePath 被替换时，在任何远程读取或保存前 fail closed。
     */
    @Test
    void ensureManifest_shouldRejectStoragePathSubstitutionBeforeReuseOrSave() {
        File file = framedFile();
        FileUploadState state = framedState();
        ChunkManifestChunk substitutedChunk = new ChunkManifestChunk(
                0,
                PLAIN_HASH,
                CIPHER_HASH,
                105L,
                "storage/tenant/" + TENANT_ID + "/chunk/sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                "S3",
                null,
                "SHA-256",
                33L,
                1);
        ChunkManifestView substituted = new ChunkManifestView(
                11L,
                FILE_ID,
                1,
                ChunkManifestCanonicalizer.SCHEMA_ID,
                FILE_HASH,
                MANIFEST_HASH,
                ChunkManifestCanonicalizer.HASH_ALGORITHM,
                33L,
                1,
                33L,
                null,
                "FRAMED_AEAD_V2",
                "S3",
                encryptionDescriptor(),
                List.of(substitutedChunk));
        when(chunkManifestService.findActiveManifest(USER_ID, FILE_ID)).thenReturn(Optional.of(substituted));

        GeneralException failure = assertThrows(GeneralException.class, () -> finalizationService.ensureManifest(
                USER_ID, file, state, List.of(), List.of()));

        assertThat(failure.getData()).asString().contains("active");
        verifyNoInteractions(fileRemoteClient, framedAeadWriter);
        verify(chunkManifestService, never()).saveManifest(anyLong(), anyLong(), any());
    }

    /**
     * 验证 active manifest 含 null 分片时 fail closed，而不是在校验字段时抛出 NPE。
     */
    @Test
    void ensureManifest_shouldRejectNullActiveManifestChunk() {
        File file = framedFile();
        FileUploadState state = framedState();
        ChunkManifestView active = new ChunkManifestView(
                12L,
                FILE_ID,
                1,
                ChunkManifestCanonicalizer.SCHEMA_ID,
                FILE_HASH,
                MANIFEST_HASH,
                ChunkManifestCanonicalizer.HASH_ALGORITHM,
                33L,
                1,
                33L,
                null,
                "FRAMED_AEAD_V2",
                "S3",
                encryptionDescriptor(),
                Collections.singletonList(null));
        when(chunkManifestService.findActiveManifest(USER_ID, FILE_ID)).thenReturn(Optional.of(active));

        GeneralException failure = assertThrows(GeneralException.class, () -> finalizationService.ensureManifest(
                USER_ID, file, state, List.of(), List.of()));

        assertThat(failure.getResultEnum()).isEqualTo(ResultEnum.FILE_RECORD_ERROR);
        assertThat(String.valueOf(failure.getData())).contains("active v2 manifest");
        verifyNoInteractions(fileRemoteClient, framedAeadWriter);
        verify(chunkManifestService, never()).saveManifest(anyLong(), anyLong(), any());
    }

    /**
     * 验证 manifest 保存失败时保留已生成对象文件，并且错误上下文不包含 DEK/nonce。
     */
    @Test
    void ensureManifest_shouldRetainProcessedEvidenceWhenManifestSaveFails() throws IOException {
        File file = framedFile();
        FileUploadState state = framedState();
        Path processed = tempDir.resolve("encrypted_chunk_0");
        Files.write(processed, new byte[]{1, 2, 3});
        when(chunkManifestService.findActiveManifest(USER_ID, FILE_ID)).thenReturn(Optional.empty());
        when(fileRemoteClient.getFile(String.valueOf(USER_ID), FILE_HASH))
                .thenReturn(Result.success(chainDetail(CIPHER_HASH, storagePath(CIPHER_HASH))));
        when(framedAeadWriter.verify(
                eq(processed), eq(FILE_DEK), eq(FILE_NONCE), eq(0), eq(1), eq(FRAME_SIZE)))
                .thenReturn(new FramedAeadWriter.WriteResult(33L, 105L, 1, PLAIN_HASH, CIPHER_HASH));
        when(chunkManifestService.calculateManifestHash(any())).thenReturn(MANIFEST_HASH);
        doThrow(new GeneralException(ResultEnum.FILE_RECORD_ERROR, "manifest store unavailable"))
                .when(chunkManifestService).saveManifest(eq(USER_ID), eq(FILE_ID), any());

        GeneralException failure = assertThrows(GeneralException.class, () -> finalizationService.ensureManifest(
                USER_ID, file, state, List.of(processed.toFile()), List.of(CIPHER_HASH)));

        assertThat(Files.isRegularFile(processed)).isTrue();
        assertThat(String.valueOf(failure.getData()))
                .doesNotContain(Base64.getEncoder().encodeToString(FILE_DEK))
                .doesNotContain(Base64.getEncoder().encodeToString(FILE_NONCE));
        ArgumentCaptor<ChunkManifestDraft> draftCaptor = ArgumentCaptor.forClass(ChunkManifestDraft.class);
        verify(chunkManifestService).saveManifest(eq(USER_ID), eq(FILE_ID), draftCaptor.capture());
        assertThat(draftCaptor.getValue().chunks().getFirst().storagePath())
                .isEqualTo(storagePath(CIPHER_HASH));
        assertThat(draftCaptor.getValue().encryption()).isEqualTo(encryptionDescriptor());
    }

    /**
     * 验证 direct NONE 会话不被 framed finalizer 接管，也不会触发链查询、writer 或 manifest 保存。
     */
    @Test
    void ensureManifest_shouldLeaveDirectNoneUploadUntouched() {
        File file = new File()
                .setId(FILE_ID)
                .setUid(USER_ID)
                .setTenantId(TENANT_ID)
                .setFileHash(FILE_HASH)
                .setFileSize(33L)
                .setVersion(1)
                .setStatus(FileUploadStatus.SUCCESS.getCode())
                .setFileParam("{\"uploadMode\":\"DIRECT_MULTIPART\",\"encryptionAlgorithm\":\"NONE\"}");
        FileUploadState state = new FileUploadState(USER_ID, "direct.bin", 33L,
                "application/octet-stream", "direct-client", 33, 1);
        state.setTenantId(TENANT_ID);
        state.setDirectUpload(true);

        assertThat(finalizationService.ensureManifest(USER_ID, file, state, List.of(), List.of()))
                .isEmpty();
        verifyNoInteractions(fileRemoteClient, chunkManifestService, framedAeadWriter);
    }

    /**
     * 验证 fileParam 明确声明 v2 但 Redis 检查点残缺时不会静默降级为 legacy。
     */
    @Test
    void ensureManifest_shouldRejectIncompleteExplicitV2Checkpoint() {
        File file = framedFile();
        FileUploadState state = new FileUploadState(USER_ID, "broken.bin", 33L,
                "application/octet-stream", "broken-client", 33, 1);
        state.setTenantId(TENANT_ID);
        state.setEncryptionFormatVersion(FramedAeadCrypto.FORMAT_VERSION);

        GeneralException failure = assertThrows(GeneralException.class, () -> finalizationService.ensureManifest(
                USER_ID, file, state, List.of(), List.of()));

        assertThat(failure.getResultEnum()).isEqualTo(ResultEnum.FILE_RECORD_ERROR);
        verifyNoInteractions(fileRemoteClient, chunkManifestService, framedAeadWriter);
    }

    /**
     * 构造完整 framed v2 文件记录。
     */
    private File framedFile() {
        return new File()
                .setId(FILE_ID)
                .setUid(USER_ID)
                .setTenantId(TENANT_ID)
                .setFileHash(FILE_HASH)
                .setFileSize(33L)
                .setVersion(1)
                .setStatus(FileUploadStatus.SUCCESS.getCode())
                .setFileParam("{"
                        + "\"fileSize\":33,"
                        + "\"chunkSize\":33,"
                        + "\"chunkCount\":1,"
                        + "\"encryptionAlgorithm\":\"FRAMED_AEAD_V2\","
                        + "\"algorithmSuite\":\"RP-AES256-GCM-FRAMED-V2\","
                        + "\"fileNonce\":\"AQIDBAUGBwgJCgsMDQ4PEA\","
                        + "\"keyDerivation\":\"HKDF-SHA256\","
                        + "\"nonceDerivation\":\"HKDF-SHA256\","
                        + "\"aadSchema\":\"cn.flying.framed-aead.aad.v2\","
                        + "\"formatVersion\":2,\"framePlainSize\":65536,\"tagSize\":16"
                        + "}");
    }

    /**
     * 构造完整 framed v2 Redis 检查点，模拟重试时稳定的 DEK/nonce 和上传计划。
     */
    private FileUploadState framedState() {
        FileUploadState state = new FileUploadState(USER_ID, "framed.bin", 33L,
                "application/octet-stream", "framed-client", 33, 1);
        state.setTenantId(TENANT_ID);
        state.setSuid("encoded-user");
        state.setPreparedFileId(FILE_ID);
        state.setEncryptionRecoveryVersion(2);
        state.setEncryptionFormatVersion(FramedAeadCrypto.FORMAT_VERSION);
        state.setEncryptionAlgorithmSuite(ChunkManifestEncryption.SUITE_FRAMED_V2);
        state.setFileDataKey(FILE_DEK.clone());
        state.setFileNonce(FILE_NONCE.clone());
        state.setFramePlainSize(FRAME_SIZE);
        state.setKeyDerivation(ChunkManifestEncryption.DERIVATION_HKDF_SHA256);
        state.setNonceDerivation(ChunkManifestEncryption.DERIVATION_HKDF_SHA256);
        state.setAadSchema(ChunkManifestEncryption.AAD_SCHEMA_FRAMED_V2);
        state.setTagSize(FramedAeadCrypto.TAG_SIZE);
        state.setManifestHash(MANIFEST_HASH);
        return state;
    }

    /**
     * 构造与固定 v2 检查点一致的加密描述。
     */
    private ChunkManifestEncryption encryptionDescriptor() {
        return new ChunkManifestEncryption(
                FramedAeadCrypto.FORMAT_VERSION,
                ChunkManifestEncryption.SUITE_FRAMED_V2,
                "AQIDBAUGBwgJCgsMDQ4PEA",
                FRAME_SIZE,
                ChunkManifestEncryption.DERIVATION_HKDF_SHA256,
                ChunkManifestEncryption.DERIVATION_HKDF_SHA256,
                ChunkManifestEncryption.AAD_SCHEMA_FRAMED_V2,
                FramedAeadCrypto.TAG_SIZE);
    }

    /**
     * 构造通过租户和摘要绑定的最终对象路径。
     */
    private String storagePath(String cipherHash) {
        return "storage/tenant/" + TENANT_ID + "/chunk/" + cipherHash;
    }

    /**
     * 构造链上对象引用详情，模拟普通上传存证结果。
     */
    private FileDetailVO chainDetail(String cipherHash, String storagePath) {
        String content = "[{\"index\":0,\"cipherHash\":\"" + cipherHash
                + "\",\"storagePath\":\"" + storagePath + "\"}]";
        return new FileDetailVO(
                String.valueOf(USER_ID), "framed.bin", "{}", content,
                FILE_HASH, "2026-07-26T00:00:00Z", 1L, 33L,
                "application/octet-stream");
    }

    /**
     * 构造具有精确尺寸和 frame 证据的 active manifest。
     */
    private ChunkManifestView activeManifest() {
        return new ChunkManifestView(
                10L,
                FILE_ID,
                1,
                ChunkManifestCanonicalizer.SCHEMA_ID,
                FILE_HASH,
                MANIFEST_HASH,
                ChunkManifestCanonicalizer.HASH_ALGORITHM,
                33L,
                1,
                33L,
                null,
                "FRAMED_AEAD_V2",
                "S3",
                encryptionDescriptor(),
                List.of(new ChunkManifestChunk(
                        0, PLAIN_HASH, CIPHER_HASH, 105L,
                        storagePath(CIPHER_HASH), "S3", null, "SHA-256", 33L, 1)));
    }
}
