-- =============================================
-- RecordPlatform Database Migration
-- Version: 1.3.1
-- Description: Add keyword mode query indexes for file search
-- =============================================

-- 支撑 PREFIX/FUZZY 模式下文件名检索（租户 + 用户 + 删除标识 + 文件名 + 时间）
CREATE INDEX `idx_file_tenant_uid_deleted_name_time`
    ON `file` (`tenant_id`, `uid`, `deleted`, `file_name`, `create_time`);

-- 支撑 EXACT_HASH/PREFIX 模式下 file_hash 精确匹配（租户 + 用户 + 删除标识 + 哈希 + 时间）
CREATE INDEX `idx_file_tenant_uid_deleted_hash_time`
    ON `file` (`tenant_id`, `uid`, `deleted`, `file_hash`, `create_time`);
