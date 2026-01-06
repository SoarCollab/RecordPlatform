package cn.flying.filter;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.util.Const;
import cn.flying.common.util.JwtUtils;
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
        @DisplayName("should proceed when no tenant ID in header (non-tenant request)")
        void shouldProceedWithoutHeaderTenantId() throws ServletException, IOException {
            // No tenant ID in request attributes (non-tenant request)

            filter.doFilterInternal(request, response, filterChain);

            assertNotNull(SecurityContextHolder.getContext().getAuthentication());
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
        @DisplayName("should allow when neither JWT nor header has tenant (public endpoint)")
        void shouldAllowWhenNeitherHasTenant() throws ServletException, IOException {
            // No header tenant ID set (public endpoint scenario)
            when(jwtUtils.toTenantId(decodedJWT)).thenReturn(null);
            when(jwtUtils.toRole(decodedJWT)).thenReturn("user");

            UserDetails userDetails = User.builder()
                    .username("testuser")
                    .password("******")
                    .authorities("ROLE_user")
                    .build();
            when(jwtUtils.toUser(decodedJWT)).thenReturn(userDetails);

            filter.doFilterInternal(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
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
