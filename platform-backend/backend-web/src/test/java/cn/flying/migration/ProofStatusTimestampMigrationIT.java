package cn.flying.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用独立真实 MySQL 验证 proof 状态时间精度前向迁移及历史数据定向修复。
 */
@Testcontainers(disabledWithoutDocker = false)
class ProofStatusTimestampMigrationIT {

    private static final String VERSION_1_14 = "1.14.0";
    private static final String VERSION_1_15 = "1.15.0";
    private static final long TENANT_ID = 98_150_001L;
    private static final long BATCH_ID = 98_150_010L;
    private static final long KEY_RECORD_ID = 98_150_020L;
    private static final long DOUBLE_ANOMALY_ID = 98_150_101L;
    private static final long CREATE_EARLY_ID = 98_150_102L;
    private static final long UPDATE_EARLY_ID = 98_150_103L;
    private static final long LEGAL_ACTIVE_ID = 98_150_104L;
    private static final long LEGAL_REVOKED_ID = 98_150_105L;
    private static final long LEGAL_SUPERSEDED_ID = 98_150_106L;
    private static final long LEGAL_INVALID_ID = 98_150_107L;
    private static final long BOUNDARY_ID = 98_150_108L;
    private static final String KEY_ID = "migration-proof-status-key";
    private static final int KEY_VERSION = 1;
    private static final String SIGNATURE_ALGORITHM = "Ed25519";
    private static final String PUBLIC_KEY_SPKI = "cHJvb2Ytc3RhdHVzLW1pZ3JhdGlvbi1rZXk=";
    private static final String PUBLIC_KEY_FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final String ISSUED_AT = "2026-07-01 12:00:00.123";
    private static final String EARLY_TIME = "2026-07-01 12:00:00.000";
    private static final String LATER_CREATE_TIME = "2026-07-01 12:00:01.000";
    private static final String LATER_UPDATE_TIME = "2026-07-01 12:00:02.000";
    private static final String FORMATTED_ISSUED_AT = "2026-07-01 12:00:00.123000";
    private static final List<String> MILLISECOND_BOUNDARIES =
            List.of("001", "123", "499", "500", "999");

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("proof_status_timestamp_migration")
                    .withUsername("test")
                    .withPassword("test")
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("init-test-db.sql"),
                            "/docker-entrypoint-initdb.d/init-test-db.sql");

    /**
     * 验证从 V1.14 升级后列合同、历史定向修复和两种 SQL mode 下的毫秒边界均保持正确。
     */
    @Test
    void shouldMigrateProofStatusTimestampsWithoutOverwritingValidLifecycleState() throws SQLException {
        Flyway version114 = migrateTo(VERSION_1_14);
        assertCurrentVersion(version114, VERSION_1_14);

        List<HistoricalFixture> historicalFixtures = historicalFixtures();
        Map<Long, IssuanceSnapshot> snapshotsBeforeMigration = new LinkedHashMap<>();
        try (Connection connection = openConnection()) {
            configureUtcSession(connection);
            assertColumnPrecision(connection, "create_time", 0);
            assertColumnPrecision(connection, "update_time", 0);
            insertRequiredProofGraph(connection, historicalFixtures);
            for (HistoricalFixture fixture : historicalFixtures) {
                insertIssuance(connection, fixture);
                snapshotsBeforeMigration.put(fixture.id(), loadSnapshot(connection, fixture.id()));
            }

            IssuanceSnapshot anomalyBefore = snapshotsBeforeMigration.get(DOUBLE_ANOMALY_ID);
            assertThat(anomalyBefore.updateTime()).isLessThan(anomalyBefore.issuedAt());
            assertThat(countImpossibleTimeRows(connection)).isEqualTo(3);
        }

        Flyway version115 = migrateTo(VERSION_1_15);
        assertCurrentVersion(version115, VERSION_1_15);

        try (Connection connection = openConnection()) {
            configureUtcSession(connection);
            assertMigratedColumnContract(connection, "create_time", false);
            assertMigratedColumnContract(connection, "update_time", true);
            assertThat(countImpossibleTimeRows(connection)).isZero();

            IssuanceSnapshot doubleAnomaly = loadSnapshot(connection, DOUBLE_ANOMALY_ID);
            assertImmutableContractUnchanged(
                    snapshotsBeforeMigration.get(DOUBLE_ANOMALY_ID), doubleAnomaly);
            assertThat(doubleAnomaly.createTime()).isEqualTo(FORMATTED_ISSUED_AT);
            assertThat(doubleAnomaly.updateTime()).isEqualTo(FORMATTED_ISSUED_AT);

            IssuanceSnapshot createEarly = loadSnapshot(connection, CREATE_EARLY_ID);
            assertImmutableContractUnchanged(snapshotsBeforeMigration.get(CREATE_EARLY_ID), createEarly);
            assertThat(createEarly.createTime()).isEqualTo(FORMATTED_ISSUED_AT);
            assertThat(createEarly.updateTime())
                    .isEqualTo(snapshotsBeforeMigration.get(CREATE_EARLY_ID).updateTime());

            IssuanceSnapshot updateEarly = loadSnapshot(connection, UPDATE_EARLY_ID);
            assertImmutableContractUnchanged(snapshotsBeforeMigration.get(UPDATE_EARLY_ID), updateEarly);
            assertThat(updateEarly.createTime())
                    .isEqualTo(snapshotsBeforeMigration.get(UPDATE_EARLY_ID).createTime());
            assertThat(updateEarly.updateTime()).isEqualTo(FORMATTED_ISSUED_AT);

            for (long id : List.of(
                    LEGAL_ACTIVE_ID,
                    LEGAL_REVOKED_ID,
                    LEGAL_SUPERSEDED_ID,
                    LEGAL_INVALID_ID)) {
                assertThat(loadSnapshot(connection, id))
                        .as("合法生命周期快照不得被迁移改写，issuanceId=%s", id)
                        .isEqualTo(snapshotsBeforeMigration.get(id));
            }

            verifyMillisecondBoundariesAcrossSqlModes(connection);
        }
    }

    /**
     * 构造迁移前覆盖双异常、两个单向异常及四种合法状态的历史 fixture。
     *
     * @return 有确定时间和值语义的历史签发记录
     */
    private List<HistoricalFixture> historicalFixtures() {
        return List.of(
                fixture(DOUBLE_ANOMALY_ID, "ACTIVE", 1L, null, null, EARLY_TIME, EARLY_TIME),
                fixture(CREATE_EARLY_ID, "ACTIVE", 1L, null, null, EARLY_TIME, LATER_UPDATE_TIME),
                fixture(UPDATE_EARLY_ID, "ACTIVE", 1L, null, null, LATER_CREATE_TIME, EARLY_TIME),
                fixture(
                        LEGAL_ACTIVE_ID,
                        "ACTIVE",
                        1L,
                        null,
                        null,
                        LATER_CREATE_TIME,
                        LATER_UPDATE_TIME),
                fixture(
                        LEGAL_REVOKED_ID,
                        "REVOKED",
                        2L,
                        "migration revoked reason",
                        "2026-07-01 12:00:01.500",
                        LATER_CREATE_TIME,
                        LATER_UPDATE_TIME),
                fixture(
                        LEGAL_SUPERSEDED_ID,
                        "SUPERSEDED",
                        3L,
                        "migration superseded reason",
                        null,
                        LATER_CREATE_TIME,
                        LATER_UPDATE_TIME),
                fixture(
                        LEGAL_INVALID_ID,
                        "INVALID",
                        4L,
                        "migration invalid reason",
                        null,
                        LATER_CREATE_TIME,
                        LATER_UPDATE_TIME));
    }

    /**
     * 创建带固定签发时间和不可变签名字段的历史 fixture。
     */
    private HistoricalFixture fixture(
            long id,
            String status,
            long statusVersion,
            String statusReason,
            String revokedAt,
            String createTime,
            String updateTime
    ) {
        return new HistoricalFixture(
                id,
                status,
                statusVersion,
                statusReason,
                ISSUED_AT,
                revokedAt,
                createTime,
                updateTime);
    }

    /**
     * 使用显式 target 迁移到指定版本，避免测试意外消费后续迁移。
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
     * 断言 Flyway 当前版本与分阶段目标完全一致。
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
     * 固定当前连接为 UTC，隔离运行节点默认时区差异。
     */
    private void configureUtcSession(Connection connection) throws SQLException {
        execute(connection, "SET SESSION time_zone = '+00:00'");
    }

    /**
     * 断言迁移前目标列的实际 DATETIME 精度。
     */
    private void assertColumnPrecision(Connection connection, String columnName, int expectedPrecision)
            throws SQLException {
        ColumnMetadata metadata = loadColumnMetadata(connection, columnName);
        assertThat(metadata.columnType()).isEqualToIgnoringCase("datetime");
        assertThat(metadata.datetimePrecision()).isEqualTo(expectedPrecision);
    }

    /**
     * 断言迁移后目标列的精度、可空性、默认值和自动更新表达式。
     */
    private void assertMigratedColumnContract(
            Connection connection,
            String columnName,
            boolean autoUpdated
    ) throws SQLException {
        ColumnMetadata metadata = loadColumnMetadata(connection, columnName);
        assertThat(metadata.columnType()).isEqualToIgnoringCase("datetime(3)");
        assertThat(metadata.datetimePrecision()).isEqualTo(3);
        assertThat(metadata.nullable()).isEqualTo("NO");
        assertThat(metadata.columnDefault()).isEqualToIgnoringCase("CURRENT_TIMESTAMP(3)");
        String normalizedExtra = metadata.extra().toLowerCase(Locale.ROOT);
        if (autoUpdated) {
            assertThat(normalizedExtra).contains("on update current_timestamp(3)");
        } else {
            assertThat(normalizedExtra).doesNotContain("on update");
        }
    }

    /**
     * 从 INFORMATION_SCHEMA 读取目标列的完整迁移合同。
     */
    private ColumnMetadata loadColumnMetadata(Connection connection, String columnName) throws SQLException {
        String sql = """
                SELECT COLUMN_TYPE, DATETIME_PRECISION, IS_NULLABLE, COLUMN_DEFAULT, EXTRA
                  FROM INFORMATION_SCHEMA.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'proof_bundle_issuance'
                   AND COLUMN_NAME = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).as("目标列必须存在: %s", columnName).isTrue();
                return new ColumnMetadata(
                        resultSet.getString("COLUMN_TYPE"),
                        resultSet.getInt("DATETIME_PRECISION"),
                        resultSet.getString("IS_NULLABLE"),
                        resultSet.getString("COLUMN_DEFAULT"),
                        resultSet.getString("EXTRA"));
            }
        }
    }

    /**
     * 插入满足 V1.14 全部外键约束的 file、batch、leaf 与 signing key fixture。
     */
    private void insertRequiredProofGraph(
            Connection connection,
            List<HistoricalFixture> historicalFixtures
    ) throws SQLException {
        insertBatch(connection, historicalFixtures.size() + 1);
        insertSigningKey(connection);
        int leafIndex = 0;
        for (HistoricalFixture fixture : historicalFixtures) {
            insertFileAndLeaf(connection, fixture.id(), leafIndex++);
        }
        insertFileAndLeaf(connection, BOUNDARY_ID, leafIndex);
    }

    /**
     * 插入所有 leaf 共用的 attestation batch。
     */
    private void insertBatch(Connection connection, int leafCount) throws SQLException {
        String sql = """
                INSERT INTO attestation_batch(
                    id, tenant_id, batch_no, idempotency_key, merkle_root,
                    proof_algorithm, leaf_count, status, deleted
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, BATCH_ID);
            statement.setLong(2, TENANT_ID);
            statement.setString(3, "proof-status-time-migration");
            statement.setString(4, "proof-status-time-migration");
            statement.setString(5, hashFor(BATCH_ID));
            statement.setString(6, "SHA-256");
            statement.setInt(7, leafCount);
            statement.setString(8, "CONFIRMED");
            statement.setInt(9, 0);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    /**
     * 插入所有 issuance 共用且身份完整的 proof signing key。
     */
    private void insertSigningKey(Connection connection) throws SQLException {
        String sql = """
                INSERT INTO proof_signing_key(
                    id, key_id, key_version, signature_algorithm, public_key_spki,
                    public_key_fingerprint, status, first_seen_at, deleted
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, KEY_RECORD_ID);
            statement.setString(2, KEY_ID);
            statement.setInt(3, KEY_VERSION);
            statement.setString(4, SIGNATURE_ALGORITHM);
            statement.setString(5, PUBLIC_KEY_SPKI);
            statement.setString(6, PUBLIC_KEY_FINGERPRINT);
            statement.setString(7, "ACTIVE");
            statement.setString(8, ISSUED_AT);
            statement.setInt(9, 0);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    /**
     * 为一个 issuance 插入匹配 composite foreign key 的 file 与 attestation leaf。
     */
    private void insertFileAndLeaf(Connection connection, long id, int leafIndex) throws SQLException {
        String fileSql = """
                INSERT INTO file(
                    id, tenant_id, uid, file_name, file_hash, status, deleted,
                    create_time, version, is_latest, version_group_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(fileSql)) {
            statement.setLong(1, id);
            statement.setLong(2, TENANT_ID);
            statement.setString(3, "migration-user-" + id);
            statement.setString(4, "proof-status-migration-" + id + ".bin");
            statement.setString(5, hashFor(id));
            statement.setInt(6, 2);
            statement.setInt(7, 0);
            statement.setString(8, EARLY_TIME);
            statement.setInt(9, 1);
            statement.setInt(10, 1);
            statement.setLong(11, id);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }

        String leafSql = """
                INSERT INTO attestation_leaf(
                    id, tenant_id, batch_id, file_id, file_version, file_hash,
                    evidence_type, evidence_hash, chain_record_id, leaf_hash,
                    leaf_index, proof_path_json, proof_algorithm, deleted
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(leafSql)) {
            statement.setLong(1, id);
            statement.setLong(2, TENANT_ID);
            statement.setLong(3, BATCH_ID);
            statement.setLong(4, id);
            statement.setInt(5, 1);
            statement.setString(6, hashFor(id));
            statement.setString(7, "LEGACY_CHAIN_RECORD_ID");
            statement.setString(8, hashFor(id));
            statement.setString(9, "chain-record-" + id);
            statement.setString(10, hashFor(id + 1));
            statement.setInt(11, leafIndex);
            statement.setString(12, "[]");
            statement.setString(13, "SHA-256");
            statement.setInt(14, 0);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    /**
     * 插入包含完整状态、签名和时间字段的 proof issuance。
     */
    private void insertIssuance(Connection connection, HistoricalFixture fixture) throws SQLException {
        String sql = """
                INSERT INTO proof_bundle_issuance(
                    id, tenant_id, proof_id, file_id, file_version, leaf_id,
                    manifest_hash, manifest_json, signature_jws, signature_algorithm,
                    key_id, key_version, public_key_spki, public_key_fingerprint,
                    issued_status, status, status_version, status_reason,
                    issued_at, revoked_at, create_time, update_time, deleted
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, fixture.id());
            statement.setLong(2, TENANT_ID);
            statement.setString(3, "rp-proof-migration-" + fixture.id());
            statement.setLong(4, fixture.id());
            statement.setInt(5, 1);
            statement.setLong(6, fixture.id());
            statement.setString(7, hashFor(fixture.id()));
            statement.setString(8, "{\"fixtureId\":" + fixture.id() + "}");
            statement.setString(9, "header.payload.signature-" + fixture.id());
            statement.setString(10, SIGNATURE_ALGORITHM);
            statement.setString(11, KEY_ID);
            statement.setInt(12, KEY_VERSION);
            statement.setString(13, PUBLIC_KEY_SPKI);
            statement.setString(14, PUBLIC_KEY_FINGERPRINT);
            statement.setString(15, "ACTIVE");
            statement.setString(16, fixture.status());
            statement.setLong(17, fixture.statusVersion());
            statement.setString(18, fixture.statusReason());
            statement.setString(19, fixture.issuedAt());
            setNullableTimestamp(statement, 20, fixture.revokedAt());
            statement.setString(21, fixture.createTime());
            statement.setString(22, fixture.updateTime());
            statement.setInt(23, 0);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    /**
     * 为 nullable DATETIME 参数设置文本时间或 SQL NULL。
     */
    private void setNullableTimestamp(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            statement.setString(index, value);
        }
    }

    /**
     * 读取包含固定六位小数文本和全部状态签名字段的 issuance 快照。
     */
    private IssuanceSnapshot loadSnapshot(Connection connection, long id) throws SQLException {
        String sql = """
                SELECT DATE_FORMAT(issued_at, '%Y-%m-%d %H:%i:%s.%f') AS issued_at_text,
                       DATE_FORMAT(revoked_at, '%Y-%m-%d %H:%i:%s.%f') AS revoked_at_text,
                       DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s.%f') AS create_time_text,
                       DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s.%f') AS update_time_text,
                       issued_status, status, status_version, status_reason,
                       manifest_hash, manifest_json, signature_jws, signature_algorithm,
                       key_id, key_version, public_key_spki, public_key_fingerprint, deleted
                  FROM proof_bundle_issuance
                 WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).as("issuance 必须存在: %s", id).isTrue();
                return new IssuanceSnapshot(
                        resultSet.getString("issued_at_text"),
                        resultSet.getString("revoked_at_text"),
                        resultSet.getString("create_time_text"),
                        resultSet.getString("update_time_text"),
                        resultSet.getString("issued_status"),
                        resultSet.getString("status"),
                        resultSet.getLong("status_version"),
                        resultSet.getString("status_reason"),
                        resultSet.getString("manifest_hash"),
                        resultSet.getString("manifest_json"),
                        resultSet.getString("signature_jws"),
                        resultSet.getString("signature_algorithm"),
                        resultSet.getString("key_id"),
                        resultSet.getInt("key_version"),
                        resultSet.getString("public_key_spki"),
                        resultSet.getString("public_key_fingerprint"),
                        resultSet.getInt("deleted"));
            }
        }
    }

    /**
     * 断言迁移仅能修改 create_time/update_time，不得漂移其余身份和状态合同。
     */
    private void assertImmutableContractUnchanged(
            IssuanceSnapshot before,
            IssuanceSnapshot after
    ) {
        assertThat(after.issuedAt()).isEqualTo(before.issuedAt());
        assertThat(after.revokedAt()).isEqualTo(before.revokedAt());
        assertThat(after.issuedStatus()).isEqualTo(before.issuedStatus());
        assertThat(after.status()).isEqualTo(before.status());
        assertThat(after.statusVersion()).isEqualTo(before.statusVersion());
        assertThat(after.statusReason()).isEqualTo(before.statusReason());
        assertThat(after.manifestHash()).isEqualTo(before.manifestHash());
        assertThat(after.manifestJson()).isEqualTo(before.manifestJson());
        assertThat(after.signatureJws()).isEqualTo(before.signatureJws());
        assertThat(after.signatureAlgorithm()).isEqualTo(before.signatureAlgorithm());
        assertThat(after.keyId()).isEqualTo(before.keyId());
        assertThat(after.keyVersion()).isEqualTo(before.keyVersion());
        assertThat(after.publicKeySpki()).isEqualTo(before.publicKeySpki());
        assertThat(after.publicKeyFingerprint()).isEqualTo(before.publicKeyFingerprint());
        assertThat(after.deleted()).isEqualTo(before.deleted());
    }

    /**
     * 统计仍违反 create/update 不早于签发时间的不可能历史行。
     */
    private int countImpossibleTimeRows(Connection connection) throws SQLException {
        String sql = """
                SELECT COUNT(*)
                  FROM proof_bundle_issuance
                 WHERE create_time < issued_at
                    OR update_time < issued_at
                """;
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    /**
     * 在默认 rounding 与 TIME_TRUNCATE_FRACTIONAL 模式下验证五个三位毫秒边界。
     */
    private void verifyMillisecondBoundariesAcrossSqlModes(Connection connection) throws SQLException {
        String originalSqlMode = querySingleString(connection, "SELECT @@SESSION.sql_mode");
        try {
            verifyMillisecondBoundaries(connection, false);
            verifyMillisecondBoundaries(connection, true);
        } finally {
            setSessionSqlMode(connection, originalSqlMode);
        }
    }

    /**
     * 切换当前连接的小数秒处理模式并逐个验证三位毫秒往返。
     */
    private void verifyMillisecondBoundaries(Connection connection, boolean truncateFractional)
            throws SQLException {
        String currentSqlMode = querySingleString(connection, "SELECT @@SESSION.sql_mode");
        setSessionSqlMode(connection, sqlModeWithFractionalTruncation(currentSqlMode, truncateFractional));

        String activeMode = querySingleString(connection, "SELECT @@SESSION.sql_mode")
                .toUpperCase(Locale.ROOT);
        if (truncateFractional) {
            assertThat(activeMode).contains("TIME_TRUNCATE_FRACTIONAL");
        } else {
            assertThat(activeMode).doesNotContain("TIME_TRUNCATE_FRACTIONAL");
        }

        for (String millisecond : MILLISECOND_BOUNDARIES) {
            deleteBoundaryIssuance(connection);
            String value = "2026-07-02 00:00:00." + millisecond;
            insertIssuance(
                    connection,
                    new HistoricalFixture(
                            BOUNDARY_ID,
                            "ACTIVE",
                            1L,
                            null,
                            value,
                            null,
                            value,
                            value));
            IssuanceSnapshot snapshot = loadSnapshot(connection, BOUNDARY_ID);
            String expected = value + "000";
            assertThat(snapshot.issuedAt()).isEqualTo(expected);
            assertThat(snapshot.createTime()).isEqualTo(expected);
            assertThat(snapshot.updateTime()).isEqualTo(expected);
        }
        verifyFractionalModeProbe(connection, truncateFractional);
        deleteBoundaryIssuance(connection);
    }

    /**
     * 在 Java 中切换 TIME_TRUNCATE_FRACTIONAL，避免测试用户依赖 MySQL sys schema 的例程权限。
     */
    private String sqlModeWithFractionalTruncation(String sqlMode, boolean enabled) {
        List<String> modes = Arrays.stream(sqlMode.split(","))
                .map(String::trim)
                .filter(mode -> !mode.isEmpty())
                .filter(mode -> !"TIME_TRUNCATE_FRACTIONAL".equalsIgnoreCase(mode))
                .collect(Collectors.toCollection(ArrayList::new));
        if (enabled) {
            modes.add("TIME_TRUNCATE_FRACTIONAL");
        }
        return String.join(",", modes);
    }

    /**
     * 使用四位小数输入校准当前 SQL mode，证明 rounding 与 truncation 分支确实生效。
     */
    private void verifyFractionalModeProbe(Connection connection, boolean truncateFractional)
            throws SQLException {
        deleteBoundaryIssuance(connection);
        String value = "2026-07-02 00:00:00.1235";
        insertIssuance(
                connection,
                new HistoricalFixture(
                        BOUNDARY_ID,
                        "ACTIVE",
                        1L,
                        null,
                        value,
                        null,
                        value,
                        value));
        String expected = truncateFractional
                ? "2026-07-02 00:00:00.123000"
                : "2026-07-02 00:00:00.124000";
        IssuanceSnapshot snapshot = loadSnapshot(connection, BOUNDARY_ID);
        assertThat(snapshot.issuedAt()).isEqualTo(expected);
        assertThat(snapshot.createTime()).isEqualTo(expected);
        assertThat(snapshot.updateTime()).isEqualTo(expected);
    }

    /**
     * 删除可复用的边界 issuance，保留其真实外键图。
     */
    private void deleteBoundaryIssuance(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM proof_bundle_issuance WHERE id = ?")) {
            statement.setLong(1, BOUNDARY_ID);
            statement.executeUpdate();
        }
    }

    /**
     * 恢复当前连接原始 SQL mode，不修改全局数据库配置。
     */
    private void setSessionSqlMode(Connection connection, String sqlMode) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SET SESSION sql_mode = ?")) {
            statement.setString(1, sqlMode);
            statement.execute();
        }
    }

    /**
     * 执行不返回结果集的固定会话级 SQL。
     */
    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * 查询单个非空字符串值。
     */
    private String querySingleString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    /**
     * 构造满足数据库长度合同的确定性 SHA-256 文本 fixture。
     */
    private String hashFor(long value) {
        return "sha256:" + String.format(Locale.ROOT, "%064x", value);
    }

    private record HistoricalFixture(
            long id,
            String status,
            long statusVersion,
            String statusReason,
            String issuedAt,
            String revokedAt,
            String createTime,
            String updateTime
    ) {
    }

    private record ColumnMetadata(
            String columnType,
            int datetimePrecision,
            String nullable,
            String columnDefault,
            String extra
    ) {
    }

    private record IssuanceSnapshot(
            String issuedAt,
            String revokedAt,
            String createTime,
            String updateTime,
            String issuedStatus,
            String status,
            long statusVersion,
            String statusReason,
            String manifestHash,
            String manifestJson,
            String signatureJws,
            String signatureAlgorithm,
            String keyId,
            int keyVersion,
            String publicKeySpki,
            String publicKeyFingerprint,
            int deleted
    ) {
    }
}
