package cn.flying.controller;

import cn.flying.common.constant.Result;
import cn.flying.dao.dto.File;
import cn.flying.dao.vo.file.FileDecryptInfoVO;
import cn.flying.service.FileQueryService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FileRestController 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class FileRestControllerTest {

    @Mock
    private FileQueryService fileQueryService;

    private FileRestController controller;

    /**
     * 初始化被测控制器并注入 mock 依赖。
     */
    @BeforeEach
    void setUp() {
        controller = new FileRestController();
        ReflectionTestUtils.setField(controller, "fileQueryService", fileQueryService);
    }

    /**
     * 验证文件查询相关 REST 新路径均可正常委托到服务层。
     */
    @Test
    void shouldDelegateFileRestEndpointsToQueryService() {
        Long userId = 100L;
        String fileHash = "hash-001";

        when(fileQueryService.getFileByHash(userId, fileHash)).thenReturn(new File());
        when(fileQueryService.getFileAddress(userId, fileHash)).thenReturn(List.of("url-1"));
        when(fileQueryService.getFile(userId, fileHash)).thenReturn(List.of("a".getBytes()));
        when(fileQueryService.getFileDecryptInfo(userId, fileHash))
                .thenReturn(new FileDecryptInfoVO("k", "n", 1L, "text/plain", 1, fileHash));

        Result<Page<File>> pageResult = controller.getFiles(userId, 1, 10, null, null);
        Result<File> byHashResult = controller.getFileByHash(userId, fileHash);
        Result<List<String>> addressResult = controller.getFileAddresses(userId, fileHash);
        Result<List<byte[]>> chunksResult = controller.getFileChunks(userId, fileHash);
        Result<FileDecryptInfoVO> decryptInfoResult = controller.getFileDecryptInfo(userId, fileHash);

        assertNotNull(pageResult.getData());
        assertNotNull(byHashResult.getData());
        assertEquals(1, addressResult.getData().size());
        assertEquals(1, chunksResult.getData().size());
        assertEquals(fileHash, decryptInfoResult.getData().fileHash());
        verify(fileQueryService).getFileByHash(userId, fileHash);
        verify(fileQueryService).getFileAddress(userId, fileHash);
        verify(fileQueryService).getFile(userId, fileHash);
        verify(fileQueryService).getFileDecryptInfo(userId, fileHash);
    }
}
