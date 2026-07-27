-- V1.19.0: Add durable tenant envelope-rotation governance and active-envelope fencing.

-- Existing duplicate active recipients are ambiguous and must be reviewed before the invariant is enabled.
DROP PROCEDURE IF EXISTS `proc_assert_file_key_envelope_active_unique`;

DELIMITER //
CREATE PROCEDURE `proc_assert_file_key_envelope_active_unique`()
BEGIN
    IF EXISTS (
        SELECT 1
        FROM `file_key_envelope`
        WHERE `status` = 'ACTIVE'
          AND `deleted` = 0
        GROUP BY `tenant_id`, `file_id`, `file_hash`, `recipient_type`, `recipient_id`
        HAVING COUNT(*) > 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'duplicate active key envelopes require manual review before V1.19.0';
    END IF;
END //
DELIMITER ;

CALL `proc_assert_file_key_envelope_active_unique`();
DROP PROCEDURE IF EXISTS `proc_assert_file_key_envelope_active_unique`;

-- A nullable generated slot enforces one readable envelope while allowing pending and historical rows.
ALTER TABLE `file_key_envelope`
    ADD COLUMN `active_slot` TINYINT
        GENERATED ALWAYS AS (
            CASE WHEN `status` = 'ACTIVE' AND `deleted` = 0 THEN 1 ELSE NULL END
        ) STORED COMMENT 'Generated active-recipient uniqueness slot',
    ADD UNIQUE KEY `uk_file_key_envelope_active`
        (`tenant_id`, `file_id`, `file_hash`, `recipient_type`, `recipient_id`, `active_slot`);

CREATE TABLE IF NOT EXISTS `key_rotation_policy` (
    `id`                         BIGINT       NOT NULL COMMENT 'Snowflake policy ID',
    `tenant_id`                  BIGINT       NOT NULL COMMENT 'Tenant boundary',
    `status`                     VARCHAR(16)  NOT NULL COMMENT 'ACTIVE, PAUSED, or DISABLED',
    `target_provider`            VARCHAR(64)  NOT NULL COMMENT 'Frozen target provider ID',
    `target_provider_contract`   INT          NOT NULL COMMENT 'Frozen provider contract version',
    `target_key_id`              VARCHAR(512) NOT NULL COMMENT 'Frozen provider key identifier',
    `target_provider_key_version` VARCHAR(128) NOT NULL COMMENT 'Frozen provider-native key version',
    `target_wrapping_algorithm`  VARCHAR(64)  NOT NULL COMMENT 'Frozen wrapping algorithm',
    `target_context_schema`      VARCHAR(128) NOT NULL COMMENT 'Frozen wrapping-context schema',
    `target_logical_key_version` INT          NOT NULL COMMENT 'Application logical key version',
    `batch_size`                 INT          NOT NULL DEFAULT 50 COMMENT 'Bounded discovery/claim page size',
    `max_items_per_minute`       INT          NOT NULL DEFAULT 600 COMMENT 'Tenant worker rate limit',
    `schedule_enabled`           TINYINT      NOT NULL DEFAULT 0 COMMENT 'Whether interval scheduling is enabled',
    `schedule_interval_seconds`  BIGINT       DEFAULT NULL COMMENT 'Fixed schedule interval',
    `next_run_at`                DATETIME     DEFAULT NULL COMMENT 'Next durable scheduled trigger',
    `max_attempts`               INT          NOT NULL DEFAULT 5 COMMENT 'Maximum item attempts',
    `initial_backoff_seconds`    BIGINT       NOT NULL DEFAULT 5 COMMENT 'Initial retry delay',
    `max_backoff_seconds`        BIGINT       NOT NULL DEFAULT 300 COMMENT 'Maximum retry delay',
    `lease_seconds`              BIGINT       NOT NULL DEFAULT 120 COMMENT 'Worker ownership lease',
    `grace_period_seconds`       BIGINT       NOT NULL DEFAULT 604800 COMMENT 'Rollback window before retirement readiness',
    `policy_version`             BIGINT       NOT NULL DEFAULT 1 COMMENT 'Optimistic immutable-run snapshot version',
    `created_by`                 BIGINT       NOT NULL COMMENT 'Creating administrator',
    `updated_by`                 BIGINT       NOT NULL COMMENT 'Last updating administrator',
    `last_run_id`                BIGINT       DEFAULT NULL COMMENT 'Newest run ID',
    `retirement_status`          VARCHAR(24)  NOT NULL DEFAULT 'NOT_READY' COMMENT 'NOT_READY, READY, or ACKNOWLEDGED',
    `retirement_eligible_at`     DATETIME     DEFAULT NULL COMMENT 'Earliest external retirement acknowledgement time',
    `retirement_acknowledged_at` DATETIME     DEFAULT NULL COMMENT 'External retirement acknowledgement time',
    `create_time`                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`                    TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_key_rotation_policy_tenant` (`tenant_id`, `deleted`),
    KEY `idx_key_rotation_policy_schedule` (`status`, `schedule_enabled`, `next_run_at`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Tenant automated file-key rotation policy';

CREATE TABLE IF NOT EXISTS `key_rotation_run` (
    `id`                          BIGINT       NOT NULL COMMENT 'Snowflake run ID',
    `tenant_id`                   BIGINT       NOT NULL COMMENT 'Tenant boundary',
    `policy_id`                   BIGINT       NOT NULL COMMENT 'Source policy ID',
    `policy_version`              BIGINT       NOT NULL COMMENT 'Frozen policy version',
    `trigger_type`                VARCHAR(16)  NOT NULL COMMENT 'MANUAL or SCHEDULED',
    `trigger_key`                 VARCHAR(96)  NOT NULL COMMENT 'Idempotent trigger identity',
    `mode`                        VARCHAR(16)  NOT NULL COMMENT 'DRY_RUN or APPLY',
    `status`                      VARCHAR(32)  NOT NULL COMMENT 'PLANNED, RUNNING, PAUSED, CANCELLED, COMPLETED, COMPLETED_WITH_FAILURES, or FAILED',
    `target_provider`             VARCHAR(64)  NOT NULL,
    `target_provider_contract`    INT          NOT NULL,
    `target_key_id`               VARCHAR(512) NOT NULL,
    `target_provider_key_version` VARCHAR(128) NOT NULL,
    `target_wrapping_algorithm`   VARCHAR(64)  NOT NULL,
    `target_context_schema`       VARCHAR(128) NOT NULL,
    `target_logical_key_version`  INT          NOT NULL,
    `batch_size`                  INT          NOT NULL,
    `max_items_per_minute`        INT          NOT NULL,
    `max_attempts`                INT          NOT NULL,
    `initial_backoff_seconds`     BIGINT       NOT NULL,
    `max_backoff_seconds`         BIGINT       NOT NULL,
    `lease_seconds`               BIGINT       NOT NULL,
    `grace_period_seconds`        BIGINT       NOT NULL,
    `snapshot_max_envelope_id`    BIGINT       NOT NULL COMMENT 'Upper candidate boundary fixed at creation',
    `scan_cursor_id`              BIGINT       DEFAULT NULL COMMENT 'Monotonic source-envelope keyset cursor',
    `discovery_complete`          TINYINT      NOT NULL DEFAULT 0,
    `total_count`                 BIGINT       NOT NULL DEFAULT 0,
    `pending_count`               BIGINT       NOT NULL DEFAULT 0,
    `running_count`               BIGINT       NOT NULL DEFAULT 0,
    `succeeded_count`             BIGINT       NOT NULL DEFAULT 0,
    `skipped_count`               BIGINT       NOT NULL DEFAULT 0,
    `failed_count`                BIGINT       NOT NULL DEFAULT 0,
    `remaining_count`             BIGINT       NOT NULL DEFAULT 0,
    `rate_window_started_at`      DATETIME     DEFAULT NULL COMMENT 'Durable one-minute rate window start',
    `rate_window_count`           INT          NOT NULL DEFAULT 0 COMMENT 'Reserved items in the current rate window',
    `created_by`                  BIGINT       NOT NULL,
    `started_at`                  DATETIME     DEFAULT NULL,
    `completed_at`                DATETIME     DEFAULT NULL,
    `retirement_status`           VARCHAR(24)  NOT NULL DEFAULT 'NOT_READY',
    `retirement_eligible_at`      DATETIME     DEFAULT NULL,
    `last_error_category`         VARCHAR(64)  DEFAULT NULL,
    `last_error_class`            VARCHAR(128) DEFAULT NULL,
    `create_time`                 DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`                 DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`                     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_key_rotation_run_trigger` (`tenant_id`, `policy_id`, `trigger_key`),
    KEY `idx_key_rotation_run_worker` (`tenant_id`, `status`, `update_time`, `id`),
    KEY `idx_key_rotation_run_history` (`tenant_id`, `create_time`, `id`),
    CONSTRAINT `fk_key_rotation_run_policy`
        FOREIGN KEY (`policy_id`) REFERENCES `key_rotation_policy` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Immutable automated key-rotation run snapshot';

CREATE TABLE IF NOT EXISTS `key_rotation_item` (
    `id`                    BIGINT       NOT NULL COMMENT 'Snowflake item ID',
    `tenant_id`             BIGINT       NOT NULL COMMENT 'Tenant boundary',
    `run_id`                BIGINT       NOT NULL COMMENT 'Owning rotation run',
    `source_envelope_id`    BIGINT       NOT NULL COMMENT 'Frozen source envelope ID',
    `candidate_envelope_id` BIGINT       DEFAULT NULL COMMENT 'Verified pending/replacement envelope ID',
    `file_id`               BIGINT       NOT NULL,
    `recipient_type`        VARCHAR(32)  NOT NULL,
    `recipient_id`          BIGINT       NOT NULL,
    `status`                VARCHAR(24)  NOT NULL COMMENT 'PENDING, RUNNING, SUCCEEDED, SKIPPED, or FAILED',
    `outcome`               VARCHAR(64)  DEFAULT NULL COMMENT 'Stable terminal outcome',
    `retryable`             TINYINT      NOT NULL DEFAULT 0,
    `attempt_count`         INT          NOT NULL DEFAULT 0,
    `claim_token`           VARCHAR(64)  DEFAULT NULL COMMENT 'Worker ownership fence',
    `lease_expires_at`      DATETIME     DEFAULT NULL COMMENT 'Worker claim expiry',
    `next_retry_at`         DATETIME     DEFAULT NULL,
    `failure_category`      VARCHAR(64)  DEFAULT NULL COMMENT 'Stable provider or rotation category',
    `last_error_class`      VARCHAR(128) DEFAULT NULL COMMENT 'Bounded exception class only',
    `create_time`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`               TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_key_rotation_item_source` (`tenant_id`, `run_id`, `source_envelope_id`),
    UNIQUE KEY `uk_key_rotation_item_candidate` (`candidate_envelope_id`),
    KEY `idx_key_rotation_item_claim`
        (`tenant_id`, `run_id`, `status`, `retryable`, `next_retry_at`, `lease_expires_at`, `id`),
    KEY `idx_key_rotation_item_page` (`tenant_id`, `run_id`, `id`),
    CONSTRAINT `fk_key_rotation_item_run`
        FOREIGN KEY (`run_id`) REFERENCES `key_rotation_run` (`id`),
    CONSTRAINT `fk_key_rotation_item_source`
        FOREIGN KEY (`source_envelope_id`) REFERENCES `file_key_envelope` (`id`),
    CONSTRAINT `fk_key_rotation_item_candidate`
        FOREIGN KEY (`candidate_envelope_id`) REFERENCES `file_key_envelope` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Per-envelope automated rotation lifecycle and worker fence';

CREATE TABLE IF NOT EXISTS `key_rotation_audit_log` (
    `id`                     BIGINT       NOT NULL COMMENT 'Snowflake audit ID',
    `tenant_id`              BIGINT       NOT NULL,
    `policy_id`              BIGINT       DEFAULT NULL,
    `run_id`                 BIGINT       DEFAULT NULL,
    `item_id`                BIGINT       DEFAULT NULL,
    `actor_id`               BIGINT       DEFAULT NULL,
    `action`                 VARCHAR(32)  NOT NULL,
    `outcome`                VARCHAR(32)  NOT NULL,
    `failure_category`       VARCHAR(64)  DEFAULT NULL,
    `remaining_count`        BIGINT       DEFAULT NULL,
    `target_provider`        VARCHAR(64)  DEFAULT NULL,
    `target_provider_contract` INT        DEFAULT NULL,
    `target_logical_key_version` INT      DEFAULT NULL,
    `target_key_fingerprint` CHAR(64)     DEFAULT NULL COMMENT 'SHA-256 fingerprint; never raw key ID',
    `create_time`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted`                TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_key_rotation_audit_run` (`tenant_id`, `run_id`, `create_time`, `id`),
    KEY `idx_key_rotation_audit_policy` (`tenant_id`, `policy_id`, `create_time`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Sanitized automated key-rotation audit evidence';
