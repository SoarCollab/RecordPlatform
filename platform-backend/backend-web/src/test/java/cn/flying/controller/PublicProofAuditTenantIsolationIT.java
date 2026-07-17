package cn.flying.controller;

import cn.flying.test.support.BaseControllerIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 公共 proof 匿名请求的操作日志租户与可信客户端 IP 集成测试。
 */
@Transactional
@TestPropertySource(properties = "spring.web.rate-limit.client-ip.trusted-proxies=10.0.0.0/8")
@DisplayName("Public proof audit tenant and trusted IP integration tests")
class PublicProofAuditTenantIsolationIT extends BaseControllerIntegrationTest {

    private static final long FORGED_TENANT_ID = 9_876_543_210L;
    private static final String UNTRUSTED_PEER = "198.51.100.24";
    private static final String TRUSTED_PEER = "10.0.0.20";
    private static final String TRUSTED_CLIENT = "203.0.113.7";
    private static final String PUBLIC_PROOF_RATE_KEY_PREFIX =
            "rate:limit:public:proof-verification:v2:ip:";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 验证两个公开端点在可信、不可信和恶意代理头场景下共享规范化限流与审计主体。
     */
    @Test
    @DisplayName("should keep public proof rate-limit and audit identities canonical")
    void shouldKeepPublicProofRateLimitAndAuditIdentitiesCanonical() throws Exception {
        assertUntrustedRequestsUseDirectPeer();
        assertTrustedRequestsUseCanonicalClient();
        assertMaliciousHeadersFallbackToDirectPeer();
    }

    /**
     * 验证伪造租户与转发头不能污染两个公开端点的限流或数据库审计主体。
     */
    private void assertUntrustedRequestsUseDirectPeer() throws Exception {
        for (String requestPath : publicProofPaths("untrusted")) {
            MockHttpServletRequestBuilder request = get(requestPath)
                    .with(remoteAddress(UNTRUSTED_PEER))
                    .header(HEADER_TENANT_ID, FORGED_TENANT_ID)
                    .header("X-Forwarded-For", "203.0.113.91")
                    .header("X-Real-IP", "203.0.113.92")
                    .header("Proxy-Client-IP", "203.0.113.93")
                    .header("WL-Proxy-Client-IP", "203.0.113.94");

            performAndAssertCanonicalAudit(requestPath, request, UNTRUSTED_PEER);
        }
    }

    /**
     * 验证可信代理的合法多跳 XFF 在两个公开端点上同时形成相同限流键和审计 IP。
     */
    private void assertTrustedRequestsUseCanonicalClient() throws Exception {
        for (String requestPath : publicProofPaths("trusted")) {
            MockHttpServletRequestBuilder request = get(requestPath)
                    .with(remoteAddress(TRUSTED_PEER))
                    .header("X-Forwarded-For", TRUSTED_CLIENT + ", 10.1.0.8, 10.2.0.9");

            performAndAssertCanonicalAudit(requestPath, request, TRUSTED_CLIENT);
        }
    }

    /**
     * 验证重复、超长、超多跳、非数字及旧代理头不会污染或丢失任一公开端点的审计行。
     */
    private void assertMaliciousHeadersFallbackToDirectPeer() throws Exception {
        for (MaliciousHeaderCase headerCase : maliciousHeaderCases()) {
            for (String requestPath : publicProofPaths(headerCase.name())) {
                MockHttpServletRequestBuilder request = get(requestPath)
                        .with(remoteAddress(TRUSTED_PEER))
                        .header(HEADER_TENANT_ID, FORGED_TENANT_ID);
                headerCase.configure().accept(request);

                performAndAssertCanonicalAudit(requestPath, request, TRUSTED_PEER);
            }
        }
    }

    /**
     * 执行公共请求并同时断言限流键、唯一审计行、系统租户和数据库字段长度边界。
     */
    private void performAndAssertCanonicalAudit(
            String requestPath,
            MockHttpServletRequestBuilder request,
            String expectedClientIp) throws Exception {
        String rateLimitKey = PUBLIC_PROOF_RATE_KEY_PREFIX + expectedClientIp;
        redisTemplate.delete(rateLimitKey);

        mockMvc.perform(request).andReturn();

        assertThat(redisTemplate.opsForValue().get(rateLimitKey)).isEqualTo("1");
        List<AuditRow> rows = jdbcTemplate.query(
                "SELECT tenant_id, request_ip FROM sys_operation_log WHERE request_url = ?",
                (resultSet, rowNumber) -> new AuditRow(
                        resultSet.getLong("tenant_id"),
                        resultSet.getString("request_ip")),
                requestPath);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.tenantId()).isZero();
            assertThat(row.requestIp()).isEqualTo(expectedClientIp);
            assertThat(row.requestIp()).hasSizeLessThanOrEqualTo(50);
        });
    }

    /**
     * 生成状态与历史公钥两个公开端点的唯一请求路径。
     */
    private List<String> publicProofPaths(String scenario) {
        String uniqueSuffix = scenario + "-" + UUID.randomUUID().toString().replace("-", "");
        return List.of(
                "/api/v1/public/proofs/rp-audit-" + uniqueSuffix + "/status",
                "/api/v1/public/proof-keys/audit-" + uniqueSuffix + "/versions/1");
    }

    /**
     * 构造覆盖 resolver 拒绝边界和已删除旧代理头回退的恶意输入矩阵。
     */
    private List<MaliciousHeaderCase> maliciousHeaderCases() {
        String tooManyHops = IntStream.rangeClosed(1, 17)
                .mapToObj(index -> "10.0.0." + index)
                .collect(java.util.stream.Collectors.joining(", "));
        return List.of(
                new MaliciousHeaderCase(
                        "duplicate",
                        request -> request.header(
                                "X-Forwarded-For",
                                "203.0.113.11",
                                "203.0.113.12")),
                new MaliciousHeaderCase(
                        "overlong",
                        request -> request.header("X-Forwarded-For", "1".repeat(1025))),
                new MaliciousHeaderCase(
                        "too-many-hops",
                        request -> request.header("X-Forwarded-For", tooManyHops)),
                new MaliciousHeaderCase(
                        "non-numeric",
                        request -> request
                                .header("X-Forwarded-For", "attacker.example")
                                .header("X-Real-IP", "203.0.113.13")),
                new MaliciousHeaderCase(
                        "legacy-headers",
                        request -> request
                                .header("Proxy-Client-IP", "203.0.113.14")
                                .header("WL-Proxy-Client-IP", "203.0.113.15")));
    }

    /**
     * 设置 MockMvc 请求的直接 socket 对端地址。
     */
    private RequestPostProcessor remoteAddress(String peer) {
        return request -> {
            request.setRemoteAddr(peer);
            return request;
        };
    }

    private record MaliciousHeaderCase(
            String name,
            Consumer<MockHttpServletRequestBuilder> configure) {
    }

    private record AuditRow(long tenantId, String requestIp) {
    }
}
