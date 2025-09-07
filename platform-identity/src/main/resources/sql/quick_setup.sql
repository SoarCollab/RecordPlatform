-- ==================== Platform Identity 快速部署脚本 ====================
-- 创建时间：2025-01-16
-- 用途：快速创建 platform-identity 服务的独立数据库和基础表结构
-- 注意：此脚本仅包含核心表，完整版本请使用 complete_init.sql

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

-- OAuth客户端表
CREATE TABLE `oauth_client` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `client_id` VARCHAR(100) NOT NULL COMMENT '客户端标识符',
    `client_secret` VARCHAR(255) NOT NULL COMMENT '客户端密钥',
    `client_name` VARCHAR(200) NOT NULL COMMENT '客户端名称',
    `redirect_uris` TEXT COMMENT '重定向URI列表',
    `scope` VARCHAR(500) DEFAULT 'read' COMMENT '授权范围',
    `grant_types` VARCHAR(200) DEFAULT 'authorization_code' COMMENT '授权类型',
    `access_token_validity` INT DEFAULT 3600 COMMENT '访问令牌有效期（秒）',
    `refresh_token_validity` INT DEFAULT 86400 COMMENT '刷新令牌有效期（秒）',
    `status` TINYINT DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_client_id` (`client_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='OAuth客户端表';

-- OAuth授权码表
CREATE TABLE `oauth_code` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `code` VARCHAR(100) NOT NULL COMMENT '授权码',
    `client_id` VARCHAR(100) NOT NULL COMMENT '客户端标识符',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `redirect_uri` VARCHAR(500) NOT NULL COMMENT '重定向URI',
    `scope` VARCHAR(500) DEFAULT 'read' COMMENT '授权范围',
    `code_status` TINYINT DEFAULT 0 COMMENT '状态：0-未使用，1-已使用，2-已过期',
    `expires_at` DATETIME NOT NULL COMMENT '过期时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`),
    KEY `idx_client_user` (`client_id`, `user_id`),
    KEY `idx_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='OAuth授权码表';



-- 审计日志表
CREATE TABLE `audit_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id` BIGINT COMMENT '操作用户ID',
    `username` VARCHAR(100) COMMENT '操作用户名',
    `operation_type` VARCHAR(50) NOT NULL COMMENT '操作类型',
    `module` VARCHAR(50) NOT NULL COMMENT '操作模块',
    `operation_desc` TEXT COMMENT '操作描述',
    `client_ip` VARCHAR(50) COMMENT '客户端IP',
    `operation_status` TINYINT DEFAULT 1 COMMENT '操作状态：0-失败，1-成功',
    `operation_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_operation_type` (`operation_type`),
    KEY `idx_operation_time` (`operation_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志表';

-- ==================== 基础索引 ====================
CREATE INDEX `idx_account_role_deleted` ON `account` (`role`, `deleted`);
CREATE INDEX `idx_oauth_code_cleanup` ON `oauth_code` (`code_status`, `expires_at`);

-- ==================== 初始数据 ====================

-- 插入默认管理员账户（用户名：admin，密码：admin123）
INSERT INTO `account` (`id`, `username`, `password`, `email`, `role`) VALUES
(1, 'admin', '$2a$12$8K1p/a0dUrFHxhX2Ku5gOeB3n9XnLAJx.iFOB2XuQSU0H8/OBVs2i', 'admin@platform-identity.com', 'admin');

-- 插入默认OAuth客户端（客户端ID：platform-web-client，密钥：client_secret）
INSERT INTO `oauth_client` (
    `client_id`, 
    `client_secret`, 
    `client_name`, 
    `redirect_uris`, 
    `scope`, 
    `grant_types`, 
    `status`
) VALUES (
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
    WHERE `expires_at` < NOW() 
    AND `code_status` != 1;
    
    SELECT ROW_COUNT() as deleted_count;
END$$
DELIMITER ;



-- ==================== 完成提示 ====================
SELECT 'Platform Identity Quick Setup Completed!' as message,
       'Database: platform_identity' as database_name,
       'Admin User: admin / admin123' as admin_account,
       'OAuth Client: platform-web-client / client_secret' as oauth_client,
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

4. 如需完整功能，请执行 complete_init.sql

5. 生产环境请修改默认密码和客户端密钥
*/
