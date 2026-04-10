-- Remove AUTO_INCREMENT from all tables migrating to Snowflake ID (ASSIGN_ID).
-- Existing sequential IDs coexist with future Snowflake IDs; no data migration needed.

-- Entity violations (previously IdType.AUTO, now IdType.ASSIGN_ID)
ALTER TABLE `quota_policy`          MODIFY COLUMN `id` BIGINT NOT NULL COMMENT '主键ID';
ALTER TABLE `quota_usage_snapshot`  MODIFY COLUMN `id` BIGINT NOT NULL COMMENT '主键ID';
ALTER TABLE `quota_rollout_audit`   MODIFY COLUMN `id` BIGINT NOT NULL COMMENT '主键ID';
ALTER TABLE `sys_operation_log`     MODIFY COLUMN `id` BIGINT NOT NULL COMMENT 'Log ID';
ALTER TABLE `sys_permission`        MODIFY COLUMN `id` BIGINT NOT NULL COMMENT 'Permission ID';
ALTER TABLE `sys_role_permission`   MODIFY COLUMN `id` BIGINT NOT NULL COMMENT 'Mapping ID';

-- DDL mismatch only (entity already ASSIGN_ID, DDL had AUTO_INCREMENT)
ALTER TABLE `file`                  MODIFY COLUMN `id` BIGINT NOT NULL COMMENT 'File ID';
ALTER TABLE `file_saga`             MODIFY COLUMN `id` BIGINT NOT NULL COMMENT 'Saga ID';
ALTER TABLE `announcement_read`     MODIFY COLUMN `id` BIGINT NOT NULL COMMENT 'ID';
ALTER TABLE `ticket_attachment`     MODIFY COLUMN `id` BIGINT NOT NULL COMMENT 'ID';

-- Other tables with AUTO_INCREMENT in DDL
ALTER TABLE `sys_audit_config`      MODIFY COLUMN `id` INT    NOT NULL COMMENT 'Config ID';
ALTER TABLE `account_role_audit`    MODIFY COLUMN `id` BIGINT NOT NULL COMMENT 'Audit ID';
