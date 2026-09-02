package cn.flying.service;

import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.vo.audit.HighFrequencyOperationVO;
import cn.flying.test.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the tenant, window, and threshold contract for audit high-frequency alerts.
 */
@Transactional
@Testcontainers(disabledWithoutDocker = false)
@DisplayName("Audit high-frequency tenant isolation integration tests")
class AuditHighFrequencyTenantIsolationIT extends BaseIntegrationTest {

    private static final long CURRENT_TENANT = 919001L;
    private static final long OTHER_TENANT = 919002L;

    @Autowired
    private SysAuditService sysAuditService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Proves list, overview count, and anomaly detection share one tenant-scoped threshold contract.
     */
    @Test
    @DisplayName("should count only current-tenant groups that cross the configured threshold")
    void shouldCountOnlyCurrentTenantGroupsThatCrossConfiguredThreshold() {
        jdbcTemplate.update(
                "UPDATE sys_audit_config SET config_value = '3' WHERE config_key = 'HIGH_FREQ_THRESHOLD'"
        );
        insertOperations(CURRENT_TENANT, "f019-crossing", "f019-user", "198.51.100.19", 4);
        insertOperations(CURRENT_TENANT, "f019-boundary", "f019-boundary", "198.51.100.20", 3);
        insertOperations(OTHER_TENANT, "f019-other", "f019-other", "203.0.113.19", 4);

        List<HighFrequencyOperationVO> alerts = TenantContext.callWithTenantIsolation(
                CURRENT_TENANT,
                sysAuditService::getHighFrequencyOperations
        );
        Map<String, Object> overview = TenantContext.callWithTenantIsolation(
                CURRENT_TENANT,
                sysAuditService::getAuditOverview
        );
        Map<String, Object> anomalies = TenantContext.callWithTenantIsolation(
                CURRENT_TENANT,
                sysAuditService::checkAnomalies
        );

        assertThat(alerts)
                .extracting(HighFrequencyOperationVO::getUserId)
                .containsExactly("f019-crossing");
        assertThat(alerts.getFirst().getOperationCount()).isEqualTo(4);
        assertThat(overview.get("highFrequencyAlerts")).isEqualTo(1);
        assertThat(anomalies.get("hasAnomalies")).isEqualTo(true);
        assertThat(anomalies.get("anomalyDetails").toString())
                .contains("\"tenantId\":" + CURRENT_TENANT)
                .contains("\"highFrequencyUsers\":1")
                .contains("\"highFrequency\":3")
                .doesNotContain(String.valueOf(OTHER_TENANT));
        assertThat(TenantContext.getTenantId()).isNull();
    }

    /**
     * Inserts a bounded operation-log fixture inside the active five-minute window.
     */
    private void insertOperations(long tenantId, String userId, String username, String requestIp, int count) {
        for (int index = 0; index < count; index++) {
            jdbcTemplate.update(
                    """
                    INSERT INTO sys_operation_log
                        (tenant_id, module, operation_type, request_ip, status, user_id, username, operation_time)
                    VALUES (?, 'f019-audit', 'QUERY', ?, 0, ?, ?, NOW())
                    """,
                    tenantId,
                    requestIp,
                    userId,
                    username
            );
        }
    }
}
