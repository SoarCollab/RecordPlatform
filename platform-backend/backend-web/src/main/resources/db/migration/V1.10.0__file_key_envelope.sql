-- V1.10.0: Add wrapped file data-key envelopes for key governance.

CREATE TABLE IF NOT EXISTS `file_key_envelope` (
    `id`                   BIGINT       NOT NULL COMMENT 'Snowflake envelope ID',
    `tenant_id`            BIGINT       NOT NULL COMMENT 'Tenant ID',
    `file_id`              BIGINT       NOT NULL COMMENT 'File table ID',
    `file_hash`            VARCHAR(255) NOT NULL COMMENT 'Original file hash',
    `recipient_type`       VARCHAR(32)  NOT NULL COMMENT 'Envelope recipient type, for example OWNER',
    `recipient_id`         BIGINT       NOT NULL COMMENT 'Recipient user or principal ID',
    `key_version`          INT          NOT NULL COMMENT 'Wrapping key version',
    `algorithm_suite`      VARCHAR(96)  NOT NULL COMMENT 'Content crypto algorithm suite identifier',
    `encryption_algorithm` VARCHAR(64)  DEFAULT NULL COMMENT 'Content encryption algorithm label',
    `wrapping_algorithm`   VARCHAR(64)  NOT NULL COMMENT 'Data-key wrapping algorithm',
    `kms_provider`         VARCHAR(64)  NOT NULL COMMENT 'KMS provider identifier',
    `kms_key_id`           VARCHAR(128) NOT NULL COMMENT 'KMS key identifier',
    `encrypted_data_key`   TEXT         NOT NULL COMMENT 'Wrapped file data key',
    `wrapping_iv`          VARCHAR(64)  NOT NULL COMMENT 'Base64 wrapping IV/nonce',
    `aad_hash`             VARCHAR(128) NOT NULL COMMENT 'SHA-256 hash of envelope AAD',
    `status`               VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'Envelope lifecycle status',
    `create_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    `update_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    `deleted`              TINYINT      NOT NULL DEFAULT 0 COMMENT 'Soft delete flag',
    PRIMARY KEY (`id`),
    KEY `idx_file_key_envelope_file` (`tenant_id`, `file_id`, `recipient_type`, `recipient_id`, `deleted`, `status`),
    KEY `idx_file_key_envelope_hash` (`tenant_id`, `file_hash`, `recipient_type`, `recipient_id`, `deleted`, `status`),
    KEY `idx_file_key_envelope_version` (`tenant_id`, `key_version`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Wrapped file data-key envelope table';
