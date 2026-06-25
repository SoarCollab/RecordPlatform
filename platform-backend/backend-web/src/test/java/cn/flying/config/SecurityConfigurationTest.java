package cn.flying.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SecurityConfiguration 单元测试。
 */
class SecurityConfigurationTest {

    /**
     * 验证生产 context-path 下的登录请求仍会被识别为登录接口。
     */
    @Test
    void shouldRecognizeLoginRequestBehindContextPath() {
        SecurityConfiguration configuration = new SecurityConfiguration();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/record-platform/api/v1/auth/login");
        request.setContextPath("/record-platform");
        request.setServletPath("/api/v1/auth/login");

        Boolean result = ReflectionTestUtils.invokeMethod(configuration, "isLoginRequest", request);

        assertTrue(Boolean.TRUE.equals(result));
    }

    /**
     * 验证非登录接口不会误触发登录失败计数逻辑。
     */
    @Test
    void shouldRejectNonLoginRequestBehindContextPath() {
        SecurityConfiguration configuration = new SecurityConfiguration();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/record-platform/api/v1/files/quota");
        request.setContextPath("/record-platform");
        request.setServletPath("/api/v1/files/quota");

        Boolean result = ReflectionTestUtils.invokeMethod(configuration, "isLoginRequest", request);

        assertFalse(Boolean.TRUE.equals(result));
    }
}
