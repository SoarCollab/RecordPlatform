-- =============================================
-- RecordPlatform Database Migration
-- Version: 1.3.0
-- Description: Add quota policy and usage snapshot tables
-- =============================================

CREATE TABLE IF NOT EXISTS `quota_policy` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
    `scope_type` VARCHAR(16) NOT NULL COMMENT '作用域类型：TENANT/USER',
    `scope_id` BIGINT NOT NULL COMMENT '作用域ID（tenantId 或 userId）',
    `max_storage_bytes` BIGINT NOT NULL COMMENT '最大存储配额（字节）',
    `max_file_count` BIGINT NOT NULL COMMENT '最大文件数量配额',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1-启用，0-停用',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_quota_policy_scope` (`tenant_id`, `scope_type`, `scope_id`),
    INDEX `idx_quota_policy_scope_type` (`tenant_id`, `scope_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配额策略表';

CREATE TABLE IF NOT EXISTS `quota_usage_snapshot` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID（0=租户聚合）',
    `used_storage_bytes` BIGINT NOT NULL DEFAULT 0 COMMENT '已使用存储（字节）',
    `used_file_count` BIGINT NOT NULL DEFAULT 0 COMMENT '已使用文件数量',
    `source` VARCHAR(16) NOT NULL DEFAULT 'REALTIME' COMMENT '快照来源：REALTIME/RECON',
    `snapshot_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '快照时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_quota_usage_scope` (`tenant_id`, `user_id`),
    INDEX `idx_quota_usage_snapshot_time` (`snapshot_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配额使用量快照表';
