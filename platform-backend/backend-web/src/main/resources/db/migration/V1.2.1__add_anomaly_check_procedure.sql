-- Add stored procedure for anomaly detection
-- Checks for: high frequency operations, failed logins, error rate anomalies

DELIMITER //

CREATE PROCEDURE IF NOT EXISTS `proc_check_operation_anomalies`(
    OUT p_has_anomalies BOOLEAN,
    OUT p_anomaly_details VARCHAR(4000)
)
BEGIN
    DECLARE v_high_freq_threshold INT DEFAULT 100;
    DECLARE v_failed_login_threshold INT DEFAULT 5;
    DECLARE v_high_freq_count INT DEFAULT 0;
    DECLARE v_failed_login_count INT DEFAULT 0;
    DECLARE v_error_rate DECIMAL(5,2) DEFAULT 0;
    DECLARE v_details JSON;

    -- Load thresholds from config
    SELECT CAST(config_value AS UNSIGNED) INTO v_high_freq_threshold
    FROM sys_audit_config WHERE config_key = 'HIGH_FREQ_THRESHOLD';

    SELECT CAST(config_value AS UNSIGNED) INTO v_failed_login_threshold
    FROM sys_audit_config WHERE config_key = 'FAILED_LOGIN_THRESHOLD';

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

    -- Check 3: Error rate in last hour (if > 10% consider anomaly)
    SELECT
        CASE
            WHEN COUNT(*) = 0 THEN 0
            ELSE ROUND(SUM(IF(status = 1, 1, 0)) * 100.0 / COUNT(*), 2)
        END INTO v_error_rate
    FROM sys_operation_log
    WHERE operation_time >= DATE_SUB(NOW(), INTERVAL 1 HOUR);

    -- Determine if anomalies exist
    SET p_has_anomalies = (v_high_freq_count > 0 OR v_failed_login_count > 0 OR v_error_rate > 10);

    -- Build details JSON
    SET v_details = JSON_OBJECT(
        'highFrequencyUsers', v_high_freq_count,
        'failedLoginUsers', v_failed_login_count,
        'errorRatePercent', v_error_rate,
        'thresholds', JSON_OBJECT(
            'highFrequency', v_high_freq_threshold,
            'failedLogin', v_failed_login_threshold,
            'errorRate', 10
        )
    );

    SET p_anomaly_details = CAST(v_details AS CHAR(4000));
END //

DELIMITER ;
