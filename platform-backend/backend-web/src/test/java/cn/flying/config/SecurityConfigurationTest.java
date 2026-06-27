package cn.flying.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

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

    /**
     * 验证 Actuator health 不对外展示组件详情，避免跨租户健康计数泄露。
     */
    @Test
    void shouldDisableActuatorHealthDetails() throws Exception {
        String applicationYaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertTrue(applicationYaml.contains("show-details: never"));
        assertFalse(applicationYaml.contains("show-details: when-authorized"));
    }

    /**
     * 验证非 health 的 Actuator 管理端点仍要求管理员或监控员角色。
     */
    @Test
    void shouldRestrictActuatorInternalsToOperatorRoles() throws Exception {
        String securityConfiguration = Files.readString(Path.of("src/main/java/cn/flying/config/SecurityConfiguration.java"));

        assertTrue(securityConfiguration.contains(".requestMatchers(\"/actuator/**\").hasAnyRole("));
        assertTrue(securityConfiguration.contains("UserRole.ROLE_ADMINISTER.getRole()"));
        assertTrue(securityConfiguration.contains("UserRole.ROLE_MONITOR.getRole()"));
    }

    /**
     * 验证 Swagger/Knife4j 文档路由不再匿名公开。
     */
    @Test
    void shouldRestrictApiDocsToOperatorRoles() throws Exception {
        String securityConfiguration = Files.readString(Path.of("src/main/java/cn/flying/config/SecurityConfiguration.java"));

        assertFalse(securityConfiguration.contains(
                ".requestMatchers(\"/swagger-ui.html\", \"/swagger-ui/**\", \"/v3/api-docs/**\", \"/doc.html/**\",\"/webjars/**\",\"/favicon.ico\").permitAll()"
        ));
        assertTrue(securityConfiguration.contains("\"/doc.html\", \"/doc.html/**\", \"/webjars/**\").hasAnyRole("));
        assertTrue(securityConfiguration.contains("UserRole.ROLE_ADMINISTER.getRole()"));
        assertTrue(securityConfiguration.contains("UserRole.ROLE_MONITOR.getRole()"));
    }

    /**
     * 验证 Knife4j 默认使用生产模式，避免默认暴露接口文档。
     */
    @Test
    void shouldDefaultKnife4jToProductionMode() throws Exception {
        String applicationYaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertTrue(applicationYaml.contains("production: ${KNIFE4J_PRODUCTION:true}"));
        assertTrue(applicationYaml.contains("enable: ${KNIFE4J_BASIC_ENABLE:true}"));
        assertFalse(applicationYaml.contains("production: false"));
    }
}
