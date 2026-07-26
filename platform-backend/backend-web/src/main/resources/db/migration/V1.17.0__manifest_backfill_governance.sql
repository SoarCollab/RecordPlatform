-- V1.17.0: Add legacy chunk-manifest backfill governance and reference-aware sweep fencing.

-- Fail before adding the active-slot constraint when historical duplicate active rows exist.
DROP PROCEDURE IF EXISTS `proc_assert_manifest_active_unique`;

DELIMITER //
CREATE PROCEDURE `proc_assert_manifest_active_unique`()
BEGIN
    IF EXISTS (
        SELECT 1
        FROM `file_chunk_manifest`
        WHERE `status` = 'ACTIVE'
          AND `deleted` = 0
        GROUP BY `tenant_id`, `file_id`
        HAVING COUNT(*) > 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'duplicate active chunk manifests require manual review before V1.17.0';
    END IF;
END //
DELIMITER ;

CALL `proc_assert_manifest_active_unique`();
DROP PROCEDURE IF EXISTS `proc_assert_manifest_active_unique`;

-- A generated nullable slot lets MySQL enforce at most one undeleted ACTIVE row per tenant/file.
ALTER TABLE `file_chunk_manifest`
    ADD COLUMN `active_slot` TINYINT
        GENERATED ALWAYS AS (
            CASE WHEN `status` = 'ACTIVE' AND `deleted` = 0 THEN 1 ELSE NULL END
        ) STORED COMMENT 'Generated active-row uniqueness slot',
    ADD UNIQUE KEY `uk_file_chunk_manifest_active` (`tenant_id`, `file_id`, `active_slot`);

CREATE TABLE IF NOT EXISTS `manifest_backfill_run` (
    `id`                    BIGINT       NOT NULL COMMENT 'Snowflake run ID',
    `tenant_id`             BIGINT       NOT NULL COMMENT 'Tenant boundary for this run',
    `snapshot_run_id`       BIGINT       DEFAULT NULL COMMENT 'Immutable source scan for dry-run/apply',
    `mode`                  VARCHAR(16)  NOT NULL COMMENT 'SCAN, DRY_RUN, or APPLY',
    `status`                VARCHAR(32)  NOT NULL COMMENT 'PLANNED, SCANNING, SNAPSHOT_READY, APPLYING, PAUSED, COMPLETED, FAILED',
    `snapshot_version`      VARCHAR(32)  NOT NULL COMMENT 'Evidence snapshot schema version',
    `snapshot_digest`       VARCHAR(128) DEFAULT NULL COMMENT 'Digest over ordered item evidence',
    `cursor_file_id`        BIGINT       DEFAULT NULL COMMENT 'Durable keyset cursor',
    `created_by`            BIGINT       NOT NULL COMMENT 'Administrator that created the run',
    `total_count`           BIGINT       NOT NULL DEFAULT 0,
    `pending_count`         BIGINT       NOT NULL DEFAULT 0,
    `backfilled_count`      BIGINT       NOT NULL DEFAULT 0,
    `reupload_count`        BIGINT       NOT NULL DEFAULT 0,
    `unrecoverable_count`   BIGINT       NOT NULL DEFAULT 0,
    `ignored_count`         BIGINT       NOT NULL DEFAULT 0,
    `failed_count`          BIGINT       NOT NULL DEFAULT 0,
    `last_error_class`      VARCHAR(128) DEFAULT NULL,
    `started_at`            DATETIME     DEFAULT NULL,
    `completed_at`          DATETIME     DEFAULT NULL,
    `create_time`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`               TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_manifest_backfill_run_tenant` (`tenant_id`, `create_time`, `id`),
    KEY `idx_manifest_backfill_run_worker` (`status`, `update_time`, `id`),
    CONSTRAINT `fk_manifest_backfill_run_snapshot`
        FOREIGN KEY (`snapshot_run_id`) REFERENCES `manifest_backfill_run` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Immutable legacy manifest scan/apply run ledger';

CREATE TABLE IF NOT EXISTS `manifest_backfill_item` (
    `id`                       BIGINT       NOT NULL COMMENT 'Snowflake item ID',
    `run_id`                   BIGINT       NOT NULL COMMENT 'Owning governance run',
    `tenant_id`                BIGINT       NOT NULL COMMENT 'Tenant boundary',
    `file_id`                  BIGINT       NOT NULL COMMENT 'File table ID',
    `file_version`             INT          NOT NULL COMMENT 'Stable file version',
    `owner_user_id`            BIGINT       DEFAULT NULL COMMENT 'Owner used for chain pointer lookup',
    `status`                   VARCHAR(32)  NOT NULL COMMENT 'PENDING, RUNNING, BACKFILLED, REUPLOAD_REQUIRED, UNRECOVERABLE, IGNORED, FAILED',
    `classification`           VARCHAR(32)  NOT NULL COMMENT 'Evidence classification',
    `reason_code`              VARCHAR(64)  NOT NULL COMMENT 'Stable machine-readable reason',
    `retryable`                TINYINT      NOT NULL DEFAULT 0 COMMENT 'Whether bounded retry is allowed',
    `legacy_download_allowed`  TINYINT      NOT NULL DEFAULT 0 COMMENT 'Explicit bounded compatibility permission',
    `evidence_digest`          VARCHAR(128) NOT NULL COMMENT 'Digest of normalized evidence snapshot',
    `evidence_payload`         JSON         DEFAULT NULL COMMENT 'Access-controlled normalized evidence snapshot',
    `manifest_id`              BIGINT       DEFAULT NULL COMMENT 'Published/winning manifest ID',
    `claim_token`              VARCHAR(64)  DEFAULT NULL COMMENT 'Worker ownership fence',
    `lease_expires_at`         DATETIME     DEFAULT NULL COMMENT 'Worker claim lease expiry',
    `attempt_count`            INT          NOT NULL DEFAULT 0,
    `next_retry_at`            DATETIME     DEFAULT NULL,
    `last_error_class`         VARCHAR(128) DEFAULT NULL,
    `create_time`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`                  TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_manifest_backfill_item_candidate` (`run_id`, `tenant_id`, `file_id`, `file_version`),
    KEY `idx_manifest_backfill_item_claim` (`run_id`, `tenant_id`, `status`, `next_retry_at`, `file_id`, `id`),
    KEY `idx_manifest_backfill_item_file` (`tenant_id`, `file_id`, `file_version`, `create_time`),
    KEY `idx_manifest_backfill_item_filter` (`run_id`, `status`, `classification`, `reason_code`, `id`),
    CONSTRAINT `fk_manifest_backfill_item_run`
        FOREIGN KEY (`run_id`) REFERENCES `manifest_backfill_run` (`id`),
    CONSTRAINT `fk_manifest_backfill_item_manifest`
        FOREIGN KEY (`manifest_id`) REFERENCES `file_chunk_manifest` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Per-file immutable evidence and backfill lifecycle ledger';

CREATE TABLE IF NOT EXISTS `manifest_reference_census` (
    `id`                    BIGINT       NOT NULL COMMENT 'Snowflake census ID',
    `tenant_id`             BIGINT       NOT NULL COMMENT 'Tenant boundary',
    `status`                VARCHAR(24)  NOT NULL COMMENT 'RUNNING, COMPLETED, or FAILED',
    `census_digest`         VARCHAR(128) DEFAULT NULL COMMENT 'Digest over ordered reference rows',
    `known_reference_count` BIGINT       NOT NULL DEFAULT 0,
    `unknown_hold_count`    BIGINT       NOT NULL DEFAULT 0,
    `last_error_class`      VARCHAR(128) DEFAULT NULL,
    `completed_at`          DATETIME     DEFAULT NULL,
    `create_time`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`               TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_manifest_reference_census_tenant` (`tenant_id`, `status`, `create_time`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Reference census completion and digest boundary';

CREATE TABLE IF NOT EXISTS `manifest_reference_ledger` (
    `id`                       BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Reference row ID',
    `census_id`                BIGINT       NOT NULL COMMENT 'Versioned census/run ID',
    `tenant_id`                BIGINT       NOT NULL COMMENT 'Tenant census scope that protects the referenced object',
    `path_tenant_id`           BIGINT       DEFAULT NULL COMMENT 'Tenant encoded by the object path',
    `storage_path`             VARCHAR(512) DEFAULT NULL COMMENT 'Logical object path; null for unknown hold',
    `cipher_hash`              VARCHAR(128) DEFAULT NULL COMMENT 'Stored object content identity',
    `object_identity_digest`   CHAR(64)     NOT NULL COMMENT 'SHA-256 over tenant/path/hash identity',
    `source_type`              VARCHAR(48)  NOT NULL COMMENT 'Manifest, share, attestation, proof, legacy, finalization, or degraded source',
    `source_id`                VARCHAR(128) NOT NULL COMMENT 'Bounded internal source identifier',
    `source_key_digest`        CHAR(64)     NOT NULL COMMENT 'SHA-256 over source identity',
    `hold_reason`              VARCHAR(64)  DEFAULT NULL COMMENT 'Conservative retention reason',
    `known_reference`          TINYINT      NOT NULL DEFAULT 1 COMMENT '0 means an unknown-reference hold',
    `observed_at`              DATETIME     NOT NULL COMMENT 'Census observation time',
    `create_time`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`                  TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_manifest_reference_ledger_source`
        (`census_id`, `object_identity_digest`, `source_type`, `source_key_digest`),
    KEY `idx_manifest_reference_ledger_object`
        (`path_tenant_id`, `object_identity_digest`, `known_reference`, `deleted`),
    KEY `idx_manifest_reference_ledger_census` (`census_id`, `tenant_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Versioned logical-object reference and conservative hold census';

CREATE TABLE IF NOT EXISTS `manifest_reference_sweep_mark` (
    `id`                       BIGINT       NOT NULL COMMENT 'Snowflake sweep mark ID',
    `tenant_id`                BIGINT       NOT NULL COMMENT 'Administrative tenant boundary',
    `path_tenant_id`           BIGINT       NOT NULL COMMENT 'Tenant encoded by object path',
    `storage_path`             VARCHAR(512) NOT NULL COMMENT 'Logical object path',
    `cipher_hash`              VARCHAR(128) NOT NULL COMMENT 'Stored object content identity',
    `content_length`           BIGINT       NOT NULL COMMENT 'Expected object length from mark-time HEAD',
    `etag`                     VARCHAR(256) DEFAULT NULL COMMENT 'Expected mark-time object ETag',
    `object_identity_digest`   CHAR(64)     NOT NULL COMMENT 'SHA-256 over tenant/path/hash identity',
    `mark_census_id`           BIGINT       NOT NULL COMMENT 'Census proving the initial orphan decision',
    `status`                   VARCHAR(32)  NOT NULL COMMENT 'MARKED, RETAINED, DELETING, DELETED, FAILED',
    `protection_until`         DATETIME     NOT NULL COMMENT 'Earliest delete time',
    `claim_token`              VARCHAR(64)  DEFAULT NULL COMMENT 'Delete worker ownership fence',
    `lease_expires_at`         DATETIME     DEFAULT NULL,
    `attempt_count`            INT          NOT NULL DEFAULT 0,
    `next_retry_at`            DATETIME     DEFAULT NULL,
    `reason_code`              VARCHAR(64)  DEFAULT NULL,
    `last_error_class`         VARCHAR(128) DEFAULT NULL,
    `deleted_at`               DATETIME     DEFAULT NULL,
    `create_time`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`                  TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_manifest_reference_sweep_object`
        (`tenant_id`, `object_identity_digest`, `deleted`),
    KEY `idx_manifest_reference_sweep_claim`
        (`status`, `protection_until`, `next_retry_at`, `lease_expires_at`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Reference-aware mark/grace/recheck/delete lifecycle fence';
