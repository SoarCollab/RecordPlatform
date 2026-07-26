package cn.flying.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.FlywayException;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 MySQL 验证 V1.17 迁移预检、active 唯一键和 claim SQL 围栏。
 */
@Testcontainers(disabledWithoutDocker = true)
class ManifestBackfillGovernanceMigrationIT {

    private static final String VERSION_1_16 = "1.16.0";
    private static final String VERSION_1_17 = "1.17.0";
    private static final long TENANT_ID = 98_170_001L;
    private static final long FILE_ID = 98_170_010L;
    private static final long RUN_ID = 98_170_020L;

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("manifest_backfill_governance")
                    .withUsername("test")
                    .withPassword("test")
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("init-test-db.sql"),
                            "/docker-entrypoint-initdb.d/init-test-db.sql");

    /**
     * 验证重复 ACTIVE 会阻断迁移，清理后唯一键和 claim/lease 围栏在真实 MySQL 生效。
     */
    @Test
    void shouldEnforcePreflightActiveUniquenessAndClaimFences() throws SQLException {
        Flyway version116 = migrateTo(VERSION_1_16);
        assertCurrentVersion(version116, VERSION_1_16);

        try (Connection connection = openConnection()) {
            insertManifest(connection, 98_170_101L, "sha256:manifest-a", "ACTIVE");
            insertManifest(connection, 98_170_102L, "sha256:manifest-b", "ACTIVE");
        }

        assertThatThrownBy(() -> migrateTo(VERSION_1_17))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("duplicate active chunk manifests require manual review");

        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM file_chunk_manifest WHERE id = 98170102");
        }
        repair();

        Flyway version117 = migrateTo(VERSION_1_17);
        assertCurrentVersion(version117, VERSION_1_17);

        try (Connection connection = openConnection()) {
            assertThatThrownBy(() -> insertManifest(
                    connection, 98_170_103L, "sha256:manifest-c", "ACTIVE"))
                    .isInstanceOf(SQLException.class);

            execute(connection, """
                    UPDATE file_chunk_manifest
                       SET status = 'SUPERSEDED'
                     WHERE id = 98170101
                    """);
            insertManifest(connection, 98_170_103L, "sha256:manifest-c", "ACTIVE");
            insertManifest(connection, 98_170_104L, "sha256:manifest-d", "SUPERSEDED");
            assertThat(countActiveManifests(connection)).isEqualTo(1);

            insertRunAndClaimItems(connection);
            assertClaimQueryUsesDeterministicIndex(connection);
            assertClaimCompletionFence(connection);
            assertSkipLockedClaimsDistinctRows();
        }
    }

    /**
     * 把测试库迁移到精确目标版本。
     */
    private Flyway migrateTo(String targetVersion) {
        Flyway flyway = configuredFlyway(targetVersion);
        flyway.migrate();
        return flyway;
    }

    /**
     * 清理预期失败迁移留下的 Flyway 历史记录，允许修复数据后原地重试。
     */
    private void repair() {
        configuredFlyway(VERSION_1_17).repair();
    }

    /**
     * 构造使用同一真实 MySQL 数据源的 Flyway 实例。
     */
    private Flyway configuredFlyway(String targetVersion) {
        return Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(targetVersion))
                .load();
    }

    /**
     * 断言数据库当前 Flyway 版本等于目标版本。
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
     * 插入一条最小 manifest fixture，覆盖 ACTIVE 与历史状态。
     */
    private void insertManifest(
            Connection connection,
            long manifestId,
            String manifestHash,
            String status
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO file_chunk_manifest (
                    id, tenant_id, file_id, file_version, file_hash, schema_id,
                    manifest_hash, hash_algorithm, chunk_size, chunk_count, total_size,
                    encryption_algorithm, storage_backend, manifest_json, status, deleted
                ) VALUES (?, ?, ?, 1, 'sha256:file', 'cn.flying.chunk-manifest.v1',
                          ?, 'SHA-256', 8, 1, 8, 'NONE', 'S3', JSON_OBJECT(), ?, 0)
                """)) {
            statement.setLong(1, manifestId);
            statement.setLong(2, TENANT_ID);
            statement.setLong(3, FILE_ID);
            statement.setString(4, manifestHash);
            statement.setString(5, status);
            statement.executeUpdate();
        }
    }

    /**
     * 统计唯一文件当前可见的 ACTIVE manifest 数量。
     */
    private long countActiveManifests(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT COUNT(*)
                  FROM file_chunk_manifest
                 WHERE tenant_id = ? AND file_id = ? AND status = 'ACTIVE' AND deleted = 0
                """)) {
            statement.setLong(1, TENANT_ID);
            statement.setLong(2, FILE_ID);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    /**
     * 插入一个 apply run 和三条 claim fixture。
     */
    private void insertRunAndClaimItems(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO manifest_backfill_run (
                    id, tenant_id, mode, status, snapshot_version, created_by, deleted
                ) VALUES (98170020, 98170001, 'APPLY', 'APPLYING',
                          'manifest-backfill-snapshot.v1', 98170099, 0)
                """);
        execute(connection, """
                INSERT INTO manifest_backfill_item (
                    id, run_id, tenant_id, file_id, file_version, status, classification,
                    reason_code, retryable, evidence_digest, claim_token, lease_expires_at,
                    attempt_count, deleted
                ) VALUES
                    (98170201, 98170020, 98170001, 98170301, 1, 'RUNNING', 'BACKFILLABLE',
                     'BACKFILLABLE_EVIDENCE', 0, 'sha256:evidence-1', 'claim-a',
                     DATE_ADD(NOW(), INTERVAL 5 MINUTE), 1, 0),
                    (98170202, 98170020, 98170001, 98170302, 1, 'PENDING', 'BACKFILLABLE',
                     'BACKFILLABLE_EVIDENCE', 0, 'sha256:evidence-2', NULL, NULL, 0, 0),
                    (98170203, 98170020, 98170001, 98170303, 1, 'PENDING', 'BACKFILLABLE',
                     'BACKFILLABLE_EVIDENCE', 0, 'sha256:evidence-3', NULL, NULL, 0, 0)
                """);
    }

    /**
     * 验证错误租户、错误 token 均不能完成 claim，精确 owner 只能成功一次。
     */
    private void assertClaimCompletionFence(Connection connection) throws SQLException {
        String completionSql = """
                UPDATE manifest_backfill_item
                   SET status = 'BACKFILLED', claim_token = NULL, lease_expires_at = NULL
                 WHERE tenant_id = ? AND run_id = ? AND id = 98170201
                   AND claim_token = ? AND status = 'RUNNING'
                   AND lease_expires_at > NOW() AND deleted = 0
                """;
        assertThat(executeClaimCompletion(connection, completionSql, TENANT_ID + 1, "claim-a")).isZero();
        assertThat(executeClaimCompletion(connection, completionSql, TENANT_ID, "claim-b")).isZero();
        assertThat(executeClaimCompletion(connection, completionSql, TENANT_ID, "claim-a")).isEqualTo(1);
        assertThat(executeClaimCompletion(connection, completionSql, TENANT_ID, "claim-a")).isZero();
    }

    /**
     * 执行一次 tenant/run/item/token/lease 完成 CAS。
     */
    private int executeClaimCompletion(
            Connection connection,
            String sql,
            long tenantId,
            String claimToken
    ) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, tenantId);
            statement.setLong(2, RUN_ID);
            statement.setString(3, claimToken);
            return statement.executeUpdate();
        }
    }

    /**
     * 验证两个并发事务的 SKIP LOCKED 查询不会领取同一候选行。
     */
    private void assertSkipLockedClaimsDistinctRows() throws SQLException {
        try (Connection first = openConnection(); Connection second = openConnection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            long firstId = selectNextPendingForUpdate(first);
            long secondId = selectNextPendingForUpdate(second);
            assertThat(Set.of(firstId, secondId))
                    .containsExactlyInAnyOrder(98_170_202L, 98_170_203L);
            first.rollback();
            second.rollback();
        }
    }

    /**
     * 验证 claim 锁定读按确定性候选索引扫描，避免 filesort 扩大锁定范围。
     */
    private void assertClaimQueryUsesDeterministicIndex(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                EXPLAIN SELECT id
                  FROM manifest_backfill_item FORCE INDEX (idx_manifest_backfill_item_claim)
                 WHERE run_id = ?
                   AND tenant_id = ?
                   AND deleted = 0
                   AND classification = 'BACKFILLABLE'
                   AND attempt_count < 3
                   AND (
                        status = 'PENDING'
                        OR (status = 'FAILED' AND retryable = 1
                            AND (next_retry_at IS NULL OR next_retry_at <= NOW()))
                        OR (status = 'RUNNING' AND lease_expires_at IS NOT NULL
                            AND lease_expires_at <= NOW())
                   )
                 ORDER BY file_id, id
                 LIMIT 1
                """)) {
            statement.setLong(1, RUN_ID);
            statement.setLong(2, TENANT_ID);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString("key"))
                        .isEqualTo("idx_manifest_backfill_item_claim");
                assertThat(resultSet.getString("Extra"))
                        .doesNotContain("Using filesort");
            }
        }
    }

    /**
     * 锁定并返回下一条 pending item，跳过其他事务已持有的行锁。
     */
    private long selectNextPendingForUpdate(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT id
                  FROM manifest_backfill_item FORCE INDEX (idx_manifest_backfill_item_claim)
                 WHERE run_id = ?
                   AND tenant_id = ?
                   AND deleted = 0
                   AND classification = 'BACKFILLABLE'
                   AND attempt_count < 3
                   AND (
                        status = 'PENDING'
                        OR (status = 'FAILED' AND retryable = 1
                            AND (next_retry_at IS NULL OR next_retry_at <= NOW()))
                        OR (status = 'RUNNING' AND lease_expires_at IS NOT NULL
                            AND lease_expires_at <= NOW())
                   )
                 ORDER BY file_id, id
                 LIMIT 1
                 FOR UPDATE SKIP LOCKED
                """)) {
            statement.setLong(1, RUN_ID);
            statement.setLong(2, TENANT_ID);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getLong(1);
            }
        }
    }

    /**
     * 执行无参数 SQL fixture。
     */
    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
