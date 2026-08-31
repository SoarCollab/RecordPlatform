package cn.flying.health;

import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.mapper.FileSagaMapper;
import cn.flying.dao.mapper.OutboxEventMapper;
import cn.flying.test.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies aggregate SQL through real MyBatis interception and the isolated MySQL fixture. */
@Transactional
@AutoConfigureMockMvc
@TestPropertySource(properties = {"outbox.health.failed-threshold=1", "saga.health.failed-threshold=1"})
class CrossTenantHealthIndicatorIT extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private OutboxEventMapper outboxMapper;
    @Autowired
    private FileSagaMapper sagaMapper;
    @Autowired
    @Qualifier("outbox")
    private HealthIndicator outbox;
    @Autowired
    @Qualifier("saga")
    private HealthIndicator saga;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ConnectionFactory rabbitConnectionFactory;

    /** Runs health callbacks against the existing RabbitMQ container without enabling message sends. */
    @BeforeEach
    void configureRealRabbitHealthProbes() {
        RabbitHealthProbeFixture.enableHealthCallbacks(rabbitTemplate, rabbitConnectionFactory);
    }

    /** Removes thread context; Spring rolls back every fixture row after the test. */
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** Proves both nonzero tenants contribute while ordinary SQL and anonymous details stay isolated. */
    @Test
    void actuatorShouldCountBothTenantsWithoutPublishingAnonymousDetails() throws Exception {
        TenantContext.clear();
        long priorOutbox = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE status='FAILED'", Long.class);
        long priorSaga = jdbc.queryForObject("SELECT COUNT(*) FROM file_saga WHERE status='FAILED'", Long.class);
        insertTenantHealthRows(876201L);
        insertTenantHealthRows(876202L);

        for (long tenant : new long[]{876201L, 876202L}) {
            TenantContext.setTenantId(tenant);
            assertThat(outboxMapper.countByStatus("FAILED")).isEqualTo(1);
            assertThat(sagaMapper.countByStatus("FAILED")).isEqualTo(1);
            assertGlobalHealth(outbox, priorOutbox + 2);
            assertGlobalHealth(saga, priorSaga + 2);
            assertThat(TenantContext.getTenantId()).isEqualTo(tenant);
            assertThat(TenantContext.isIgnoreIsolation()).isFalse();
        }

        TenantContext.clear();
        assertGlobalHealth(outbox, priorOutbox + 2);
        assertGlobalHealth(saga, priorSaga + 2);
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(TenantContext.isIgnoreIsolation()).isFalse();
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(TenantContext.isIgnoreIsolation()).isFalse();
    }

    /** Inserts test-owned failed work into the transaction without involving external producers. */
    private void insertTenantHealthRows(long tenant) {
        jdbc.update("""
                INSERT INTO outbox_event(id, tenant_id, aggregate_type, aggregate_id, event_type,
                                         payload, status, retry_count)
                VALUES (?, ?, 'FILE', ?, 'health-fixture', '{}', 'FAILED', 0)
                """, "health-" + tenant, tenant, tenant);
        jdbc.update("""
                INSERT INTO file_saga(id, tenant_id, request_id, user_id, file_name, current_step, status)
                VALUES (?, ?, ?, ?, 'health.txt', 'UPLOAD', 'FAILED')
                """, tenant, tenant, "health-" + tenant, tenant);
    }

    /** Exercises the production Actuator entry point and its per-call detail policy. */
    private void assertGlobalHealth(HealthIndicator indicator, long expectedFailed) {
        Health detailed = indicator.getHealth(true);
        assertThat(detailed.getStatus()).isEqualTo(Status.DOWN);
        assertThat(detailed.getDetails()).containsEntry("failed", expectedFailed);
        Health anonymous = indicator.getHealth(false);
        assertThat(anonymous.getStatus()).isEqualTo(Status.DOWN);
        assertThat(anonymous.getDetails()).isEmpty();
    }
}
