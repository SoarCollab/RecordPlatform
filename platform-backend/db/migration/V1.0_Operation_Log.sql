-- step 2
-- 创建操作日志表
CREATE TABLE IF NOT EXISTS `sys_operation_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  `module` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '操作模块',
  `operation_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '操作类型',
  `description` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '操作描述',
  `method` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '请求方法',
  `request_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '请求URL',
  `request_method` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '请求方式',
  `request_ip` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '请求IP',
  `request_param` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '请求参数',
  `response_result` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '响应结果',
  `status` tinyint DEFAULT '0' COMMENT '操作状态（0正常 1异常）',
  `error_msg` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '错误信息',
  `user_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '操作用户ID',
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '操作用户名',
  `operation_time` datetime DEFAULT NULL COMMENT '操作时间',
  `execution_time` bigint DEFAULT NULL COMMENT '执行时长（毫秒）',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_operation_time` (`operation_time`) USING BTREE COMMENT '操作时间索引',
  KEY `idx_username` (`username`) USING BTREE COMMENT '用户名索引',
  KEY `idx_module` (`module`) USING BTREE COMMENT '模块索引',
  KEY `idx_operation_type` (`operation_type`) USING BTREE COMMENT '操作类型索引',
  KEY `idx_status` (`status`) USING BTREE COMMENT '状态索引'
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='系统操作日志';

-- 创建日志清理存储过程
DELIMITER //
CREATE PROCEDURE IF NOT EXISTS `proc_clean_old_operation_logs`(IN days INT)
BEGIN
    DELETE FROM sys_operation_log WHERE operation_time < DATE_SUB(NOW(), INTERVAL days DAY);
END //
DELIMITER ;

-- 创建日志统计视图
CREATE OR REPLACE VIEW `v_operation_log_stats` AS
SELECT 
    module,
    operation_type,
    COUNT(*)                  as operation_count,
    SUM(IF(status = 0, 1, 0)) as success_count,
    SUM(IF(status = 1, 1, 0)) as error_count,
    AVG(execution_time)       as avg_execution_time,
    MAX(execution_time)       as max_execution_time,
    MIN(execution_time)       as min_execution_time,
    DATE(operation_time)      as operation_date
FROM 
    sys_operation_log
GROUP BY 
    module, operation_type, DATE(operation_time);

-- 创建用户操作统计视图
CREATE OR REPLACE VIEW `v_user_operation_stats` AS
SELECT 
    user_id,
    username,
    COUNT(*) as operation_count,
    COUNT(DISTINCT DATE(operation_time)) as active_days,
    MIN(operation_time) as first_operation,
    MAX(operation_time) as last_operation,
    SUM(IF(status = 1, 1, 0)) as error_count
FROM 
    sys_operation_log
WHERE 
    user_id IS NOT NULL
GROUP BY 
    user_id, username;

-- 创建模块访问频率视图
CREATE OR REPLACE VIEW `v_module_access_frequency` AS
SELECT 
    module,
    COUNT(*) as access_count,
    COUNT(DISTINCT user_id) as unique_users,
    COUNT(DISTINCT DATE(operation_time)) as active_days,
    COUNT(*) / COUNT(DISTINCT DATE(operation_time)) as avg_daily_access
FROM 
    sys_operation_log
GROUP BY 
    module
ORDER BY 
    access_count DESC;

-- 创建用户统计视图
CREATE OR REPLACE VIEW `v_user_stats` AS
SELECT
    a.id,
    a.username,
    a.email,
    a.role,
    a.register_time,
    COUNT(DISTINCT s.id) as operation_count,
    MAX(s.operation_time) as last_active_time,
    DATEDIFF(NOW(), a.register_time) as days_since_register,
    DATEDIFF(NOW(), MAX(s.operation_time)) as days_since_last_active
FROM
    account a
        LEFT JOIN
    sys_operation_log s ON a.id = s.user_id
WHERE
    a.deleted = 0
GROUP BY
    a.id, a.username, a.email, a.role, a.register_time;