-- =============================================================================
-- V1.2.0: File Share Metadata Table
-- =============================================================================
-- Description: Creates the file_share table to store share metadata including
--              share type (PUBLIC/PRIVATE), access count, and status.
--              Works alongside blockchain storage for share validity.
-- =============================================================================

CREATE TABLE IF NOT EXISTS `file_share` (
    `id`            BIGINT       NOT NULL COMMENT 'Snowflake ID',
    `tenant_id`     BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID for multi-tenant isolation',
    `user_id`       BIGINT       NOT NULL COMMENT 'Share creator user ID',
    `share_code`    VARCHAR(10)  NOT NULL COMMENT 'Blockchain share code (6 chars)',
    `share_type`    TINYINT      NOT NULL DEFAULT 0 COMMENT 'Share type: 0-PUBLIC (no auth), 1-PRIVATE (auth required)',
    `file_hashes`   TEXT         NOT NULL COMMENT 'JSON array of shared file hashes',
    `expire_time`   DATETIME     NOT NULL COMMENT 'Share expiration time',
    `access_count`  INT          NOT NULL DEFAULT 0 COMMENT 'Number of times accessed',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT 'Status: 0-cancelled, 1-active, 2-expired',
    `create_time`   DATETIME     NOT NULL COMMENT 'Share creation time',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update time',
    `deleted`       TINYINT      DEFAULT 0 COMMENT 'Soft delete flag: 0-active, 1-deleted',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_share_code` (`share_code`) USING BTREE COMMENT 'Share code must be unique',
    INDEX `idx_tenant_user` (`tenant_id`, `user_id`) COMMENT 'User shares lookup within tenant',
    INDEX `idx_status_expire` (`status`, `expire_time`) COMMENT 'Active shares query with expiration',
    INDEX `idx_create_time` (`create_time`) COMMENT 'Time-based ordering for pagination'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='File share metadata table - supplements blockchain storage';
