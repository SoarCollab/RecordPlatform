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

    /**
     * 非白名单路径缺少租户头时应返回 400，并且不进入后续过滤器链。
     */
    @Test
    @DisplayName("should reject missing tenant header for protected path")
    void shouldRejectMissingTenantHeaderForProtectedPath() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files/list");
        request.setServletPath("/api/v1/files/list");
        MockHttpServletResponse response = new MockHttpServletResponse();

        TenantContext.setTenantId(999L);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains(String.valueOf(ResultEnum.PARAM_IS_INVALID.getCode())));
        assertNull(TenantContext.getTenantId());
    }

    /**
     * 租户头不是数字时应返回 400，并且不进入后续过滤器链。
     */
    @Test
    @DisplayName("should reject invalid tenant header value")
    void shouldRejectInvalidTenantHeaderValue() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files/list");
        request.setServletPath("/api/v1/files/list");
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
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files/list");
        request.setServletPath("/api/v1/files/list");
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
     * SSE 连接端点允许从 query 参数读取租户 ID（EventSource 不支持自定义 Header）。
     */
    @Test
    @DisplayName("should read tenant id from query params for SSE connect")
    void shouldReadTenantIdFromQueryParamsForSseConnect() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/sse/connect");
        request.setServletPath("/api/v1/sse/connect");
        request.addParameter("x-tenant-id", "7");
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
        assertEquals(7L, capturedTenantId.get());
        // 请求属性应被设置
        assertEquals(7L, request.getAttribute(Const.ATTR_TENANT_ID));
        // 过滤器完成后 TenantContext 应被清理
        assertNull(TenantContext.getTenantId());
    }
}

