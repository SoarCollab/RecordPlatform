package cn.flying.controller;

import cn.flying.common.constant.Result;
import cn.flying.dao.dto.File;
import cn.flying.dao.vo.file.FileDecryptInfoVO;
import cn.flying.dao.vo.file.FileSharingVO;
import cn.flying.dao.vo.file.SaveSharingFile;
import cn.flying.dao.vo.file.UpdateShareVO;
import cn.flying.service.FileQueryService;
import cn.flying.service.FileService;
import cn.flying.service.ShareAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ShareRestController 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class ShareRestControllerTest {

    @Mock
    private FileService fileService;

    @Mock
    private FileQueryService fileQueryService;

    @Mock
    private ShareAuditService shareAuditService;

    private ShareRestController controller;

    /**
     * 初始化被测控制器并注入 mock 依赖。
     */
    @BeforeEach
    void setUp() {
        controller = new ShareRestController();
        ReflectionTestUtils.setField(controller, "fileService", fileService);
        ReflectionTestUtils.setField(controller, "fileQueryService", fileQueryService);
        ReflectionTestUtils.setField(controller, "shareAuditService", shareAuditService);
    }

    /**
     * 验证分享 REST 新路径的核心流程都可正常执行并调用服务层。
     */
    @Test
    void shouldSupportShareRestEndpoints() {
        Long userId = 10L;
        String shareCode = "S100";
        String fileHash = "HASH100";

        FileSharingVO createVO = new FileSharingVO();
        createVO.setFileHash(List.of(fileHash));
        createVO.setExpireMinutes(60);
        createVO.setShareType(0);

        UpdateShareVO updateVO = new UpdateShareVO();
        updateVO.setShareType(1);
        updateVO.setExtendMinutes(120);

        SaveSharingFile saveVO = new SaveSharingFile();
        saveVO.setSharingFileIdList(List.of("f-1"));
        saveVO.setShareCode(shareCode);

        when(fileService.generateSharingCode(userId, createVO.getFileHash(), 60, 0)).thenReturn(shareCode);
        when(fileQueryService.getShareFile(shareCode)).thenReturn(List.of(new File()));
        when(fileService.getSharedFileContent(userId, shareCode, fileHash)).thenReturn(List.of("abc".getBytes()));
        when(fileService.getSharedFileDecryptInfo(userId, shareCode, fileHash))
                .thenReturn(new FileDecryptInfoVO("k1", "n", 1L, "text/plain", 1, fileHash));
        when(fileService.getPublicFile(shareCode, fileHash)).thenReturn(List.of("def".getBytes()));
        when(fileService.getPublicFileDecryptInfo(shareCode, fileHash))
                .thenReturn(new FileDecryptInfoVO("k2", "n", 2L, "text/plain", 2, fileHash));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2");
        request.addHeader("User-Agent", "JUnit");

        Result<String> createResult = controller.createShare(userId, createVO);
        Result<String> updateResult = controller.updateShare(userId, shareCode, updateVO);
        Result<List<File>> listResult = controller.getSharedFiles(shareCode, userId, request);
        Result<String> saveResult = controller.saveSharedFiles(shareCode, saveVO, request);
        Result<List<byte[]>> downloadResult = controller.downloadSharedFile(userId, shareCode, fileHash, request);
        Result<FileDecryptInfoVO> decryptResult = controller.getSharedDecryptInfo(userId, shareCode, fileHash);
        Result<List<byte[]>> publicDownloadResult = controller.publicDownload(shareCode, fileHash, request);
        Result<FileDecryptInfoVO> publicDecryptResult = controller.publicDecryptInfo(shareCode, fileHash);

        assertEquals(shareCode, createResult.getData());
        assertEquals("分享设置已更新", updateResult.getData());
        assertNotNull(listResult.getData());
        assertEquals("保存成功", saveResult.getData());
        assertEquals(1, downloadResult.getData().size());
        assertEquals(fileHash, decryptResult.getData().fileHash());
        assertEquals(1, publicDownloadResult.getData().size());
        assertEquals(fileHash, publicDecryptResult.getData().fileHash());

        verify(fileService).updateShare(eq(userId), any(UpdateShareVO.class));
        verify(shareAuditService).logShareView(eq(shareCode), eq(userId), eq("1.1.1.1"), eq("JUnit"));
        verify(shareAuditService).logShareDownload(eq(shareCode), eq(userId), eq(fileHash), eq(null), eq("1.1.1.1"));
    }
}
