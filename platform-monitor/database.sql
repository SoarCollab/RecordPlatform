/*
 Navicat MySQL Data Transfer

 Source Server         : 本地测试环境
 Source Server Type    : MySQL
 Source Server Version : 80034 (8.0.34)
 Source Host           : localhost:3306
 Source Schema         : test

 Target Server Type    : MySQL
 Target Server Version : 80034 (8.0.34)
 File Encoding         : 65001

 Date: 07/08/2023 00:03:19
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for db_account
-- ----------------------------
DROP TABLE IF EXISTS `db_account`;
CREATE TABLE `db_account` (
  `id` int NOT NULL AUTO_INCREMENT,
  `username` varchar(255) DEFAULT NULL,
  `email` varchar(255) DEFAULT NULL,
  `password` varchar(255) DEFAULT NULL,
  `role` varchar(255) DEFAULT NULL,
  `register_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `unique_email` (`email`),
  UNIQUE KEY `unique_username` (`username`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of db_account
-- ----------------------------
BEGIN;
COMMIT;

-- ----------------------------
-- Table structure for client
-- ----------------------------
DROP TABLE IF EXISTS `client`;
CREATE TABLE `client` (
  `id` int NOT NULL,
  `name` varchar(255) DEFAULT NULL,
  `token` varchar(255) NOT NULL COMMENT '客户端认证令牌，唯一标识客户端',
  `location` varchar(255) DEFAULT NULL,
  `node` varchar(255) DEFAULT NULL,
  `register_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `unique_token` (`token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of client
-- ----------------------------
BEGIN;
COMMIT;

-- ----------------------------
-- Table structure for client_detail
-- ----------------------------
DROP TABLE IF EXISTS `client_detail`;
CREATE TABLE `client_detail` (
  `id` int NOT NULL,
  `os_arch` varchar(255) DEFAULT NULL COMMENT '操作系统架构',
  `os_name` varchar(255) DEFAULT NULL COMMENT '操作系统名称',
  `os_version` varchar(255) DEFAULT NULL COMMENT '操作系统版本',
  `os_bit` int DEFAULT NULL COMMENT '操作系统位数',
  `cpu_name` varchar(255) DEFAULT NULL COMMENT 'CPU型号',
  `cpu_core` int DEFAULT NULL COMMENT 'CPU核心数',
  `memory` double DEFAULT NULL COMMENT '内存大小(GB)',
  `disk` double DEFAULT NULL COMMENT '磁盘大小(GB)',
  `ip` varchar(255) DEFAULT NULL COMMENT '客户端IP地址',
  PRIMARY KEY (`id`),
  FOREIGN KEY (`id`) REFERENCES `client` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of client_detail
-- ----------------------------
BEGIN;
COMMIT;

-- ----------------------------
-- Table structure for client_ssh
-- ----------------------------
DROP TABLE IF EXISTS `client_ssh`;
CREATE TABLE `client_ssh` (
  `id` int NOT NULL,
  `ip` varchar(255) DEFAULT NULL COMMENT 'SSH连接地址',
  `port` int DEFAULT NULL COMMENT 'SSH连接端口',
  `username` varchar(255) DEFAULT NULL COMMENT 'SSH用户名',
  `password` varchar(255) DEFAULT NULL COMMENT 'SSH密码',
  PRIMARY KEY (`id`),
  FOREIGN KEY (`id`) REFERENCES `client` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of client_ssh
-- ----------------------------
BEGIN;
COMMIT;

SET FOREIGN_KEY_CHECKS = 1;
