-- =============================================
-- RecordPlatform Database Migration
-- Version: 1.3.2
-- Description: Add quota rollout audit table for gray expansion governance
-- =============================================

CREATE TABLE IF NOT EXISTS `quota_rollout_audit` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `batch_id` VARCHAR(64) NOT NULL COMMENT '灰度批次ID',
    `tenant_id` BIGINT NOT NULL COMMENT '租户ID',
    `observation_start_time` DATETIME NOT NULL COMMENT '观察窗口开始时间',
    `observation_end_time` DATETIME NOT NULL COMMENT '观察窗口结束时间',
    `sampled_request_count` BIGINT NOT NULL DEFAULT 0 COMMENT '观察样本请求数',
    `exceeded_request_count` BIGINT NOT NULL DEFAULT 0 COMMENT '超限命中数',
    `false_positive_count` BIGINT NOT NULL DEFAULT 0 COMMENT '误判数',
    `rollback_decision` VARCHAR(32) NOT NULL COMMENT '回滚决策：KEEP_ENFORCE/FORCE_SHADOW/EXTEND_OBSERVATION',
    `rollback_reason` VARCHAR(255) DEFAULT NULL COMMENT '回滚或延长观察原因',
    `evidence_link` VARCHAR(512) DEFAULT NULL COMMENT '证据链接（工单/文档/CI）',
    `operator_name` VARCHAR(64) NOT NULL COMMENT '提交人标识',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_quota_rollout_batch_tenant` (`batch_id`, `tenant_id`),
    INDEX `idx_quota_rollout_update_time` (`update_time`),
    INDEX `idx_quota_rollout_rollback_decision` (`rollback_decision`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配额灰度扩容审计表';
