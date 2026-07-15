package cn.flying.controller;

import cn.flying.test.support.BaseControllerIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 公共 proof 匿名请求的操作日志租户隔离集成测试。
 */
@Transactional
@DisplayName("Public proof audit tenant isolation integration tests")
class PublicProofAuditTenantIsolationIT extends BaseControllerIntegrationTest {

    private static final long FORGED_TENANT_ID = 9_876_543_210L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 验证伪造租户头经过完整过滤器、控制器 AOP 和 MyBatis 落库链路后不会污染目标租户日志。
     */
    @Test
    @DisplayName("should not attribute anonymous public proof audit logs to forged tenant")
    void shouldNotAttributeAnonymousPublicProofAuditLogsToForgedTenant() throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().replace("-", "");
        List<String> requestPaths = List.of(
                "/api/v1/public/proofs/rp-audit-" + uniqueSuffix + "/status",
                "/api/v1/public/proof-keys/audit-" + uniqueSuffix + "/versions/1");

        for (String requestPath : requestPaths) {
            mockMvc.perform(get(requestPath)
                            .header(HEADER_TENANT_ID, FORGED_TENANT_ID))
                    .andReturn();

            Integer totalLogs = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_operation_log WHERE request_url = ?",
                    Integer.class,
                    requestPath);
            Integer forgedTenantLogs = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_operation_log WHERE tenant_id = ? AND request_url = ?",
                    Integer.class,
                    FORGED_TENANT_ID,
                    requestPath);
            Integer systemTenantLogs = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_operation_log WHERE tenant_id = 0 AND request_url = ?",
                    Integer.class,
                    requestPath);

            assertThat(totalLogs).isEqualTo(1);
            assertThat(forgedTenantLogs).isZero();
            assertThat(systemTenantLogs).isEqualTo(1);
        }
    }
}
