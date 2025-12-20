-- =============================================
-- 03_operation_log.sql
-- 操作日志功能
-- 包含：日志表、统计视图、清理过程
-- =============================================

-- ---------------------------------------------
-- 系统操作日志表
-- 记录用户操作行为
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_operation_log` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '日志ID',
    `tenant_id`       BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID',
    `module`          VARCHAR(50)  DEFAULT NULL COMMENT '操作模块',
    `operation_type`  VARCHAR(20)  DEFAULT NULL COMMENT '操作类型',
    `description`     VARCHAR(255) DEFAULT NULL COMMENT '操作描述',
    `method`          VARCHAR(255) DEFAULT NULL COMMENT '请求方法',
    `request_url`     VARCHAR(255) DEFAULT NULL COMMENT '请求URL',
    `request_method`  VARCHAR(10)  DEFAULT NULL COMMENT '请求方式：GET/POST/PUT/DELETE',
    `request_ip`      VARCHAR(50)  DEFAULT NULL COMMENT '请求IP',
    `request_param`   TEXT         DEFAULT NULL COMMENT '请求参数',
    `response_result` TEXT         DEFAULT NULL COMMENT '响应结果',
    `status`          TINYINT      DEFAULT 0 COMMENT '操作状态：0-正常，1-异常',
    `error_msg`       TEXT         DEFAULT NULL COMMENT '错误信息',
    `user_id`         VARCHAR(50)  DEFAULT NULL COMMENT '操作用户ID',
    `username`        VARCHAR(50)  DEFAULT NULL COMMENT '操作用户名',
    `operation_time`  DATETIME     DEFAULT NULL COMMENT '操作时间',
    `execution_time`  BIGINT       DEFAULT NULL COMMENT '执行时长（毫秒）',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_tenant_id` (`tenant_id`) COMMENT '租户索引',
    INDEX `idx_operation_time` (`operation_time`) USING BTREE COMMENT '操作时间索引',
    INDEX `idx_username` (`username`) USING BTREE COMMENT '用户名索引',
    INDEX `idx_module` (`module`) USING BTREE COMMENT '模块索引',
    INDEX `idx_operation_type` (`operation_type`) USING BTREE COMMENT '操作类型索引',
    INDEX `idx_status` (`status`) USING BTREE COMMENT '状态索引'
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='系统操作日志';


-- ---------------------------------------------
-- 日志清理存储过程
-- 清理指定天数前的日志
-- ---------------------------------------------
DELIMITER //
CREATE PROCEDURE IF NOT EXISTS `proc_clean_old_operation_logs`(IN days INT)
BEGIN
    DELETE FROM sys_operation_log
    WHERE operation_time < DATE_SUB(NOW(), INTERVAL days DAY);
END //
DELIMITER ;


-- =============================================
-- 统计视图
-- =============================================

-- ---------------------------------------------
-- 操作日志统计视图
-- 按模块和操作类型统计
-- ---------------------------------------------
CREATE OR REPLACE VIEW `v_operation_log_stats` AS
SELECT
    module,
    operation_type,
    COUNT(*)                  AS operation_count,
    SUM(IF(status = 0, 1, 0)) AS success_count,
    SUM(IF(status = 1, 1, 0)) AS error_count,
    AVG(execution_time)       AS avg_execution_time,
    MAX(execution_time)       AS max_execution_time,
    MIN(execution_time)       AS min_execution_time,
    DATE(operation_time)      AS operation_date
FROM
    sys_operation_log
GROUP BY
    module, operation_type, DATE(operation_time);


-- ---------------------------------------------
-- 用户操作统计视图
-- 统计每个用户的操作情况
-- ---------------------------------------------
CREATE OR REPLACE VIEW `v_user_operation_stats` AS
SELECT
    user_id,
    username,
    COUNT(*)                              AS operation_count,
    COUNT(DISTINCT DATE(operation_time))  AS active_days,
    MIN(operation_time)                   AS first_operation,
    MAX(operation_time)                   AS last_operation,
    SUM(IF(status = 1, 1, 0))             AS error_count
FROM
    sys_operation_log
WHERE
    user_id IS NOT NULL
GROUP BY
    user_id, username;


-- ---------------------------------------------
-- 模块访问频率视图
-- 统计各模块的访问情况
-- ---------------------------------------------
CREATE OR REPLACE VIEW `v_module_access_frequency` AS
SELECT
    module,
    COUNT(*)                                          AS access_count,
    COUNT(DISTINCT user_id)                           AS unique_users,
    COUNT(DISTINCT DATE(operation_time))              AS active_days,
    COUNT(*) / COUNT(DISTINCT DATE(operation_time))   AS avg_daily_access
FROM
    sys_operation_log
GROUP BY
    module
ORDER BY
    access_count DESC;


-- ---------------------------------------------
-- 用户统计视图
-- 关联账户信息和操作日志
-- ---------------------------------------------
CREATE OR REPLACE VIEW `v_user_stats` AS
SELECT
    a.id,
    a.username,
    a.email,
    a.role,
    a.register_time,
    COUNT(DISTINCT s.id)                        AS operation_count,
    MAX(s.operation_time)                       AS last_active_time,
    DATEDIFF(NOW(), a.register_time)            AS days_since_register,
    DATEDIFF(NOW(), MAX(s.operation_time))      AS days_since_last_active
FROM
    account a
LEFT JOIN
    sys_operation_log s ON a.id = s.user_id
WHERE
    a.deleted = 0
GROUP BY
    a.id, a.username, a.email, a.role, a.register_time;
