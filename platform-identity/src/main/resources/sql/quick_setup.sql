-- ==================== Platform Identity 快速部署脚本（修复版） ====================
-- 用途：快速创建 platform-identity 服务的独立数据库和基础表结构
-- 修复内容：修复实体类与数据库表字段映射不一致问题，统一字段命名规范

-- 创建独立数据库
DROP DATABASE IF EXISTS `platform_identity`;
CREATE DATABASE `platform_identity` 
DEFAULT CHARACTER SET utf8mb4 
COLLATE utf8mb4_unicode_ci 
COMMENT '存证平台认证服务独立数据库';

USE `platform_identity`;

-- ==================== 核心表结构 ====================

-- 用户账户表
CREATE TABLE `account` (
    `id` BIGINT NOT NULL COMMENT '用户ID',
    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
    `password` VARCHAR(128) NOT NULL COMMENT '密码（BCrypt加密）',
    `email` VARCHAR(100) NOT NULL COMMENT '邮箱',
    `role` VARCHAR(20) DEFAULT 'user' COMMENT '角色',
    `avatar` VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
    `register_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`),
    KEY `idx_role` (`role`),
    KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户账户表';

-- OAuth客户端表（修复字段映射）
CREATE TABLE `oauth_client` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `client_id` BIGINT NOT NULL COMMENT '客户端ID（主键）',
    `client_key` VARCHAR(100) NOT NULL COMMENT '客户端标识符',
    `client_secret` VARCHAR(255) NOT NULL COMMENT '客户端密钥',
    `client_name` VARCHAR(200) NOT NULL COMMENT '客户端名称',
    `description` VARCHAR(500) COMMENT '客户端描述',
    `redirect_uris` TEXT COMMENT '重定向URI列表',
    `scopes` VARCHAR(500) DEFAULT 'read' COMMENT '授权范围',
    `grant_types` VARCHAR(200) DEFAULT 'authorization_code' COMMENT '授权类型',
    `access_token_validity` INT DEFAULT 3600 COMMENT '访问令牌有效期（秒）',
    `refresh_token_validity` INT DEFAULT 86400 COMMENT '刷新令牌有效期（秒）',
    `auto_approve` TINYINT DEFAULT 0 COMMENT '是否自动授权：0-否，1-是',
    `status` TINYINT DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_client_id` (`client_id`),
    UNIQUE KEY `uk_client_key` (`client_key`),
    KEY `idx_status` (`status`),
    KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='OAuth客户端表';

-- OAuth授权码表（修复字段映射）
CREATE TABLE `oauth_code` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `code` VARCHAR(100) NOT NULL COMMENT '授权码',
    `client_key` VARCHAR(100) NOT NULL COMMENT '客户端标识符',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `redirect_uri` VARCHAR(500) NOT NULL COMMENT '重定向URI',
    `scope` VARCHAR(500) DEFAULT 'read' COMMENT '授权范围',
    `state` VARCHAR(255) COMMENT '状态参数',
    `status` TINYINT DEFAULT 1 COMMENT '状态：1-有效，0-已使用，-1-已过期',
    `expire_time` DATETIME NOT NULL COMMENT '过期时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `used_time` DATETIME COMMENT '使用时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`),
    KEY `idx_client_user` (`client_key`, `user_id`),
    KEY `idx_expire_time` (`expire_time`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='OAuth授权码表';

-- Token监控表（简化版）
CREATE TABLE `token_monitor` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `token_id` VARCHAR(100) NOT NULL COMMENT 'Token ID',
    `token_type` VARCHAR(50) COMMENT 'Token类型',
    `user_id` BIGINT COMMENT '用户ID',
    `username` VARCHAR(100) COMMENT '用户名',
    `client_id` VARCHAR(100) COMMENT '客户端ID',
    `event_type` VARCHAR(50) NOT NULL COMMENT '事件类型',
    `event_desc` TEXT COMMENT '事件描述',
    `client_ip` VARCHAR(50) COMMENT '客户端IP',
    `risk_score` INT DEFAULT 0 COMMENT '风险评分',
    `is_abnormal` TINYINT DEFAULT 0 COMMENT '是否异常',
    `abnormal_type` VARCHAR(50) COMMENT '异常类型',
    `handle_status` VARCHAR(20) DEFAULT 'PROCESSED' COMMENT '处理状态',
    `handle_result` TEXT COMMENT '处理结果',
    `handle_remark` TEXT COMMENT '处理备注',
    `handler_id` BIGINT COMMENT '处理人ID',
    `handle_time` DATETIME COMMENT '处理时间',
    `token_create_time` DATETIME COMMENT 'Token创建时间',
    `token_expire_time` DATETIME COMMENT 'Token过期时间',
    `event_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '事件时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_token_id` (`token_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_event_type` (`event_type`),
    KEY `idx_event_time` (`event_time`)
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
    KEY `idx_operation_type` (`operation_type`),
    KEY `idx_operation_time` (`operation_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作日志表（兼容旧版本）';

-- ==================== 基础索引 ====================
CREATE INDEX `idx_account_role_deleted` ON `account` (`role`, `deleted`);
CREATE INDEX `idx_oauth_client_deleted_status` ON `oauth_client` (`deleted`, `status`);
CREATE INDEX `idx_oauth_code_cleanup` ON `oauth_code` (`status`, `expire_time`);
CREATE INDEX `idx_token_monitor_abnormal_time` ON `token_monitor` (`is_abnormal`, `event_time`);

-- ==================== 初始数据 ====================

-- 插入默认管理员账户（用户名：admin，密码：admin123）
INSERT INTO `account` (`id`, `username`, `password`, `email`, `role`) VALUES
(1, 'admin', '$2a$12$8K1p/a0dUrFHxhX2Ku5gOeB3n9XnLAJx.iFOB2XuQSU0H8/OBVs2i', 'admin@platform-identity.com', 'admin');

-- 插入默认OAuth客户端（客户端标识：platform-web-client，密钥：client_secret）
INSERT INTO `oauth_client` (
    `client_id`,
    `client_key`, 
    `client_secret`, 
    `client_name`, 
    `redirect_uris`, 
    `scopes`, 
    `grant_types`, 
    `status`
) VALUES (
    1001,
    'platform-web-client', 
    '$2a$12$8K1p/a0dUrFHxhX2Ku5gOeB3n9XnLAJx.iFOB2XuQSU0H8/OBVs2i',
    '平台Web客户端', 
    'http://localhost:3000/callback,http://localhost:8080/callback', 
    'read,write', 
    'authorization_code,refresh_token,client_credentials', 
    1
);

-- ==================== 清理存储过程 ====================

-- 清理过期OAuth授权码
DELIMITER $$
CREATE PROCEDURE `CleanExpiredOAuthCodes`()
BEGIN
    DELETE FROM `oauth_code` 
    WHERE `expire_time` < NOW() 
    AND `status` != 0;
    
    SELECT ROW_COUNT() as deleted_count;
END$$
DELIMITER ;

-- ==================== 完成提示 ====================
SELECT 'Platform Identity Quick Setup (Fixed) Completed!' as message,
       'Database: platform_identity' as database_name,
       'Admin User: admin / admin123' as admin_account,
       'OAuth Client: platform-web-client / client_secret' as oauth_client,
       'Fixed field mapping issues' as improvements,
       NOW() as completion_time;

-- ==================== 使用说明 ====================
/*
快速部署完成后，请执行以下步骤：

1. 更新配置文件中的数据库连接：
   spring.datasource.druid.url=jdbc:mysql://localhost:3306/platform_identity

2. 重启应用服务

3. 测试登录：
   用户名：admin
   密码：admin123

4. OAuth客户端信息：
   客户端标识：platform-web-client
   客户端密钥：client_secret

5. 主要修复内容：
   - 修复了OAuth客户端表和授权码表的字段映射问题
   - 统一了字段命名规范
   - 添加了逻辑删除字段
   - 优化了索引设计

6. 如需完整功能，请执行 complete_init.sql

7. 生产环境请修改默认密码和客户端密钥
*/
