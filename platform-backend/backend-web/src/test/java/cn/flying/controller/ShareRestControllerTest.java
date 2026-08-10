package cn.flying.controller;

import cn.flying.common.annotation.RateLimit;
import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.dao.vo.file.FileDecryptInfoVO;
import cn.flying.dao.vo.file.FileDownloadMetadataVO;
import cn.flying.dao.vo.file.FileDownloadPartVO;
import cn.flying.dao.vo.file.FileSharingVO;
import cn.flying.dao.vo.file.SaveSharingFile;
import cn.flying.dao.vo.file.ShareFileVO;
import cn.flying.dao.vo.file.UpdateShareVO;
import cn.flying.dao.vo.file.DownloadKeyGrantConsumeRequestVO;
import cn.flying.dao.vo.file.DownloadKeyMaterialVO;
import cn.flying.service.FileQueryService;
import cn.flying.service.FileService;
import cn.flying.service.ShareAuditService;
import cn.flying.service.key.FileKeyGrantService;
import cn.flying.security.TrustedClientIpResolver;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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

    @Mock
    private TrustedClientIpResolver trustedClientIpResolver;

    @Mock
    private FileKeyGrantService fileKeyGrantService;

    private ShareRestController controller;

    /**
     * 初始化被测控制器并注入 mock 依赖。
     */
    @BeforeEach
    void setUp() {
        controller = new ShareRestController(
                fileService,
                fileQueryService,
                shareAuditService,
                trustedClientIpResolver,
                fileKeyGrantService
        );
        lenient().when(trustedClientIpResolver.resolve(any())).thenReturn("203.0.113.7");
    }

    /**
     * 验证分享 REST 新路径的核心流程都可正常执行并调用服务层。
     */
    @Test
    void shouldSupportShareRestEndpoints() {
        Long userId = 10L;
        String shareCode = "S100";
        String fileHash = "HASH100";
        FileDownloadMetadataVO downloadMetadata = new FileDownloadMetadataVO(
                "file-1", fileHash, "n", 1L, "text/plain", "k",
                "cn.flying.chunk-manifest.v1", "sha256:manifest",
                "SHA-256", "AES-GCM", "S3", 1L, 1,
                List.of(new FileDownloadPartVO(
                        0, 1L, "url-1", 1_000L, "chunks/0",
                        "plain-0", "cipher-0", "SHA-256")));

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
        when(fileQueryService.getShareFile(shareCode)).thenReturn(List.of(
                new ShareFileVO("f-1", "n", "document", fileHash, 1L, "text/plain", null, null, null, null)
        ));
        when(fileService.getSharedFileContent(userId, shareCode, fileHash)).thenReturn(List.of("abc".getBytes()));
        when(fileService.getSharedFileDecryptInfo(
                userId, shareCode, fileHash, "grant-v1", "session-123456789"))
                .thenReturn(new FileDecryptInfoVO("k1", "n", 1L, "text/plain", 1, fileHash));
        when(fileService.getSharedFileDownloadMetadata(
                userId, shareCode, fileHash, "grant-v1", "session-123456789"))
                .thenReturn(downloadMetadata);
        when(fileService.getPublicFile(shareCode, fileHash)).thenReturn(List.of("def".getBytes()));
        when(fileService.getPublicFileDecryptInfo(
                shareCode, fileHash, "grant-v1", "session-123456789", "203.0.113.7"))
                .thenReturn(new FileDecryptInfoVO("k2", "n", 2L, "text/plain", 2, fileHash));
        when(fileService.getPublicFileDownloadMetadata(
                shareCode, fileHash, "grant-v1", "session-123456789", "203.0.113.7"))
                .thenReturn(downloadMetadata);
        when(fileKeyGrantService.consumePublic(
                "B".repeat(43), "session-123456789", "203.0.113.7"))
                .thenReturn(new DownloadKeyMaterialVO("transient-public-key", "grant-v1"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2");
        request.addHeader("User-Agent", "JUnit");
        request.setRemoteAddr("10.0.0.10");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Result<String> createResult = controller.createShare(userId, createVO);
        Result<String> updateResult = controller.updateShare(userId, shareCode, updateVO);
        Result<List<ShareFileVO>> listResult = controller.getSharedFiles(shareCode, userId, request);
        Result<String> saveResult = controller.saveSharedFiles(shareCode, saveVO, request);
        Result<List<byte[]>> downloadResult = controller.downloadSharedFile(userId, shareCode, fileHash, request);
        Result<FileDecryptInfoVO> decryptResult = controller.getSharedDecryptInfo(
                userId, shareCode, fileHash, "grant-v1", "session-123456789", response);
        Result<FileDownloadMetadataVO> metadataResult = controller.getSharedDownloadMetadata(
                userId, shareCode, fileHash, "grant-v1", "session-123456789", response);
        Result<List<byte[]>> publicDownloadResult = controller.publicDownload(shareCode, fileHash, request);
        Result<FileDecryptInfoVO> publicDecryptResult = controller.publicDecryptInfo(
                shareCode, fileHash, "grant-v1", "session-123456789", request, response);
        Result<FileDownloadMetadataVO> publicMetadataResult = controller.publicDownloadMetadata(
                shareCode, fileHash, "grant-v1", "session-123456789", request, response);
        Result<DownloadKeyMaterialVO> publicKeyMaterialResult = controller.consumePublicDownloadKeyGrant(
                new DownloadKeyGrantConsumeRequestVO("B".repeat(43), "session-123456789"),
                request,
                response);

        assertEquals(shareCode, createResult.getData());
        assertEquals("分享设置已更新", updateResult.getData());
        assertNotNull(listResult.getData());
        assertEquals("保存成功", saveResult.getData());
        assertEquals(1, downloadResult.getData().size());
        assertEquals(fileHash, decryptResult.getData().fileHash());
        assertEquals(downloadMetadata, metadataResult.getData());
        assertEquals(1, publicDownloadResult.getData().size());
        assertEquals(fileHash, publicDecryptResult.getData().fileHash());
        assertEquals(downloadMetadata, publicMetadataResult.getData());
        assertEquals("transient-public-key", publicKeyMaterialResult.getData().initialKey());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("no-cache", response.getHeader("Pragma"));

        verify(fileService).updateShare(eq(userId), any(UpdateShareVO.class));
        verify(shareAuditService).logShareView(eq(shareCode), eq(userId), eq("203.0.113.7"), eq("JUnit"));
        verify(shareAuditService).logShareDownload(
                eq(shareCode), eq(userId), eq(fileHash), eq(null), eq("203.0.113.7"));
        verify(shareAuditService).logShareDownload(
                eq(shareCode), eq(null), eq(fileHash), eq(null), eq("203.0.113.7"));
        verify(fileKeyGrantService).consumePublic(
                "B".repeat(43), "session-123456789", "203.0.113.7");
        verify(fileService).getSharedFileDownloadMetadata(
                userId, shareCode, fileHash, "grant-v1", "session-123456789");
        verify(fileService).getPublicFileDownloadMetadata(
                shareCode, fileHash, "grant-v1", "session-123456789", "203.0.113.7");
    }

    /**
     * 验证保存分享文件时以路径中的 shareCode 为准，避免请求体伪造分享码。
     */
    @Test
    void shouldUsePathShareCodeWhenSavingSharedFiles() {
        SaveSharingFile saveVO = new SaveSharingFile();
        saveVO.setSharingFileIdList(List.of("f-1"));
        saveVO.setShareCode("body-code");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        Result<String> result = controller.saveSharedFiles("path-code", saveVO, request);

        assertEquals("保存成功", result.getData());
        assertEquals("path-code", saveVO.getShareCode());
        verify(fileService).saveShareFile(eq(List.of("f-1")), eq("path-code"), eq("203.0.113.7"));
    }

    /**
     * 验证匿名公开下载端点共用可信客户端 IP，且限流桶不再按调用者租户拆分。
     */
    @Test
    void shouldUseGlobalTrustedPeerRateLimitForAnonymousPublicShareEndpoints() throws NoSuchMethodException {
        RateLimit downloadRateLimit = ShareRestController.class
                .getMethod("publicDownload", String.class, String.class, jakarta.servlet.http.HttpServletRequest.class)
                .getAnnotation(RateLimit.class);
        RateLimit decryptRateLimit = ShareRestController.class
                .getMethod("publicDecryptInfo", String.class, String.class, String.class, String.class,
                        jakarta.servlet.http.HttpServletRequest.class,
                        jakarta.servlet.http.HttpServletResponse.class)
                .getAnnotation(RateLimit.class);
        RateLimit metadataRateLimit = ShareRestController.class
                .getMethod("publicDownloadMetadata", String.class, String.class, String.class, String.class,
                        jakarta.servlet.http.HttpServletRequest.class,
                        jakarta.servlet.http.HttpServletResponse.class)
                .getAnnotation(RateLimit.class);

        assertNotNull(downloadRateLimit);
        assertNotNull(decryptRateLimit);
        assertNotNull(metadataRateLimit);
        assertFalse(downloadRateLimit.tenantScoped());
        assertFalse(decryptRateLimit.tenantScoped());
        assertFalse(metadataRateLimit.tenantScoped());
        assertEquals(RateLimit.ClientIpMode.TRUSTED_PEER, downloadRateLimit.clientIpMode());
        assertEquals(RateLimit.ClientIpMode.TRUSTED_PEER, decryptRateLimit.clientIpMode());
        assertEquals(RateLimit.ClientIpMode.TRUSTED_PEER, metadataRateLimit.clientIpMode());
        assertEquals(RateLimit.LimitType.IP, downloadRateLimit.type());
        assertEquals(RateLimit.LimitType.IP, decryptRateLimit.type());
        assertEquals(RateLimit.LimitType.IP, metadataRateLimit.type());
        assertEquals(downloadRateLimit.key(), decryptRateLimit.key());
        assertEquals(downloadRateLimit.key(), metadataRateLimit.key());
        assertEquals(30, downloadRateLimit.limit());
        assertEquals(60, downloadRateLimit.period());
        assertEquals(30, metadataRateLimit.limit());
        assertEquals(60, metadataRateLimit.period());
    }

    /**
     * 验证公开 grant 消费使用独立可信客户端 IP 限流且不保存请求体。
     */
    @Test
    void shouldProtectPublicGrantConsumeContract() throws NoSuchMethodException {
        var method = ShareRestController.class.getMethod(
                "consumePublicDownloadKeyGrant",
                DownloadKeyGrantConsumeRequestVO.class,
                jakarta.servlet.http.HttpServletRequest.class,
                jakarta.servlet.http.HttpServletResponse.class);
        RateLimit rateLimit = method.getAnnotation(RateLimit.class);
        OperationLog operationLog = method.getAnnotation(OperationLog.class);

        assertNotNull(rateLimit);
        assertEquals(20, rateLimit.limit());
        assertEquals(60, rateLimit.period());
        assertEquals(RateLimit.LimitType.IP, rateLimit.type());
        assertEquals(RateLimit.ClientIpMode.TRUSTED_PEER, rateLimit.clientIpMode());
        assertFalse(rateLimit.tenantScoped());
        assertEquals("public:key-grant:consume", rateLimit.key());
        assertNotNull(operationLog);
        assertFalse(operationLog.saveRequestData());
    }

    /**
     * 验证登录分享的 grant 签发入口复用认证用户签发限流桶。
     */
    @Test
    void shouldProtectAuthenticatedShareGrantIssueContract() throws NoSuchMethodException {
        var method = ShareRestController.class.getMethod(
                "getSharedDecryptInfo", Long.class, String.class, String.class, String.class, String.class,
                jakarta.servlet.http.HttpServletResponse.class);
        var metadataMethod = ShareRestController.class.getMethod(
                "getSharedDownloadMetadata", Long.class, String.class, String.class, String.class, String.class,
                jakarta.servlet.http.HttpServletResponse.class);
        RateLimit rateLimit = method.getAnnotation(RateLimit.class);
        RateLimit metadataRateLimit = metadataMethod.getAnnotation(RateLimit.class);
        OperationLog operationLog = method.getAnnotation(OperationLog.class);
        OperationLog metadataOperationLog = metadataMethod.getAnnotation(OperationLog.class);

        assertNotNull(rateLimit);
        assertEquals(60, rateLimit.limit());
        assertEquals(60, rateLimit.period());
        assertEquals(RateLimit.LimitType.USER, rateLimit.type());
        assertEquals("download:key-grant:issue", rateLimit.key());
        assertNotNull(metadataRateLimit);
        assertEquals(rateLimit.limit(), metadataRateLimit.limit());
        assertEquals(rateLimit.period(), metadataRateLimit.period());
        assertEquals(rateLimit.type(), metadataRateLimit.type());
        assertEquals(rateLimit.key(), metadataRateLimit.key());
        assertNotNull(operationLog);
        assertFalse(operationLog.saveRequestData());
        assertNotNull(metadataOperationLog);
        assertFalse(metadataOperationLog.saveRequestData());
    }

    /**
     * 验证创建分享请求在控制器校验阶段只接受公开或私密两种类型。
     */
    @Test
    void shouldRejectUnsupportedCreateShareTypeAtValidationBoundary() {
        FileSharingVO request = new FileSharingVO();
        request.setFileHash(List.of("hash-1"));
        request.setExpireMinutes(60);
        request.setShareType(99);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(request);
            assertEquals(1, violations.size());
            assertEquals("shareType", violations.iterator().next().getPropertyPath().toString());
        }
    }
}
