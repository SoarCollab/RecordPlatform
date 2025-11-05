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

-- 提示：执行本脚本前需确保连接的默认数据库已创建并指向目标 schema

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
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`),
    KEY `idx_register_time` (`register_time`),
    KEY `idx_role` (`role`),
    KEY `idx_deleted` (`deleted`)
);

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
);

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
);

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
);

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
);

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
    UNIQUE KEY `uk_user_provider_deleted` (`user_id`, `provider`, `deleted`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_provider` (`provider`),
    KEY `idx_status` (`status`),
    KEY `idx_deleted` (`deleted`),
    KEY `idx_bind_time` (`bind_time`),
    CONSTRAINT `fk_third_party_account_user_id` FOREIGN KEY (`user_id`) REFERENCES `account` (`id`) ON DELETE CASCADE
);

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
);

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
CREATE INDEX `idx_third_party_status_deleted` ON `third_party_account` (`status`, `deleted`);

-- 用户会话表相关复合索引
CREATE INDEX `idx_user_session_user_status` ON `user_session` (`user_id`, `status`);
CREATE INDEX `idx_user_session_ip_time` ON `user_session` (`client_ip`, `login_time`);
CREATE INDEX `idx_user_session_cleanup` ON `user_session` (`status`, `expire_time`);
CREATE INDEX `idx_user_session_user_device_status` ON `user_session` (`user_id`, `device_fingerprint`, `status`);

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

-- ==================== API 开放平台与网关相关表（纳入初始化） ====================

-- 1. API应用管理表
CREATE TABLE IF NOT EXISTS `api_application` (
  `id` bigint NOT NULL COMMENT '应用ID',
  `app_name` varchar(100) NOT NULL COMMENT '应用名称',
  `app_code` varchar(50) NOT NULL COMMENT '应用标识码(唯一)',
  `app_description` varchar(500) DEFAULT NULL COMMENT '应用描述',
  `owner_id` bigint NOT NULL COMMENT '所属开发者用户ID',
  `app_type` tinyint NOT NULL DEFAULT '1' COMMENT '应用类型:1-Web应用,2-移动应用,3-服务端应用,4-其他',
  `app_status` tinyint NOT NULL DEFAULT '0' COMMENT '应用状态:0-待审核,1-已启用,2-已禁用,3-已删除',
  `app_icon` varchar(255) DEFAULT NULL COMMENT '应用图标URL',
  `app_website` varchar(255) DEFAULT NULL COMMENT '应用官网',
  `callback_url` varchar(500) DEFAULT NULL COMMENT '回调URL(多个用逗号分隔)',
  `ip_whitelist` text DEFAULT NULL COMMENT 'IP白名单(JSON数组)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `approve_time` datetime DEFAULT NULL COMMENT '审核通过时间',
  `approve_by` bigint DEFAULT NULL COMMENT '审核人ID',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除:0-未删除,1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_app_code` (`app_code`),
  KEY `idx_owner_id` (`owner_id`),
  KEY `idx_app_status` (`app_status`),
  KEY `idx_create_time` (`create_time`)
);

-- 2. API密钥管理表
CREATE TABLE IF NOT EXISTS `api_key` (
  `id` bigint NOT NULL COMMENT '密钥ID',
  `app_id` bigint NOT NULL COMMENT '所属应用ID',
  `api_key` varchar(64) NOT NULL COMMENT 'API密钥(公开)',
  `api_secret` varchar(128) NOT NULL COMMENT 'API密钥(加密存储)',
  `key_name` varchar(100) DEFAULT NULL COMMENT '密钥名称',
  `key_status` tinyint NOT NULL DEFAULT '1' COMMENT '密钥状态:0-已禁用,1-已启用,2-已过期',
  `key_type` tinyint NOT NULL DEFAULT '1' COMMENT '密钥类型:1-正式环境,2-测试环境',
  `expire_time` datetime DEFAULT NULL COMMENT '过期时间(NULL表示永久)',
  `last_used_time` datetime DEFAULT NULL COMMENT '最后使用时间',
  `used_count` bigint NOT NULL DEFAULT '0' COMMENT '使用次数',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除:0-未删除,1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_api_key` (`api_key`),
  KEY `idx_app_id` (`app_id`),
  KEY `idx_key_status` (`key_status`),
  KEY `idx_expire_time` (`expire_time`)
);

-- 3. API接口定义表
CREATE TABLE IF NOT EXISTS `api_interface` (
  `id` bigint NOT NULL COMMENT '接口ID',
  `interface_name` varchar(100) NOT NULL COMMENT '接口名称',
  `interface_code` varchar(100) NOT NULL COMMENT '接口标识码(唯一)',
  `interface_path` varchar(255) NOT NULL COMMENT '接口路径',
  `interface_method` varchar(20) NOT NULL COMMENT 'HTTP方法:GET,POST,PUT,DELETE等',
  `interface_description` varchar(500) DEFAULT NULL COMMENT '接口描述',
  `interface_category` varchar(50) DEFAULT NULL COMMENT '接口分类',
  `service_name` varchar(100) DEFAULT NULL COMMENT '后端服务名称',
  `request_params` text DEFAULT NULL COMMENT '请求参数定义(JSON)',
  `response_example` text DEFAULT NULL COMMENT '响应示例(JSON)',
  `is_auth_required` tinyint NOT NULL DEFAULT '1' COMMENT '是否需要认证:0-否,1-是',
  `rate_limit` int DEFAULT NULL COMMENT '限流次数(每分钟)',
  `timeout` int DEFAULT '30000' COMMENT '超时时间(毫秒)',
  `interface_status` tinyint NOT NULL DEFAULT '1' COMMENT '接口状态:0-已下线,1-已上线,2-维护中',
  `version` varchar(20) DEFAULT 'v1' COMMENT '接口版本',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除:0-未删除,1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_interface_code` (`interface_code`),
  KEY `idx_interface_path` (`interface_path`),
  KEY `idx_interface_status` (`interface_status`),
  KEY `idx_service_name` (`service_name`)
);

-- 4. API权限配置表
CREATE TABLE IF NOT EXISTS `api_permission` (
  `id` bigint NOT NULL COMMENT '权限ID',
  `app_id` bigint NOT NULL COMMENT '应用ID',
  `interface_id` bigint NOT NULL COMMENT '接口ID',
  `permission_status` tinyint NOT NULL DEFAULT '1' COMMENT '权限状态:0-已禁用,1-已启用',
  `expire_time` datetime DEFAULT NULL COMMENT '权限过期时间(NULL表示永久)',
  `grant_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
  `grant_by` bigint DEFAULT NULL COMMENT '授权人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除:0-未删除,1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_app_interface` (`app_id`, `interface_id`),
  KEY `idx_app_id` (`app_id`),
  KEY `idx_interface_id` (`interface_id`),
  KEY `idx_permission_status` (`permission_status`)
);

-- 5. API配额限制表
CREATE TABLE IF NOT EXISTS `api_quota` (
  `id` bigint NOT NULL COMMENT '配额ID',
  `app_id` bigint NOT NULL COMMENT '应用ID',
  `interface_id` bigint DEFAULT NULL COMMENT '接口ID(NULL表示全局配额)',
  `quota_type` tinyint NOT NULL DEFAULT '1' COMMENT '配额类型:1-每分钟,2-每小时,3-每天,4-每月',
  `quota_limit` bigint NOT NULL COMMENT '配额限制(次数)',
  `quota_used` bigint NOT NULL DEFAULT '0' COMMENT '已使用配额',
  `reset_time` datetime DEFAULT NULL COMMENT '配额重置时间',
  `alert_threshold` int DEFAULT '80' COMMENT '告警阈值(百分比)',
  `is_alerted` tinyint NOT NULL DEFAULT '0' COMMENT '是否已告警:0-否,1-是',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除:0-未删除,1-已删除',
  PRIMARY KEY (`id`),
  KEY `idx_app_id` (`app_id`),
  KEY `idx_interface_id` (`interface_id`),
  KEY `idx_reset_time` (`reset_time`)
);

-- 6. API调用日志表
CREATE TABLE IF NOT EXISTS `api_call_log` (
  `id` bigint NOT NULL COMMENT '日志ID',
  `request_id` varchar(64) NOT NULL COMMENT '请求ID(唯一)',
  `app_id` bigint NOT NULL COMMENT '应用ID',
  `api_key` varchar(64) NOT NULL COMMENT 'API密钥',
  `interface_id` bigint NOT NULL COMMENT '接口ID',
  `interface_path` varchar(255) NOT NULL COMMENT '接口路径',
  `request_method` varchar(20) NOT NULL COMMENT '请求方法',
  `request_params` text DEFAULT NULL COMMENT '请求参数',
  `request_ip` varchar(50) NOT NULL COMMENT '请求IP',
  `request_time` datetime NOT NULL COMMENT '请求时间',
  `response_code` int NOT NULL COMMENT '响应状态码',
  `response_time` int NOT NULL COMMENT '响应耗时(毫秒)',
  `response_size` bigint DEFAULT '0' COMMENT '响应大小(字节)',
  `error_message` text DEFAULT NULL COMMENT '错误信息',
  `user_agent` varchar(500) DEFAULT NULL COMMENT '用户代理',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_request_id` (`request_id`),
  KEY `idx_app_id` (`app_id`),
  KEY `idx_interface_id` (`interface_id`),
  KEY `idx_request_time` (`request_time`),
  KEY `idx_response_code` (`response_code`)
);

-- 7. 网关路由配置表
CREATE TABLE IF NOT EXISTS `gateway_route` (
  `id` bigint NOT NULL COMMENT '路由ID',
  `route_name` varchar(100) NOT NULL COMMENT '路由名称',
  `route_code` varchar(100) NOT NULL COMMENT '路由标识码(唯一)',
  `route_path` varchar(255) NOT NULL COMMENT '路由路径(支持通配符)',
  `route_method` varchar(20) DEFAULT NULL COMMENT 'HTTP方法(NULL表示全部)',
  `target_service` varchar(100) NOT NULL COMMENT '目标服务名称',
  `target_path` varchar(255) NOT NULL COMMENT '目标路径',
  `load_balance_strategy` varchar(50) DEFAULT 'ROUND_ROBIN' COMMENT '负载均衡策略:ROUND_ROBIN,WEIGHTED_ROUND_ROBIN,LEAST_CONNECTIONS,CONSISTENT_HASH',
  `route_order` int NOT NULL DEFAULT '100' COMMENT '路由优先级(数值越小优先级越高)',
  `is_strip_prefix` tinyint NOT NULL DEFAULT '1' COMMENT '是否去除前缀:0-否,1-是',
  `timeout` int DEFAULT '30000' COMMENT '超时时间(毫秒)',
  `retry_times` int DEFAULT '0' COMMENT '重试次数',
  `route_status` tinyint NOT NULL DEFAULT '1' COMMENT '路由状态:0-已禁用,1-已启用',
  `route_metadata` text DEFAULT NULL COMMENT '路由元数据(JSON)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除:0-未删除,1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_route_code` (`route_code`),
  KEY `idx_route_path` (`route_path`),
  KEY `idx_route_status` (`route_status`),
  KEY `idx_route_order` (`route_order`)
);

-- 8. 网关服务注册表
CREATE TABLE IF NOT EXISTS `gateway_service` (
  `id` bigint NOT NULL COMMENT '服务ID',
  `service_name` varchar(100) NOT NULL COMMENT '服务名称',
  `service_host` varchar(255) NOT NULL COMMENT '服务主机地址',
  `service_port` int NOT NULL COMMENT '服务端口',
  `service_weight` int NOT NULL DEFAULT '100' COMMENT '服务权重(用于加权负载均衡)',
  `service_status` tinyint NOT NULL DEFAULT '1' COMMENT '服务状态:0-已下线,1-已上线,2-维护中',
  `health_check_url` varchar(255) DEFAULT NULL COMMENT '健康检查URL',
  `last_health_check_time` datetime DEFAULT NULL COMMENT '最后健康检查时间',
  `health_check_status` tinyint DEFAULT '1' COMMENT '健康检查状态:0-异常,1-正常',
  `service_metadata` text DEFAULT NULL COMMENT '服务元数据(JSON)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除:0-未删除,1-已删除',
  PRIMARY KEY (`id`),
  KEY `idx_service_name` (`service_name`),
  KEY `idx_service_status` (`service_status`),
  KEY `idx_health_check_status` (`health_check_status`)
);

-- 9. 网关插件配置表
CREATE TABLE IF NOT EXISTS `gateway_plugin` (
  `id` bigint NOT NULL COMMENT '插件ID',
  `plugin_name` varchar(100) NOT NULL COMMENT '插件名称',
  `plugin_type` varchar(50) NOT NULL COMMENT '插件类型:RATE_LIMIT,CIRCUIT_BREAKER,AUTH,TRANSFORM,LOG等',
  `plugin_config` text NOT NULL COMMENT '插件配置(JSON)',
  `apply_to_route` varchar(100) DEFAULT NULL COMMENT '应用到路由(route_code,NULL表示全局)',
  `apply_to_service` varchar(100) DEFAULT NULL COMMENT '应用到服务(service_name)',
  `plugin_order` int NOT NULL DEFAULT '100' COMMENT '插件执行优先级',
  `plugin_status` tinyint NOT NULL DEFAULT '1' COMMENT '插件状态:0-已禁用,1-已启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除:0-未删除,1-已删除',
  PRIMARY KEY (`id`),
  KEY `idx_plugin_type` (`plugin_type`),
  KEY `idx_apply_to_route` (`apply_to_route`),
  KEY `idx_plugin_status` (`plugin_status`)
);

-- 10. API 路由配置表
CREATE TABLE IF NOT EXISTS `api_route` (
  `id` bigint NOT NULL COMMENT '路由ID',
  `route_name` varchar(100) NOT NULL COMMENT '路由名称',
  `route_path` varchar(255) NOT NULL COMMENT '前端请求路径',
  `target_service` varchar(100) NOT NULL COMMENT '目标服务名称',
  `target_path` varchar(255) DEFAULT NULL COMMENT '转发目标路径',
  `route_type` tinyint NOT NULL DEFAULT '1' COMMENT '路由类型:1-精确,2-前缀,3-正则',
  `http_method` varchar(20) NOT NULL DEFAULT '*' COMMENT 'HTTP方法:* 表示全部',
  `priority` int NOT NULL DEFAULT '100' COMMENT '路由优先级,数值越小优先级越高',
  `route_status` tinyint NOT NULL DEFAULT '1' COMMENT '路由状态:0-禁用,1-启用',
  `require_auth` tinyint NOT NULL DEFAULT '1' COMMENT '是否需要认证:0-否,1-是',
  `enable_rate_limit` tinyint NOT NULL DEFAULT '1' COMMENT '是否启用限流:0-否,1-是',
  `rate_limit` int NOT NULL DEFAULT '100' COMMENT '限流QPS',
  `load_balance` tinyint NOT NULL DEFAULT '1' COMMENT '负载均衡策略:1-轮询,2-随机,3-最少连接,4-一致性哈希',
  `timeout` int NOT NULL DEFAULT '30000' COMMENT '超时时间(毫秒)',
  `retry_times` int NOT NULL DEFAULT '3' COMMENT '重试次数',
  `metadata` text DEFAULT NULL COMMENT '路由元数据(JSON)',
  `route_description` varchar(500) DEFAULT NULL COMMENT '路由描述',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_route_path_method` (`route_path`,`http_method`),
  KEY `idx_route_status` (`route_status`),
  KEY `idx_priority` (`priority`)
);

-- API/网关初始化示例数据
INSERT INTO `api_interface` (`id`, `interface_name`, `interface_code`, `interface_path`, `interface_method`, `interface_description`, `interface_category`, `service_name`, `is_auth_required`, `rate_limit`, `interface_status`)
VALUES
  (1, '用户信息查询', 'user.info.get', '/api/user/info', 'GET', '查询用户基本信息', '用户管理', 'platform-identity', 1, 100, 1),
  (2, '文件上传', 'file.upload.post', '/api/file/upload', 'POST', '上传文件到存证平台', '文件管理', 'platform-backend', 1, 50, 1),
  (3, '文件查询', 'file.info.get', '/api/file/info', 'GET', '查询文件详细信息', '文件管理', 'platform-backend', 1, 200, 1)
ON DUPLICATE KEY UPDATE interface_name=interface_name;

INSERT INTO `gateway_route` (`id`, `route_name`, `route_code`, `route_path`, `target_service`, `target_path`, `load_balance_strategy`, `route_order`, `route_status`)
VALUES
  (1, '用户服务路由', 'route.user', '/gateway/user/**', 'platform-identity', '/api/user/**', 'ROUND_ROBIN', 100, 1),
  (2, '文件服务路由', 'route.file', '/gateway/file/**', 'platform-backend', '/api/file/**', 'ROUND_ROBIN', 100, 1),
  (3, 'FISCO区块链路由', 'route.fisco', '/gateway/blockchain/**', 'platform-fisco', '/api/**', 'ROUND_ROBIN', 100, 1)
ON DUPLICATE KEY UPDATE route_name=route_name;

-- ==================== 流量监控表（纳入初始化） ====================
CREATE TABLE IF NOT EXISTS `traffic_monitor` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `request_id` VARCHAR(64) NOT NULL COMMENT '请求ID（用于链路追踪）',
    `client_ip` VARCHAR(50) NOT NULL COMMENT '客户端IP地址',
    `user_id` BIGINT COMMENT '用户ID（可选）',
    `request_path` VARCHAR(500) NOT NULL COMMENT '请求路径',
    `request_method` VARCHAR(10) NOT NULL COMMENT 'HTTP方法',
    `user_agent` TEXT COMMENT '用户代理',
    `response_status` INT COMMENT '响应状态码',
    `response_time` BIGINT COMMENT '响应时间（毫秒）',
    `request_size` BIGINT COMMENT '请求大小（字节）',
    `response_size` BIGINT COMMENT '响应大小（字节）',
    `is_abnormal` TINYINT(1) DEFAULT 0 COMMENT '是否异常流量',
    `abnormal_type` VARCHAR(50) COMMENT '异常类型',
    `risk_score` INT DEFAULT 0 COMMENT '风险评分 (0-100)',
    `block_status` TINYINT DEFAULT 0 COMMENT '拦截状态 (0-正常, 1-限流, 2-拦截, 3-黑名单)',
    `block_reason` VARCHAR(200) COMMENT '拦截原因',
    `geo_location` VARCHAR(100) COMMENT '地理位置信息',
    `device_fingerprint` VARCHAR(100) COMMENT '设备指纹',
    `request_time` DATETIME NOT NULL COMMENT '请求时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_request_id` (`request_id`),
    KEY `idx_client_ip` (`client_ip`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_request_time` (`request_time`),
    KEY `idx_request_path` (`request_path`),
    KEY `idx_is_abnormal` (`is_abnormal`),
    KEY `idx_block_status` (`block_status`),
    KEY `idx_response_status` (`response_status`),
    KEY `idx_risk_score` (`risk_score`),
    KEY `idx_response_time` (`response_time`)
);

CREATE INDEX `idx_traffic_monitor_ip_time` ON `traffic_monitor` (`client_ip`, `request_time`);
CREATE INDEX `idx_traffic_monitor_user_time` ON `traffic_monitor` (`user_id`, `request_time`);
CREATE INDEX `idx_traffic_monitor_abnormal_time` ON `traffic_monitor` (`is_abnormal`, `request_time`);
CREATE INDEX `idx_traffic_monitor_path_method` ON `traffic_monitor` (`request_path`, `request_method`);
CREATE INDEX `idx_traffic_monitor_geo_time` ON `traffic_monitor` (`geo_location`, `request_time`);

DELIMITER $$
CREATE PROCEDURE `CleanExpiredTrafficData`(IN retention_days INT)
BEGIN
    DECLARE expire_date DATE DEFAULT DATE_SUB(CURDATE(), INTERVAL retention_days DAY);
    DECLARE affected_rows INT DEFAULT 0;

    DELETE FROM `traffic_monitor` 
    WHERE `request_time` < expire_date;

    SET affected_rows = ROW_COUNT();

    INSERT INTO `operation_log` (
        `operation_type`, `module`, `description`,
        `status`, `risk_level`, `operation_time`
    ) VALUES (
        'DELETE', 'SYSTEM',
        CONCAT('自动清理过期流量监控数据，清理数量：', affected_rows, '，保留天数：', retention_days),
        0, 'LOW', NOW()
    );
END$$
DELIMITER ;

DELIMITER $$
CREATE PROCEDURE `GetTrafficStats`(
    IN start_time DATETIME,
    IN end_time DATETIME
)
BEGIN
    SELECT 
        COUNT(*) as total_requests,
        COUNT(DISTINCT client_ip) as unique_ips,
        COUNT(DISTINCT user_id) as unique_users,
        AVG(response_time) as avg_response_time,
        MAX(response_time) as max_response_time,
        COUNT(CASE WHEN response_status >= 400 THEN 1 END) as error_count,
        COUNT(CASE WHEN is_abnormal = 1 THEN 1 END) as abnormal_count,
        COUNT(CASE WHEN block_status > 0 THEN 1 END) as blocked_count
    FROM traffic_monitor 
    WHERE request_time BETWEEN start_time AND end_time;

    SELECT 
        client_ip,
        COUNT(*) as request_count,
        AVG(response_time) as avg_response_time,
        COUNT(CASE WHEN response_status >= 400 THEN 1 END) as error_count,
        MAX(request_time) as last_request_time
    FROM traffic_monitor 
    WHERE request_time BETWEEN start_time AND end_time
    GROUP BY client_ip 
    ORDER BY request_count DESC 
    LIMIT 20;

    SELECT 
        request_path,
        request_method,
        COUNT(*) as request_count,
        AVG(response_time) as avg_response_time,
        COUNT(CASE WHEN response_status >= 400 THEN 1 END) as error_count
    FROM traffic_monitor 
    WHERE request_time BETWEEN start_time AND end_time
    GROUP BY request_path, request_method 
    ORDER BY request_count DESC 
    LIMIT 20;

    SELECT 
        abnormal_type,
        COUNT(*) as count
    FROM traffic_monitor 
    WHERE is_abnormal = 1 
    AND request_time BETWEEN start_time AND end_time
    GROUP BY abnormal_type 
    ORDER BY count DESC;
END$$
DELIMITER ;

DELIMITER $$
CREATE PROCEDURE `GetHighRiskTraffic`(
    IN min_risk_score INT,
    IN start_time DATETIME,
    IN end_time DATETIME,
    IN limit_count INT
)
BEGIN
    SELECT *
    FROM traffic_monitor 
    WHERE risk_score >= min_risk_score
    AND request_time BETWEEN start_time AND end_time
    ORDER BY risk_score DESC, request_time DESC 
    LIMIT limit_count;
END$$
DELIMITER ;

CREATE EVENT IF NOT EXISTS evt_clean_traffic_data
ON SCHEDULE EVERY 1 DAY
STARTS '2025-01-01 02:00:00'
DO CALL CleanExpiredTrafficData(7);

ANALYZE TABLE traffic_monitor;

-- ==================== 完成提示 ====================
SELECT 'Platform Identity Service Database Initialization Completed Successfully!' as message,
       CONCAT('Database: ', DATABASE()) as database_name,
       'Fixed field mappings and added missing tables' as improvements,
       NOW() as completion_time;
