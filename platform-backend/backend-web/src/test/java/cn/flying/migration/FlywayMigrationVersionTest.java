package cn.flying.migration;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlywayMigrationVersionTest {

    private static final Pattern VERSION_PATTERN = Pattern.compile("^V([^_]+)__.+\\.sql$");

    /**
     * 验证 Flyway 历史迁移文件名保持稳定且不会复用版本号。
     */
    @Test
    @DisplayName("should keep released migration versions and avoid version reuse")
    void shouldKeepReleasedMigrationVersionsAndAvoidVersionReuse() throws IOException {
        Path migrationDir = resolveMigrationDir();
        List<String> migrationFiles;
        try (var stream = Files.list(migrationDir)) {
            migrationFiles = stream
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("V") && name.endsWith(".sql"))
                    .sorted()
                    .toList();
        }

        assertTrue(migrationFiles.contains("V1.5.0__add_account_nickname.sql"));
        assertTrue(migrationFiles.contains("V1.7.3__integrity_alert.sql"));
        assertTrue(migrationFiles.contains("V1.7.4__rename_file_contract_hash_to_transaction_hash.sql"));
        assertTrue(migrationFiles.contains("V1.7.5__replace_clean_log_procedures.sql"));
        assertTrue(migrationFiles.contains("V1.10.3__integrity_alert_evidence.sql"));
        assertTrue(migrationFiles.contains("V1.11.0__attestation_batch_consistency.sql"));
        assertTrue(migrationFiles.contains("V1.12.0__attestation_batch_production_trigger.sql"));
        assertTrue(migrationFiles.contains("V1.13.0__attestation_contract_registry_snapshot.sql"));
        assertTrue(migrationFiles.contains("V1.14.0__signed_proof_bundle.sql"));
        assertTrue(migrationFiles.contains("V1.15.0__proof_status_timestamp_precision.sql"));
        assertTrue(migrationFiles.contains("V1.16.0__framed_download_contract.sql"));
        assertTrue(migrationFiles.contains("V1.17.0__manifest_backfill_governance.sql"));
        assertFalse(migrationFiles.contains("V1.0.1__add_account_nickname.sql"));
        assertFalse(migrationFiles.contains("V1.5.0__integrity_alert.sql"));

        Set<MigrationVersion> versions = new HashSet<>();
        for (String fileName : migrationFiles) {
            Matcher matcher = VERSION_PATTERN.matcher(fileName);
            assertTrue(matcher.matches(), "Invalid migration filename: " + fileName);
            MigrationVersion version = MigrationVersion.fromVersion(matcher.group(1));
            assertTrue(versions.add(version), "Duplicate migration version: " + matcher.group(1));
        }
    }

    /**
     * 验证初始化迁移保持历史列名，由后续前向迁移负责改名。
     */
    @Test
    @DisplayName("should keep initial file migration compatible with forward transaction hash rename")
    void shouldKeepInitialFileMigrationCompatibleWithForwardTransactionHashRename() throws IOException {
        Path migration = resolveMigrationDir().resolve("V1.0.0__init_schema.sql");
        String sql = Files.readString(migration);

        assertTrue(sql.contains("`contract_hash`"));
        assertFalse(sql.contains("`transaction_hash`     VARCHAR"));
    }

    /**
     * 验证历史列名修复通过新的前向迁移实现，避免继续修改已发布迁移。
     */
    @Test
    @DisplayName("should rename legacy file contract hash column through forward migration")
    void shouldRenameLegacyFileContractHashColumnThroughForwardMigration() throws IOException {
        Path migration = resolveMigrationDir().resolve("V1.7.4__rename_file_contract_hash_to_transaction_hash.sql");
        String sql = Files.readString(migration);

        assertTrue(sql.contains("INFORMATION_SCHEMA.COLUMNS"));
        assertTrue(sql.contains("COLUMN_NAME = 'contract_hash'"));
        assertTrue(sql.contains("COLUMN_NAME = 'transaction_hash'"));
        assertTrue(sql.contains("CHANGE COLUMN `contract_hash` `transaction_hash`"));
    }

    /**
     * 验证所有迁移脚本避免使用 MySQL 8.0 早期版本不支持的存储过程 IF NOT EXISTS 语法。
     */
    @Test
    @DisplayName("should not use unsupported create procedure if not exists syntax")
    void shouldNotUseUnsupportedCreateProcedureIfNotExistsSyntax() throws IOException {
        Path migrationDir = resolveMigrationDir();
        try (var stream = Files.list(migrationDir)) {
            List<Path> migrationFiles = stream
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .toList();

            for (Path migration : migrationFiles) {
                String sql = Files.readString(migration);
                assertFalse(
                        sql.matches("(?is).*CREATE\\s+PROCEDURE\\s+IF\\s+NOT\\s+EXISTS.*"),
                        "Unsupported procedure syntax in " + migration.getFileName());
            }
        }
    }

    /**
     * 验证清理类存储过程通过新的前向迁移替换，避免改写历史初始化迁移。
     */
    @Test
    @DisplayName("should replace clean log procedures through forward migration")
    void shouldReplaceCleanLogProceduresThroughForwardMigration() throws IOException {
        Path migration = resolveMigrationDir().resolve("V1.7.5__replace_clean_log_procedures.sql");
        String sql = Files.readString(migration);

        assertTrue(sql.contains("DROP PROCEDURE IF EXISTS `proc_clean_processed_messages`"));
        assertTrue(sql.contains("DROP PROCEDURE IF EXISTS `proc_clean_old_operation_logs`"));
        assertTrue(sql.contains("CREATE PROCEDURE `proc_clean_processed_messages`"));
        assertTrue(sql.contains("CREATE PROCEDURE `proc_clean_old_operation_logs`"));
        assertFalse(sql.matches("(?is).*CREATE\\s+PROCEDURE\\s+IF\\s+NOT\\s+EXISTS.*"));
    }

    /**
     * 验证完整性告警证据通过前向迁移新增，并包含开放告警去重索引。
     */
    @Test
    @DisplayName("should add integrity severity evidence and open-alert dedup index")
    void shouldAddIntegritySeverityEvidenceAndOpenAlertDedupIndex() throws IOException {
        Path migration = resolveMigrationDir().resolve("V1.10.3__integrity_alert_evidence.sql");
        String sql = Files.readString(migration);

        assertTrue(sql.contains("ADD COLUMN `severity` VARCHAR(16) NOT NULL"));
        assertTrue(sql.contains("ADD COLUMN `evidence` VARCHAR(1024)"));
        assertTrue(sql.contains("ADD INDEX `idx_integrity_alert_open_dedup`"));
        assertTrue(sql.contains("WHEN `alert_type` = 'CHAIN_NOT_FOUND' THEN 'ERROR'"));
    }

    /**
     * 验证批量存证一致性迁移只追加状态字段、幂等唯一键和 attempt 审计表。
     */
    @Test
    @DisplayName("should add recoverable attestation batch state through a forward migration")
    void shouldAddRecoverableAttestationBatchStateThroughForwardMigration() throws IOException {
        Path migration = resolveMigrationDir().resolve("V1.11.0__attestation_batch_consistency.sql");
        String sql = Files.readString(migration);

        assertTrue(sql.contains("ADD COLUMN `idempotency_key`"));
        assertTrue(sql.contains("ADD UNIQUE KEY `uk_attestation_batch_idempotency`"));
        assertTrue(sql.contains("ADD COLUMN `claim_token`"));
        assertTrue(sql.contains("ADD COLUMN `lease_expires_at`"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `attestation_batch_attempt`"));
        assertTrue(sql.contains("UNIQUE KEY `uk_attestation_attempt_claim`"));
        assertTrue(sql.contains("FOREIGN KEY (`batch_id`) REFERENCES `attestation_batch` (`id`)"));
        assertFalse(sql.matches("(?is).*DROP\\s+(TABLE|COLUMN).*"));
    }

    /**
     * 验证生产触发通过前向迁移增加 candidate 账本和显式 leaf 证据语义。
     */
    @Test
    @DisplayName("should add durable production candidates and manifest evidence through a forward migration")
    void shouldAddDurableProductionCandidatesAndManifestEvidence() throws IOException {
        Path migration = resolveMigrationDir().resolve("V1.12.0__attestation_batch_production_trigger.sql");
        String sql = Files.readString(migration);

        assertTrue(sql.contains("ADD COLUMN `file_version`"));
        assertTrue(sql.contains("ADD COLUMN `evidence_type`"));
        assertTrue(sql.contains("ADD COLUMN `evidence_hash`"));
        assertTrue(sql.contains("ADD COLUMN `chain_record_id`"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `attestation_batch_candidate`"));
        assertTrue(sql.contains("`evidence_hash`    VARCHAR(255) DEFAULT NULL"));
        assertTrue(sql.contains("ADD KEY `idx_file_attestation_candidate`"));
        assertTrue(sql.contains("ADD KEY `idx_manifest_attestation_candidate`"));
        assertTrue(sql.contains("UNIQUE KEY `uk_attestation_candidate_file_version`"));
        assertTrue(sql.contains("KEY `idx_attestation_candidate_ready` "
                + "(`tenant_id`, `deleted`, `status`, `eligible_at`, `id`)"));
        assertTrue(sql.contains("KEY `idx_attestation_candidate_claim` "
                + "(`tenant_id`, `claim_token`, `status`, `deleted`)"));
        assertTrue(sql.contains("KEY `idx_attestation_candidate_lease` "
                + "(`tenant_id`, `deleted`, `status`, `lease_expires_at`)"));
        assertTrue(sql.contains("KEY `idx_attestation_candidate_batch` "
                + "(`tenant_id`, `deleted`, `batch_id`)"));
        assertTrue(sql.contains("FOREIGN KEY (`manifest_id`) REFERENCES `file_chunk_manifest` (`id`)"));
        assertTrue(sql.contains("FOREIGN KEY (`batch_id`) REFERENCES `attestation_batch` (`id`)"));
        assertTrue(sql.contains("'LEGACY_CHAIN_RECORD_ID'"));
        assertFalse(sql.matches("(?is).*DROP\\s+(TABLE|COLUMN).*"));
    }

    /**
     * 验证合约注册表快照通过前向迁移追加，并保留历史批次为未知状态。
     */
    @Test
    @DisplayName("should bind attestation batches to immutable contract registry snapshots")
    void shouldAddContractRegistrySnapshotThroughForwardMigration() throws IOException {
        Path migration = resolveMigrationDir().resolve(
                "V1.13.0__attestation_contract_registry_snapshot.sql");
        String sql = Files.readString(migration);

        assertTrue(sql.contains("ADD COLUMN `contract_registry_fingerprint` VARCHAR(71)"));
        assertTrue(sql.contains("ADD COLUMN `contract_registry_json` JSON DEFAULT NULL"));
        assertTrue(sql.contains("ADD COLUMN `contract_address`"));
        assertTrue(sql.contains("ADD COLUMN `contract_abi_sha256`"));
        assertTrue(sql.contains("ADD COLUMN `contract_code_sha256`"));
        assertTrue(sql.contains("ADD KEY `idx_attestation_batch_registry`"));
        assertTrue(sql.contains("ADD KEY `idx_attestation_batch_contract`"));
        assertFalse(sql.matches("(?is).*UPDATE\\s+`attestation_batch`.*"));
        assertFalse(sql.matches("(?is).*DROP\\s+(TABLE|COLUMN).*"));
    }

    /**
     * 验证签名证明迁移只前向增加 nullable contentHash、全局 key 注册表和签发状态表。
     */
    @Test
    @DisplayName("should add signed proof issuance and content hash through a forward migration")
    void shouldAddSignedProofIssuanceThroughForwardMigration() throws IOException {
        Path migration = resolveMigrationDir().resolve("V1.14.0__signed_proof_bundle.sql");
        String sql = Files.readString(migration);

        assertTrue(sql.contains("ADD COLUMN `content_hash` VARCHAR(71) DEFAULT NULL"));
        assertTrue(sql.contains("CREATE TABLE `proof_signing_key`"));
        assertTrue(sql.contains("UNIQUE KEY `uk_proof_signing_key_identity` (`key_id`, `key_version`)"));
        assertTrue(sql.contains("`first_seen_at`"));
        assertTrue(sql.contains("CREATE TABLE `proof_bundle_issuance`"));
        assertTrue(sql.contains("UNIQUE KEY `uk_proof_bundle_proof_id`"));
        assertTrue(sql.contains("UNIQUE KEY `uk_proof_bundle_leaf` (`tenant_id`, `leaf_id`)"));
        assertTrue(sql.contains("UNIQUE KEY `uk_file_tenant_id_version` (`tenant_id`, `id`, `version`)"));
        assertTrue(sql.contains("UNIQUE KEY `uk_attestation_leaf_binding`"));
        assertTrue(sql.contains("FOREIGN KEY (`tenant_id`, `file_id`, `file_version`)"));
        assertTrue(sql.contains("FOREIGN KEY (`tenant_id`, `file_id`, `file_version`, `leaf_id`)"));
        assertTrue(sql.contains("CONSTRAINT `fk_proof_bundle_signing_key`"));
        assertTrue(sql.contains("REFERENCES `proof_signing_key` (`key_id`, `key_version`)"));
        assertTrue(sql.contains("`issued_status`"));
        assertTrue(sql.contains("`status_version`"));
        assertTrue(sql.contains("`public_key_spki`"));
        assertFalse(sql.matches("(?is).*UPDATE\\s+`file`.*"));
        assertFalse(sql.matches("(?is).*DROP\\s+(TABLE|COLUMN).*"));
    }

    /**
     * 验证 proof 状态时间精度通过严格定向的前向迁移修复，且不改写生命周期或签名字段。
     */
    @Test
    @DisplayName("should align proof status timestamp precision through a strict forward migration")
    void shouldAlignProofStatusTimestampPrecisionThroughForwardMigration() throws IOException {
        Path migration = resolveMigrationDir().resolve(
                "V1.15.0__proof_status_timestamp_precision.sql");
        assertTrue(Files.isRegularFile(migration), "Missing proof status timestamp precision migration");
        String sql = Files.readString(migration);
        String normalizedSql = sql.replaceAll("\\s+", " ").trim();

        assertTrue(normalizedSql.contains("ALTER TABLE `proof_bundle_issuance`"));
        assertTrue(normalizedSql.contains(
                "MODIFY COLUMN `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)"));
        assertTrue(normalizedSql.contains(
                "MODIFY COLUMN `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)"));
        assertTrue(normalizedSql.contains("ON UPDATE CURRENT_TIMESTAMP(3)"));
        assertTrue(normalizedSql.contains(
                "WHEN `create_time` < `issued_at` THEN `issued_at` ELSE `create_time` END"));
        assertTrue(normalizedSql.contains(
                "WHEN `update_time` < `issued_at` THEN `issued_at` ELSE `update_time` END"));
        assertTrue(normalizedSql.contains(
                "WHERE `create_time` < `issued_at` OR `update_time` < `issued_at`"));
        assertFalse(normalizedSql.matches(
                "(?is).*`(?:create_time|update_time)`\\s*<=\\s*`issued_at`.*"));

        Pattern alterPattern = Pattern.compile(
                "(?is)ALTER\\s+TABLE\\s+`proof_bundle_issuance`\\s+(.*?);");
        Matcher alterMatcher = alterPattern.matcher(sql);
        assertTrue(alterMatcher.find(), "Missing proof_bundle_issuance ALTER TABLE");
        String alterClause = alterMatcher.group(1);
        assertFalse(alterMatcher.find(), "Proof status precision must use one bounded ALTER TABLE");
        assertEquals(
                1,
                countMatches(sql, Pattern.compile("(?i)ALTER\\s+TABLE\\s+")),
                "Migration must not alter unrelated tables");
        assertFalse(
                Pattern.compile("(?i)\\b(?:ADD|DROP|CHANGE|RENAME)\\b")
                        .matcher(alterClause)
                        .find(),
                "Migration ALTER must only modify the two timestamp columns");

        Matcher modifiedColumnMatcher = Pattern.compile(
                "(?i)MODIFY\\s+COLUMN\\s+`([^`]+)`")
                .matcher(alterClause);
        Set<String> modifiedColumns = new HashSet<>();
        int modifiedColumnCount = 0;
        while (modifiedColumnMatcher.find()) {
            modifiedColumns.add(modifiedColumnMatcher.group(1).toLowerCase());
            modifiedColumnCount++;
        }
        assertEquals(2, modifiedColumnCount, "Migration must modify exactly two columns");
        assertEquals(
                Set.of("create_time", "update_time"),
                modifiedColumns,
                "Migration must not modify lifecycle or signing columns");

        Pattern updatePattern = Pattern.compile(
                "(?is)UPDATE\\s+`proof_bundle_issuance`\\s+SET\\s+(.*?)\\s+WHERE\\s+.*?;");
        Matcher updateMatcher = updatePattern.matcher(sql);
        assertTrue(updateMatcher.find(), "Missing directed proof_bundle_issuance update");
        String setClause = updateMatcher.group(1);
        assertFalse(updateMatcher.find(), "Proof status timestamps must be repaired in one update");

        for (String forbiddenColumn : List.of(
                "issued_at",
                "revoked_at",
                "issued_status",
                "status",
                "status_version",
                "status_reason",
                "proof_id",
                "manifest_hash",
                "manifest_json",
                "signature_jws",
                "signature_algorithm",
                "key_id",
                "key_version",
                "public_key_spki",
                "public_key_fingerprint")) {
            assertFalse(
                    Pattern.compile("(?i)`?" + forbiddenColumn + "`?\\s*=")
                            .matcher(setClause)
                            .find(),
                    "Migration must not assign protected column: " + forbiddenColumn);
        }

        assertEquals(
                1,
                countMatches(sql, Pattern.compile("(?i)UPDATE\\s+`proof_bundle_issuance`")),
                "Proof status history must be repaired by exactly one directed update");
        assertFalse(sql.matches("(?is).*ALTER\\s+TABLE\\s+`proof_signing_key`.*"));
        assertFalse(sql.matches("(?is).*UPDATE\\s+`proof_signing_key`.*"));
        assertFalse(sql.matches("(?is).*DROP\\s+(TABLE|COLUMN).*"));
    }

    /**
     * 验证 framed 下载迁移只追加 nullable 描述列和分片证据列，不改写历史数据或删除结构。
     */
    @Test
    @DisplayName("should add framed download columns through an additive forward migration")
    void shouldAddFramedDownloadContractThroughForwardMigration() throws IOException {
        Path migration = resolveMigrationDir().resolve("V1.16.0__framed_download_contract.sql");
        assertTrue(Files.isRegularFile(migration), "Missing framed download contract migration");
        String sql = Files.readString(migration);
        String normalizedSql = sql.replaceAll("\\s+", " ").trim();

        assertTrue(normalizedSql.contains("ALTER TABLE `file_chunk_manifest`"));
        assertTrue(normalizedSql.contains(
                "ADD COLUMN `encryption_metadata` JSON DEFAULT NULL"));
        assertTrue(normalizedSql.contains("ALTER TABLE `file_chunk_manifest_item`"));
        assertTrue(normalizedSql.contains("ADD COLUMN `plain_size` BIGINT DEFAULT NULL"));
        assertTrue(normalizedSql.contains("ADD COLUMN `frame_count` INT DEFAULT NULL"));
        assertTrue(normalizedSql.contains("Versioned framed-encryption descriptor"));
        assertTrue(normalizedSql.contains("Plaintext bytes represented by this stored object"));
        assertTrue(normalizedSql.contains("Authenticated frame count for framed encryption v2"));
        assertFalse(normalizedSql.matches("(?is).*UPDATE\\s+`(?:file_chunk_manifest|file_chunk_manifest_item)`.*"));
        assertFalse(normalizedSql.matches("(?is).*DROP\\s+(TABLE|COLUMN).*"));
    }

    /**
     * Verifies the manifest backfill migration adds durable fencing without rewriting file truth.
     */
    @Test
    @DisplayName("should add manifest backfill and reference sweep fencing through a forward migration")
    void shouldAddManifestBackfillGovernanceThroughForwardMigration() throws IOException {
        Path migration = resolveMigrationDir().resolve("V1.17.0__manifest_backfill_governance.sql");
        assertTrue(Files.isRegularFile(migration), "Missing manifest backfill governance migration");
        String sql = Files.readString(migration);
        String normalizedSql = sql.replaceAll("\\s+", " ").trim();

        assertTrue(normalizedSql.contains("SIGNAL SQLSTATE '45000'"));
        assertTrue(normalizedSql.contains("duplicate active chunk manifests require manual review"));
        assertTrue(normalizedSql.contains("GENERATED ALWAYS AS"));
        assertTrue(normalizedSql.contains(
                "UNIQUE KEY `uk_file_chunk_manifest_active` (`tenant_id`, `file_id`, `active_slot`)"));
        assertTrue(normalizedSql.contains("CREATE TABLE IF NOT EXISTS `manifest_backfill_run`"));
        assertTrue(normalizedSql.contains("CREATE TABLE IF NOT EXISTS `manifest_backfill_item`"));
        assertTrue(normalizedSql.contains("`claim_token` VARCHAR(64) DEFAULT NULL"));
        assertTrue(normalizedSql.contains("`lease_expires_at` DATETIME DEFAULT NULL"));
        assertTrue(normalizedSql.contains(
                "KEY `idx_manifest_backfill_item_claim` (`run_id`, `tenant_id`, `deleted`, "
                        + "`classification`, `file_id`, `id`)"));
        assertTrue(normalizedSql.contains("CREATE TABLE IF NOT EXISTS `manifest_reference_census`"));
        assertTrue(normalizedSql.contains("CREATE TABLE IF NOT EXISTS `manifest_reference_ledger`"));
        assertTrue(normalizedSql.contains("CREATE TABLE IF NOT EXISTS `manifest_reference_sweep_mark`"));
        assertTrue(normalizedSql.contains("`content_length` BIGINT NOT NULL"));
        assertTrue(normalizedSql.contains("`protection_until` DATETIME NOT NULL"));
        assertFalse(normalizedSql.matches("(?is).*UPDATE\\s+`(?:file|file_chunk_manifest)`.*"));
        assertFalse(normalizedSql.matches("(?is).*DROP\\s+(TABLE|COLUMN).*"));
    }

    /**
     * 统计正则在迁移脚本中的非重叠匹配数量。
     *
     * @param value 待检查迁移文本
     * @param pattern 目标正则
     * @return 非重叠匹配数量
     */
    private int countMatches(String value, Pattern pattern) {
        int count = 0;
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * 解析不同 Maven 执行目录下的迁移目录路径。
     *
     * @return Flyway 迁移目录
     */
    private Path resolveMigrationDir() {
        Path moduleDir = Path.of("src/main/resources/db/migration");
        if (Files.isDirectory(moduleDir)) {
            return moduleDir;
        }
        return Path.of("backend-web/src/main/resources/db/migration");
    }
}
