-- =============================================
-- 02_distributed_transaction.sql
-- 分布式事务支持（Saga模式 + Outbox模式）
-- 包含：Saga状态机、Outbox事件、消息幂等
-- =============================================

-- ---------------------------------------------
-- Saga 状态表
-- 追踪文件上传Saga的状态转换
-- 支持指数退避重试机制
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `file_saga` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Saga ID',
    `tenant_id`     BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID',
    `file_id`       BIGINT       DEFAULT NULL COMMENT '关联文件ID（文件记录创建前可为空）',
    `request_id`    VARCHAR(64)  NOT NULL COMMENT '请求唯一标识（幂等键）',
    `user_id`       BIGINT       NOT NULL COMMENT '发起上传的用户ID',
    `file_name`     VARCHAR(255) NOT NULL COMMENT '原始文件名',
    `current_step`  VARCHAR(32)  NOT NULL COMMENT '当前步骤：PENDING, MINIO_UPLOADING, MINIO_UPLOADED, CHAIN_STORING, COMPLETED',
    `status`        VARCHAR(32)  NOT NULL COMMENT '状态：RUNNING, SUCCEEDED, COMPENSATING, COMPENSATED, PENDING_COMPENSATION, FAILED',
    `payload`       JSON         DEFAULT NULL COMMENT '步骤数据（文件哈希、路径等）',
    `retry_count`   INT          DEFAULT 0 COMMENT '补偿重试次数',
    `last_error`    TEXT         DEFAULT NULL COMMENT '最后错误信息',
    `next_retry_at` DATETIME     DEFAULT NULL COMMENT '下次重试时间（指数退避调度）',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT 'Saga创建时间',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_request_id` (`request_id`),
    INDEX `idx_tenant_id` (`tenant_id`) COMMENT '租户索引',
    INDEX `idx_status_step` (`status`, `current_step`) COMMENT '状态步骤复合索引',
    INDEX `idx_status_next_retry` (`status`, `next_retry_at`) COMMENT '待重试Saga索引',
    INDEX `idx_file_id` (`file_id`) COMMENT '文件关联索引',
    INDEX `idx_user_id` (`user_id`) COMMENT '用户索引',
    INDEX `idx_create_time` (`create_time`) COMMENT '创建时间索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件上传Saga状态表';


-- ---------------------------------------------
-- Outbox 事件表
-- 存储待发布到消息队列的事件
-- 保证消息可靠投递
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `outbox_event` (
    `id`              VARCHAR(64)  NOT NULL COMMENT '事件UUID',
    `tenant_id`       BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID',
    `trace_id`        VARCHAR(64)  DEFAULT NULL COMMENT '分布式追踪ID',
    `aggregate_type`  VARCHAR(64)  NOT NULL COMMENT '聚合类型：FILE, USER等',
    `aggregate_id`    BIGINT       NOT NULL COMMENT '聚合ID（如file_id）',
    `event_type`      VARCHAR(64)  NOT NULL COMMENT '事件类型：file.stored, file.deleted等',
    `payload`         JSON         NOT NULL COMMENT '事件载荷JSON',
    `status`          VARCHAR(16)  DEFAULT 'PENDING' COMMENT '状态：PENDING, SENT, FAILED',
    `next_attempt_at` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '下次发送尝试时间',
    `retry_count`     INT          DEFAULT 0 COMMENT '发送重试次数',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '事件创建时间',
    `sent_time`       DATETIME     DEFAULT NULL COMMENT '成功发送时间',
    PRIMARY KEY (`id`),
    INDEX `idx_tenant_id` (`tenant_id`) COMMENT '租户索引',
    INDEX `idx_trace_id` (`trace_id`) COMMENT '追踪ID索引',
    INDEX `idx_status_next` (`status`, `next_attempt_at`) COMMENT '待发送事件索引',
    INDEX `idx_aggregate` (`aggregate_type`, `aggregate_id`) COMMENT '聚合索引',
    INDEX `idx_event_type` (`event_type`) COMMENT '事件类型索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Outbox事件表（可靠消息投递）';


-- ---------------------------------------------
-- 已处理消息表
-- 确保消息消费的幂等性
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `processed_message` (
    `message_id`   VARCHAR(64) NOT NULL COMMENT '消息UUID（来自RabbitMQ）',
    `event_type`   VARCHAR(64) DEFAULT NULL COMMENT '处理的事件类型',
    `processed_at` DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '处理时间',
    PRIMARY KEY (`message_id`),
    INDEX `idx_processed_at` (`processed_at`) COMMENT '处理时间索引（用于清理）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='已处理消息表（幂等消费）';


-- ---------------------------------------------
-- 清理已处理消息的存储过程
-- 建议定期执行，防止表膨胀
-- ---------------------------------------------
DELIMITER //
CREATE PROCEDURE IF NOT EXISTS `proc_clean_processed_messages`(IN retention_days INT)
BEGIN
    DELETE FROM processed_message
    WHERE processed_at < DATE_SUB(NOW(), INTERVAL retention_days DAY);
END //
DELIMITER ;

-- 使用示例: CALL proc_clean_processed_messages(7); -- 清理7天前的记录
