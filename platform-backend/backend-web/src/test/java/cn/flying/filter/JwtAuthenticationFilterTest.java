package cn.flying.filter;

import cn.flying.common.util.Const;
import cn.flying.common.util.JwtUtils;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * JwtAuthenticationFilter 测试类
 * 测试JWT认证过滤器的核心功能
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @Mock
    private DecodedJWT decodedJWT;

    @InjectMocks
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        // 清理SecurityContext和MDC
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    @DisplayName("测试有效JWT令牌的认证")
    void testDoFilterInternal_validToken() throws Exception {
        // Given
        String token = "Bearer valid.jwt.token";
        Long userId = 123456L;
        String userRole = "admin";
        UserDetails userDetails = User.builder()
                .username("testuser")
                .password("password")
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                .build();

        when(request.getHeader("Authorization")).thenReturn(token);
        when(jwtUtils.resolveJwt(token)).thenReturn(decodedJWT);
        when(jwtUtils.toUser(decodedJWT)).thenReturn(userDetails);
        when(jwtUtils.toId(decodedJWT)).thenReturn(userId);
        when(jwtUtils.toRole(decodedJWT)).thenReturn(userRole);

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain, times(1)).doFilter(request, response);
        verify(request, times(1)).setAttribute(Const.ATTR_USER_ID, userId);
        verify(request, times(1)).setAttribute(Const.ATTR_USER_ROLE, userRole);

        // 验证SecurityContext已设置
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication, "认证对象不应为null");
        assertEquals(userDetails, authentication.getPrincipal(), "用户信息应该设置正确");

        // 验证MDC已清理
        assertNull(MDC.get("userId"), "MDC应该在请求结束后清理");
        assertNull(MDC.get("userRole"), "MDC应该在请求结束后清理");
    }

    @Test
    @DisplayName("测试无效JWT令牌")
    void testDoFilterInternal_invalidToken() throws Exception {
        // Given
        String token = "Bearer invalid.jwt.token";
        when(request.getHeader("Authorization")).thenReturn(token);
        when(jwtUtils.resolveJwt(token)).thenReturn(null);

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain, times(1)).doFilter(request, response);
        verify(request, never()).setAttribute(eq(Const.ATTR_USER_ID), any());
        verify(request, never()).setAttribute(eq(Const.ATTR_USER_ROLE), any());

        // 验证SecurityContext未设置
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNull(authentication, "无效令牌不应设置认证对象");
    }

    @Test
    @DisplayName("测试缺少Authorization头")
    void testDoFilterInternal_missingAuthorizationHeader() throws Exception {
        // Given
        when(request.getHeader("Authorization")).thenReturn(null);

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain, times(1)).doFilter(request, response);
        verify(jwtUtils, never()).resolveJwt(anyString());
        verify(request, never()).setAttribute(eq(Const.ATTR_USER_ID), any());

        // 验证SecurityContext未设置
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNull(authentication, "缺少令牌不应设置认证对象");
    }

    @Test
    @DisplayName("测试空Authorization头")
    void testDoFilterInternal_emptyAuthorizationHeader() throws Exception {
        // Given
        when(request.getHeader("Authorization")).thenReturn("");
        when(jwtUtils.resolveJwt("")).thenReturn(null);

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain, times(1)).doFilter(request, response);
        verify(request, never()).setAttribute(eq(Const.ATTR_USER_ID), any());
    }

    @Test
    @DisplayName("测试JWT解析成功但用户ID为null")
    void testDoFilterInternal_nullUserId() throws Exception {
        // Given
        String token = "Bearer valid.jwt.token";
        UserDetails userDetails = User.builder()
                .username("testuser")
                .password("password")
                .authorities(Collections.emptyList())
                .build();

        when(request.getHeader("Authorization")).thenReturn(token);
        when(jwtUtils.resolveJwt(token)).thenReturn(decodedJWT);
        when(jwtUtils.toUser(decodedJWT)).thenReturn(userDetails);
        when(jwtUtils.toId(decodedJWT)).thenReturn(null);
        when(jwtUtils.toRole(decodedJWT)).thenReturn(null);

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain, times(1)).doFilter(request, response);
        verify(request, times(1)).setAttribute(Const.ATTR_USER_ID, null);
        verify(request, times(1)).setAttribute(Const.ATTR_USER_ROLE, null);
    }

    @Test
    @DisplayName("测试MDC上下文清理")
    void testDoFilterInternal_mdcCleanup() throws Exception {
        // Given
        String token = "Bearer valid.jwt.token";
        Long userId = 123456L;
        String userRole = "user";
        UserDetails userDetails = User.builder()
                .username("testuser")
                .password("password")
                .authorities(Collections.emptyList())
                .build();

        when(request.getHeader("Authorization")).thenReturn(token);
        when(jwtUtils.resolveJwt(token)).thenReturn(decodedJWT);
        when(jwtUtils.toUser(decodedJWT)).thenReturn(userDetails);
        when(jwtUtils.toId(decodedJWT)).thenReturn(userId);
        when(jwtUtils.toRole(decodedJWT)).thenReturn(userRole);

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then - MDC应该在finally块中被清理
        assertNull(MDC.get("userId"), "MDC userId应该被清理");
        assertNull(MDC.get("userRole"), "MDC userRole应该被清理");
    }

    @Test
    @DisplayName("测试FilterChain抛出异常时的MDC清理")
    void testDoFilterInternal_mdcCleanupOnException() throws Exception {
        // Given
        String token = "Bearer valid.jwt.token";
        Long userId = 123456L;
        String userRole = "admin";
        UserDetails userDetails = User.builder()
                .username("testuser")
                .password("password")
                .authorities(Collections.emptyList())
                .build();

        when(request.getHeader("Authorization")).thenReturn(token);
        when(jwtUtils.resolveJwt(token)).thenReturn(decodedJWT);
        when(jwtUtils.toUser(decodedJWT)).thenReturn(userDetails);
        when(jwtUtils.toId(decodedJWT)).thenReturn(userId);
        when(jwtUtils.toRole(decodedJWT)).thenReturn(userRole);

        // FilterChain抛出异常
        doThrow(new RuntimeException("Test exception")).when(filterChain).doFilter(request, response);

        // When & Then
        assertThrows(RuntimeException.class, () ->
                filter.doFilterInternal(request, response, filterChain));

        // MDC应该在异常情况下也被清理
        assertNull(MDC.get("userId"), "异常时MDC userId应该被清理");
        assertNull(MDC.get("userRole"), "异常时MDC userRole应该被清理");
    }

    @Test
    @DisplayName("测试JWT令牌格式不正确")
    void testDoFilterInternal_malformedToken() throws Exception {
        // Given
        String token = "InvalidTokenFormat";
        when(request.getHeader("Authorization")).thenReturn(token);
        when(jwtUtils.resolveJwt(token)).thenReturn(null);

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain, times(1)).doFilter(request, response);
        verify(request, never()).setAttribute(eq(Const.ATTR_USER_ID), any());
    }

    @Test
    @DisplayName("测试不同的用户角色")
    void testDoFilterInternal_differentRoles() throws Exception {
        // Given
        String token = "Bearer valid.jwt.token";
        Long userId = 999L;
        String userRole = "monitor";
        UserDetails userDetails = User.builder()
                .username("monitor_user")
                .password("password")
                .authorities(new SimpleGrantedAuthority("ROLE_MONITOR"))
                .build();

        when(request.getHeader("Authorization")).thenReturn(token);
        when(jwtUtils.resolveJwt(token)).thenReturn(decodedJWT);
        when(jwtUtils.toUser(decodedJWT)).thenReturn(userDetails);
        when(jwtUtils.toId(decodedJWT)).thenReturn(userId);
        when(jwtUtils.toRole(decodedJWT)).thenReturn(userRole);

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        verify(request, times(1)).setAttribute(Const.ATTR_USER_ID, userId);
        verify(request, times(1)).setAttribute(Const.ATTR_USER_ROLE, userRole);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertTrue(authentication.getAuthorities().stream()
                .anyMatch(auth -> auth.getAuthority().equals("ROLE_MONITOR")));
    }
}
