-- Replace legacy log cleanup procedures through a forward migration.
-- Released migrations stay unchanged so Flyway validation remains stable on upgraded databases.

DROP PROCEDURE IF EXISTS `proc_clean_processed_messages`;
DROP PROCEDURE IF EXISTS `proc_clean_old_operation_logs`;

DELIMITER //

CREATE PROCEDURE `proc_clean_processed_messages`(IN retention_days INT)
BEGIN
    DELETE FROM processed_message
    WHERE processed_at < DATE_SUB(NOW(), INTERVAL retention_days DAY);
END //

CREATE PROCEDURE `proc_clean_old_operation_logs`(IN days INT)
BEGIN
    DELETE FROM sys_operation_log
    WHERE operation_time < DATE_SUB(NOW(), INTERVAL days DAY);
END //

DELIMITER ;
