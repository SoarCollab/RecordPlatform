package cn.flying.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Uses real MySQL 8 to prove rotation migration safety, active authority, and worker fences.
 */
@Testcontainers(disabledWithoutDocker = false)
class KeyRotationMigrationIT {

    private static final String VERSION_1_18 = "1.18.0";
    private static final String VERSION_1_19 = "1.19.0";
    private static final long TENANT_ID = 98_190_001L;
    private static final long POLICY_ID = 98_190_010L;
    private static final long RUN_ID = 98_190_020L;

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("key_rotation_migration")
                    .withUsername("test")
                    .withPassword("test")
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("init-test-db.sql"),
                            "/docker-entrypoint-initdb.d/init-test-db.sql");

    /**
     * Cleans every test back to an empty Flyway-managed database.
     */
    @BeforeEach
    void cleanDatabase() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
    }

    /**
     * Proves a fresh V1.19 install allows pending history but rejects two readable envelopes.
     */
    @Test
    void shouldInstallSchemaAndEnforceOneActiveRecipient() throws SQLException {
        assertCurrentVersion(migrateTo(VERSION_1_19), VERSION_1_19);

        try (Connection connection = openConnection()) {
            execute(connection, """
                    INSERT INTO tenant (id, name, code, status, deleted)
                    VALUES (98190001, 'Rotation tenant', 'rotation-tenant', 1, 0)
                    """);
            insertPolicyAndRun(connection);
            insertEnvelope(connection, 98_190_101L, 98_190_201L, "ACTIVE");
            insertEnvelope(connection, 98_190_102L, 98_190_201L, "PENDING_VERIFICATION");

            assertThatThrownBy(() -> insertEnvelope(
                    connection, 98_190_103L, 98_190_201L, "ACTIVE"))
                    .isInstanceOf(SQLException.class);
            assertThat(queryInt(connection, """
                    SELECT COUNT(*) FROM file_key_envelope
                    WHERE tenant_id = 98190001 AND recipient_id = 98190201
                      AND active_slot = 1
                    """)).isEqualTo(1);
            assertThat(queryInt(connection, """
                    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME IN ('key_rotation_policy', 'key_rotation_run',
                                         'key_rotation_item', 'key_rotation_audit_log')
                    """)).isEqualTo(4);
            assertThat(queryLong(connection, """
                    SELECT p.tenant_id
                    FROM key_rotation_policy p
                    INNER JOIN tenant t
                            ON t.id = p.tenant_id
                           AND t.status = 1
                           AND t.deleted = 0
                    LEFT JOIN key_rotation_run r
                           ON r.policy_id = p.id
                          AND r.tenant_id = p.tenant_id
                          AND r.deleted = 0
                    WHERE p.deleted = 0
                      AND (
                            (p.status = 'ACTIVE' AND p.schedule_enabled = 1
                                AND p.next_run_at IS NOT NULL AND p.next_run_at <= NOW())
                            OR r.status IN ('PLANNED', 'RUNNING')
                            OR (r.id = p.last_run_id AND r.status = 'COMPLETED'
                                AND r.mode = 'APPLY' AND r.retirement_status = 'NOT_READY'
                                AND r.retirement_eligible_at IS NOT NULL
                                AND r.retirement_eligible_at <= NOW())
                      )
                    GROUP BY p.tenant_id
                    ORDER BY MIN(CASE
                            WHEN r.status IN ('PLANNED', 'RUNNING') THEN r.update_time
                            WHEN r.id = p.last_run_id AND r.retirement_eligible_at IS NOT NULL
                                THEN r.retirement_eligible_at
                            ELSE p.next_run_at
                        END) ASC,
                        p.tenant_id ASC
                    LIMIT 1
                    """)).isEqualTo(TENANT_ID);
        }
    }

    /**
     * Proves ambiguous historical authority blocks upgrade before the unique invariant is enabled.
     */
    @Test
    void shouldRejectUpgradeWhenHistoricalActiveRecipientsAreDuplicated() throws SQLException {
        assertCurrentVersion(migrateTo(VERSION_1_18), VERSION_1_18);
        try (Connection connection = openConnection()) {
            insertEnvelope(connection, 98_190_111L, 98_190_211L, "ACTIVE");
            insertEnvelope(connection, 98_190_112L, 98_190_211L, "ACTIVE");
        }

        assertThatThrownBy(() -> migrateTo(VERSION_1_19))
                .hasStackTraceContaining("duplicate active key envelopes require manual review");
    }

    /**
     * Proves concurrent workers lock distinct due items with FOR UPDATE SKIP LOCKED.
     */
    @Test
    void shouldSplitClaimRowsAcrossConcurrentWorkers() throws SQLException {
        assertCurrentVersion(migrateTo(VERSION_1_19), VERSION_1_19);
        try (Connection setup = openConnection()) {
            insertPolicyAndRun(setup);
            insertEnvelope(setup, 98_190_121L, 98_190_221L, "ACTIVE");
            insertEnvelope(setup, 98_190_122L, 98_190_222L, "ACTIVE");
            insertItem(setup, 98_190_301L, 98_190_121L);
            insertItem(setup, 98_190_302L, 98_190_122L);
        }

        try (Connection first = openConnection(); Connection second = openConnection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            long firstId = lockNextItem(first);
            long secondId = lockNextItem(second);

            assertThat(firstId).isEqualTo(98_190_301L);
            assertThat(secondId).isEqualTo(98_190_302L);
            assertThat(secondId).isNotEqualTo(firstId);
            first.rollback();
            second.rollback();
        }
    }

    /**
     * Proves duplicate triggers fail and only the current claim token may complete an item.
     */
    @Test
    void shouldEnforceTriggerAndClaimFences() throws SQLException {
        assertCurrentVersion(migrateTo(VERSION_1_19), VERSION_1_19);
        try (Connection connection = openConnection()) {
            insertPolicyAndRun(connection);
            assertThatThrownBy(() -> insertRun(connection, RUN_ID + 1, "manual:request-1"))
                    .isInstanceOf(SQLException.class);

            insertEnvelope(connection, 98_190_131L, 98_190_231L, "ACTIVE");
            insertItem(connection, 98_190_311L, 98_190_131L);
            execute(connection, """
                    UPDATE key_rotation_item
                    SET status = 'RUNNING', claim_token = 'current-token', attempt_count = 1,
                        lease_expires_at = DATE_ADD(NOW(), INTERVAL 5 MINUTE)
                    WHERE id = 98190311
                    """);

            assertThat(executeUpdate(connection, """
                    UPDATE key_rotation_item
                    SET status = 'SUCCEEDED', claim_token = NULL, lease_expires_at = NULL
                    WHERE id = 98190311 AND claim_token = 'stale-token'
                      AND status = 'RUNNING' AND lease_expires_at > NOW()
                    """)).isZero();
            assertThat(executeUpdate(connection, """
                    UPDATE key_rotation_item
                    SET status = 'SUCCEEDED', claim_token = NULL, lease_expires_at = NULL
                    WHERE id = 98190311 AND claim_token = 'current-token'
                      AND status = 'RUNNING' AND lease_expires_at > NOW()
                    """)).isEqualTo(1);
        }
    }

    /**
     * Migrates the shared container to one exact Flyway target.
     */
    private Flyway migrateTo(String targetVersion) {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(targetVersion))
                .load();
        flyway.migrate();
        return flyway;
    }

    /**
     * Proves the applied Flyway version exactly matches the scenario target.
     */
    private void assertCurrentVersion(Flyway flyway, String expectedVersion) {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(expectedVersion);
    }

    /**
     * Opens a JDBC connection to the container-managed schema.
     */
    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    /**
     * Inserts a complete envelope fixture with a caller-selected recipient and lifecycle state.
     */
    private void insertEnvelope(Connection connection, long id, long recipientId, String status)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO file_key_envelope (
                    id, tenant_id, file_id, file_hash, recipient_type, recipient_id,
                    key_version, algorithm_suite, wrapping_algorithm, kms_provider,
                    provider_contract_version, kms_key_id, provider_key_version,
                    context_schema, encrypted_data_key, wrapping_iv, aad_hash, status, deleted
                ) VALUES (?, ?, 98190401, 'sha256:file', 'SHARE', ?,
                          1, 'RP-AES256-GCM-CHUNK-CHAIN-V1', 'VAULT-TRANSIT', 'vault-transit',
                          1, 'tenant-key', '6', 'rp-file-envelope-context-v2',
                          'vault:v6:ciphertext', NULL, 'sha256:context', ?, 0)
                """)) {
            statement.setLong(1, id);
            statement.setLong(2, TENANT_ID);
            statement.setLong(3, recipientId);
            statement.setString(4, status);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    /**
     * Inserts one active policy and its first immutable APPLY run.
     */
    private void insertPolicyAndRun(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO key_rotation_policy (
                    id, tenant_id, status, target_provider, target_provider_contract,
                    target_key_id, target_provider_key_version, target_wrapping_algorithm,
                    target_context_schema, target_logical_key_version, batch_size,
                    max_items_per_minute, schedule_enabled, max_attempts,
                    initial_backoff_seconds, max_backoff_seconds, lease_seconds,
                    grace_period_seconds, policy_version, created_by, updated_by,
                    retirement_status, deleted
                ) VALUES (
                    98190010, 98190001, 'ACTIVE', 'vault-transit', 1,
                    'tenant-key', '7', 'VAULT-TRANSIT', 'rp-file-envelope-context-v2',
                    2, 25, 100, 0, 3, 5, 60, 120, 600, 1, 98190501, 98190501,
                    'NOT_READY', 0)
                """);
        insertRun(connection, RUN_ID, "manual:request-1");
    }

    /**
     * Inserts one immutable run using the shared policy target snapshot.
     */
    private void insertRun(Connection connection, long runId, String triggerKey) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO key_rotation_run (
                    id, tenant_id, policy_id, policy_version, trigger_type, trigger_key,
                    mode, status, target_provider, target_provider_contract, target_key_id,
                    target_provider_key_version, target_wrapping_algorithm, target_context_schema,
                    target_logical_key_version, batch_size, max_items_per_minute, max_attempts,
                    initial_backoff_seconds, max_backoff_seconds, lease_seconds,
                    grace_period_seconds, snapshot_max_envelope_id, scan_cursor_id,
                    discovery_complete, created_by, retirement_status, deleted
                ) VALUES (
                    ?, 98190001, 98190010, 1, 'MANUAL', ?, 'APPLY', 'RUNNING',
                    'vault-transit', 1, 'tenant-key', '7', 'VAULT-TRANSIT',
                    'rp-file-envelope-context-v2', 2, 25, 100, 3, 5, 60, 120,
                    600, 98199999, 0, 1, 98190501, 'NOT_READY', 0)
                """)) {
            statement.setLong(1, runId);
            statement.setString(2, triggerKey);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    /**
     * Inserts one pending work item linked to a source envelope.
     */
    private void insertItem(Connection connection, long itemId, long sourceEnvelopeId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO key_rotation_item (
                    id, tenant_id, run_id, source_envelope_id, file_id,
                    recipient_type, recipient_id, status, deleted
                ) VALUES (?, 98190001, 98190020, ?, 98190401,
                          'SHARE', ?, 'PENDING', 0)
                """)) {
            statement.setLong(1, itemId);
            statement.setLong(2, sourceEnvelopeId);
            statement.setLong(3, itemId + 1_000L);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    /**
     * Locks the next due item in claim-index order while skipping rows locked by another transaction.
     */
    private long lockNextItem(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT id FROM key_rotation_item FORCE INDEX (idx_key_rotation_item_claim)
                     WHERE tenant_id = 98190001 AND run_id = 98190020 AND deleted = 0
                       AND attempt_count < 3 AND status = 'PENDING'
                     ORDER BY status ASC, retryable ASC, next_retry_at ASC,
                              lease_expires_at ASC, id ASC
                     LIMIT 1 FOR UPDATE SKIP LOCKED
                     """)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }

    /**
     * Executes a DDL or DML statement that must succeed.
     */
    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * Executes a DML statement and returns its affected-row fence result.
     */
    private int executeUpdate(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    /**
     * Executes a scalar integer query.
     */
    private int queryInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    /**
     * Executes a scalar long query used for bounded scheduler selection evidence.
     */
    private long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }
}
