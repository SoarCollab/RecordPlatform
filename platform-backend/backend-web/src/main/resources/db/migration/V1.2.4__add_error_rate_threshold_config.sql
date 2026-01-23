-- Add ERROR_RATE_THRESHOLD config and update anomaly check procedure to use it
-- Also adds audit task configuration entries

-- 1) Add ERROR_RATE_THRESHOLD configuration
INSERT IGNORE INTO `sys_audit_config` (`config_key`, `config_value`, `description`)
VALUES ('ERROR_RATE_THRESHOLD', '10', '错误率告警阈值（百分比），每小时错误率超过此值时触发告警');

-- 2) Drop and recreate the procedure with configurable error rate threshold
DROP PROCEDURE IF EXISTS `proc_check_operation_anomalies`;

DELIMITER //

CREATE PROCEDURE `proc_check_operation_anomalies`(
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

    -- Load thresholds from config (with IFNULL protection)
    SELECT IFNULL(CAST(config_value AS UNSIGNED), 100) INTO v_high_freq_threshold
    FROM sys_audit_config WHERE config_key = 'HIGH_FREQ_THRESHOLD'
    LIMIT 1;

    SELECT IFNULL(CAST(config_value AS UNSIGNED), 5) INTO v_failed_login_threshold
    FROM sys_audit_config WHERE config_key = 'FAILED_LOGIN_THRESHOLD'
    LIMIT 1;

    SELECT IFNULL(CAST(config_value AS UNSIGNED), 10) INTO v_error_rate_threshold
    FROM sys_audit_config WHERE config_key = 'ERROR_RATE_THRESHOLD'
    LIMIT 1;

    -- Check 1: High frequency operations (users exceeding threshold in last 5 min)
    SELECT COUNT(*) INTO v_high_freq_count
    FROM (
        SELECT user_id
        FROM sys_operation_log
        WHERE operation_time >= DATE_SUB(NOW(), INTERVAL 5 MINUTE)
          AND user_id IS NOT NULL AND user_id != '' AND user_id != 'system'
        GROUP BY user_id
        HAVING COUNT(*) > v_high_freq_threshold
    ) AS high_freq_users;

    -- Check 2: Failed login attempts (per user in last hour)
    SELECT COUNT(*) INTO v_failed_login_count
    FROM (
        SELECT user_id
        FROM sys_operation_log
        WHERE operation_time >= DATE_SUB(NOW(), INTERVAL 1 HOUR)
          AND operation_type = 'LOGIN'
          AND status = 1
          AND user_id IS NOT NULL AND user_id != ''
        GROUP BY user_id
        HAVING COUNT(*) >= v_failed_login_threshold
    ) AS failed_login_users;

    -- Check 3: Error rate in last hour (configurable threshold)
    SELECT
        CASE
            WHEN COUNT(*) = 0 THEN 0
            ELSE ROUND(SUM(IF(status = 1, 1, 0)) * 100.0 / COUNT(*), 2)
        END INTO v_error_rate
    FROM sys_operation_log
    WHERE operation_time >= DATE_SUB(NOW(), INTERVAL 1 HOUR);

    -- Determine if anomalies exist (using configurable error rate threshold)
    SET p_has_anomalies = (v_high_freq_count > 0 OR v_failed_login_count > 0 OR v_error_rate > v_error_rate_threshold);

    -- Build details JSON (include configured thresholds for transparency)
    SET v_details = JSON_OBJECT(
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

DELIMITER ;
