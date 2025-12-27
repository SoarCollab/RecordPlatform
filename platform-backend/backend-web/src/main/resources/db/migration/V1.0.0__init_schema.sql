-- =============================================
-- RecordPlatform Database Schema
-- Version: 1.0.0
-- Date: 2024-12-28
-- =============================================
-- All tables are defined with their final structure.
-- No migration history - fresh install only.
-- =============================================

-- =============================================
-- 1. Core Entity Tables
-- =============================================

-- ---------------------------------------------
-- 1.1 Tenant Table
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `tenant` (
    `id`          BIGINT       NOT NULL COMMENT 'Tenant ID',
    `name`        VARCHAR(128) NOT NULL COMMENT 'Tenant name',
    `code`        VARCHAR(64)  NOT NULL COMMENT 'Tenant code',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT 'Status: 1-active, 0-disabled',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Tenant table';

INSERT INTO `tenant` (`id`, `name`, `code`, `status`) VALUES (0, 'Default', 'default', 1);


-- ---------------------------------------------
-- 1.2 Account Table
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `account` (
    `id`            BIGINT       NOT NULL COMMENT 'User ID (Snowflake)',
    `tenant_id`     BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    `username`      VARCHAR(50)  NOT NULL COMMENT 'Username',
    `password`      VARCHAR(128) NOT NULL COMMENT 'Password (BCrypt)',
    `email`         VARCHAR(100) NOT NULL COMMENT 'Email',
    `role`          VARCHAR(20)  DEFAULT 'user' COMMENT 'Role: user/admin/monitor',
    `avatar`        VARCHAR(255) DEFAULT NULL COMMENT 'Avatar URL',
    `register_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT 'Register time',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    `deleted`       TINYINT      DEFAULT 0 COMMENT 'Soft delete: 0-active, 1-deleted',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`),
    INDEX `idx_tenant_id` (`tenant_id`),
    INDEX `idx_role` (`role`),
    INDEX `idx_register_time` (`register_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='User account table';


-- ---------------------------------------------
-- 1.3 File Table
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `file` (
    `id`                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'File ID',
    `tenant_id`            BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    `uid`                  VARCHAR(50)  NOT NULL COMMENT 'External file UID (UUID)',
    `origin`               BIGINT       DEFAULT NULL COMMENT 'Original file ID (for shared files)',
    `shared_from_user_id`  BIGINT       DEFAULT NULL COMMENT 'Direct sharer user ID',
    `file_name`            VARCHAR(255) NOT NULL COMMENT 'File name',
    `classification`       VARCHAR(50)  DEFAULT NULL COMMENT 'File classification',
    `file_param`           VARCHAR(255) DEFAULT NULL COMMENT 'File params JSON',
    `file_hash`            VARCHAR(255) DEFAULT NULL COMMENT 'File hash',
    `status`               INT          NOT NULL COMMENT 'Upload status',
    `transaction_hash`     VARCHAR(255) DEFAULT NULL COMMENT 'Blockchain tx hash',
    `deleted`              INT          DEFAULT 0 NOT NULL COMMENT 'Soft delete flag',
    `create_time`          DATETIME     NOT NULL COMMENT 'Create time',
    PRIMARY KEY (`id`),
    INDEX `idx_tenant_id` (`tenant_id`),
    INDEX `idx_uid` (`uid`),
    INDEX `idx_status` (`status`),
    INDEX `idx_create_time` (`create_time`),
    INDEX `idx_file_tenant_uid` (`tenant_id`, `uid`),
    INDEX `idx_file_tenant_status` (`tenant_id`, `status`),
    INDEX `idx_file_tenant_deleted_time` (`tenant_id`, `deleted`, `create_time`),
    INDEX `idx_shared_from` (`shared_from_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='File storage table';


-- ---------------------------------------------
-- 1.4 Image Store Table
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `image_store` (
    `uid`       VARCHAR(64)  NOT NULL COMMENT 'Image UID',
    `tenant_id` BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    `name`      VARCHAR(255) DEFAULT NULL COMMENT 'Image name',
    `time`      DATETIME     DEFAULT NULL COMMENT 'Upload time',
    PRIMARY KEY (`uid`),
    INDEX `idx_tenant_id` (`tenant_id`),
    INDEX `idx_time` (`time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Image storage table';


-- =============================================
-- 2. File Sharing Tables
-- =============================================

-- ---------------------------------------------
-- 2.1 File Share Table
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `file_share` (
    `id`            BIGINT       NOT NULL COMMENT 'Snowflake ID',
    `tenant_id`     BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    `user_id`       BIGINT       NOT NULL COMMENT 'Share creator user ID',
    `share_code`    VARCHAR(10)  NOT NULL COMMENT 'Share code (6 chars)',
    `share_type`    TINYINT      NOT NULL DEFAULT 0 COMMENT 'Type: 0-PUBLIC, 1-PRIVATE',
    `file_hashes`   TEXT         NOT NULL COMMENT 'JSON array of file hashes',
    `expire_time`   DATETIME     NOT NULL COMMENT 'Expiration time',
    `access_count`  INT          NOT NULL DEFAULT 0 COMMENT 'Access count',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT 'Status: 0-cancelled, 1-active, 2-expired',
    `create_time`   DATETIME     NOT NULL COMMENT 'Create time',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    `deleted`       TINYINT      DEFAULT 0 COMMENT 'Soft delete flag',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_share_code` (`share_code`),
    INDEX `idx_tenant_user` (`tenant_id`, `user_id`),
    INDEX `idx_status_expire` (`status`, `expire_time`),
    INDEX `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='File share metadata table';


-- ---------------------------------------------
-- 2.2 File Source Chain Table (Provenance)
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `file_source` (
    `id`              BIGINT      NOT NULL COMMENT 'Snowflake ID',
    `tenant_id`       BIGINT      NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    `file_id`         BIGINT      NOT NULL COMMENT 'Current file ID',
    `origin_file_id`  BIGINT      NOT NULL COMMENT 'Original uploader file ID',
    `source_file_id`  BIGINT      NOT NULL COMMENT 'Direct source file ID',
    `source_user_id`  BIGINT      NOT NULL COMMENT 'User who shared',
    `share_code`      VARCHAR(10) DEFAULT NULL COMMENT 'Share code used',
    `depth`           INT         NOT NULL DEFAULT 1 COMMENT 'Chain depth',
    `create_time`     DATETIME    NOT NULL COMMENT 'Save time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_id` (`file_id`),
    INDEX `idx_origin` (`origin_file_id`),
    INDEX `idx_source_user` (`source_user_id`),
    INDEX `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='File provenance chain table';


-- ---------------------------------------------
-- 2.3 Share Access Log Table
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `share_access_log` (
    `id`              BIGINT       NOT NULL COMMENT 'Snowflake ID',
    `tenant_id`       BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    `share_code`      VARCHAR(10)  NOT NULL COMMENT 'Share code',
    `share_owner_id`  BIGINT       NOT NULL COMMENT 'Share owner user ID',
    `action_type`     TINYINT      NOT NULL COMMENT 'Action: 1-view, 2-download, 3-save',
    `actor_user_id`   BIGINT       DEFAULT NULL COMMENT 'Actor user ID (NULL=anonymous)',
    `actor_ip`        VARCHAR(50)  NOT NULL COMMENT 'Actor IP',
    `actor_ua`        VARCHAR(500) DEFAULT NULL COMMENT 'User-Agent',
    `file_hash`       VARCHAR(255) DEFAULT NULL COMMENT 'File hash',
    `file_name`       VARCHAR(255) DEFAULT NULL COMMENT 'File name',
    `access_time`     DATETIME     NOT NULL COMMENT 'Access time',
    PRIMARY KEY (`id`),
    INDEX `idx_share_code` (`share_code`),
    INDEX `idx_owner_time` (`share_owner_id`, `access_time`),
    INDEX `idx_actor` (`actor_user_id`),
    INDEX `idx_tenant` (`tenant_id`),
    INDEX `idx_access_time` (`access_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Share access audit log';


-- =============================================
-- 3. Distributed Transaction Tables
-- =============================================

-- ---------------------------------------------
-- 3.1 File Saga Table
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `file_saga` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Saga ID',
    `tenant_id`     BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    `file_id`       BIGINT       DEFAULT NULL COMMENT 'File ID',
    `request_id`    VARCHAR(64)  NOT NULL COMMENT 'Request ID (idempotency key)',
    `user_id`       BIGINT       NOT NULL COMMENT 'User ID',
    `file_name`     VARCHAR(255) NOT NULL COMMENT 'File name',
    `current_step`  VARCHAR(32)  NOT NULL COMMENT 'Current step',
    `status`        VARCHAR(32)  NOT NULL COMMENT 'Status',
    `payload`       JSON         DEFAULT NULL COMMENT 'Step data',
    `retry_count`   INT          DEFAULT 0 COMMENT 'Retry count',
    `last_error`    TEXT         DEFAULT NULL COMMENT 'Last error',
    `next_retry_at` DATETIME     DEFAULT NULL COMMENT 'Next retry time',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_request_id` (`request_id`),
    INDEX `idx_tenant_id` (`tenant_id`),
    INDEX `idx_status_step` (`status`, `current_step`),
    INDEX `idx_status_next_retry` (`status`, `next_retry_at`),
    INDEX `idx_file_id` (`file_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='File upload saga state table';


-- ---------------------------------------------
-- 3.2 Outbox Event Table
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `outbox_event` (
    `id`              VARCHAR(64)  NOT NULL COMMENT 'Event UUID',
    `tenant_id`       BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    `trace_id`        VARCHAR(64)  DEFAULT NULL COMMENT 'Trace ID',
    `aggregate_type`  VARCHAR(64)  NOT NULL COMMENT 'Aggregate type',
    `aggregate_id`    BIGINT       NOT NULL COMMENT 'Aggregate ID',
    `event_type`      VARCHAR(64)  NOT NULL COMMENT 'Event type',
    `payload`         JSON         NOT NULL COMMENT 'Event payload',
    `status`          VARCHAR(16)  DEFAULT 'PENDING' COMMENT 'Status',
    `next_attempt_at` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT 'Next attempt time',
    `retry_count`     INT          DEFAULT 0 COMMENT 'Retry count',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    `sent_time`       DATETIME     DEFAULT NULL COMMENT 'Sent time',
    PRIMARY KEY (`id`),
    INDEX `idx_tenant_id` (`tenant_id`),
    INDEX `idx_trace_id` (`trace_id`),
    INDEX `idx_status_next` (`status`, `next_attempt_at`),
    INDEX `idx_aggregate` (`aggregate_type`, `aggregate_id`),
    INDEX `idx_event_type` (`event_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Outbox event table';


-- ---------------------------------------------
-- 3.3 Processed Message Table
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `processed_message` (
    `message_id`   VARCHAR(64) NOT NULL COMMENT 'Message UUID',
    `event_type`   VARCHAR(64) DEFAULT NULL COMMENT 'Event type',
    `processed_at` DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT 'Process time',
    PRIMARY KEY (`message_id`),
    INDEX `idx_processed_at` (`processed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Processed message table (idempotency)';


-- =============================================
-- 4. Operation Log & Audit Tables
-- =============================================

-- ---------------------------------------------
-- 4.1 Operation Log Table
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_operation_log` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Log ID',
    `module`          VARCHAR(50)  DEFAULT NULL COMMENT 'Module',
    `operation_type`  VARCHAR(20)  DEFAULT NULL COMMENT 'Operation type',
    `description`     VARCHAR(255) DEFAULT NULL COMMENT 'Description',
    `method`          VARCHAR(255) DEFAULT NULL COMMENT 'Method',
    `request_url`     VARCHAR(255) DEFAULT NULL COMMENT 'Request URL',
    `request_method`  VARCHAR(10)  DEFAULT NULL COMMENT 'HTTP method',
    `request_ip`      VARCHAR(50)  DEFAULT NULL COMMENT 'Request IP',
    `request_param`   TEXT         DEFAULT NULL COMMENT 'Request params',
    `response_result` TEXT         DEFAULT NULL COMMENT 'Response result',
    `status`          TINYINT      DEFAULT 0 COMMENT 'Status: 0-success, 1-error',
    `error_msg`       TEXT         DEFAULT NULL COMMENT 'Error message',
    `user_id`         VARCHAR(50)  DEFAULT NULL COMMENT 'User ID',
    `username`        VARCHAR(50)  DEFAULT NULL COMMENT 'Username',
    `operation_time`  DATETIME     DEFAULT NULL COMMENT 'Operation time',
    `execution_time`  BIGINT       DEFAULT NULL COMMENT 'Execution time (ms)',
    PRIMARY KEY (`id`),
    INDEX `idx_operation_time` (`operation_time`),
    INDEX `idx_username` (`username`),
    INDEX `idx_module` (`module`),
    INDEX `idx_operation_type` (`operation_type`),
    INDEX `idx_status` (`status`),
    INDEX `idx_operation_log_user_id` (`user_id`),
    INDEX `idx_operation_log_time_status` (`operation_time`, `status`),
    INDEX `idx_operation_log_request_ip` (`request_ip`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='System operation log';


-- ---------------------------------------------
-- 4.2 Audit Config Table
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_audit_config` (
    `id`           INT          NOT NULL AUTO_INCREMENT COMMENT 'Config ID',
    `config_key`   VARCHAR(50)  NOT NULL COMMENT 'Config key',
    `config_value` VARCHAR(255) NOT NULL COMMENT 'Config value',
    `description`  VARCHAR(255) DEFAULT NULL COMMENT 'Description',
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Audit config table';

INSERT INTO `sys_audit_config` (`config_key`, `config_value`, `description`) VALUES
    ('AUDIT_ENABLED', 'true', 'Enable audit'),
    ('LOG_RETENTION_DAYS', '180', 'Log retention days'),
    ('SENSITIVE_MODULES', 'system,user,permission', 'Sensitive modules'),
    ('HIGH_FREQ_THRESHOLD', '100', 'High frequency threshold (per 5 min)'),
    ('FAILED_LOGIN_THRESHOLD', '5', 'Failed login threshold (per hour)');


-- =============================================
-- 5. Permission System Tables
-- =============================================

-- ---------------------------------------------
-- 5.1 Account Role Audit Table
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `account_role_audit` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Audit ID',
    `tenant_id`   BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    `account_id`  BIGINT       NOT NULL COMMENT 'Account ID',
    `old_role`    VARCHAR(20)  DEFAULT NULL COMMENT 'Old role',
    `new_role`    VARCHAR(20)  NOT NULL COMMENT 'New role',
    `changed_by`  BIGINT       DEFAULT NULL COMMENT 'Changed by user ID',
    `changed_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Change time',
    `reason`      VARCHAR(255) DEFAULT NULL COMMENT 'Reason',
    PRIMARY KEY (`id`),
    INDEX `idx_account_id` (`account_id`),
    INDEX `idx_tenant_id` (`tenant_id`),
    INDEX `idx_changed_at` (`changed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Role change audit table';


-- ---------------------------------------------
-- 5.2 Permission Definition Table
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_permission` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Permission ID',
    `tenant_id`   BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID (0=global)',
    `code`        VARCHAR(100) NOT NULL COMMENT 'Permission code: module:action',
    `name`        VARCHAR(100) NOT NULL COMMENT 'Permission name',
    `module`      VARCHAR(50)  NOT NULL COMMENT 'Module name',
    `action`      VARCHAR(50)  NOT NULL COMMENT 'Action type',
    `description` VARCHAR(255) DEFAULT NULL COMMENT 'Description',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT 'Status: 0-disabled, 1-enabled',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`tenant_id`, `code`),
    INDEX `idx_tenant_id` (`tenant_id`),
    INDEX `idx_module` (`module`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Permission definition table';


-- ---------------------------------------------
-- 5.3 Role Permission Mapping Table
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_role_permission` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT 'Mapping ID',
    `tenant_id`     BIGINT      NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    `role`          VARCHAR(20) NOT NULL COMMENT 'Role: user/admin/monitor',
    `permission_id` BIGINT      NOT NULL COMMENT 'Permission ID',
    `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_permission` (`tenant_id`, `role`, `permission_id`),
    INDEX `idx_tenant_id` (`tenant_id`),
    INDEX `idx_role` (`role`),
    CONSTRAINT `fk_permission_id` FOREIGN KEY (`permission_id`) REFERENCES `sys_permission` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Role permission mapping table';


-- =============================================
-- 6. Message Service Tables
-- =============================================

-- ---------------------------------------------
-- 6.1 Announcement Table
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `announcement` (
    `id`            BIGINT       NOT NULL COMMENT 'ID (Snowflake)',
    `tenant_id`     BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    `title`         VARCHAR(200) NOT NULL COMMENT 'Title',
    `content`       TEXT         NOT NULL COMMENT 'Content (Markdown)',
    `priority`      TINYINT      NOT NULL DEFAULT 0 COMMENT 'Priority: 0-normal, 1-important, 2-urgent',
    `is_pinned`     TINYINT      NOT NULL DEFAULT 0 COMMENT 'Is pinned: 0-no, 1-yes',
    `publish_time`  DATETIME     NULL COMMENT 'Publish time',
    `expire_time`   DATETIME     NULL COMMENT 'Expire time',
    `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT 'Status: 0-draft, 1-published, 2-expired',
    `publisher_id`  BIGINT       NOT NULL COMMENT 'Publisher ID',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT 'Soft delete',
    PRIMARY KEY (`id`),
    INDEX `idx_tenant_id` (`tenant_id`),
    INDEX `idx_status_publish` (`status`, `publish_time`),
    INDEX `idx_pinned_time` (`is_pinned`, `publish_time` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Announcement table';


-- ---------------------------------------------
-- 6.2 Announcement Read Table
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `announcement_read` (
    `id`                BIGINT   NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `tenant_id`         BIGINT   NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    `announcement_id`   BIGINT   NOT NULL COMMENT 'Announcement ID',
    `user_id`           BIGINT   NOT NULL COMMENT 'User ID',
    `read_time`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Read time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_announcement_user` (`announcement_id`, `user_id`),
    INDEX `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Announcement read record';


-- ---------------------------------------------
-- 6.3 Conversation Table
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `conversation` (
    `id`                BIGINT   NOT NULL COMMENT 'ID (Snowflake)',
    `tenant_id`         BIGINT   NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    `participant_a`     BIGINT   NOT NULL COMMENT 'Participant A (smaller ID)',
    `participant_b`     BIGINT   NOT NULL COMMENT 'Participant B (larger ID)',
    `last_message_id`   BIGINT   NULL COMMENT 'Last message ID',
    `last_message_at`   DATETIME NULL COMMENT 'Last message time',
    `create_time`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    `update_time`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_participants` (`tenant_id`, `participant_a`, `participant_b`),
    INDEX `idx_participant_a` (`participant_a`, `last_message_at` DESC),
    INDEX `idx_participant_b` (`participant_b`, `last_message_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Conversation table';


-- ---------------------------------------------
-- 6.4 Message Table
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `message` (
    `id`                BIGINT      NOT NULL COMMENT 'ID (Snowflake)',
    `tenant_id`         BIGINT      NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    `conversation_id`   BIGINT      NOT NULL COMMENT 'Conversation ID',
    `sender_id`         BIGINT      NOT NULL COMMENT 'Sender ID',
    `receiver_id`       BIGINT      NOT NULL COMMENT 'Receiver ID',
    `content`           TEXT        NOT NULL COMMENT 'Content',
    `content_type`      VARCHAR(20) NOT NULL DEFAULT 'text' COMMENT 'Type: text/image/file',
    `is_read`           TINYINT     NOT NULL DEFAULT 0 COMMENT 'Is read: 0-no, 1-yes',
    `read_time`         DATETIME    NULL COMMENT 'Read time',
    `create_time`       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    `deleted`           TINYINT     NOT NULL DEFAULT 0 COMMENT 'Soft delete',
    PRIMARY KEY (`id`),
    INDEX `idx_conversation` (`conversation_id`, `create_time` DESC),
    INDEX `idx_receiver_unread` (`receiver_id`, `is_read`, `create_time` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Message table';


-- ---------------------------------------------
-- 6.5 Ticket Table
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `ticket` (
    `id`            BIGINT       NOT NULL COMMENT 'ID (Snowflake)',
    `tenant_id`     BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    `ticket_no`     VARCHAR(32)  NOT NULL COMMENT 'Ticket number',
    `title`         VARCHAR(200) NOT NULL COMMENT 'Title',
    `content`       TEXT         NOT NULL COMMENT 'Content',
    `priority`      TINYINT      NOT NULL DEFAULT 1 COMMENT 'Priority: 0-low, 1-medium, 2-high',
    `category`      TINYINT      NOT NULL DEFAULT 99 COMMENT 'Category: 0-Bug, 1-Feature, 2-Question, 3-Feedback, 99-Other',
    `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT 'Status: 0-pending, 1-processing, 2-awaiting, 3-completed, 4-closed',
    `creator_id`    BIGINT       NOT NULL COMMENT 'Creator ID',
    `assignee_id`   BIGINT       NULL COMMENT 'Assignee ID',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    `close_time`    DATETIME     NULL COMMENT 'Close time',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT 'Soft delete',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ticket_no` (`ticket_no`),
    INDEX `idx_tenant_id` (`tenant_id`),
    INDEX `idx_creator` (`creator_id`, `status`),
    INDEX `idx_assignee` (`assignee_id`, `status`),
    INDEX `idx_status_time` (`status`, `create_time` DESC),
    INDEX `idx_category` (`category`),
    INDEX `idx_ticket_tenant_creator` (`tenant_id`, `creator_id`),
    INDEX `idx_ticket_tenant_assignee` (`tenant_id`, `assignee_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Ticket table';


-- ---------------------------------------------
-- 6.6 Ticket Reply Table
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `ticket_reply` (
    `id`            BIGINT   NOT NULL COMMENT 'ID (Snowflake)',
    `tenant_id`     BIGINT   NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    `ticket_id`     BIGINT   NOT NULL COMMENT 'Ticket ID',
    `replier_id`    BIGINT   NOT NULL COMMENT 'Replier ID',
    `content`       TEXT     NOT NULL COMMENT 'Content',
    `is_internal`   TINYINT  NOT NULL DEFAULT 0 COMMENT 'Is internal: 0-no, 1-yes',
    `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    `deleted`       TINYINT  NOT NULL DEFAULT 0 COMMENT 'Soft delete',
    PRIMARY KEY (`id`),
    INDEX `idx_ticket` (`ticket_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Ticket reply table';


-- ---------------------------------------------
-- 6.7 Ticket Attachment Table
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `ticket_attachment` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `tenant_id`     BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    `ticket_id`     BIGINT       NOT NULL COMMENT 'Ticket ID',
    `reply_id`      BIGINT       NULL COMMENT 'Reply ID (NULL=ticket attachment)',
    `file_id`       BIGINT       NOT NULL COMMENT 'File ID',
    `file_name`     VARCHAR(255) NOT NULL COMMENT 'File name',
    `file_size`     BIGINT       NULL COMMENT 'File size (bytes)',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    PRIMARY KEY (`id`),
    INDEX `idx_ticket` (`ticket_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Ticket attachment table';


-- =============================================
-- 7. Initial Data - Permissions
-- =============================================

-- File module permissions
INSERT INTO `sys_permission` (`tenant_id`, `code`, `name`, `module`, `action`, `description`) VALUES
(0, 'file:read', 'View Files', 'file', 'read', 'View own files'),
(0, 'file:write', 'Upload Files', 'file', 'write', 'Upload and modify files'),
(0, 'file:delete', 'Delete Files', 'file', 'delete', 'Delete own files'),
(0, 'file:share', 'Share Files', 'file', 'share', 'Share files with others'),
(0, 'file:admin', 'File Admin', 'file', 'admin', 'Manage all files');

-- Ticket module permissions
INSERT INTO `sys_permission` (`tenant_id`, `code`, `name`, `module`, `action`, `description`) VALUES
(0, 'ticket:read', 'View Tickets', 'ticket', 'read', 'View own tickets'),
(0, 'ticket:write', 'Create Tickets', 'ticket', 'write', 'Create and reply tickets'),
(0, 'ticket:admin', 'Ticket Admin', 'ticket', 'admin', 'Manage all tickets');

-- Announcement module permissions
INSERT INTO `sys_permission` (`tenant_id`, `code`, `name`, `module`, `action`, `description`) VALUES
(0, 'announcement:read', 'View Announcements', 'announcement', 'read', 'View announcements'),
(0, 'announcement:admin', 'Announcement Admin', 'announcement', 'admin', 'Manage announcements');

-- Message module permissions
INSERT INTO `sys_permission` (`tenant_id`, `code`, `name`, `module`, `action`, `description`) VALUES
(0, 'message:read', 'View Messages', 'message', 'read', 'View own messages'),
(0, 'message:write', 'Send Messages', 'message', 'write', 'Send messages');

-- System module permissions
INSERT INTO `sys_permission` (`tenant_id`, `code`, `name`, `module`, `action`, `description`) VALUES
(0, 'system:logs', 'View Logs', 'system', 'logs', 'View operation logs'),
(0, 'system:audit', 'View Audit', 'system', 'audit', 'View audit records'),
(0, 'system:metrics', 'View Metrics', 'system', 'metrics', 'View system metrics'),
(0, 'system:admin', 'System Admin', 'system', 'admin', 'System administration');

-- Admin role: all permissions
INSERT INTO `sys_role_permission` (`tenant_id`, `role`, `permission_id`)
SELECT 0, 'admin', id FROM `sys_permission` WHERE `tenant_id` = 0;

-- User role: basic permissions
INSERT INTO `sys_role_permission` (`tenant_id`, `role`, `permission_id`)
SELECT 0, 'user', id FROM `sys_permission` WHERE `tenant_id` = 0 AND `code` IN (
    'file:read', 'file:write', 'file:delete', 'file:share',
    'ticket:read', 'ticket:write',
    'announcement:read',
    'message:read', 'message:write'
);

-- Monitor role: read-only permissions
INSERT INTO `sys_role_permission` (`tenant_id`, `role`, `permission_id`)
SELECT 0, 'monitor', id FROM `sys_permission` WHERE `tenant_id` = 0 AND `code` IN (
    'file:read',
    'ticket:read',
    'announcement:read',
    'message:read',
    'system:logs', 'system:audit', 'system:metrics'
);


-- =============================================
-- 8. Stored Procedures
-- =============================================

DELIMITER //

-- Clean processed messages
CREATE PROCEDURE IF NOT EXISTS `proc_clean_processed_messages`(IN retention_days INT)
BEGIN
    DELETE FROM processed_message
    WHERE processed_at < DATE_SUB(NOW(), INTERVAL retention_days DAY);
END //

-- Clean old operation logs
CREATE PROCEDURE IF NOT EXISTS `proc_clean_old_operation_logs`(IN days INT)
BEGIN
    DELETE FROM sys_operation_log
    WHERE operation_time < DATE_SUB(NOW(), INTERVAL days DAY);
END //

DELIMITER ;


-- =============================================
-- 9. Views
-- =============================================

-- Operation log statistics view
CREATE OR REPLACE VIEW `v_operation_log_stats` AS
SELECT
    module,
    operation_type,
    COUNT(*)                  AS operation_count,
    SUM(IF(status = 0, 1, 0)) AS success_count,
    SUM(IF(status = 1, 1, 0)) AS error_count,
    AVG(execution_time)       AS avg_execution_time,
    MAX(execution_time)       AS max_execution_time,
    MIN(execution_time)       AS min_execution_time,
    DATE(operation_time)      AS operation_date
FROM sys_operation_log
GROUP BY module, operation_type, DATE(operation_time);

-- User operation statistics view
CREATE OR REPLACE VIEW `v_user_operation_stats` AS
SELECT
    user_id,
    username,
    COUNT(*)                              AS operation_count,
    COUNT(DISTINCT DATE(operation_time))  AS active_days,
    MIN(operation_time)                   AS first_operation,
    MAX(operation_time)                   AS last_operation,
    SUM(IF(status = 1, 1, 0))             AS error_count
FROM sys_operation_log
WHERE user_id IS NOT NULL
GROUP BY user_id, username;

-- Module access frequency view
CREATE OR REPLACE VIEW `v_module_access_frequency` AS
SELECT
    module,
    COUNT(*)                                          AS access_count,
    COUNT(DISTINCT user_id)                           AS unique_users,
    COUNT(DISTINCT DATE(operation_time))              AS active_days,
    COUNT(*) / COUNT(DISTINCT DATE(operation_time))   AS avg_daily_access
FROM sys_operation_log
GROUP BY module
ORDER BY access_count DESC;

-- User statistics view
CREATE OR REPLACE VIEW `v_user_stats` AS
SELECT
    a.id,
    a.username,
    a.email,
    a.role,
    a.register_time,
    COUNT(DISTINCT s.id)                   AS operation_count,
    MAX(s.operation_time)                  AS last_active_time,
    DATEDIFF(NOW(), a.register_time)       AS days_since_register,
    DATEDIFF(NOW(), MAX(s.operation_time)) AS days_since_last_active
FROM account a
LEFT JOIN sys_operation_log s ON a.id = s.user_id
WHERE a.deleted = 0
GROUP BY a.id, a.username, a.email, a.role, a.register_time;
