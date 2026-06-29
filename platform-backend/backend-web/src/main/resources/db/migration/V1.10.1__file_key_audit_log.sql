-- V1.10.1: Add audit log for file key unwrap, rotation, and revocation operations.

CREATE TABLE IF NOT EXISTS `file_key_audit_log` (
    `id`             BIGINT       NOT NULL COMMENT 'Snowflake audit log ID',
    `tenant_id`      BIGINT       NOT NULL COMMENT 'Tenant ID',
    `file_id`        BIGINT       DEFAULT NULL COMMENT 'File table ID',
    `file_hash`      VARCHAR(255) DEFAULT NULL COMMENT 'Original file hash',
    `recipient_type` VARCHAR(32)  DEFAULT NULL COMMENT 'Envelope recipient type',
    `recipient_id`   BIGINT       DEFAULT NULL COMMENT 'Envelope recipient ID',
    `key_version`    INT          DEFAULT NULL COMMENT 'Wrapping key version',
    `operation`      VARCHAR(32)  NOT NULL COMMENT 'Key operation: UNWRAP, ROTATE, REVOKE',
    `actor_id`       BIGINT       DEFAULT NULL COMMENT 'Actor user ID',
    `result`         VARCHAR(32)  NOT NULL COMMENT 'Operation result',
    `reason`         VARCHAR(255) DEFAULT NULL COMMENT 'Operator reason or system reason',
    `error_message`  VARCHAR(512) DEFAULT NULL COMMENT 'Failure detail without secret material',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    `deleted`        TINYINT      NOT NULL DEFAULT 0 COMMENT 'Soft delete flag',
    PRIMARY KEY (`id`),
    KEY `idx_file_key_audit_file` (`tenant_id`, `file_id`, `operation`, `create_time`),
    KEY `idx_file_key_audit_hash` (`tenant_id`, `file_hash`, `operation`, `create_time`),
    KEY `idx_file_key_audit_recipient` (`tenant_id`, `recipient_type`, `recipient_id`, `create_time`),
    KEY `idx_file_key_audit_actor` (`tenant_id`, `actor_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='File key envelope audit log table';
