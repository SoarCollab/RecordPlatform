-- =============================================
-- RecordPlatform Database Migration
-- Version: 1.2.6
-- Description: Add composite indexes for file query combinations
-- =============================================

-- 支撑用户文件组合检索：tenant + uid + deleted + status + create_time
CREATE INDEX `idx_file_tenant_uid_deleted_status_time`
    ON `file` (`tenant_id`, `uid`, `deleted`, `status`, `create_time`);

-- 支撑无状态过滤场景：tenant + uid + deleted + create_time
CREATE INDEX `idx_file_tenant_uid_deleted_time`
    ON `file` (`tenant_id`, `uid`, `deleted`, `create_time`);
