package cn.flying.identity.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.config.OAuthConfig;
import cn.flying.identity.dto.Account;
import cn.flying.identity.dto.OAuthClient;
import cn.flying.identity.dto.OAuthCode;
import cn.flying.identity.exception.BusinessException;
import cn.flying.identity.mapper.AccountMapper;
import cn.flying.identity.mapper.OAuthClientMapper;
import cn.flying.identity.mapper.OAuthCodeMapper;
import cn.flying.identity.service.OAuthClientSecretService;
import cn.flying.identity.service.OAuthService;
import cn.flying.platformapi.constant.ResultEnum;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * OAuth2.0服务实现类
 * 实现SSO单点登录和第三方应用接入的具体逻辑
 */
@Slf4j
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
    private static final String ACCESS_TOKEN_HINT = "access_token";
    private static final String REFRESH_TOKEN_HINT = "refresh_token";

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
    public Map<String, Object> getAuthorizeInfo(String clientId, String redirectUri, String scope, String state) {
        try {
            OAuthClient client = oauthClientMapper.findByClientKey(clientId);
            if (client == null || client.getStatus() != 1) {
                throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "客户端不存在或已禁用");
            }

            if (!isValidRedirectUri(client, redirectUri)) {
                log.warn("无效的重定向URI: clientId={}, redirectUri={}", clientId, redirectUri);
                throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "重定向URI不合法");
            }

            if (oauthConfig.isRequireState() && StrUtil.isBlank(state)) {
                log.warn("缺少必需的状态参数: clientId={}", clientId);
                throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "缺少必需的state参数");
            }

            if (!isValidScope(client, scope)) {
                log.warn("无效的授权范围: clientId={}, requestScope={}", clientId, scope);
                throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "授权范围不合法");
            }

            if (!StpUtil.isLogin()) {
                throw new BusinessException(ResultEnum.USER_NOT_LOGGED_IN, "用户未登录");
            }

            Map<String, Object> info = new HashMap<>();
            info.put("clientName", client.getClientName());
            info.put("description", client.getDescription());
            info.put("scope", StrUtil.isNotBlank(scope) ? scope : oauthConfig.getDefaultScope());
            info.put("redirectUri", redirectUri);
            info.put("state", state);
            info.put("autoApprove", client.getAutoApprove() == 1);

            return info;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("获取授权页面信息失败", e);
            throw new BusinessException(ResultEnum.SYSTEM_ERROR, "获取授权信息失败");
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
    public String authorize(String clientId, String redirectUri, String scope, String state, boolean approved) {
        try {
            OAuthClient client = oauthClientMapper.findByClientKey(clientId);
            if (client == null || client.getStatus() != 1) {
                throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "客户端不存在或已禁用");
            }

            if (!isValidRedirectUri(client, redirectUri)) {
                log.warn("无效的重定向URI: clientId={}, redirectUri={}", clientId, redirectUri);
                throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "重定向URI不合法");
            }

            if (oauthConfig.isRequireState() && StrUtil.isBlank(state)) {
                log.warn("缺少必需的状态参数: clientId={}", clientId);
                throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "缺少必需的state参数");
            }

            if (!isValidScope(client, scope)) {
                log.warn("无效的授权范围: clientId={}, requestScope={}, clientScope={}",
                        clientId, scope, client.getScopes());
                throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "授权范围不合法");
            }

            if (!StpUtil.isLogin()) {
                throw new BusinessException(ResultEnum.USER_NOT_LOGGED_IN, "用户未登录");
            }

            if (!approved) {
                String errorUrl = redirectUri + "?error=access_denied";
                if (StrUtil.isNotBlank(state)) {
                    errorUrl += "&state=" + state;
                }
                return errorUrl;
            }

            Long userId = StpUtil.getLoginIdAsLong();
            OAuthCode authCode = generateAuthorizationCode(clientId, userId, redirectUri, scope, state);

            String redirectUrl = redirectUri + "?code=" + authCode.getCode();
            if (StrUtil.isNotBlank(state)) {
                redirectUrl += "&state=" + state;
            }

            return redirectUrl;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("处理用户授权失败", e);
            throw new BusinessException(ResultEnum.SYSTEM_ERROR, "用户授权失败");
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
    public Map<String, Object> getAccessToken(String grantType, String code, String redirectUri,
                                              String clientId, String clientSecret) {
        try {
            if (StrUtil.isBlank(grantType)) {
                throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "grant_type不能为空");
            }
            if (StrUtil.isBlank(code)) {
                throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "授权码不能为空");
            }
            if (StrUtil.isBlank(redirectUri)) {
                throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "redirect_uri不能为空");
            }
            if (StrUtil.isBlank(clientId) || StrUtil.isBlank(clientSecret)) {
                throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "客户端凭证不能为空");
            }

            if (!"authorization_code".equals(grantType)) {
                throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "暂不支持的授权类型: " + grantType);
            }

            OAuthClient client = validateClient(clientId, clientSecret);
            if (client == null || client.getStatus() != 1) {
                throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "客户端认证失败或已禁用");
            }

            OAuthCode authCode = validateAndUseAuthorizationCode(code, clientId, redirectUri);
            if (authCode == null) {
                throw new BusinessException(ResultEnum.OAUTH_CODE_INVALID, "授权码无效或已过期");
            }

            String accessToken = generateAccessToken(authCode.getUserId(), clientId, authCode.getScope());
            String refreshToken = generateRefreshToken(authCode.getUserId(), clientId, accessToken);

            Map<String, Object> tokenInfo = new HashMap<>();
            tokenInfo.put("access_token", accessToken);
            tokenInfo.put("token_type", "Bearer");
            tokenInfo.put("expires_in", client.getAccessTokenValidity());
            tokenInfo.put("refresh_token", refreshToken);
            tokenInfo.put("scope", authCode.getScope());

            return tokenInfo;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("获取访问令牌失败", e);
            throw new BusinessException(ResultEnum.SYSTEM_ERROR, "获取访问令牌失败");
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
    public Map<String, Object> refreshAccessToken(String grantType, String refreshToken,
                                                  String clientId, String clientSecret) {
        try {
            if (StrUtil.isBlank(grantType)) {
                throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "grant_type不能为空");
            }
            if (StrUtil.isBlank(refreshToken)) {
                throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "refresh_token不能为空");
            }
            if (StrUtil.isBlank(clientId) || StrUtil.isBlank(clientSecret)) {
                throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "客户端凭证不能为空");
            }

            if (!"refresh_token".equals(grantType)) {
                throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "暂不支持的授权类型: " + grantType);
            }

            OAuthClient client = validateClient(clientId, clientSecret);
            if (client == null || client.getStatus() != 1) {
                throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "客户端认证失败或已禁用");
            }

            String tokenKey = oauthConfig.getRefreshTokenPrefix() + refreshToken;
            Map<Object, Object> tokenData = redisTemplate.opsForHash().entries(tokenKey);
            if (tokenData.isEmpty()) {
                log.warn("刷新令牌不存在或已过期: {}", refreshToken);
                throw new BusinessException(ResultEnum.OAUTH_TOKEN_INVALID, "刷新令牌不存在或已过期");
            }

            String userIdStr = (String) tokenData.get("user_id");
            String tokenClientId = (String) tokenData.get("client_id");
            if (!clientId.equals(tokenClientId)) {
                log.warn("刷新令牌客户端不匹配: expected={}, actual={}", clientId, tokenClientId);
                throw new BusinessException(ResultEnum.OAUTH_TOKEN_INVALID, "刷新令牌与客户端不匹配");
            }

            Long userId = null;
            if (StrUtil.isNotBlank(userIdStr)) {
                userId = Long.valueOf(userIdStr);
                Account account = accountMapper.selectById(userId);
                if (account == null || account.getDeleted() == 1) {
                    log.warn("用户不存在或已删除: {}", userId);
                    throw new BusinessException(ResultEnum.USER_NOT_EXIST, "刷新令牌关联的用户不存在");
                }
            }

            String scope = (String) tokenData.get("scope");
            if (StrUtil.isBlank(scope)) {
                scope = oauthConfig.getDefaultScope();
            }

            String newAccessToken = generateAccessToken(userId, clientId, scope);
            String newRefreshToken = generateRefreshToken(userId, clientId, newAccessToken);

            removeRefreshToken(refreshToken, tokenData, true);

            Map<String, Object> response = new HashMap<>();
            response.put("access_token", newAccessToken);
            response.put("token_type", "Bearer");
            response.put("expires_in", oauthConfig.getAccessTokenTimeout());
            response.put("refresh_token", newRefreshToken);
            response.put("refresh_token_expires_in", oauthConfig.getRefreshTokenTimeout());
            response.put("scope", scope);

            return response;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("刷新访问令牌失败", e);
            throw new BusinessException(ResultEnum.SYSTEM_ERROR, "刷新访问令牌失败");
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
    public Map<String, Object> getClientCredentialsToken(String grantType, String scope,
                                                         String clientId, String clientSecret) {
        try {
            if (!"client_credentials".equals(grantType)) {
                throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "仅支持 client_credentials 授权类型");
            }

            OAuthClient client = validateClient(clientId, clientSecret);
            if (client == null || client.getStatus() != 1) {
                throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "客户端认证失败或已禁用");
            }

            String effectiveScope = StrUtil.isNotBlank(scope) ? scope : oauthConfig.getDefaultScope();
            String accessToken = generateAccessToken(null, clientId, effectiveScope);

            Map<String, Object> tokenInfo = new HashMap<>();
            tokenInfo.put("access_token", accessToken);
            tokenInfo.put("token_type", "Bearer");
            tokenInfo.put("expires_in", client.getAccessTokenValidity());
            tokenInfo.put("scope", effectiveScope);

            return tokenInfo;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("客户端凭证模式获取访问令牌失败", e);
            throw new BusinessException(ResultEnum.SYSTEM_ERROR, "客户端凭证模式获取访问令牌失败");
        }
    }

    /**
     * 获取用户信息（通过访问令牌）
     *
     * @param accessToken 访问令牌
     * @return 用户信息
     */
    @Override
    public Map<String, Object> getUserInfo(String accessToken) {
        try {
            String tokenKey = oauthConfig.getAccessTokenPrefix() + accessToken;
            Map<Object, Object> tokenData = redisTemplate.opsForHash().entries(tokenKey);
            if (tokenData.isEmpty()) {
                throw new BusinessException(ResultEnum.OAUTH_TOKEN_INVALID, "访问令牌无效或已过期");
            }

            String userIdStr = (String) tokenData.get("user_id");
            if (StrUtil.isBlank(userIdStr)) {
                throw new BusinessException(ResultEnum.OAUTH_TOKEN_INVALID, "访问令牌缺少用户信息");
            }

            Long userId = Long.valueOf(userIdStr);
            Account account = accountMapper.selectById(userId);
            if (account == null) {
                throw new BusinessException(ResultEnum.USER_NOT_EXIST, "用户不存在");
            }

            Map<String, Object> userInfo = new HashMap<>();
            userInfo.put("id", account.getId());
            userInfo.put("username", account.getUsername());
            userInfo.put("email", account.getEmail());
            userInfo.put("role", account.getRole());
            userInfo.put("avatar", account.getAvatar());
            userInfo.put("registerTime", account.getRegisterTime());

            return userInfo;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("获取用户信息失败", e);
            throw new BusinessException(ResultEnum.SYSTEM_ERROR, "获取OAuth用户信息失败");
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
    public void revokeToken(String token, String tokenTypeHint, String clientId, String clientSecret) {
        try {
            OAuthClient client = validateClient(clientId, clientSecret);
            if (client == null || client.getStatus() != 1) {
                throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "客户端认证失败或已禁用");
            }

            boolean deleted = false;
            if (tokenTypeHint == null || ACCESS_TOKEN_HINT.equalsIgnoreCase(tokenTypeHint)) {
                deleted = removeAccessToken(token, null, true) || deleted;
            }

            if (tokenTypeHint == null || REFRESH_TOKEN_HINT.equalsIgnoreCase(tokenTypeHint)) {
                deleted = removeRefreshToken(token, null, true) || deleted;
            }

            if (!deleted) {
                log.warn("令牌撤销失败，令牌可能不存在: token={}, hint={}", token, tokenTypeHint);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("撤销令牌失败", e);
            throw new BusinessException(ResultEnum.SYSTEM_ERROR, "撤销令牌失败");
        }
    }

    @Override
    public void revokeTokensByUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "用户ID不能为空");
        }
        try {
            Set<String> accessTokens = Optional.ofNullable(
                    redisTemplate.opsForSet().members(buildUserAccessIndexKey(userId)))
                    .orElse(Collections.emptySet());
            Set<String> refreshTokens = Optional.ofNullable(
                    redisTemplate.opsForSet().members(buildUserRefreshIndexKey(userId)))
                    .orElse(Collections.emptySet());

            for (String accessToken : accessTokens) {
                removeAccessToken(accessToken, null, true);
            }
            for (String refreshToken : refreshTokens) {
                removeRefreshToken(refreshToken, null, true);
            }

            redisTemplate.delete(buildUserAccessIndexKey(userId));
            redisTemplate.delete(buildUserRefreshIndexKey(userId));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("按用户批量撤销令牌失败: userId={}", userId, e);
            throw new BusinessException(ResultEnum.SYSTEM_ERROR, "按用户撤销令牌失败");
        }
    }

    @Override
    public void revokeTokensByClient(String clientKey) {
        if (StrUtil.isBlank(clientKey)) {
            throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "客户端标识不能为空");
        }
        try {
            Set<String> accessTokens = Optional.ofNullable(
                    redisTemplate.opsForSet().members(buildClientAccessIndexKey(clientKey)))
                    .orElse(Collections.emptySet());
            Set<String> refreshTokens = Optional.ofNullable(
                    redisTemplate.opsForSet().members(buildClientRefreshIndexKey(clientKey)))
                    .orElse(Collections.emptySet());

            for (String accessToken : accessTokens) {
                removeAccessToken(accessToken, null, true);
            }
            for (String refreshToken : refreshTokens) {
                removeRefreshToken(refreshToken, null, true);
            }

            redisTemplate.delete(buildClientAccessIndexKey(clientKey));
            redisTemplate.delete(buildClientRefreshIndexKey(clientKey));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("按客户端批量撤销令牌失败: clientKey={}", clientKey, e);
            throw new BusinessException(ResultEnum.SYSTEM_ERROR, "按客户端撤销令牌失败");
        }
    }

    @Override
    public void revokeAllTokens() {
        try {
            Set<String> refreshKeys = scanKeys(oauthConfig.getRefreshTokenPrefix() + "*");
            for (String refreshKey : refreshKeys) {
                String token = extractToken(refreshKey, oauthConfig.getRefreshTokenPrefix());
                if (StrUtil.isBlank(token)) {
                    continue;
                }
                removeRefreshToken(token, null, true);
            }

            Set<String> accessKeys = scanKeys(oauthConfig.getAccessTokenPrefix() + "*");
            for (String accessKey : accessKeys) {
                String token = extractToken(accessKey, oauthConfig.getAccessTokenPrefix());
                if (StrUtil.isBlank(token)) {
                    continue;
                }
                removeAccessToken(token, null, true);
            }

            // 清理索引集合
            Set<String> userIndexKeys = scanKeys(oauthConfig.getUserTokenPrefix() + "*");
            for (String key : userIndexKeys) {
                redisTemplate.delete(key);
            }
            Set<String> clientIndexKeys = scanKeys(oauthConfig.getClientTokenPrefix() + "*");
            for (String key : clientIndexKeys) {
                redisTemplate.delete(key);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("批量撤销所有 OAuth 令牌失败", e);
            throw new BusinessException(ResultEnum.SYSTEM_ERROR, "批量撤销所有令牌失败");
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
            List<String> providers = oauthConfig.getThirdPartyProviders();
            if (providers.isEmpty()) {
                return;
            }
            boolean clearAccess = tokenTypeHint == null || ACCESS_TOKEN_HINT.equalsIgnoreCase(tokenTypeHint);
            boolean clearRefresh = tokenTypeHint == null || REFRESH_TOKEN_HINT.equalsIgnoreCase(tokenTypeHint);

            for (String provider : providers) {
                if (StrUtil.isBlank(provider)) {
                    continue;
                }
                String normalized = provider.trim().toLowerCase(java.util.Locale.ROOT);
                if (clearAccess) {
                    String accessKey = THIRD_PARTY_TOKEN_PREFIX + normalized + ":access:" + token;
                    redisTemplate.delete(accessKey);
                }
                if (clearRefresh) {
                    String refreshKey = THIRD_PARTY_TOKEN_PREFIX + normalized + ":refresh:" + token;
                    redisTemplate.delete(refreshKey);
                }
            }
        } catch (Exception e) {
            log.warn("清理第三方映射时发生异常: token={}, hint={}", token, tokenTypeHint, e);
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
            log.warn("客户端验证参数为空: clientId={}, clientSecret={}", 
                    StrUtil.isBlank(clientId) ? "blank" : "present",
                    StrUtil.isBlank(clientSecret) ? "blank" : "present");
            return null;
        }
        
        try {
            // 先根据clientId查找客户端
            OAuthClient client = oauthClientMapper.findByClientKey(clientId);
            if (client == null) {
                log.warn("客户端不存在: clientId={}", clientId);
                return null;
            }
            
            if (client.getStatus() != 1) {
                log.warn("客户端已禁用: clientId={}, status={}", clientId, client.getStatus());
                return null;
            }
            
            // 使用专门的密钥服务验证客户端密钥
            String storedSecret = client.getClientSecret();
            if (!oauthClientSecretService.matches(clientSecret, storedSecret)) {
                log.warn("客户端密钥验证失败: clientId={}, secretIsEncrypted={}", 
                        clientId, oauthClientSecretService.isEncrypted(storedSecret));
                return null;
            }

            try {
                if (oauthConfig.isUseBcrypt() && !oauthClientSecretService.isEncrypted(storedSecret)) {
                    String upgradedSecret = oauthClientSecretService.encodeClientSecret(clientSecret);
                    OAuthClient updateEntity = new OAuthClient();
                    updateEntity.setClientId(client.getClientId());
                    updateEntity.setClientSecret(upgradedSecret);
                    oauthClientMapper.updateById(updateEntity);
                    client.setClientSecret(upgradedSecret);
                    log.info("客户端密钥已自动升级为BCrypt: clientId={}", clientId);
                }
            } catch (Exception upgradeEx) {
                log.warn("客户端密钥升级失败，将保持原状态: clientId={}", clientId, upgradeEx);
            }
            
            if (log.isDebugEnabled()) {
                log.debug("客户端验证成功: clientId={}, clientName={}", clientId, client.getClientName());
            }
            return client;
        } catch (Exception e) {
            log.error("客户端验证过程中发生异常: clientId={}", clientId, e);
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
    public OAuthClient registerClient(OAuthClient client) {
        try {
            if (client == null || StrUtil.isBlank(client.getClientKey())) {
                log.warn("客户端注册失败: clientId为空");
                throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "客户端标识不能为空");
            }

            if (oauthClientMapper.findByClientKey(client.getClientKey()) != null) {
                log.warn("客户端注册失败: clientId已存在: {}", client.getClientKey());
                throw new BusinessException(ResultEnum.DATA_ALREADY_EXISTED, "客户端已存在");
            }

            String rawSecret;
            if (StrUtil.isBlank(client.getClientSecret())) {
                rawSecret = oauthClientSecretService.generateClientSecret();
                log.info("为客户端生成新密钥: clientId={}, secretLength={}", 
                        client.getClientKey(), rawSecret.length());
            } else {
                rawSecret = client.getClientSecret();
                if (!oauthClientSecretService.validateSecretStrength(rawSecret)) {
                    log.warn("客户端注册失败: 密钥强度不足: clientId={}", client.getClientKey());
                    throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "客户端密钥强度不足");
                }
            }

            String encodedSecret = oauthClientSecretService.encodeClientSecret(rawSecret);
            client.setClientSecret(encodedSecret);

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

            oauthClientMapper.insert(client);

            OAuthClient responseClient = new OAuthClient();
            responseClient.setClientId(client.getClientId());
            responseClient.setClientKey(client.getClientKey());
            responseClient.setClientSecret(rawSecret);
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

            log.info("客户端注册成功: clientId={}, clientName={}",
                    client.getClientKey(), client.getClientName());

            return responseClient;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("客户端注册失败: clientId={}",
                    client != null ? client.getClientKey() : "unknown", e);
            throw new BusinessException(ResultEnum.SYSTEM_ERROR, "客户端注册失败");
        }
    }

    /**
     * 更新OAuth客户端
     *
     * @param client 客户端信息
     * @return 更新结果
     */
    @Override
    public OAuthClient updateClient(OAuthClient client) {
        try {
            if (client == null || StrUtil.isBlank(client.getClientKey())) {
                throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "客户端信息不能为空");
            }
            oauthClientMapper.updateById(client);
            return client;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("更新客户端失败: clientId={}", client != null ? client.getClientKey() : "unknown", e);
            throw new BusinessException(ResultEnum.SYSTEM_ERROR, "更新客户端失败");
        }
    }

    /**
     * 删除OAuth客户端
     *
     * @param clientId 客户端标识符
     * @return 删除结果
     */
    @Override
    public void deleteClient(String clientId) {
        try {
            OAuthClient client = oauthClientMapper.findByClientKey(clientId);
            if (client != null) {
                oauthClientMapper.deleteById(client.getClientId());
            }
        } catch (Exception e) {
            log.error("删除客户端失败: clientId={}", clientId, e);
            throw new BusinessException(ResultEnum.SYSTEM_ERROR, "删除客户端失败");
        }
    }

    /**
     * 获取客户端信息
     *
     * @param clientId 客户端标识符
     * @return 客户端信息
     */
    @Override
    public OAuthClient getClient(String clientId) {
        try {
            OAuthClient client = oauthClientMapper.findByClientKey(clientId);
            if (client == null) {
                throw new BusinessException(ResultEnum.RESULT_DATA_NONE, "客户端不存在");
            }
            return client;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("获取客户端信息失败: clientId={}", clientId, e);
            throw new BusinessException(ResultEnum.SYSTEM_ERROR, "获取客户端信息失败");
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
        recordAccessTokenIndexes(userId, clientId, token);

        if (log.isDebugEnabled()) {
            log.debug("生成访问令牌成功: clientId={}, userId={}, scope={}, expiresIn={}",
                    clientId, userId, scope, oauthConfig.getAccessTokenTimeout());
        }

        return token;
    }

    private void recordAccessTokenIndexes(Long userId, String clientId, String token) {
        if (StrUtil.isBlank(token)) {
            return;
        }
        long ttl = getIndexTtlSeconds();
        if (userId != null) {
            String key = buildUserAccessIndexKey(userId);
            redisTemplate.opsForSet().add(key, token);
            redisTemplate.expire(key, ttl, TimeUnit.SECONDS);
        }
        if (StrUtil.isNotBlank(clientId)) {
            String key = buildClientAccessIndexKey(clientId);
            redisTemplate.opsForSet().add(key, token);
            redisTemplate.expire(key, ttl, TimeUnit.SECONDS);
        }
    }

    private void recordRefreshTokenIndexes(Long userId, String clientId, String token) {
        if (StrUtil.isBlank(token)) {
            return;
        }
        long ttl = getIndexTtlSeconds();
        if (userId != null) {
            String key = buildUserRefreshIndexKey(userId);
            redisTemplate.opsForSet().add(key, token);
            redisTemplate.expire(key, ttl, TimeUnit.SECONDS);
        }
        if (StrUtil.isNotBlank(clientId)) {
            String key = buildClientRefreshIndexKey(clientId);
            redisTemplate.opsForSet().add(key, token);
            redisTemplate.expire(key, ttl, TimeUnit.SECONDS);
        }
    }

    private long getIndexTtlSeconds() {
        return Math.max(oauthConfig.getAccessTokenTimeout(), oauthConfig.getRefreshTokenTimeout());
    }

    private String buildUserAccessIndexKey(Long userId) {
        return oauthConfig.getUserTokenPrefix() + userId + ":access";
    }

    private String buildUserRefreshIndexKey(Long userId) {
        return oauthConfig.getUserTokenPrefix() + userId + ":refresh";
    }

    private String buildClientAccessIndexKey(String clientId) {
        return oauthConfig.getClientTokenPrefix() + clientId + ":access";
    }

    private String buildClientRefreshIndexKey(String clientId) {
        return oauthConfig.getClientTokenPrefix() + clientId + ":refresh";
    }

    private boolean removeAccessToken(String token, Map<Object, Object> tokenData, boolean clearThirdParty) {
        if (StrUtil.isBlank(token)) {
            return false;
        }
        String tokenKey = oauthConfig.getAccessTokenPrefix() + token;
        Map<Object, Object> data = tokenData != null ? tokenData : redisTemplate.opsForHash().entries(tokenKey);
        String userIdStr = data != null ? (String) data.get("user_id") : null;
        String clientId = data != null ? (String) data.get("client_id") : null;
        boolean existed = data != null && !data.isEmpty();

        boolean removed = Boolean.TRUE.equals(redisTemplate.delete(tokenKey));
        removeTokenFromIndexes(token, userIdStr, clientId, true);

        if (clearThirdParty) {
            clearThirdPartyMappings(token, ACCESS_TOKEN_HINT);
        }
        return existed || removed;
    }

    private boolean removeRefreshToken(String token, Map<Object, Object> tokenData, boolean clearThirdParty) {
        if (StrUtil.isBlank(token)) {
            return false;
        }
        String tokenKey = oauthConfig.getRefreshTokenPrefix() + token;
        Map<Object, Object> data = tokenData != null ? tokenData : redisTemplate.opsForHash().entries(tokenKey);
        String userIdStr = data != null ? (String) data.get("user_id") : null;
        String clientId = data != null ? (String) data.get("client_id") : null;
        String linkedAccessToken = data != null ? (String) data.get("access_token") : null;
        boolean existed = data != null && !data.isEmpty();

        boolean removed = Boolean.TRUE.equals(redisTemplate.delete(tokenKey));
        removeTokenFromIndexes(token, userIdStr, clientId, false);

        if (clearThirdParty) {
            clearThirdPartyMappings(token, REFRESH_TOKEN_HINT);
        }
        if (StrUtil.isNotBlank(linkedAccessToken)) {
            removeAccessToken(linkedAccessToken, null, clearThirdParty);
        }
        return existed || removed;
    }

    private void removeTokenFromIndexes(String token, String userIdStr, String clientId, boolean accessToken) {
        if (StrUtil.isNotBlank(userIdStr)) {
            try {
                Long userId = Long.valueOf(userIdStr);
                String key = accessToken ? buildUserAccessIndexKey(userId) : buildUserRefreshIndexKey(userId);
                redisTemplate.opsForSet().remove(key, token);
            } catch (NumberFormatException ex) {
                log.warn("无法解析用户ID，跳过索引清理: userId={}", userIdStr, ex);
            }
        }
        if (StrUtil.isNotBlank(clientId)) {
            String key = accessToken ? buildClientAccessIndexKey(clientId) : buildClientRefreshIndexKey(clientId);
            redisTemplate.opsForSet().remove(key, token);
        }
    }

    private String extractToken(String redisKey, String prefix) {
        if (redisKey == null || prefix == null || !redisKey.startsWith(prefix)) {
            return redisKey;
        }
        return redisKey.substring(prefix.length());
    }

    private Set<String> scanKeys(String pattern) {
        Set<String> keys = new HashSet<>();
        try {
            redisTemplate.execute((RedisCallback<Void>) connection -> {
                try (Cursor<byte[]> cursor = connection.scan(
                        ScanOptions.scanOptions()
                                .match(pattern)
                                .count(200)
                                .build())) {
                    while (cursor.hasNext()) {
                        keys.add(new String(cursor.next(), java.nio.charset.StandardCharsets.UTF_8));
                    }
                } catch (Exception e) {
                    log.error("扫描Redis键失败: pattern={}", pattern, e);
                }
                return null;
            });
        } catch (Exception e) {
            log.error("执行Redis SCAN命令失败: pattern={}", pattern, e);
        }
        return keys;
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
    private String generateRefreshToken(Long userId, String clientId, String associatedAccessToken) {
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
        if (StrUtil.isNotBlank(associatedAccessToken)) {
            tokenInfo.put("access_token", associatedAccessToken);
        }
        
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
        recordRefreshTokenIndexes(userId, clientId, token);

        if (log.isDebugEnabled()) {
            log.debug("生成刷新令牌成功: clientId={}, userId={}, expiresIn={}",
                    clientId, userId, oauthConfig.getRefreshTokenTimeout());
        }

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
            log.warn("重定向URI验证失败: 配置或请求URI为空");
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
            
            log.warn("重定向URI不在允许列表中: requestUri={}, configuredUris={}", 
                    redirectUri, client.getRedirectUris());
            return false;
        } catch (Exception e) {
            log.error("解析重定向URI配置失败: {}", client.getRedirectUris(), e);
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
        if (log.isDebugEnabled()) {
            log.debug("请求scope为空，将使用默认scope: {}", oauthConfig.getDefaultScope());
        }
            return true;
        }
        
        // 如果客户端没有配置scope，只允许默认scope
        if (StrUtil.isBlank(client.getScopes())) {
            log.warn("客户端未配置授权范围: clientId={}", client.getClientKey());
            return requestScope.equals(oauthConfig.getDefaultScope());
        }
        
        // 解析客户端配置的scope
        Set<String> clientScopes = parseScopes(client.getScopes());
        Set<String> requestScopes = parseScopes(requestScope);
        
        // 验证请求的所有scope都在客户端配置的范围内
        for (String scope : requestScopes) {
            if (!clientScopes.contains(scope)) {
                log.warn("请求的scope不在客户端允许范围内: requestScope={}, clientScopes={}", 
                        scope, clientScopes);
                return false;
            }
        }
        
        if (log.isDebugEnabled()) {
            log.debug("scope验证通过: requestScopes={}, clientScopes={}", requestScopes, clientScopes);
        }
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
            log.warn("授权码不存在或已失效: code={}, clientId={}, status={}", 
                    code, clientId, authCode != null ? authCode.getStatus() : "null");
            return null;
        }

        // 检查是否过期
        if (authCode.getExpireTime().isBefore(LocalDateTime.now())) {
            log.warn("授权码已过期: code={}, expireTime={}", code, authCode.getExpireTime());
            return null;
        }

        // 检查重定向URI
        if (!authCode.getRedirectUri().equals(redirectUri)) {
            log.warn("重定向URI不匹配: expected={}, actual={}", authCode.getRedirectUri(), redirectUri);
            return null;
        }

        // 原子性标记为已使用，防止并发使用
        int updateCount = oauthCodeMapper.markCodeAsUsed(code);
        if (updateCount == 0) {
            log.warn("授权码可能已被使用: code={}", code);
            return null;
        }

        return authCode;
    }
}
