-- ============================================================================
-- 数据库迁移脚本：OAuth2单点登录支持
-- ============================================================================
-- 版本：V2.0
-- 描述：为platform-monitor的account表添加OAuth2认证支持字段
-- 用途：支持通过platform-identity进行单点登录(SSO)
-- 执行方式：mysql -u root -p monitor < V2.0_OAuth_Support.sql
-- ============================================================================

USE monitor;

-- 检查数据库字符集
SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME
FROM information_schema.SCHEMATA
WHERE SCHEMA_NAME = 'monitor';

-- ============================================================================
-- 步骤1：为account表添加OAuth2支持字段
-- ============================================================================

-- 添加认证类型字段
ALTER TABLE account
    ADD COLUMN IF NOT EXISTS auth_type VARCHAR (20) DEFAULT 'local'
    COMMENT '认证类型：local-本地用户名密码认证，oauth-OAuth2单点登录认证';

-- 添加OAuth提供者字段
ALTER TABLE account
    ADD COLUMN IF NOT EXISTS oauth_provider VARCHAR (50) DEFAULT NULL
    COMMENT 'OAuth2提供者标识，如platform-identity、github、google等';

-- 添加OAuth用户ID字段
ALTER TABLE account
    ADD COLUMN IF NOT EXISTS oauth_user_id BIGINT DEFAULT NULL
    COMMENT 'OAuth2提供者系统中的用户ID，用于关联外部用户身份';

-- ============================================================================
-- 步骤2：添加索引以提升查询性能
-- ============================================================================

-- 添加认证类型索引
ALTER TABLE account
    ADD INDEX IF NOT EXISTS idx_auth_type (auth_type)
    COMMENT '认证类型索引，用于快速筛选本地用户或OAuth用户';

-- 添加OAuth用户复合索引
ALTER TABLE account
    ADD INDEX IF NOT EXISTS idx_oauth_user (oauth_provider, oauth_user_id)
    COMMENT 'OAuth用户复合索引，用于快速查找OAuth用户';

-- ============================================================================
-- 步骤3：数据迁移 - 将现有用户标记为本地认证
-- ============================================================================

-- 更新现有用户的认证类型为local
UPDATE account
SET auth_type = 'local'
WHERE auth_type IS NULL
   OR auth_type = '';

-- ============================================================================
-- 步骤4：添加数据完整性约束
-- ============================================================================

-- 确保auth_type不为NULL
ALTER TABLE account
    MODIFY COLUMN auth_type VARCHAR(20) NOT NULL DEFAULT 'local'
        COMMENT '认证类型：local-本地用户名密码认证，oauth-OAuth2单点登录认证';

-- 添加CHECK约束（MySQL 8.0.16+支持）
ALTER TABLE account
    ADD CONSTRAINT chk_auth_type
        CHECK (auth_type IN ('local', 'oauth')) COMMENT '认证类型必须为local或oauth';

-- 添加唯一约束：同一OAuth提供者的同一用户只能有一条记录
ALTER TABLE account
    ADD CONSTRAINT uk_oauth_user
        UNIQUE (oauth_provider, oauth_user_id)
        COMMENT '同一OAuth提供者的同一用户只能有一条记录';

-- ============================================================================
-- 验证迁移结果
-- ============================================================================

-- 查看account表结构
DESCRIBE account;

-- 查看新添加的字段
SELECT COLUMN_NAME    AS '字段名',
       COLUMN_TYPE    AS '数据类型',
       IS_NULLABLE    AS '允许空值',
       COLUMN_DEFAULT AS '默认值',
       COLUMN_COMMENT AS '注释'
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'monitor'
  AND TABLE_NAME = 'account'
  AND COLUMN_NAME IN ('auth_type', 'oauth_provider', 'oauth_user_id');

-- 查看新添加的索引
SHOW INDEX FROM account
    WHERE Key_name IN ('idx_auth_type', 'idx_oauth_user', 'uk_oauth_user');

-- 统计现有用户的认证类型分布
SELECT auth_type AS '认证类型',
       COUNT(*)  AS '用户数量'
FROM account
GROUP BY auth_type;

-- ============================================================================
-- 使用说明
-- ============================================================================
--
-- 1. 字段说明：
--    - auth_type: 认证类型
--      * 'local': 本地用户名密码认证（原有用户）
--      * 'oauth': OAuth2单点登录认证（新接入用户）
--
--    - oauth_provider: OAuth提供者标识
--      * 'platform-identity': platform-identity系统
--      * 未来可扩展其他提供者如'github'、'google'等
--
--    - oauth_user_id: 外部系统用户ID
--      * 对于platform-identity，存储identity系统的user_id
--      * 用于OAuth登录时查找或创建本地用户
--
-- 2. 数据示例：
--    -- 本地用户
--    INSERT INTO account (username, password, email, auth_type)
--    VALUES ('admin', '$2a$10$...', 'admin@example.com', 'local');
--
--    -- OAuth用户
--    INSERT INTO account (username, email, auth_type, oauth_provider, oauth_user_id)
--    VALUES ('zhang_san', 'zhangsan@example.com', 'oauth', 'platform-identity', 1001);
--
-- 3. 查询示例：
--    -- 查找OAuth用户
--    SELECT * FROM account
--    WHERE oauth_provider = 'platform-identity'
--      AND oauth_user_id = 1001;
--
--    -- 查找所有OAuth用户
--    SELECT * FROM account WHERE auth_type = 'oauth';
--
--    -- 查找所有本地用户
--    SELECT * FROM account WHERE auth_type = 'local';
--
-- 4. 回滚方案（如需回滚）：
--    ALTER TABLE account DROP CONSTRAINT IF EXISTS chk_auth_type;
--    ALTER TABLE account DROP CONSTRAINT IF EXISTS uk_oauth_user;
--    ALTER TABLE account DROP INDEX IF EXISTS idx_auth_type;
--    ALTER TABLE account DROP INDEX IF EXISTS idx_oauth_user;
--    ALTER TABLE account DROP COLUMN IF EXISTS oauth_user_id;
--    ALTER TABLE account DROP COLUMN IF EXISTS oauth_provider;
--    ALTER TABLE account DROP COLUMN IF EXISTS auth_type;
--
-- ============================================================================
-- 迁移完成
-- ============================================================================

SELECT '✅ 数据库迁移完成！account表已支持OAuth2单点登录' AS '状态';
