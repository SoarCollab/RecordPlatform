package cn.flying.identity.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.config.OAuthConfig;
import cn.flying.identity.dto.Account;
import cn.flying.identity.dto.OAuthClient;
import cn.flying.identity.dto.OAuthCode;
import cn.flying.identity.mapper.AccountMapper;
import cn.flying.identity.mapper.OAuthClientMapper;
import cn.flying.identity.mapper.OAuthCodeMapper;
import cn.flying.identity.service.OAuthClientSecretService;
import cn.flying.identity.service.OAuthService;
import cn.flying.identity.util.SecureLogger;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * OAuth2.0服务实现类
 * 实现SSO单点登录和第三方应用接入的具体逻辑
 */
@Service
public class OAuthServiceImpl implements OAuthService {

    @Resource
    private OAuthConfig oauthConfig;

    @Resource
    private OAuthClientMapper oauthClientMapper;

    @Resource
    private OAuthCodeMapper oauthCodeMapper;

    @Resource
    private AccountMapper accountMapper;

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    @Resource
    private OAuthClientSecretService oauthClientSecretService;

    /**
     * 第三方令牌Redis键前缀
     * 用于在revoke时同步清理第三方access/refresh映射
     */
    private static final String THIRD_PARTY_TOKEN_PREFIX = "third_party:token:";

    /**
     * 获取授权页面信息
     *
     * @param clientId   客户端标识符
     * @param redirectUri 重定向URI
     * @param scope       授权范围
     * @param state       状态参数
     * @return 授权页面信息
     */
    @Override
    public Result<Map<String, Object>> getAuthorizeInfo(String clientId, String redirectUri, String scope, String state) {
        try {
            // 验证客户端
            OAuthClient client = oauthClientMapper.findByClientKey(clientId);
            if (client == null || client.getStatus() != 1) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            // 验证重定向URI
            if (!isValidRedirectUri(client, redirectUri)) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }
            
            // 强制验证状态参数（防CSRF攻击）
            if (oauthConfig.isRequireState() && StrUtil.isBlank(state)) {
                SecureLogger.warn("缺少必需的状态参数: clientId={}", clientId);
                return createOAuthError("invalid_request", "Missing required state parameter");
            }

            // 验证授权范围
            if (!isValidScope(client, scope)) {
                SecureLogger.warn("无效的授权范围: clientId={}, requestScope={}",
                        clientId, scope);
                return createOAuthError("invalid_scope",
                    "The requested scope is invalid, unknown, or malformed");
            }

            // 检查用户登录状态
            if (!StpUtil.isLogin()) {
                return createOAuthError("access_denied", "User not logged in");
            }

            // 构建授权页面信息
            Map<String, Object> info = new HashMap<>();
            info.put("clientName", client.getClientName());
            info.put("description", client.getDescription());
            info.put("scope", scope);
            info.put("redirectUri", redirectUri);
            info.put("state", state);
            info.put("autoApprove", client.getAutoApprove() == 1);

            return Result.success(info);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 用户授权确认
     *
     * @param clientId   客户端标识符
     * @param redirectUri 重定向URI
     * @param scope       授权范围
     * @param state       状态参数
     * @param approved    是否同意授权
     * @return 授权结果（包含授权码或错误信息）
     */
    @Override
    public Result<String> authorize(String clientId, String redirectUri, String scope, String state, boolean approved) {
        try {
            // 验证客户端
            OAuthClient client = oauthClientMapper.findByClientKey(clientId);
            if (client == null || client.getStatus() != 1) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            // 验证重定向URI
            if (!isValidRedirectUri(client, redirectUri)) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }
            
            // 强制验证状态参数（防CSRF攻击）
            if (oauthConfig.isRequireState() && StrUtil.isBlank(state)) {
                SecureLogger.warn("缺少必需的状态参数: clientId={}", clientId);
                String errorUrl = redirectUri + "?error=invalid_request&error_description=missing_state_parameter";
                return Result.success(errorUrl);
            }
            
            // 验证授权范围
            if (!isValidScope(client, scope)) {
                SecureLogger.warn("无效的授权范围: clientId={}, requestScope={}, clientScope={}", 
                        clientId, scope, client.getScopes());
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            // 检查用户登录状态
            if (!StpUtil.isLogin()) {
                return Result.error(ResultEnum.USER_NOT_LOGGED_IN, null);
            }

            // 如果用户拒绝授权
            if (!approved) {
                String errorUrl = redirectUri + "?error=access_denied";
                if (StrUtil.isNotBlank(state)) {
                    errorUrl += "&state=" + state;
                }
                return Result.success(errorUrl);
            }

            // 生成授权码
            Long userId = StpUtil.getLoginIdAsLong();
            OAuthCode authCode = generateAuthorizationCode(clientId, userId, redirectUri, scope, state);

            // 构建重定向URL
            String redirectUrl = redirectUri + "?code=" + authCode.getCode();
            if (StrUtil.isNotBlank(state)) {
                redirectUrl += "&state=" + state;
            }

            return Result.success(redirectUrl);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 通过授权码获取访问令牌
     *
     * @param grantType    授权类型
     * @param code         授权码
     * @param redirectUri  重定向URI
     * @param clientId    客户端标识符
     * @param clientSecret 客户端密钥
     * @return 访问令牌信息
     */
    @Override
    public Result<Map<String, Object>> getAccessToken(String grantType, String code, String redirectUri,
                                                      String clientId, String clientSecret) {
        try {
            // 参数验证
            if (StrUtil.isBlank(grantType)) {
                return createOAuthError("invalid_request", "Missing grant_type parameter");
            }
            if (StrUtil.isBlank(code)) {
                return createOAuthError("invalid_request", "Missing code parameter");
            }
            if (StrUtil.isBlank(redirectUri)) {
                return createOAuthError("invalid_request", "Missing redirect_uri parameter");
            }
            if (StrUtil.isBlank(clientId)) {
                return createOAuthError("invalid_client", "Missing client_id parameter");
            }
            if (StrUtil.isBlank(clientSecret)) {
                return createOAuthError("invalid_client", "Missing client_secret parameter");
            }

            // 验证授权类型
            if (!"authorization_code".equals(grantType)) {
                return createOAuthError("unsupported_grant_type", 
                    "Grant type '" + grantType + "' is not supported. Only 'authorization_code' is supported.");
            }

            // 验证客户端
            OAuthClient client = validateClient(clientId, clientSecret);
            if (client == null) {
                return createOAuthError("invalid_client", "Client authentication failed");
            }
            if (client.getStatus() != 1) {
                return createOAuthError("invalid_client", "Client is disabled");
            }

            // 验证授权码并原子性标记为已使用
            OAuthCode authCode = validateAndUseAuthorizationCode(code, clientId, redirectUri);
            if (authCode == null) {
                return createOAuthError("invalid_grant", "Invalid or expired authorization code");
            }

            // 生成访问令牌
            String accessToken = generateAccessToken(authCode.getUserId(), clientId, authCode.getScope());
            String refreshToken = generateRefreshToken(authCode.getUserId(), clientId);

            // 构建响应
            Map<String, Object> tokenInfo = new HashMap<>();
            tokenInfo.put("access_token", accessToken);
            tokenInfo.put("token_type", "Bearer");
            tokenInfo.put("expires_in", client.getAccessTokenValidity());
            tokenInfo.put("refresh_token", refreshToken);
            tokenInfo.put("scope", authCode.getScope());

            return Result.success(tokenInfo);
        } catch (Exception e) {
            SecureLogger.error("获取访问令牌失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 刷新访问令牌
     *
     * @param grantType    授权类型
     * @param refreshToken 刷新令牌
     * @param clientId    客户端标识符
     * @param clientSecret 客户端密钥
     * @return 新的访问令牌信息
     */
    @Override
    public Result<Map<String, Object>> refreshAccessToken(String grantType, String refreshToken,
                                                          String clientId, String clientSecret) {
        try {
            // 参数验证
            if (StrUtil.isBlank(grantType)) {
                return createOAuthError("invalid_request", "Missing grant_type parameter");
            }
            if (StrUtil.isBlank(refreshToken)) {
                return createOAuthError("invalid_request", "Missing refresh_token parameter");
            }
            if (StrUtil.isBlank(clientId)) {
                return createOAuthError("invalid_client", "Missing client_id parameter");
            }
            if (StrUtil.isBlank(clientSecret)) {
                return createOAuthError("invalid_client", "Missing client_secret parameter");
            }

            // 验证授权类型
            if (!"refresh_token".equals(grantType)) {
                return createOAuthError("unsupported_grant_type", 
                    "Grant type '" + grantType + "' is not supported. Only 'refresh_token' is supported.");
            }

            // 验证客户端
            OAuthClient client = validateClient(clientId, clientSecret);
            if (client == null) {
                return createOAuthError("invalid_client", "Client authentication failed");
            }
            if (client.getStatus() != 1) {
                return createOAuthError("invalid_client", "Client is disabled");
            }

            // 验证刷新令牌
            String tokenKey = oauthConfig.getRefreshTokenPrefix() + refreshToken;
            Map<Object, Object> tokenData = redisTemplate.opsForHash().entries(tokenKey);

            if (tokenData.isEmpty()) {
                SecureLogger.warn("刷新令牌不存在或已过期: {}", refreshToken);
                return createOAuthError("invalid_grant", "Invalid or expired refresh token");
            }

            // 提取令牌信息
            String userIdStr = (String) tokenData.get("user_id");
            String tokenClientId = (String) tokenData.get("client_id");
            String oldAccessToken = (String) tokenData.get("access_token");

            // 验证客户端匹配
            if (!clientId.equals(tokenClientId)) {
                SecureLogger.warn("刷新令牌客户端不匹配: expected={}, actual={}", clientId, tokenClientId);
                return createOAuthError("invalid_grant", "Refresh token was issued to a different client");
            }

            Long userId = null;
            if (StrUtil.isNotBlank(userIdStr)) {
                userId = Long.valueOf(userIdStr);

                // 验证用户是否存在且有效
                Account account = accountMapper.selectById(userId);
                if (account == null || account.getDeleted() == 1) {
                    SecureLogger.warn("用户不存在或已删除: {}", userId);
                    return createOAuthError("invalid_grant", "User associated with refresh token no longer exists");
                }
            }

            // 生成新的访问令牌
            String scope = (String) tokenData.get("scope");
            if (StrUtil.isBlank(scope)) {
                scope = oauthConfig.getDefaultScope();
            }

            String newAccessToken = generateAccessToken(userId, clientId, scope);
            String newRefreshToken = generateRefreshToken(userId, clientId);

            // 删除旧的刷新令牌和访问令牌
            redisTemplate.delete(tokenKey);
            if (StrUtil.isNotBlank(oldAccessToken)) {
                String oldAccessTokenKey = oauthConfig.getAccessTokenPrefix() + oldAccessToken;
                redisTemplate.delete(oldAccessTokenKey);
            }

            // 注意：新的访问令牌和刷新令牌已经在generateAccessToken和generateRefreshToken方法中自动存储
            // 这里不需要重复存储

            // 注意：新的刷新令牌已经在generateRefreshToken方法中自动存储

            // 构建响应
            Map<String, Object> response = new HashMap<>();
            response.put("access_token", newAccessToken);
            response.put("token_type", "Bearer");
            response.put("expires_in", oauthConfig.getAccessTokenTimeout());
            response.put("refresh_token", newRefreshToken);
            response.put("refresh_token_expires_in", oauthConfig.getRefreshTokenTimeout());
            response.put("scope", scope);

            return Result.success(response);
        } catch (Exception e) {
            SecureLogger.error("刷新访问令牌失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 客户端凭证模式获取访问令牌
     *
     * @param grantType    授权类型
     * @param scope        授权范围
     * @param clientId    客户端标识符
     * @param clientSecret 客户端密钥
     * @return 访问令牌信息
     */
    @Override
    public Result<Map<String, Object>> getClientCredentialsToken(String grantType, String scope,
                                                                 String clientId, String clientSecret) {
        try {
            // 验证授权类型
            if (!"client_credentials".equals(grantType)) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            // 验证客户端
            OAuthClient client = validateClient(clientId, clientSecret);
            if (client == null) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            // 生成访问令牌（客户端凭证模式不需要用户ID）
            String accessToken = generateAccessToken(null, clientId, scope);

            // 构建响应
            Map<String, Object> tokenInfo = new HashMap<>();
            tokenInfo.put("access_token", accessToken);
            tokenInfo.put("token_type", "Bearer");
            tokenInfo.put("expires_in", client.getAccessTokenValidity());
            tokenInfo.put("scope", scope);

            return Result.success(tokenInfo);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 获取用户信息（通过访问令牌）
     *
     * @param accessToken 访问令牌
     * @return 用户信息
     */
    @Override
    public Result<Map<String, Object>> getUserInfo(String accessToken) {
        try {
            // 验证访问令牌 - 使用统一的Hash格式
            String tokenKey = oauthConfig.getAccessTokenPrefix() + accessToken;
            Map<Object, Object> tokenData = redisTemplate.opsForHash().entries(tokenKey);
            if (tokenData.isEmpty()) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            // 获取用户ID
            String userIdStr = (String) tokenData.get("user_id");
            if (StrUtil.isBlank(userIdStr)) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            Long userId = Long.valueOf(userIdStr);
            Account account = accountMapper.selectById(userId);
            if (account == null) {
                return Result.error(ResultEnum.USER_NOT_EXIST, null);
            }

            // 构建用户信息响应
            Map<String, Object> userInfo = new HashMap<>();
            userInfo.put("id", account.getId());
            userInfo.put("username", account.getUsername());
            userInfo.put("email", account.getEmail());
            userInfo.put("role", account.getRole());
            userInfo.put("avatar", account.getAvatar());
            userInfo.put("registerTime", account.getRegisterTime());

            return Result.success(userInfo);
        } catch (Exception e) {
            SecureLogger.error("获取用户信息失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 撤销令牌
     *
     * @param token         令牌
     * @param tokenTypeHint 令牌类型提示
     * @param clientId     客户端标识符
     * @param clientSecret  客户端密钥
     * @return 撤销结果
     */
    @Override
    public Result<Void> revokeToken(String token, String tokenTypeHint, String clientId, String clientSecret) {
        try {
            // 验证客户端
            OAuthClient client = validateClient(clientId, clientSecret);
            if (client == null) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            // 根据令牌类型提示删除相应的令牌
            boolean deleted = false;
            if ("access_token".equals(tokenTypeHint) || tokenTypeHint == null) {
                String accessTokenKey = oauthConfig.getAccessTokenPrefix() + token;
                deleted = redisTemplate.delete(accessTokenKey);
            }
            
            if ("refresh_token".equals(tokenTypeHint) || tokenTypeHint == null) {
                String refreshTokenKey = oauthConfig.getRefreshTokenPrefix() + token;
                Boolean refreshDeleted = redisTemplate.delete(refreshTokenKey);
                deleted = deleted || refreshDeleted;
            }

            // 同步清理第三方access/refresh映射，保持一致性
            clearThirdPartyMappings(token, tokenTypeHint);

            if (!deleted) {
                SecureLogger.warn("令牌撤销失败，令牌可能不存在: token={}, hint={}", token, tokenTypeHint);
            }

            return Result.success(null);
        } catch (Exception e) {
            SecureLogger.error("撤销令牌失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 清理第三方令牌映射
     * 根据token与类型提示，尝试删除所有已知提供商(wechat/google/github)的access/refresh映射键
     *
     * @param token 要清理的令牌值
     * @param tokenTypeHint 令牌类型提示（access_token/refresh_token/null）
     */
    private void clearThirdPartyMappings(String token, String tokenTypeHint) {
        try {
            if (cn.hutool.core.util.StrUtil.isBlank(token)) {
                return;
            }
            String[] providers = {"wechat", "google", "github"};
            boolean clearAccess = tokenTypeHint == null || "access_token".equalsIgnoreCase(tokenTypeHint);
            boolean clearRefresh = tokenTypeHint == null || "refresh_token".equalsIgnoreCase(tokenTypeHint);

            for (String provider : providers) {
                if (clearAccess) {
                    String accessKey = THIRD_PARTY_TOKEN_PREFIX + provider + ":access:" + token;
                    redisTemplate.delete(accessKey);
                }
                if (clearRefresh) {
                    String refreshKey = THIRD_PARTY_TOKEN_PREFIX + provider + ":refresh:" + token;
                    redisTemplate.delete(refreshKey);
                }
            }
        } catch (Exception e) {
            SecureLogger.warn("清理第三方映射时发生异常: token={}, hint={}", token, tokenTypeHint, e);
        }
    }

    /**
     * 验证客户端
     *
     * @param clientId    客户端标识符
     * @param clientSecret 客户端密钥
     * @return 客户端信息
     */
    @Override
    public OAuthClient validateClient(String clientId, String clientSecret) {
        if (StrUtil.isBlank(clientId) || StrUtil.isBlank(clientSecret)) {
            SecureLogger.warn("客户端验证参数为空: clientId={}, clientSecret={}", 
                    StrUtil.isBlank(clientId) ? "blank" : "present",
                    StrUtil.isBlank(clientSecret) ? "blank" : "present");
            return null;
        }
        
        try {
            // 先根据clientId查找客户端
            OAuthClient client = oauthClientMapper.findByClientKey(clientId);
            if (client == null) {
                SecureLogger.warn("客户端不存在: clientId={}", clientId);
                return null;
            }
            
            if (client.getStatus() != 1) {
                SecureLogger.warn("客户端已禁用: clientId={}, status={}", clientId, client.getStatus());
                return null;
            }
            
            // 使用专门的密钥服务验证客户端密钥
            if (!oauthClientSecretService.matches(clientSecret, client.getClientSecret())) {
                SecureLogger.warn("客户端密钥验证失败: clientId={}, secretIsEncrypted={}", 
                        clientId, oauthClientSecretService.isEncrypted(client.getClientSecret()));
                return null;
            }
            
            SecureLogger.debug("客户端验证成功: clientId={}, clientName={}", clientId, client.getClientName());
            return client;
        } catch (Exception e) {
            SecureLogger.error("客户端验证过程中发生异常: clientId={}", clientId, e);
            return null;
        }
    }

    /**
     * 生成授权码
     *
     * @param clientId   客户端标识符
     * @param userId      用户ID
     * @param redirectUri 重定向URI
     * @param scope       授权范围
     * @param state       状态参数
     * @return 授权码记录
     */
    @Override
    public OAuthCode generateAuthorizationCode(String clientId, Long userId, String redirectUri, String scope, String state) {
        OAuthCode authCode = new OAuthCode();
        authCode.setCode(IdUtil.fastSimpleUUID());
        authCode.setClientKey(clientId);
        authCode.setUserId(userId);
        authCode.setRedirectUri(redirectUri);
        authCode.setScope(scope);
        authCode.setState(state);
        authCode.setStatus(OAuthConfig.CodeStatus.VALID);
        authCode.setExpireTime(LocalDateTime.now().plusSeconds(oauthConfig.getCodeTimeout())); // 使用配置的过期时间

        oauthCodeMapper.insert(authCode);
        return authCode;
    }

    /**
     * 验证授权码
     *
     * @param code        授权码
     * @param clientId   客户端标识符
     * @param redirectUri 重定向URI
     * @return 授权码记录
     */
    @Override
    public OAuthCode validateAuthorizationCode(String code, String clientId, String redirectUri) {
        OAuthCode authCode = oauthCodeMapper.findByCodeAndClientKey(code, clientId);
        if (authCode == null || authCode.getStatus() != OAuthConfig.CodeStatus.VALID) {
            return null;
        }

        // 检查是否过期
        if (authCode.getExpireTime().isBefore(LocalDateTime.now())) {
            return null;
        }

        // 检查重定向URI
        if (!authCode.getRedirectUri().equals(redirectUri)) {
            return null;
        }

        return authCode;
    }

    /**
     * 清理过期的授权码
     *
     * @return 清理的记录数
     */
    @Override
    public int cleanExpiredCodes() {
        return oauthCodeMapper.cleanExpiredCodes();
    }

    /**
     * 注册OAuth客户端
     *
     * @param client 客户端信息
     * @return 注册结果
     */
    @Override
    public Result<OAuthClient> registerClient(OAuthClient client) {
        try {
            // 验证必要参数
            if (StrUtil.isBlank(client.getClientKey())) {
                SecureLogger.warn("客户端注册失败: clientId为空");
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            // 检查客户端标识符是否已存在
            if (oauthClientMapper.findByClientKey(client.getClientKey()) != null) {
                SecureLogger.warn("客户端注册失败: clientId已存在: {}", client.getClientKey());
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            // 处理客户端密钥
            String rawSecret;
            if (StrUtil.isBlank(client.getClientSecret())) {
                // 生成新的客户端密钥
                rawSecret = oauthClientSecretService.generateClientSecret();
                SecureLogger.info("为客户端生成新密钥: clientId={}, secretLength={}", 
                        client.getClientKey(), rawSecret.length());
            } else {
                // 使用提供的密钥，但需要验证强度
                rawSecret = client.getClientSecret();
                if (!oauthClientSecretService.validateSecretStrength(rawSecret)) {
                    SecureLogger.warn("客户端注册失败: 提供的密钥强度不足: clientId={}", client.getClientKey());
                    return Result.error(ResultEnum.PARAM_IS_INVALID, null);
                }
            }

            // 加密密钥并存储
            String encodedSecret = oauthClientSecretService.encodeClientSecret(rawSecret);
            client.setClientSecret(encodedSecret);

            // 设置默认值
            if (client.getStatus() == null) {
                client.setStatus(1);
            }
            if (client.getAutoApprove() == null) {
                client.setAutoApprove(0);
            }
            if (client.getAccessTokenValidity() == null) {
                client.setAccessTokenValidity(oauthConfig.getAccessTokenTimeout());
            }
            if (client.getRefreshTokenValidity() == null) {
                client.setRefreshTokenValidity(oauthConfig.getRefreshTokenTimeout());
            }
            if (StrUtil.isBlank(client.getScopes())) {
                client.setScopes(oauthConfig.getDefaultScope());
            }
            if (StrUtil.isBlank(client.getGrantTypes())) {
                client.setGrantTypes("authorization_code,refresh_token");
            }

            // 保存到数据库
            oauthClientMapper.insert(client);

            // 返回时不包含加密后的密钥，而是返回原始密钥供客户端使用
            OAuthClient responseClient = new OAuthClient();
            responseClient.setClientId(client.getClientId());
            responseClient.setClientKey(client.getClientKey());
            responseClient.setClientSecret(rawSecret); // 返回明文密钥给客户端保存
            responseClient.setClientName(client.getClientName());
            responseClient.setDescription(client.getDescription());
            responseClient.setRedirectUris(client.getRedirectUris());
            responseClient.setScopes(client.getScopes());
            responseClient.setGrantTypes(client.getGrantTypes());
            responseClient.setAccessTokenValidity(client.getAccessTokenValidity());
            responseClient.setRefreshTokenValidity(client.getRefreshTokenValidity());
            responseClient.setAutoApprove(client.getAutoApprove());
            responseClient.setStatus(client.getStatus());
            responseClient.setCreateTime(client.getCreateTime());

            SecureLogger.info("客户端注册成功: clientId={}, clientName={}, secretEncrypted={}", 
                    client.getClientKey(), client.getClientName(), 
                    oauthClientSecretService.isEncrypted(encodedSecret));

            return Result.success(responseClient);
        } catch (Exception e) {
            SecureLogger.error("客户端注册失败: clientId={}", 
                    client != null ? client.getClientKey() : "unknown", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 更新OAuth客户端
     *
     * @param client 客户端信息
     * @return 更新结果
     */
    @Override
    public Result<OAuthClient> updateClient(OAuthClient client) {
        try {
            oauthClientMapper.updateById(client);
            return Result.success(client);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 删除OAuth客户端
     *
     * @param clientId 客户端标识符
     * @return 删除结果
     */
    @Override
    public Result<Void> deleteClient(String clientId) {
        try {
            OAuthClient client = oauthClientMapper.findByClientKey(clientId);
            if (client != null) {
                oauthClientMapper.deleteById(client.getClientId());
            }
            return Result.success(null);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 获取客户端信息
     *
     * @param clientId 客户端标识符
     * @return 客户端信息
     */
    @Override
    public Result<OAuthClient> getClient(String clientId) {
        try {
            OAuthClient client = oauthClientMapper.findByClientKey(clientId);
            if (client == null) {
                return Result.error(ResultEnum.RESULT_DATA_NONE, null);
            }
            return Result.success(client);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 生成访问令牌
     * 创建UUID格式的访问令牌，并将令牌信息存储到Redis中
     * 令牌信息包含用户ID、客户端标识、授权范围和创建时间
     *
     * @param userId    用户ID（客户端凭证模式时可为null）
     * @param clientId 客户端标识符
     * @param scope     授权范围
     * @return 访问令牌字符串
     */
    private String generateAccessToken(Long userId, String clientId, String scope) {
        // 生成UUID格式的令牌
        String token = IdUtil.fastSimpleUUID();

        // 构建统一的令牌信息格式 - 符合OAuth2.0标准
        Map<String, String> tokenInfo = new HashMap<>();
        tokenInfo.put("token_type", "access_token");
        tokenInfo.put("token_value", token);
        tokenInfo.put("client_id", clientId);
        tokenInfo.put("scope", normalizeScope(scope));
        tokenInfo.put("issued_at", String.valueOf(System.currentTimeMillis()));
        tokenInfo.put("expires_in", String.valueOf(oauthConfig.getAccessTokenTimeout()));
        tokenInfo.put("expires_at", String.valueOf(System.currentTimeMillis() + (oauthConfig.getAccessTokenTimeout() * 1000L)));
        
        // 用户相关信息（仅当用户ID不为空时）
        if (userId != null) {
            tokenInfo.put("user_id", userId.toString());
            // 获取用户详细信息
            Account account = accountMapper.selectById(userId);
            if (account != null) {
                tokenInfo.put("username", account.getUsername());
                tokenInfo.put("user_role", account.getRole());
            }
        }

        // 使用配置的键前缀，统一存储格式
        String tokenKey = oauthConfig.getAccessTokenPrefix() + token;
        redisTemplate.opsForHash().putAll(tokenKey, tokenInfo);
        redisTemplate.expire(tokenKey, oauthConfig.getAccessTokenTimeout(), TimeUnit.SECONDS);

        SecureLogger.debug("生成访问令牌成功: clientId={}, userId={}, scope={}, expiresIn={}", 
                 clientId, userId, scope, oauthConfig.getAccessTokenTimeout());

        return token;
    }

    /**
     * 生成刷新令牌
     * 创建UUID格式的刷新令牌，用于获取新的访问令牌
     * 刷新令牌的有效期比访问令牌更长，通常为30天
     *
     * @param userId    用户ID
     * @param clientId 客户端标识符
     * @return 刷新令牌字符串
     */
    private String generateRefreshToken(Long userId, String clientId) {
        // 生成UUID格式的刷新令牌
        String token = IdUtil.fastSimpleUUID();

        // 构建统一的令牌信息格式 - 符合OAuth2.0标准
        Map<String, String> tokenInfo = new HashMap<>();
        tokenInfo.put("token_type", "refresh_token");
        tokenInfo.put("token_value", token);
        tokenInfo.put("client_id", clientId);
        tokenInfo.put("issued_at", String.valueOf(System.currentTimeMillis()));
        tokenInfo.put("expires_in", String.valueOf(oauthConfig.getRefreshTokenTimeout()));
        tokenInfo.put("expires_at", String.valueOf(System.currentTimeMillis() + (oauthConfig.getRefreshTokenTimeout() * 1000L)));
        
        // 用户相关信息（仅当用户ID不为空时）
        if (userId != null) {
            tokenInfo.put("user_id", userId.toString());
            // 获取用户详细信息
            Account account = accountMapper.selectById(userId);
            if (account != null) {
                tokenInfo.put("username", account.getUsername());
                tokenInfo.put("user_role", account.getRole());
            }
        }

        // 使用配置的键前缀
        String tokenKey = oauthConfig.getRefreshTokenPrefix() + token;
        redisTemplate.opsForHash().putAll(tokenKey, tokenInfo);
        redisTemplate.expire(tokenKey, oauthConfig.getRefreshTokenTimeout(), TimeUnit.SECONDS);

        SecureLogger.debug("生成刷新令牌成功: clientId={}, userId={}, expiresIn={}", 
                 clientId, userId, oauthConfig.getRefreshTokenTimeout());

        return token;
    }

    /**
     * 验证重定向URI是否有效
     * 检查重定向URI是否在客户端注册的URI列表中
     * 支持JSON数组格式和逗号分隔格式
     *
     * @param client      客户端信息
     * @param redirectUri 重定向URI
     * @return 是否有效
     */
    private boolean isValidRedirectUri(OAuthClient client, String redirectUri) {
        if (StrUtil.isBlank(client.getRedirectUris()) || StrUtil.isBlank(redirectUri)) {
            SecureLogger.warn("重定向URI验证失败: 配置或请求URI为空");
            return false;
        }

        try {
            // 支持JSON数组格式和逗号分隔格式
            List<String> validUris;
            String configuredUris = client.getRedirectUris().trim();
            
            if (configuredUris.startsWith("[") && configuredUris.endsWith("]")) {
                // JSON数组格式：["http://example.com/callback", "https://app.example.com/auth"]
                validUris = JSONUtil.toList(configuredUris, String.class);
            } else {
                // 逗号分隔格式：http://example.com/callback,https://app.example.com/auth
                validUris = Arrays.asList(configuredUris.split(","));
            }
            
            // 清理URI并验证
            for (String validUri : validUris) {
                String cleanUri = validUri.trim();
                if (redirectUri.equals(cleanUri)) {
                    return true;
                }
            }
            
            SecureLogger.warn("重定向URI不在允许列表中: requestUri={}, configuredUris={}", 
                    redirectUri, client.getRedirectUris());
            return false;
        } catch (Exception e) {
            SecureLogger.error("解析重定向URI配置失败: {}", client.getRedirectUris(), e);
            return false;
        }
    }
    
    /**
     * 验证授权范围是否有效
     * 检查请求的scope是否在客户端允许的范围内
     * 支持scope子集验证和空scope的默认处理
     *
     * @param client       客户端信息
     * @param requestScope 请求的授权范围
     * @return 是否有效
     */
    private boolean isValidScope(OAuthClient client, String requestScope) {
        // 如果请求的scope为空，使用默认scope（这是有效的）
        if (StrUtil.isBlank(requestScope)) {
            SecureLogger.debug("请求scope为空，将使用默认scope: {}", oauthConfig.getDefaultScope());
            return true;
        }
        
        // 如果客户端没有配置scope，只允许默认scope
        if (StrUtil.isBlank(client.getScopes())) {
            SecureLogger.warn("客户端未配置授权范围: clientId={}", client.getClientKey());
            return requestScope.equals(oauthConfig.getDefaultScope());
        }
        
        // 解析客户端配置的scope
        Set<String> clientScopes = parseScopes(client.getScopes());
        Set<String> requestScopes = parseScopes(requestScope);
        
        // 验证请求的所有scope都在客户端配置的范围内
        for (String scope : requestScopes) {
            if (!clientScopes.contains(scope)) {
                SecureLogger.warn("请求的scope不在客户端允许范围内: requestScope={}, clientScopes={}", 
                        scope, clientScopes);
                return false;
            }
        }
        
        SecureLogger.debug("scope验证通过: requestScopes={}, clientScopes={}", requestScopes, clientScopes);
        return true;
    }
    
    /**
     * 解析scope字符串为Set集合
     * 支持空格分隔和逗号分隔格式
     *
     * @param scopeString scope字符串
     * @return scope集合
     */
    private Set<String> parseScopes(String scopeString) {
        if (StrUtil.isBlank(scopeString)) {
            return Collections.emptySet();
        }
        
        Set<String> scopes = new HashSet<>();
        
        // 支持空格分隔（OAuth2.0标准）和逗号分隔
        String[] scopeArray;
        if (scopeString.contains(" ")) {
            scopeArray = scopeString.split("\\s+");
        } else if (scopeString.contains(",")) {
            scopeArray = scopeString.split(",");
        } else {
            scopeArray = new String[]{scopeString};
        }
        
        for (String scope : scopeArray) {
            String trimmedScope = scope.trim();
            if (StrUtil.isNotBlank(trimmedScope)) {
                scopes.add(trimmedScope);
            }
        }
        
        return scopes;
    }
    
    /**
     * 规范化scope字符串
     * 将scope集合转换为标准的空格分隔格式
     *
     * @param scopes scope集合
     * @return 规范化的scope字符串
     */
    private String normalizeScope(Set<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return oauthConfig.getDefaultScope();
        }
        return String.join(" ", scopes);
    }
    
    /**
     * 规范化scope字符串（基于字符串输入）
     * 将scope字符串转换为标准的空格分隔格式
     *
     * @param scopeString scope字符串
     * @return 规范化的scope字符串
     */
    private String normalizeScope(String scopeString) {
        if (StrUtil.isBlank(scopeString)) {
            return oauthConfig.getDefaultScope();
        }
        Set<String> scopes = parseScopes(scopeString);
        return normalizeScope(scopes);
    }

    /**
     * 生成符合OAuth2.0规范的错误响应
     * 根据RFC 6749标准格式化错误信息
     *
     * @param error           错误代码
     * @param errorDescription 错误描述
     * @param errorUri         错误详情URI（可选）
     * @return 标准错误响应
     */
    @SuppressWarnings("unchecked")
    private <T> Result<T> createOAuthError(String error, String errorDescription, String errorUri) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", error);
        errorResponse.put("error_description", errorDescription);
        if (StrUtil.isNotBlank(errorUri)) {
            errorResponse.put("error_uri", errorUri);
        }
        
        SecureLogger.warn("OAuth2.0错误: error={}, description={}", error, errorDescription);
        
        // 使用统一的错误响应格式
        return (Result<T>) Result.error("OAuth2.0 Error: " + error);
    }

    /**
     * 生成符合OAuth2.0规范的错误响应（无错误详情URI）
     *
     * @param error           错误代码
     * @param errorDescription 错误描述
     * @return 标准错误响应
     */
    private <T> Result<T> createOAuthError(String error, String errorDescription) {
        return createOAuthError(error, errorDescription, null);
    }

    /**
     * 验证授权码并原子性标记为已使用
     * 防止授权码重复使用的安全漏洞
     *
     * @param code        授权码
     * @param clientId   客户端标识符
     * @param redirectUri 重定向URI
     * @return 授权码记录，如果无效或已使用则返回null
     */
    private OAuthCode validateAndUseAuthorizationCode(String code, String clientId, String redirectUri) {
        // 先查找授权码
        OAuthCode authCode = oauthCodeMapper.findByCodeAndClientKey(code, clientId);
        if (authCode == null || authCode.getStatus() != OAuthConfig.CodeStatus.VALID) {
            SecureLogger.warn("授权码不存在或已失效: code={}, clientId={}, status={}", 
                    code, clientId, authCode != null ? authCode.getStatus() : "null");
            return null;
        }

        // 检查是否过期
        if (authCode.getExpireTime().isBefore(LocalDateTime.now())) {
            SecureLogger.warn("授权码已过期: code={}, expireTime={}", code, authCode.getExpireTime());
            return null;
        }

        // 检查重定向URI
        if (!authCode.getRedirectUri().equals(redirectUri)) {
            SecureLogger.warn("重定向URI不匹配: expected={}, actual={}", authCode.getRedirectUri(), redirectUri);
            return null;
        }

        // 原子性标记为已使用，防止并发使用
        int updateCount = oauthCodeMapper.markCodeAsUsed(code);
        if (updateCount == 0) {
            SecureLogger.warn("授权码可能已被使用: code={}", code);
            return null;
        }

        return authCode;
    }
}