package cn.flying.identity.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.dto.Account;
import cn.flying.identity.dto.ThirdPartyAccount;
import cn.flying.identity.mapper.ThirdPartyAccountMapper;
import cn.flying.identity.service.AccountService;
import cn.flying.identity.service.ThirdPartyAuthService;
import cn.flying.identity.util.IdUtils;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 第三方认证服务实现类
 * 支持 GitHub、Google、微信等第三方登录
 *
 * @author 王贝强
 */
@Slf4j
@Service
public class ThirdPartyAuthServiceImpl implements ThirdPartyAuthService {

    private static final String THIRD_PARTY_STATE_PREFIX = "third_party:state:";
    private static final String THIRD_PARTY_BIND_PREFIX = "third_party:bind:";
    private static final String THIRD_PARTY_TOKEN_PREFIX = "third_party:token:";

    @Resource
    private AccountService accountService;

    @Resource
    private ThirdPartyAccountMapper thirdPartyAccountMapper;

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    // GitHub OAuth 配置
    @Value("${oauth.github.client-id:}")
    private String githubClientId;
    @Value("${oauth.github.client-secret:}")
    private String githubClientSecret;

    // Google OAuth 配置
    @Value("${oauth.google.client-id:}")
    private String googleClientId;
    @Value("${oauth.google.client-secret:}")
    private String googleClientSecret;

    // 微信 OAuth 配置
    @Value("${oauth.wechat.app-id:}")
    private String wechatAppId;
    @Value("${oauth.wechat.app-secret:}")
    private String wechatAppSecret;

    @Override
    public Result<String> getAuthorizationUrl(String provider, String redirectUri, String state) {
        try {
            String authUrl;
            String stateParam = state != null ? state : IdUtils.nextIdWithPrefix("STATE");

            // 存储状态参数到Redis，防止CSRF攻击
            String stateKey = THIRD_PARTY_STATE_PREFIX + stateParam;
            redisTemplate.opsForValue().set(stateKey, provider + ":" + redirectUri, 10, TimeUnit.MINUTES);

            switch (provider.toLowerCase()) {
                case "github":
                    authUrl = buildGitHubAuthUrl(redirectUri, stateParam);
                    break;
                case "google":
                    authUrl = buildGoogleAuthUrl(redirectUri, stateParam);
                    break;
                case "wechat":
                    authUrl = buildWeChatAuthUrl(redirectUri, stateParam);
                    break;
                default:
                    return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            return Result.success(authUrl);
        } catch (Exception e) {
            log.error("获取第三方登录授权URL失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> handleCallback(String provider, String code, String state) {
        try {
            // 验证状态参数
            String stateKey = THIRD_PARTY_STATE_PREFIX + state;
            String stateValue = redisTemplate.opsForValue().get(stateKey);
            if (stateValue == null) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            // 清除状态参数
            redisTemplate.delete(stateKey);

            // 获取访问令牌
            String redirectUri = stateValue.split(":")[1];
            String accessToken = getAccessTokenFromProvider(provider, code, redirectUri);
            if (accessToken == null) {
                return Result.error(ResultEnum.SYSTEM_ERROR, null);
            }

            // 获取第三方用户信息
            Result<Map<String, Object>> userInfoResult = getThirdPartyUserInfo(provider, accessToken);
            if (userInfoResult.getCode() != ResultEnum.SUCCESS.getCode()) {
                return userInfoResult;
            }

            Map<String, Object> thirdPartyUser = userInfoResult.getData();
            String normalizedId = String.valueOf(thirdPartyUser.get("id"));
            String email = (String) thirdPartyUser.get("email");
            String username = (String) thirdPartyUser.get("login");
            String avatar = (String) thirdPartyUser.get("avatar_url");

            // 微信专属：优先使用unionid作为第三方标识；若无则退回openid
            String unionId = null;
            String openId = null;
            if ("wechat".equalsIgnoreCase(provider)) {
                Object rawObj = thirdPartyUser.get("raw");
                if (rawObj instanceof JSONObject wxRaw) {
                    unionId = wxRaw.getStr("unionid");
                    openId = wxRaw.getStr("openid");
                }
                if (thirdPartyUser.get("unionid") != null) {
                    unionId = String.valueOf(thirdPartyUser.get("unionid"));
                }
                // 规范化后的id可能是unionid或openid，但此处再显式获取
            }

            // 选择用于绑定/查找的第三方ID
            String thirdPartyIdForBinding = normalizedId;
            if ("wechat".equalsIgnoreCase(provider)) {
                thirdPartyIdForBinding = org.apache.commons.lang3.StringUtils.isNotBlank(unionId)
                        ? unionId
                        : (org.apache.commons.lang3.StringUtils.isNotBlank(openId) ? openId : normalizedId);
            }

            // 查找是否已有绑定的账号（微信场景优先以unionid匹配，其次openid）
            Account existingAccount = findAccountByThirdPartyId(provider, thirdPartyIdForBinding);
            if (existingAccount == null && "wechat".equalsIgnoreCase(provider) && org.apache.commons.lang3.StringUtils.isNotBlank(openId)) {
                existingAccount = findAccountByThirdPartyId(provider, openId);
            }

            Map<String, Object> result = new HashMap<>();

            if (existingAccount != null) {
                // 已有绑定账号，直接登录
                StpUtil.login(existingAccount.getId());
                StpUtil.getSession().set("role", existingAccount.getRole());
                StpUtil.getSession().set("username", existingAccount.getUsername());
                StpUtil.getSession().set("email", existingAccount.getEmail());

                result.put("status", "login_success");
                result.put("user_id", existingAccount.getId());
                result.put("username", existingAccount.getUsername());
                result.put("token", StpUtil.getTokenValue());
                result.put("provider", provider);
            } else {
                // 未绑定账号，需要注册或绑定
                if (email != null) {
                    Account emailAccount = accountService.findAccountByNameOrEmail(email);
                    if (emailAccount != null) {
                        // 邮箱已存在，提示绑定
                        result.put("status", "need_bind");
                        result.put("email", email);
                        result.put("provider", provider);
                        result.put("third_party_id", thirdPartyIdForBinding);

                        // 临时存储第三方信息（JSON格式）
                        String bindKey = THIRD_PARTY_BIND_PREFIX + emailAccount.getId() + ":" + provider;
                        JSONObject bindValue = JSONUtil.createObj()
                                .set("thirdPartyId", thirdPartyIdForBinding)
                                .set("accessToken", accessToken)
                                .set("openid", openId)
                                .set("unionid", unionId)
                                .set("username", username)
                                .set("avatar", avatar);

                        if ("wechat".equalsIgnoreCase(provider)) {
                            // 从缓存的access映射中取出refresh_token
                            String mappingKey = THIRD_PARTY_TOKEN_PREFIX + "wechat:access:" + accessToken;
                            String mappingJson = redisTemplate.opsForValue().get(mappingKey);
                            if (org.apache.commons.lang3.StringUtils.isNotBlank(mappingJson)) {
                                JSONObject mapping = JSONUtil.parseObj(mappingJson);
                                bindValue.set("refreshToken", mapping.getStr("refresh_token"));
                                bindValue.set("expiresIn", mapping.getInt("expires_in", 7200));
                            }
                        }

                        redisTemplate.opsForValue().set(bindKey, bindValue.toString(), 30, TimeUnit.MINUTES);
                    } else {
                        // 自动注册新账号
                        Account newAccount = createAccountFromThirdParty(provider, thirdPartyUser);
                        if (newAccount != null) {
                            StpUtil.login(newAccount.getId());
                            StpUtil.getSession().set("role", newAccount.getRole());
                            StpUtil.getSession().set("username", newAccount.getUsername());
                            StpUtil.getSession().set("email", newAccount.getEmail());

                            result.put("status", "register_success");
                            result.put("user_id", newAccount.getId());
                            result.put("username", newAccount.getUsername());
                            result.put("token", StpUtil.getTokenValue());
                            result.put("provider", provider);
                        } else {
                            return Result.error(ResultEnum.SYSTEM_ERROR, null);
                        }
                    }
                } else {
                    result.put("status", "need_complete_info");
                    result.put("provider", provider);
                    result.put("third_party_id", thirdPartyIdForBinding);
                    result.put("username", username);
                    result.put("avatar", avatar);
                }
            }

            return Result.success(result);
        } catch (Exception e) {
            log.error("处理第三方登录回调失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Void> bindThirdPartyAccount(Long userId, String provider, String code) {
        try {
            // 从Redis获取临时存储的绑定信息（JSON）
            String bindKey = THIRD_PARTY_BIND_PREFIX + userId + ":" + provider;
            String bindJson = redisTemplate.opsForValue().get(bindKey);

            if (org.apache.commons.lang3.StringUtils.isNotBlank(bindJson)) {
                JSONObject obj = JSONUtil.parseObj(bindJson);
                String thirdPartyId = obj.getStr("thirdPartyId");
                String accessToken = obj.getStr("accessToken");
                String refreshToken = obj.getStr("refreshToken");
                String unionId = obj.getStr("unionid");

                // 保存绑定关系到数据库
                saveThirdPartyBinding(userId, provider, thirdPartyId, unionId, accessToken, refreshToken);

                // 清除临时数据
                redisTemplate.delete(bindKey);

                // 统一清理此次绑定流程中用过的access映射（临时用途）
                clearAccessMapping(provider, accessToken);
                // 同步清理refresh映射（如果存在）
                clearRefreshMapping(provider, refreshToken);

                return Result.success(null);
            }

            // 未找到临时绑定信息
            return Result.error(ResultEnum.PARAM_IS_INVALID, null);
        } catch (Exception e) {
            log.error("绑定第三方账号失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Void> unbindThirdPartyAccount(Long userId, String provider) {
        try {
            // 删除绑定关系
            removeThirdPartyBinding(userId, provider);
            return Result.success(null);
        } catch (Exception e) {
            log.error("解绑第三方账号失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getUserThirdPartyAccounts(Long userId) {
        try {
            // 获取用户绑定的第三方账号列表
            List<Map<String, Object>> bindings = getThirdPartyBindings(userId);

            Map<String, Object> result = new HashMap<>();
            result.put("user_id", userId);
            result.put("bindings", bindings);
            result.put("total", bindings.size());

            return Result.success(result);
        } catch (Exception e) {
            log.error("获取用户第三方账号列表失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getSupportedProviders() {
        Map<String, Object> providers = new HashMap<>();

        // GitHub
        if (StrUtil.isNotBlank(githubClientId)) {
            Map<String, Object> github = new HashMap<>();
            github.put("name", "GitHub");
            github.put("icon", "github");
            github.put("enabled", true);
            providers.put("github", github);
        }

        // Google
        if (StrUtil.isNotBlank(googleClientId)) {
            Map<String, Object> google = new HashMap<>();
            google.put("name", "Google");
            google.put("icon", "google");
            google.put("enabled", true);
            providers.put("google", google);
        }

        // 微信
        if (StrUtil.isNotBlank(wechatAppId)) {
            Map<String, Object> wechat = new HashMap<>();
            wechat.put("name", "微信");
            wechat.put("icon", "wechat");
            wechat.put("enabled", true);
            providers.put("wechat", wechat);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("providers", providers);
        result.put("total", providers.size());

        return Result.success(result);
    }

    /**
     * 刷新第三方访问令牌
     * 支持：
     * - WeChat: sns/oauth2/refresh_token
     * - Google: oauth2.googleapis.com/token (grant_type=refresh_token)
     * - GitHub: login/oauth/access_token (grant_type=refresh_token)
     */
    @Override
    public Result<Map<String, Object>> refreshThirdPartyToken(Long userId, String provider) {
        try {
            ThirdPartyAccount account = thirdPartyAccountMapper.findByUserIdAndProvider(userId, provider);
            if (account == null || org.apache.commons.lang3.StringUtils.isBlank(account.getRefreshToken())) {
                log.warn("未找到可刷新令牌的第三方账号: userId={}, provider={}", userId, provider);
                return Result.error(ResultEnum.RESULT_DATA_NONE, null);
            }

            String newAccessToken = null;
            String newRefreshToken = account.getRefreshToken();
            int expiresIn = 7200;
            String openIdOrSub = null;
            JSONObject extra = JSONUtil.createObj();

            switch (provider.toLowerCase()) {
                case "wechat": {
                    String url = String.format(
                            "https://api.weixin.qq.com/sns/oauth2/refresh_token?appid=%s&grant_type=refresh_token&refresh_token=%s",
                            URLEncoder.encode(wechatAppId, StandardCharsets.UTF_8),
                            URLEncoder.encode(account.getRefreshToken(), StandardCharsets.UTF_8)
                    );

                    HttpResponse resp = HttpRequest.get(url)
                            .header("Accept", "application/json")
                            .timeout(10000)
                            .execute();
                    if (!resp.isOk()) {
                        return Result.error(mapHttpStatusToEnum(resp.getStatus()), null);
                    }
                    JSONObject obj = JSONUtil.parseObj(resp.body());
                    if (obj.containsKey("errcode") && obj.getInt("errcode", 0) != 0) {
                        return Result.error(mapWeChatErr(obj.getInt("errcode")), null);
                    }
                    newAccessToken = obj.getStr("access_token");
                    newRefreshToken = obj.getStr("refresh_token", newRefreshToken);
                    expiresIn = obj.getInt("expires_in", 7200);
                    openIdOrSub = obj.getStr("openid");

                    // 更新Redis映射
                    if (org.apache.commons.lang3.StringUtils.isNotBlank(newAccessToken)) {
                        String mappingKey = THIRD_PARTY_TOKEN_PREFIX + "wechat:access:" + newAccessToken;
                        JSONObject mapping = JSONUtil.createObj()
                                .set("openid", openIdOrSub)
                                .set("unionid", obj.getStr("unionid"))
                                .set("refresh_token", newRefreshToken)
                                .set("expires_in", expiresIn);
                        redisTemplate.opsForValue().set(mappingKey, mapping.toString(), expiresIn, TimeUnit.SECONDS);
                    }
                    break;
                }
                case "google": {
                    String url = "https://oauth2.googleapis.com/token";
                    HttpResponse resp = HttpRequest.post(url)
                            .form(Map.of(
                                    "client_id", googleClientId,
                                    "client_secret", googleClientSecret,
                                    "grant_type", "refresh_token",
                                    "refresh_token", account.getRefreshToken()
                            ))
                            .header("Accept", "application/json")
                            .timeout(10000)
                            .execute();
                    if (!resp.isOk()) {
                        return Result.error(mapHttpStatusToEnum(resp.getStatus()), null);
                    }
                    JSONObject obj = JSONUtil.parseObj(resp.body());
                    if (obj.containsKey("error")) {
                        return Result.error(mapOAuthError(obj.getStr("error")), null);
                    }
                    newAccessToken = obj.getStr("access_token");
                    newRefreshToken = obj.getStr("refresh_token", newRefreshToken);
                    expiresIn = obj.getInt("expires_in", 3600);
                    extra.set("scope", obj.getStr("scope"));
                    extra.set("token_type", obj.getStr("token_type"));

                    // 更新Redis缓存
                    if (org.apache.commons.lang3.StringUtils.isNotBlank(newAccessToken)) {
                        String mappingKey = THIRD_PARTY_TOKEN_PREFIX + "google:access:" + newAccessToken;
                        JSONObject mapping = JSONUtil.createObj()
                                .set("expires_in", expiresIn)
                                .set("scope", obj.getStr("scope"))
                                .set("token_type", obj.getStr("token_type"));
                        redisTemplate.opsForValue().set(mappingKey, mapping.toString(), expiresIn, TimeUnit.SECONDS);
                    }
                    break;
                }
                case "github": {
                    String url = "https://github.com/login/oauth/access_token";
                    HttpResponse resp = HttpRequest.post(url)
                            .form(Map.of(
                                    "client_id", githubClientId,
                                    "client_secret", githubClientSecret,
                                    "grant_type", "refresh_token",
                                    "refresh_token", account.getRefreshToken()
                            ))
                            .header("Accept", "application/json")
                            .timeout(10000)
                            .execute();
                    if (!resp.isOk()) {
                        return Result.error(mapHttpStatusToEnum(resp.getStatus()), null);
                    }
                    JSONObject obj = JSONUtil.parseObj(resp.body());
                    if (obj.containsKey("error")) {
                        return Result.error(mapOAuthError(obj.getStr("error")), null);
                    }
                    newAccessToken = obj.getStr("access_token");
                    newRefreshToken = obj.getStr("refresh_token", newRefreshToken);
                    expiresIn = obj.getInt("expires_in", 7200);
                    extra.set("scope", obj.getStr("scope"));
                    extra.set("token_type", obj.getStr("token_type"));

                    if (org.apache.commons.lang3.StringUtils.isNotBlank(newAccessToken)) {
                        String mappingKey = THIRD_PARTY_TOKEN_PREFIX + "github:access:" + newAccessToken;
                        JSONObject mapping = JSONUtil.createObj()
                                .set("expires_in", expiresIn)
                                .set("scope", obj.getStr("scope"))
                                .set("token_type", obj.getStr("token_type"));
                        redisTemplate.opsForValue().set(mappingKey, mapping.toString(), expiresIn, TimeUnit.SECONDS);
                    }
                    break;
                }
                default:
                    return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            // 清理旧的 access/refresh 映射缓存键
            clearAccessMapping(provider, account.getAccessToken());
            clearRefreshMapping(provider, account.getRefreshToken());

            // 更新数据库 token 与过期时间
            java.time.LocalDateTime newExpire = java.time.LocalDateTime.now().plusSeconds(expiresIn);
            thirdPartyAccountMapper.updateTokens(userId, provider, newAccessToken, newRefreshToken, newExpire);

            Map<String, Object> res = new HashMap<>();
            res.put("access_token", newAccessToken);
            res.put("refresh_token", newRefreshToken);
            res.put("expires_in", expiresIn);
            res.put("subject", openIdOrSub);
            res.put("provider", provider);
            if (!extra.isEmpty()) {
                res.put("extra", extra);
            }

            return Result.success(res);
        } catch (Exception e) {
            log.error("刷新第三方令牌失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getThirdPartyUserInfo(String provider, String accessToken) {
        try {
            String userInfoUrl;
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + accessToken);
            headers.put("User-Agent", "RecordPlatform/1.0");

            switch (provider.toLowerCase()) {
                case "github":
                    userInfoUrl = "https://api.github.com/user";
                    break;
                case "google":
                    userInfoUrl = "https://www.googleapis.com/oauth2/v2/userinfo";
                    break;
                case "wechat":
                    // 微信需要特殊处理
                    return getWeChatUserInfo(accessToken);
                default:
                    return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            HttpResponse response = HttpRequest.get(userInfoUrl)
                    .headerMap(headers, true)
                    .timeout(10000)
                    .execute();

            if (response.isOk()) {
                String responseBody = response.body();
                JSONObject userInfo = JSONUtil.parseObj(responseBody);

                // Google在用户信息也可能返回error字段
                if ("google".equalsIgnoreCase(provider) && userInfo.containsKey("error")) {
                    String errorCode = userInfo.getByPath("error.errors[0].reason", String.class);
                    return Result.error(mapOAuthError(errorCode), null);
                }

                // 规范化不同提供商的字段，统一给上层：id / login / avatar_url / email
                JSONObject normalized = JSONUtil.createObj();
                switch (provider.toLowerCase()) {
                    case "github":
                        normalized.set("id", userInfo.get("id"));
                        normalized.set("login", userInfo.getStr("login"));
                        normalized.set("avatar_url", userInfo.getStr("avatar_url"));
                        normalized.set("email", userInfo.getStr("email"));
                        break;
                    case "google":
                        normalized.set("id", userInfo.getStr("id"));
                        normalized.set("login", userInfo.getStr("name"));
                        normalized.set("avatar_url", userInfo.getStr("picture"));
                        normalized.set("email", userInfo.getStr("email"));
                        break;
                }
                normalized.set("raw", userInfo);

                return Result.success(normalized);
            } else {
                // 根据HTTP状态码映射平台错误
                return Result.error(mapHttpStatusToEnum(response.getStatus()), null);
            }
        } catch (Exception e) {
            log.error("获取第三方用户信息失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Boolean> validateThirdPartyToken(String provider, String accessToken) {
        try {
            Result<Map<String, Object>> userInfoResult = getThirdPartyUserInfo(provider, accessToken);
            return Result.success(userInfoResult.getCode() == ResultEnum.SUCCESS.getCode());
        } catch (Exception e) {
            log.error("验证第三方令牌失败", e);
            return Result.success(false);
        }
    }

    /**
     * 获取第三方绑定关系列表
     */
    private List<Map<String, Object>> getThirdPartyBindings(Long userId) {
        try {
            List<Map<String, Object>> bindings = new ArrayList<>();

            // 从数据库查询绑定关系
            List<ThirdPartyAccount> thirdPartyAccounts = thirdPartyAccountMapper.findByUserId(userId);

            for (ThirdPartyAccount account : thirdPartyAccounts) {
                Map<String, Object> binding = new HashMap<>();
                binding.put("provider", account.getProvider());
                binding.put("third_party_id", account.getThirdPartyId());
                binding.put("bind_time", account.getBindTime());

                // 检查令牌是否过期
                if (account.getExpiresAt() != null && account.getExpiresAt().isBefore(java.time.LocalDateTime.now())) {
                    binding.put("status", "expired");
                } else {
                    binding.put("status", "active");
                }

                // 添加提供商友好名称
                switch (account.getProvider().toLowerCase()) {
                    case "github":
                        binding.put("provider_name", "GitHub");
                        break;
                    case "google":
                        binding.put("provider_name", "Google");
                        break;
                    case "wechat":
                        binding.put("provider_name", "微信");
                        break;
                    default:
                        binding.put("provider_name", account.getProvider());
                        break;
                }

                bindings.add(binding);
            }

            return bindings;
        } catch (Exception e) {
            log.error("获取第三方绑定关系列表失败: userId={}", userId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 移除第三方绑定关系
     */
    private void removeThirdPartyBinding(Long userId, String provider) {
        try {
            // 在删除前获取绑定信息，用于清理缓存
            ThirdPartyAccount existing = thirdPartyAccountMapper.findByUserIdAndProvider(userId, provider);

            // 从数据库中删除绑定关系（逻辑删除）
            int affectedRows = thirdPartyAccountMapper.deleteByUserIdAndProvider(userId, provider);
            if (affectedRows > 0) {
                log.info("从数据库移除第三方绑定关系: userId={}, provider={}", userId, provider);
            }

            // 同步清理access/refresh相关缓存
            if (existing != null) {
                clearAccessMapping(provider, existing.getAccessToken());
                clearRefreshMapping(provider, existing.getRefreshToken());
            }

            // 同时从Redis中删除（旧版快速缓存键）
            String bindingKey = THIRD_PARTY_TOKEN_PREFIX + userId + ":" + provider;
            redisTemplate.delete(bindingKey);

            log.info("移除第三方绑定关系: userId={}, provider={}", userId, provider);
        } catch (Exception e) {
            log.error("移除第三方绑定关系失败: userId={}, provider={}", userId, provider, e);
            throw new RuntimeException("移除第三方绑定关系失败", e);
        }
    }

    /**
     * 保存第三方绑定关系
     */
    private void saveThirdPartyBinding(Long userId, String provider, String thirdPartyId, String unionId,
                                       String accessToken, String refreshToken) {
        try {
            // 微信优先使用unionid作为第三方标识
            String storeId = thirdPartyId;
            if ("wechat".equalsIgnoreCase(provider) && org.apache.commons.lang3.StringUtils.isNotBlank(unionId)) {
                storeId = unionId;
            }

            // 检查是否已存在绑定关系
            ThirdPartyAccount existingBinding = thirdPartyAccountMapper.findByUserIdAndProvider(userId, provider);

            if (existingBinding != null) {
                // 记录旧token用于清理
                String oldAccessToken = existingBinding.getAccessToken();
                String oldRefreshToken = existingBinding.getRefreshToken();

                existingBinding.setThirdPartyId(storeId);
                existingBinding.setAccessToken(accessToken);
                if (org.apache.commons.lang3.StringUtils.isNotBlank(refreshToken)) {
                    existingBinding.setRefreshToken(refreshToken);
                }
                existingBinding.setExpiresAt(java.time.LocalDateTime.now().plusDays(30)); // 默认延长30天
                thirdPartyAccountMapper.updateById(existingBinding);
                log.info("更新第三方绑定关系: userId={}, provider={}, thirdPartyId={}", userId, provider, storeId);

                // 清理旧的 access/refresh 映射缓存键
                clearAccessMapping(provider, oldAccessToken);
                clearRefreshMapping(provider, oldRefreshToken);
            } else {
                // 创建新的绑定关系
                ThirdPartyAccount thirdPartyAccount = new ThirdPartyAccount();
                thirdPartyAccount.setId(IdUtils.nextUserId());
                thirdPartyAccount.setUserId(userId);
                thirdPartyAccount.setProvider(provider);
                thirdPartyAccount.setThirdPartyId(storeId);
                thirdPartyAccount.setAccessToken(accessToken);
                thirdPartyAccount.setRefreshToken(refreshToken);
                thirdPartyAccount.setExpiresAt(java.time.LocalDateTime.now().plusDays(30)); // 默认30天
                thirdPartyAccount.setDeleted(0);

                thirdPartyAccountMapper.insert(thirdPartyAccount);
                log.info("保存第三方绑定关系: userId={}, provider={}, thirdPartyId={}", userId, provider, storeId);
            }

            // 同时更新Redis缓存（仅作快速查询用途）
            if ("wechat".equalsIgnoreCase(provider) && org.apache.commons.lang3.StringUtils.isNotBlank(accessToken)) {
                String mappingKey = THIRD_PARTY_TOKEN_PREFIX + "wechat:access:" + accessToken;
                JSONObject mapping = JSONUtil.createObj()
                        .set("openid", org.apache.commons.lang3.StringUtils.defaultString(thirdPartyId))
                        .set("unionid", unionId)
                        .set("refresh_token", refreshToken)
                        .set("expires_in", 7200);
                redisTemplate.opsForValue().set(mappingKey, mapping.toString(), 7200, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.error("保存第三方绑定关系失败: userId={}, provider={}, thirdPartyId={}", userId, provider, thirdPartyId, e);
            throw new RuntimeException("保存第三方绑定关系失败", e);
        }
    }

    /**
     * 从第三方提供商获取访问令牌
     */
    private String getAccessTokenFromProvider(String provider, String code, String redirectUri) {
        try {
            String tokenUrl;
            Map<String, Object> params = new HashMap<>();

            switch (provider.toLowerCase()) {
                case "github":
                    tokenUrl = "https://github.com/login/oauth/access_token";
                    params.put("client_id", githubClientId);
                    params.put("client_secret", githubClientSecret);
                    params.put("code", code);
                    // 兼容多回调场景，补充 redirect_uri 参数
                    params.put("redirect_uri", redirectUri);
                    break;
                case "google":
                    tokenUrl = "https://oauth2.googleapis.com/token";
                    params.put("client_id", googleClientId);
                    params.put("client_secret", googleClientSecret);
                    params.put("code", code);
                    params.put("grant_type", "authorization_code");
                    params.put("redirect_uri", redirectUri);
                    break;
                case "wechat":
                    tokenUrl = "https://api.weixin.qq.com/sns/oauth2/access_token";
                    params.put("appid", wechatAppId);
                    params.put("secret", wechatAppSecret);
                    params.put("code", code);
                    params.put("grant_type", "authorization_code");
                    break;
                default:
                    log.error("不支持的第三方提供商: {}", provider);
                    return null;
            }

            HttpResponse response = HttpRequest.post(tokenUrl)
                    .form(params)
                    .header("Accept", "application/json")
                    .timeout(10000)
                    .execute();

            if (response.isOk()) {
                String responseBody = response.body();
                JSONObject tokenInfo = JSONUtil.parseObj(responseBody);

                // WeChat错误码判断
                if ("wechat".equalsIgnoreCase(provider) && tokenInfo.containsKey("errcode") && tokenInfo.getInt("errcode", 0) != 0) {
                    log.error("微信获取access_token失败: {}", tokenInfo.toString());
                    return null;
                }
                // Google/GitHub标准错误处理
                if (("google".equalsIgnoreCase(provider) || "github".equalsIgnoreCase(provider)) && tokenInfo.containsKey("error")) {
                    String errorCode = tokenInfo.getStr("error");
                    log.warn("{} 交换token失败: error={}, payload={}", provider, errorCode, tokenInfo);
                    return null;
                }

                String accessToken = tokenInfo.getStr("access_token");
                if (StrUtil.isBlank(accessToken)) {
                    log.error("第三方返回的访问令牌为空，提供商: {}, 响应: {}", provider, responseBody);
                }

                // 微信：缓存 access_token -> openid/unionid/refresh_token 映射，便于后续获取用户信息与刷新
                if ("wechat".equalsIgnoreCase(provider)) {
                    String openId = tokenInfo.getStr("openid");
                    Integer expiresIn = tokenInfo.getInt("expires_in", 7200);
                    String refreshToken = tokenInfo.getStr("refresh_token");
                    if (StrUtil.isNotBlank(openId) && StrUtil.isNotBlank(accessToken)) {
                        String mappingKey = THIRD_PARTY_TOKEN_PREFIX + "wechat:access:" + accessToken;
                        JSONObject mapping = JSONUtil.createObj()
                                .set("openid", openId)
                                .set("unionid", tokenInfo.getStr("unionid"))
                                .set("refresh_token", refreshToken)
                                .set("expires_in", expiresIn);
                        redisTemplate.opsForValue().set(mappingKey, mapping.toString(), expiresIn, TimeUnit.SECONDS);
                        log.debug("已缓存微信access_token到openid映射，TTL={}s", expiresIn);
                    } else {
                        log.warn("微信access_token或openid为空，无法缓存映射: token={}, openid={}", accessToken, openId);
                    }
                }

                return accessToken;
            } else {
                log.error("获取第三方访问令牌HTTP请求失败，提供商: {}, 状态码: {}", provider, response.getStatus());
            }

            return null;
        } catch (Exception e) {
            log.error("获取第三方访问令牌失败", e);
            return null;
        }
    }

    /**
     * 根据第三方ID查找账号
     */
    private Account findAccountByThirdPartyId(String provider, String thirdPartyId) {
        try {
            if (StrUtil.isBlank(provider) || StrUtil.isBlank(thirdPartyId)) {
                log.warn("查找第三方账号参数无效: provider={}, thirdPartyId={}", provider, thirdPartyId);
                return null;
            }

            // 从数据库查询第三方绑定关系
            ThirdPartyAccount thirdPartyAccount = thirdPartyAccountMapper.findByProviderAndThirdPartyId(provider, thirdPartyId);
            if (thirdPartyAccount != null) {
                // 根据绑定的用户ID查找用户账号
                Account account = accountService.findAccountById(thirdPartyAccount.getUserId());
                if (account == null) {
                    log.warn("第三方绑定的用户账号不存在: userId={}, provider={}, thirdPartyId={}",
                            thirdPartyAccount.getUserId(), provider, thirdPartyId);
                }
                return account;
            }

            log.debug("未找到第三方账号绑定: provider={}, thirdPartyId={}", provider, thirdPartyId);
            return null;
        } catch (Exception e) {
            log.error("根据第三方ID查找账号失败: provider={}, thirdPartyId={}", provider, thirdPartyId, e);
            return null;
        }
    }

    /**
     * 从第三方信息创建账号
     */
    private Account createAccountFromThirdParty(String provider, Map<String, Object> thirdPartyUser) {
        try {
            String username = (String) thirdPartyUser.get("login");
            String email = (String) thirdPartyUser.get("email");
            String avatar = (String) thirdPartyUser.get("avatar_url");

            if (username == null) {
                username = provider + "_" + thirdPartyUser.get("id");
            }

            // 确保用户名唯一
            String finalUsername = username;
            int suffix = 1;
            while (accountService.existsAccountByUsername(finalUsername)) {
                finalUsername = username + "_" + suffix++;
            }

            Account account = new Account();
            account.setId(IdUtils.nextUserId());
            account.setUsername(finalUsername);
            account.setPassword(accountService.encodePassword(IdUtils.nextIdWithPrefix("PWD"))); // 随机密码
            account.setEmail(email);
            account.setRole("user");
            account.setAvatar(avatar);

            if (accountService.save(account)) {
                log.info("从第三方信息创建账号成功: provider={}, username={}, email={}", provider, finalUsername, email);
                return account;
            } else {
                log.error("保存第三方创建的账号失败: provider={}, username={}", provider, finalUsername);
            }

            return null;
        } catch (Exception e) {
            log.error("从第三方信息创建账号异常: provider={}", provider, e);
            return null;
        }
    }

    /**
     * 获取微信用户信息
     */
    private Result<Map<String, Object>> getWeChatUserInfo(String accessToken) {
        try {
            // 从缓存恢复 openid 映射
            String mappingKey = THIRD_PARTY_TOKEN_PREFIX + "wechat:access:" + accessToken;
            String mappingJson = redisTemplate.opsForValue().get(mappingKey);
            if (StrUtil.isBlank(mappingJson)) {
                return Result.error(ResultEnum.OAUTH_TOKEN_INVALID, null);
            }
            JSONObject mapping = JSONUtil.parseObj(mappingJson);
            String openId = mapping.getStr("openid");
            if (StrUtil.isBlank(openId)) {
                return Result.error(ResultEnum.OAUTH_TOKEN_INVALID, null);
            }

            String userInfoUrl = String.format(
                    "https://api.weixin.qq.com/sns/userinfo?access_token=%s&openid=%s&lang=zh_CN",
                    URLEncoder.encode(accessToken, StandardCharsets.UTF_8),
                    URLEncoder.encode(openId, StandardCharsets.UTF_8)
            );

            HttpResponse response = HttpRequest.get(userInfoUrl)
                    .header("Accept", "application/json")
                    .timeout(10000)
                    .execute();

            if (response.isOk()) {
                String body = response.body();
                JSONObject wx = JSONUtil.parseObj(body);

                // 错误码处理
                if (wx.containsKey("errcode") && wx.getInt("errcode", 0) != 0) {
                    return Result.error(mapWeChatErr(wx.getInt("errcode")), null);
                }

                // 规范化字段，兼容上层使用（id/login/avatar_url/email）
                String unionId = wx.getStr("unionid");
                JSONObject normalized = JSONUtil.createObj();
                normalized.set("id", org.apache.commons.lang3.StringUtils.defaultIfBlank(unionId, wx.getStr("openid", openId)));
                normalized.set("login", wx.getStr("nickname", "wechat_user"));
                normalized.set("avatar_url", wx.getStr("headimgurl", ""));
                normalized.set("email", (String) null); // 微信通常不返回邮箱
                normalized.set("unionid", unionId);
                normalized.set("raw", wx);

                return Result.success(normalized);
            } else {
                return Result.error(mapHttpStatusToEnum(response.getStatus()), null);
            }
        } catch (Exception e) {
            log.error("获取微信用户信息异常", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 清理不同提供商的 access 映射缓存键
     */
    private void clearAccessMapping(String provider, String accessToken) {
        try {
            if (org.apache.commons.lang3.StringUtils.isBlank(accessToken)) {
                return;
            }
            String prefix = provider.toLowerCase();
            String key = THIRD_PARTY_TOKEN_PREFIX + prefix + ":access:" + accessToken;
            redisTemplate.delete(key);
            log.debug("已清理第三方access映射: {}", key);
        } catch (Exception e) {
            log.warn("清理第三方access映射失败: provider={}, token={}", provider, accessToken, e);
        }
    }

    private void clearRefreshMapping(String provider, String refreshToken) {
        try {
            if (org.apache.commons.lang3.StringUtils.isBlank(refreshToken)) {
                return;
            }
            String prefix = provider.toLowerCase();
            String key = THIRD_PARTY_TOKEN_PREFIX + prefix + ":refresh:" + refreshToken;
            redisTemplate.delete(key);
            log.debug("已清理第三方refresh映射: {}", key);
        } catch (Exception e) {
            log.warn("清理第三方refresh映射失败: provider={}, token={}", provider, refreshToken, e);
        }
    }

    /**
     * 构建 GitHub 授权URL
     */
    private String buildGitHubAuthUrl(String redirectUri, String state) {
        return String.format("https://github.com/login/oauth/authorize?client_id=%s&redirect_uri=%s&scope=user:email&state=%s",
                githubClientId, URLEncoder.encode(redirectUri, StandardCharsets.UTF_8), state);
    }

    /**
     * 构建 Google 授权URL
     */
    private String buildGoogleAuthUrl(String redirectUri, String state) {
        return String.format("https://accounts.google.com/o/oauth2/v2/auth?client_id=%s&redirect_uri=%s&scope=openid email profile&response_type=code&state=%s",
                googleClientId, URLEncoder.encode(redirectUri, StandardCharsets.UTF_8), state);
    }

    /**
     * 构建微信授权URL
     */
    private String buildWeChatAuthUrl(String redirectUri, String state) {
        return String.format("https://open.weixin.qq.com/connect/qrconnect?appid=%s&redirect_uri=%s&response_type=code&scope=snsapi_login&state=%s",
                wechatAppId, URLEncoder.encode(redirectUri, StandardCharsets.UTF_8), state);
    }

    /**
     * 将HTTP状态码映射为平台内部错误枚举
     */
    private ResultEnum mapHttpStatusToEnum(int status) {
        if (status == 401) return ResultEnum.PERMISSION_UNAUTHORIZED;
        if (status == 403) return ResultEnum.PERMISSION_UNAUTHORIZED;
        if (status == 429) return ResultEnum.SYSTEM_BUSY;
        if (status >= 500) return ResultEnum.SYSTEM_ERROR;
        return ResultEnum.OAUTH_ERROR;
    }

    /**
     * 将微信errcode映射为平台内部错误枚举
     */
    private ResultEnum mapWeChatErr(int errcode) {
        switch (errcode) {
            case 40029: // invalid code
            case 40163: // code been used
                return ResultEnum.OAUTH_CODE_INVALID;
            case 40003: // invalid openid
            case 40014: // invalid access_token
            case 40125: // invalid appsecret
            case 42001: // access_token expired
                return ResultEnum.OAUTH_TOKEN_INVALID;
            default:
                return ResultEnum.OAUTH_ERROR;
        }
    }

    /**
     * 将OAuth标准错误码映射为平台内部错误枚举
     */
    private ResultEnum mapOAuthError(String errorCode) {
        if (errorCode == null) return ResultEnum.OAUTH_ERROR;
        String e = errorCode.toLowerCase();
        if (e.contains("invalid_grant") || e.contains("invalid_code")) {
            return ResultEnum.OAUTH_CODE_INVALID;
        }
        if (e.contains("invalid_token") || e.contains("expired") || e.contains("token")) {
            return ResultEnum.OAUTH_TOKEN_INVALID;
        }
        if (e.contains("invalid_client") || e.contains("unauthorized_client") || e.contains("access_denied")) {
            return ResultEnum.PERMISSION_UNAUTHORIZED;
        }
        if (e.contains("temporarily_unavailable") || e.contains("slowdown") || e.contains("rate_limit")) {
            return ResultEnum.SYSTEM_BUSY;
        }
        return ResultEnum.OAUTH_ERROR;
    }
}
