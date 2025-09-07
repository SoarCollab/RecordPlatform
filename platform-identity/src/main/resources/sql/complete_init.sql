-- ==================== RecordPlatform Identity Service Complete Database Initialization Script ====================
-- 描述：为 platform-identity 服务创建独立数据库和完整的表结构
-- 注意：该服务使用独立数据库 platform_identity，与其他服务完全分离
-- 
-- 修复内容：
-- 1. 修复实体类与数据库表字段映射不一致问题
-- 2. 添加缺失的表结构
-- 3. 统一字段命名规范
-- 4. 添加完善的索引和约束
--
-- 执行方式：
-- 1. 直接执行此脚本：mysql -u root -p < complete_init.sql
-- 2. 或在MySQL客户端中：source complete_init.sql
--
-- 包含的表：
-- - account: 用户账户表
-- - oauth_client: OAuth客户端表
-- - oauth_code: OAuth授权码表
-- - operation_log: 用户操作日志表
-- - token_monitor: Token监控表
-- - third_party_account: 第三方登录绑定表
-- - user_session: 用户会话管理表

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
    `role` VARCHAR(20) DEFAULT 'user' COMMENT '角色：admin-管理员，user-普通用户，monitor-监控员',
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

-- OAuth客户端表（修复字段映射）
CREATE TABLE `oauth_client` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `client_id` BIGINT NOT NULL COMMENT '客户端ID（主键）',
    `client_key` VARCHAR(100) NOT NULL COMMENT '客户端标识符',
    `client_secret` VARCHAR(255) NOT NULL COMMENT '客户端密钥（BCrypt加密）',
    `client_name` VARCHAR(200) NOT NULL COMMENT '客户端名称',
    `description` VARCHAR(500) COMMENT '客户端描述',
    `redirect_uris` TEXT COMMENT '重定向URI列表，多个用逗号分隔',
    `scopes` VARCHAR(500) DEFAULT 'read' COMMENT '授权范围：read-读取，write-写入，admin-管理',
    `grant_types` VARCHAR(200) DEFAULT 'authorization_code' COMMENT '授权类型：authorization_code-授权码，refresh_token-刷新令牌，client_credentials-客户端凭证',
    `access_token_validity` INT DEFAULT 3600 COMMENT '访问令牌有效期（秒）',
    `refresh_token_validity` INT DEFAULT 86400 COMMENT '刷新令牌有效期（秒）',
    `auto_approve` TINYINT DEFAULT 0 COMMENT '是否自动授权：0-否，1-是',
    `status` TINYINT DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
    `logo_url` VARCHAR(255) COMMENT '客户端Logo URL',
    `website_url` VARCHAR(255) COMMENT '客户端官网URL',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_by` BIGINT COMMENT '创建人ID',
    `update_by` BIGINT COMMENT '更新人ID',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_client_id` (`client_id`),
    UNIQUE KEY `uk_client_key` (`client_key`),
    KEY `idx_client_name` (`client_name`),
    KEY `idx_status` (`status`),
    KEY `idx_deleted` (`deleted`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='OAuth2.0客户端表';

-- OAuth授权码表（修复字段映射）
CREATE TABLE `oauth_code` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `code` VARCHAR(100) NOT NULL COMMENT '授权码',
    `client_key` VARCHAR(100) NOT NULL COMMENT '客户端标识符',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `redirect_uri` VARCHAR(500) NOT NULL COMMENT '重定向URI',
    `scope` VARCHAR(500) DEFAULT 'read' COMMENT '授权范围',
    `state` VARCHAR(255) COMMENT '状态参数（防CSRF攻击）',
    `status` TINYINT DEFAULT 1 COMMENT '授权码状态：1-有效，0-已使用，-1-已过期',
    `expire_time` DATETIME NOT NULL COMMENT '过期时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `used_time` DATETIME COMMENT '使用时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`),
    KEY `idx_client_user` (`client_key`, `user_id`),
    KEY `idx_expire_time` (`expire_time`),
    KEY `idx_status` (`status`),
    KEY `idx_create_time` (`create_time`),
    CONSTRAINT `fk_oauth_code_user_id` FOREIGN KEY (`user_id`) REFERENCES `account` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='OAuth2.0授权码表';

-- ==================== 审计监控相关表 ====================


-- Token监控表（修复字段映射）
CREATE TABLE `token_monitor` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `token_id` VARCHAR(100) NOT NULL COMMENT 'Token ID',
    `token_type` VARCHAR(50) COMMENT 'Token类型：ACCESS_TOKEN-访问令牌，REFRESH_TOKEN-刷新令牌，CLIENT_TOKEN-客户端令牌，AUTH_CODE-授权码',
    `user_id` BIGINT COMMENT '用户ID',
    `username` VARCHAR(100) COMMENT '用户名',
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
    `handle_status` VARCHAR(20) DEFAULT 'PROCESSED' COMMENT '处理状态：PENDING-待处理，PROCESSING-处理中，PROCESSED-已处理，IGNORED-已忽略',
    `handle_result` TEXT COMMENT '处理结果',
    `handle_remark` TEXT COMMENT '处理备注',
    `handler_id` BIGINT COMMENT '处理人ID',
    `handle_time` DATETIME COMMENT '处理时间',
    `token_create_time` DATETIME COMMENT 'Token创建时间',
    `token_expire_time` DATETIME COMMENT 'Token过期时间',
    `session_id` VARCHAR(100) COMMENT '会话ID',
    `extra_info` JSON COMMENT '额外信息（JSON格式）',
    `event_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '事件时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_token_id` (`token_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_client_id` (`client_id`),
    KEY `idx_event_type` (`event_type`),
    KEY `idx_client_ip` (`client_ip`),
    KEY `idx_is_abnormal` (`is_abnormal`),
    KEY `idx_handle_status` (`handle_status`),
    KEY `idx_risk_score` (`risk_score`),
    KEY `idx_event_time` (`event_time`),
    KEY `idx_create_time` (`create_time`),
    KEY `idx_token_create_time` (`token_create_time`),
    KEY `idx_token_expire_time` (`token_expire_time`),
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

-- ==================== 新增表 ====================

-- 第三方登录绑定表
CREATE TABLE `third_party_account` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `provider` VARCHAR(50) NOT NULL COMMENT '第三方提供商：github-GitHub，google-Google，wechat-微信，qq-QQ',
    `third_party_id` VARCHAR(100) NOT NULL COMMENT '第三方用户ID',
    `third_party_username` VARCHAR(100) COMMENT '第三方用户名',
    `third_party_email` VARCHAR(100) COMMENT '第三方邮箱',
    `third_party_avatar` VARCHAR(255) COMMENT '第三方头像',
    `access_token` VARCHAR(500) COMMENT '访问令牌',
    `refresh_token` VARCHAR(500) COMMENT '刷新令牌',
    `expires_at` DATETIME COMMENT '令牌过期时间',
    `bind_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '绑定时间',
    `last_login_time` DATETIME COMMENT '最后登录时间',
    `status` TINYINT DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_provider_third_party_id` (`provider`, `third_party_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_provider` (`provider`),
    KEY `idx_status` (`status`),
    KEY `idx_deleted` (`deleted`),
    KEY `idx_bind_time` (`bind_time`),
    CONSTRAINT `fk_third_party_account_user_id` FOREIGN KEY (`user_id`) REFERENCES `account` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='第三方登录绑定表';

-- 用户会话管理表
CREATE TABLE `user_session` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `session_id` VARCHAR(100) NOT NULL COMMENT '会话ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `username` VARCHAR(100) COMMENT '用户名',
    `client_ip` VARCHAR(50) COMMENT '客户端IP',
    `user_agent` VARCHAR(1000) COMMENT '用户代理',
    `location` VARCHAR(200) COMMENT '地理位置',
    `device_fingerprint` VARCHAR(200) COMMENT '设备指纹',
    `login_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
    `last_access_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后访问时间',
    `expire_time` DATETIME COMMENT '过期时间',
    `status` TINYINT DEFAULT 1 COMMENT '状态：0-失效，1-有效',
    `logout_reason` VARCHAR(100) COMMENT '注销原因：USER_LOGOUT-用户主动注销，TIMEOUT-超时，FORCE_LOGOUT-强制注销',
    `logout_time` DATETIME COMMENT '注销时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_id` (`session_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_client_ip` (`client_ip`),
    KEY `idx_status` (`status`),
    KEY `idx_login_time` (`login_time`),
    KEY `idx_last_access_time` (`last_access_time`),
    KEY `idx_expire_time` (`expire_time`),
    CONSTRAINT `fk_user_session_user_id` FOREIGN KEY (`user_id`) REFERENCES `account` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户会话管理表';

-- ==================== 索引优化 ====================

-- 用户表相关复合索引
CREATE INDEX `idx_account_role_deleted` ON `account` (`role`, `deleted`);
CREATE INDEX `idx_account_email_deleted` ON `account` (`email`, `deleted`);

-- OAuth表相关复合索引
CREATE INDEX `idx_oauth_client_status_time` ON `oauth_client` (`status`, `create_time`);
CREATE INDEX `idx_oauth_client_deleted_status` ON `oauth_client` (`deleted`, `status`);
CREATE INDEX `idx_oauth_code_user_client` ON `oauth_code` (`user_id`, `client_key`, `status`);
CREATE INDEX `idx_oauth_code_cleanup` ON `oauth_code` (`status`, `expire_time`);


-- Token监控表相关复合索引
CREATE INDEX `idx_token_monitor_user_time` ON `token_monitor` (`user_id`, `event_time`);
CREATE INDEX `idx_token_monitor_abnormal_time` ON `token_monitor` (`is_abnormal`, `event_time`);
CREATE INDEX `idx_token_monitor_risk_time` ON `token_monitor` (`risk_score`, `event_time`);
CREATE INDEX `idx_token_monitor_event_time` ON `token_monitor` (`event_type`, `event_time`);

-- 操作日志表相关复合索引
CREATE INDEX `idx_operation_log_user_time` ON `operation_log` (`user_id`, `operation_time`);
CREATE INDEX `idx_operation_log_module_type` ON `operation_log` (`module`, `operation_type`);
CREATE INDEX `idx_operation_log_status_time` ON `operation_log` (`status`, `operation_time`);

-- 第三方登录表相关复合索引
CREATE INDEX `idx_third_party_user_provider` ON `third_party_account` (`user_id`, `provider`);
CREATE INDEX `idx_third_party_status_deleted` ON `third_party_account` (`status`, `deleted`);

-- 用户会话表相关复合索引
CREATE INDEX `idx_user_session_user_status` ON `user_session` (`user_id`, `status`);
CREATE INDEX `idx_user_session_ip_time` ON `user_session` (`client_ip`, `login_time`);
CREATE INDEX `idx_user_session_cleanup` ON `user_session` (`status`, `expire_time`);

-- ==================== 存储过程 ====================

-- 清理过期的OAuth授权码
DELIMITER $$
CREATE PROCEDURE `CleanExpiredOAuthCodes`()
BEGIN
    DECLARE affected_rows INT DEFAULT 0;

    -- 删除过期的授权码
    DELETE FROM `oauth_code`
    WHERE `expire_time` < NOW()
    AND `status` != 0;

    SET affected_rows = ROW_COUNT();

    -- 记录清理日志
    INSERT INTO `operation_log` (
        `operation_type`, `module`, `description`,
        `status`, `risk_level`, `operation_time`
    ) VALUES (
        'DELETE', 'SYSTEM',
        CONCAT('自动清理过期OAuth授权码，清理数量：', affected_rows),
        0, 'LOW', NOW()
    );

    SELECT affected_rows as deleted_count;
END$$
DELIMITER ;

-- 清理过期的用户会话
DELIMITER $$
CREATE PROCEDURE `CleanExpiredUserSessions`()
BEGIN
    DECLARE affected_rows INT DEFAULT 0;

    -- 更新过期会话状态
    UPDATE `user_session`
    SET `status` = 0, `logout_reason` = 'TIMEOUT', `logout_time` = NOW()
    WHERE `expire_time` < NOW()
    AND `status` = 1;

    SET affected_rows = ROW_COUNT();

    -- 记录清理日志
    INSERT INTO `operation_log` (
        `operation_type`, `module`, `description`,
        `status`, `risk_level`, `operation_time`
    ) VALUES (
        'UPDATE', 'SYSTEM',
        CONCAT('自动清理过期用户会话，清理数量：', affected_rows),
        0, 'LOW', NOW()
    );

    SELECT affected_rows as updated_count;
END$$
DELIMITER ;

-- 归档旧的操作日志
DELIMITER $$
CREATE PROCEDURE `ArchiveOldOperationLogs`(IN retention_days INT)
BEGIN
    DECLARE archive_date DATE DEFAULT DATE_SUB(CURDATE(), INTERVAL retention_days DAY);
    DECLARE affected_rows INT DEFAULT 0;

    -- 标记需要归档的日志
    UPDATE `operation_log`
    SET `description` = CONCAT('[ARCHIVED] ', `description`)
    WHERE `operation_time` < archive_date
    AND `description` NOT LIKE '[ARCHIVED]%';

    SET affected_rows = ROW_COUNT();

    -- 记录归档日志
    INSERT INTO `operation_log` (
        `operation_type`, `module`, `description`,
        `status`, `risk_level`, `operation_time`
    ) VALUES (
        'UPDATE', 'SYSTEM',
        CONCAT('自动归档旧操作日志，归档数量：', affected_rows, '，保留天数：', retention_days),
        0, 'LOW', NOW()
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
    FROM `operation_log`
    WHERE DATE(operation_time) BETWEEN start_date AND end_date
    AND user_id IS NOT NULL
    GROUP BY DATE(operation_time)
    ORDER BY activity_date DESC;
END$$
DELIMITER ;

-- Token监控统计
DELIMITER $$
CREATE PROCEDURE `GetTokenMonitorStats`(
    IN start_time DATETIME,
    IN end_time DATETIME
)
BEGIN
    SELECT
        'token_events' as stat_type,
        COUNT(*) as total_count,
        COUNT(CASE WHEN is_abnormal = 1 THEN 1 END) as abnormal_count,
        AVG(risk_score) as avg_risk_score,
        MAX(risk_score) as max_risk_score
    FROM `token_monitor`
    WHERE event_time BETWEEN start_time AND end_time

    UNION ALL

    SELECT
        'event_types' as stat_type,
        event_type as detail,
        COUNT(*) as count,
        NULL as avg_score,
        NULL as max_score
    FROM `token_monitor`
    WHERE event_time BETWEEN start_time AND end_time
    GROUP BY event_type
    ORDER BY stat_type, count DESC;
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
    `client_key`,
    `client_secret`,
    `client_name`,
    `redirect_uris`,
    `scopes`,
    `grant_types`,
    `access_token_validity`,
    `refresh_token_validity`,
    `status`,
    `description`
) VALUES (
    1001,
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
    1002,
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
       'Fixed field mappings and added missing tables' as improvements,
       NOW() as completion_time;
