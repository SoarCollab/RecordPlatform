package cn.flying.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the exact platform forward migration and database ownership/state invariants in real MySQL. */
@Testcontainers(disabledWithoutDocker = false)
class PlatformControlPlaneMigrationIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("platform_control_plane_migration")
            .withUsername("test")
            .withPassword("test");

    /** Resets only this suite's disposable database so every scenario has independent migration history. */
    @BeforeEach
    void resetIsolatedDatabase() {
        flyway(null, true).clean();
    }

    /** Existing tenant/user policies and global configuration retain values and identities while receiving version zero. */
    @Test
    void upgradesExistingQuotaAndConfigurationRowsWithoutDataLoss() throws SQLException {
        flyway("1.22.0", false).migrate();
        long originalConfigId;
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("""
                    SELECT data_type, extra FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = 'sys_audit_config' AND column_name = 'id'
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("data_type")).isEqualTo("int");
                assertThat(rows.getString("extra")).doesNotContain("auto_increment");
            }
            statement.executeUpdate("""
                    INSERT INTO quota_policy (id, tenant_id, scope_type, scope_id, max_storage_bytes, max_file_count, status)
                    VALUES (9234001, 42, 'TENANT', 42, 123456, 123, 1),
                           (9234002, 42, 'USER', 4201, 4567, 4, 0)
                    """);
            statement.executeUpdate("""
                    UPDATE sys_audit_config SET config_value = '87', description = 'Existing operator description'
                    WHERE config_key = 'HIGH_FREQ_THRESHOLD'
                    """);
            try (var rows = statement.executeQuery("SELECT id FROM sys_audit_config WHERE config_key = 'HIGH_FREQ_THRESHOLD'")) {
                assertThat(rows.next()).isTrue();
                originalConfigId = rows.getLong(1);
            }
        }

        Flyway upgraded = flyway("1.23.0", false);
        assertThat(upgraded.migrate().migrationsExecuted).isOne();
        assertThat(upgraded.info().current().getVersion().getVersion()).isEqualTo("1.23.0");
        assertThat(upgraded.migrate().migrationsExecuted).isZero();

        try (Connection connection = connection(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("""
                    SELECT data_type, extra FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = 'sys_audit_config' AND column_name = 'id'
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("data_type")).isEqualTo("bigint");
                assertThat(rows.getString("extra")).doesNotContain("auto_increment");
            }
            assertThat(statement.executeUpdate("""
                    INSERT INTO sys_audit_config (id, config_key, config_value)
                    VALUES (923400000000000001, 'PLATFORM_MIGRATION_BIGINT_TEST', '1')
                    """)).isOne();
            try (var rows = statement.executeQuery("""
                    SELECT id, version FROM sys_audit_config WHERE config_key = 'PLATFORM_MIGRATION_BIGINT_TEST'
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong("id")).isEqualTo(923400000000000001L);
                assertThat(rows.getLong("version")).isZero();
            }
            try (var rows = statement.executeQuery("""
                    SELECT id, tenant_id, scope_type, scope_id, max_storage_bytes, max_file_count, status, version
                    FROM quota_policy WHERE id IN (9234001, 9234002) ORDER BY id
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong("id")).isEqualTo(9234001L);
                assertThat(rows.getLong("tenant_id")).isEqualTo(42L);
                assertThat(rows.getString("scope_type")).isEqualTo("TENANT");
                assertThat(rows.getLong("scope_id")).isEqualTo(42L);
                assertThat(rows.getLong("max_storage_bytes")).isEqualTo(123456L);
                assertThat(rows.getLong("max_file_count")).isEqualTo(123L);
                assertThat(rows.getInt("status")).isOne();
                assertThat(rows.getLong("version")).isZero();
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong("id")).isEqualTo(9234002L);
                assertThat(rows.getString("scope_type")).isEqualTo("USER");
                assertThat(rows.getLong("scope_id")).isEqualTo(4201L);
                assertThat(rows.getLong("max_storage_bytes")).isEqualTo(4567L);
                assertThat(rows.getLong("max_file_count")).isEqualTo(4L);
                assertThat(rows.getInt("status")).isZero();
                assertThat(rows.getLong("version")).isZero();
                assertThat(rows.next()).isFalse();
            }
            try (var rows = statement.executeQuery("""
                    SELECT id, config_value, description, version FROM sys_audit_config
                    WHERE config_key = 'HIGH_FREQ_THRESHOLD'
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong("id")).isEqualTo(originalConfigId);
                assertThat(rows.getString("config_value")).isEqualTo("87");
                assertThat(rows.getString("description")).isEqualTo("Existing operator description");
                assertThat(rows.getLong("version")).isZero();
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM platform_operation_log")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isZero();
            }
        }
    }

    /** Fresh schema rejects non-system ownership, duplicate logical commands, and incomplete terminal states. */
    @Test
    void enforcesOperationOwnershipIdempotencyAndCompleteTerminalState() throws SQLException {
        flyway("1.23.0", false).migrate();
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            insertClaim(connection, 9234101L, 0L, 41L, "89452768-6843-4de6-b061-91c4f28c2a0e");
            assertThatThrownBy(() -> insertClaim(connection, 9234102L, 42L, 41L,
                    "89452768-6843-4de6-b061-91c4f28c2a0f")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertClaim(connection, 9234103L, 0L, 0L,
                    "89452768-6843-4de6-b061-91c4f28c2a10")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertClaim(connection, 9234104L, 0L, 41L,
                    "89452768-6843-4de6-b061-91c4f28c2a0e")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE platform_operation_log SET status = 'SUCCESS' WHERE id = 9234101
                    """)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE platform_operation_log
                    SET status = 'SUCCESS', completed_at = NOW(), result_json = JSON_OBJECT(), duration_ms = NULL
                    WHERE id = 9234101
                    """)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE platform_operation_log
                    SET status = 'FAILURE', completed_at = NOW(), error_code = 40000, duration_ms = NULL
                    WHERE id = 9234101
                    """)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE platform_operation_log
                    SET status = 'FAILURE', completed_at = NOW(), error_code = 40000, duration_ms = -1
                    WHERE id = 9234101
                    """)).isInstanceOf(SQLException.class);

            assertThat(statement.executeUpdate("""
                    UPDATE platform_operation_log
                    SET status = 'SUCCESS', completed_at = NOW(), duration_ms = 0, result_json = JSON_OBJECT()
                    WHERE id = 9234101
                    """)).isOne();
            try (var rows = statement.executeQuery("SELECT tenant_id, actor_id, status, duration_ms FROM platform_operation_log")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong("tenant_id")).isZero();
                assertThat(rows.getLong("actor_id")).isEqualTo(41L);
                assertThat(rows.getString("status")).isEqualTo("SUCCESS");
                assertThat(rows.getLong("duration_ms")).isZero();
                assertThat(rows.next()).isFalse();
            }
        }
    }

    /** Inserts a syntactically valid initial operation to isolate database constraint behavior. */
    private void insertClaim(Connection connection, long id, long tenant, long actor, String key) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO platform_operation_log
                    (id, tenant_id, actor_id, idempotency_key, request_hash, operation, target_tenant_id,
                     resource_type, resource_id, reason, status, started_at)
                VALUES (?, ?, ?, ?, ?, 'TENANT_UPDATE', 42, 'TENANT', 42, 'Migration verification', 'PROCESSING', NOW())
                """)) {
            statement.setLong(1, id);
            statement.setLong(2, tenant);
            statement.setLong(3, actor);
            statement.setString(4, key);
            statement.setString(5, "a".repeat(64));
            assertThat(statement.executeUpdate()).isOne();
        }
    }

    /** Builds an exact-version Flyway instance with validation enabled and test-only cleaning when requested. */
    private Flyway flyway(String target, boolean cleanEnabled) {
        var builder = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .validateOnMigrate(true)
                .cleanDisabled(!cleanEnabled);
        if (target != null) builder.target(MigrationVersion.fromVersion(target));
        return builder.load();
    }

    /** Opens one real connection to the suite's disposable database. */
    private Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
