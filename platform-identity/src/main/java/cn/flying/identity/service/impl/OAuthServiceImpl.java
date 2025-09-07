package cn.flying.identity.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.dto.Account;
import cn.flying.identity.dto.OAuthClient;
import cn.flying.identity.dto.OAuthCode;
import cn.flying.identity.mapper.AccountMapper;
import cn.flying.identity.mapper.OAuthClientMapper;
import cn.flying.identity.mapper.OAuthCodeMapper;
import cn.flying.identity.service.OAuthService;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OAuth2.0服务实现类
 * 实现SSO单点登录和第三方应用接入的具体逻辑
 */
@Service
public class OAuthServiceImpl implements OAuthService {

    @Resource
    private OAuthClientMapper oauthClientMapper;

    @Resource
    private OAuthCodeMapper oauthCodeMapper;

    @Resource
    private AccountMapper accountMapper;

    @Resource
    private StringRedisTemplate redisTemplate;

    /**
     * 获取授权页面信息
     *
     * @param clientKey   客户端标识符
     * @param redirectUri 重定向URI
     * @param scope       授权范围
     * @param state       状态参数
     * @return 授权页面信息
     */
    @Override
    public Result<Map<String, Object>> getAuthorizeInfo(String clientKey, String redirectUri, String scope, String state) {
        try {
            // 验证客户端
            OAuthClient client = oauthClientMapper.findByClientKey(clientKey);
            if (client == null || client.getStatus() != 1) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            // 验证重定向URI
            if (isValidRedirectUri(client, redirectUri)) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            // 检查用户登录状态
            if (!StpUtil.isLogin()) {
                return Result.error(ResultEnum.USER_NOT_LOGGED_IN, null);
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
     * @param clientKey   客户端标识符
     * @param redirectUri 重定向URI
     * @param scope       授权范围
     * @param state       状态参数
     * @param approved    是否同意授权
     * @return 授权结果（包含授权码或错误信息）
     */
    @Override
    public Result<String> authorize(String clientKey, String redirectUri, String scope, String state, boolean approved) {
        try {
            // 验证客户端
            OAuthClient client = oauthClientMapper.findByClientKey(clientKey);
            if (client == null || client.getStatus() != 1) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            // 验证重定向URI
            if (isValidRedirectUri(client, redirectUri)) {
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
            OAuthCode authCode = generateAuthorizationCode(clientKey, userId, redirectUri, scope, state);

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
     * @param clientKey    客户端标识符
     * @param clientSecret 客户端密钥
     * @return 访问令牌信息
     */
    @Override
    public Result<Map<String, Object>> getAccessToken(String grantType, String code, String redirectUri,
                                                      String clientKey, String clientSecret) {
        try {
            // 验证授权类型
            if (!"authorization_code".equals(grantType)) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            // 验证客户端
            OAuthClient client = validateClient(clientKey, clientSecret);
            if (client == null) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            // 验证授权码
            OAuthCode authCode = validateAuthorizationCode(code, clientKey, redirectUri);
            if (authCode == null) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            // 标记授权码为已使用
            oauthCodeMapper.markCodeAsUsed(code);

            // 生成访问令牌
            String accessToken = generateAccessToken(authCode.getUserId(), clientKey, authCode.getScope());
            String refreshToken = generateRefreshToken(authCode.getUserId(), clientKey);

            // 构建响应
            Map<String, Object> tokenInfo = new HashMap<>();
            tokenInfo.put("access_token", accessToken);
            tokenInfo.put("token_type", "Bearer");
            tokenInfo.put("expires_in", client.getAccessTokenValidity());
            tokenInfo.put("refresh_token", refreshToken);
            tokenInfo.put("scope", authCode.getScope());

            return Result.success(tokenInfo);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 刷新访问令牌
     *
     * @param grantType    授权类型
     * @param refreshToken 刷新令牌
     * @param clientKey    客户端标识符
     * @param clientSecret 客户端密钥
     * @return 新的访问令牌信息
     */
    @Override
    public Result<Map<String, Object>> refreshAccessToken(String grantType, String refreshToken,
                                                          String clientKey, String clientSecret) {
        try {
            // 验证授权类型
            if (!"refresh_token".equals(grantType)) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            // 验证客户端
            OAuthClient client = validateClient(clientKey, clientSecret);
            if (client == null) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            // 验证刷新令牌
            String tokenKey = "oauth:refresh_token:" + refreshToken;
            String tokenData = redisTemplate.opsForValue().get(tokenKey);
            if (StrUtil.isBlank(tokenData)) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            Map<String, Object> tokenInfo = JSONUtil.toBean(tokenData, Map.class);
            Long userId = Long.valueOf(tokenInfo.get("userId").toString());
            String scope = (String) tokenInfo.get("scope");

            // 生成新的访问令牌
            String newAccessToken = generateAccessToken(userId, clientKey, scope);
            String newRefreshToken = generateRefreshToken(userId, clientKey);

            // 删除旧的刷新令牌
            redisTemplate.delete(tokenKey);

            // 构建响应
            Map<String, Object> response = new HashMap<>();
            response.put("access_token", newAccessToken);
            response.put("token_type", "Bearer");
            response.put("expires_in", client.getAccessTokenValidity());
            response.put("refresh_token", newRefreshToken);
            response.put("scope", scope);

            return Result.success(response);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 客户端凭证模式获取访问令牌
     *
     * @param grantType    授权类型
     * @param scope        授权范围
     * @param clientKey    客户端标识符
     * @param clientSecret 客户端密钥
     * @return 访问令牌信息
     */
    @Override
    public Result<Map<String, Object>> getClientCredentialsToken(String grantType, String scope,
                                                                 String clientKey, String clientSecret) {
        try {
            // 验证授权类型
            if (!"client_credentials".equals(grantType)) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            // 验证客户端
            OAuthClient client = validateClient(clientKey, clientSecret);
            if (client == null) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            // 生成访问令牌（客户端凭证模式不需要用户ID）
            String accessToken = generateAccessToken(null, clientKey, scope);

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
            // 验证访问令牌
            String tokenKey = "oauth:access_token:" + accessToken;
            String tokenData = redisTemplate.opsForValue().get(tokenKey);
            if (StrUtil.isBlank(tokenData)) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            Map<String, Object> tokenInfo = JSONUtil.toBean(tokenData, Map.class);
            Object userIdObj = tokenInfo.get("userId");
            if (userIdObj == null) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            Long userId = Long.valueOf(userIdObj.toString());
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
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 撤销令牌
     *
     * @param token         令牌
     * @param tokenTypeHint 令牌类型提示
     * @param clientKey     客户端标识符
     * @param clientSecret  客户端密钥
     * @return 撤销结果
     */
    @Override
    public Result<Void> revokeToken(String token, String tokenTypeHint, String clientKey, String clientSecret) {
        try {
            // 验证客户端
            OAuthClient client = validateClient(clientKey, clientSecret);
            if (client == null) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            // 删除访问令牌
            redisTemplate.delete("oauth:access_token:" + token);

            // 删除刷新令牌
            redisTemplate.delete("oauth:refresh_token:" + token);

            return Result.success(null);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 验证客户端
     *
     * @param clientKey    客户端标识符
     * @param clientSecret 客户端密钥
     * @return 客户端信息
     */
    @Override
    public OAuthClient validateClient(String clientKey, String clientSecret) {
        if (StrUtil.isBlank(clientKey) || StrUtil.isBlank(clientSecret)) {
            return null;
        }
        return oauthClientMapper.findByClientKeyAndSecret(clientKey, clientSecret);
    }

    /**
     * 生成授权码
     *
     * @param clientKey   客户端标识符
     * @param userId      用户ID
     * @param redirectUri 重定向URI
     * @param scope       授权范围
     * @param state       状态参数
     * @return 授权码记录
     */
    @Override
    public OAuthCode generateAuthorizationCode(String clientKey, Long userId, String redirectUri, String scope, String state) {
        OAuthCode authCode = new OAuthCode();
        authCode.setCode(IdUtil.fastSimpleUUID());
        authCode.setClientKey(clientKey);
        authCode.setUserId(userId);
        authCode.setRedirectUri(redirectUri);
        authCode.setScope(scope);
        authCode.setState(state);
        authCode.setStatus(1);
        authCode.setExpireTime(LocalDateTime.now().plusMinutes(10)); // 10分钟过期

        oauthCodeMapper.insert(authCode);
        return authCode;
    }

    /**
     * 验证授权码
     *
     * @param code        授权码
     * @param clientKey   客户端标识符
     * @param redirectUri 重定向URI
     * @return 授权码记录
     */
    @Override
    public OAuthCode validateAuthorizationCode(String code, String clientKey, String redirectUri) {
        OAuthCode authCode = oauthCodeMapper.findByCodeAndClientKey(code, clientKey);
        if (authCode == null || authCode.getStatus() != 1) {
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
            // 检查客户端标识符是否已存在
            if (oauthClientMapper.findByClientKey(client.getClientKey()) != null) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            // 生成客户端密钥
            if (StrUtil.isBlank(client.getClientSecret())) {
                client.setClientSecret(IdUtil.fastSimpleUUID());
            }

            // 设置默认值
            if (client.getStatus() == null) {
                client.setStatus(1);
            }
            if (client.getAutoApprove() == null) {
                client.setAutoApprove(0);
            }
            if (client.getAccessTokenValidity() == null) {
                client.setAccessTokenValidity(7200); // 2小时
            }
            if (client.getRefreshTokenValidity() == null) {
                client.setRefreshTokenValidity(2592000); // 30天
            }

            oauthClientMapper.insert(client);
            return Result.success(client);
        } catch (Exception e) {
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
     * @param clientKey 客户端标识符
     * @return 删除结果
     */
    @Override
    public Result<Void> deleteClient(String clientKey) {
        try {
            OAuthClient client = oauthClientMapper.findByClientKey(clientKey);
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
     * @param clientKey 客户端标识符
     * @return 客户端信息
     */
    @Override
    public Result<OAuthClient> getClient(String clientKey) {
        try {
            OAuthClient client = oauthClientMapper.findByClientKey(clientKey);
            if (client == null) {
                return Result.error(ResultEnum.RESULT_DATA_NONE, null);
            }
            return Result.success(client);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 验证重定向URI是否有效
     *
     * @param client      客户端信息
     * @param redirectUri 重定向URI
     * @return 是否有效
     */
    private boolean isValidRedirectUri(OAuthClient client, String redirectUri) {
        if (StrUtil.isBlank(client.getRedirectUris()) || StrUtil.isBlank(redirectUri)) {
            return true;
        }

        List<String> validUris = JSONUtil.toList(client.getRedirectUris(), String.class);
        return !validUris.contains(redirectUri);
    }

    /**
     * 生成访问令牌
     *
     * @param userId    用户ID
     * @param clientKey 客户端标识符
     * @param scope     授权范围
     * @return 访问令牌
     */
    private String generateAccessToken(Long userId, String clientKey, String scope) {
        String token = IdUtil.fastSimpleUUID();

        Map<String, Object> tokenInfo = new HashMap<>();
        tokenInfo.put("userId", userId);
        tokenInfo.put("clientKey", clientKey);
        tokenInfo.put("scope", scope);
        tokenInfo.put("createTime", System.currentTimeMillis());

        String tokenKey = "oauth:access_token:" + token;
        redisTemplate.opsForValue().set(tokenKey, JSONUtil.toJsonStr(tokenInfo), 2, TimeUnit.HOURS);

        return token;
    }

    /**
     * 生成刷新令牌
     *
     * @param userId    用户ID
     * @param clientKey 客户端标识符
     * @return 刷新令牌
     */
    private String generateRefreshToken(Long userId, String clientKey) {
        String token = IdUtil.fastSimpleUUID();

        Map<String, Object> tokenInfo = new HashMap<>();
        tokenInfo.put("userId", userId);
        tokenInfo.put("clientKey", clientKey);
        tokenInfo.put("createTime", System.currentTimeMillis());

        String tokenKey = "oauth:refresh_token:" + token;
        redisTemplate.opsForValue().set(tokenKey, JSONUtil.toJsonStr(tokenInfo), 30, TimeUnit.DAYS);

        return token;
    }
}