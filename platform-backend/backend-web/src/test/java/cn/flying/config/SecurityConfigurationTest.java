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
     * 验证只公开 proof 的 GET 状态/公钥路径，导出和撤销仍落入认证规则。
     */
    @Test
    void shouldPermitOnlyPublicProofReadEndpoints() throws Exception {
        String securityConfiguration = Files.readString(Path.of("src/main/java/cn/flying/config/SecurityConfiguration.java"));

        assertTrue(securityConfiguration.contains(
                ".requestMatchers(HttpMethod.GET, \"/api/v1/public/proofs/*/status\").permitAll()"));
        assertTrue(securityConfiguration.contains(
                ".requestMatchers(HttpMethod.GET, \"/api/v1/public/proof-keys/*/versions/*\").permitAll()"));
        assertFalse(securityConfiguration.contains(
                ".requestMatchers(\"/api/v1/files/**\").permitAll()"));
    }

    /**
     * 验证公开分享只放行精确读取合同和公开 grant 消费 POST，近似前缀仍需认证。
     */
    @Test
    void shouldPermitOnlyExactPublicShareReadEndpoints() throws Exception {
        String securityConfiguration = Files.readString(Path.of("src/main/java/cn/flying/config/SecurityConfiguration.java"));

        assertTrue(securityConfiguration.contains(
                ".requestMatchers(HttpMethod.GET, \"/api/v1/shares/*/info\").permitAll()"));
        assertTrue(securityConfiguration.contains(
                ".requestMatchers(HttpMethod.GET, \"/api/v1/shares/*/files\").permitAll()"));
        assertTrue(securityConfiguration.contains(
                "\"/api/v1/public/shares/*/files/*/chunks\","));
        assertTrue(securityConfiguration.contains(
                "\"/api/v1/public/shares/*/files/*/decrypt-info\","));
        assertTrue(securityConfiguration.contains(
                "\"/api/v1/public/shares/*/files/*/download-metadata\").permitAll()"));
        assertTrue(securityConfiguration.contains(
                ".requestMatchers(HttpMethod.POST, \"/api/v1/public/key-grants/consume\").permitAll()"));
        assertFalse(securityConfiguration.contains(
                ".requestMatchers(HttpMethod.GET, \"/api/v1/public/shares/**\").permitAll()"));
        assertFalse(securityConfiguration.contains(
                ".requestMatchers(\"/api/v1/public/shares/**\").permitAll()"));
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

    /**
     * 验证容器不预处理转发头，并由公共限流解析器独占可信代理配置。
     */
    @Test
    void shouldDisableContainerForwardedHeaderRewriting() throws Exception {
        String applicationYaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertTrue(applicationYaml.contains("forward-headers-strategy: none"));
        assertTrue(applicationYaml.contains("trusted-proxies: ${RATE_LIMIT_TRUSTED_PROXY_CIDRS:}"));
        assertFalse(applicationYaml.contains("forward-headers-strategy: native"));
        assertFalse(applicationYaml.contains("forward-headers-strategy: framework"));
    }
}
