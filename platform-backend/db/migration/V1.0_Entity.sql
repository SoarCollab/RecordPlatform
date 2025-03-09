-- step 1
-- 创建图片存储表
CREATE TABLE IF NOT EXISTS `image_store` (
  `uid` varchar(64) NOT NULL COMMENT '图片唯一标识',
  `name` varchar(255) DEFAULT NULL COMMENT '图片名称',
  `time` datetime DEFAULT NULL COMMENT '上传时间',
  PRIMARY KEY (`uid`) USING BTREE,
  KEY `idx_time` (`time`) USING BTREE COMMENT '时间索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='图片存储信息表';

-- 创建用户账户表
CREATE TABLE IF NOT EXISTS `account` (
  `id` bigint NOT NULL COMMENT '用户ID',
  `username` varchar(50) NOT NULL COMMENT '用户名',
  `password` varchar(128) NOT NULL COMMENT '密码',
  `email` varchar(100) NOT NULL COMMENT '邮箱',
  `role` varchar(20) DEFAULT 'user' COMMENT '角色',
  `avatar` varchar(255) DEFAULT NULL COMMENT '头像',
  `register_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
  `deleted` tinyint DEFAULT '0' COMMENT '是否删除(0-未删除，1-已删除)',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_username` (`username`) USING BTREE COMMENT '用户名唯一索引',
  UNIQUE KEY `uk_email` (`email`) USING BTREE COMMENT '邮箱唯一索引',
  KEY `idx_register_time` (`register_time`) USING BTREE COMMENT '注册时间索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户账户信息表';