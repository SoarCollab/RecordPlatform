-- =====================================================
-- 注册Backend服务为Identity的OAuth2客户端
-- 用于实现SSO单点登录
-- 执行前请确保RecordPlatform数据库已存在
-- =====================================================

USE RecordPlatform;

-- 插入backend-service OAuth2客户端配置
INSERT INTO oauth_client (
    client_key,
    client_secret,
    client_name,
    description,
    redirect_uris,
    scopes,
    grant_types,
    access_token_validity,
    refresh_token_validity,
    auto_approve,
    status,
    create_time,
    update_time
) VALUES (
    'backend-service',
    -- 客户端密钥（BCrypt加密后的 'backend-secret-2025'）
    -- 生产环境请使用更强的密钥并通过环境变量配置
    '$2a$10$3zQvVQ9YzXJv8N2KvN5yZeR7XqE8JqY2b.hN9Y0N5jXqN7Y0N5jXq',
    'Backend Service',
    'RecordPlatform主后端服务，提供文件管理、区块链存证等核心功能',
    'http://localhost:8000/record-platform/api/oauth2/callback,http://127.0.0.1:8000/record-platform/api/oauth2/callback',
    'read,write',
    'authorization_code,refresh_token',
    7200,          -- access_token有效期：2小时
    2592000,       -- refresh_token有效期：30天
    0,             -- 不自动授权，需要用户手动确认
    1,             -- 状态：启用
    NOW(),
    NOW()
) ON DUPLICATE KEY UPDATE
    client_secret = VALUES(client_secret),
    client_name = VALUES(client_name),
    description = VALUES(description),
    redirect_uris = VALUES(redirect_uris),
    scopes = VALUES(scopes),
    grant_types = VALUES(grant_types),
    access_token_validity = VALUES(access_token_validity),
    refresh_token_validity = VALUES(refresh_token_validity),
    auto_approve = VALUES(auto_approve),
    status = VALUES(status),
    update_time = NOW();

-- 验证插入结果
SELECT
    client_id,
    client_key,
    client_name,
    redirect_uris,
    scopes,
    grant_types,
    status,
    create_time
FROM oauth_client
WHERE client_key = 'backend-service';

-- =====================================================
-- 重要说明
-- =====================================================
-- 1. 客户端密钥说明：
--    - 示例中的密钥是 'backend-secret-2025' 的BCrypt加密值
--    - 生产环境必须修改为更强的密钥
--    - 在backend的application.yml中配置：
--      oauth2.client.client-secret=${OAUTH2_CLIENT_SECRET:backend-secret-2025}
--
-- 2. 回调地址说明：
--    - 本地开发环境：http://localhost:8000/record-platform/api/oauth2/callback
--    - 生产环境需要修改为实际的域名和HTTPS地址
--    - 支持多个回调地址，用逗号分隔
--
-- 3. 授权范围说明：
--    - read: 读取用户信息和资源
--    - write: 修改资源
--    - 可根据需要扩展更细粒度的scope
--
-- 4. 如何生成新的客户端密钥：
--    方法1：使用identity的API（推荐）
--      POST http://localhost:8888/api/oauth/clients
--      {
--        "clientKey": "backend-service",
--        "clientName": "Backend Service",
--        ...
--      }
--
--    方法2：使用BCrypt在线工具或编程方式生成
--      Java示例：
--      String encodedSecret = new BCryptPasswordEncoder().encode("your-secret");
--
-- 5. 安全建议：
--    - 使用环境变量存储客户端密钥，不要硬编码
--    - 定期轮换客户端密钥
--    - 生产环境必须使用HTTPS
--    - 严格限制redirect_uris，防止重定向攻击
-- =====================================================
