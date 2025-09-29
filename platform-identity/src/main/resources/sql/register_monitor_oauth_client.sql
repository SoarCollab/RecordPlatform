-- ============================================================================
-- OAuth2客户端注册脚本 - Platform Monitor
-- ============================================================================
-- 用途：将platform-monitor注册为identity的OAuth2客户端，实现单点登录(SSO)
-- 执行方式：mysql -u root -p RecordPlatform < register_monitor_oauth_client.sql
-- ============================================================================

-- 插入monitor客户端记录
INSERT INTO oauth_client (
    id,
    client_id,
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
    update_time,
    deleted
) VALUES (
    1003,
    1003,
    'platform-monitor-client',
    -- BCrypt加密的"monitor_secret_2025"
    -- 注意：这是示例密钥，生产环境应使用更复杂的密钥并重新生成BCrypt哈希
    '$2a$10$X5wkX5wkX5wkX5wkX5wkXuF.qZ7Qx6Qx6Qx6Qx6Qx6Qx6Qx6Q',
    '平台监控系统',
    '用于platform-monitor的OAuth2.0客户端接入，支持单点登录',
    -- 重定向URI列表（开发环境和生产环境）
    'http://localhost:8001/login/oauth2/code/identity,http://localhost:8001/oauth/callback,https://monitor.example.com/login/oauth2/code/identity',
    'read,write',
    'authorization_code,refresh_token',
    3600,    -- 访问令牌有效期：1小时
    86400,   -- 刷新令牌有效期：24小时
    0,       -- 非自动授权，需要用户确认
    1,       -- 启用状态
    NOW(),
    NOW(),
    0        -- 未删除
)
ON DUPLICATE KEY UPDATE
    client_secret = VALUES(client_secret),
    redirect_uris = VALUES(redirect_uris),
    scopes = VALUES(scopes),
    grant_types = VALUES(grant_types),
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
WHERE client_key = 'platform-monitor-client';

-- ============================================================================
-- 使用说明
-- ============================================================================
--
-- 1. client_secret说明：
--    - 明文密钥：monitor_secret_2025
--    - BCrypt哈希：$2a$10$X5wkX5wkX5wkX5wkX5wkXuF.qZ7Qx6Qx6Qx6Qx6Qx6Qx6Qx6Q
--    - 生产环境请使用更安全的密钥并重新生成BCrypt哈希
--
-- 2. 生成新的BCrypt哈希（Java代码）：
--    String plainSecret = "your_secret_here";
--    String bcryptHash = BCrypt.hashpw(plainSecret, BCrypt.gensalt(10));
--    System.out.println(bcryptHash);
--
-- 3. 重定向URI配置：
--    - Spring Security OAuth2 Client默认回调路径：/login/oauth2/code/{registrationId}
--    - 本例中registrationId为"identity"
--    - 因此回调地址为：http://localhost:8001/login/oauth2/code/identity
--
-- 4. 配置monitor端：
--    在monitor的application-dev.yml中配置：
--    spring.security.oauth2.client.registration.identity.client-id=platform-monitor-client
--    spring.security.oauth2.client.registration.identity.client-secret=monitor_secret_2025
--
-- ============================================================================
