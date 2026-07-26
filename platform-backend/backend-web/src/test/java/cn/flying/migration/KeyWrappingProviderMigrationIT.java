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

/**
 * 使用真实 MySQL 验证密钥包装提供方迁移的全新安装与历史升级路径。
 */
@Testcontainers(disabledWithoutDocker = false)
class KeyWrappingProviderMigrationIT {

    private static final String VERSION_1_17 = "1.17.0";
    private static final String VERSION_1_18 = "1.18.0";

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("key_wrapping_provider_migration")
                    .withUsername("test")
                    .withPassword("test")
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("init-test-db.sql"),
                            "/docker-entrypoint-initdb.d/init-test-db.sql");

    /**
     * 每个场景前清理 Flyway 管理对象，隔离全新安装和升级路径。
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
     * 验证空库可直接迁移到 V1.18，并接受 Vault 所需的长 key id 与空 IV。
     */
    @Test
    void shouldInstallProviderMetadataOnFreshDatabase() throws SQLException {
        Flyway latest = configuredFlyway(null);
        latest.migrate();
        assertCurrentVersion(latest, VERSION_1_18);

        try (Connection connection = openConnection()) {
            assertEnvelopeColumnContract(connection);
            assertAuditColumnContract(connection);
            assertTargetIndexContract(connection);

            String longKeyId = "transit/keys/" + "vault-key-".repeat(20);
            try (var statement = connection.prepareStatement("""
                    INSERT INTO file_key_envelope (
                        id, tenant_id, file_id, file_hash, recipient_type, recipient_id,
                        key_version, algorithm_suite, wrapping_algorithm, kms_provider,
                        provider_contract_version, kms_key_id, provider_key_version,
                        context_schema, encrypted_data_key, wrapping_iv, aad_hash, status, deleted
                    ) VALUES (98180001, 98180002, 98180003, 'sha256:fresh', 'OWNER', 98180004,
                              1, 'AES-256-GCM', 'VAULT-TRANSIT-AES256-GCM96-DERIVED',
                              'vault-transit', 1, ?, '3', 'rp-file-envelope-context-v2',
                              'vault:v3:ciphertext', NULL, 'sha256:context', 'ACTIVE', 0)
                    """)) {
                statement.setString(1, longKeyId);
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
        }
    }

    /**
     * 验证 V1.17 本地历史信封升级后被确定性回填为 local v1 合同。
     */
    @Test
    void shouldBackfillHistoricalLocalEnvelopeOnUpgrade() throws SQLException {
        Flyway version117 = configuredFlyway(VERSION_1_17);
        version117.migrate();
        assertCurrentVersion(version117, VERSION_1_17);

        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO file_key_envelope (
                        id, tenant_id, file_id, file_hash, recipient_type, recipient_id,
                        key_version, algorithm_suite, wrapping_algorithm, kms_provider,
                        kms_key_id, encrypted_data_key, wrapping_iv, aad_hash, status, deleted
                    ) VALUES (98180101, 98180102, 98180103, 'sha256:legacy', 'OWNER', 98180104,
                              7, 'AES-256-GCM', 'AES/GCM/NoPadding', 'local',
                              'local-file-key-v7', 'legacy-ciphertext', 'legacy-iv',
                              'legacy-aad-hash', 'ACTIVE', 0)
                    """);
        }

        Flyway version118 = configuredFlyway(VERSION_1_18);
        version118.migrate();
        assertCurrentVersion(version118, VERSION_1_18);

        try (Connection connection = openConnection();
             var statement = connection.prepareStatement("""
                     SELECT kms_provider, provider_contract_version, provider_key_version,
                            context_schema, wrapping_iv
                       FROM file_key_envelope
                      WHERE id = 98180101
                     """);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("kms_provider")).isEqualTo("local");
            assertThat(resultSet.getInt("provider_contract_version")).isEqualTo(1);
            assertThat(resultSet.getString("provider_key_version")).isEqualTo("7");
            assertThat(resultSet.getString("context_schema")).isEqualTo("rp-file-envelope-aad-v1");
            assertThat(resultSet.getString("wrapping_iv")).isEqualTo("legacy-iv");
        }
    }

    /**
     * 构造指向同一真实 MySQL 数据源的 Flyway 实例。
     */
    private Flyway configuredFlyway(String targetVersion) {
        var builder = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration");
        if (targetVersion != null) {
            builder.target(MigrationVersion.fromVersion(targetVersion));
        }
        return builder.load();
    }

    /**
     * 断言当前 Flyway 版本等于目标版本。
     */
    private void assertCurrentVersion(Flyway flyway, String expectedVersion) {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(expectedVersion);
    }

    /**
     * 打开测试数据库连接。
     */
    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    /**
     * 验证信封表的提供方路由、长 key id 与可空 IV 合同。
     */
    private void assertEnvelopeColumnContract(Connection connection) throws SQLException {
        assertColumn(connection, "file_key_envelope", "provider_contract_version", "int", "NO", 1L);
        assertColumn(connection, "file_key_envelope", "provider_key_version", "varchar(128)", "YES", null);
        assertColumn(connection, "file_key_envelope", "context_schema", "varchar(64)", "NO", null);
        assertColumn(connection, "file_key_envelope", "kms_key_id", "varchar(512)", "NO", null);
        assertColumn(connection, "file_key_envelope", "wrapping_iv", "varchar(64)", "YES", null);
    }

    /**
     * 验证审计表具备稳定提供方与失败分类字段。
     */
    private void assertAuditColumnContract(Connection connection) throws SQLException {
        for (String column : new String[] {
                "kms_provider",
                "provider_contract_version",
                "provider_key_version",
                "key_id_fingerprint",
                "wrapping_algorithm",
                "algorithm_suite",
                "failure_category"
        }) {
            assertThat(columnExists(connection, "file_key_audit_log", column)).isTrue();
        }
    }

    /**
     * 验证完整轮换目标索引覆盖提供方、合同、key id 和原生 key 版本。
     */
    private void assertTargetIndexContract(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') AS columns_csv
                  FROM INFORMATION_SCHEMA.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'file_key_envelope'
                   AND INDEX_NAME = 'idx_file_key_envelope_target'
                """);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("columns_csv")).isEqualTo(
                    "tenant_id,file_id,recipient_type,recipient_id,status,kms_provider,"
                            + "provider_contract_version,kms_key_id,provider_key_version,"
                            + "key_version,wrapping_algorithm,context_schema,deleted");
        }
    }

    /**
     * 验证指定列的类型、可空性和可选默认值。
     */
    private void assertColumn(
            Connection connection,
            String table,
            String column,
            String expectedType,
            String expectedNullable,
            Long expectedDefault
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
                  FROM INFORMATION_SCHEMA.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString("COLUMN_TYPE")).isEqualToIgnoringCase(expectedType);
                assertThat(resultSet.getString("IS_NULLABLE")).isEqualTo(expectedNullable);
                if (expectedDefault != null) {
                    assertThat(resultSet.getLong("COLUMN_DEFAULT")).isEqualTo(expectedDefault);
                }
            }
        }
    }

    /**
     * 查询指定表列是否存在。
     */
    private boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT COUNT(*)
                  FROM INFORMATION_SCHEMA.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) == 1;
            }
        }
    }
}
