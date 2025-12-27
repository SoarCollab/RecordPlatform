-- =============================================================================
-- V1.3.0: Share Audit and File Provenance Tracking
-- =============================================================================
-- Description: Adds file provenance tracking for chain sharing scenarios
--              and audit logging for share access/download/save operations.
-- =============================================================================

-- ---------------------------------------------
-- 1. Add shared_from_user_id to file table
-- Tracks the direct sharer (who shared the file to current user)
-- ---------------------------------------------
ALTER TABLE `file`
ADD COLUMN `shared_from_user_id` BIGINT DEFAULT NULL
COMMENT 'Direct sharer user ID (who shared this file to current user)'
AFTER `origin`;

CREATE INDEX `idx_shared_from` ON `file` (`shared_from_user_id`);


-- ---------------------------------------------
-- 2. File Source Chain Table
-- Tracks complete provenance chain for shared files
-- Enables recursive queries: A -> B -> C -> D
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `file_source` (
    `id`              BIGINT      NOT NULL COMMENT 'Snowflake ID',
    `tenant_id`       BIGINT      NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    `file_id`         BIGINT      NOT NULL COMMENT 'Current file ID',
    `origin_file_id`  BIGINT      NOT NULL COMMENT 'Original uploader file ID',
    `source_file_id`  BIGINT      NOT NULL COMMENT 'Direct source file ID (shared from)',
    `source_user_id`  BIGINT      NOT NULL COMMENT 'User who shared to current user',
    `share_code`      VARCHAR(10) DEFAULT NULL COMMENT 'Share code used for this transfer',
    `depth`           INT         NOT NULL DEFAULT 1 COMMENT 'Chain depth (1=first share, 2=reshare, etc.)',
    `create_time`     DATETIME    NOT NULL COMMENT 'Save time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_id` (`file_id`) COMMENT 'Each file has one source record',
    INDEX `idx_origin` (`origin_file_id`) COMMENT 'Find all files from same origin',
    INDEX `idx_source_user` (`source_user_id`) COMMENT 'Find files shared by user',
    INDEX `idx_tenant` (`tenant_id`) COMMENT 'Tenant isolation'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
COMMENT='File provenance chain - tracks sharing path for chain sharing scenarios';


-- ---------------------------------------------
-- 3. Share Access Log Table
-- Audit trail for share view/download/save operations
-- Enables answering: who accessed, downloaded, saved files
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `share_access_log` (
    `id`              BIGINT       NOT NULL COMMENT 'Snowflake ID',
    `tenant_id`       BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    `share_code`      VARCHAR(10)  NOT NULL COMMENT 'Share code accessed',
    `share_owner_id`  BIGINT       NOT NULL COMMENT 'Share creator user ID',
    `action_type`     TINYINT      NOT NULL COMMENT 'Action: 1=view, 2=download, 3=save',
    `actor_user_id`   BIGINT       DEFAULT NULL COMMENT 'Actor user ID (NULL for anonymous)',
    `actor_ip`        VARCHAR(50)  NOT NULL COMMENT 'Actor IP address',
    `actor_ua`        VARCHAR(500) DEFAULT NULL COMMENT 'User-Agent string',
    `file_hash`       VARCHAR(255) DEFAULT NULL COMMENT 'File hash (for download/save)',
    `file_name`       VARCHAR(255) DEFAULT NULL COMMENT 'File name (for download/save)',
    `access_time`     DATETIME     NOT NULL COMMENT 'Access timestamp',
    PRIMARY KEY (`id`),
    INDEX `idx_share_code` (`share_code`) COMMENT 'Query by share code',
    INDEX `idx_owner_time` (`share_owner_id`, `access_time`) COMMENT 'Owner access history',
    INDEX `idx_actor` (`actor_user_id`) COMMENT 'Actor activity lookup',
    INDEX `idx_tenant` (`tenant_id`) COMMENT 'Tenant isolation',
    INDEX `idx_access_time` (`access_time`) COMMENT 'Time-based cleanup'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
COMMENT='Share access audit log - tracks view/download/save operations';
