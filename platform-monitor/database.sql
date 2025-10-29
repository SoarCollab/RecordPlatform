/*
 最新完整初始化脚本（monitor 模块）
 - 创建数据库 monitor（如不存在）
 - 初始化 account / client / client_detail / client_ssh 表结构
 - 字符集统一 utf8mb4
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 数据库
CREATE DATABASE IF NOT EXISTS `monitor` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `monitor`;

-- 先删除子表以满足外键依赖
DROP TABLE IF EXISTS `client_detail`;
DROP TABLE IF EXISTS `client_ssh`;
DROP TABLE IF EXISTS `client`;
DROP TABLE IF EXISTS `account`;

-- ============================================================================
-- account 表（与实体 Account 一致，支持 OAuth2 单点登录）
-- ============================================================================
CREATE TABLE `account` (
  `id` INT NOT NULL AUTO_INCREMENT,
  `username` VARCHAR(255) NOT NULL COMMENT '用户名',
  `password` VARCHAR(255) NOT NULL COMMENT '密码（BCrypt）',
  `email` VARCHAR(255) NOT NULL COMMENT '邮箱',
  `role` VARCHAR(50) NOT NULL DEFAULT 'user' COMMENT '角色：user/admin',
  `clients` TEXT NULL COMMENT '可访问客户端ID列表JSON',
  `register_time` DATETIME NULL COMMENT '注册时间',
  `auth_type` VARCHAR(20) NOT NULL DEFAULT 'local' COMMENT '认证类型：local/oauth',
  `oauth_provider` VARCHAR(50) NULL COMMENT 'OAuth提供者标识',
  `oauth_user_id` BIGINT NULL COMMENT 'OAuth用户ID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_email` (`email`),
  KEY `idx_auth_type` (`auth_type`),
  UNIQUE KEY `uk_oauth_user` (`oauth_provider`, `oauth_user_id`),
  CONSTRAINT `chk_auth_type` CHECK (`auth_type` IN ('local','oauth'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================================
-- client 表（客户端注册信息，ID 由业务生成随机8位数）
-- ============================================================================
CREATE TABLE `client` (
  `id` INT NOT NULL COMMENT '客户端ID（业务生成）',
  `name` VARCHAR(255) DEFAULT NULL,
  `token` VARCHAR(255) NOT NULL COMMENT '客户端认证令牌，唯一标识客户端',
  `location` VARCHAR(255) DEFAULT NULL,
  `node` VARCHAR(255) DEFAULT NULL,
  `register_time` DATETIME DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `unique_token` (`token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================================
-- client_detail 表（客户端硬件基础信息）
-- ============================================================================
CREATE TABLE `client_detail` (
  `id` INT NOT NULL,
  `os_arch` VARCHAR(255) DEFAULT NULL COMMENT '操作系统架构',
  `os_name` VARCHAR(255) DEFAULT NULL COMMENT '操作系统名称',
  `os_version` VARCHAR(255) DEFAULT NULL COMMENT '操作系统版本',
  `os_bit` INT DEFAULT NULL COMMENT '操作系统位数',
  `cpu_name` VARCHAR(255) DEFAULT NULL COMMENT 'CPU型号',
  `cpu_core` INT DEFAULT NULL COMMENT 'CPU核心数',
  `memory` DOUBLE DEFAULT NULL COMMENT '内存大小(GB)',
  `disk` DOUBLE DEFAULT NULL COMMENT '磁盘大小(GB)',
  `ip` VARCHAR(255) DEFAULT NULL COMMENT '客户端IP地址',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_client_detail_client` FOREIGN KEY (`id`) REFERENCES `client` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================================
-- client_ssh 表（客户端 SSH 连接配置）
-- ============================================================================
CREATE TABLE `client_ssh` (
  `id` INT NOT NULL,
  `ip` VARCHAR(255) DEFAULT NULL COMMENT 'SSH连接地址',
  `port` INT DEFAULT NULL COMMENT 'SSH连接端口',
  `username` VARCHAR(255) DEFAULT NULL COMMENT 'SSH用户名',
  `password` VARCHAR(255) DEFAULT NULL COMMENT 'SSH密码',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_client_ssh_client` FOREIGN KEY (`id`) REFERENCES `client` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET FOREIGN_KEY_CHECKS = 1;
