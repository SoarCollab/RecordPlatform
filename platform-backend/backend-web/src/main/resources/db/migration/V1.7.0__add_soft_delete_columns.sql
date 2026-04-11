-- V1.7.0: Add soft-delete (deleted) and update_time columns to tables
--          that use MyBatis-Plus @TableLogic but are missing the DB column.

-- tenant: add deleted + update_time
ALTER TABLE `tenant`
    ADD COLUMN `deleted`     TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除标记 0=正常 1=已删除' AFTER `status`,
    ADD COLUMN `update_time` DATETIME NULL DEFAULT NULL COMMENT '更新时间' AFTER `create_time`;

-- friend_file_share: add deleted
ALTER TABLE `friend_file_share`
    ADD COLUMN `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记 0=正常 1=已删除' AFTER `status`;

-- conversation: add deleted (update_time already exists)
ALTER TABLE `conversation`
    ADD COLUMN `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记 0=正常 1=已删除' AFTER `update_time`;

-- integrity_alert: add deleted + update_time
ALTER TABLE `integrity_alert`
    ADD COLUMN `deleted`     TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除标记 0=正常 1=已删除' AFTER `note`,
    ADD COLUMN `update_time` DATETIME NULL DEFAULT NULL COMMENT '更新时间' AFTER `create_time`;

-- file_source: add deleted
ALTER TABLE `file_source`
    ADD COLUMN `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记 0=正常 1=已删除' AFTER `depth`;
