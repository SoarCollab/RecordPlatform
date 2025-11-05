package cn.flying.identity.filter;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.config.AuthWhitelistProperties;
import cn.flying.identity.service.JwtBlacklistService;
import cn.flying.platformapi.constant.ResultEnum;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * SaTokenGatewayFilter 单元测试
 * 验证白名单、黑名单、角色权限与默认登录校验分支
 */
class SaTokenGatewayFilterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void doFilter_shouldBypassWhitelist() throws Exception {
        SaTokenGatewayFilter filter = buildFilter(props -> props.setPublicApiPatterns(List.of("/public/**")), null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/public/info");
        MockHttpServletResponse response = new MockHttpServletResponse();
        CountingFilterChain chain = new CountingFilterChain();

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            filter.doFilter(request, response, chain);
            Assertions.assertEquals(1, chain.getCount(), "白名单请求应透传过滤器链");
            stp.verifyNoInteractions();
        }
    }

    @Test
    void doFilter_shouldRejectBlacklistedToken() throws Exception {
        JwtBlacklistService blacklistService = mock(JwtBlacklistService.class);
        SaTokenGatewayFilter filter = buildFilter(null, blacklistService);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/user/profile");
        request.addHeader("Authorization", "Bearer banned-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(blacklistService.isBlacklisted("banned-token")).thenReturn(true);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            filter.doFilter(request, response, new CountingFilterChain());
        }

        Map<String, Object> body = parseBody(response);
        Assertions.assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        Assertions.assertEquals(ResultEnum.USER_NOT_LOGGED_IN.getCode(),
                ((Number) body.get("code")).intValue(), "黑名单命中应返回未登录错误码");
    }

    @Test
    void doFilter_adminEndpointWithoutRoleShouldReturnForbidden() throws Exception {
        JwtBlacklistService blacklistService = mock(JwtBlacklistService.class);
        when(blacklistService.isBlacklisted("admin-token")).thenReturn(false);
        SaTokenGatewayFilter filter = buildFilter(null, blacklistService);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");
        request.addHeader("Authorization", "Bearer admin-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(() -> StpUtil.checkRole("admin")).thenThrow(new NotRoleException("admin", null));
            filter.doFilter(request, response, new CountingFilterChain());
        }

        Map<String, Object> body = parseBody(response);
        Assertions.assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
        Assertions.assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(),
                ((Number) body.get("code")).intValue(), "管理员角色不足应返回权限错误码");
    }

    @Test
    void doFilter_tokenMonitorMissingPermissionShouldReturnForbidden() throws Exception {
        SaTokenGatewayFilter filter = buildFilter(null, null);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/token/monitor/record");
        request.addHeader("Authorization", "Bearer monitor-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(() -> StpUtil.checkRoleOr("admin", "monitor")).thenAnswer(invocation -> null);
            stp.when(() -> StpUtil.checkPermission("token:monitor:record"))
                    .thenThrow(new NotPermissionException("token:monitor:record", null));
            filter.doFilter(request, response, new CountingFilterChain());
        }

        Map<String, Object> body = parseBody(response);
        Assertions.assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
        Assertions.assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(),
                ((Number) body.get("code")).intValue(), "权限缺失应返回权限错误码");
    }

    @Test
    void doFilter_missingLoginTokenShouldReturnUnauthorized() throws Exception {
        SaTokenGatewayFilter filter = buildFilter(null, null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/user/settings");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::checkLogin).thenThrow(new NotLoginException("no login", null, null));
            filter.doFilter(request, response, new CountingFilterChain());
        }

        Map<String, Object> body = parseBody(response);
        Assertions.assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        Assertions.assertEquals(ResultEnum.USER_NOT_LOGGED_IN.getCode(),
                ((Number) body.get("code")).intValue(), "未登录请求应返回401");
    }

    private SaTokenGatewayFilter buildFilter(Consumer<AuthWhitelistProperties> customizer,
                                             JwtBlacklistService blacklistService) throws ServletException, IOException {
        AuthWhitelistProperties properties = new AuthWhitelistProperties();
        if (customizer != null) {
            customizer.accept(properties);
        }
        SaTokenGatewayFilter filter = new SaTokenGatewayFilter(properties);
        if (blacklistService != null) {
            ReflectionTestUtils.setField(filter, "jwtBlacklistService", blacklistService);
        }
        ReflectionTestUtils.setField(filter, "PREFIX", "");
        filter.init(null);
        return filter;
    }

    private Map<String, Object> parseBody(MockHttpServletResponse response) throws IOException {
        byte[] content = response.getContentAsByteArray();
        if (content == null || content.length == 0) {
            return Map.of();
        }
        return OBJECT_MAPPER.readValue(content,
                new TypeReference<Map<String, Object>>() {
                });
    }
    private static final class CountingFilterChain implements FilterChain {

        private int count;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) throws IOException {
            count++;
        }

        int getCount() {
            return count;
        }
    }
}
