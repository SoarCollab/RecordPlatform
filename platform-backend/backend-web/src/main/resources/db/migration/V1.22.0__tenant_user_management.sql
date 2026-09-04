-- Tenant member administration, invitation lifecycle and sanitized mutation audit.
CREATE TABLE `account_invitation` (
    `id` BIGINT NOT NULL COMMENT 'Snowflake invitation ID',
    `tenant_id` BIGINT NOT NULL COMMENT 'Owning tenant ID',
    `token_hash` CHAR(64) NOT NULL COMMENT 'Lowercase SHA-256 digest of opaque token',
    `email` VARCHAR(100) NOT NULL COMMENT 'Normalized invited email',
    `role` VARCHAR(20) NOT NULL COMMENT 'Target tenant role',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACCEPTED/REVOKED/EXPIRED',
    `pending_slot` TINYINT GENERATED ALWAYS AS
        (CASE WHEN `status` = 'PENDING' THEN 1 ELSE NULL END) STORED
        COMMENT 'Single pending invitation guard per tenant/email',
    `invited_by` BIGINT NOT NULL COMMENT 'Inviting tenant administrator',
    `expires_at` DATETIME(6) NOT NULL COMMENT 'Invitation expiry',
    `accepted_by` BIGINT NULL COMMENT 'Created account ID',
    `accepted_at` DATETIME(6) NULL,
    `revoked_by` BIGINT NULL,
    `revoked_at` DATETIME(6) NULL,
    `revoke_reason` VARCHAR(255) NULL COMMENT 'Sanitized revocation reason',
    `create_time` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `update_time` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_account_invitation_token_hash` (`token_hash`),
    UNIQUE KEY `uk_invitation_tenant_email_pending` (`tenant_id`, `email`, `pending_slot`),
    INDEX `idx_invitation_tenant_email_status` (`tenant_id`, `email`, `status`, `expires_at`),
    INDEX `idx_invitation_tenant_created` (`tenant_id`, `create_time`, `id`),
    CONSTRAINT `chk_account_invitation_role` CHECK (`role` IN ('user', 'admin', 'monitor')),
    CONSTRAINT `chk_account_invitation_status` CHECK (`status` IN ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Tenant account invitations';

CREATE TABLE `account_member_audit` (
    `id` BIGINT NOT NULL COMMENT 'Snowflake audit ID',
    `tenant_id` BIGINT NOT NULL,
    `actor_id` BIGINT NOT NULL,
    `target_account_id` BIGINT NULL,
    `invitation_id` BIGINT NULL,
    `action` VARCHAR(32) NOT NULL,
    `old_value` VARCHAR(64) NULL,
    `new_value` VARCHAR(64) NULL,
    `reason` VARCHAR(255) NOT NULL,
    `create_time` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    INDEX `idx_member_audit_tenant_created` (`tenant_id`, `create_time`, `id`),
    INDEX `idx_member_audit_target` (`tenant_id`, `target_account_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Sanitized tenant member mutation audit';

INSERT INTO `sys_permission`
    (`tenant_id`, `code`, `name`, `module`, `action`, `description`, `status`, `create_time`, `update_time`)
SELECT 0, 'tenant:user:admin', 'Tenant user administration', 'tenant-user', 'admin',
       'Manage members and invitations in the authenticated tenant', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM `sys_permission` WHERE `tenant_id` = 0 AND `code` = 'tenant:user:admin'
);

INSERT INTO `sys_role_permission` (`tenant_id`, `role`, `permission_id`, `create_time`)
SELECT 0, 'admin', p.`id`, CURRENT_TIMESTAMP
FROM `sys_permission` p
WHERE p.`tenant_id` = 0 AND p.`code` = 'tenant:user:admin'
  AND NOT EXISTS (
      SELECT 1 FROM `sys_role_permission` rp
      WHERE rp.`tenant_id` = 0 AND rp.`role` = 'admin' AND rp.`permission_id` = p.`id`
  );
