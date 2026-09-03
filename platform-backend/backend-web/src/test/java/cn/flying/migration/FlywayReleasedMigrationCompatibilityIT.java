package cn.flying.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the released Flyway compatibility matrix against a real MySQL 8 database.
 */
@Testcontainers(disabledWithoutDocker = false)
class FlywayReleasedMigrationCompatibilityIT {

    private static final String LATEST_VERSION = "1.21.0";
    private static final String RELEASED_VERSION = "1.7.0";
    private static final String RELEASED_V1_SHA256 =
            "2d62aa70b0f58851579db62034ad12556409201a3cd2e7ad036d709348150645";
    private static final String KNOWN_REWRITTEN_V1_SHA256 =
            "c11f6518144797c7206ce987c9afeba0ecec491acca9dde4f684acee45f9b979";
    private static final String KNOWN_REWRITTEN_NICKNAME_SHA256 =
            "72e68808690ae1a44f22181349d1bae83bae3a7466898d02e5b5d6ef798d2b49";
    private static final String KNOWN_REWRITTEN_SOFT_DELETE_SHA256 =
            "4aa571d76a83325eeb7d41cc95a267f7896d4f11f39102af85113461897ead6a";
    private static final String KNOWN_REWRITTEN_INTEGRITY_SHA256 =
            "fcf6750113ce50ab50ebcc398709636e4dd97b7f75bc2b7a041feafbe7152584";
    private static final int KNOWN_REWRITTEN_V1_FLYWAY_CHECKSUM = 1043684703;
    private static final int KNOWN_REWRITTEN_NICKNAME_FLYWAY_CHECKSUM = 529309880;
    private static final int KNOWN_REWRITTEN_SOFT_DELETE_FLYWAY_CHECKSUM = 1607980540;
    private static final int KNOWN_REWRITTEN_INTEGRITY_FLYWAY_CHECKSUM = 2100652293;
    private static final int KNOWN_REWRITTEN_MIGRATION_COUNT = 40;

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("flyway_release_compatibility")
                    .withUsername("test")
                    .withPassword("test");

    @TempDir
    Path tempDir;

    /**
     * Resets the isolated schema before every compatibility scenario.
     */
    @BeforeEach
    void cleanDatabase() {
        configuredFlyway(null, "classpath:db/migration", true).clean();
    }

    /**
     * Proves fresh and v0.0.2 upgrade paths reach the same reviewed final schema.
     */
    @Test
    @DisplayName("should produce the same schema for fresh and v0.0.2 upgrade paths")
    void shouldProduceSameSchemaForFreshAndReleasedUpgradePaths() throws IOException, SQLException {
        Flyway freshFlyway = configuredFlyway(null, "classpath:db/migration", false);
        freshFlyway.migrate();
        assertCurrentVersion(freshFlyway, LATEST_VERSION);
        assertTrue(freshFlyway.validateWithResult().validationSuccessful);
        SchemaContract freshContract = readSchemaContract();

        configuredFlyway(null, "classpath:db/migration", true).clean();

        Flyway releasedFlyway = configuredFlyway(RELEASED_VERSION, "classpath:db/migration", false);
        releasedFlyway.migrate();
        assertCurrentVersion(releasedFlyway, RELEASED_VERSION);
        assertEquals(18, countAppliedVersionedMigrations());
        assertEquals(RELEASED_V1_SHA256, sha256(resolveMigration("V1.0.0__init_schema.sql")));

        Flyway upgradeFlyway = configuredFlyway(null, "classpath:db/migration", false);
        upgradeFlyway.migrate();
        assertCurrentVersion(upgradeFlyway, LATEST_VERSION);
        assertTrue(upgradeFlyway.validateWithResult().validationSuccessful);
        SchemaContract upgradedContract = readSchemaContract();

        assertEquals(freshContract, upgradedContract);
        assertFinalSchemaContract(upgradedContract);
    }

    /**
     * Proves the complete known rewritten and renumbered history fails validation without silent repair.
     */
    @Test
    @DisplayName("should fail fast for the complete known rewritten history without automatic repair")
    void shouldFailFastForKnownRewrittenChecksumWithoutAutomaticRepair() throws IOException, SQLException {
        Path rewrittenLocation = createKnownRewrittenMigrationLocation();
        Flyway rewrittenFlyway = configuredFlyway(
                null,
                "filesystem:" + rewrittenLocation.toAbsolutePath(),
                false);
        rewrittenFlyway.migrate();
        assertCurrentVersion(rewrittenFlyway, LATEST_VERSION);
        SchemaContract rewrittenContract = readSchemaContract();
        assertFinalSchemaContract(rewrittenContract);
        assertEquals(
                new AppliedMigration("V1.0.0__init_schema.sql", KNOWN_REWRITTEN_V1_FLYWAY_CHECKSUM),
                readAppliedMigration("1.0.0"));
        assertFalse(isAppliedVersion("1.0.1"));
        assertEquals(
                new AppliedMigration(
                        "V1.5.0__add_account_nickname.sql",
                        KNOWN_REWRITTEN_NICKNAME_FLYWAY_CHECKSUM),
                readAppliedMigration("1.5.0"));
        assertEquals(
                new AppliedMigration(
                        "V1.7.0__add_soft_delete_columns.sql",
                        KNOWN_REWRITTEN_SOFT_DELETE_FLYWAY_CHECKSUM),
                readAppliedMigration("1.7.0"));
        assertEquals(
                new AppliedMigration("V1.7.3__integrity_alert.sql", KNOWN_REWRITTEN_INTEGRITY_FLYWAY_CHECKSUM),
                readAppliedMigration("1.7.3"));
        assertEquals(KNOWN_REWRITTEN_MIGRATION_COUNT, countAppliedVersionedMigrations());

        Flyway canonicalFlyway = configuredFlyway(null, "classpath:db/migration", false);
        FlywayValidateException exception = assertThrows(FlywayValidateException.class, canonicalFlyway::migrate);

        assertTrue(exception.getMessage().contains("Migration checksum mismatch for migration version 1.0.0"));
        assertEquals(
                new AppliedMigration("V1.0.0__init_schema.sql", KNOWN_REWRITTEN_V1_FLYWAY_CHECKSUM),
                readAppliedMigration("1.0.0"));
        assertFalse(isAppliedVersion("1.0.1"));
        assertEquals(
                new AppliedMigration(
                        "V1.5.0__add_account_nickname.sql",
                        KNOWN_REWRITTEN_NICKNAME_FLYWAY_CHECKSUM),
                readAppliedMigration("1.5.0"));
        assertEquals(
                new AppliedMigration(
                        "V1.7.0__add_soft_delete_columns.sql",
                        KNOWN_REWRITTEN_SOFT_DELETE_FLYWAY_CHECKSUM),
                readAppliedMigration("1.7.0"));
        assertEquals(
                new AppliedMigration("V1.7.3__integrity_alert.sql", KNOWN_REWRITTEN_INTEGRITY_FLYWAY_CHECKSUM),
                readAppliedMigration("1.7.3"));
        assertEquals(KNOWN_REWRITTEN_MIGRATION_COUNT, countAppliedVersionedMigrations());
        assertEquals(rewrittenContract, readSchemaContract());
    }

    /**
     * Builds a Flyway instance for the isolated MySQL database.
     *
     * @param targetVersion optional migration target
     * @param location migration location
     * @param cleanEnabled whether clean is enabled for test reset
     * @return configured Flyway instance
     */
    private Flyway configuredFlyway(String targetVersion, String location, boolean cleanEnabled) {
        var builder = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations(location)
                .validateOnMigrate(true)
                .cleanDisabled(!cleanEnabled);
        if (targetVersion != null) {
            builder.target(MigrationVersion.fromVersion(targetVersion));
        }
        return builder.load();
    }

    /**
     * Recreates the exact rewritten V1 bytes previously present on main.
     *
     * @param releasedSql v0.0.2 V1 bytes
     * @return known rewritten development variant
     */
    private String rewriteReleasedV1AsKnownDevelopmentVariant(String releasedSql) {
        String rewritten = releasedSql
                .replace(
                        "`transaction_hash`     VARCHAR(255) DEFAULT NULL COMMENT 'Blockchain tx hash',",
                        "`contract_hash`        VARCHAR(255) DEFAULT NULL COMMENT 'Blockchain tx hash',")
                .replace(
                        "CREATE PROCEDURE IF NOT EXISTS `proc_clean_processed_messages`(IN retention_days INT)",
                        "DROP PROCEDURE IF EXISTS `proc_clean_processed_messages` //\n"
                                + "CREATE PROCEDURE `proc_clean_processed_messages`(IN retention_days INT)")
                .replace(
                        "CREATE PROCEDURE IF NOT EXISTS `proc_clean_old_operation_logs`(IN days INT)",
                        "DROP PROCEDURE IF EXISTS `proc_clean_old_operation_logs` //\n"
                                + "CREATE PROCEDURE `proc_clean_old_operation_logs`(IN days INT)");
        assertFalse(rewritten.equals(releasedSql));
        return rewritten;
    }

    /**
     * Builds the exact migration tree formerly present on main, including all renumbered release files.
     *
     * @return filesystem location containing the known rewritten history
     */
    private Path createKnownRewrittenMigrationLocation() throws IOException {
        Path rewrittenLocation = tempDir.resolve("rewritten-migrations");
        Files.createDirectories(rewrittenLocation);
        Path migrationDir = resolveMigrationDir();

        try (var stream = Files.list(migrationDir)) {
            for (Path migration : stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("V"))
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .toList()) {
                String filename = migration.getFileName().toString();
                switch (filename) {
                    case "V1.0.0__init_schema.sql" -> Files.writeString(
                            rewrittenLocation.resolve(filename),
                            rewriteReleasedV1AsKnownDevelopmentVariant(Files.readString(migration)),
                            StandardCharsets.UTF_8);
                    case "V1.0.1__add_account_nickname.sql" -> {
                        // The known rewritten tree moved these exact bytes to V1.5.0.
                    }
                    case "V1.5.0__integrity_alert.sql" -> Files.writeString(
                            rewrittenLocation.resolve("V1.5.0__add_account_nickname.sql"),
                            Files.readString(resolveMigration("V1.0.1__add_account_nickname.sql")),
                            StandardCharsets.UTF_8);
                    case "V1.7.0__add_soft_delete_columns.sql" -> Files.writeString(
                            rewrittenLocation.resolve(filename),
                            rewriteReleasedSoftDeleteAsKnownDevelopmentVariant(Files.readString(migration)),
                            StandardCharsets.UTF_8);
                    default -> Files.copy(migration, rewrittenLocation.resolve(filename));
                }
            }
        }

        Path rewrittenIntegrity = rewrittenLocation.resolve("V1.7.3__integrity_alert.sql");
        Files.writeString(
                rewrittenIntegrity,
                rewriteReleasedIntegrityAsKnownDevelopmentVariant(
                        Files.readString(resolveMigration("V1.5.0__integrity_alert.sql"))),
                StandardCharsets.UTF_8);

        assertEquals(KNOWN_REWRITTEN_V1_SHA256,
                sha256(rewrittenLocation.resolve("V1.0.0__init_schema.sql")));
        assertEquals(KNOWN_REWRITTEN_NICKNAME_SHA256,
                sha256(rewrittenLocation.resolve("V1.5.0__add_account_nickname.sql")));
        assertEquals(KNOWN_REWRITTEN_SOFT_DELETE_SHA256,
                sha256(rewrittenLocation.resolve("V1.7.0__add_soft_delete_columns.sql")));
        assertEquals(KNOWN_REWRITTEN_INTEGRITY_SHA256, sha256(rewrittenIntegrity));
        return rewrittenLocation;
    }

    /**
     * Removes the integrity-alert additions that were absent from the known rewritten V1.7.0.
     *
     * @param releasedSql v0.0.2 V1.7.0 bytes
     * @return known rewritten V1.7.0 bytes
     */
    private String rewriteReleasedSoftDeleteAsKnownDevelopmentVariant(String releasedSql) {
        String integrityAlertBlock = "-- integrity_alert: add deleted + update_time\n"
                + "ALTER TABLE `integrity_alert`\n"
                + "    ADD COLUMN `deleted`     TINYINT  NOT NULL DEFAULT 0 "
                + "COMMENT '逻辑删除标记 0=正常 1=已删除' AFTER `note`,\n"
                + "    ADD COLUMN `update_time` DATETIME NULL DEFAULT NULL COMMENT '更新时间' AFTER `create_time`;\n\n";
        String rewritten = releasedSql.replace(integrityAlertBlock, "");
        assertFalse(rewritten.equals(releasedSql));
        return rewritten;
    }

    /**
     * Adds the columns that the known rewritten tree embedded in its renumbered V1.7.3 file.
     *
     * @param releasedSql v0.0.2 V1.5.0 integrity table bytes
     * @return known rewritten V1.7.3 bytes
     */
    private String rewriteReleasedIntegrityAsKnownDevelopmentVariant(String releasedSql) {
        String rewritten = releasedSql.replace(
                "    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,\n",
                "    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,\n"
                        + "    `update_time` DATETIME NULL DEFAULT NULL COMMENT 'Update time',\n"
                        + "    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT 'Soft delete flag',\n");
        assertFalse(rewritten.equals(releasedSql));
        return rewritten;
    }

    /**
     * Reads the final columns and routine bodies that the compatibility paths must preserve.
     *
     * @return final schema contract
     */
    private SchemaContract readSchemaContract() throws SQLException {
        return new SchemaContract(
                columnExists("file", "transaction_hash"),
                columnExists("file", "contract_hash"),
                columnExists("account", "nickname"),
                columnExists("integrity_alert", "deleted"),
                columnExists("integrity_alert", "update_time"),
                readRoutineDefinition("proc_clean_processed_messages"),
                readRoutineDefinition("proc_clean_old_operation_logs"));
    }

    /**
     * Asserts the final hash, nickname, integrity, and cleanup-routine contracts.
     *
     * @param contract schema contract to verify
     */
    private void assertFinalSchemaContract(SchemaContract contract) {
        assertTrue(contract.transactionHash());
        assertFalse(contract.contractHash());
        assertTrue(contract.nickname());
        assertTrue(contract.integrityDeleted());
        assertTrue(contract.integrityUpdateTime());
        assertNotNull(contract.processedMessagesRoutine());
        assertNotNull(contract.operationLogsRoutine());
        assertTrue(contract.processedMessagesRoutine().contains("DELETE FROM processed_message"));
        assertTrue(contract.operationLogsRoutine().contains("DELETE FROM sys_operation_log"));
    }

    /**
     * Reads whether a column exists in the isolated schema.
     *
     * @param table table name
     * @param column column name
     * @return true when exactly one column exists
     */
    private boolean columnExists(String table, String column) throws SQLException {
        try (Connection connection = openConnection();
             var statement = connection.prepareStatement("""
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

    /**
     * Reads a stored routine body from MySQL metadata.
     *
     * @param routineName routine name
     * @return routine definition or null
     */
    private String readRoutineDefinition(String routineName) throws SQLException {
        try (Connection connection = openConnection();
             var statement = connection.prepareStatement("""
                     SELECT ROUTINE_DEFINITION
                       FROM INFORMATION_SCHEMA.ROUTINES
                      WHERE ROUTINE_SCHEMA = DATABASE() AND ROUTINE_NAME = ?
                     """)) {
            statement.setString(1, routineName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    /**
     * Reads one applied Flyway script and checksum.
     *
     * @param version Flyway history version
     * @return stored script and checksum
     */
    private AppliedMigration readAppliedMigration(String version) throws SQLException {
        try (Connection connection = openConnection();
             var statement = connection.prepareStatement("""
                     SELECT script, checksum
                       FROM flyway_schema_history
                      WHERE version = ? AND success = 1
                     """)) {
            statement.setString(1, version);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return new AppliedMigration(resultSet.getString(1), resultSet.getInt(2));
            }
        }
    }

    /**
     * Reads whether a successful version exists in Flyway history.
     *
     * @param version Flyway history version
     * @return true when the version was applied successfully
     */
    private boolean isAppliedVersion(String version) throws SQLException {
        try (Connection connection = openConnection();
             var statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                       FROM flyway_schema_history
                      WHERE version = ? AND success = 1
                     """)) {
            statement.setString(1, version);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) > 0;
            }
        }
    }

    /**
     * Counts successful versioned migrations in Flyway history.
     *
     * @return successful versioned migration count
     */
    private int countAppliedVersionedMigrations() throws SQLException {
        try (Connection connection = openConnection();
             var statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT COUNT(*)
                       FROM flyway_schema_history
                      WHERE version IS NOT NULL AND success = 1
                     """)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    /**
     * Asserts the current Flyway version.
     *
     * @param flyway configured Flyway instance
     * @param expectedVersion expected semantic version
     */
    private void assertCurrentVersion(Flyway flyway, String expectedVersion) {
        assertNotNull(flyway.info().current());
        assertEquals(expectedVersion, flyway.info().current().getVersion().getVersion());
    }

    /**
     * Opens a JDBC connection to the isolated MySQL database.
     *
     * @return open JDBC connection
     */
    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    /**
     * Resolves a canonical migration from module and reactor working directories.
     *
     * @param filename migration filename
     * @return migration path
     */
    private Path resolveMigration(String filename) {
        return resolveMigrationDir().resolve(filename);
    }

    /**
     * Resolves the canonical migration directory from module and reactor working directories.
     *
     * @return canonical migration directory
     */
    private Path resolveMigrationDir() {
        Path modulePath = Path.of("src/main/resources/db/migration");
        if (Files.isDirectory(modulePath)) {
            return modulePath;
        }
        return Path.of("backend-web/src/main/resources/db/migration");
    }

    /**
     * Computes the lower-case SHA-256 of a file.
     *
     * @param file file to hash
     * @return lower-case hexadecimal SHA-256
     */
    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record SchemaContract(
            boolean transactionHash,
            boolean contractHash,
            boolean nickname,
            boolean integrityDeleted,
            boolean integrityUpdateTime,
            String processedMessagesRoutine,
            String operationLogsRoutine) {
    }

    private record AppliedMigration(String script, int checksum) {
    }
}
