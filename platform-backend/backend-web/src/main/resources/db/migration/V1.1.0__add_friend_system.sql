-- =============================================
-- Friend System Tables
-- V1.1.0 - Add friend relationship, request, and file sharing
-- =============================================

-- ---------------------------------------------
-- Friend Request Table (pending friend requests)
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `friend_request` (
    `id`            BIGINT       NOT NULL COMMENT 'ID (Snowflake)',
    `tenant_id`     BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    `requester_id`  BIGINT       NOT NULL COMMENT 'Requester user ID',
    `addressee_id`  BIGINT       NOT NULL COMMENT 'Recipient user ID',
    `message`       VARCHAR(255) DEFAULT NULL COMMENT 'Request message',
    `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT 'Status: 0-pending, 1-accepted, 2-rejected, 3-cancelled',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Request time',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Response time',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT 'Soft delete',
    PRIMARY KEY (`id`),
    INDEX `idx_tenant` (`tenant_id`),
    INDEX `idx_requester` (`requester_id`, `status`),
    INDEX `idx_addressee` (`addressee_id`, `status`),
    INDEX `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Friend request table';

-- ---------------------------------------------
-- Friendship Table (confirmed friend relationships)
-- Uses smaller ID as user_a and larger ID as user_b to ensure uniqueness
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `friendship` (
    `id`            BIGINT       NOT NULL COMMENT 'ID (Snowflake)',
    `tenant_id`     BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    `user_a`        BIGINT       NOT NULL COMMENT 'User A (smaller ID)',
    `user_b`        BIGINT       NOT NULL COMMENT 'User B (larger ID)',
    `request_id`    BIGINT       NOT NULL COMMENT 'Original friend request ID',
    `remark_a`      VARCHAR(50)  DEFAULT NULL COMMENT 'User A remark for User B',
    `remark_b`      VARCHAR(50)  DEFAULT NULL COMMENT 'User B remark for User A',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Friendship establish time',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT 'Soft delete (unfriend)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_friendship` (`tenant_id`, `user_a`, `user_b`),
    INDEX `idx_user_a` (`user_a`),
    INDEX `idx_user_b` (`user_b`),
    INDEX `idx_tenant` (`tenant_id`),
    INDEX `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Friendship table';

-- ---------------------------------------------
-- Friend File Share Table (direct share to friends)
-- Allows sharing files directly to friends without share code
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `friend_file_share` (
    `id`            BIGINT       NOT NULL COMMENT 'ID (Snowflake)',
    `tenant_id`     BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    `sharer_id`     BIGINT       NOT NULL COMMENT 'File owner (sharer)',
    `friend_id`     BIGINT       NOT NULL COMMENT 'Friend receiving the share',
    `file_hashes`   TEXT         NOT NULL COMMENT 'JSON array of file hashes',
    `message`       VARCHAR(255) DEFAULT NULL COMMENT 'Share message',
    `is_read`       TINYINT      NOT NULL DEFAULT 0 COMMENT 'Read status: 0-unread, 1-read',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT 'Status: 0-cancelled, 1-active',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Share time',
    `read_time`     DATETIME     DEFAULT NULL COMMENT 'First read time',
    PRIMARY KEY (`id`),
    INDEX `idx_tenant` (`tenant_id`),
    INDEX `idx_sharer` (`sharer_id`, `status`),
    INDEX `idx_friend` (`friend_id`, `is_read`, `create_time` DESC),
    INDEX `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Friend file share table';

-- ---------------------------------------------
-- Friend module permissions
-- ---------------------------------------------
INSERT INTO `sys_permission` (`tenant_id`, `code`, `name`, `module`, `action`, `description`) VALUES
(0, 'friend:read', 'View Friends', 'friend', 'read', 'View friend list and requests'),
(0, 'friend:write', 'Manage Friends', 'friend', 'write', 'Send/accept/reject friend requests'),
(0, 'friend:share', 'Share to Friends', 'friend', 'share', 'Share files directly to friends');

-- Grant to user role
INSERT INTO `sys_role_permission` (`tenant_id`, `role`, `permission_id`)
SELECT 0, 'user', id FROM `sys_permission` WHERE `tenant_id` = 0 AND `code` IN ('friend:read', 'friend:write', 'friend:share');

-- Grant to admin role
INSERT INTO `sys_role_permission` (`tenant_id`, `role`, `permission_id`)
SELECT 0, 'admin', id FROM `sys_permission` WHERE `tenant_id` = 0 AND `code` IN ('friend:read', 'friend:write', 'friend:share');
