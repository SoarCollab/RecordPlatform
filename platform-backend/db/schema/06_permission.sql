-- =============================================
-- 06_permission.sql
-- 权限控制系统表定义
-- 包含：角色变更审计、权限定义、角色权限映射
-- =============================================

-- ---------------------------------------------
-- 角色变更审计表
-- 记录用户角色变更历史
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `account_role_audit` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '审计记录ID',
    `tenant_id`   BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID',
    `account_id`  BIGINT       NOT NULL COMMENT '账户ID',
    `old_role`    VARCHAR(20)  DEFAULT NULL COMMENT '变更前角色',
    `new_role`    VARCHAR(20)  NOT NULL COMMENT '变更后角色',
    `changed_by`  BIGINT       DEFAULT NULL COMMENT '操作人ID',
    `changed_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '变更时间',
    `reason`      VARCHAR(255) DEFAULT NULL COMMENT '变更原因',
    PRIMARY KEY (`id`),
    INDEX `idx_account_id` (`account_id`) COMMENT '账户ID索引',
    INDEX `idx_tenant_id` (`tenant_id`) COMMENT '租户索引',
    INDEX `idx_changed_at` (`changed_at`) COMMENT '变更时间索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色变更审计表';


-- ---------------------------------------------
-- 权限定义表
-- 定义系统中的所有权限
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_permission` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '权限ID',
    `tenant_id`   BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID（0表示全局权限）',
    `code`        VARCHAR(100) NOT NULL COMMENT '权限码，格式：module:action',
    `name`        VARCHAR(100) NOT NULL COMMENT '权限名称',
    `module`      VARCHAR(50)  NOT NULL COMMENT '模块名：file, ticket, announcement, system等',
    `action`      VARCHAR(50)  NOT NULL COMMENT '操作类型：read, write, delete, admin等',
    `description` VARCHAR(255) DEFAULT NULL COMMENT '权限描述',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`tenant_id`, `code`) COMMENT '租户+权限码唯一',
    INDEX `idx_tenant_id` (`tenant_id`) COMMENT '租户索引',
    INDEX `idx_module` (`module`) COMMENT '模块索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='权限定义表';


-- ---------------------------------------------
-- 角色权限映射表
-- 定义角色与权限的多对多关系
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_role_permission` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '映射ID',
    `tenant_id`     BIGINT      NOT NULL DEFAULT 0 COMMENT '租户ID',
    `role`          VARCHAR(20) NOT NULL COMMENT '角色：user, admin, monitor',
    `permission_id` BIGINT      NOT NULL COMMENT '权限ID',
    `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_permission` (`tenant_id`, `role`, `permission_id`) COMMENT '租户+角色+权限唯一',
    INDEX `idx_tenant_id` (`tenant_id`) COMMENT '租户索引',
    INDEX `idx_role` (`role`) COMMENT '角色索引',
    CONSTRAINT `fk_permission_id` FOREIGN KEY (`permission_id`) REFERENCES `sys_permission` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色权限映射表';


-- ---------------------------------------------
-- 初始化默认权限数据
-- ---------------------------------------------

-- 文件模块权限
INSERT INTO `sys_permission` (`tenant_id`, `code`, `name`, `module`, `action`, `description`) VALUES
(0, 'file:read', '查看文件', 'file', 'read', '查看自己的文件列表'),
(0, 'file:write', '上传文件', 'file', 'write', '上传和修改文件'),
(0, 'file:delete', '删除文件', 'file', 'delete', '删除自己的文件'),
(0, 'file:share', '分享文件', 'file', 'share', '分享文件给其他用户'),
(0, 'file:admin', '文件管理', 'file', 'admin', '管理所有用户的文件');

-- 工单模块权限
INSERT INTO `sys_permission` (`tenant_id`, `code`, `name`, `module`, `action`, `description`) VALUES
(0, 'ticket:read', '查看工单', 'ticket', 'read', '查看自己的工单'),
(0, 'ticket:write', '创建工单', 'ticket', 'write', '创建和回复工单'),
(0, 'ticket:admin', '工单管理', 'ticket', 'admin', '管理所有工单');

-- 公告模块权限
INSERT INTO `sys_permission` (`tenant_id`, `code`, `name`, `module`, `action`, `description`) VALUES
(0, 'announcement:read', '查看公告', 'announcement', 'read', '查看系统公告'),
(0, 'announcement:admin', '公告管理', 'announcement', 'admin', '发布和管理公告');

-- 消息模块权限
INSERT INTO `sys_permission` (`tenant_id`, `code`, `name`, `module`, `action`, `description`) VALUES
(0, 'message:read', '查看消息', 'message', 'read', '查看自己的消息'),
(0, 'message:write', '发送消息', 'message', 'write', '发送消息');

-- 系统模块权限
INSERT INTO `sys_permission` (`tenant_id`, `code`, `name`, `module`, `action`, `description`) VALUES
(0, 'system:logs', '查看日志', 'system', 'logs', '查看操作日志'),
(0, 'system:audit', '查看审计', 'system', 'audit', '查看审计记录'),
(0, 'system:metrics', '查看监控', 'system', 'metrics', '查看系统监控指标'),
(0, 'system:admin', '系统管理', 'system', 'admin', '系统管理权限');


-- ---------------------------------------------
-- 初始化角色权限映射
-- admin: 拥有所有权限
-- user: 基础权限
-- monitor: 监控相关权限
-- ---------------------------------------------

-- 管理员权限（所有权限）
INSERT INTO `sys_role_permission` (`tenant_id`, `role`, `permission_id`)
SELECT 0, 'admin', id FROM `sys_permission` WHERE `tenant_id` = 0;

-- 普通用户权限
INSERT INTO `sys_role_permission` (`tenant_id`, `role`, `permission_id`)
SELECT 0, 'user', id FROM `sys_permission` WHERE `tenant_id` = 0 AND `code` IN (
    'file:read', 'file:write', 'file:delete', 'file:share',
    'ticket:read', 'ticket:write',
    'announcement:read',
    'message:read', 'message:write'
);

-- 监控员权限
INSERT INTO `sys_role_permission` (`tenant_id`, `role`, `permission_id`)
SELECT 0, 'monitor', id FROM `sys_permission` WHERE `tenant_id` = 0 AND `code` IN (
    'file:read',
    'ticket:read',
    'announcement:read',
    'message:read',
    'system:logs', 'system:audit', 'system:metrics'
);


-- ---------------------------------------------
-- 为account表的role字段添加索引
-- ---------------------------------------------
ALTER TABLE `account`
    ADD INDEX `idx_role` (`role`) COMMENT '角色索引';
