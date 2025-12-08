-- =============================================
-- 04_audit.sql
-- 审计功能
-- 包含：审计视图、存储过程、配置表、定时事件
-- 依赖：03_operation_log.sql（需先执行）
-- =============================================

-- =============================================
-- 审计视图
-- =============================================

-- ---------------------------------------------
-- 高频操作检测视图
-- 检测短时间内的高频操作（可能是恶意行为）
-- ---------------------------------------------
CREATE OR REPLACE VIEW `v_high_frequency_operations` AS
SELECT
    user_id,
    username,
    request_ip,
    COUNT(*)                                                                AS operation_count,
    MIN(operation_time)                                                     AS start_time,
    MAX(operation_time)                                                     AS end_time,
    TIMESTAMPDIFF(SECOND, MIN(operation_time), MAX(operation_time))         AS time_span_seconds
FROM
    sys_operation_log
WHERE
    operation_time > DATE_SUB(NOW(), INTERVAL 1 HOUR)
GROUP BY
    user_id, username, request_ip
HAVING
    COUNT(*) > 100
    AND TIMESTAMPDIFF(SECOND, MIN(operation_time), MAX(operation_time)) < 300  -- 5分钟内超过100次
ORDER BY
    operation_count DESC;


-- ---------------------------------------------
-- 敏感操作审计视图
-- 聚焦敏感操作的审计
-- ---------------------------------------------
CREATE OR REPLACE VIEW `v_sensitive_operations` AS
SELECT
    id,
    user_id,
    username,
    request_ip,
    module,
    operation_type,
    description,
    method,
    request_url,
    request_param,
    operation_time,
    status
FROM
    sys_operation_log
WHERE
    operation_type IN ('删除', '修改', '重置', 'DELETE', 'UPDATE', 'RESET')
    OR module IN ('系统管理', '用户管理', '权限管理', '系统配置')
    OR description LIKE '%敏感%'
    OR description LIKE '%密码%'
    OR description LIKE '%权限%'
ORDER BY
    operation_time DESC;


-- ---------------------------------------------
-- 异常操作统计视图
-- 统计错误操作，找出问题模块
-- ---------------------------------------------
CREATE OR REPLACE VIEW `v_error_operations_stats` AS
SELECT
    module,
    operation_type,
    error_msg,
    COUNT(*)            AS error_count,
    MIN(operation_time) AS first_occurrence,
    MAX(operation_time) AS last_occurrence
FROM
    sys_operation_log
WHERE
    status = 1
GROUP BY
    module, operation_type, error_msg
ORDER BY
    error_count DESC;


-- ---------------------------------------------
-- 用户操作时间分布视图
-- 了解用户活跃时段
-- ---------------------------------------------
CREATE OR REPLACE VIEW `v_user_time_distribution` AS
SELECT
    HOUR(operation_time)    AS hour_of_day,
    WEEKDAY(operation_time) AS day_of_week,
    COUNT(*)                AS operation_count
FROM
    sys_operation_log
WHERE
    operation_time > DATE_SUB(NOW(), INTERVAL 30 DAY)
GROUP BY
    HOUR(operation_time), WEEKDAY(operation_time)
ORDER BY
    day_of_week, hour_of_day;


-- =============================================
-- 审计存储过程
-- =============================================

-- ---------------------------------------------
-- 按操作类型查询日志
-- ---------------------------------------------
DELIMITER //
CREATE PROCEDURE IF NOT EXISTS `proc_query_logs_by_operation_type`(
    IN p_operation_type VARCHAR(20),
    IN p_start_time DATETIME,
    IN p_end_time DATETIME,
    IN p_limit INT
)
BEGIN
    SELECT
        id, user_id, username, module, operation_type,
        description, request_url, request_ip,
        operation_time, status, execution_time
    FROM
        sys_operation_log
    WHERE
        operation_type = p_operation_type
        AND operation_time BETWEEN p_start_time AND p_end_time
    ORDER BY
        operation_time DESC
    LIMIT p_limit;
END //
DELIMITER ;


-- ---------------------------------------------
-- 按用户ID查询日志
-- ---------------------------------------------
DELIMITER //
CREATE PROCEDURE IF NOT EXISTS `proc_query_logs_by_user`(
    IN p_user_id VARCHAR(50),
    IN p_start_time DATETIME,
    IN p_end_time DATETIME,
    IN p_limit INT
)
BEGIN
    SELECT
        id, user_id, username, module, operation_type,
        description, request_url, request_ip,
        operation_time, status, execution_time
    FROM
        sys_operation_log
    WHERE
        user_id = p_user_id
        AND operation_time BETWEEN p_start_time AND p_end_time
    ORDER BY
        operation_time DESC
    LIMIT p_limit;
END //
DELIMITER ;


-- ---------------------------------------------
-- 导出特定时间段日志
-- ---------------------------------------------
DELIMITER //
CREATE PROCEDURE IF NOT EXISTS `proc_export_logs`(
    IN p_start_time DATETIME,
    IN p_end_time DATETIME,
    IN p_module VARCHAR(50),
    IN p_operation_type VARCHAR(20),
    IN p_status TINYINT
)
BEGIN
    SELECT
        id, user_id, username, module, operation_type,
        description, request_url, request_method, request_ip,
        request_param, response_result, status, error_msg,
        operation_time, execution_time
    FROM
        sys_operation_log
    WHERE
        operation_time BETWEEN p_start_time AND p_end_time
        AND (p_module IS NULL OR module = p_module)
        AND (p_operation_type IS NULL OR operation_type = p_operation_type)
        AND (p_status IS NULL OR status = p_status)
    ORDER BY
        operation_time DESC;
END //
DELIMITER ;


-- ---------------------------------------------
-- 异常操作预警
-- 检测并返回异常情况
-- ---------------------------------------------
DELIMITER //
CREATE PROCEDURE IF NOT EXISTS `proc_check_operation_anomalies`(
    OUT p_has_anomalies BOOLEAN,
    OUT p_anomaly_details TEXT
)
BEGIN
    DECLARE v_high_freq_count INT DEFAULT 0;
    DECLARE v_sensitive_op_count INT DEFAULT 0;
    DECLARE v_failed_login_count INT DEFAULT 0;
    DECLARE v_anomaly_text TEXT DEFAULT '';

    -- 检查高频操作
    SELECT COUNT(*) INTO v_high_freq_count FROM v_high_frequency_operations;

    IF v_high_freq_count > 0 THEN
        SET v_anomaly_text = CONCAT(v_anomaly_text, '发现', v_high_freq_count, '个高频操作行为;\n');
    END IF;

    -- 检查敏感操作
    SELECT COUNT(*) INTO v_sensitive_op_count
    FROM sys_operation_log
    WHERE operation_time > DATE_SUB(NOW(), INTERVAL 1 HOUR)
      AND (operation_type IN ('删除', '修改', '重置', 'DELETE', 'UPDATE', 'RESET')
           OR module IN ('系统管理', '用户管理', '权限管理', '系统配置'));

    IF v_sensitive_op_count > 20 THEN  -- 1小时内敏感操作超过20次
        SET v_anomaly_text = CONCAT(v_anomaly_text, '最近1小时内执行了', v_sensitive_op_count, '次敏感操作;\n');
    END IF;

    -- 检查登录失败
    SELECT COUNT(*) INTO v_failed_login_count
    FROM sys_operation_log
    WHERE operation_time > DATE_SUB(NOW(), INTERVAL 1 HOUR)
      AND module = '用户认证'
      AND operation_type = '登录'
      AND status = 1;

    IF v_failed_login_count > 5 THEN  -- 1小时内登录失败超过5次
        SET v_anomaly_text = CONCAT(v_anomaly_text, '最近1小时内发生', v_failed_login_count, '次登录失败;\n');
    END IF;

    -- 设置返回值
    IF LENGTH(v_anomaly_text) > 0 THEN
        SET p_has_anomalies = TRUE;
        SET p_anomaly_details = v_anomaly_text;
    ELSE
        SET p_has_anomalies = FALSE;
        SET p_anomaly_details = '未发现异常';
    END IF;
END //
DELIMITER ;


-- ---------------------------------------------
-- 日志备份存储过程
-- ---------------------------------------------
DELIMITER //
CREATE PROCEDURE IF NOT EXISTS `proc_backup_operation_logs`(
    IN p_backup_days INT,
    IN p_delete_after_backup BOOLEAN
)
BEGIN
    DECLARE v_backup_table_name VARCHAR(50);
    DECLARE v_sql TEXT;

    -- 创建备份表名（包含当前日期）
    SET v_backup_table_name = CONCAT('sys_operation_log_backup_', DATE_FORMAT(NOW(), '%Y%m%d'));

    -- 创建备份表
    SET v_sql = CONCAT('CREATE TABLE ', v_backup_table_name, ' LIKE sys_operation_log');

    PREPARE stmt FROM @v_sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;

    -- 复制数据到备份表
    SET v_sql = CONCAT(
        'INSERT INTO ', v_backup_table_name,
        ' SELECT * FROM sys_operation_log',
        ' WHERE operation_time < DATE_SUB(NOW(), INTERVAL ', p_backup_days, ' DAY)'
    );

    PREPARE stmt FROM @v_sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;

    -- 如果需要，删除已备份的数据
    IF p_delete_after_backup THEN
        DELETE FROM sys_operation_log
        WHERE operation_time < DATE_SUB(NOW(), INTERVAL p_backup_days DAY);
    END IF;

    -- 记录备份操作
    INSERT INTO sys_operation_log (
        module, operation_type, description, method, request_url,
        request_method, request_ip, status, user_id, username, operation_time
    ) VALUES (
        '系统审计', '日志备份',
        CONCAT('已备份', p_backup_days, '天前的日志到', v_backup_table_name),
        'proc_backup_operation_logs', '/system/audit/backup',
        'SYSTEM', '127.0.0.1', 0, 'system', 'system', NOW()
    );
END //
DELIMITER ;


-- ---------------------------------------------
-- 按月分区存储过程（用于大量日志管理）
-- ---------------------------------------------
DELIMITER //
CREATE PROCEDURE IF NOT EXISTS `proc_create_monthly_log_partition`()
BEGIN
    DECLARE v_start_date DATE;
    DECLARE v_end_date DATE;
    DECLARE v_partition_name VARCHAR(50);
    DECLARE v_sql TEXT;

    -- 计算下个月的分区
    SET v_start_date = DATE_FORMAT(DATE_ADD(NOW(), INTERVAL 1 MONTH), '%Y-%m-01');
    SET v_end_date = DATE_FORMAT(DATE_ADD(v_start_date, INTERVAL 1 MONTH), '%Y-%m-01');
    SET v_partition_name = CONCAT('p', DATE_FORMAT(v_start_date, '%Y%m'));

    -- 检查表是否已经分区
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.partitions
        WHERE table_schema = DATABASE() AND table_name = 'sys_operation_log' AND partition_name IS NOT NULL
    ) THEN
        -- 如果表未分区，则先添加当前月份的分区
        SET @current_partition_name = CONCAT('p', DATE_FORMAT(NOW(), '%Y%m'));
        SET @current_start_date = DATE_FORMAT(NOW(), '%Y-%m-01');
        SET @next_start_date = v_start_date;

        SET @init_sql = CONCAT(
            'ALTER TABLE sys_operation_log PARTITION BY RANGE (TO_DAYS(operation_time)) (',
            'PARTITION ', @current_partition_name, ' VALUES LESS THAN (TO_DAYS(''', @next_start_date, ''')),',
            'PARTITION ', v_partition_name, ' VALUES LESS THAN (TO_DAYS(''', v_end_date, '''))',
            ')'
        );

        PREPARE stmt FROM @init_sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    ELSE
        -- 如果表已分区，则添加下个月的分区
        SET @v_sql = CONCAT(
            'ALTER TABLE sys_operation_log ADD PARTITION (',
            'PARTITION ', v_partition_name, ' VALUES LESS THAN (TO_DAYS(''', v_end_date, '''))',
            ')'
        );

        PREPARE stmt FROM @v_sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END //
DELIMITER ;


-- =============================================
-- 审计配置表
-- =============================================

-- ---------------------------------------------
-- 审计配置表
-- 存储审计相关配置
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_audit_config` (
    `id`           INT          NOT NULL AUTO_INCREMENT COMMENT '配置ID',
    `config_key`   VARCHAR(50)  NOT NULL COMMENT '配置键',
    `config_value` VARCHAR(255) NOT NULL COMMENT '配置值',
    `description`  VARCHAR(255) DEFAULT NULL COMMENT '配置描述',
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='系统审计配置表';


-- ---------------------------------------------
-- 默认审计配置
-- ---------------------------------------------
INSERT INTO `sys_audit_config` (`config_key`, `config_value`, `description`) VALUES
    ('AUDIT_ENABLED', 'true', '是否启用审计'),
    ('LOG_RETENTION_DAYS', '180', '日志保留天数'),
    ('SENSITIVE_MODULES', '系统管理,用户管理,权限管理,系统配置', '敏感模块列表'),
    ('HIGH_FREQ_THRESHOLD', '100', '高频操作阈值（每5分钟）'),
    ('FAILED_LOGIN_THRESHOLD', '5', '登录失败阈值（每小时）')
ON DUPLICATE KEY UPDATE `update_time` = NOW();


-- =============================================
-- 定时事件
-- =============================================

-- ---------------------------------------------
-- 每月创建日志分区事件
-- ---------------------------------------------
DELIMITER //
CREATE EVENT IF NOT EXISTS `evt_create_monthly_partition`
ON SCHEDULE EVERY 1 MONTH
STARTS DATE_FORMAT(DATE_ADD(NOW(), INTERVAL 1 DAY), '%Y-%m-01 00:00:00')
DO
BEGIN
    CALL proc_create_monthly_log_partition();
END //
DELIMITER ;


-- ---------------------------------------------
-- 每日异常检查事件
-- ---------------------------------------------
DELIMITER //
CREATE EVENT IF NOT EXISTS `evt_daily_check_anomalies`
ON SCHEDULE EVERY 1 DAY
STARTS CONCAT(CURDATE() + INTERVAL 1 DAY, ' 01:00:00')
DO
BEGIN
    DECLARE v_has_anomalies BOOLEAN;
    DECLARE v_anomaly_details TEXT;

    CALL proc_check_operation_anomalies(v_has_anomalies, v_anomaly_details);

    IF v_has_anomalies THEN
        INSERT INTO sys_operation_log (
            module, operation_type, description, method, request_url,
            request_method, request_ip, request_param, status,
            user_id, username, operation_time
        ) VALUES (
            '系统审计', '安全预警', v_anomaly_details,
            'proc_check_operation_anomalies', '/system/audit/anomalies',
            'SYSTEM', '127.0.0.1', '系统自动检查', 0,
            'system', 'system', NOW()
        );
    END IF;
END //
DELIMITER ;
