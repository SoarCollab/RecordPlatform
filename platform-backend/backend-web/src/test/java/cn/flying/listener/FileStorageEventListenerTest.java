package cn.flying.listener;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.event.FileStorageEvent;
import cn.flying.common.exception.GeneralException;
import cn.flying.dao.dto.File;
import cn.flying.dao.vo.file.FileUploadState;
import cn.flying.service.FileService;
import cn.flying.service.assistant.FileUploadRedisStateManager;
import cn.flying.service.manifest.ChunkManifestView;
import cn.flying.service.manifest.FramedManifestFinalizationService;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.service.sse.SseEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FileStorageEventListener Tests")
class FileStorageEventListenerTest {

    @Mock
    private FileService fileService;

    @Mock
    private FileUploadRedisStateManager redisStateManager;

    @Mock
    private SseEmitterManager sseEmitterManager;

    @InjectMocks
    private FileStorageEventListener listener;

    /**
     * 构造测试用文件存证事件。
     *
     * @return 文件存证事件
     */
    private FileStorageEvent buildEvent() {
        return buildEvent(1L, 9527L, "client-1",
                List.of(new java.io.File("/tmp/contract.part")));
    }

    /**
     * 构造可覆盖恢复边界的文件存证事件。
     *
     * @param tenantId 租户ID
     * @param preparedFileId 稳定 PREPARE 文件ID
     * @param clientId 上传客户端ID
     * @param processedFiles 已处理文件
     * @return 文件存证事件
     */
    private FileStorageEvent buildEvent(
            Long tenantId,
            Long preparedFileId,
            String clientId,
            List<java.io.File> processedFiles
    ) {
        return new FileStorageEvent(
                this,
                tenantId,
                100L,
                preparedFileId,
                "contract.pdf",
                "session-1",
                clientId,
                processedFiles,
                List.of("hash-1"),
                "{\"contentType\":\"application/pdf\"}"
        );
    }

    /**
     * 为单个测试注入 framed manifest 最终化服务，保留其他历史用例的空依赖兼容分支。
     *
     * @return framed manifest 最终化服务 mock
     */
    private FramedManifestFinalizationService injectFramedManifestFinalizationService() {
        FramedManifestFinalizationService service = mock(FramedManifestFinalizationService.class);
        ReflectionTestUtils.setField(listener, "framedManifestFinalizationService", service);
        return service;
    }

    /**
     * 构造仅携带监听器所需 manifest 摘要的持久化视图。
     *
     * @param manifestHash manifest 摘要
     * @return manifest 持久化视图
     */
    private ChunkManifestView buildManifest(String manifestHash) {
        return new ChunkManifestView(
                1L, 1L, 1, "v1", "hash-1", manifestHash, "SHA-256",
                1024L, 1, 1024L, null, "FRAMED_AEAD_V2", "S3", List.of());
    }

    @Test
    @DisplayName("success path should send file-record-success event")
    void successPathShouldSendFileRecordSuccessEvent() {
        File storedFile = new File();
        storedFile.setFileHash("hash-1");
        when(fileService.storeFile(
                anyLong(), any(Long.class), anyString(), anyList(), anyList(), anyString(), anyString()))
                .thenReturn(storedFile);

        listener.handleFileStorageEvent(buildEvent());

        ArgumentCaptor<SseEvent> eventCaptor = ArgumentCaptor.forClass(SseEvent.class);
        verify(sseEmitterManager, times(1)).sendToUser(eq(1L), eq(100L), eventCaptor.capture());
        assertThat(eventCaptor.getValue().getType()).isEqualTo("file-record-success");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) eventCaptor.getValue().getPayload();
        assertThat(payload)
                .containsEntry("fileName", "contract.pdf")
                .containsEntry("fileHash", "hash-1")
                .containsEntry("status", "completed")
                .doesNotContainKey("reason");
        verify(fileService, never()).changeFileStatusById(anyLong(), anyLong(), anyInt());
        verify(fileService, never()).markFileUploadFailed(anyLong(), anyLong());
        verify(redisStateManager, never()).removeSession(anyString(), anyString());
    }

    /**
     * 验证普通 framed v2 存储成功后把 active manifest 摘要回写到稳定 Redis 检查点。
     */
    @Test
    void framedManifestShouldPersistHashIntoUploadState() {
        FileStorageEvent event = buildEvent();
        File storedFile = new File();
        storedFile.setFileHash("hash-1");
        FileUploadState state = new FileUploadState(
                100L, "contract.pdf", 1024L, "application/pdf", "client-1", 1024, 1);
        ChunkManifestView manifest = buildManifest("sha256:manifest");
        FramedManifestFinalizationService finalizationService = injectFramedManifestFinalizationService();
        when(fileService.storeFile(
                anyLong(), any(Long.class), anyString(), anyList(), anyList(), anyString(), anyString()))
                .thenReturn(storedFile);
        when(redisStateManager.getState("client-1")).thenReturn(state);
        when(finalizationService.ensureManifest(
                eq(100L), same(storedFile), same(state),
                same(event.getProcessedFiles()), same(event.getFileHashes())))
                .thenReturn(Optional.of(manifest));

        listener.handleFileStorageEvent(event);

        assertThat(state.getManifestHash()).isEqualTo("sha256:manifest");
        verify(finalizationService).ensureManifest(
                eq(100L), same(storedFile), same(state),
                same(event.getProcessedFiles()), same(event.getFileHashes()));
        verify(redisStateManager).updateState(same(state));
    }

    /**
     * 验证 legacy 或无需 manifest 的成功存储不会改写上传检查点。
     */
    @Test
    void emptyFramedManifestShouldNotUpdateUploadState() {
        FileStorageEvent event = buildEvent();
        File storedFile = new File();
        storedFile.setFileHash("hash-1");
        FileUploadState state = new FileUploadState(
                100L, "contract.pdf", 1024L, "application/pdf", "client-1", 1024, 1);
        FramedManifestFinalizationService finalizationService = injectFramedManifestFinalizationService();
        when(fileService.storeFile(
                anyLong(), any(Long.class), anyString(), anyList(), anyList(), anyString(), anyString()))
                .thenReturn(storedFile);
        when(redisStateManager.getState("client-1")).thenReturn(state);
        when(finalizationService.ensureManifest(
                eq(100L), same(storedFile), same(state),
                same(event.getProcessedFiles()), same(event.getFileHashes())))
                .thenReturn(Optional.empty());

        listener.handleFileStorageEvent(event);

        assertThat(state.getManifestHash()).isNull();
        verify(redisStateManager, never()).updateState(any());
    }

    /**
     * 验证 Redis 会话已过期时仍允许复用 active manifest，但不得凭空创建状态。
     */
    @Test
    void activeManifestWithoutUploadStateShouldSkipRedisUpdate() {
        FileStorageEvent event = buildEvent();
        File storedFile = new File();
        storedFile.setFileHash("hash-1");
        ChunkManifestView manifest = buildManifest("sha256:manifest");
        FramedManifestFinalizationService finalizationService = injectFramedManifestFinalizationService();
        when(fileService.storeFile(
                anyLong(), any(Long.class), anyString(), anyList(), anyList(), anyString(), anyString()))
                .thenReturn(storedFile);
        when(redisStateManager.getState("client-1")).thenReturn(null);
        when(finalizationService.ensureManifest(
                eq(100L), same(storedFile), isNull(),
                same(event.getProcessedFiles()), same(event.getFileHashes())))
                .thenReturn(Optional.of(manifest));

        listener.handleFileStorageEvent(event);

        verify(finalizationService).ensureManifest(
                eq(100L), same(storedFile), isNull(),
                same(event.getProcessedFiles()), same(event.getFileHashes()));
        verify(redisStateManager, never()).updateState(any());
    }

    @Test
    @DisplayName("failure path should send file-record-failed and keep cleanup behavior")
    void failurePathShouldSendFileRecordFailedAndKeepCleanupBehavior() {
        when(fileService.storeFile(
                anyLong(), any(Long.class), anyString(), anyList(), anyList(), anyString(), anyString()))
                .thenThrow(new RuntimeException("chain write failed"));
        when(fileService.getFinalizationRecoveryPhase(100L, 9527L))
                .thenReturn(FileService.FinalizationRecoveryPhase.NONE);
        when(fileService.markFileUploadFailed(100L, 9527L)).thenReturn(true);

        GeneralException failure = assertThrows(
                GeneralException.class,
                () -> listener.handleFileStorageEvent(buildEvent()));

        assertThat(failure.getCause()).hasMessage("chain write failed");
        verify(fileService, times(1)).markFileUploadFailed(100L, 9527L);
        verify(fileService, never()).changeFileStatusById(anyLong(), anyLong(), eq(FileUploadStatus.FAIL.getCode()));
        verify(redisStateManager, never()).removeSession(anyString(), anyString());

        ArgumentCaptor<SseEvent> eventCaptor = ArgumentCaptor.forClass(SseEvent.class);
        verify(sseEmitterManager, times(1)).sendToUser(eq(1L), eq(100L), eventCaptor.capture());
        assertThat(eventCaptor.getValue().getType()).isEqualTo("file-record-failed");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) eventCaptor.getValue().getPayload();
        assertThat(payload)
                .containsEntry("fileName", "contract.pdf")
                .containsEntry("status", "failed")
                .containsEntry("reason", "chain write failed");
    }

    /**
     * 验证空分片在进入存储前失败，并安全收敛稳定 PREPARE 记录。
     */
    @Test
    void emptyProcessedFilesShouldFailBeforeStorageAndPreserveRecoveryContract() {
        when(fileService.getFinalizationRecoveryPhase(100L, 9527L))
                .thenReturn(FileService.FinalizationRecoveryPhase.NONE);
        when(fileService.markFileUploadFailed(100L, 9527L)).thenReturn(true);

        GeneralException failure = assertThrows(
                GeneralException.class,
                () -> listener.handleFileStorageEvent(
                        buildEvent(1L, 9527L, "client-1", List.of())));

        assertThat(failure.getResultEnum()).isEqualTo(ResultEnum.FILE_RECORD_ERROR);
        assertThat(failure.getData()).isEqualTo("文件分片为空");
        verify(fileService, never()).storeFile(
                anyLong(), any(), anyString(), anyList(), anyList(), anyString(), anyString());
        verify(fileService).markFileUploadFailed(100L, 9527L);
        assertFailedNotification("文件分片为空");
    }

    /**
     * 验证缺失稳定 PREPARE 身份时禁止按文件名回退修改或清理现场。
     */
    @Test
    void missingPreparedFileIdShouldRetainEvidenceWithoutFallbackMutation() {
        GeneralException failure = assertThrows(
                GeneralException.class,
                () -> listener.handleFileStorageEvent(
                        buildEvent(1L, null, "client-1",
                                List.of(new java.io.File("/tmp/contract.part")))));

        assertThat(failure.getResultEnum()).isEqualTo(ResultEnum.FILE_RECORD_ERROR);
        assertThat(failure.getData()).isEqualTo("文件存证事件缺少稳定 preparedFileId/clientId");
        verify(fileService, never()).storeFile(
                anyLong(), any(), anyString(), anyList(), anyList(), anyString(), anyString());
        verify(fileService, never()).getFinalizationRecoveryPhase(anyLong(), anyLong());
        verify(fileService, never()).markFileUploadFailed(anyLong(), anyLong());
        verifyNoInteractions(redisStateManager);
        assertFailedNotification(null);
    }

    /**
     * 验证空 clientId 同样禁止调用存储和任何不稳定回退清理路径。
     */
    @Test
    void missingClientIdShouldRetainEvidenceWithoutCallingStorage() {
        assertThrows(
                GeneralException.class,
                () -> listener.handleFileStorageEvent(
                        buildEvent(1L, 9527L, "",
                                List.of(new java.io.File("/tmp/contract.part")))));

        verify(fileService, never()).storeFile(
                anyLong(), any(), anyString(), anyList(), anyList(), anyString(), anyString());
        verify(fileService, never()).markFileUploadFailed(anyLong(), anyLong());
        verifyNoInteractions(redisStateManager);
    }

    /**
     * 验证存储层返回空对象时保留结构化业务异常并执行安全失败回写。
     */
    @Test
    void nullStorageResultShouldRemainStructuredFailure() {
        when(fileService.storeFile(
                anyLong(), any(Long.class), anyString(), anyList(), anyList(), anyString(), anyString()))
                .thenReturn(null);
        when(fileService.getFinalizationRecoveryPhase(100L, 9527L))
                .thenReturn(FileService.FinalizationRecoveryPhase.NONE);
        when(fileService.markFileUploadFailed(100L, 9527L)).thenReturn(true);

        GeneralException failure = assertThrows(
                GeneralException.class,
                () -> listener.handleFileStorageEvent(buildEvent()));

        assertThat(failure.getResultEnum()).isEqualTo(ResultEnum.FILE_RECORD_ERROR);
        assertThat(failure.getData()).isEqualTo("存储结果为空");
        assertThat(failure.getCause()).isNull();
        verify(fileService).markFileUploadFailed(100L, 9527L);
    }

    /**
     * 验证 storeFile 已提交 SUCCESS、仅在方法退出阶段失败时，失败回写拒绝后必须保留 Redis 恢复现场。
     */
    @Test
    void postCommitCacheEvictionFailureShouldRetainRedisForSuccessRecovery() {
        when(fileService.storeFile(
                anyLong(), any(Long.class), anyString(), anyList(), anyList(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("cache eviction failed after DB commit"));
        when(fileService.getFinalizationRecoveryPhase(100L, 9527L))
                .thenReturn(FileService.FinalizationRecoveryPhase.SUCCESS);

        GeneralException failure = assertThrows(
                GeneralException.class,
                () -> listener.handleFileStorageEvent(buildEvent()));

        assertThat(failure.getCause()).hasMessage("cache eviction failed after DB commit");
        verify(fileService).getFinalizationRecoveryPhase(100L, 9527L);
        verify(fileService, never()).markFileUploadFailed(anyLong(), anyLong());
        verify(redisStateManager, never()).removeSession(anyString(), anyString());
        verify(redisStateManager, never()).retainManualReconciliationState(any(), anyString(), anyLong());
    }

    /**
     * 验证 durable claim 已进入链不确定阶段时保留 PREPARE 与 Redis 现场。
     */
    @Test
    void ambiguousFinalizationClaimShouldRetainFailureEvidence() {
        FileUploadState state = new FileUploadState(
                100L, "contract.pdf", 1024L, "application/pdf", "client-1", 1024, 1);
        when(fileService.storeFile(
                anyLong(), any(Long.class), anyString(), anyList(), anyList(), anyString(), anyString()))
                .thenThrow(new RuntimeException("chain response lost"));
        when(fileService.getFinalizationRecoveryPhase(100L, 9527L))
                .thenReturn(FileService.FinalizationRecoveryPhase.CHAIN_ATTESTING);
        when(redisStateManager.getState("client-1")).thenReturn(state);

        assertThrows(GeneralException.class, () -> listener.handleFileStorageEvent(buildEvent()));

        verify(fileService, never()).markFileUploadFailed(anyLong(), anyLong());
        verify(redisStateManager, never()).removeSession(anyString(), anyString());
        verify(redisStateManager).retainManualReconciliationState(
                same(state),
                eq("finalization_manual_reconciliation_required"),
                eq(7 * 24 * 60 * 60L));
        verify(redisStateManager, never()).updateState(any());
    }

    /**
     * 验证 claim 读取失败被视为 UNKNOWN，并延长现场而不是尝试失败回写。
     */
    @Test
    void claimLookupFailureShouldFailClosedAndRetainManualEvidence() {
        FileUploadState state = new FileUploadState(
                100L, "contract.pdf", 1024L, "application/pdf", "client-1", 1024, 1);
        when(fileService.storeFile(
                anyLong(), any(Long.class), anyString(), anyList(), anyList(), anyString(), anyString()))
                .thenThrow(new RuntimeException("storage failed"));
        when(fileService.getFinalizationRecoveryPhase(100L, 9527L))
                .thenThrow(new IllegalStateException("claim unavailable"));
        when(redisStateManager.getState("client-1")).thenReturn(state);

        GeneralException failure = assertThrows(
                GeneralException.class,
                () -> listener.handleFileStorageEvent(buildEvent()));

        assertThat(failure.getCause()).hasMessage("storage failed");
        verify(fileService, never()).markFileUploadFailed(anyLong(), anyLong());
        verify(redisStateManager).retainManualReconciliationState(
                same(state), eq("finalization_manual_reconciliation_required"),
                eq(7 * 24 * 60 * 60L));
    }

    /**
     * 验证人工对账阶段即使 Redis 状态已缺失，也不得降级为删除或失败回写。
     */
    @Test
    void manualRecoveryWithMissingRedisStateShouldStillPreserveDatabase() {
        when(fileService.storeFile(
                anyLong(), any(Long.class), anyString(), anyList(), anyList(), anyString(), anyString()))
                .thenThrow(new RuntimeException("chain response lost"));
        when(fileService.getFinalizationRecoveryPhase(100L, 9527L))
                .thenReturn(FileService.FinalizationRecoveryPhase.CHAIN_ATTESTING);
        when(redisStateManager.getState("client-1")).thenReturn(null);

        assertThrows(GeneralException.class, () -> listener.handleFileStorageEvent(buildEvent()));

        verify(fileService, never()).markFileUploadFailed(anyLong(), anyLong());
        verify(redisStateManager, never()).retainManualReconciliationState(any(), anyString(), anyLong());
        verify(redisStateManager, never()).removeSession(anyString(), anyString());
    }

    /**
     * 验证延长人工对账 TTL 失败时保留原始存证异常，不启动破坏性清理。
     */
    @Test
    void manualRecoveryRetentionFailureShouldNotMaskStorageFailure() {
        FileUploadState state = new FileUploadState(
                100L, "contract.pdf", 1024L, "application/pdf", "client-1", 1024, 1);
        RuntimeException storageFailure = new RuntimeException("chain response lost");
        when(fileService.storeFile(
                anyLong(), any(Long.class), anyString(), anyList(), anyList(), anyString(), anyString()))
                .thenThrow(storageFailure);
        when(fileService.getFinalizationRecoveryPhase(100L, 9527L))
                .thenReturn(FileService.FinalizationRecoveryPhase.CHAIN_ATTESTING);
        when(redisStateManager.getState("client-1")).thenReturn(state);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(redisStateManager).retainManualReconciliationState(
                        same(state), anyString(), anyLong());

        GeneralException failure = assertThrows(
                GeneralException.class,
                () -> listener.handleFileStorageEvent(buildEvent()));

        assertThat(failure.getCause()).isSameAs(storageFailure);
        verify(fileService, never()).markFileUploadFailed(anyLong(), anyLong());
        verify(redisStateManager, never()).removeSession(anyString(), anyString());
    }

    /**
     * 验证已持久化链回执的 ATTESTED 阶段标记自动恢复，且不回退为 FAIL。
     */
    @Test
    void attestedFinalizationShouldRetainAutomaticRecoveryState() {
        FileUploadState state = new FileUploadState(
                100L, "contract.pdf", 1024L, "application/pdf", "client-1", 1024, 1);
        when(fileService.storeFile(
                anyLong(), any(Long.class), anyString(), anyList(), anyList(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db finalize failed"));
        when(fileService.getFinalizationRecoveryPhase(100L, 9527L))
                .thenReturn(FileService.FinalizationRecoveryPhase.CHAIN_ATTESTED);
        when(redisStateManager.getState("client-1")).thenReturn(state);

        assertThrows(GeneralException.class, () -> listener.handleFileStorageEvent(buildEvent()));

        assertThat(state.getStatus()).isEqualTo("finalization_recovery_pending");
        verify(redisStateManager).updateState(same(state));
        verify(fileService, never()).markFileUploadFailed(anyLong(), anyLong());
        verify(redisStateManager, never()).removeSession(anyString(), anyString());
    }

    /**
     * 验证自动恢复状态写入失败不覆盖原始最终化异常，也不触发删除。
     */
    @Test
    void automaticRecoveryRetentionFailureShouldNotMaskStorageFailure() {
        FileUploadState state = new FileUploadState(
                100L, "contract.pdf", 1024L, "application/pdf", "client-1", 1024, 1);
        RuntimeException storageFailure = new RuntimeException("db finalize failed");
        when(fileService.storeFile(
                anyLong(), any(Long.class), anyString(), anyList(), anyList(), anyString(), anyString()))
                .thenThrow(storageFailure);
        when(fileService.getFinalizationRecoveryPhase(100L, 9527L))
                .thenReturn(FileService.FinalizationRecoveryPhase.CHAIN_ATTESTED);
        when(redisStateManager.getState("client-1")).thenReturn(state);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(redisStateManager).updateState(same(state));

        GeneralException failure = assertThrows(
                GeneralException.class,
                () -> listener.handleFileStorageEvent(buildEvent()));

        assertThat(failure.getCause()).isSameAs(storageFailure);
        verify(fileService, never()).markFileUploadFailed(anyLong(), anyLong());
        verify(redisStateManager, never()).removeSession(anyString(), anyString());
    }

    /**
     * 验证失败状态事务回写异常时保留 Redis 会话和版本链供重试。
     */
    @Test
    void failureMarkExceptionShouldRetainUploadSession() {
        when(fileService.storeFile(
                anyLong(), any(Long.class), anyString(), anyList(), anyList(), anyString(), anyString()))
                .thenThrow(new RuntimeException("storage failed"));
        when(fileService.getFinalizationRecoveryPhase(100L, 9527L))
                .thenReturn(FileService.FinalizationRecoveryPhase.NONE);
        when(fileService.markFileUploadFailed(100L, 9527L))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(GeneralException.class, () -> listener.handleFileStorageEvent(buildEvent()));

        verify(redisStateManager, never()).removeSession(anyString(), anyString());
        verify(redisStateManager, never()).retainManualReconciliationState(any(), anyString(), anyLong());
    }

    /**
     * 验证并发终态使 FAIL 条件更新未命中时保留 Redis 恢复入口。
     */
    @Test
    void rejectedFailureMarkShouldRetainUploadSession() {
        when(fileService.storeFile(
                anyLong(), any(Long.class), anyString(), anyList(), anyList(), anyString(), anyString()))
                .thenThrow(new RuntimeException("storage failed"));
        when(fileService.getFinalizationRecoveryPhase(100L, 9527L))
                .thenReturn(FileService.FinalizationRecoveryPhase.CLAIMED);
        when(fileService.markFileUploadFailed(100L, 9527L)).thenReturn(false);

        assertThrows(GeneralException.class, () -> listener.handleFileStorageEvent(buildEvent()));

        verify(fileService).markFileUploadFailed(100L, 9527L);
        verify(redisStateManager, never()).removeSession(anyString(), anyString());
    }

    /**
     * 验证 SSE 通知异常不会覆盖已经成功的文件存证结果。
     */
    @Test
    void sseFailureShouldNotOverrideStoredFileSuccess() {
        File storedFile = new File();
        storedFile.setFileHash("hash-1");
        when(fileService.storeFile(
                anyLong(), any(Long.class), anyString(), anyList(), anyList(), anyString(), anyString()))
                .thenReturn(storedFile);
        doThrow(new IllegalStateException("sse executor unavailable"))
                .when(sseEmitterManager).sendToUser(anyLong(), anyLong(), any());

        assertDoesNotThrow(() -> listener.handleFileStorageEvent(buildEvent()));

        verify(fileService).storeFile(
                anyLong(), any(Long.class), anyString(), anyList(), anyList(), anyString(), anyString());
        verify(fileService, never()).markFileUploadFailed(anyLong(), anyLong());
        verify(redisStateManager, never()).removeSession(anyString(), anyString());
    }

    /**
     * 验证无 tenantId 时成功存证仍然完成，只跳过无法路由的通知。
     */
    @Test
    void missingTenantShouldSkipNotificationWithoutChangingSuccess() {
        File storedFile = new File();
        storedFile.setFileHash("hash-1");
        when(fileService.storeFile(
                anyLong(), any(Long.class), anyString(), anyList(), anyList(), anyString(), anyString()))
                .thenReturn(storedFile);

        assertDoesNotThrow(() -> listener.handleFileStorageEvent(
                buildEvent(null, 9527L, "client-1",
                        List.of(new java.io.File("/tmp/contract.part")))));

        verifyNoInteractions(sseEmitterManager);
        verify(fileService, never()).markFileUploadFailed(anyLong(), anyLong());
    }

    /**
     * 验证失败通知通道异常不会覆盖原始存储异常或触发二次清理。
     */
    @Test
    void failedNotificationExceptionShouldNotMaskStorageFailure() {
        RuntimeException storageFailure = new RuntimeException("storage failed");
        when(fileService.storeFile(
                anyLong(), any(Long.class), anyString(), anyList(), anyList(), anyString(), anyString()))
                .thenThrow(storageFailure);
        when(fileService.getFinalizationRecoveryPhase(100L, 9527L))
                .thenReturn(FileService.FinalizationRecoveryPhase.NONE);
        when(fileService.markFileUploadFailed(100L, 9527L)).thenReturn(true);
        doThrow(new IllegalStateException("sse unavailable"))
                .when(sseEmitterManager).sendToUser(anyLong(), anyLong(), any());

        GeneralException failure = assertThrows(
                GeneralException.class,
                () -> listener.handleFileStorageEvent(buildEvent()));

        assertThat(failure.getCause()).isSameAs(storageFailure);
        verify(fileService, times(1)).markFileUploadFailed(100L, 9527L);
        verify(sseEmitterManager, times(1)).sendToUser(eq(1L), eq(100L), any());
    }

    /**
     * 验证失败回写自身异常被附加为 suppressed，原始存证失败仍是对外主因。
     */
    @Test
    void failureHandlingExceptionShouldBeSuppressedUnderOriginalStorageFailure() {
        FileStorageEvent event = mock(FileStorageEvent.class);
        RuntimeException storageFailure = new RuntimeException("storage failed");
        RuntimeException failureHandlingError = new RuntimeException("event identity unavailable");
        when(event.getTenantId()).thenReturn(1L);
        when(event.getUid()).thenReturn(100L);
        when(event.getFileName()).thenReturn("contract.pdf");
        when(event.getProcessedFiles())
                .thenReturn(List.of(new java.io.File("/tmp/contract.part")));
        when(event.getFileHashes()).thenReturn(List.of("hash-1"));
        when(event.getFileParam()).thenReturn("{}");
        when(event.getClientId()).thenReturn("client-1");
        when(event.getPreparedFileId())
                .thenReturn(9527L, 9527L)
                .thenThrow(failureHandlingError);
        when(fileService.storeFile(
                anyLong(), any(Long.class), anyString(), anyList(), anyList(), anyString(), anyString()))
                .thenThrow(storageFailure);

        GeneralException failure = assertThrows(
                GeneralException.class,
                () -> listener.handleFileStorageEvent(event));

        assertThat(failure.getCause()).isSameAs(storageFailure);
        assertThat(storageFailure.getSuppressed()).containsExactly(failureHandlingError);
        verify(fileService, never()).markFileUploadFailed(anyLong(), anyLong());
        verifyNoInteractions(redisStateManager);
    }

    /**
     * 验证监听器不再持有或二次投递到 Executor，调用返回即代表 storeFile 已终结。
     */
    @Test
    void listenerShouldNotDependOnSecondExecutorSubmission() {
        assertThat(Arrays.stream(FileStorageEventListener.class.getDeclaredFields()))
                .noneMatch(field -> Executor.class.isAssignableFrom(field.getType()));
    }

    /**
     * 断言失败通知携带可诊断原因。
     *
     * @param expectedReason 期望失败原因
     */
    private void assertFailedNotification(String expectedReason) {
        ArgumentCaptor<SseEvent> eventCaptor = ArgumentCaptor.forClass(SseEvent.class);
        verify(sseEmitterManager).sendToUser(eq(1L), eq(100L), eventCaptor.capture());
        assertThat(eventCaptor.getValue().getType()).isEqualTo("file-record-failed");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) eventCaptor.getValue().getPayload();
        assertThat(payload)
                .containsEntry("fileName", "contract.pdf")
                .containsEntry("fileHash", null)
                .containsEntry("status", "failed")
                .containsEntry("reason", expectedReason);
    }
}
