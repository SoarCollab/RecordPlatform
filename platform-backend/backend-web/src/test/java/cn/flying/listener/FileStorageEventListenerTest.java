package cn.flying.listener;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.event.FileStorageEvent;
import cn.flying.dao.dto.File;
import cn.flying.service.FileService;
import cn.flying.service.assistant.FileUploadRedisStateManager;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.service.sse.SseEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
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
     * 使用同步执行器替代异步线程池，保证测试可重复且断言稳定。
     */
    @BeforeEach
    void setUp() {
        Executor directExecutor = Runnable::run;
        ReflectionTestUtils.setField(listener, "fileProcessTaskExecutor", directExecutor);
    }

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
        when(fileService.storeFile(anyLong(), any(Long.class), anyString(), anyList(), anyList(), anyString())).thenReturn(storedFile);

        listener.handleFileStorageEvent(buildEvent());

        ArgumentCaptor<SseEvent> eventCaptor = ArgumentCaptor.forClass(SseEvent.class);
        verify(sseEmitterManager, times(1)).sendToUser(eq(1L), eq(100L), eventCaptor.capture());
        assertThat(eventCaptor.getValue().getType()).isEqualTo("file-record-success");
        verify(fileService, never()).changeFileStatusByName(anyLong(), anyString(), anyInt());
        verify(fileService, never()).changeFileStatusById(anyLong(), anyLong(), anyInt());
        verify(fileService, never()).markFileUploadFailed(anyLong(), anyLong());
        verify(redisStateManager, never()).removeSession(anyString(), anyString());
        verify(redisStateManager, never()).removeSessionByFileName(anyLong(), anyString());
    }

    @Test
    @DisplayName("failure path should send file-record-failed and keep cleanup behavior")
    void failurePathShouldSendFileRecordFailedAndKeepCleanupBehavior() {
        when(fileService.storeFile(anyLong(), any(Long.class), anyString(), anyList(), anyList(), anyString()))
                .thenThrow(new RuntimeException("chain write failed"));

        listener.handleFileStorageEvent(buildEvent());

        verify(fileService, times(1)).markFileUploadFailed(100L, 9527L);
        verify(fileService, never()).changeFileStatusById(anyLong(), anyLong(), eq(FileUploadStatus.FAIL.getCode()));
        verify(redisStateManager, times(1)).removeSession("client-1", "session-1");
        verify(redisStateManager, never()).removeSessionByFileName(anyLong(), anyString());

        ArgumentCaptor<SseEvent> eventCaptor = ArgumentCaptor.forClass(SseEvent.class);
        verify(sseEmitterManager, times(1)).sendToUser(eq(1L), eq(100L), eventCaptor.capture());
        assertThat(eventCaptor.getValue().getType()).isEqualTo("file-record-failed");
    }
}
