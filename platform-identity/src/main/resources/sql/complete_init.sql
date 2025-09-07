-- ==================== RecordPlatform Identity Service Complete Database Initialization Script ====================
-- 创建时间：2025-01-16
-- 版本：v2.0
-- 描述：为 platform-identity 服务创建独立数据库和完整的表结构
-- 注意：该服务使用独立数据库 platform_identity，与其他服务完全分离
-- 
-- 执行方式：
-- 1. 直接执行此脚本：mysql -u root -p < complete_init.sql
-- 2. 或在MySQL客户端中：source complete_init.sql
--
-- 包含的表（仅包含代码中实际使用的表）：
-- - account: 用户账户表
-- - oauth_client: OAuth客户端表
-- - oauth_code: OAuth授权码表
-- - audit_log: 操作审计日志表
-- - token_monitor: Token监控表
-- - operation_log: 操作日志表

-- ==================== 创建独立数据库 ====================
-- DROP DATABASE IF EXISTS `platform_identity`;
-- CREATE DATABASE `platform_identity`
-- DEFAULT CHARACTER SET utf8mb4
-- COLLATE utf8mb4_unicode_ci
-- COMMENT '存证平台认证服务独立数据库';

-- 使用新创建的数据库
USE `platform_identity`;

-- ==================== 用户相关表 ====================

-- 用户账户表
CREATE TABLE `account` (
    `id` BIGINT NOT NULL COMMENT '用户ID',
    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
    `password` VARCHAR(128) NOT NULL COMMENT '密码（BCrypt加密）',
    `email` VARCHAR(100) NOT NULL COMMENT '邮箱',
    `role` VARCHAR(20) DEFAULT 'user' COMMENT '角色：admin-管理员，user-普通用户',
    `avatar` VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
    `register_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_username` (`username`) USING BTREE COMMENT '用户名唯一索引',
    UNIQUE KEY `uk_email` (`email`) USING BTREE COMMENT '邮箱唯一索引',
    KEY `idx_register_time` (`register_time`) USING BTREE COMMENT '注册时间索引',
    KEY `idx_role` (`role`) USING BTREE COMMENT '角色索引',
    KEY `idx_deleted` (`deleted`) USING BTREE COMMENT '删除状态索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户账户信息表';



-- ==================== OAuth2.0 相关表 ====================

-- OAuth客户端表
CREATE TABLE `oauth_client` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `client_id` VARCHAR(100) NOT NULL COMMENT '客户端标识符',
    `client_secret` VARCHAR(255) NOT NULL COMMENT '客户端密钥（BCrypt加密）',
    `client_name` VARCHAR(200) NOT NULL COMMENT '客户端名称',
    `redirect_uris` TEXT COMMENT '重定向URI列表，多个用逗号分隔',
    `scope` VARCHAR(500) DEFAULT 'read' COMMENT '授权范围：read-读取，write-写入，admin-管理',
    `grant_types` VARCHAR(200) DEFAULT 'authorization_code' COMMENT '授权类型：authorization_code-授权码，refresh_token-刷新令牌，client_credentials-客户端凭证',
    `access_token_validity` INT DEFAULT 3600 COMMENT '访问令牌有效期（秒）',
    `refresh_token_validity` INT DEFAULT 86400 COMMENT '刷新令牌有效期（秒）',
    `auto_approve` TINYINT DEFAULT 0 COMMENT '是否自动授权：0-否，1-是',
    `status` TINYINT DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
    `description` VARCHAR(500) COMMENT '客户端描述',
    `logo_url` VARCHAR(255) COMMENT '客户端Logo URL',
    `website_url` VARCHAR(255) COMMENT '客户端官网URL',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_by` BIGINT COMMENT '创建人ID',
    `update_by` BIGINT COMMENT '更新人ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_client_id` (`client_id`),
    KEY `idx_client_name` (`client_name`),
    KEY `idx_status` (`status`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='OAuth2.0客户端表';

-- OAuth授权码表
CREATE TABLE `oauth_code` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `code` VARCHAR(100) NOT NULL COMMENT '授权码',
    `client_id` VARCHAR(100) NOT NULL COMMENT '客户端标识符',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `redirect_uri` VARCHAR(500) NOT NULL COMMENT '重定向URI',
    `scope` VARCHAR(500) DEFAULT 'read' COMMENT '授权范围',
    `state` VARCHAR(255) COMMENT '状态参数（防CSRF攻击）',
    `code_status` TINYINT DEFAULT 0 COMMENT '授权码状态：0-未使用，1-已使用，2-已过期',
    `expires_at` DATETIME NOT NULL COMMENT '过期时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `used_time` DATETIME COMMENT '使用时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`),
    KEY `idx_client_user` (`client_id`, `user_id`),
    KEY `idx_expires_at` (`expires_at`),
    KEY `idx_code_status` (`code_status`),
    KEY `idx_create_time` (`create_time`),
    CONSTRAINT `fk_oauth_code_user_id` FOREIGN KEY (`user_id`) REFERENCES `account` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='OAuth2.0授权码表';



-- ==================== 审计监控相关表 ====================

-- 操作审计日志表
CREATE TABLE `audit_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id` BIGINT COMMENT '操作用户ID',
    `username` VARCHAR(100) COMMENT '操作用户名',
    `operation_type` VARCHAR(50) NOT NULL COMMENT '操作类型：LOGIN-登录，LOGOUT-登出，CREATE-创建，UPDATE-更新，DELETE-删除，VIEW-查看，EXPORT-导出，IMPORT-导入',
    `module` VARCHAR(50) NOT NULL COMMENT '操作模块：USER-用户管理，ROLE-角色管理，OAUTH-OAuth管理，AUTH-认证模块，SYSTEM-系统管理',
    `operation_desc` TEXT COMMENT '操作描述',
    `request_method` VARCHAR(10) COMMENT '请求方法：GET，POST，PUT，DELETE',
    `request_url` VARCHAR(500) COMMENT '请求URL',
    `request_params` TEXT COMMENT '请求参数（JSON格式）',
    `response_result` TEXT COMMENT '响应结果（JSON格式）',
    `operation_status` TINYINT DEFAULT 1 COMMENT '操作状态：0-失败，1-成功',
    `error_message` TEXT COMMENT '错误信息',
    `client_ip` VARCHAR(50) COMMENT '客户端IP',
    `user_agent` VARCHAR(500) COMMENT '用户代理',
    `execution_time` BIGINT COMMENT '执行时间（毫秒）',
    `session_id` VARCHAR(100) COMMENT '会话ID',
    `token_id` VARCHAR(100) COMMENT '令牌ID',
    `business_id` VARCHAR(100) COMMENT '业务ID',
    `business_type` VARCHAR(50) COMMENT '业务类型',
    `risk_level` VARCHAR(20) DEFAULT 'LOW' COMMENT '风险等级：LOW-低风险，MEDIUM-中风险，HIGH-高风险，CRITICAL-严重风险',
    `location` VARCHAR(200) COMMENT '地理位置',
    `device_info` VARCHAR(500) COMMENT '设备信息（JSON格式）',
    `operation_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_operation_type` (`operation_type`),
    KEY `idx_module` (`module`),
    KEY `idx_client_ip` (`client_ip`),
    KEY `idx_operation_time` (`operation_time`),
    KEY `idx_risk_level` (`risk_level`),
    KEY `idx_operation_status` (`operation_status`),
    KEY `idx_session_id` (`session_id`),
    KEY `idx_token_id` (`token_id`),
    KEY `idx_business_type` (`business_type`),
    CONSTRAINT `fk_audit_log_user_id` FOREIGN KEY (`user_id`) REFERENCES `account` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作审计日志表';

-- Token监控表
CREATE TABLE `token_monitor` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `token_id` VARCHAR(100) NOT NULL COMMENT 'Token ID',
    `token_type` VARCHAR(50) COMMENT 'Token类型：ACCESS_TOKEN-访问令牌，REFRESH_TOKEN-刷新令牌，CLIENT_TOKEN-客户端令牌，AUTH_CODE-授权码',
    `user_id` BIGINT COMMENT '用户ID',
    `client_id` VARCHAR(100) COMMENT '客户端ID',
    `event_type` VARCHAR(50) NOT NULL COMMENT '事件类型：CREATE-创建，USE-使用，REFRESH-刷新，REVOKE-撤销，EXPIRE-过期，INVALID-无效，SUSPICIOUS-可疑，ABNORMAL-异常',
    `event_desc` TEXT COMMENT '事件描述',
    `client_ip` VARCHAR(50) COMMENT '客户端IP',
    `user_agent` VARCHAR(500) COMMENT '用户代理',
    `request_url` VARCHAR(500) COMMENT '请求URL',
    `request_method` VARCHAR(10) COMMENT '请求方法',
    `location` VARCHAR(200) COMMENT '地理位置',
    `device_fingerprint` VARCHAR(200) COMMENT '设备指纹',
    `risk_score` INT DEFAULT 0 COMMENT '风险评分：0-100，分数越高风险越大',
    `risk_reason` TEXT COMMENT '风险原因',
    `is_abnormal` TINYINT DEFAULT 0 COMMENT '是否异常：0-正常，1-异常',
    `abnormal_type` VARCHAR(50) COMMENT '异常类型：IP_CHANGE-IP变更，LOCATION_CHANGE-地理位置变更，DEVICE_CHANGE-设备变更，FREQUENCY_ANOMALY-频率异常，TIME_ANOMALY-时间异常，CONCURRENT_LOGIN-并发登录，BRUTE_FORCE-暴力破解，TOKEN_REUSE-令牌重用',
    `process_status` VARCHAR(20) DEFAULT 'PROCESSED' COMMENT '处理状态：PENDING-待处理，PROCESSING-处理中，PROCESSED-已处理，IGNORED-已忽略',
    `process_result` TEXT COMMENT '处理结果',
    `processor_id` BIGINT COMMENT '处理人ID',
    `process_time` DATETIME COMMENT '处理时间',
    `token_created_at` DATETIME COMMENT 'Token创建时间',
    `token_expires_at` DATETIME COMMENT 'Token过期时间',
    `session_id` VARCHAR(100) COMMENT '会话ID',
    `extra_info` JSON COMMENT '额外信息（JSON格式）',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_token_id` (`token_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_client_id` (`client_id`),
    KEY `idx_event_type` (`event_type`),
    KEY `idx_client_ip` (`client_ip`),
    KEY `idx_is_abnormal` (`is_abnormal`),
    KEY `idx_process_status` (`process_status`),
    KEY `idx_risk_score` (`risk_score`),
    KEY `idx_create_time` (`create_time`),
    KEY `idx_token_created_at` (`token_created_at`),
    KEY `idx_token_expires_at` (`token_expires_at`),
    CONSTRAINT `fk_token_monitor_user_id` FOREIGN KEY (`user_id`) REFERENCES `account` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Token监控表';

-- 操作日志表（兼容旧版本）
CREATE TABLE `operation_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id` BIGINT COMMENT '操作用户ID',
    `username` VARCHAR(100) COMMENT '操作用户名',
    `user_role` VARCHAR(20) COMMENT '用户角色',
    `module` VARCHAR(50) COMMENT '操作模块',
    `operation_type` VARCHAR(50) COMMENT '操作类型',
    `description` VARCHAR(500) COMMENT '操作描述',
    `request_url` VARCHAR(500) COMMENT '请求URL',
    `request_method` VARCHAR(10) COMMENT '请求方法',
    `request_param` TEXT COMMENT '请求参数',
    `response_result` TEXT COMMENT '响应结果',
    `client_ip` VARCHAR(50) COMMENT '客户端IP',
    `user_agent` VARCHAR(1000) COMMENT '用户代理',
    `class_name` VARCHAR(200) COMMENT '类名',
    `method_name` VARCHAR(100) COMMENT '方法名',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '操作状态：0-成功，1-失败',
    `error_msg` TEXT COMMENT '错误信息',
    `execution_time` BIGINT COMMENT '执行时间（毫秒）',
    `risk_level` VARCHAR(20) DEFAULT 'LOW' COMMENT '风险等级',
    `operation_time` DATETIME NOT NULL COMMENT '操作时间',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_module` (`module`),
    KEY `idx_operation_type` (`operation_type`),
    KEY `idx_status` (`status`),
    KEY `idx_risk_level` (`risk_level`),
    KEY `idx_operation_time` (`operation_time`),
    KEY `idx_client_ip` (`client_ip`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作日志表（兼容旧版本）';

-- ==================== 索引优化 ====================

-- 用户表相关复合索引
CREATE INDEX `idx_account_role_deleted` ON `account` (`role`, `deleted`);
CREATE INDEX `idx_account_email_deleted` ON `account` (`email`, `deleted`);

-- OAuth表相关复合索引
CREATE INDEX `idx_oauth_client_status_time` ON `oauth_client` (`status`, `create_time`);
CREATE INDEX `idx_oauth_code_user_client` ON `oauth_code` (`user_id`, `client_id`, `code_status`);
CREATE INDEX `idx_oauth_code_cleanup` ON `oauth_code` (`code_status`, `expires_at`);

-- 审计表相关复合索引
CREATE INDEX `idx_audit_log_user_time` ON `audit_log` (`user_id`, `operation_time`);
CREATE INDEX `idx_audit_log_module_type` ON `audit_log` (`module`, `operation_type`);
CREATE INDEX `idx_audit_log_risk_time` ON `audit_log` (`risk_level`, `operation_time`);
CREATE INDEX `idx_audit_log_ip_time` ON `audit_log` (`client_ip`, `operation_time`);

-- Token监控表相关复合索引
CREATE INDEX `idx_token_monitor_user_time` ON `token_monitor` (`user_id`, `create_time`);
CREATE INDEX `idx_token_monitor_abnormal_time` ON `token_monitor` (`is_abnormal`, `create_time`);
CREATE INDEX `idx_token_monitor_risk_time` ON `token_monitor` (`risk_score`, `create_time`);
CREATE INDEX `idx_token_monitor_event_time` ON `token_monitor` (`event_type`, `create_time`);

-- 操作日志表相关复合索引
CREATE INDEX `idx_operation_log_user_time` ON `operation_log` (`user_id`, `operation_time`);
CREATE INDEX `idx_operation_log_module_type` ON `operation_log` (`module`, `operation_type`);
CREATE INDEX `idx_operation_log_status_time` ON `operation_log` (`status`, `operation_time`);

-- ==================== 存储过程 ====================

-- 清理过期的OAuth授权码
DELIMITER $$
CREATE PROCEDURE `CleanExpiredOAuthCodes`()
BEGIN
    DECLARE affected_rows INT DEFAULT 0;

    -- 删除过期的授权码
    DELETE FROM `oauth_code`
    WHERE `expires_at` < NOW()
    AND `code_status` != 1;

    SET affected_rows = ROW_COUNT();

    -- 记录清理日志
    INSERT INTO `audit_log` (
        `operation_type`, `module`, `operation_desc`,
        `operation_status`, `risk_level`
    ) VALUES (
        'DELETE', 'SYSTEM',
        CONCAT('自动清理过期OAuth授权码，清理数量：', affected_rows),
        1, 'LOW'
    );

    SELECT affected_rows as deleted_count;
END$$
DELIMITER ;



-- 归档旧的审计日志
DELIMITER $$
CREATE PROCEDURE `ArchiveOldAuditLogs`()
BEGIN
    DECLARE archive_date DATE DEFAULT DATE_SUB(CURDATE(), INTERVAL 90 DAY);
    DECLARE affected_rows INT DEFAULT 0;

    -- 标记需要归档的日志
    UPDATE `audit_log`
    SET `operation_desc` = CONCAT('[ARCHIVED] ', `operation_desc`)
    WHERE `operation_time` < archive_date
    AND `operation_desc` NOT LIKE '[ARCHIVED]%';

    SET affected_rows = ROW_COUNT();

    -- 记录归档日志
    INSERT INTO `audit_log` (
        `operation_type`, `module`, `operation_desc`,
        `operation_status`, `risk_level`
    ) VALUES (
        'UPDATE', 'SYSTEM',
        CONCAT('自动归档旧审计日志，归档数量：', affected_rows),
        1, 'LOW'
    );

    SELECT affected_rows as archived_count;
END$$
DELIMITER ;

-- 统计用户活跃度
DELIMITER $$
CREATE PROCEDURE `GetUserActivityStats`(
    IN start_date DATE,
    IN end_date DATE
)
BEGIN
    SELECT
        DATE(operation_time) as activity_date,
        COUNT(DISTINCT user_id) as active_users,
        COUNT(*) as total_operations,
        COUNT(CASE WHEN operation_type = 'LOGIN' THEN 1 END) as login_count,
        COUNT(CASE WHEN risk_level = 'HIGH' OR risk_level = 'CRITICAL' THEN 1 END) as high_risk_operations
    FROM `audit_log`
    WHERE DATE(operation_time) BETWEEN start_date AND end_date
    AND user_id IS NOT NULL
    GROUP BY DATE(operation_time)
    ORDER BY activity_date DESC;
END$$
DELIMITER ;

-- ==================== 初始数据插入 ====================

-- 插入默认管理员账户（密码：admin123，已BCrypt加密）
INSERT INTO `account` (`id`, `username`, `password`, `email`, `role`) VALUES
(1, 'admin', '$2a$12$8K1p/a0dUrFHxhX2Ku5gOeB3n9XnLAJx.iFOB2XuQSU0H8/OBVs2i', 'admin@platform-identity.com', 'admin')
ON DUPLICATE KEY UPDATE
    `username` = VALUES(`username`),
    `email` = VALUES(`email`),
    `role` = VALUES(`role`);

-- 插入默认OAuth2客户端（用于测试和开发）
INSERT INTO `oauth_client` (
    `client_id`,
    `client_secret`,
    `client_name`,
    `redirect_uris`,
    `scope`,
    `grant_types`,
    `access_token_validity`,
    `refresh_token_validity`,
    `status`,
    `description`
) VALUES (
    'platform-web-client',
    '$2a$12$8K1p/a0dUrFHxhX2Ku5gOeB3n9XnLAJx.iFOB2XuQSU0H8/OBVs2i', -- 密码：client_secret
    '平台Web客户端',
    'http://localhost:3000/callback,http://localhost:8080/callback,https://platform.example.com/callback',
    'read,write',
    'authorization_code,refresh_token,client_credentials',
    3600,
    86400,
    1,
    '用于平台Web前端的OAuth2.0客户端'
),
(
    'platform-mobile-client',
    '$2a$12$8K1p/a0dUrFHxhX2Ku5gOeB3n9XnLAJx.iFOB2XuQSU0H8/OBVs2i', -- 密码：client_secret
    '平台移动客户端',
    'platform://oauth/callback',
    'read,write',
    'authorization_code,refresh_token',
    7200,
    172800,
    1,
    '用于平台移动端的OAuth2.0客户端'
)
ON DUPLICATE KEY UPDATE
    `client_name` = VALUES(`client_name`),
    `description` = VALUES(`description`),
    `status` = VALUES(`status`);

-- ==================== 完成提示 ====================
SELECT 'Platform Identity Service Database Initialization Completed Successfully!' as message,
       'Database: platform_identity' as database_name,
       NOW() as completion_time;
