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

/** Executes V1.21 platform identity migration and constraints against real MySQL 8. */
@Testcontainers(disabledWithoutDocker = false)
class PlatformIdentityMigrationIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("platform_identity_migration")
            .withUsername("test")
            .withPassword("test");

    /** Cleans the isolated database before every migration path. */
    @BeforeEach
    void cleanDatabase() {
        flyway(null, true).clean();
    }

    /** Existing tenant accounts receive active status/version defaults without duplicate released columns. */
    @Test
    void upgradesExistingRowsWithoutDataLoss() throws SQLException {
        flyway("1.20.1", false).migrate();
        try (Connection connection = connection()) {
            connection.createStatement().executeUpdate("""
                    INSERT INTO tenant (id, name, code, status, deleted, create_time, update_time)
                    VALUES (42, 'Tenant 42', 'tenant-42', 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO account (id, tenant_id, username, password, email, role, deleted)
                    VALUES (4201, 42, 'legacy-admin', '$2a$10$synthetic', 'legacy-admin@example.test', 'admin', 0)
                    """);
        }

        Flyway upgraded = flyway(null, false);
        upgraded.migrate();

        assertThat(upgraded.info().current().getVersion().getVersion()).isEqualTo("1.21.0");
        try (Connection connection = connection();
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("""
                    SELECT status, auth_version, last_login_time
                      FROM account WHERE id = 4201
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("status")).isEqualTo(1);
                assertThat(rows.getLong("auth_version")).isZero();
                assertThat(rows.getObject("last_login_time")).isNull();
            }
            try (var rows = statement.executeQuery("""
                    SELECT status, version, disabled_reason, disabled_at, disabled_by
                      FROM tenant WHERE id = 42
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("status")).isEqualTo(1);
                assertThat(rows.getLong("version")).isZero();
                assertThat(rows.getObject("disabled_reason")).isNull();
                assertThat(rows.getObject("disabled_at")).isNull();
                assertThat(rows.getObject("disabled_by")).isNull();
            }
            try (var rows = statement.executeQuery("""
                    SELECT COUNT(*) AS column_count
                      FROM information_schema.columns
                     WHERE table_schema = DATABASE()
                       AND table_name = 'tenant'
                       AND column_name = 'update_time'
                    """)) {
                rows.next();
                assertThat(rows.getInt("column_count")).isEqualTo(1);
            }
        }
    }

    /** Database constraint rejects platform administrators outside system tenant zero. */
    @Test
    void enforcesPlatformAdministratorTenantBinding() throws SQLException {
        flyway(null, false).migrate();
        try (Connection connection = connection()) {
            assertThatThrownBy(() -> connection.createStatement().executeUpdate("""
                    INSERT INTO account
                        (id, tenant_id, username, password, email, role, status, auth_version, deleted)
                    VALUES
                        (4202, 42, 'invalid-platform', '$2a$10$synthetic',
                         'invalid-platform@example.test', 'platform_admin', 1, 0, 0)
                    """))
                    .isInstanceOf(SQLException.class);

            int inserted = connection.createStatement().executeUpdate("""
                    INSERT INTO account
                        (id, tenant_id, username, password, email, role, status, auth_version, deleted)
                    VALUES
                        (4203, 0, 'valid-platform', '$2a$10$synthetic',
                         'valid-platform@example.test', 'platform_admin', 1, 0, 0)
                    """);
            assertThat(inserted).isEqualTo(1);
        }
    }

    /** Builds Flyway for the isolated real database and optional target version. */
    private Flyway flyway(String targetVersion, boolean cleanEnabled) {
        var builder = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .validateOnMigrate(true)
                .cleanDisabled(!cleanEnabled);
        if (targetVersion != null) {
            builder.target(MigrationVersion.fromVersion(targetVersion));
        }
        return builder.load();
    }

    /** Opens a JDBC connection to the test container. */
    private Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
