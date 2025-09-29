package cn.flying.identity.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.dto.Account;
import cn.flying.identity.dto.OAuthClient;
import cn.flying.identity.service.AccountService;
import cn.flying.identity.service.BaseService;
import cn.flying.identity.service.JwtBlacklistService;
import cn.flying.identity.service.OAuthService;
import cn.flying.identity.service.SSOService;
import cn.flying.identity.util.IdUtils;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * SSO 单点登录服务实现类
 * 基于 SA-Token 实现完整的单点登录功能
 *
 * @author 王贝强
 */
@Slf4j
@Service
public class SSOServiceImpl extends BaseService implements SSOService {

    @Value("${redis.prefix.sso.token:sso:token:}")
    private String ssoTokenPrefix;

    @Value("${redis.prefix.sso.client:sso:client:}")
    private String ssoClientPrefix;

    @Value("${redis.prefix.sso.user:sso:user:}")
    private String ssoUserPrefix;

    @Value("${cache.expire.sso.token:7200}")
    private int ssoTokenTimeout;

    @Resource
    private AccountService accountService;

    @Resource
    private OAuthService oauthService;

    @Resource
    private JwtBlacklistService jwtBlacklistService;

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    @Override
    public Result<Map<String, Object>> getSSOLoginInfo(String clientId, String redirectUri, String scope, String state) {
        try {
            // 参数验证
            requireNonBlank(clientId, "客户端ID不能为空");
            requireNonBlank(redirectUri, "重定向URI不能为空");

            Map<String, Object> result = new HashMap<>();

            // 验证客户端
            Result<OAuthClient> clientResult = oauthService.getClient(clientId);
            if (!isSuccess(clientResult) || clientResult.getData() == null) {
                logWarn("无效的客户端ID: {}", clientId);
                return error("无效的客户端ID");
            }

            OAuthClient client = clientResult.getData();

            // 验证重定向URI的安全性
            if (!isValidRedirectUri(client, redirectUri)) {
                logWarn("无效的重定向URI: clientId={}, redirectUri={}", clientId, redirectUri);
                return error("无效的重定向URI");
            }

            // 检查客户端是否启用
            if (client.getStatus() != 1) {
                logWarn("客户端已禁用: {}", clientId);
                return error("客户端已禁用");
            }

            // 检查用户是否已登录
            if (StpUtil.isLogin()) {
                // 已登录，直接返回授权信息
                Long userId = StpUtil.getLoginIdAsLong();
                Account account = accountService.findAccountById(userId);

                if (account != null && account.getDeleted() == 0) {
                    // 生成 SSO Token
                    String ssoToken = generateSSOToken(userId, clientId);

                    result.put("status", "logged_in");
                    result.put("user_id", userId);
                    result.put("username", account.getUsername());
                    result.put("sso_token", ssoToken);
                    result.put("redirect_uri", redirectUri);
                    result.put("state", getOrElse(state, ""));
                    result.put("client_name", client.getClientName());

                    // 记录客户端登录状态
                    recordClientLogin(userId, clientId, client.getClientName());

                    return success(result);
                }
            }

            // 未登录，返回登录页面信息
            result.put("status", "need_login");
            result.put("client_id", clientId);
            result.put("client_name", client.getClientName());
            result.put("redirect_uri", redirectUri);
            result.put("scope", getOrElse(scope, ""));
            result.put("state", getOrElse(state, ""));
            result.put("login_url", "/api/sso/login");

            return success(result);
        } catch (Exception e) {
            logError("获取SSO登录信息失败", e);
            return error("获取SSO登录信息失败: " + e.getMessage());
        }
    }

    @Override
    public Result<Map<String, Object>> processSSOLogin(String username, String password, String clientId,
                                                       String redirectUri, String scope, String state) {
        try {
            // 验证用户凭证
            Account account = accountService.findAccountByNameOrEmail(username);
            if (account == null) {
                return Result.error(ResultEnum.USER_NOT_EXIST, null);
            }

            if (!accountService.matchesPassword(password, account.getPassword())) {
                return Result.error(ResultEnum.USER_LOGIN_ERROR, null);
            }

            // 验证客户端
            Result<OAuthClient> clientResult = oauthService.getClient(clientId);
            if (!(clientResult.getCode() == 1) || clientResult.getData() == null) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            OAuthClient client = clientResult.getData();

            // SA-Token 登录
            StpUtil.login(account.getId());
            StpUtil.getSession().set("role", account.getRole());
            StpUtil.getSession().set("username", account.getUsername());
            StpUtil.getSession().set("email", account.getEmail());

            // 生成 SSO Token
            String ssoToken = generateSSOToken(account.getId(), clientId);

            // 记录客户端登录状态
            recordClientLogin(account.getId(), clientId, client.getClientName());

            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("user_id", account.getId());
            result.put("username", account.getUsername());
            result.put("sso_token", ssoToken);
            result.put("redirect_uri", redirectUri);
            result.put("state", state);
            result.put("access_token", StpUtil.getTokenValue());

            return Result.success(result);
        } catch (Exception e) {
            log.error("SSO登录处理失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> checkSSOLoginStatus(String clientId, String redirectUri, String scope, String state) {
        try {
            Map<String, Object> result = new HashMap<>();

            if (StpUtil.isLogin()) {
                Long userId = StpUtil.getLoginIdAsLong();
                Account account = accountService.findAccountById(userId);

                if (account != null) {
                    result.put("status", "logged_in");
                    result.put("user_id", userId);
                    result.put("username", account.getUsername());
                    result.put("email", account.getEmail());
                    result.put("role", account.getRole());

                    // 检查是否已在该客户端登录
                    String clientKey = ssoClientPrefix + userId + ":" + clientId;
                    if (redisTemplate.hasKey(clientKey)) {
                        result.put("client_logged_in", true);
                    } else {
                        result.put("client_logged_in", false);
                    }

                    return Result.success(result);
                }
            }

            result.put("status", "not_logged_in");
            return Result.success(result);
        } catch (Exception e) {
            log.error("检查SSO登录状态失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> ssoLogout(String redirectUri, String clientId) {
        try {
            Map<String, Object> result = new HashMap<>();

            if (StpUtil.isLogin()) {
                Long userId = StpUtil.getLoginIdAsLong();

                if (clientId != null) {
                    // 从指定客户端注销
                    removeClientLogin(userId, clientId);
                    result.put("status", "client_logout_success");
                    result.put("client_id", clientId);
                } else {
                    // 全局注销
                    clearAllClientLogins(userId);
                    try {
                        String token = StpUtil.getTokenValue();
                        if (token != null && !token.isBlank()) {
                            jwtBlacklistService.blacklistToken(token, -1);
                        }
                    } catch (Exception ignored) {
                    }
                    StpUtil.logout();
                    result.put("status", "global_logout_success");
                }

                result.put("user_id", userId);
            } else {
                result.put("status", "not_logged_in");
            }

            if (redirectUri != null) {
                result.put("redirect_uri", redirectUri);
            }

            return Result.success(result);
        } catch (Exception e) {
            log.error("SSO注销失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getSSOUserInfo(String token) {
        try {
            // 验证 SSO Token
            String tokenKey = ssoTokenPrefix + token;
            String userInfo = redisTemplate.opsForValue().get(tokenKey);

            if (userInfo == null) {
                return Result.error(ResultEnum.PERMISSION_TOKEN_INVALID, null);
            }

            // 解析用户信息
            String[] parts = userInfo.split(":");
            if (parts.length < 2) {
                return Result.error(ResultEnum.PERMISSION_TOKEN_INVALID, null);
            }

            Long userId = Long.valueOf(parts[0]);
            String clientId = parts[1];

            Account account = accountService.findAccountById(userId);
            if (account == null) {
                return Result.error(ResultEnum.USER_NOT_EXIST, null);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("user_id", userId);
            result.put("username", account.getUsername());
            result.put("email", account.getEmail());
            result.put("role", account.getRole());
            result.put("client_id", clientId);
            result.put("external_id", IdUtils.toExternalUserId(userId));

            return Result.success(result);
        } catch (Exception e) {
            log.error("获取SSO用户信息失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> validateSSOToken(String token) {
        try {
            String tokenKey = ssoTokenPrefix + token;
            String userInfo = redisTemplate.opsForValue().get(tokenKey);

            Map<String, Object> result = new HashMap<>();

            if (userInfo != null) {
                String[] parts = userInfo.split(":");
                if (parts.length >= 2) {
                    result.put("valid", true);
                    result.put("user_id", Long.valueOf(parts[0]));
                    result.put("client_id", parts[1]);

                    // 获取剩余有效时间
                    Long expire = redisTemplate.getExpire(tokenKey, TimeUnit.SECONDS);
                    result.put("expires_in", expire);

                    return Result.success(result);
                }
            }

            result.put("valid", false);
            return Result.success(result);
        } catch (Exception e) {
            log.error("验证SSO Token失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> refreshSSOToken(String refreshToken, String clientId) {
        try {
            // 这里可以实现刷新令牌的逻辑
            // 目前简化处理，直接返回错误
            return Result.error(ResultEnum.PARAM_IS_INVALID, null);
        } catch (Exception e) {
            log.error("刷新SSO Token失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getLoggedInClients() {
        try {
            if (!StpUtil.isLogin()) {
                return Result.error(ResultEnum.USER_NOT_LOGGED_IN, null);
            }

            Long userId = StpUtil.getLoginIdAsLong();
            String pattern = ssoClientPrefix + userId + ":*";

            // 使用SCAN替代KEYS命令，避免阻塞Redis
            Set<String> keys = scanKeys(pattern);

            List<Map<String, Object>> clients = new ArrayList<>();

            for (String key : keys) {
                String clientInfo = redisTemplate.opsForValue().get(key);
                if (clientInfo != null) {
                    String[] parts = clientInfo.split(":");
                    if (parts.length >= 2) {
                        Map<String, Object> client = new HashMap<>();
                        client.put("client_id", parts[0]);
                        client.put("client_name", parts[1]);
                        client.put("login_time", parts.length > 2 ? parts[2] : "");
                        clients.add(client);
                    }
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("user_id", userId);
            result.put("clients", clients);
            result.put("total", clients.size());

            return Result.success(result);
        } catch (Exception e) {
            log.error("获取已登录客户端列表失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Void> logoutFromClient(String clientId) {
        try {
            if (!StpUtil.isLogin()) {
                return Result.error(ResultEnum.USER_NOT_LOGGED_IN, null);
            }

            Long userId = StpUtil.getLoginIdAsLong();
            removeClientLogin(userId, clientId);

            return Result.success(null);
        } catch (Exception e) {
            log.error("从客户端注销失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 移除客户端登录状态
     */
    private void removeClientLogin(Long userId, String clientId) {
        String clientKey = ssoClientPrefix + userId + ":" + clientId;
        redisTemplate.delete(clientKey);

        String userKey = ssoUserPrefix + userId;
        redisTemplate.opsForSet().remove(userKey, clientId);
    }

    /**
     * 清除所有客户端登录状态
     */
    private void clearAllClientLogins(Long userId) {
        // 获取用户的所有登录客户端
        String userKey = ssoUserPrefix + userId;
        Set<String> clientIds = redisTemplate.opsForSet().members(userKey);

        if (clientIds != null) {
            for (String clientId : clientIds) {
                String clientKey = ssoClientPrefix + userId + ":" + clientId;
                redisTemplate.delete(clientKey);
            }
        }

        // 清除用户客户端列表
        redisTemplate.delete(userKey);

        // 清除相关的 SSO Token - 使用SCAN替代KEYS
        String pattern = ssoTokenPrefix + "*";
        Set<String> tokenKeys = scanKeys(pattern);
        for (String tokenKey : tokenKeys) {
            String userInfo = redisTemplate.opsForValue().get(tokenKey);
            if (userInfo != null && userInfo.startsWith(userId + ":")) {
                redisTemplate.delete(tokenKey);
            }
        }
    }

    /**
     * 验证重定向URI的安全性
     * 防止开放重定向攻击
     *
     * @param client      OAuth客户端
     * @param redirectUri 重定向URI
     * @return 是否有效
     */
    private boolean isValidRedirectUri(OAuthClient client, String redirectUri) {
        if (StrUtil.isBlank(redirectUri)) {
            return false;
        }

        try {
            // 检查重定向URI格式
            java.net.URI uri = new java.net.URI(redirectUri);
            String scheme = uri.getScheme();

            // 仅允许 https 和 http (仅开发环境)
            if (!"https".equals(scheme) && !"http".equals(scheme)) {
                log.warn("不支持的重定向URI协议: {}", scheme);
                return false;
            }

            // 生产环境强制使用 HTTPS
            String env = System.getProperty("spring.profiles.active", "prod");
            if ("prod".equals(env) && !"https".equals(scheme)) {
                log.warn("生产环境必须使用HTTPS重定向URI: {}", redirectUri);
                return false;
            }

            // 验证是否在客户端配置的重定向URI列表中
            String configuredUris = client.getRedirectUris();
            if (StrUtil.isBlank(configuredUris)) {
                return false;
            }

            String[] allowedUris = configuredUris.split(",");
            for (String allowedUri : allowedUris) {
                if (redirectUri.equals(allowedUri.trim())) {
                    return true;
                }
                // 支持子路径匹配（但要防止子域名攻击）
                if (allowedUri.endsWith("/*") && redirectUri.startsWith(allowedUri.substring(0, allowedUri.length() - 2))) {
                    java.net.URI allowedDomain = new java.net.URI(allowedUri.substring(0, allowedUri.length() - 2));
                    if (uri.getHost().equals(allowedDomain.getHost()) &&
                            uri.getPort() == allowedDomain.getPort()) {
                        return true;
                    }
                }
            }

            return false;
        } catch (Exception e) {
            log.error("验证重定向URI失败: {}", redirectUri, e);
            return false;
        }
    }

    /**
     * 生成 SSO Token
     */
    private String generateSSOToken(Long userId, String clientId) {
        String token = IdUtils.nextIdWithPrefix("SSO");
        String tokenKey = ssoTokenPrefix + token;
        String userInfo = userId + ":" + clientId + ":" + System.currentTimeMillis();

        redisTemplate.opsForValue().set(tokenKey, userInfo, ssoTokenTimeout, TimeUnit.SECONDS);

        return token;
    }

    /**
     * 记录客户端登录状态
     */
    private void recordClientLogin(Long userId, String clientId, String clientName) {
        String clientKey = ssoClientPrefix + userId + ":" + clientId;
        String clientInfo = clientId + ":" + clientName + ":" + System.currentTimeMillis();

        redisTemplate.opsForValue().set(clientKey, clientInfo, ssoTokenTimeout, TimeUnit.SECONDS);

        // 记录用户的所有登录客户端
        String userKey = ssoUserPrefix + userId;
        redisTemplate.opsForSet().add(userKey, clientId);
        redisTemplate.expire(userKey, ssoTokenTimeout, TimeUnit.SECONDS);
    }

    /**
     * 使用SCAN命令扫描Redis键
     * 替代阻塞的KEYS命令
     *
     * @param pattern 匹配模式
     * @return 匹配的键集合
     */
    private Set<String> scanKeys(String pattern) {
        Set<String> keys = new HashSet<>();

        try {
            // 使用SCAN命令迭代扫描
            redisTemplate.execute((RedisCallback<Set<String>>) connection -> {
                try (Cursor<byte[]> cursor = connection.scan(
                        ScanOptions.scanOptions()
                                .match(pattern)
                                .count(100)  // 每次扫描100个键
                                .build())) {
                    while (cursor.hasNext()) {
                        keys.add(new String(cursor.next()));
                    }
                } catch (Exception e) {
                    log.error("扫描Redis键失败: pattern={}", pattern, e);
                }
                return keys;
            });
        } catch (Exception e) {
            log.error("执行Redis SCAN命令失败: pattern={}", pattern, e);
        }

        return keys;
    }
}
