package cn.flying.filter;

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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

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

    private final TenantFilter filter = new TenantFilter();

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
     * 白名单路径不强制要求租户头，但若主动携带租户头，应写入 TenantContext 与 request attribute，
     * 以便后续 MyBatis-Plus 租户拦截器能正确注入 tenant_id 条件。
     */
    @Test
    @DisplayName("should set tenant context for whitelisted path when tenant header present")
    void shouldSetTenantContextForWhitelistedPathWhenTenantHeaderPresent() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/shares/abc/info");
        request.setServletPath("/api/v1/shares/abc/info");
        request.addHeader("X-Tenant-ID", "12");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicLong capturedTenantId = new AtomicLong(-1);
        doAnswer(invocation -> {
            capturedTenantId.set(TenantContext.getTenantId() != null ? TenantContext.getTenantId() : -1);
            return null;
        }).when(filterChain).doFilter(any(), any());

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertEquals(12L, capturedTenantId.get());
        assertEquals(12L, request.getAttribute(Const.ATTR_TENANT_ID));
        assertNull(TenantContext.getTenantId());
    }

    /**
     * 非 proof 白名单路径携带畸形租户头时应返回 400，且不得污染请求属性或线程租户上下文。
     */
    @Test
    @DisplayName("should reject invalid tenant header for non-proof whitelisted path")
    void shouldRejectInvalidTenantHeaderForNonProofWhitelistedPath() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/shares/abc/info");
        request.setServletPath("/api/v1/shares/abc/info");
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
        for (String path : new String[]{"/api/v1/shares/abc/info", "/api/v1/sse/connect"}) {
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
     * 非 proof 白名单和受保护路径都必须拒绝重复租户头，避免有歧义的租户身份被静默采用。
     */
    @Test
    @DisplayName("should reject duplicate tenant headers outside public proof endpoints")
    void shouldRejectDuplicateTenantHeadersOutsidePublicProofEndpoints() throws ServletException, IOException {
        String[] paths = {"/api/v1/shares/abc/info", "/api/v1/files"};
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
     * /api/v1/shares 下除公开文件列表外的路径必须携带租户头，防止绕过租户校验。
     */
    @Test
    @DisplayName("should reject missing tenant header for protected shares path")
    void shouldRejectMissingTenantHeaderForProtectedSharesPath() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/shares");
        request.setServletPath("/api/v1/shares");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains(String.valueOf(ResultEnum.PARAM_IS_INVALID.getCode())));
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
