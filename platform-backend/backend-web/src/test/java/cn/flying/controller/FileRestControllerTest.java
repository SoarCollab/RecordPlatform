package cn.flying.controller;

import cn.flying.common.constant.Result;
import cn.flying.common.annotation.OperationLog;
import cn.flying.common.annotation.RateLimit;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecureIdCodec;
import cn.flying.dao.dto.File;
import cn.flying.dao.vo.file.BatchDownloadMetricsReportVO;
import cn.flying.dao.vo.file.FileDecryptInfoVO;
import cn.flying.dao.vo.file.FileDownloadMetadataVO;
import cn.flying.dao.vo.file.FileDownloadPartVO;
import cn.flying.dao.vo.file.FileVO;
import cn.flying.dao.vo.file.DownloadKeyGrantConsumeRequestVO;
import cn.flying.dao.vo.file.DownloadKeyMaterialVO;
import cn.flying.service.DownloadBatchMetricsService;
import cn.flying.service.FileQueryService;
import cn.flying.service.key.FileKeyGrantService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

    @Mock
    private FileKeyGrantService fileKeyGrantService;

    private FileRestController controller;

    /**
     * 初始化被测控制器并注入 mock 依赖。
     */
    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(
                IdUtils.class,
                "secureIdCodec",
                new SecureIdCodec("SecureTestKey4UnitTests2026XyZ789AbCdEfGhIjKlMnOpQrStUvWxYz1234")
        );
        controller = new FileRestController(
                fileQueryService, downloadBatchMetricsService, fileKeyGrantService);
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
        when(fileQueryService.getFileDecryptInfo(userId, fileHash, "grant-v1", "session-123456789"))
                .thenReturn(new FileDecryptInfoVO("k", "n", 1L, "text/plain", 1, fileHash));
        when(fileQueryService.getDownloadMetadata(userId, fileHash, "grant-v1", "session-123456789"))
                .thenReturn(new FileDownloadMetadataVO(
                        "file-1",
                        fileHash,
                        "n",
                        1L,
                        "text/plain",
                        "k",
                        "cn.flying.chunk-manifest.v1",
                        "sha256:manifest",
                        "SHA-256",
                        "AES-GCM",
                        "S3",
                        1L,
                        1,
                        List.of(new FileDownloadPartVO(
                                0,
                                1L,
                                "url-1",
                                1000L,
                                "chunks/0",
                                "plain-0",
                                "cipher-0",
                                "SHA-256"
                        ))
                ));
        when(fileKeyGrantService.consumeAuthenticated(
                "A".repeat(43), "session-123456789", userId))
                .thenReturn(new DownloadKeyMaterialVO("transient-key", "grant-v1"));

        Result<Page<FileVO>> pageResult = controller.getFiles(userId, 1, 10, null, null, null, null, null);
        Result<FileVO> byHashResult = controller.getFileByHash(userId, fileHash);
        Result<List<String>> addressResult = controller.getFileAddresses(userId, fileHash);
        MockHttpServletResponse response = new MockHttpServletResponse();
        Result<FileDownloadMetadataVO> metadataResult = controller.getDownloadMetadata(
                userId, fileHash, "grant-v1", "session-123456789", response);
        Result<List<byte[]>> chunksResult = controller.getFileChunks(userId, fileHash);
        Result<FileDecryptInfoVO> decryptInfoResult = controller.getFileDecryptInfo(
                userId, fileHash, "grant-v1", "session-123456789", response);
        Result<DownloadKeyMaterialVO> keyMaterialResult = controller.consumeDownloadKeyGrant(
                userId,
                new DownloadKeyGrantConsumeRequestVO("A".repeat(43), "session-123456789"),
                response);

        assertNotNull(pageResult.getData());
        assertNotNull(byHashResult.getData());
        assertEquals(1, addressResult.getData().size());
        assertEquals("sha256:manifest", metadataResult.getData().manifestHash());
        assertEquals(1, chunksResult.getData().size());
        assertEquals(fileHash, decryptInfoResult.getData().fileHash());
        assertEquals("transient-key", keyMaterialResult.getData().initialKey());
        assertEquals("no-store, private", response.getHeader("Cache-Control"));
        assertEquals("no-cache", response.getHeader("Pragma"));
        verify(fileQueryService).getFileByHash(userId, fileHash);
        verify(fileQueryService).getFileAddress(userId, fileHash);
        verify(fileQueryService).getDownloadMetadata(userId, fileHash, "grant-v1", "session-123456789");
        verify(fileQueryService).getFile(userId, fileHash);
        verify(fileQueryService).getFileDecryptInfo(userId, fileHash, "grant-v1", "session-123456789");
        verify(fileKeyGrantService).consumeAuthenticated(
                "A".repeat(43), "session-123456789", userId);
    }

    /**
     * 验证认证 grant 消费使用独立用户限流桶且操作日志不保存请求体。
     */
    @Test
    void shouldProtectAuthenticatedGrantConsumeContract() throws NoSuchMethodException {
        var method = FileRestController.class.getMethod(
                "consumeDownloadKeyGrant",
                Long.class,
                DownloadKeyGrantConsumeRequestVO.class,
                jakarta.servlet.http.HttpServletResponse.class);
        RateLimit rateLimit = method.getAnnotation(RateLimit.class);
        OperationLog operationLog = method.getAnnotation(OperationLog.class);

        assertNotNull(rateLimit);
        assertEquals(20, rateLimit.limit());
        assertEquals(60, rateLimit.period());
        assertEquals(RateLimit.LimitType.USER, rateLimit.type());
        assertEquals("download:key-grant:consume", rateLimit.key());
        assertNotNull(operationLog);
        assertFalse(operationLog.saveRequestData());
    }

    /**
     * 验证两个认证 grant 签发入口共享独立用户限流桶且不记录请求数据。
     */
    @Test
    void shouldProtectAuthenticatedGrantIssueContracts() throws NoSuchMethodException {
        var metadataMethod = FileRestController.class.getMethod(
                "getDownloadMetadata", Long.class, String.class, String.class, String.class,
                jakarta.servlet.http.HttpServletResponse.class);
        var decryptMethod = FileRestController.class.getMethod(
                "getFileDecryptInfo", Long.class, String.class, String.class, String.class,
                jakarta.servlet.http.HttpServletResponse.class);

        for (var method : List.of(metadataMethod, decryptMethod)) {
            RateLimit rateLimit = method.getAnnotation(RateLimit.class);
            OperationLog operationLog = method.getAnnotation(OperationLog.class);
            assertNotNull(rateLimit);
            assertEquals(60, rateLimit.limit());
            assertEquals(60, rateLimit.period());
            assertEquals(RateLimit.LimitType.USER, rateLimit.type());
            assertEquals("download:key-grant:issue", rateLimit.key());
            assertNotNull(operationLog);
            assertFalse(operationLog.saveRequestData());
        }
    }

    /**
     * 验证分页边界裁剪与时间参数透传。
     */
    @Test
    void shouldNormalizePaginationAndForwardTimeRange() {
        Long userId = 88L;
        OffsetDateTime startTime = OffsetDateTime.of(2026, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endTime = OffsetDateTime.of(2026, 2, 10, 0, 0, 0, 0, ZoneOffset.UTC);

        Result<Page<FileVO>> result = controller.getFiles(
                userId,
                0,
                1000,
                "report",
                "prefix",
                1,
                startTime,
                endTime
        );

        assertNotNull(result.getData());
        verify(fileQueryService).getUserFilesPage(
                eq(userId),
                any(Page.class),
                eq("report"),
                eq("PREFIX"),
                eq(1),
                eq(Date.from(startTime.toInstant())),
                eq(Date.from(endTime.toInstant()))
        );
        assertEquals(1, result.getData().getCurrent());
        assertEquals(100, result.getData().getSize());
    }

    /**
     * 验证 keywordMode 为空时会回落为 FUZZY。
     */
    @Test
    void shouldFallbackKeywordModeToFuzzyWhenBlank() {
        Long userId = 90L;

        Result<Page<FileVO>> result = controller.getFiles(
                userId,
                1,
                10,
                "invoice",
                "   ",
                null,
                null,
                null
        );

        assertNotNull(result.getData());
        verify(fileQueryService).getUserFilesPage(
                eq(userId),
                any(Page.class),
                eq("invoice"),
                eq("FUZZY"),
                isNull(),
                isNull(),
                isNull()
        );
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
