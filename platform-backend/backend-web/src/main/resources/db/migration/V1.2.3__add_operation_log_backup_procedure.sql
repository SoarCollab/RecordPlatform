-- Add stored procedure for operation log backup
-- 功能：将 sys_operation_log 中超过指定天数的记录备份到 sys_operation_log_backup，并可选择是否删除原表数据。

-- Ensure backup table exists
CREATE TABLE IF NOT EXISTS `sys_operation_log_backup` LIKE `sys_operation_log`;

DROP PROCEDURE IF EXISTS `proc_backup_operation_logs`;

DELIMITER //

CREATE PROCEDURE `proc_backup_operation_logs`(
    IN p_days INT,
    IN p_delete_after_backup BOOLEAN
)
BEGIN
    -- 1) 备份：插入到备份表（重复 ID 直接忽略）
    INSERT IGNORE INTO `sys_operation_log_backup`
    SELECT *
    FROM `sys_operation_log`
    WHERE `operation_time` < DATE_SUB(NOW(), INTERVAL p_days DAY);

    -- 2) 可选删除：按调用参数决定是否从原表清理
    IF p_delete_after_backup THEN
        DELETE FROM `sys_operation_log`
        WHERE `operation_time` < DATE_SUB(NOW(), INTERVAL p_days DAY);
    END IF;
END //

DELIMITER ;

