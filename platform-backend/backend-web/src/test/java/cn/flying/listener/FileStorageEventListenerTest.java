package cn.flying.listener;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.event.FileStorageEvent;
import cn.flying.common.exception.GeneralException;
import cn.flying.dao.dto.File;
import cn.flying.dao.vo.file.FileUploadState;
import cn.flying.service.FileService;
import cn.flying.service.assistant.FileUploadRedisStateManager;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.service.sse.SseEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
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
        return new FileStorageEvent(
                this,
                1L,
                100L,
                9527L,
                "contract.pdf",
                "session-1",
                "client-1",
                List.of(new java.io.File("/tmp/contract.part")),
                List.of("hash-1"),
                "{\"contentType\":\"application/pdf\"}"
        );
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
        verify(fileService, never()).changeFileStatusById(anyLong(), anyLong(), anyInt());
        verify(fileService, never()).markFileUploadFailed(anyLong(), anyLong());
        verify(redisStateManager, never()).removeSession(anyString(), anyString());
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
     * 验证监听器不再持有或二次投递到 Executor，调用返回即代表 storeFile 已终结。
     */
    @Test
    void listenerShouldNotDependOnSecondExecutorSubmission() {
        assertThat(Arrays.stream(FileStorageEventListener.class.getDeclaredFields()))
                .noneMatch(field -> Executor.class.isAssignableFrom(field.getType()));
    }
}
