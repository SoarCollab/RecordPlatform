-- ============================================================
-- API开放平台与智能网关 - 数据库迁移脚本 V2.0
-- 创建时间: 2025-10-11
-- 说明: 为platform-identity模块添加API开放平台和智能网关功能
-- ============================================================

-- --------------------------------------------------------
-- 1. API应用管理表
-- 用于管理接入API开放平台的第三方应用
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `api_application` (
  `id` bigint NOT NULL COMMENT '应用ID',
  `app_name` varchar(100) NOT NULL COMMENT '应用名称',
  `app_code` varchar(50) NOT NULL COMMENT '应用标识码(唯一)',
  `app_description` varchar(500) DEFAULT NULL COMMENT '应用描述',
  `owner_id` bigint NOT NULL COMMENT '所属开发者用户ID',
  `app_type` tinyint NOT NULL DEFAULT '1' COMMENT '应用类型:1-Web应用,2-移动应用,3-服务端应用,4-其他',
  `app_status` tinyint NOT NULL DEFAULT '0' COMMENT '应用状态:0-待审核,1-已启用,2-已禁用,3-已删除',
  `app_icon` varchar(255) DEFAULT NULL COMMENT '应用图标URL',
  `app_website` varchar(255) DEFAULT NULL COMMENT '应用官网',
  `callback_url` varchar(500) DEFAULT NULL COMMENT '回调URL(多个用逗号分隔)',
  `ip_whitelist` text DEFAULT NULL COMMENT 'IP白名单(JSON数组)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `approve_time` datetime DEFAULT NULL COMMENT '审核通过时间',
  `approve_by` bigint DEFAULT NULL COMMENT '审核人ID',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除:0-未删除,1-已删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_app_code` (`app_code`) USING BTREE COMMENT '应用标识唯一索引',
  KEY `idx_owner_id` (`owner_id`) USING BTREE COMMENT '所属用户索引',
  KEY `idx_app_status` (`app_status`) USING BTREE COMMENT '应用状态索引',
  KEY `idx_create_time` (`create_time`) USING BTREE COMMENT '创建时间索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='API应用管理表';

-- --------------------------------------------------------
-- 2. API密钥管理表
-- 用于管理应用的API密钥,支持一个应用多个密钥
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `api_key` (
  `id` bigint NOT NULL COMMENT '密钥ID',
  `app_id` bigint NOT NULL COMMENT '所属应用ID',
  `api_key` varchar(64) NOT NULL COMMENT 'API密钥(公开)',
  `api_secret` varchar(128) NOT NULL COMMENT 'API密钥(加密存储)',
  `key_name` varchar(100) DEFAULT NULL COMMENT '密钥名称',
  `key_status` tinyint NOT NULL DEFAULT '1' COMMENT '密钥状态:0-已禁用,1-已启用,2-已过期',
  `key_type` tinyint NOT NULL DEFAULT '1' COMMENT '密钥类型:1-正式环境,2-测试环境',
  `expire_time` datetime DEFAULT NULL COMMENT '过期时间(NULL表示永久)',
  `last_used_time` datetime DEFAULT NULL COMMENT '最后使用时间',
  `used_count` bigint NOT NULL DEFAULT '0' COMMENT '使用次数',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除:0-未删除,1-已删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_api_key` (`api_key`) USING BTREE COMMENT 'API密钥唯一索引',
  KEY `idx_app_id` (`app_id`) USING BTREE COMMENT '应用ID索引',
  KEY `idx_key_status` (`key_status`) USING BTREE COMMENT '密钥状态索引',
  KEY `idx_expire_time` (`expire_time`) USING BTREE COMMENT '过期时间索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='API密钥管理表';

-- --------------------------------------------------------
-- 3. API接口定义表
-- 定义系统中所有可供开放的API接口
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `api_interface` (
  `id` bigint NOT NULL COMMENT '接口ID',
  `interface_name` varchar(100) NOT NULL COMMENT '接口名称',
  `interface_code` varchar(100) NOT NULL COMMENT '接口标识码(唯一)',
  `interface_path` varchar(255) NOT NULL COMMENT '接口路径',
  `interface_method` varchar(20) NOT NULL COMMENT 'HTTP方法:GET,POST,PUT,DELETE等',
  `interface_description` varchar(500) DEFAULT NULL COMMENT '接口描述',
  `interface_category` varchar(50) DEFAULT NULL COMMENT '接口分类',
  `service_name` varchar(100) DEFAULT NULL COMMENT '后端服务名称',
  `request_params` text DEFAULT NULL COMMENT '请求参数定义(JSON)',
  `response_example` text DEFAULT NULL COMMENT '响应示例(JSON)',
  `is_auth_required` tinyint NOT NULL DEFAULT '1' COMMENT '是否需要认证:0-否,1-是',
  `rate_limit` int DEFAULT NULL COMMENT '限流次数(每分钟)',
  `timeout` int DEFAULT '30000' COMMENT '超时时间(毫秒)',
  `interface_status` tinyint NOT NULL DEFAULT '1' COMMENT '接口状态:0-已下线,1-已上线,2-维护中',
  `version` varchar(20) DEFAULT 'v1' COMMENT '接口版本',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除:0-未删除,1-已删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_interface_code` (`interface_code`) USING BTREE COMMENT '接口标识唯一索引',
  KEY `idx_interface_path` (`interface_path`) USING BTREE COMMENT '接口路径索引',
  KEY `idx_interface_status` (`interface_status`) USING BTREE COMMENT '接口状态索引',
  KEY `idx_service_name` (`service_name`) USING BTREE COMMENT '服务名称索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='API接口定义表';

-- --------------------------------------------------------
-- 4. API权限配置表
-- 管理应用对API接口的访问权限
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `api_permission` (
  `id` bigint NOT NULL COMMENT '权限ID',
  `app_id` bigint NOT NULL COMMENT '应用ID',
  `interface_id` bigint NOT NULL COMMENT '接口ID',
  `permission_status` tinyint NOT NULL DEFAULT '1' COMMENT '权限状态:0-已禁用,1-已启用',
  `expire_time` datetime DEFAULT NULL COMMENT '权限过期时间(NULL表示永久)',
  `grant_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
  `grant_by` bigint DEFAULT NULL COMMENT '授权人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除:0-未删除,1-已删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_app_interface` (`app_id`, `interface_id`) USING BTREE COMMENT '应用接口唯一索引',
  KEY `idx_app_id` (`app_id`) USING BTREE COMMENT '应用ID索引',
  KEY `idx_interface_id` (`interface_id`) USING BTREE COMMENT '接口ID索引',
  KEY `idx_permission_status` (`permission_status`) USING BTREE COMMENT '权限状态索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='API权限配置表';

-- --------------------------------------------------------
-- 5. API配额限制表
-- 管理应用的API调用配额
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `api_quota` (
  `id` bigint NOT NULL COMMENT '配额ID',
  `app_id` bigint NOT NULL COMMENT '应用ID',
  `interface_id` bigint DEFAULT NULL COMMENT '接口ID(NULL表示全局配额)',
  `quota_type` tinyint NOT NULL DEFAULT '1' COMMENT '配额类型:1-每分钟,2-每小时,3-每天,4-每月',
  `quota_limit` bigint NOT NULL COMMENT '配额限制(次数)',
  `quota_used` bigint NOT NULL DEFAULT '0' COMMENT '已使用配额',
  `reset_time` datetime DEFAULT NULL COMMENT '配额重置时间',
  `alert_threshold` int DEFAULT '80' COMMENT '告警阈值(百分比)',
  `is_alerted` tinyint NOT NULL DEFAULT '0' COMMENT '是否已告警:0-否,1-是',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除:0-未删除,1-已删除',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_app_id` (`app_id`) USING BTREE COMMENT '应用ID索引',
  KEY `idx_interface_id` (`interface_id`) USING BTREE COMMENT '接口ID索引',
  KEY `idx_reset_time` (`reset_time`) USING BTREE COMMENT '重置时间索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='API配额限制表';

-- --------------------------------------------------------
-- 6. API调用日志表
-- 记录所有API调用的详细日志
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `api_call_log` (
  `id` bigint NOT NULL COMMENT '日志ID',
  `request_id` varchar(64) NOT NULL COMMENT '请求ID(唯一)',
  `app_id` bigint NOT NULL COMMENT '应用ID',
  `api_key` varchar(64) NOT NULL COMMENT 'API密钥',
  `interface_id` bigint NOT NULL COMMENT '接口ID',
  `interface_path` varchar(255) NOT NULL COMMENT '接口路径',
  `request_method` varchar(20) NOT NULL COMMENT '请求方法',
  `request_params` text DEFAULT NULL COMMENT '请求参数',
  `request_ip` varchar(50) NOT NULL COMMENT '请求IP',
  `request_time` datetime NOT NULL COMMENT '请求时间',
  `response_code` int NOT NULL COMMENT '响应状态码',
  `response_time` int NOT NULL COMMENT '响应耗时(毫秒)',
  `response_size` bigint DEFAULT '0' COMMENT '响应大小(字节)',
  `error_message` text DEFAULT NULL COMMENT '错误信息',
  `user_agent` varchar(500) DEFAULT NULL COMMENT '用户代理',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_request_id` (`request_id`) USING BTREE COMMENT '请求ID唯一索引',
  KEY `idx_app_id` (`app_id`) USING BTREE COMMENT '应用ID索引',
  KEY `idx_interface_id` (`interface_id`) USING BTREE COMMENT '接口ID索引',
  KEY `idx_request_time` (`request_time`) USING BTREE COMMENT '请求时间索引',
  KEY `idx_response_code` (`response_code`) USING BTREE COMMENT '响应码索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='API调用日志表';

-- --------------------------------------------------------
-- 7. 网关路由配置表
-- 配置网关的动态路由规则
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `gateway_route` (
  `id` bigint NOT NULL COMMENT '路由ID',
  `route_name` varchar(100) NOT NULL COMMENT '路由名称',
  `route_code` varchar(100) NOT NULL COMMENT '路由标识码(唯一)',
  `route_path` varchar(255) NOT NULL COMMENT '路由路径(支持通配符)',
  `route_method` varchar(20) DEFAULT NULL COMMENT 'HTTP方法(NULL表示全部)',
  `target_service` varchar(100) NOT NULL COMMENT '目标服务名称',
  `target_path` varchar(255) NOT NULL COMMENT '目标路径',
  `load_balance_strategy` varchar(50) DEFAULT 'ROUND_ROBIN' COMMENT '负载均衡策略:ROUND_ROBIN,WEIGHTED_ROUND_ROBIN,LEAST_CONNECTIONS,CONSISTENT_HASH',
  `route_order` int NOT NULL DEFAULT '100' COMMENT '路由优先级(数值越小优先级越高)',
  `is_strip_prefix` tinyint NOT NULL DEFAULT '1' COMMENT '是否去除前缀:0-否,1-是',
  `timeout` int DEFAULT '30000' COMMENT '超时时间(毫秒)',
  `retry_times` int DEFAULT '0' COMMENT '重试次数',
  `route_status` tinyint NOT NULL DEFAULT '1' COMMENT '路由状态:0-已禁用,1-已启用',
  `route_metadata` text DEFAULT NULL COMMENT '路由元数据(JSON)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除:0-未删除,1-已删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_route_code` (`route_code`) USING BTREE COMMENT '路由标识唯一索引',
  KEY `idx_route_path` (`route_path`) USING BTREE COMMENT '路由路径索引',
  KEY `idx_route_status` (`route_status`) USING BTREE COMMENT '路由状态索引',
  KEY `idx_route_order` (`route_order`) USING BTREE COMMENT '路由优先级索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='网关路由配置表';

-- --------------------------------------------------------
-- 8. 网关服务注册表
-- 注册后端服务实例,用于负载均衡
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `gateway_service` (
  `id` bigint NOT NULL COMMENT '服务ID',
  `service_name` varchar(100) NOT NULL COMMENT '服务名称',
  `service_host` varchar(255) NOT NULL COMMENT '服务主机地址',
  `service_port` int NOT NULL COMMENT '服务端口',
  `service_weight` int NOT NULL DEFAULT '100' COMMENT '服务权重(用于加权负载均衡)',
  `service_status` tinyint NOT NULL DEFAULT '1' COMMENT '服务状态:0-已下线,1-已上线,2-维护中',
  `health_check_url` varchar(255) DEFAULT NULL COMMENT '健康检查URL',
  `last_health_check_time` datetime DEFAULT NULL COMMENT '最后健康检查时间',
  `health_check_status` tinyint DEFAULT '1' COMMENT '健康检查状态:0-异常,1-正常',
  `service_metadata` text DEFAULT NULL COMMENT '服务元数据(JSON)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除:0-未删除,1-已删除',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_service_name` (`service_name`) USING BTREE COMMENT '服务名称索引',
  KEY `idx_service_status` (`service_status`) USING BTREE COMMENT '服务状态索引',
  KEY `idx_health_check_status` (`health_check_status`) USING BTREE COMMENT '健康检查状态索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='网关服务注册表';

-- --------------------------------------------------------
-- 9. 网关插件配置表
-- 配置网关的各种插件(限流、熔断、认证等)
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `gateway_plugin` (
  `id` bigint NOT NULL COMMENT '插件ID',
  `plugin_name` varchar(100) NOT NULL COMMENT '插件名称',
  `plugin_type` varchar(50) NOT NULL COMMENT '插件类型:RATE_LIMIT,CIRCUIT_BREAKER,AUTH,TRANSFORM,LOG等',
  `plugin_config` text NOT NULL COMMENT '插件配置(JSON)',
  `apply_to_route` varchar(100) DEFAULT NULL COMMENT '应用到路由(route_code,NULL表示全局)',
  `apply_to_service` varchar(100) DEFAULT NULL COMMENT '应用到服务(service_name)',
  `plugin_order` int NOT NULL DEFAULT '100' COMMENT '插件执行优先级',
  `plugin_status` tinyint NOT NULL DEFAULT '1' COMMENT '插件状态:0-已禁用,1-已启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除:0-未删除,1-已删除',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_plugin_type` (`plugin_type`) USING BTREE COMMENT '插件类型索引',
  KEY `idx_apply_to_route` (`apply_to_route`) USING BTREE COMMENT '应用路由索引',
  KEY `idx_plugin_status` (`plugin_status`) USING BTREE COMMENT '插件状态索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='网关插件配置表';

-- ============================================================
-- 初始化数据
-- ============================================================

-- 插入默认API接口
INSERT INTO `api_interface` (`id`, `interface_name`, `interface_code`, `interface_path`, `interface_method`,
  `interface_description`, `interface_category`, `service_name`, `is_auth_required`, `rate_limit`, `interface_status`)
VALUES
  (1, '用户信息查询', 'user.info.get', '/api/user/info', 'GET', '查询用户基本信息', '用户管理', 'platform-identity', 1, 100, 1),
  (2, '文件上传', 'file.upload.post', '/api/file/upload', 'POST', '上传文件到存证平台', '文件管理', 'platform-backend', 1, 50, 1),
  (3, '文件查询', 'file.info.get', '/api/file/info', 'GET', '查询文件详细信息', '文件管理', 'platform-backend', 1, 200, 1)
ON DUPLICATE KEY UPDATE interface_name=interface_name;

-- 插入默认网关路由
INSERT INTO `gateway_route` (`id`, `route_name`, `route_code`, `route_path`, `target_service`, `target_path`,
  `load_balance_strategy`, `route_order`, `route_status`)
VALUES
  (1, '用户服务路由', 'route.user', '/gateway/user/**', 'platform-identity', '/api/user/**', 'ROUND_ROBIN', 100, 1),
  (2, '文件服务路由', 'route.file', '/gateway/file/**', 'platform-backend', '/api/file/**', 'ROUND_ROBIN', 100, 1),
  (3, 'FISCO区块链路由', 'route.fisco', '/gateway/blockchain/**', 'platform-fisco', '/api/**', 'ROUND_ROBIN', 100, 1)
ON DUPLICATE KEY UPDATE route_name=route_name;

-- ============================================================
-- 创建索引优化
-- ============================================================

-- API调用日志表分区优化(按月分区,提升查询性能)
-- 注意: 需要根据实际数据量决定是否启用分区
-- ALTER TABLE `api_call_log` PARTITION BY RANGE (TO_DAYS(request_time)) (
--   PARTITION p202501 VALUES LESS THAN (TO_DAYS('2025-02-01')),
--   PARTITION p202502 VALUES LESS THAN (TO_DAYS('2025-03-01'))
-- );

-- ============================================================
-- 数据库迁移脚本结束
-- ============================================================
