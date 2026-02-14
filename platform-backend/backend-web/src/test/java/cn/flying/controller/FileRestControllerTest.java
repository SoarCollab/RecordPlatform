package cn.flying.controller;

import cn.flying.common.constant.Result;
import cn.flying.dao.dto.File;
import cn.flying.dao.vo.file.BatchDownloadMetricsReportVO;
import cn.flying.dao.vo.file.FileDecryptInfoVO;
import cn.flying.service.DownloadBatchMetricsService;
import cn.flying.service.FileQueryService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FileRestController 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class FileRestControllerTest {

    @Mock
    private FileQueryService fileQueryService;

    @Mock
    private DownloadBatchMetricsService downloadBatchMetricsService;

    private FileRestController controller;

    /**
     * 初始化被测控制器并注入 mock 依赖。
     */
    @BeforeEach
    void setUp() {
        controller = new FileRestController();
        ReflectionTestUtils.setField(controller, "fileQueryService", fileQueryService);
        ReflectionTestUtils.setField(controller, "downloadBatchMetricsService", downloadBatchMetricsService);
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

        Result<Page<File>> pageResult = controller.getFiles(userId, 1, 10, null, null, null, null);
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

    /**
     * 验证分页边界裁剪与时间参数透传。
     */
    @Test
    void shouldNormalizePaginationAndForwardTimeRange() {
        Long userId = 88L;
        OffsetDateTime startTime = OffsetDateTime.of(2026, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endTime = OffsetDateTime.of(2026, 2, 10, 0, 0, 0, 0, ZoneOffset.UTC);

        Result<Page<File>> result = controller.getFiles(
                userId,
                0,
                1000,
                "report",
                1,
                startTime,
                endTime
        );

        assertNotNull(result.getData());
        verify(fileQueryService).getUserFilesPage(
                eq(userId),
                any(Page.class),
                eq("report"),
                eq(1),
                eq(Date.from(startTime.toInstant())),
                eq(Date.from(endTime.toInstant()))
        );
        assertEquals(1, result.getData().getCurrent());
        assertEquals(100, result.getData().getSize());
    }

    /**
     * 验证批量下载指标上报接口会委托到服务层。
     */
    @Test
    void shouldDelegateBatchMetricsReportToService() {
        BatchDownloadMetricsReportVO report = new BatchDownloadMetricsReportVO(
                "batch-1",
                10,
                8,
                2,
                3,
                1500L,
                java.util.Map.of("network_error", 2)
        );

        Result<String> result = controller.reportDownloadBatchMetrics(88L, 1L, report);

        assertEquals("ok", result.getData());
        verify(downloadBatchMetricsService).reportBatchMetrics(1L, 88L, report);
    }
}
