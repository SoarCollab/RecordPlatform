-- =============================================
-- RecordPlatform Database Migration
-- Version: 1.2.5
-- Description: Replace wildcard select in audit view and backup procedure
-- =============================================

-- v_sensitive_operations 使用显式列，避免基表字段变更带来隐式行为变化
CREATE OR REPLACE VIEW `v_sensitive_operations` AS
SELECT
    `id`,
    `tenant_id`,
    `module`,
    `operation_type`,
    `description`,
    `method`,
    `request_url`,
    `request_method`,
    `request_ip`,
    `request_param`,
    `response_result`,
    `status`,
    `error_msg`,
    `user_id`,
    `username`,
    `operation_time`,
    `execution_time`
FROM `sys_operation_log`;

-- 兜底确保备份表存在（历史迁移已创建，此处保持幂等）
CREATE TABLE IF NOT EXISTS `sys_operation_log_backup` LIKE `sys_operation_log`;

DROP PROCEDURE IF EXISTS `proc_backup_operation_logs`;

DELIMITER //

CREATE PROCEDURE `proc_backup_operation_logs`(
    IN p_days INT,
    IN p_delete_after_backup BOOLEAN
)
BEGIN
    -- 1) 备份：插入到备份表（重复 ID 直接忽略）
    INSERT IGNORE INTO `sys_operation_log_backup` (
        `id`,
        `tenant_id`,
        `module`,
        `operation_type`,
        `description`,
        `method`,
        `request_url`,
        `request_method`,
        `request_ip`,
        `request_param`,
        `response_result`,
        `status`,
        `error_msg`,
        `user_id`,
        `username`,
        `operation_time`,
        `execution_time`
    )
    SELECT
        `id`,
        `tenant_id`,
        `module`,
        `operation_type`,
        `description`,
        `method`,
        `request_url`,
        `request_method`,
        `request_ip`,
        `request_param`,
        `response_result`,
        `status`,
        `error_msg`,
        `user_id`,
        `username`,
        `operation_time`,
        `execution_time`
    FROM `sys_operation_log`
    WHERE `operation_time` < DATE_SUB(NOW(), INTERVAL p_days DAY);

    -- 2) 可选删除：按调用参数决定是否从原表清理
    IF p_delete_after_backup THEN
        DELETE FROM `sys_operation_log`
        WHERE `operation_time` < DATE_SUB(NOW(), INTERVAL p_days DAY);
    END IF;
END //

DELIMITER ;
