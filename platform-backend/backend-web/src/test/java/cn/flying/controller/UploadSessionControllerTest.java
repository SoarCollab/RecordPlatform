package cn.flying.controller;

import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.dao.vo.file.FileUploadStatusVO;
import cn.flying.dao.vo.file.ProgressVO;
import cn.flying.dao.vo.file.ResumeUploadVO;
import cn.flying.dao.vo.file.StartUploadVO;
import cn.flying.service.FileUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UploadSessionController 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class UploadSessionControllerTest {

    @Mock
    private FileUploadService fileUploadService;

    private UploadSessionController controller;

    /**
     * 初始化被测控制器并注入 mock 依赖。
     */
    @BeforeEach
    void setUp() {
        controller = new UploadSessionController();
        ReflectionTestUtils.setField(controller, "fileUploadService", fileUploadService);
    }

    /**
     * 验证上传会话创建、分片上传与状态查询链路。
     */
    @Test
    void shouldHandleUploadSessionLifecycle() {
        Long userId = 20L;
        String clientId = "client-1";

        StartUploadVO startUploadVO = new StartUploadVO(clientId, 1024, 2, false, List.of(), List.of(), false);
        ResumeUploadVO resumeUploadVO = new ResumeUploadVO(List.of(1), 2);
        FileUploadStatusVO statusVO = new FileUploadStatusVO("f", 100L, clientId, false, "UPLOADING", 50, List.of(1), 1, 2);
        ProgressVO progressVO = new ProgressVO(50, 50, 0, 1, 0, 2, clientId, "uploading");

        when(fileUploadService.startUpload(userId, "a.txt", 100L, "text/plain", null, 1024, 2)).thenReturn(startUploadVO);
        when(fileUploadService.resumeUpload(userId, clientId)).thenReturn(resumeUploadVO);
        when(fileUploadService.checkFileStatus(userId, clientId)).thenReturn(statusVO);
        when(fileUploadService.getUploadProgress(userId, clientId)).thenReturn(progressVO);
        when(fileUploadService.cancelUpload(userId, clientId)).thenReturn(true);

        Result<StartUploadVO> startResult = controller.createUploadSession(userId, "a.txt", 100L, "text/plain", null, 1024, 2);
        Result<String> uploadResult = controller.uploadChunk(
                userId,
                clientId,
                1,
                new MockMultipartFile("file", "chunk.bin", "application/octet-stream", new byte[]{1})
        );
        Result<String> completeResult = controller.completeUpload(userId, clientId);
        Result<String> pauseResult = controller.pauseUpload(userId, clientId);
        Result<ResumeUploadVO> resumeResult = controller.resumeUpload(userId, clientId);
        Result<String> cancelResult = controller.cancelUpload(userId, clientId);
        Result<FileUploadStatusVO> statusResult = controller.getUploadSession(userId, clientId);
        Result<ProgressVO> progressResult = controller.getUploadProgress(userId, clientId);

        assertEquals(clientId, startResult.getData().getClientId());
        assertEquals("分片上传成功", uploadResult.getData());
        assertEquals("文件处理完成", completeResult.getData());
        assertEquals("上传已暂停", pauseResult.getData());
        assertNotNull(resumeResult.getData());
        assertEquals("上传已取消", cancelResult.getData());
        assertEquals(clientId, statusResult.getData().getClientId());
        assertEquals(clientId, progressResult.getData().getClientId());

        verify(fileUploadService).completeUpload(userId, clientId);
        verify(fileUploadService).pauseUpload(userId, clientId);
        verify(fileUploadService).uploadChunk(eq(userId), eq(clientId), eq(1), any());
    }

    /**
     * 验证取消上传失败时返回 RESULT_DATA_NONE。
     */
    @Test
    void shouldReturnResultDataNoneWhenCancelFailed() {
        when(fileUploadService.cancelUpload(30L, "client-x")).thenReturn(false);
        Result<String> result = controller.cancelUpload(30L, "client-x");
        assertEquals(ResultEnum.RESULT_DATA_NONE.getCode(), result.getCode());
    }
}
