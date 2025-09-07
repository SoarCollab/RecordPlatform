-- 流量监控表
CREATE TABLE `traffic_monitor` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `request_id` VARCHAR(64) NOT NULL COMMENT '请求ID（用于链路追踪）',
    `client_ip` VARCHAR(50) NOT NULL COMMENT '客户端IP地址',
    `user_id` BIGINT COMMENT '用户ID（可选）',
    `request_path` VARCHAR(500) NOT NULL COMMENT '请求路径',
    `request_method` VARCHAR(10) NOT NULL COMMENT 'HTTP方法',
    `user_agent` TEXT COMMENT '用户代理',
    `response_status` INT COMMENT '响应状态码',
    `response_time` BIGINT COMMENT '响应时间（毫秒）',
    `request_size` BIGINT COMMENT '请求大小（字节）',
    `response_size` BIGINT COMMENT '响应大小（字节）',
    `is_abnormal` TINYINT(1) DEFAULT 0 COMMENT '是否异常流量',
    `abnormal_type` VARCHAR(50) COMMENT '异常类型',
    `risk_score` INT DEFAULT 0 COMMENT '风险评分 (0-100)',
    `block_status` TINYINT DEFAULT 0 COMMENT '拦截状态 (0-正常, 1-限流, 2-拦截, 3-黑名单)',
    `block_reason` VARCHAR(200) COMMENT '拦截原因',
    `geo_location` VARCHAR(100) COMMENT '地理位置信息',
    `device_fingerprint` VARCHAR(100) COMMENT '设备指纹',
    `request_time` DATETIME NOT NULL COMMENT '请求时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_request_id` (`request_id`),
    KEY `idx_client_ip` (`client_ip`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_request_time` (`request_time`),
    KEY `idx_request_path` (`request_path`),
    KEY `idx_is_abnormal` (`is_abnormal`),
    KEY `idx_block_status` (`block_status`),
    KEY `idx_response_status` (`response_status`),
    KEY `idx_risk_score` (`risk_score`),
    KEY `idx_response_time` (`response_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='流量监控表';

-- 复合索引优化
CREATE INDEX `idx_traffic_monitor_ip_time` ON `traffic_monitor` (`client_ip`, `request_time`);
CREATE INDEX `idx_traffic_monitor_user_time` ON `traffic_monitor` (`user_id`, `request_time`);
CREATE INDEX `idx_traffic_monitor_abnormal_time` ON `traffic_monitor` (`is_abnormal`, `request_time`);
CREATE INDEX `idx_traffic_monitor_path_method` ON `traffic_monitor` (`request_path`, `request_method`);
CREATE INDEX `idx_traffic_monitor_geo_time` ON `traffic_monitor` (`geo_location`, `request_time`);

-- 清理过期流量监控数据的存储过程
DELIMITER $$
CREATE PROCEDURE `CleanExpiredTrafficData`(IN retention_days INT)
BEGIN
    DECLARE expire_date DATE DEFAULT DATE_SUB(CURDATE(), INTERVAL retention_days DAY);
    DECLARE affected_rows INT DEFAULT 0;

    -- 删除过期的流量监控数据
    DELETE FROM `traffic_monitor` 
    WHERE `request_time` < expire_date;

    SET affected_rows = ROW_COUNT();

    -- 记录清理日志
    INSERT INTO `operation_log` (
        `operation_type`, `module`, `description`,
        `status`, `risk_level`, `operation_time`
    ) VALUES (
        'DELETE', 'SYSTEM',
        CONCAT('自动清理过期流量监控数据，清理数量：', affected_rows, '，保留天数：', retention_days),
        0, 'LOW', NOW()
    );
END$$
DELIMITER ;

-- 获取流量统计的存储过程
DELIMITER $$
CREATE PROCEDURE `GetTrafficStats`(
    IN start_time DATETIME,
    IN end_time DATETIME
)
BEGIN
    -- 基础统计
    SELECT 
        COUNT(*) as total_requests,
        COUNT(DISTINCT client_ip) as unique_ips,
        COUNT(DISTINCT user_id) as unique_users,
        AVG(response_time) as avg_response_time,
        MAX(response_time) as max_response_time,
        COUNT(CASE WHEN response_status >= 400 THEN 1 END) as error_count,
        COUNT(CASE WHEN is_abnormal = 1 THEN 1 END) as abnormal_count,
        COUNT(CASE WHEN block_status > 0 THEN 1 END) as blocked_count
    FROM traffic_monitor 
    WHERE request_time BETWEEN start_time AND end_time;

    -- IP访问排行
    SELECT 
        client_ip,
        COUNT(*) as request_count,
        AVG(response_time) as avg_response_time,
        COUNT(CASE WHEN response_status >= 400 THEN 1 END) as error_count,
        MAX(request_time) as last_request_time
    FROM traffic_monitor 
    WHERE request_time BETWEEN start_time AND end_time
    GROUP BY client_ip 
    ORDER BY request_count DESC 
    LIMIT 20;

    -- API访问排行
    SELECT 
        request_path,
        request_method,
        COUNT(*) as request_count,
        AVG(response_time) as avg_response_time,
        COUNT(CASE WHEN response_status >= 400 THEN 1 END) as error_count
    FROM traffic_monitor 
    WHERE request_time BETWEEN start_time AND end_time
    GROUP BY request_path, request_method 
    ORDER BY request_count DESC 
    LIMIT 20;

    -- 异常类型统计
    SELECT 
        abnormal_type,
        COUNT(*) as count
    FROM traffic_monitor 
    WHERE is_abnormal = 1 
    AND request_time BETWEEN start_time AND end_time
    GROUP BY abnormal_type 
    ORDER BY count DESC;
END$$
DELIMITER ;

-- 获取高风险流量的存储过程
DELIMITER $$
CREATE PROCEDURE `GetHighRiskTraffic`(
    IN min_risk_score INT,
    IN start_time DATETIME,
    IN end_time DATETIME,
    IN limit_count INT
)
BEGIN
    SELECT *
    FROM traffic_monitor 
    WHERE risk_score >= min_risk_score
    AND request_time BETWEEN start_time AND end_time
    ORDER BY risk_score DESC, request_time DESC 
    LIMIT limit_count;
END$$
DELIMITER ;

-- 创建定时清理任务（每天凌晨2点执行）
CREATE EVENT IF NOT EXISTS evt_clean_traffic_data
ON SCHEDULE EVERY 1 DAY
STARTS '2025-01-01 02:00:00'
DO CALL CleanExpiredTrafficData(7);

-- 索引统计信息更新
ANALYZE TABLE traffic_monitor;
