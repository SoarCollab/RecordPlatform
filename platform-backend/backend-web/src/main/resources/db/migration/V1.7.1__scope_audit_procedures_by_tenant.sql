-- Scope audit anomaly and backup procedures by tenant.
-- Application code no longer calls these procedures, but replacing them keeps the database-side
-- contract tenant-safe for operators or legacy clients with procedure access.

CREATE TABLE IF NOT EXISTS `sys_operation_log_backup` LIKE `sys_operation_log`;

DROP PROCEDURE IF EXISTS `proc_check_operation_anomalies`;
DROP PROCEDURE IF EXISTS `proc_backup_operation_logs`;

DELIMITER //

CREATE PROCEDURE `proc_check_operation_anomalies`(
    IN p_tenant_id BIGINT,
    OUT p_has_anomalies BOOLEAN,
    OUT p_anomaly_details VARCHAR(4000)
)
BEGIN
    DECLARE v_high_freq_threshold INT DEFAULT 100;
    DECLARE v_failed_login_threshold INT DEFAULT 5;
    DECLARE v_error_rate_threshold INT DEFAULT 10;
    DECLARE v_high_freq_count INT DEFAULT 0;
    DECLARE v_failed_login_count INT DEFAULT 0;
    DECLARE v_error_rate DECIMAL(5,2) DEFAULT 0;
    DECLARE v_details JSON;

    IF p_tenant_id IS NULL OR p_tenant_id <= 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'p_tenant_id must be positive';
    END IF;

    SELECT IFNULL(CAST(config_value AS UNSIGNED), 100) INTO v_high_freq_threshold
    FROM sys_audit_config WHERE config_key = 'HIGH_FREQ_THRESHOLD'
    LIMIT 1;

    SELECT IFNULL(CAST(config_value AS UNSIGNED), 5) INTO v_failed_login_threshold
    FROM sys_audit_config WHERE config_key = 'FAILED_LOGIN_THRESHOLD'
    LIMIT 1;

    SELECT IFNULL(CAST(config_value AS UNSIGNED), 10) INTO v_error_rate_threshold
    FROM sys_audit_config WHERE config_key = 'ERROR_RATE_THRESHOLD'
    LIMIT 1;

    SELECT COUNT(*) INTO v_high_freq_count
    FROM (
        SELECT user_id
        FROM sys_operation_log
        WHERE tenant_id = p_tenant_id
          AND operation_time >= DATE_SUB(NOW(), INTERVAL 5 MINUTE)
          AND user_id IS NOT NULL AND user_id != '' AND user_id != 'system'
        GROUP BY user_id
        HAVING COUNT(*) > v_high_freq_threshold
    ) AS high_freq_users;

    SELECT COUNT(*) INTO v_failed_login_count
    FROM (
        SELECT user_id
        FROM sys_operation_log
        WHERE tenant_id = p_tenant_id
          AND operation_time >= DATE_SUB(NOW(), INTERVAL 1 HOUR)
          AND operation_type = 'LOGIN'
          AND status = 1
          AND user_id IS NOT NULL AND user_id != ''
        GROUP BY user_id
        HAVING COUNT(*) >= v_failed_login_threshold
    ) AS failed_login_users;

    SELECT
        CASE
            WHEN COUNT(*) = 0 THEN 0
            ELSE ROUND(SUM(IF(status = 1, 1, 0)) * 100.0 / COUNT(*), 2)
        END INTO v_error_rate
    FROM sys_operation_log
    WHERE tenant_id = p_tenant_id
      AND operation_time >= DATE_SUB(NOW(), INTERVAL 1 HOUR);

    SET p_has_anomalies = (v_high_freq_count > 0 OR v_failed_login_count > 0 OR v_error_rate > v_error_rate_threshold);
    SET v_details = JSON_OBJECT(
        'tenantId', p_tenant_id,
        'highFrequencyUsers', v_high_freq_count,
        'failedLoginUsers', v_failed_login_count,
        'errorRatePercent', v_error_rate,
        'thresholds', JSON_OBJECT(
            'highFrequency', v_high_freq_threshold,
            'failedLogin', v_failed_login_threshold,
            'errorRate', v_error_rate_threshold
        )
    );

    SET p_anomaly_details = CAST(v_details AS CHAR(4000));
END //

CREATE PROCEDURE `proc_backup_operation_logs`(
    IN p_tenant_id BIGINT,
    IN p_days INT,
    IN p_delete_after_backup BOOLEAN
)
BEGIN
    IF p_tenant_id IS NULL OR p_tenant_id <= 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'p_tenant_id must be positive';
    END IF;

    IF p_days IS NULL OR p_days < 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'p_days must be greater than or equal to 1';
    END IF;

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
    WHERE `tenant_id` = p_tenant_id
      AND `operation_time` < DATE_SUB(NOW(), INTERVAL p_days DAY);

    IF p_delete_after_backup THEN
        DELETE FROM `sys_operation_log`
        WHERE `tenant_id` = p_tenant_id
          AND `operation_time` < DATE_SUB(NOW(), INTERVAL p_days DAY);
    END IF;
END //

DELIMITER ;
