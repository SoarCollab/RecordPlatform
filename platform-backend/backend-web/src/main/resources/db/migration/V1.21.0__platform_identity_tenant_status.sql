-- Add authorization state required by platform identities and tenant lifecycle enforcement.
ALTER TABLE `account`
    ADD COLUMN `status` TINYINT NOT NULL DEFAULT 1 COMMENT 'Authorization status: 1-active, 0-disabled' AFTER `role`,
    ADD COLUMN `auth_version` BIGINT NOT NULL DEFAULT 0 COMMENT 'Account-wide session revocation version' AFTER `status`,
    ADD COLUMN `last_login_time` DATETIME NULL DEFAULT NULL COMMENT 'Last successful login time' AFTER `auth_version`,
    ADD INDEX `idx_account_authorization_state` (`tenant_id`, `id`, `deleted`, `status`, `role`, `auth_version`),
    ADD INDEX `idx_account_tenant_status_role` (`tenant_id`, `deleted`, `status`, `role`),
    ADD CONSTRAINT `chk_account_platform_admin_tenant`
        CHECK (`role` <> 'platform_admin' OR `tenant_id` = 0);

ALTER TABLE `tenant`
    ADD COLUMN `version` BIGINT NOT NULL DEFAULT 0 COMMENT 'Tenant lifecycle version' AFTER `status`,
    ADD COLUMN `disabled_reason` VARCHAR(255) NULL DEFAULT NULL COMMENT 'Sanitized disable reason' AFTER `version`,
    ADD COLUMN `disabled_at` DATETIME NULL DEFAULT NULL COMMENT 'Disable time' AFTER `disabled_reason`,
    ADD COLUMN `disabled_by` BIGINT NULL DEFAULT NULL COMMENT 'Platform actor account ID' AFTER `disabled_at`,
    ADD INDEX `idx_tenant_authorization_state` (`id`, `status`, `version`);
