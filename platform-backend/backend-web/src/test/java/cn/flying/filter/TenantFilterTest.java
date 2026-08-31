package cn.flying.filter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/**
 * TenantFilter 单元测试。
 * <p>
 * 目标：覆盖租户头解析、白名单放行、错误分支与 ThreadLocal 清理，避免多租户上下文泄漏导致的隐蔽 BUG。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TenantFilter Tests")
class TenantFilterTest {

    private final TenantFilter filter = new TenantFilter(new cn.flying.config.PrometheusScrapeSecurity(false, "", ""));

    @Mock
    private FilterChain filterChain;

    /**
     * 清理 TenantContext，避免测试间 ThreadLocal 污染。
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * 白名单路径无需租户头，应直接放行，并清空可能残留的 TenantContext。
     */
    @Test
    @DisplayName("should allow whitelisted path without tenant header")
    void shouldAllowWhitelistedPathWithoutTenantHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.setServletPath("/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        TenantContext.setTenantId(999L);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(TenantContext.getTenantId());
    }

    @Test
    @DisplayName("should allow share info endpoint without tenant header")
    void shouldAllowShareEndpointWithoutTenantHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/shares/abc/info");
        request.setServletPath("/api/v1/shares/abc/info");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(TenantContext.getTenantId());
    }

    /**
     * REST 公开分享文件列表路径无需租户头，应放行。
     */
    @Test
    @DisplayName("should allow public share files endpoint without tenant header")
    void shouldAllowPublicShareFilesEndpointWithoutTenantHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/shares/share123/files");
        request.setServletPath("/api/v1/shares/share123/files");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(TenantContext.getTenantId());
    }

    /**
     * 公开 proof 状态和历史公钥端点无需租户头，且不得建立残留租户上下文。
     */
    @Test
    @DisplayName("should allow public proof endpoints without tenant header")
    void shouldAllowPublicProofEndpointsWithoutTenantHeader() throws ServletException, IOException {
        for (String path : new String[]{
                "/api/v1/public/proofs/rp-proof-abc/status",
                "/api/v1/public/proof-keys/key-main/versions/1"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            request.setServletPath(path);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            clearInvocations(filterChain);
            assertNull(TenantContext.getTenantId());
        }
    }

    /**
     * 公开 proof 端点不得信任调用者提供的租户头，避免匿名请求污染指定租户的操作日志。
     */
    @Test
    @DisplayName("should ignore tenant header for public proof endpoints")
    void shouldIgnoreTenantHeaderForPublicProofEndpoints() throws ServletException, IOException {
        AtomicLong chainCalls = new AtomicLong();
        doAnswer(invocation -> {
            MockHttpServletRequest chainedRequest = invocation.getArgument(0);
            assertNull(TenantContext.getTenantId());
            assertNull(chainedRequest.getAttribute(Const.ATTR_TENANT_ID));
            chainCalls.incrementAndGet();
            return null;
        }).when(filterChain).doFilter(any(), any());

        for (String path : new String[]{
                "/api/v1/public/proofs/rp-proof-abc/status",
                "/api/v1/public/proof-keys/key-main/versions/1"}) {
            for (String tenantHeader : new String[]{"12", "spoofed-tenant", ""}) {
                MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
                request.setServletPath(path);
                request.addHeader("X-Tenant-ID", tenantHeader);
                MockHttpServletResponse response = new MockHttpServletResponse();

                filter.doFilterInternal(request, response, filterChain);

                assertNull(request.getAttribute(Const.ATTR_TENANT_ID));
                assertNull(TenantContext.getTenantId());
            }
        }

        assertEquals(6L, chainCalls.get());
        verify(filterChain, times(6)).doFilter(any(), any());
    }

    /**
     * 公开 proof 路径必须完整忽略重复租户头，避免新校验改变匿名 proof 合同。
     */
    @Test
    @DisplayName("should ignore duplicate tenant headers for public proof endpoints")
    void shouldIgnoreDuplicateTenantHeadersForPublicProofEndpoints() throws ServletException, IOException {
        for (String path : new String[]{
                "/api/v1/public/proofs/rp-proof-abc/status",
                "/api/v1/public/proof-keys/key-main/versions/1"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            request.setServletPath(path);
            request.addHeader("X-Tenant-ID", "12");
            request.addHeader("X-Tenant-ID", "13");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, filterChain);

            assertNull(request.getAttribute(Const.ATTR_TENANT_ID));
            assertNull(TenantContext.getTenantId());
        }

        verify(filterChain, times(2)).doFilter(any(), any());
    }

    /**
     * 与公开 proof 前缀相似但不在该路径族内的请求不得误用忽略规则。
     */
    @Test
    @DisplayName("should preserve tenant header for similar public proof prefix")
    void shouldPreserveTenantHeaderForSimilarPublicProofPrefix() throws ServletException, IOException {
        String path = "/api/v1/public/proofs-admin";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        request.addHeader("X-Tenant-ID", "12");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicLong capturedTenantId = new AtomicLong(-1L);
        doAnswer(invocation -> {
            Long tenantId = TenantContext.getTenantId();
            capturedTenantId.set(tenantId == null ? -1L : tenantId);
            return null;
        }).when(filterChain).doFilter(request, response);

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(12L, capturedTenantId.get());
        assertEquals(12L, request.getAttribute(Const.ATTR_TENANT_ID));
        assertNull(TenantContext.getTenantId());
        verify(filterChain).doFilter(request, response);
    }

    /**
     * 匿名公开分享 GET 及其等价编码形式必须完整忽略调用者租户头。
     */
    @Test
    @DisplayName("should ignore every tenant header form for anonymous public share GET endpoints")
    void shouldIgnoreEveryTenantHeaderFormForAnonymousPublicShareGetEndpoints()
            throws ServletException, IOException {
        AtomicLong chainCalls = new AtomicLong();
        doAnswer(invocation -> {
            MockHttpServletRequest chainedRequest = invocation.getArgument(0);
            assertNull(TenantContext.getTenantId());
            assertNull(chainedRequest.getAttribute(Const.ATTR_TENANT_ID));
            chainCalls.incrementAndGet();
            return null;
        }).when(filterChain).doFilter(any(), any());

        String[] paths = {
                "/api/v1/shares/abc/info",
                "/api/v1/shares/abc/files",
                "/api/v1/public/shares/abc/files/hash-1/chunks",
                "/api/v1/public/shares/abc/files/hash-1/decrypt-info",
                "/api/v1/public/shares/abc/files/hash-1/download-metadata",
                "/api/v1/sh%61res/abc/info",
                "/api/v1/shares;x=1/abc/files",
                "/api/v1/public/sh%61res/abc/f%69les/hash-1/chunks",
                "/api/v1/public/shares;x=1/abc/files;v=2/hash-1/decrypt-info",
                "/api/v1/shares/./abc/info",
                "/api/v1/shares//abc/files",
                "/api/v1/public/shares/%2E/abc/files/hash-1/chunks",
                "/api/v1/public/shares/decoy/../abc/files/hash-1/decrypt-info",
                "/api/v1/public/shares/encoded-decoy/%2e%2E/abc/files/hash-1/chunks"
        };
        String[] tenantHeaders = {null, "0", "12", "invalid-tenant", ""};

        for (String path : paths) {
            for (String tenantHeader : tenantHeaders) {
                MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
                request.setServletPath(path);
                if (tenantHeader != null) {
                    request.addHeader("X-Tenant-ID", tenantHeader);
                }
                MockHttpServletResponse response = new MockHttpServletResponse();

                filter.doFilterInternal(request, response, filterChain);

                assertEquals(200, response.getStatus());
                assertNull(request.getAttribute(Const.ATTR_TENANT_ID));
                assertNull(TenantContext.getTenantId());
            }
        }

        assertEquals((long) paths.length * tenantHeaders.length, chainCalls.get());
        verify(filterChain, times(paths.length * tenantHeaders.length)).doFilter(any(), any());
    }

    /**
     * 匿名公开分享 GET 对重复租户头也必须完全忽略，不能让代理差异改变公开合同。
     */
    @Test
    @DisplayName("should ignore duplicate tenant headers for anonymous public share GET endpoints")
    void shouldIgnoreDuplicateTenantHeadersForAnonymousPublicShareGetEndpoints()
            throws ServletException, IOException {
        for (String path : new String[]{
                "/api/v1/shares/abc/info",
                "/api/v1/shares/abc/files",
                "/api/v1/public/shares/abc/files/hash-1/chunks",
                "/api/v1/public/shares/abc/files/hash-1/decrypt-info",
                "/api/v1/public/shares/abc/files/hash-1/download-metadata",
                "/api/v1/public/sh%61res/abc/f%69les/hash-1/chunks",
                "/api/v1/public/shares/%2E/abc/files/hash-1/chunks"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            request.setServletPath(path);
            request.addHeader("X-Tenant-ID", "12");
            request.addHeader("X-Tenant-ID", "13");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, filterChain);

            assertEquals(200, response.getStatus());
            assertNull(request.getAttribute(Const.ATTR_TENANT_ID));
            assertNull(TenantContext.getTenantId());
        }

        verify(filterChain, times(7)).doFilter(any(), any());
    }

    /**
     * 公开 grant 消费 POST 必须忽略所有调用者租户头，并且不建立租户权威上下文。
     */
    @Test
    @DisplayName("should ignore every tenant header form for public key grant consume POST")
    void shouldIgnoreEveryTenantHeaderFormForPublicKeyGrantConsumePost()
            throws ServletException, IOException {
        AtomicLong chainCalls = new AtomicLong();
        doAnswer(invocation -> {
            MockHttpServletRequest chainedRequest = invocation.getArgument(0);
            assertNull(TenantContext.getTenantId());
            assertNull(chainedRequest.getAttribute(Const.ATTR_TENANT_ID));
            chainCalls.incrementAndGet();
            return null;
        }).when(filterChain).doFilter(any(), any());

        String[] paths = {
                "/api/v1/public/key-grants/consume",
                "/api/v1/public/key-grants;c=1/consume",
                "/api/v1/public/key-grants/./consume"
        };
        String[] tenantHeaders = {null, "0", "12", "invalid-tenant", ""};
        for (String path : paths) {
            for (String tenantHeader : tenantHeaders) {
                MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
                request.setServletPath(path);
                if (tenantHeader != null) {
                    request.addHeader("X-Tenant-ID", tenantHeader);
                }
                MockHttpServletResponse response = new MockHttpServletResponse();

                filter.doFilterInternal(request, response, filterChain);

                assertEquals(200, response.getStatus());
                assertNull(request.getAttribute(Const.ATTR_TENANT_ID));
                assertNull(TenantContext.getTenantId());
            }
        }

        MockHttpServletRequest duplicateRequest = new MockHttpServletRequest(
                "POST", "/api/v1/public/key-grants/consume");
        duplicateRequest.setServletPath("/api/v1/public/key-grants/consume");
        duplicateRequest.addHeader("X-Tenant-ID", "12");
        duplicateRequest.addHeader("X-Tenant-ID", "13");
        filter.doFilterInternal(duplicateRequest, new MockHttpServletResponse(), filterChain);

        assertEquals(16L, chainCalls.get());
        verify(filterChain, times(16)).doFilter(any(), any());
    }

    /**
     * 非公开资源白名单路径携带畸形租户头时应返回 400，且不得污染请求属性或线程租户上下文。
     */
    @Test
    @DisplayName("should reject invalid tenant header for non-proof whitelisted path")
    void shouldRejectInvalidTenantHeaderForNonProofWhitelistedPath() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.setServletPath("/actuator/health");
        request.addHeader("X-Tenant-ID", "invalid-tenant");
        MockHttpServletResponse response = new MockHttpServletResponse();

        TenantContext.setTenantId(999L);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains(String.valueOf(ResultEnum.PARAM_IS_INVALID.getCode())));
        assertNull(request.getAttribute(Const.ATTR_TENANT_ID));
        assertNull(TenantContext.getTenantId());
    }

    /**
     * 显式空租户头必须按畸形输入拒绝；SSE 也不得用 query 参数覆盖调用方已提供的空头。
     */
    @Test
    @DisplayName("should reject a single empty tenant header outside public proof endpoints")
    void shouldRejectSingleEmptyTenantHeaderOutsidePublicProofEndpoints() throws ServletException, IOException {
        for (String path : new String[]{"/actuator/health", "/api/v1/sse/connect"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            request.setServletPath(path);
            request.addHeader("X-Tenant-ID", "");
            request.addParameter("x-tenant-id", "7");
            MockHttpServletResponse response = new MockHttpServletResponse();

            TenantContext.setTenantId(999L);

            filter.doFilterInternal(request, response, filterChain);

            assertEquals(400, response.getStatus());
            assertTrue(response.getContentAsString()
                    .contains(String.valueOf(ResultEnum.PARAM_IS_INVALID.getCode())));
            assertNull(request.getAttribute(Const.ATTR_TENANT_ID));
            assertNull(TenantContext.getTenantId());
        }

        verifyNoInteractions(filterChain);
    }

    /**
     * 非公开资源白名单和受保护路径都必须拒绝重复租户头，避免有歧义的租户身份被静默采用。
     */
    @Test
    @DisplayName("should reject duplicate tenant headers outside public proof endpoints")
    void shouldRejectDuplicateTenantHeadersOutsidePublicProofEndpoints() throws ServletException, IOException {
        String[] paths = {"/actuator/health", "/api/v1/files"};
        String[][] duplicateValues = {
                {"12", "12"},
                {"12", "13"},
                {"", "12"},
                {"12", ""}
        };

        for (String path : paths) {
            for (String[] values : duplicateValues) {
                MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
                request.setServletPath(path);
                request.addHeader("X-Tenant-ID", values[0]);
                request.addHeader("X-Tenant-ID", values[1]);
                MockHttpServletResponse response = new MockHttpServletResponse();

                TenantContext.setTenantId(999L);

                filter.doFilterInternal(request, response, filterChain);

                assertEquals(400, response.getStatus());
                assertTrue(response.getContentAsString()
                        .contains(String.valueOf(ResultEnum.PARAM_IS_INVALID.getCode())));
                assertNull(request.getAttribute(Const.ATTR_TENANT_ID));
                assertNull(TenantContext.getTenantId());
            }
        }

        verifyNoInteractions(filterChain);
    }

    /**
     * 验证租户头拒绝分支和正常调试日志均不会暴露分享码或文件哈希。
     */
    @Test
    @DisplayName("should mask share credentials in tenant filter logs")
    void shouldMaskShareCredentialsInTenantFilterLogs() throws ServletException, IOException {
        String shareCode = "share-secret-credential";
        String fileHash = "file-hash-secret";
        String path = "/api/v1/shares/" + shareCode + "/files/" + fileHash + "/chunks";

        Logger logger = (Logger) LoggerFactory.getLogger(TenantFilter.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        try {
            MockHttpServletRequest duplicateHeaderRequest = new MockHttpServletRequest("GET", path);
            duplicateHeaderRequest.setServletPath(path);
            duplicateHeaderRequest.addHeader("X-Tenant-ID", "12");
            duplicateHeaderRequest.addHeader("X-Tenant-ID", "13");
            filter.doFilterInternal(duplicateHeaderRequest, new MockHttpServletResponse(), filterChain);

            MockHttpServletRequest emptyHeaderRequest = new MockHttpServletRequest("GET", path);
            emptyHeaderRequest.setServletPath(path);
            emptyHeaderRequest.addHeader("X-Tenant-ID", "");
            filter.doFilterInternal(emptyHeaderRequest, new MockHttpServletResponse(), filterChain);

            MockHttpServletRequest missingHeaderRequest = new MockHttpServletRequest("GET", path);
            missingHeaderRequest.setServletPath(path);
            filter.doFilterInternal(missingHeaderRequest, new MockHttpServletResponse(), filterChain);

            MockHttpServletRequest validHeaderRequest = new MockHttpServletRequest("GET", path);
            validHeaderRequest.setServletPath(path);
            validHeaderRequest.addHeader("X-Tenant-ID", "12");
            filter.doFilterInternal(validHeaderRequest, new MockHttpServletResponse(), filterChain);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }

        List<String> messages = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertTrue(messages.stream().anyMatch(message ->
                message.contains("/api/v1/shares/***/files/***/chunks")));
        assertTrue(messages.stream().noneMatch(message ->
                message.contains(shareCode) || message.contains(fileHash)));
    }

    /**
     * 与白名单相似但缺少路径段边界的请求必须按受保护路径处理。
     */
    @Test
    @DisplayName("should reject paths with whitelisted prefix but no segment boundary")
    void shouldRejectPathsWithWhitelistedPrefixButNoSegmentBoundary() throws ServletException, IOException {
        for (String path : new String[]{
                "/api/v1/public/proofs-admin",
                "/actuator/healthcheck",
                "/swagger-ui-admin",
                "/api/v1/images/download-private",
                "/api/v1/shares/abc/extra/info"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            request.setServletPath(path);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, filterChain);

            assertEquals(400, response.getStatus());
            assertNull(request.getAttribute(Const.ATTR_TENANT_ID));
            assertNull(TenantContext.getTenantId());
        }

        verifyNoInteractions(filterChain);
    }

    /**
     * 非白名单路径缺少租户头时应返回 400，并且不进入后续过滤器链。
     */
    @Test
    @DisplayName("should reject missing tenant header for protected path")
    void shouldRejectMissingTenantHeaderForProtectedPath() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
        request.setServletPath("/api/v1/files");
        MockHttpServletResponse response = new MockHttpServletResponse();

        TenantContext.setTenantId(999L);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains(String.valueOf(ResultEnum.PARAM_IS_INVALID.getCode())));
        assertNull(TenantContext.getTenantId());
    }

    /**
     * /api/v1/shares 下除两个匿名 GET 外的路径必须携带租户头，防止绕过租户校验。
     */
    @Test
    @DisplayName("should reject missing tenant header for protected shares path")
    void shouldRejectMissingTenantHeaderForProtectedSharesPath() throws ServletException, IOException {
        String[][] protectedRequests = {
                {"POST", "/api/v1/shares"},
                {"POST", "/api/v1/shares/abc/info"},
                {"POST", "/api/v1/shares/abc/files"},
                {"POST", "/api/v1/shares/abc/files/save"},
                {"POST", "/api/v1/shares/abc/files/hash-1/chunks"},
                {"POST", "/api/v1/shares/abc/files/hash-1/decrypt-info"},
                {"GET", "/api/v1/shares/abc/files/hash-1/download-metadata"},
                {"POST", "/api/v1/public/shares/abc/files/hash-1/chunks"},
                {"POST", "/api/v1/public/shares/abc/files/hash-1/decrypt-info"},
                {"POST", "/api/v1/public/shares/abc/files/hash-1/download-metadata"},
                {"GET", "/api/v1/public/shares/abc/files/hash-1/metadata"},
                {"GET", "/api/v1/public/key-grants/consume"},
                {"POST", "/api/v1/public/key-grants/consume/extra"}
        };
        for (String[] protectedRequest : protectedRequests) {
            String method = protectedRequest[0];
            String path = protectedRequest[1];
            MockHttpServletRequest request = new MockHttpServletRequest(method, path);
            request.setServletPath(path);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, filterChain);

            assertEquals(400, response.getStatus());
            assertTrue(response.getContentAsString()
                    .contains(String.valueOf(ResultEnum.PARAM_IS_INVALID.getCode())));
        }

        verifyNoInteractions(filterChain);
    }

    /**
     * 租户头不是数字时应返回 400，并且不进入后续过滤器链。
     */
    @Test
    @DisplayName("should reject invalid tenant header value")
    void shouldRejectInvalidTenantHeaderValue() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
        request.setServletPath("/api/v1/files");
        request.addHeader("X-Tenant-ID", "abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        TenantContext.setTenantId(999L);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains(String.valueOf(ResultEnum.PARAM_IS_INVALID.getCode())));
        assertNull(TenantContext.getTenantId());
    }

    /**
     * 正常携带租户头时应写入 TenantContext 与 request attribute，并进入后续过滤器链。
     * 使用 doAnswer 在过滤链执行期间断言 TenantContext（因为 finally 会清理）。
     */
    @Test
    @DisplayName("should set tenant context and request attribute when tenant header present")
    void shouldSetTenantContextAndRequestAttributeWhenTenantHeaderPresent() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
        request.setServletPath("/api/v1/files");
        request.addHeader("X-Tenant-ID", "12");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // 使用 AtomicLong 在过滤链执行期间捕获 TenantContext
        AtomicLong capturedTenantId = new AtomicLong(-1);
        doAnswer(invocation -> {
            capturedTenantId.set(TenantContext.getTenantId() != null ? TenantContext.getTenantId() : -1);
            return null;
        }).when(filterChain).doFilter(any(), any());

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        // 在过滤链执行期间 TenantContext 应已设置
        assertEquals(12L, capturedTenantId.get());
        // 请求属性应被设置
        assertEquals(12L, request.getAttribute(Const.ATTR_TENANT_ID));
        // 过滤器完成后 TenantContext 应被清理（双重保护）
        assertNull(TenantContext.getTenantId());
    }

    /**
     * 下游抛出的 NumberFormatException 必须原样传播，不能被误写成租户头格式错误。
     */
    @Test
    @DisplayName("should propagate number format exception from downstream filter")
    void shouldPropagateNumberFormatExceptionFromDownstreamFilter() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
        request.setServletPath("/api/v1/files");
        request.addHeader("X-Tenant-ID", "12");
        MockHttpServletResponse response = new MockHttpServletResponse();
        NumberFormatException downstreamFailure = new NumberFormatException("downstream failure");
        doThrow(downstreamFailure).when(filterChain).doFilter(request, response);

        NumberFormatException actual = assertThrows(NumberFormatException.class,
                () -> filter.doFilterInternal(request, response, filterChain));

        assertSame(downstreamFailure, actual);
        assertEquals(200, response.getStatus());
        assertEquals("", response.getContentAsString());
        assertEquals(12L, request.getAttribute(Const.ATTR_TENANT_ID));
        assertNull(TenantContext.getTenantId());
    }

    /**
     * SSE 连接端点只能把 query 租户 ID 保存为不可信提示，不能提前建立权威租户上下文。
     */
    @Test
    @DisplayName("should keep SSE query tenant as an untrusted hint")
    void shouldKeepSseQueryTenantAsUntrustedHint() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/sse/connect");
        request.setServletPath("/api/v1/sse/connect");
        request.addParameter("x-tenant-id", "7");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicLong capturedTenantId = new AtomicLong(-1);
        doAnswer(invocation -> {
            capturedTenantId.set(TenantContext.getTenantId() != null ? TenantContext.getTenantId() : -1);
            return null;
        }).when(filterChain).doFilter(any(), any());

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertEquals(-1L, capturedTenantId.get());
        assertNull(request.getAttribute(Const.ATTR_TENANT_ID));
        assertEquals(7L, request.getAttribute(Const.ATTR_SSE_TENANT_HINT));
        assertNull(TenantContext.getTenantId());
    }

    /**
     * SSE 连接头中的租户 ID 也只能作为 namespace 提示，不能建立权威上下文。
     */
    @Test
    @DisplayName("should keep SSE header tenant as an untrusted hint")
    void shouldKeepSseHeaderTenantAsUntrustedHint() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/sse/connect");
        request.setServletPath("/api/v1/sse/connect");
        request.addHeader("X-Tenant-ID", "8");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicLong capturedTenantId = new AtomicLong(-1);
        doAnswer(invocation -> {
            capturedTenantId.set(TenantContext.getTenantId() != null ? TenantContext.getTenantId() : -1);
            return null;
        }).when(filterChain).doFilter(any(), any());

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(-1L, capturedTenantId.get());
        assertNull(request.getAttribute(Const.ATTR_TENANT_ID));
        assertEquals(8L, request.getAttribute(Const.ATTR_SSE_TENANT_HINT));
        assertNull(TenantContext.getTenantId());
    }

    /**
     * 旧 tenantId query 参数继续兼容，但仍只能产生不可信提示。
     */
    @Test
    @DisplayName("should keep legacy SSE tenantId query as an untrusted hint")
    void shouldKeepLegacySseTenantIdAsUntrustedHint() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/sse/connect");
        request.setServletPath("/api/v1/sse/connect");
        request.addParameter("tenantId", "9");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(request.getAttribute(Const.ATTR_TENANT_ID));
        assertEquals(9L, request.getAttribute(Const.ATTR_SSE_TENANT_HINT));
        assertNull(TenantContext.getTenantId());
    }

    /**
     * 只有精确 SSE connect 路径允许 query 租户参数，相似路径仍必须提供租户头。
     */
    @Test
    @DisplayName("should only use query tenant for exact SSE connect path")
    void shouldOnlyUseQueryTenantForExactSseConnectPath() throws ServletException, IOException {
        for (String path : new String[]{
                "/api/v1/sse/connectivity",
                "/api/v1/admin/sse/connect",
                "/api/v1/sse/connect/extra"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            request.setServletPath(path);
            request.addParameter("tenantId", "7");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, filterChain);

            assertEquals(400, response.getStatus());
            assertNull(request.getAttribute(Const.ATTR_TENANT_ID));
            assertNull(TenantContext.getTenantId());
        }

        verifyNoInteractions(filterChain);
    }
}
