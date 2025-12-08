-- =====================================================
-- V1.1.0 消息服务模块
-- 包含：公告、站内信、工单
-- =====================================================

-- -----------------------------------------------------
-- 1. 公告表 (announcement)
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `announcement` (
    `id`            BIGINT NOT NULL COMMENT '主键ID(雪花算法)',
    `tenant_id`     BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
    `title`         VARCHAR(200) NOT NULL COMMENT '公告标题',
    `content`       TEXT NOT NULL COMMENT '公告内容(支持Markdown)',
    `priority`      TINYINT NOT NULL DEFAULT 0 COMMENT '优先级: 0-普通, 1-重要, 2-紧急',
    `is_pinned`     TINYINT NOT NULL DEFAULT 0 COMMENT '是否置顶: 0-否, 1-是',
    `publish_time`  DATETIME NULL COMMENT '发布时间(为空则立即发布)',
    `expire_time`   DATETIME NULL COMMENT '过期时间(为空则永不过期)',
    `status`        TINYINT NOT NULL DEFAULT 0 COMMENT '状态: 0-草稿, 1-已发布, 2-已过期',
    `publisher_id`  BIGINT NOT NULL COMMENT '发布者ID',
    `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-未删除, 1-已删除',
    PRIMARY KEY (`id`),
    INDEX `idx_tenant_id` (`tenant_id`),
    INDEX `idx_status_publish` (`status`, `publish_time`),
    INDEX `idx_pinned_time` (`is_pinned`, `publish_time` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统公告表';

-- -----------------------------------------------------
-- 2. 公告已读记录表 (announcement_read)
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `announcement_read` (
    `id`                BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id`         BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
    `announcement_id`   BIGINT NOT NULL COMMENT '公告ID',
    `user_id`           BIGINT NOT NULL COMMENT '用户ID',
    `read_time`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '阅读时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_announcement_user` (`announcement_id`, `user_id`),
    INDEX `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公告已读记录表';

-- -----------------------------------------------------
-- 3. 私信会话表 (conversation)
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `conversation` (
    `id`                BIGINT NOT NULL COMMENT '会话ID(雪花算法)',
    `tenant_id`         BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
    `participant_a`     BIGINT NOT NULL COMMENT '参与者A(ID较小者)',
    `participant_b`     BIGINT NOT NULL COMMENT '参与者B(ID较大者)',
    `last_message_id`   BIGINT NULL COMMENT '最后一条消息ID',
    `last_message_at`   DATETIME NULL COMMENT '最后消息时间',
    `create_time`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_participants` (`tenant_id`, `participant_a`, `participant_b`),
    INDEX `idx_participant_a` (`participant_a`, `last_message_at` DESC),
    INDEX `idx_participant_b` (`participant_b`, `last_message_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='私信会话表';

-- -----------------------------------------------------
-- 4. 私信消息表 (message)
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `message` (
    `id`                BIGINT NOT NULL COMMENT '消息ID(雪花算法)',
    `tenant_id`         BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
    `conversation_id`   BIGINT NOT NULL COMMENT '会话ID',
    `sender_id`         BIGINT NOT NULL COMMENT '发送者ID',
    `receiver_id`       BIGINT NOT NULL COMMENT '接收者ID',
    `content`           TEXT NOT NULL COMMENT '消息内容',
    `content_type`      VARCHAR(20) NOT NULL DEFAULT 'text' COMMENT '内容类型: text, image, file',
    `is_read`           TINYINT NOT NULL DEFAULT 0 COMMENT '是否已读: 0-未读, 1-已读',
    `read_time`         DATETIME NULL COMMENT '阅读时间',
    `create_time`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`           TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-未删除, 1-已删除',
    PRIMARY KEY (`id`),
    INDEX `idx_conversation` (`conversation_id`, `create_time` DESC),
    INDEX `idx_receiver_unread` (`receiver_id`, `is_read`, `create_time` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='私信消息表';

-- -----------------------------------------------------
-- 5. 工单表 (ticket)
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `ticket` (
    `id`            BIGINT NOT NULL COMMENT '工单ID(雪花算法)',
    `tenant_id`     BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
    `ticket_no`     VARCHAR(32) NOT NULL COMMENT '工单编号(如TK202312080001)',
    `title`         VARCHAR(200) NOT NULL COMMENT '工单标题',
    `content`       TEXT NOT NULL COMMENT '工单内容',
    `priority`      TINYINT NOT NULL DEFAULT 1 COMMENT '优先级: 0-低, 1-中, 2-高',
    `status`        TINYINT NOT NULL DEFAULT 0 COMMENT '状态: 0-待处理, 1-处理中, 2-待确认, 3-已完成, 4-已关闭',
    `creator_id`    BIGINT NOT NULL COMMENT '创建者ID',
    `assignee_id`   BIGINT NULL COMMENT '处理人ID',
    `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `close_time`    DATETIME NULL COMMENT '关闭时间',
    `deleted`       TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-未删除, 1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ticket_no` (`ticket_no`),
    INDEX `idx_tenant_id` (`tenant_id`),
    INDEX `idx_creator` (`creator_id`, `status`),
    INDEX `idx_assignee` (`assignee_id`, `status`),
    INDEX `idx_status_time` (`status`, `create_time` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单表';

-- -----------------------------------------------------
-- 6. 工单回复表 (ticket_reply)
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `ticket_reply` (
    `id`            BIGINT NOT NULL COMMENT '回复ID(雪花算法)',
    `tenant_id`     BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
    `ticket_id`     BIGINT NOT NULL COMMENT '工单ID',
    `replier_id`    BIGINT NOT NULL COMMENT '回复者ID',
    `content`       TEXT NOT NULL COMMENT '回复内容',
    `is_internal`   TINYINT NOT NULL DEFAULT 0 COMMENT '是否内部备注: 0-否, 1-是(仅管理员可见)',
    `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`       TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-未删除, 1-已删除',
    PRIMARY KEY (`id`),
    INDEX `idx_ticket` (`ticket_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单回复表';

-- -----------------------------------------------------
-- 7. 工单附件表 (ticket_attachment)
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `ticket_attachment` (
    `id`            BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id`     BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
    `ticket_id`     BIGINT NOT NULL COMMENT '工单ID',
    `reply_id`      BIGINT NULL COMMENT '回复ID(为空则属于工单)',
    `file_id`       BIGINT NOT NULL COMMENT '文件ID(关联file表)',
    `file_name`     VARCHAR(255) NOT NULL COMMENT '文件名',
    `file_size`     BIGINT NULL COMMENT '文件大小(字节)',
    `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_ticket` (`ticket_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单附件表';
