-- 文件版本链支持
-- 为 file 表新增版本链字段，支持同一业务文件的版本演进关系

ALTER TABLE `file`
  ADD COLUMN `version` INT NOT NULL DEFAULT 1 COMMENT '版本号，从 1 开始',
  ADD COLUMN `parent_version_id` BIGINT NULL COMMENT '上一版本文件ID',
  ADD COLUMN `is_latest` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否最新版本：1=是，0=否',
  ADD COLUMN `version_group_id` BIGINT NULL COMMENT '版本链分组ID';

-- 回填历史数据：每条现有记录自成独立的 v1
UPDATE `file` SET `version_group_id` = `id` WHERE `version_group_id` IS NULL;

-- 版本链查询索引（按 tenant + group 查版本链，version DESC 取最新）
ALTER TABLE `file`
  ADD INDEX `idx_file_version_chain` (`tenant_id`, `version_group_id`, `version` DESC);

-- 文件列表默认过滤 is_latest=1 的覆盖索引
ALTER TABLE `file`
  ADD INDEX `idx_file_latest_list` (`tenant_id`, `is_latest`, `deleted`, `create_time` DESC);

-- parent_version_id 查询索引
ALTER TABLE `file`
  ADD INDEX `idx_file_parent_version` (`parent_version_id`);
