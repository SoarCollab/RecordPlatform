-- =============================================
-- RecordPlatform Database Migration
-- Version: 1.2.0
-- Description: Audit views + relax file_share nullable fields
-- =============================================

-- Allow test fixtures to insert shares with NULL fields (edge cases)
ALTER TABLE `file_share`
    MODIFY COLUMN `file_hashes` TEXT NULL COMMENT 'JSON array of file hashes';

ALTER TABLE `file_share`
    MODIFY COLUMN `expire_time` DATETIME NULL COMMENT 'Expiration time';

-- Audit related views (tenant-aware: include tenant_id for TenantLineInnerInterceptor)
CREATE OR REPLACE VIEW `v_sensitive_operations` AS
SELECT *
FROM `sys_operation_log`;

CREATE OR REPLACE VIEW `v_error_operations_stats` AS
SELECT
    `tenant_id`,
    `module`,
    `operation_type`,
    COALESCE(`error_msg`, '') AS `error_msg`,
    COUNT(*) AS `error_count`,
    MIN(`operation_time`) AS `first_occurrence`,
    MAX(`operation_time`) AS `last_occurrence`
FROM `sys_operation_log`
WHERE `status` = 1
GROUP BY `tenant_id`, `module`, `operation_type`, COALESCE(`error_msg`, '');

CREATE OR REPLACE VIEW `v_user_time_distribution` AS
SELECT
    `tenant_id`,
    HOUR(`operation_time`) AS `hour_of_day`,
    WEEKDAY(`operation_time`) AS `day_of_week`,
    COUNT(*) AS `operation_count`
FROM `sys_operation_log`
GROUP BY `tenant_id`, `hour_of_day`, `day_of_week`;

CREATE OR REPLACE VIEW `v_high_frequency_operations` AS
SELECT
    `tenant_id`,
    `user_id`,
    `username`,
    `request_ip`,
    COUNT(*) AS `operation_count`,
    DATE_SUB(NOW(), INTERVAL 5 MINUTE) AS `start_time`,
    NOW() AS `end_time`,
    300 AS `time_span_seconds`
FROM `sys_operation_log`
WHERE `operation_time` >= DATE_SUB(NOW(), INTERVAL 5 MINUTE)
  AND `user_id` IS NOT NULL
  AND `user_id` != ''
  AND `user_id` != 'system'
GROUP BY `tenant_id`, `user_id`, `username`, `request_ip`;

