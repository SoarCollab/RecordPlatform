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
 * Uses real MySQL 8 to prove V1.20 fresh install, upgrade, constraints, and policy concurrency.
 */
@Testcontainers(disabledWithoutDocker = false)
class RuntimeCryptoAgilityMigrationIT {

    private static final String VERSION_1_19 = "1.19.0";
    private static final String VERSION_1_20 = "1.20.0";

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("runtime_crypto_agility_migration")
                    .withUsername("test")
                    .withPassword("test")
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("init-test-db.sql"),
                            "/docker-entrypoint-initdb.d/init-test-db.sql");

    /**
     * Cleans every scenario back to an empty Flyway-managed database.
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
     * Proves a fresh V1.20 install creates mandatory routing columns and policy/audit tables.
     */
    @Test
    void shouldInstallRuntimeCryptoAgilityOnFreshDatabase() throws SQLException {
        assertCurrentVersion(migrateTo(VERSION_1_20), VERSION_1_20);

        try (Connection connection = openConnection()) {
            assertThat(queryInt(connection, """
                    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME IN ('tenant_crypto_policy', 'tenant_crypto_policy_audit')
                    """)).isEqualTo(2);
            assertThat(queryInt(connection, """
                    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME IN ('file_key_envelope', 'proof_signing_key', 'proof_bundle_issuance')
                      AND COLUMN_NAME IN ('algorithm_suite', 'signing_provider', 'signing_provider_contract',
                                          'signature_suite', 'kem_suite', 'proof_suite')
                      AND IS_NULLABLE = 'NO'
                    """)).isEqualTo(12);
            insertPolicy(connection, 120_001L, 120_101L, 1L);
            insertSuccessAudit(connection, 120_201L, 120_001L, 120_101L);
            assertThat(queryInt(connection, "SELECT COUNT(*) FROM tenant_crypto_policy_audit"))
                    .isEqualTo(1);
        }
    }

    /**
     * Proves V1.19 envelope and proof rows receive deterministic persisted routing identities on upgrade.
     */
    @Test
    void shouldBackfillHistoricalEnvelopeAndProofRoutingOnUpgrade() throws SQLException {
        assertCurrentVersion(migrateTo(VERSION_1_19), VERSION_1_19);
        try (Connection connection = openConnection()) {
            execute(connection, """
                    INSERT INTO file_key_envelope (
                        id, tenant_id, file_id, file_hash, recipient_type, recipient_id,
                        key_version, algorithm_suite, signature_suite, kem_suite, proof_suite,
                        wrapping_algorithm, kms_provider, provider_contract_version, kms_key_id,
                        provider_key_version, context_schema, encrypted_data_key, wrapping_iv,
                        aad_hash, status, deleted
                    ) VALUES (
                        120301, 120001, 120401, 'sha256:legacy', 'OWNER', 120501,
                        1, '', NULL, NULL, NULL, 'AES-256-GCM', 'local', 1,
                        'local-file-key-v1', '1', 'rp-file-envelope-aad-v1',
                        'legacy-ciphertext', 'legacy-iv', 'legacy-aad', 'ACTIVE', 0)
                    """);
            execute(connection, """
                    INSERT INTO proof_signing_key (
                        id, key_id, key_version, signature_algorithm, public_key_spki,
                        public_key_fingerprint, status, first_seen_at, deleted
                    ) VALUES (
                        120601, 'legacy-proof-key', 1, 'EdDSA', 'spki',
                        CONCAT('sha256:', REPEAT('a', 64)), 'ACTIVE', NOW(3), 0)
                    """);
            execute(connection, "SET FOREIGN_KEY_CHECKS = 0");
            execute(connection, """
                    INSERT INTO proof_bundle_issuance (
                        id, tenant_id, proof_id, file_id, file_version, leaf_id,
                        manifest_hash, manifest_json, signature_jws, signature_algorithm,
                        key_id, key_version, public_key_spki, public_key_fingerprint,
                        issued_status, status, status_version, issued_at, deleted
                    ) VALUES (
                        120701, 120001, 'legacy-proof', 120401, 1, 120801,
                        CONCAT('sha256:', REPEAT('b', 64)), '{}', 'header.payload.signature',
                        'EdDSA', 'legacy-proof-key', 1, 'spki',
                        CONCAT('sha256:', REPEAT('a', 64)), 'ACTIVE', 'ACTIVE', 1, NOW(3), 0)
                    """);
            execute(connection, "SET FOREIGN_KEY_CHECKS = 1");
        }

        assertCurrentVersion(migrateTo(VERSION_1_20), VERSION_1_20);
        try (Connection connection = openConnection()) {
            assertThat(queryString(connection, """
                    SELECT CONCAT_WS('|', algorithm_suite, signature_suite, kem_suite, proof_suite)
                    FROM file_key_envelope WHERE id = 120301
                    """)).isEqualTo(
                            "RP-AES256-GCM-CHUNK-CHAIN-V1|UNSIGNED-V1|NONE-V1|RP-MERKLE-SHA256-V1");
            assertThat(queryString(connection, """
                    SELECT CONCAT_WS('|', signing_provider, signing_provider_contract,
                                     signature_suite, proof_suite)
                    FROM proof_signing_key WHERE id = 120601
                    """)).isEqualTo(
                            "local-ed25519|1|JWS-EDDSA-ED25519-V1|RP-SIGNED-PROOF-ZIP-V2");
            assertThat(queryString(connection, """
                    SELECT CONCAT_WS('|', signing_provider, signing_provider_contract,
                                     signature_suite, proof_suite)
                    FROM proof_bundle_issuance WHERE id = 120701
                    """)).isEqualTo(
                            "local-ed25519|1|JWS-EDDSA-ED25519-V1|RP-SIGNED-PROOF-ZIP-V2");
        }
    }

    /**
     * Proves mandatory routing, one-policy-per-tenant, and tenant-matched audit references fail closed.
     */
    @Test
    void shouldEnforceRoutingAndTenantPolicyConstraints() throws SQLException {
        assertCurrentVersion(migrateTo(VERSION_1_20), VERSION_1_20);
        try (Connection connection = openConnection()) {
            insertPolicy(connection, 120_011L, 120_111L, 1L);
            insertPolicy(connection, 120_012L, 120_112L, 2L);

            assertThatThrownBy(() -> insertPolicy(connection, 120_013L, 120_113L, 1L))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO tenant_crypto_policy_audit (
                        id, tenant_id, policy_id, policy_version, actor_id,
                        action, outcome, old_policy_fingerprint, new_policy_fingerprint,
                        failure_reason, deleted
                    ) VALUES (
                        120213, 1, 120112, 1, 120901, 'UPDATE', 'FAILURE',
                        NULL, NULL, 'POLICY_VERSION_CONFLICT', 0)
                    """))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO proof_signing_key (
                        id, key_id, key_version, signature_algorithm, public_key_spki,
                        public_key_fingerprint, status, first_seen_at, deleted
                    ) VALUES (
                        120613, 'missing-routing', 1, 'EdDSA', 'spki',
                        CONCAT('sha256:', REPEAT('c', 64)), 'ACTIVE', NOW(3), 0)
                    """))
                    .isInstanceOf(SQLException.class);
        }
    }

    /**
     * Proves concurrent workers cannot both lock and mutate the same tenant policy snapshot.
     */
    @Test
    void shouldSerializeConcurrentTenantPolicyUpdates() throws SQLException {
        assertCurrentVersion(migrateTo(VERSION_1_20), VERSION_1_20);
        try (Connection setup = openConnection()) {
            insertPolicy(setup, 120_021L, 120_121L, 21L);
        }

        try (Connection first = openConnection(); Connection second = openConnection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            assertThat(lockPolicy(first, false)).isTrue();
            assertThat(lockPolicy(second, true)).isFalse();
            first.rollback();
            second.rollback();
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
     * Proves the applied Flyway version exactly matches the requested scenario target.
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
     * Inserts one complete tenant policy at a stable optimistic version.
     */
    private void insertPolicy(Connection connection, long id, long actorId, long tenantId)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO tenant_crypto_policy (
                    id, tenant_id, content_encryption_suite, envelope_signature_suite,
                    kem_suite, proof_suite, wrapping_provider, wrapping_provider_contract,
                    signed_proof_signature_suite, signed_proof_suite,
                    signing_provider, signing_provider_contract, policy_version,
                    created_by, updated_by, deleted
                ) VALUES (
                    ?, ?, 'RP-AES256-GCM-CHUNK-CHAIN-V1', 'UNSIGNED-V1',
                    'NONE-V1', 'RP-MERKLE-SHA256-V1', 'local', 1,
                    'JWS-EDDSA-ED25519-V1', 'RP-SIGNED-PROOF-ZIP-V2',
                    'local-ed25519', 1, 1, ?, ?, 0)
                """)) {
            statement.setLong(1, id);
            statement.setLong(2, tenantId);
            statement.setLong(3, actorId);
            statement.setLong(4, actorId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    /**
     * Inserts one successful sanitized audit row linked to the same tenant policy identity.
     */
    private void insertSuccessAudit(Connection connection,
                                    long id,
                                    long policyId,
                                    long actorId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO tenant_crypto_policy_audit (
                    id, tenant_id, policy_id, policy_version, actor_id,
                    action, outcome, old_policy_fingerprint, new_policy_fingerprint,
                    failure_reason, deleted
                ) VALUES (
                    ?, 1, ?, 1, ?, 'CREATE', 'SUCCESS', NULL, ?, NULL, 0)
                """)) {
            statement.setLong(1, id);
            statement.setLong(2, policyId);
            statement.setLong(3, actorId);
            statement.setString(4, "a".repeat(64));
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    /**
     * Locks one policy row, optionally skipping a row already locked by another worker.
     */
    private boolean lockPolicy(Connection connection, boolean skipLocked) throws SQLException {
        String suffix = skipLocked ? " SKIP LOCKED" : "";
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT id FROM tenant_crypto_policy
                     WHERE tenant_id = 21 AND deleted = 0
                     FOR UPDATE
                     """ + suffix)) {
            return resultSet.next();
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
     * Executes a scalar string query.
     */
    private String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }
}
