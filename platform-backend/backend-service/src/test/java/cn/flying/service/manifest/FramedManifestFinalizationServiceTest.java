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
import cn.flying.service.support.StoredObjectReference;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

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
     * 验证首次最终化会保存并返回由链上路径与 writer 认证共同构造的 active manifest。
     */
    @Test
    void ensureManifest_shouldSaveVerifiedManifestForFirstFinalization() throws IOException {
        File file = framedFile();
        FileUploadState state = framedState();
        Path processed = tempDir.resolve("encrypted_chunk_0");
        Files.write(processed, new byte[]{1, 2, 3});
        ChunkManifestView saved = activeManifest();
        when(chunkManifestService.findActiveManifest(USER_ID, FILE_ID)).thenReturn(Optional.empty());
        when(fileRemoteClient.getFile(String.valueOf(USER_ID), FILE_HASH))
                .thenReturn(Result.success(chainDetail(CIPHER_HASH, storagePath(CIPHER_HASH))));
        when(framedAeadWriter.verify(
                eq(processed), eq(FILE_DEK), eq(FILE_NONCE), eq(0), eq(1), eq(FRAME_SIZE)))
                .thenReturn(new FramedAeadWriter.WriteResult(33L, 105L, 1, PLAIN_HASH, CIPHER_HASH));
        when(chunkManifestService.calculateManifestHash(any())).thenReturn(MANIFEST_HASH);
        when(chunkManifestService.saveManifest(eq(USER_ID), eq(FILE_ID), any())).thenReturn(saved);

        Optional<ChunkManifestView> result = finalizationService.ensureManifest(
                USER_ID, file, state, List.of(processed.toFile()), List.of(CIPHER_HASH));

        assertThat(result).containsSame(saved);
        verify(chunkManifestService).saveManifest(eq(USER_ID), eq(FILE_ID), any());
    }

    /**
     * 验证 Redis 中的 manifest hash 检查点与当前对象证据不一致时禁止保存。
     */
    @Test
    void ensureManifest_shouldRejectManifestCheckpointMismatch() throws IOException {
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
        when(chunkManifestService.calculateManifestHash(any()))
                .thenReturn("sha256:" + "d".repeat(64));

        GeneralException failure = assertThrows(GeneralException.class, () -> finalizationService.ensureManifest(
                USER_ID, file, state, List.of(processed.toFile()), List.of(CIPHER_HASH)));

        assertThat(failure.getData()).asString().contains("manifest 检查点");
        verify(chunkManifestService, never()).saveManifest(anyLong(), anyLong(), any());
    }

    /**
     * 验证恢复版本、分片计划和 manifest hash 格式均属于不可变检查点。
     */
    @Test
    void ensureManifest_shouldRejectInvalidFramedStateVariants() {
        FileUploadState missingRecoveryVersion = framedState();
        missingRecoveryVersion.setEncryptionRecoveryVersion(null);
        assertThat(assertThrows(GeneralException.class, () -> finalizationService.ensureManifest(
                USER_ID, framedFile(), missingRecoveryVersion, List.of(), List.of())).getData())
                .asString()
                .contains("检查点不完整");

        FileUploadState inconsistentPlan = framedState();
        inconsistentPlan.setTotalChunks(2);
        assertThat(assertThrows(GeneralException.class, () -> finalizationService.ensureManifest(
                USER_ID, framedFile(), inconsistentPlan, List.of(), List.of())).getData())
                .asString()
                .contains("分片计划");

        FileUploadState malformedManifestHash = framedState();
        malformedManifestHash.setManifestHash("sha256:BAD");
        assertThat(assertThrows(GeneralException.class, () -> finalizationService.ensureManifest(
                USER_ID, framedFile(), malformedManifestHash, List.of(), List.of())).getData())
                .asString()
                .contains("hash 检查点格式");

        verifyNoInteractions(fileRemoteClient, chunkManifestService, framedAeadWriter);
    }

    /**
     * 验证持久化 fileParam 的字段漂移与损坏 JSON 均失败关闭。
     */
    @Test
    void ensureManifest_shouldRejectMismatchedOrMalformedFileParam() {
        File mismatched = framedFile().setFileParam(
                framedFile().getFileParam().replace("RP-AES256-GCM-FRAMED-V2", "OTHER-SUITE"));
        GeneralException mismatchFailure = assertThrows(GeneralException.class,
                () -> finalizationService.ensureManifest(
                        USER_ID, mismatched, framedState(), List.of(), List.of()));
        assertThat(mismatchFailure.getData()).asString().contains("fileParam");

        File malformed = framedFile().setFileParam("{\"encryptionAlgorithm\":\"FRAMED_AEAD_V2\"");
        GeneralException malformedFailure = assertThrows(GeneralException.class,
                () -> finalizationService.ensureManifest(
                        USER_ID, malformed, framedState(), List.of(), List.of()));
        assertThat(malformedFailure.getData()).asString().contains("JSON 解析失败");

        verifyNoInteractions(fileRemoteClient, chunkManifestService, framedAeadWriter);
    }

    /**
     * 验证链记录缺失、格式损坏和摘要替换均在 writer 认证前失败关闭。
     */
    @Test
    void ensureManifest_shouldRejectInvalidChainReferenceEvidence() {
        when(chunkManifestService.findActiveManifest(USER_ID, FILE_ID)).thenReturn(Optional.empty());
        when(fileRemoteClient.getFile(String.valueOf(USER_ID), FILE_HASH))
                .thenReturn(Result.success(chainDetailWithContent(" ")));
        assertThat(assertThrows(GeneralException.class, () -> finalizationService.ensureManifest(
                USER_ID, framedFile(), framedState(), List.of(new java.io.File("unused")),
                List.of(CIPHER_HASH))).getData())
                .asString()
                .contains("引用缺失");

        when(fileRemoteClient.getFile(String.valueOf(USER_ID), FILE_HASH))
                .thenReturn(Result.success(chainDetailWithContent("{not-json")));
        assertThat(assertThrows(GeneralException.class, () -> finalizationService.ensureManifest(
                USER_ID, framedFile(), framedState(), List.of(new java.io.File("unused")),
                List.of(CIPHER_HASH))).getData())
                .asString()
                .contains("格式无效");

        String substitutedHash = "sha256:" + "d".repeat(64);
        when(fileRemoteClient.getFile(String.valueOf(USER_ID), FILE_HASH))
                .thenReturn(Result.success(chainDetail(substitutedHash, storagePath(substitutedHash))));
        assertThat(assertThrows(GeneralException.class, () -> finalizationService.ensureManifest(
                USER_ID, framedFile(), framedState(), List.of(new java.io.File("unused")),
                List.of(CIPHER_HASH))).getData())
                .asString()
                .contains("摘要");

        verifyNoInteractions(framedAeadWriter);
        verify(chunkManifestService, never()).saveManifest(anyLong(), anyLong(), any());
    }

    /**
     * 验证 writer 重新认证失败时保留对象证据并阻止 manifest 保存。
     */
    @Test
    void ensureManifest_shouldRejectWriterVerificationFailure() throws IOException {
        Path processed = tempDir.resolve("encrypted_chunk_0");
        Files.write(processed, new byte[]{1, 2, 3});
        when(chunkManifestService.findActiveManifest(USER_ID, FILE_ID)).thenReturn(Optional.empty());
        when(fileRemoteClient.getFile(String.valueOf(USER_ID), FILE_HASH))
                .thenReturn(Result.success(chainDetail(CIPHER_HASH, storagePath(CIPHER_HASH))));
        when(framedAeadWriter.verify(
                eq(processed), eq(FILE_DEK), eq(FILE_NONCE), eq(0), eq(1), eq(FRAME_SIZE)))
                .thenThrow(new IOException("tag mismatch"));

        GeneralException failure = assertThrows(GeneralException.class, () -> finalizationService.ensureManifest(
                USER_ID, framedFile(), framedState(), List.of(processed.toFile()), List.of(CIPHER_HASH)));

        assertThat(failure.getData()).asString().contains("认证或长度校验失败");
        assertThat(Files.isRegularFile(processed)).isTrue();
        verify(chunkManifestService, never()).saveManifest(anyLong(), anyLong(), any());
    }

    /**
     * 验证残缺 fileParam 的显式 v2 标识不会被当作 legacy 静默跳过。
     */
    @Test
    void ensureManifest_shouldRejectMalformedExplicitV2FileParam() {
        File malformed = framedFile().setFileParam("{FRAMED_AEAD_V2");
        FileUploadState legacyState = new FileUploadState(
                USER_ID, "legacy.bin", 33L, "application/octet-stream", "legacy-client", 33, 1);

        GeneralException failure = assertThrows(GeneralException.class, () -> finalizationService.ensureManifest(
                USER_ID, malformed, legacyState, List.of(), List.of()));

        assertThat(failure.getData()).asString().contains("fileParam 格式无效");
        verifyNoInteractions(fileRemoteClient, chunkManifestService, framedAeadWriter);
    }

    /**
     * 验证文件 owner 与稳定上传用户不一致时拒绝恢复。
     */
    @Test
    void ensureManifest_shouldRejectFileOwnerMismatch() {
        File file = framedFile().setUid(200L);

        GeneralException failure = assertThrows(GeneralException.class, () -> finalizationService.ensureManifest(
                USER_ID, file, framedState(), List.of(), List.of()));

        assertThat(failure.getResultEnum()).isEqualTo(ResultEnum.PERMISSION_UNAUTHORIZED);
        verifyNoInteractions(fileRemoteClient, chunkManifestService, framedAeadWriter);
    }

    /**
     * 验证没有完整 processed/cipher 证据时不读取链上引用或创建 manifest。
     */
    @Test
    void ensureManifest_shouldRejectIncompleteObjectEvidence() {
        when(chunkManifestService.findActiveManifest(USER_ID, FILE_ID)).thenReturn(Optional.empty());

        GeneralException failure = assertThrows(GeneralException.class, () -> finalizationService.ensureManifest(
                USER_ID, framedFile(), framedState(), List.of(), List.of()));

        assertThat(failure.getData()).asString().contains("输入证据不完整");
        verifyNoInteractions(fileRemoteClient, framedAeadWriter);
        verify(chunkManifestService, never()).saveManifest(anyLong(), anyLong(), any());
    }

    /**
     * 验证链上引用的数量、路径租户、摘要后缀和摘要编码均严格绑定。
     */
    @Test
    void ensureManifest_shouldRejectReferenceBindingViolations() {
        when(chunkManifestService.findActiveManifest(USER_ID, FILE_ID)).thenReturn(Optional.empty());
        java.io.File placeholder = new java.io.File("unused");

        when(fileRemoteClient.getFile(String.valueOf(USER_ID), FILE_HASH))
                .thenReturn(Result.success(chainDetailWithContent(
                        "[{\"index\":0,\"cipherHash\":\"" + CIPHER_HASH
                                + "\",\"storagePath\":\"" + storagePath(CIPHER_HASH)
                                + "\"},{\"index\":1,\"cipherHash\":\"" + CIPHER_HASH
                                + "\",\"storagePath\":\"" + storagePath(CIPHER_HASH) + "-1\"}]")));
        assertThat(assertThrows(GeneralException.class, () -> finalizationService.ensureManifest(
                USER_ID, framedFile(), framedState(), List.of(placeholder), List.of(CIPHER_HASH))).getData())
                .asString()
                .contains("数量不一致");

        when(fileRemoteClient.getFile(String.valueOf(USER_ID), FILE_HASH))
                .thenReturn(Result.success(chainDetail(CIPHER_HASH, "../" + CIPHER_HASH)));
        assertThat(assertThrows(GeneralException.class, () -> finalizationService.ensureManifest(
                USER_ID, framedFile(), framedState(), List.of(placeholder), List.of(CIPHER_HASH))).getData())
                .asString()
                .contains("索引或路径无效");

        when(fileRemoteClient.getFile(String.valueOf(USER_ID), FILE_HASH))
                .thenReturn(Result.success(chainDetail(CIPHER_HASH,
                        "storage/tenant/999/chunk/" + CIPHER_HASH)));
        assertThat(assertThrows(GeneralException.class, () -> finalizationService.ensureManifest(
                USER_ID, framedFile(), framedState(), List.of(placeholder), List.of(CIPHER_HASH))).getData())
                .asString()
                .contains("租户不匹配");

        when(fileRemoteClient.getFile(String.valueOf(USER_ID), FILE_HASH))
                .thenReturn(Result.success(chainDetail(CIPHER_HASH,
                        storagePath("sha256:" + "d".repeat(64)))));
        assertThat(assertThrows(GeneralException.class, () -> finalizationService.ensureManifest(
                USER_ID, framedFile(), framedState(), List.of(placeholder), List.of(CIPHER_HASH))).getData())
                .asString()
                .contains("storagePath 与密文摘要");

        when(fileRemoteClient.getFile(String.valueOf(USER_ID), FILE_HASH))
                .thenReturn(Result.success(chainDetail("not-a-digest", "storage/tenant/" + TENANT_ID
                        + "/chunk/not-a-digest")));
        assertThat(assertThrows(GeneralException.class, () -> finalizationService.ensureManifest(
                USER_ID, framedFile(), framedState(), List.of(placeholder), List.of("not-a-digest"))).getData())
                .asString()
                .contains("摘要格式无效");

        verifyNoInteractions(framedAeadWriter);
        verify(chunkManifestService, never()).saveManifest(anyLong(), anyLong(), any());
    }

    /**
     * 验证 writer 返回的密文摘要或明文大小与上传证据不一致时拒绝保存。
     */
    @Test
    void ensureManifest_shouldRejectWriterEvidenceMismatch() throws IOException {
        Path processed = tempDir.resolve("encrypted_chunk_0");
        Files.write(processed, new byte[]{1, 2, 3});
        when(chunkManifestService.findActiveManifest(USER_ID, FILE_ID)).thenReturn(Optional.empty());
        when(fileRemoteClient.getFile(String.valueOf(USER_ID), FILE_HASH))
                .thenReturn(Result.success(chainDetail(CIPHER_HASH, storagePath(CIPHER_HASH))));
        when(framedAeadWriter.verify(
                eq(processed), eq(FILE_DEK), eq(FILE_NONCE), eq(0), eq(1), eq(FRAME_SIZE)))
                .thenReturn(new FramedAeadWriter.WriteResult(33L, 105L, 1, PLAIN_HASH,
                        "sha256:" + "d".repeat(64)));

        assertThat(assertThrows(GeneralException.class, () -> finalizationService.ensureManifest(
                USER_ID, framedFile(), framedState(), List.of(processed.toFile()), List.of(CIPHER_HASH))).getData())
                .asString()
                .contains("密文摘要不一致");

        when(framedAeadWriter.verify(
                eq(processed), eq(FILE_DEK), eq(FILE_NONCE), eq(0), eq(1), eq(FRAME_SIZE)))
                .thenReturn(new FramedAeadWriter.WriteResult(32L, 104L, 1, PLAIN_HASH, CIPHER_HASH));
        assertThat(assertThrows(GeneralException.class, () -> finalizationService.ensureManifest(
                USER_ID, framedFile(), framedState(), List.of(processed.toFile()), List.of(CIPHER_HASH))).getData())
                .asString()
                .contains("明文大小不一致");

        verify(chunkManifestService, never()).saveManifest(anyLong(), anyLong(), any());
    }

    /**
     * 逐项验证 framed v2 Redis 不可变检查点，确保长短路条件的每个失败分支都关闭。
     */
    @Test
    void validateFramedState_shouldRejectEveryImmutableCheckpointVariant() {
        List<Consumer<FileUploadState>> invalidMutations = List.of(
                state -> state.setEncryptionRecoveryVersion(null),
                state -> state.setEncryptionRecoveryVersion(1),
                state -> state.setEncryptionFormatVersion(1),
                state -> state.setEncryptionAlgorithmSuite("legacy"),
                state -> state.setFileDataKey(null),
                state -> state.setFileDataKey(new byte[FramedAeadCrypto.FILE_DEK_SIZE - 1]),
                state -> state.setFileNonce(null),
                state -> state.setFileNonce(new byte[FramedAeadCrypto.FILE_NONCE_SIZE - 1]),
                state -> state.setFramePlainSize(null),
                state -> state.setFramePlainSize(ChunkManifestEncryption.MIN_FRAME_PLAIN_SIZE - 1),
                state -> state.setFramePlainSize(ChunkManifestEncryption.MAX_FRAME_PLAIN_SIZE + 1),
                state -> state.setKeyDerivation("PBKDF2"),
                state -> state.setNonceDerivation("PBKDF2"),
                state -> state.setAadSchema("other-aad"),
                state -> state.setTagSize(12),
                state -> state.setFileSize(0),
                state -> state.setChunkSize(0),
                state -> state.setTotalChunks(0)
        );

        ReflectionTestUtils.invokeMethod(finalizationService, "validateFramedState", framedState());
        assertThrows(GeneralException.class,
                () -> ReflectionTestUtils.invokeMethod(finalizationService, "validateFramedState", (Object) null));
        for (Consumer<FileUploadState> mutation : invalidMutations) {
            FileUploadState state = framedState();
            mutation.accept(state);
            assertThrows(GeneralException.class,
                    () -> ReflectionTestUtils.invokeMethod(finalizationService, "validateFramedState", state));
        }

        FileUploadState inconsistentPlan = framedState();
        inconsistentPlan.setFileSize(34L);
        assertThrows(GeneralException.class,
                () -> ReflectionTestUtils.invokeMethod(
                        finalizationService, "validateFramedState", inconsistentPlan));

        FileUploadState withoutManifestCheckpoint = framedState();
        withoutManifestCheckpoint.setManifestHash(null);
        ReflectionTestUtils.invokeMethod(finalizationService, "validateFramedState", withoutManifestCheckpoint);
    }

    /**
     * 穷举文件身份与 fileParam 的稳定字段漂移，覆盖每个短路条件而不依赖远程服务。
     */
    @Test
    void validationHelpers_shouldRejectEveryIdentityAndFileParamDrift() {
        assertIdentityFailure(null, framedFile(), framedState());
        assertIdentityFailure(USER_ID, null, framedState());
        assertIdentityFailure(USER_ID, framedFile().setId(null), framedState());
        assertIdentityFailure(USER_ID, framedFile().setFileHash(" "), framedState());
        assertIdentityFailure(USER_ID, framedFile().setTenantId(null), framedState());

        FileUploadState missingTenant = framedState();
        missingTenant.setTenantId(null);
        assertIdentityFailure(USER_ID, framedFile(), missingTenant);
        FileUploadState missingUser = framedState();
        missingUser.setUserId(null);
        assertIdentityFailure(USER_ID, framedFile(), missingUser);
        FileUploadState missingPreparedFile = framedState();
        missingPreparedFile.setPreparedFileId(null);
        assertIdentityFailure(USER_ID, framedFile(), missingPreparedFile);
        FileUploadState wrongSize = framedState();
        wrongSize.setFileSize(34L);
        assertIdentityFailure(USER_ID, framedFile(), wrongSize);
        FileUploadState wrongTenant = framedState();
        wrongTenant.setTenantId(TENANT_ID + 1);
        assertIdentityFailure(USER_ID, framedFile(), wrongTenant);
        FileUploadState wrongUser = framedState();
        wrongUser.setUserId(USER_ID + 1);
        assertIdentityFailure(USER_ID, framedFile(), wrongUser);
        FileUploadState wrongPreparedFile = framedState();
        wrongPreparedFile.setPreparedFileId(FILE_ID + 1);
        assertIdentityFailure(USER_ID, framedFile(), wrongPreparedFile);

        ReflectionTestUtils.invokeMethod(
                finalizationService, "validateFileIdentity", USER_ID, framedFile(), framedState());

        Map<String, String> fileParamDrifts = new LinkedHashMap<>();
        fileParamDrifts.put("null", "null");
        fileParamDrifts.put("algorithm", framedFile().getFileParam()
                .replace("\"encryptionAlgorithm\":\"FRAMED_AEAD_V2\"", "\"encryptionAlgorithm\":\"NONE\""));
        fileParamDrifts.put("suite", framedFile().getFileParam()
                .replace("RP-AES256-GCM-FRAMED-V2", "OTHER-SUITE"));
        fileParamDrifts.put("nonce", framedFile().getFileParam()
                .replace("AQIDBAUGBwgJCgsMDQ4PEA", "ERITFBUWFxgZGhscHR4fIA"));
        fileParamDrifts.put("keyDerivation", framedFile().getFileParam()
                .replace("\"keyDerivation\":\"HKDF-SHA256\"", "\"keyDerivation\":\"OTHER\""));
        fileParamDrifts.put("nonceDerivation", framedFile().getFileParam()
                .replace("\"nonceDerivation\":\"HKDF-SHA256\"", "\"nonceDerivation\":\"OTHER\""));
        fileParamDrifts.put("aad", framedFile().getFileParam()
                .replace("cn.flying.framed-aead.aad.v2", "other-aad"));
        fileParamDrifts.put("format", framedFile().getFileParam()
                .replace("\"formatVersion\":2", "\"formatVersion\":1"));
        fileParamDrifts.put("frameSize", framedFile().getFileParam()
                .replace("\"framePlainSize\":65536", "\"framePlainSize\":131072"));
        fileParamDrifts.put("tag", framedFile().getFileParam()
                .replace("\"tagSize\":16", "\"tagSize\":12"));
        fileParamDrifts.put("fileSize", framedFile().getFileParam()
                .replace("\"fileSize\":33", "\"fileSize\":34"));
        fileParamDrifts.put("chunkSize", framedFile().getFileParam()
                .replace("\"chunkSize\":33", "\"chunkSize\":32"));
        fileParamDrifts.put("chunkCount", framedFile().getFileParam()
                .replace("\"chunkCount\":1", "\"chunkCount\":2"));
        fileParamDrifts.put("malformed", "{not-json");

        for (String fileParam : fileParamDrifts.values()) {
            assertThrows(GeneralException.class, () -> ReflectionTestUtils.invokeMethod(
                    finalizationService, "validateFileParam", framedFile().setFileParam(fileParam), framedState()));
        }
        ReflectionTestUtils.invokeMethod(
                finalizationService, "validateFileParam", framedFile(), framedState());
    }

    /**
     * 验证 active manifest 顶层、分片和 canonical hash 的每项不可变证据。
     */
    @Test
    void validateActiveManifest_shouldRejectEveryTopLevelAndChunkDrift() {
        when(chunkManifestService.calculateManifestHash(any())).thenReturn(MANIFEST_HASH);

        Map<String, Object> topLevelDrifts = new LinkedHashMap<>();
        topLevelDrifts.put("fileId", FILE_ID + 1);
        topLevelDrifts.put("fileVersion", 2);
        topLevelDrifts.put("fileHash", "other-hash");
        topLevelDrifts.put("schemaId", "other-schema");
        topLevelDrifts.put("hashAlgorithm", "SHA-512");
        topLevelDrifts.put("chunkSize", 32L);
        topLevelDrifts.put("chunkCount", null);
        topLevelDrifts.put("totalSize", 34L);
        topLevelDrifts.put("encryptionAlgorithm", "AES-GCM");
        topLevelDrifts.put("storageBackend", "MINIO");
        topLevelDrifts.put("encryption", null);
        topLevelDrifts.put("chunks", List.of());

        assertActiveManifestFailure(null, framedState());
        for (Map.Entry<String, Object> drift : topLevelDrifts.entrySet()) {
            assertActiveManifestFailure(copyManifest(activeManifest(), drift.getKey(), drift.getValue()), framedState());
        }

        Map<String, Object> chunkDrifts = new LinkedHashMap<>();
        chunkDrifts.put("index", 1);
        chunkDrifts.put("plainSize", 32L);
        chunkDrifts.put("frameCount", 2);
        chunkDrifts.put("size", 104L);
        chunkDrifts.put("plainHash", "not-a-hash");
        chunkDrifts.put("cipherHash", "not-a-hash");
        chunkDrifts.put("checksumAlgorithm", "SHA-512");
        chunkDrifts.put("storageBackend", "MINIO");
        chunkDrifts.put("storagePath", " ");
        for (Map.Entry<String, Object> drift : chunkDrifts.entrySet()) {
            ChunkManifestChunk chunk = copyChunk(
                    activeManifest().chunks().getFirst(), drift.getKey(), drift.getValue());
            assertActiveManifestFailure(
                    copyManifest(activeManifest(), "chunks", List.of(chunk)), framedState());
        }

        ChunkManifestChunk wrongTenantPath = copyChunk(
                activeManifest().chunks().getFirst(), "storagePath",
                "storage/tenant/999/chunk/" + CIPHER_HASH);
        assertActiveManifestFailure(
                copyManifest(activeManifest(), "chunks", List.of(wrongTenantPath)), framedState());

        when(chunkManifestService.calculateManifestHash(any())).thenReturn("sha256:" + "d".repeat(64));
        assertActiveManifestFailure(activeManifest(), framedState());
        when(chunkManifestService.calculateManifestHash(any())).thenReturn(MANIFEST_HASH);
        FileUploadState mismatchedCheckpoint = framedState();
        mismatchedCheckpoint.setManifestHash("sha256:" + "d".repeat(64));
        assertActiveManifestFailure(activeManifest(), mismatchedCheckpoint);

        FileUploadState overflowState = framedState();
        overflowState.setFileSize(Long.MAX_VALUE);
        overflowState.setChunkSize(1);
        overflowState.setTotalChunks(1);
        ChunkManifestView overflowManifest = copyManifest(activeManifest(), "chunkSize", 1L);
        overflowManifest = copyManifest(overflowManifest, "totalSize", Long.MAX_VALUE);
        assertActiveManifestFailure(overflowManifest, overflowState);
    }

    /**
     * 验证引用、摘要、路径和分片尺寸辅助函数覆盖安全与兼容格式边界。
     */
    @Test
    void referenceAndDigestHelpers_shouldCoverAllSafetyBranches() throws IOException {
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                finalizationService, "isFramedV2", (Object) null)).isFalse();
        FileUploadState formatOnly = framedState();
        formatOnly.setEncryptionAlgorithmSuite(null);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                finalizationService, "declaresFramedV2", formatOnly)).isTrue();
        FileUploadState suiteOnly = framedState();
        suiteOnly.setEncryptionFormatVersion(null);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                finalizationService, "declaresFramedV2", suiteOnly)).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                finalizationService, "declaresFramedV2", new FileUploadState())).isFalse();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                finalizationService, "declaresFramedV2", (File) null)).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                finalizationService, "declaresFramedV2", new File().setFileParam(" "))).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                finalizationService, "declaresFramedV2", new File().setFileParam("{\"formatVersion\":2}"))).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                finalizationService, "declaresFramedV2", new File().setFileParam(
                        "{\"algorithmSuite\":\"RP-AES256-GCM-FRAMED-V2\"}"))).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                finalizationService, "declaresFramedV2", new File().setFileParam("{\"formatVersion\":1}"))).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                finalizationService, "declaresFramedV2", new File().setFileParam("{not-json"))).isFalse();

        assertThat((Long) ReflectionTestUtils.invokeMethod(
                finalizationService, "expectedPlainChunkSize", twoChunkState(), 0)).isEqualTo(33L);
        assertThat((Long) ReflectionTestUtils.invokeMethod(
                finalizationService, "expectedPlainChunkSize", twoChunkState(), 1)).isEqualTo(1L);
        assertThrows(GeneralException.class, () -> ReflectionTestUtils.invokeMethod(
                finalizationService, "expectedPlainChunkSize", twoChunkState(), -1));
        assertThrows(GeneralException.class, () -> ReflectionTestUtils.invokeMethod(
                finalizationService, "expectedPlainChunkSize", twoChunkState(), 2));

        String base64Digest = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        assertThat((String) ReflectionTestUtils.invokeMethod(
                finalizationService, "normalizeDigest", "A".repeat(64)))
                .isEqualTo("sha256:" + "a".repeat(64));
        assertThat((String) ReflectionTestUtils.invokeMethod(
                finalizationService, "normalizeDigest", base64Digest))
                .isEqualTo("sha256:" + "0".repeat(64));
        assertThrows(GeneralException.class, () -> ReflectionTestUtils.invokeMethod(
                finalizationService, "normalizeDigest", " "));
        assertThrows(GeneralException.class, () -> ReflectionTestUtils.invokeMethod(
                finalizationService, "normalizeDigest", "x".repeat(2049)));
        assertThrows(GeneralException.class, () -> ReflectionTestUtils.invokeMethod(
                finalizationService, "normalizeDigest", "AQID"));

        String safePath = storagePath(CIPHER_HASH);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                finalizationService, "containsUnsafePathText", safePath)).isFalse();
        for (String unsafe : List.of("/absolute", "../relative", "control\npath", "windows\\path")) {
            assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                    finalizationService, "containsUnsafePathText", unsafe)).isTrue();
        }
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                finalizationService, "isTenantStoragePath", TENANT_ID, safePath, CIPHER_HASH)).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                finalizationService, "isTenantStoragePath", null, safePath, CIPHER_HASH)).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                finalizationService, "isTenantStoragePath", TENANT_ID, " ", CIPHER_HASH)).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                finalizationService, "isTenantStoragePath", TENANT_ID,
                "storage/tenant/999/chunk/" + CIPHER_HASH, CIPHER_HASH)).isFalse();

        FileUploadState state = framedState();
        assertThrows(GeneralException.class, () -> ReflectionTestUtils.invokeMethod(
                finalizationService, "validateReferences", framedFile(), state, null, List.of(CIPHER_HASH)));
        assertReferenceFailure(new StoredObjectReference(1, CIPHER_HASH, safePath));
        assertReferenceFailure(new StoredObjectReference(0, " ", safePath));
        assertReferenceFailure(new StoredObjectReference(0, CIPHER_HASH, " "));
        assertReferenceFailure(new StoredObjectReference(0, "x".repeat(2049), safePath));
        assertReferenceFailure(new StoredObjectReference(0, CIPHER_HASH, "x".repeat(2049)));
        assertReferenceFailure(new StoredObjectReference(0, CIPHER_HASH, "/absolute"));

        Path processed = tempDir.resolve("aggregate-framed.bin");
        Files.write(processed, new byte[]{1});
        FileUploadState aggregateState = twoChunkState();
        when(framedAeadWriter.verify(any(), any(), any(), eq(0), eq(2), eq(FRAME_SIZE)))
                .thenReturn(new FramedAeadWriter.WriteResult(33L, 105L, 1, PLAIN_HASH, CIPHER_HASH));
        assertThrows(GeneralException.class, () -> ReflectionTestUtils.invokeMethod(
                finalizationService,
                "buildChunks",
                aggregateState,
                List.of(processed.toFile()),
                List.of(CIPHER_HASH),
                List.of(new StoredObjectReference(0, CIPHER_HASH, safePath)),
                encryptionDescriptor()));
        assertThrows(GeneralException.class, () -> ReflectionTestUtils.invokeMethod(
                finalizationService,
                "buildChunks",
                framedState(),
                Collections.singletonList(null),
                List.of(CIPHER_HASH),
                List.of(new StoredObjectReference(0, CIPHER_HASH, safePath)),
                encryptionDescriptor()));
    }

    /**
     * 断言文件身份私有校验失败。
     */
    private void assertIdentityFailure(Long userId, File file, FileUploadState state) {
        assertThrows(GeneralException.class, () -> ReflectionTestUtils.invokeMethod(
                finalizationService, "validateFileIdentity", userId, file, state));
    }

    /**
     * 断言 active manifest 私有校验失败。
     */
    private void assertActiveManifestFailure(ChunkManifestView manifest, FileUploadState state) {
        assertThrows(GeneralException.class, () -> ReflectionTestUtils.invokeMethod(
                finalizationService, "validateActiveManifest", manifest, framedFile(), state));
    }

    /**
     * 断言单条链引用在私有引用校验中失败。
     */
    private void assertReferenceFailure(StoredObjectReference reference) {
        assertThrows(GeneralException.class, () -> ReflectionTestUtils.invokeMethod(
                finalizationService,
                "validateReferences",
                framedFile(),
                framedState(),
                List.of(reference),
                List.of(CIPHER_HASH)));
    }

    /**
     * 复制 manifest 并替换一个指定字段，便于逐字段生成失败样本。
     */
    @SuppressWarnings("unchecked")
    private ChunkManifestView copyManifest(ChunkManifestView source, String field, Object value) {
        return new ChunkManifestView(
                source.manifestId(),
                "fileId".equals(field) ? (Long) value : source.fileId(),
                "fileVersion".equals(field) ? (Integer) value : source.fileVersion(),
                "schemaId".equals(field) ? (String) value : source.schemaId(),
                "fileHash".equals(field) ? (String) value : source.fileHash(),
                source.manifestHash(),
                "hashAlgorithm".equals(field) ? (String) value : source.hashAlgorithm(),
                "chunkSize".equals(field) ? ((Number) value).longValue() : source.chunkSize(),
                "chunkCount".equals(field) ? (Integer) value : source.chunkCount(),
                "totalSize".equals(field) ? ((Number) value).longValue() : source.totalSize(),
                source.merkleRoot(),
                "encryptionAlgorithm".equals(field) ? (String) value : source.encryptionAlgorithm(),
                "storageBackend".equals(field) ? (String) value : source.storageBackend(),
                "encryption".equals(field) ? (ChunkManifestEncryption) value : source.encryption(),
                "chunks".equals(field) ? (List<ChunkManifestChunk>) value : source.chunks());
    }

    /**
     * 复制 manifest 分片并替换一个指定字段，便于逐字段生成失败样本。
     */
    private ChunkManifestChunk copyChunk(ChunkManifestChunk source, String field, Object value) {
        return new ChunkManifestChunk(
                "index".equals(field) ? ((Number) value).intValue() : source.index(),
                "plainHash".equals(field) ? (String) value : source.plainHash(),
                "cipherHash".equals(field) ? (String) value : source.cipherHash(),
                "size".equals(field) ? ((Number) value).longValue() : source.size(),
                "storagePath".equals(field) ? (String) value : source.storagePath(),
                "storageBackend".equals(field) ? (String) value : source.storageBackend(),
                source.etag(),
                "checksumAlgorithm".equals(field) ? (String) value : source.checksumAlgorithm(),
                "plainSize".equals(field) ? (Long) value : source.plainSize(),
                "frameCount".equals(field) ? (Integer) value : source.frameCount());
    }

    /**
     * 构造两分片状态，用于覆盖首分片、尾分片和明文聚合边界。
     */
    private FileUploadState twoChunkState() {
        FileUploadState state = framedState();
        state.setFileSize(34L);
        state.setChunkSize(33);
        state.setTotalChunks(2);
        return state;
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
        return chainDetailWithContent(content);
    }

    /**
     * 构造指定 content 的链上文件详情，用于损坏引用边界测试。
     */
    private FileDetailVO chainDetailWithContent(String content) {
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
