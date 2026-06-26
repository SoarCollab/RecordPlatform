-- Storage integrity check alert records
CREATE TABLE `integrity_alert` (
    `id` BIGINT NOT NULL COMMENT 'Snowflake ID',
    `tenant_id` BIGINT NOT NULL COMMENT 'Tenant ID',
    `file_id` BIGINT NOT NULL COMMENT 'File ID',
    `file_hash` VARCHAR(128) NOT NULL COMMENT 'Expected SHA-256 hash',
    `actual_hash` VARCHAR(128) COMMENT 'Actual computed hash (null if file not found)',
    `chain_hash` VARCHAR(128) COMMENT 'On-chain hash (null if chain record not found)',
    `alert_type` VARCHAR(32) NOT NULL COMMENT 'HASH_MISMATCH, FILE_NOT_FOUND, CHAIN_NOT_FOUND',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0=PENDING, 1=ACKNOWLEDGED, 2=RESOLVED',
    `resolved_by` BIGINT COMMENT 'Admin user who resolved',
    `resolved_at` DATETIME COMMENT 'Resolution time',
    `note` VARCHAR(512) COMMENT 'Resolution note',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_integrity_alert_tenant` (`tenant_id`, `status`, `create_time` DESC),
    INDEX `idx_integrity_alert_file` (`file_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Storage integrity check alerts';
