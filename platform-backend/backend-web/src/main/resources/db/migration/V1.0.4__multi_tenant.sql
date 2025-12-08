-- =============================================
-- V1.0.4__multi_tenant.sql
-- 多租户支持
-- 包含：租户主表、默认租户数据
-- 注意：tenant_id字段已在各表定义中包含
-- =============================================

-- ---------------------------------------------
-- 租户主表
-- 管理系统租户信息
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `tenant` (
    `id`          BIGINT       NOT NULL COMMENT '租户ID',
    `name`        VARCHAR(128) NOT NULL COMMENT '租户名称',
    `code`        VARCHAR(64)  NOT NULL COMMENT '租户标识码',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-启用，0-禁用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户主表';


-- ---------------------------------------------
-- 默认租户数据
-- 用于现有数据兼容
-- ---------------------------------------------
INSERT INTO `tenant` (`id`, `name`, `code`, `status`) VALUES
    (0, 'Default', 'default', 1)
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`);


-- =============================================
-- 租户隔离说明
-- =============================================
-- 各业务表已包含 tenant_id 字段：
--   - account.tenant_id
--   - file.tenant_id
--   - image_store.tenant_id
--   - file_saga.tenant_id
--   - outbox_event.tenant_id
--
-- 查询时需添加租户过滤条件：
--   SELECT * FROM file WHERE tenant_id = ? AND ...
--
-- 应用层通过 TenantContextUtil 获取当前租户ID
-- MyBatis Plus 可配置租户拦截器自动添加过滤条件
