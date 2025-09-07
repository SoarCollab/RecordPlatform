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
import java.util.*;
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
            String accessToken = getAccessTokenFromProvider(provider, code, stateValue.split(":")[1]);
            if (accessToken == null) {
                return Result.error(ResultEnum.SYSTEM_ERROR, null);
            }

            // 获取第三方用户信息
            Result<Map<String, Object>> userInfoResult = getThirdPartyUserInfo(provider, accessToken);
            if (userInfoResult.getCode() != ResultEnum.SUCCESS.getCode()) {
                return userInfoResult;
            }

            Map<String, Object> thirdPartyUser = userInfoResult.getData();
            String thirdPartyId = thirdPartyUser.get("id").toString();
            String email = (String) thirdPartyUser.get("email");
            String username = (String) thirdPartyUser.get("login");
            String avatar = (String) thirdPartyUser.get("avatar_url");

            // 查找是否已有绑定的账号
            Account existingAccount = findAccountByThirdPartyId(provider, thirdPartyId);

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
                        result.put("third_party_id", thirdPartyId);

                        // 临时存储第三方信息，用于后续绑定
                        String bindKey = THIRD_PARTY_BIND_PREFIX + emailAccount.getId() + ":" + provider;
                        String bindValue = thirdPartyId + ":" + accessToken;
                        redisTemplate.opsForValue().set(bindKey, bindValue, 30, TimeUnit.MINUTES);
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
                    result.put("third_party_id", thirdPartyId);
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
            // 这里可以实现绑定逻辑
            // 验证用户身份，获取第三方信息，建立绑定关系

            // 简化实现：直接从Redis获取临时存储的绑定信息
            String bindKey = THIRD_PARTY_BIND_PREFIX + userId + ":" + provider;
            String bindValue = redisTemplate.opsForValue().get(bindKey);

            if (bindValue != null) {
                String[] parts = bindValue.split(":");
                String thirdPartyId = parts[0];
                String accessToken = parts[1];

                // 保存绑定关系到数据库（这里需要创建相应的表和实体类）
                saveThirdPartyBinding(userId, provider, thirdPartyId, accessToken);

                // 清除临时数据
                redisTemplate.delete(bindKey);

                return Result.success(null);
            }

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

    @Override
    public Result<Map<String, Object>> refreshThirdPartyToken(Long userId, String provider) {
        try {
            // 这里可以实现令牌刷新逻辑
            // 目前简化处理
            return Result.error(ResultEnum.PARAM_IS_INVALID, null);
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
                return Result.success(userInfo);
            } else {
                log.error("获取第三方用户信息失败，状态码: {}, 响应: {}", response.getStatus(), response.body());
                return Result.error(ResultEnum.SYSTEM_ERROR, null);
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
            // 从数据库中删除绑定关系（逻辑删除）
            int affectedRows = thirdPartyAccountMapper.deleteByUserIdAndProvider(userId, provider);
            if (affectedRows > 0) {
                log.info("从数据库移除第三方绑定关系: userId={}, provider={}", userId, provider);
            }
            
            // 同时从Redis中删除缓存
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
    private void saveThirdPartyBinding(Long userId, String provider, String thirdPartyId, String accessToken) {
        try {
            // 检查是否已存在绑定关系
            ThirdPartyAccount existingBinding = thirdPartyAccountMapper.findByUserIdAndProvider(userId, provider);
            
            if (existingBinding != null) {
                // 更新现有绑定关系
                existingBinding.setThirdPartyId(thirdPartyId);
                existingBinding.setAccessToken(accessToken);
                existingBinding.setExpiresAt(java.time.LocalDateTime.now().plusDays(30)); // 30天后过期
                thirdPartyAccountMapper.updateById(existingBinding);
                log.info("更新第三方绑定关系: userId={}, provider={}, thirdPartyId={}", userId, provider, thirdPartyId);
            } else {
                // 创建新的绑定关系
                ThirdPartyAccount thirdPartyAccount = new ThirdPartyAccount();
                thirdPartyAccount.setId(IdUtils.nextUserId());
                thirdPartyAccount.setUserId(userId);
                thirdPartyAccount.setProvider(provider);
                thirdPartyAccount.setThirdPartyId(thirdPartyId);
                thirdPartyAccount.setAccessToken(accessToken);
                thirdPartyAccount.setExpiresAt(java.time.LocalDateTime.now().plusDays(30)); // 30天后过期
                thirdPartyAccount.setDeleted(0);
                
                thirdPartyAccountMapper.insert(thirdPartyAccount);
                log.info("保存第三方绑定关系: userId={}, provider={}, thirdPartyId={}", userId, provider, thirdPartyId);
            }

            // 同时在Redis中缓存，便于快速访问
            String bindingKey = THIRD_PARTY_TOKEN_PREFIX + userId + ":" + provider;
            String bindingValue = thirdPartyId + ":" + accessToken + ":" + System.currentTimeMillis();
            redisTemplate.opsForValue().set(bindingKey, bindingValue, 30, TimeUnit.DAYS);
            
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
                return tokenInfo.getStr("access_token");
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
            // 从数据库查询第三方绑定关系
            ThirdPartyAccount thirdPartyAccount = thirdPartyAccountMapper.findByProviderAndThirdPartyId(provider, thirdPartyId);
            if (thirdPartyAccount != null) {
                // 根据绑定的用户ID查找用户账号
                return accountService.findAccountById(thirdPartyAccount.getUserId());
            }
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
                return account;
            }

            return null;
        } catch (Exception e) {
            log.error("从第三方信息创建账号失败", e);
            return null;
        }
    }

    /**
     * 获取微信用户信息
     */
    private Result<Map<String, Object>> getWeChatUserInfo(String accessToken) {
        try {
            // 微信需要先获取openid
            String userInfoUrl = "https://api.weixin.qq.com/sns/userinfo?access_token=" + accessToken + "&openid=OPENID";

            HttpResponse response = HttpRequest.get(userInfoUrl)
                    .timeout(10000)
                    .execute();

            if (response.isOk()) {
                String responseBody = response.body();
                JSONObject userInfo = JSONUtil.parseObj(responseBody);
                return Result.success(userInfo);
            } else {
                return Result.error(ResultEnum.SYSTEM_ERROR, null);
            }
        } catch (Exception e) {
            log.error("获取微信用户信息失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
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
}
