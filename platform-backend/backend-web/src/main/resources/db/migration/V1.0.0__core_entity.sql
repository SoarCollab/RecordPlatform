-- =============================================
-- V1.0.0__core_entity.sql
-- 核心业务实体表定义
-- 包含：用户账户、文件存储、图片存储
-- =============================================

-- ---------------------------------------------
-- 用户账户表
-- 存储用户基本信息和认证数据
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `account` (
    `id`            BIGINT       NOT NULL COMMENT '用户ID（雪花算法生成）',
    `tenant_id`     BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID',
    `username`      VARCHAR(50)  NOT NULL COMMENT '用户名',
    `password`      VARCHAR(128) NOT NULL COMMENT '密码（BCrypt加密）',
    `email`         VARCHAR(100) NOT NULL COMMENT '邮箱',
    `role`          VARCHAR(20)  DEFAULT 'user' COMMENT '角色：user/admin',
    `avatar`        VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
    `register_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT      DEFAULT 0 COMMENT '软删除标记：0-正常，1-已删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_username` (`username`) USING BTREE COMMENT '用户名唯一索引',
    UNIQUE KEY `uk_email` (`email`) USING BTREE COMMENT '邮箱唯一索引',
    INDEX `idx_tenant_id` (`tenant_id`) COMMENT '租户索引',
    INDEX `idx_register_time` (`register_time`) USING BTREE COMMENT '注册时间索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户账户信息表';


-- ---------------------------------------------
-- 文件存储表
-- 存储用户上传文件的元数据
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `file` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '文件ID',
    `tenant_id`      BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID',
    `uid`            VARCHAR(50)  NOT NULL COMMENT '文件对外唯一标识（UUID）',
    `origin`         BIGINT       DEFAULT NULL COMMENT '来源文件ID（标识分享文件原始来源）',
    `file_name`      VARCHAR(255) NOT NULL COMMENT '文件名',
    `classification` VARCHAR(50)  DEFAULT NULL COMMENT '文件分类',
    `file_param`     VARCHAR(255) DEFAULT NULL COMMENT '文件参数JSON：类型、描述、大小等',
    `file_hash`      VARCHAR(255) DEFAULT NULL COMMENT '文件哈希值',
    `status`         INT          NOT NULL COMMENT '上传状态：见FileUploadStatus枚举',
    `transaction_hash`  VARCHAR(255) DEFAULT NULL COMMENT '区块链交易哈希',
    `deleted`        INT          DEFAULT 0 NOT NULL COMMENT '软删除标记',
    `create_time`    DATETIME     NOT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_id` (`id`),
    INDEX `idx_tenant_id` (`tenant_id`) COMMENT '租户索引',
    INDEX `idx_uid` (`uid`) COMMENT 'UID索引',
    INDEX `idx_status` (`status`) COMMENT '状态索引',
    INDEX `idx_create_time` (`create_time`) COMMENT '创建时间索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='文件信息表';


-- ---------------------------------------------
-- 图片存储表
-- 存储图片资源的元数据
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `image_store` (
    `uid`       VARCHAR(64)  NOT NULL COMMENT '图片唯一标识',
    `tenant_id` BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID',
    `name`      VARCHAR(255) DEFAULT NULL COMMENT '图片名称',
    `time`      DATETIME     DEFAULT NULL COMMENT '上传时间',
    PRIMARY KEY (`uid`) USING BTREE,
    INDEX `idx_tenant_id` (`tenant_id`) COMMENT '租户索引',
    INDEX `idx_time` (`time`) USING BTREE COMMENT '时间索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='图片存储信息表';
