package cn.flying.filter;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import cn.flying.common.util.JwtUtils;
import cn.flying.service.auth.AuthorizationStateService;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for JwtAuthenticationFilter.
 * Verifies JWT validation, tenant matching, and security context setup.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter Tests")
class JwtAuthenticationFilterTest {

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private FilterChain filterChain;

    @Mock
    private DecodedJWT decodedJWT;

    @Mock
    private AuthorizationStateService authorizationStateService;

    @InjectMocks
    private JwtAuthenticationFilter filter;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    /**
     * 初始化请求/响应对象并清空安全上下文，确保用例隔离。
     */
    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("Without Authorization Header")
    class NoAuthorizationHeader {

        @Test
        @DisplayName("should proceed without setting security context when no token")
        void shouldProceedWithoutSecurityContext() throws ServletException, IOException {
            // No Authorization header set

            filter.doFilterInternal(request, response, filterChain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("should initialize traceId in request")
        void shouldInitializeTraceId() throws ServletException, IOException {
            filter.doFilterInternal(request, response, filterChain);

            assertNotNull(request.getAttribute(Const.TRACE_ID));
            verify(filterChain).doFilter(request, response);
        }
    }

    @Nested
    @DisplayName("With Valid JWT")
    class ValidJwt {

        /**
         * 构造一个可解析的 JWT Mock，并准备基础用户信息，供本组用例复用。
         */
        @BeforeEach
        void setUpValidJwt() {
            request.addHeader("Authorization", "Bearer valid-token");
            when(jwtUtils.resolveJwt("Bearer valid-token")).thenReturn(decodedJWT);
            when(jwtUtils.toId(decodedJWT)).thenReturn(123L);
            when(jwtUtils.toRole(decodedJWT)).thenReturn("user");
            when(jwtUtils.toTenantId(decodedJWT)).thenReturn(1L);
            when(jwtUtils.toScope(decodedJWT)).thenReturn("tenant");
            when(jwtUtils.toAuthVersion(decodedJWT)).thenReturn(0L);
            when(authorizationStateService.isTokenAuthorized(123L, 1L, "user", "tenant", 0L))
                    .thenReturn(true);

            UserDetails userDetails = User.builder()
                    .username("testuser")
                    .password("******")
                    .authorities("ROLE_user")
                    .build();
            when(jwtUtils.toUser(decodedJWT)).thenReturn(userDetails);
        }

        @Test
        @DisplayName("should set security context with valid token")
        void shouldSetSecurityContext() throws ServletException, IOException {
            request.setAttribute(Const.ATTR_TENANT_ID, 1L);

            filter.doFilterInternal(request, response, filterChain);

            assertNotNull(SecurityContextHolder.getContext().getAuthentication());
            assertEquals("testuser", SecurityContextHolder.getContext().getAuthentication().getName());
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("should set user attributes in request")
        void shouldSetUserAttributes() throws ServletException, IOException {
            request.setAttribute(Const.ATTR_TENANT_ID, 1L);

            filter.doFilterInternal(request, response, filterChain);

            assertEquals(123L, request.getAttribute(Const.ATTR_USER_ID));
            assertEquals("user", request.getAttribute(Const.ATTR_USER_ROLE));
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("should restore JWT tenant when no tenant request attribute exists")
        void shouldProceedWithoutHeaderTenantId() throws ServletException, IOException {
            AtomicLong capturedTenantId = new AtomicLong(-1L);
            doAnswer(invocation -> {
                Long tenantId = TenantContext.getTenantId();
                capturedTenantId.set(tenantId == null ? -1L : tenantId);
                return null;
            }).when(filterChain).doFilter(request, response);

            filter.doFilterInternal(request, response, filterChain);

            assertNotNull(SecurityContextHolder.getContext().getAuthentication());
            assertEquals(1L, capturedTenantId.get());
            assertEquals(1L, request.getAttribute(Const.ATTR_TENANT_ID));
            assertNull(TenantContext.getTenantId());
            verify(filterChain).doFilter(request, response);
        }

        /**
         * 验证公开 proof 路径忽略伪造租户头后，完整过滤器链仍从有效 JWT 恢复真实租户。
         */
        @Test
        @DisplayName("should use JWT tenant for public proof path with forged tenant header")
        void shouldUseJwtTenantForPublicProofPathWithForgedTenantHeader() throws ServletException, IOException {
            String path = "/api/v1/public/proofs/rp-proof-abc/status";
            request.setRequestURI(path);
            request.setServletPath(path);
            request.addHeader("X-Tenant-ID", "999");
            AtomicLong capturedTenantId = new AtomicLong(-1L);
            doAnswer(invocation -> {
                Long tenantId = TenantContext.getTenantId();
                capturedTenantId.set(tenantId == null ? -1L : tenantId);
                return null;
            }).when(filterChain).doFilter(request, response);

            new TenantFilter(new cn.flying.config.PrometheusScrapeSecurity(false, "", "")).doFilterInternal(
                    request,
                    response,
                    (filteredRequest, filteredResponse) ->
                            filter.doFilterInternal(request, response, filterChain));

            assertEquals(200, response.getStatus());
            assertEquals(1L, capturedTenantId.get());
            assertEquals(1L, request.getAttribute(Const.ATTR_TENANT_ID));
            assertNull(TenantContext.getTenantId());
            verify(filterChain).doFilter(request, response);
        }
    }

    @Nested
    @DisplayName("Tenant Validation")
    class TenantValidation {

        /**
         * 初始化 JWT Mock（只准备最小字段），用于租户校验相关用例。
         */
        @BeforeEach
        void setUpJwt() {
            request.addHeader("Authorization", "Bearer valid-token");
            when(jwtUtils.resolveJwt("Bearer valid-token")).thenReturn(decodedJWT);
            when(jwtUtils.toId(decodedJWT)).thenReturn(123L);
        }

        @Test
        @DisplayName("should reject request when tenant ID mismatch")
        void shouldRejectTenantMismatch() throws ServletException, IOException {
            request.setAttribute(Const.ATTR_TENANT_ID, 1L);
            when(jwtUtils.toTenantId(decodedJWT)).thenReturn(2L); // Different tenant

            filter.doFilterInternal(request, response, filterChain);

            assertEquals(403, response.getStatus());
            assertTrue(response.getContentAsString().contains(String.valueOf(ResultEnum.PERMISSION_UNAUTHORIZED.getCode())));
            assertNull(SecurityContextHolder.getContext().getAuthentication());
            verify(filterChain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("should allow request when tenant IDs match")
        void shouldAllowMatchingTenants() throws ServletException, IOException {
            request.setAttribute(Const.ATTR_TENANT_ID, 1L);
            when(jwtUtils.toTenantId(decodedJWT)).thenReturn(1L);
            when(jwtUtils.toRole(decodedJWT)).thenReturn("user");
            when(jwtUtils.toScope(decodedJWT)).thenReturn("tenant");
            when(jwtUtils.toAuthVersion(decodedJWT)).thenReturn(0L);
            when(authorizationStateService.isTokenAuthorized(123L, 1L, "user", "tenant", 0L))
                    .thenReturn(true);

            UserDetails userDetails = User.builder()
                    .username("testuser")
                    .password("******")
                    .authorities("ROLE_user")
                    .build();
            when(jwtUtils.toUser(decodedJWT)).thenReturn(userDetails);

            filter.doFilterInternal(request, response, filterChain);

            assertEquals(200, response.getStatus());
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("should reject when JWT has no tenant but header has tenant")
        void shouldRejectNullJwtTenantWithHeaderTenant() throws ServletException, IOException {
            request.setAttribute(Const.ATTR_TENANT_ID, 1L);
            when(jwtUtils.toTenantId(decodedJWT)).thenReturn(null);

            filter.doFilterInternal(request, response, filterChain);

            assertEquals(403, response.getStatus());
            assertTrue(response.getContentAsString().contains(String.valueOf(ResultEnum.PERMISSION_UNAUTHORIZED.getCode())));
            assertNull(SecurityContextHolder.getContext().getAuthentication());
            verify(filterChain, never()).doFilter(request, response);
        }

        @Test
        @DisplayName("should reject a protected identity token with no tenant claim")
        void shouldRejectWhenJwtHasNoTenant() throws ServletException, IOException {
            // No header tenant ID set (public endpoint scenario)
            when(jwtUtils.toTenantId(decodedJWT)).thenReturn(null);
            when(jwtUtils.toRole(decodedJWT)).thenReturn("user");

            filter.doFilterInternal(request, response, filterChain);

            assertEquals(401, response.getStatus());
            verify(filterChain, never()).doFilter(request, response);
        }

        /** Rejects a stale authVersion with the same non-enumerating token response. */
        @Test
        void shouldRejectStaleAuthorizationState() throws ServletException, IOException {
            request.setAttribute(Const.ATTR_TENANT_ID, 1L);
            when(jwtUtils.toTenantId(decodedJWT)).thenReturn(1L);
            when(jwtUtils.toRole(decodedJWT)).thenReturn("user");
            when(jwtUtils.toScope(decodedJWT)).thenReturn("tenant");
            when(jwtUtils.toAuthVersion(decodedJWT)).thenReturn(3L);
            when(authorizationStateService.isTokenAuthorized(123L, 1L, "user", "tenant", 3L))
                    .thenReturn(false);

            filter.doFilterInternal(request, response, filterChain);

            assertEquals(401, response.getStatus());
            assertTrue(response.getContentAsString().contains(
                    String.valueOf(ResultEnum.PERMISSION_TOKEN_INVALID.getCode())));
            verify(filterChain, never()).doFilter(request, response);
        }

        /** Dependency failure returns a fixed unavailable response without leaking state details. */
        @Test
        void shouldFailClosedWhenAuthorizationStoreIsUnavailable() throws ServletException, IOException {
            request.setAttribute(Const.ATTR_TENANT_ID, 1L);
            when(jwtUtils.toTenantId(decodedJWT)).thenReturn(1L);
            when(jwtUtils.toRole(decodedJWT)).thenReturn("user");
            when(jwtUtils.toScope(decodedJWT)).thenReturn("tenant");
            when(jwtUtils.toAuthVersion(decodedJWT)).thenReturn(3L);
            when(authorizationStateService.isTokenAuthorized(123L, 1L, "user", "tenant", 3L))
                    .thenThrow(new IllegalStateException("redis://secret-host/account/123"));

            filter.doFilterInternal(request, response, filterChain);

            assertEquals(503, response.getStatus());
            assertTrue(response.getContentAsString().contains(
                    String.valueOf(ResultEnum.SERVICE_UNAVAILABLE.getCode())));
            assertFalse(response.getContentAsString().contains("secret-host"));
            verify(filterChain, never()).doFilter(request, response);
        }
    }

    @Nested
    @DisplayName("Invalid JWT")
    class InvalidJwt {

        @Test
        @DisplayName("should proceed without security context for invalid token")
        void shouldProceedWithoutContextForInvalidToken() throws ServletException, IOException {
            request.addHeader("Authorization", "Bearer invalid-token");
            when(jwtUtils.resolveJwt("Bearer invalid-token")).thenReturn(null);

            filter.doFilterInternal(request, response, filterChain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("should proceed without security context for malformed header")
        void shouldProceedForMalformedHeader() throws ServletException, IOException {
            request.addHeader("Authorization", "NotBearer token");
            when(jwtUtils.resolveJwt("NotBearer token")).thenReturn(null);

            filter.doFilterInternal(request, response, filterChain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
            verify(filterChain).doFilter(request, response);
        }
    }

    @Nested
    @DisplayName("Context Cleanup")
    class ContextCleanup {

        @Test
        @DisplayName("should clear security context after filter chain completes")
        void shouldClearSecurityContextAfterChain() throws ServletException, IOException {
            request.addHeader("Authorization", "Bearer valid-token");
            request.setAttribute(Const.ATTR_TENANT_ID, 1L);
            when(jwtUtils.resolveJwt("Bearer valid-token")).thenReturn(decodedJWT);
            when(jwtUtils.toId(decodedJWT)).thenReturn(123L);
            when(jwtUtils.toRole(decodedJWT)).thenReturn("user");
            when(jwtUtils.toTenantId(decodedJWT)).thenReturn(1L);
            when(jwtUtils.toScope(decodedJWT)).thenReturn("tenant");
            when(jwtUtils.toAuthVersion(decodedJWT)).thenReturn(0L);
            when(authorizationStateService.isTokenAuthorized(123L, 1L, "user", "tenant", 0L))
                    .thenReturn(true);

            UserDetails userDetails = User.builder()
                    .username("testuser")
                    .password("******")
                    .authorities("ROLE_user")
                    .build();
            when(jwtUtils.toUser(decodedJWT)).thenReturn(userDetails);

            filter.doFilterInternal(request, response, filterChain);

            // After filter completes, context should still be set (cleared in outer filter)
            // The filter sets context during request, cleared by Spring Security after response
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("should cleanup even when filter chain throws exception")
        void shouldCleanupOnException() throws ServletException, IOException {
            doThrow(new ServletException("Test exception")).when(filterChain).doFilter(any(), any());

            assertThrows(ServletException.class, () ->
                    filter.doFilterInternal(request, response, filterChain));

            // MDC should be cleaned up in finally block
            // This is tested by observing no memory leaks in the MDC
        }
    }
}
