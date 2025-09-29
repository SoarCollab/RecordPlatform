package cn.flying.service;

import cn.flying.common.util.CacheUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.io.Serializable;
import java.util.Date;

/**
 * OAuth2 用户信息缓存服务
 * 负责缓存和管理从 Identity 服务获取的用户信息，减少远程调用，提高性能
 */
@Slf4j
@Service
public class OAuth2UserInfoCacheService {

    private static final String CACHE_PREFIX = "oauth2:userinfo:";
    private static final String NEGATIVE_CACHE_PREFIX = "oauth2:invalid:";

    @Resource
    private CacheUtils cacheUtils;

    @Resource
    private RestTemplate restTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Value("${oauth2.resource.userinfo-url:http://localhost:8081/oauth/userinfo}")
    private String userinfoUrl;

    @Value("${oauth2.cache.userinfo-ttl:300}")
    private long userinfoCacheTtl; // 默认缓存5分钟

    @Value("${oauth2.cache.negative-ttl:60}")
    private long negativeCacheTtl; // 无效token缓存1分钟

    /**
     * 获取用户信息（优先从缓存获取）
     *
     * @param accessToken 访问令牌
     * @return 用户信息，如果令牌无效返回null
     */
    public CachedUserInfo getUserInfo(String accessToken) {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            log.debug("令牌为空，跳过用户信息获取");
            return null;
        }

        String cacheKey = CACHE_PREFIX + accessToken;
        String negativeCacheKey = NEGATIVE_CACHE_PREFIX + accessToken;

        // 1. 检查是否在无效令牌缓存中
        CachedUserInfo negativeCache = cacheUtils.takeFormCache(negativeCacheKey, CachedUserInfo.class);
        if (negativeCache != null) {
            log.debug("令牌在无效缓存中: {}", accessToken.substring(0, Math.min(8, accessToken.length())) + "...");
            return negativeCache;
        }

        // 2. 尝试从缓存获取
        CachedUserInfo cachedInfo = cacheUtils.takeFormCache(cacheKey, CachedUserInfo.class);
        if (cachedInfo != null) {
            log.debug("从缓存获取用户信息: userId={}", cachedInfo.getUserId());
            return cachedInfo;
        }

        // 3. 缓存未命中，调用远程接口
        log.debug("缓存未命中，调用远程接口获取用户信息");
        return fetchAndCacheUserInfo(accessToken);
    }

    /**
     * 从远程接口获取用户信息并缓存
     *
     * @param accessToken 访问令牌
     * @return 用户信息
     */
    private CachedUserInfo fetchAndCacheUserInfo(String accessToken) {
        long startTime = System.currentTimeMillis();

        try {
            // 构造请求
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + accessToken);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            // 发送请求
            ResponseEntity<String> response = restTemplate.exchange(
                    userinfoUrl,
                    HttpMethod.GET,
                    entity,
                    String.class
            );

            long elapsedTime = System.currentTimeMillis() - startTime;
            log.info("OAuth2 userinfo 接口调用耗时: {}ms", elapsedTime);

            // 解析响应
            String resultJson = response.getBody();
            if (resultJson == null || resultJson.isEmpty()) {
                log.warn("OAuth2 userinfo 接口返回空响应");
                return cacheInvalidToken(accessToken, "空响应");
            }

            JsonNode root = objectMapper.readTree(resultJson);
            int code = root.path("code").asInt(0);

            if (code != 1) {
                String message = root.path("message").asText("认证失败");
                log.warn("OAuth2 userinfo 接口返回错误: code={}, message={}", code, message);
                return cacheInvalidToken(accessToken, message);
            }

            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                log.warn("OAuth2 userinfo 接口返回数据为空");
                return cacheInvalidToken(accessToken, "数据为空");
            }

            // 提取用户信息
            Long userId = data.hasNonNull("id") ? data.path("id").asLong() : null;
            String username = data.path("username").asText("");
            String email = data.path("email").asText("");
            String role = data.path("role").asText("");

            if (userId == null) {
                log.warn("OAuth2 userinfo 接口返回的用户ID为空");
                return cacheInvalidToken(accessToken, "用户ID为空");
            }

            // 缓存有效用户信息
            CachedUserInfo userInfo = CachedUserInfo.valid(userId, username, email, role);
            String cacheKey = CACHE_PREFIX + accessToken;
            cacheUtils.saveToCache(cacheKey, userInfo, userinfoCacheTtl);

            log.info("成功获取并缓存用户信息: userId={}, username={}, role={}",
                    userId, username, role);

            return userInfo;

        } catch (ResourceAccessException e) {
            // 网络超时或连接失败
            log.error("调用 OAuth2 userinfo 接口超时或连接失败: {}", e.getMessage());
            return cacheInvalidToken(accessToken, "网络超时");

        } catch (RestClientResponseException e) {
            // HTTP错误状态码
            int statusCode = e.getStatusCode().value();
            String responseBody = e.getResponseBodyAsString();
            log.error("调用 OAuth2 userinfo 接口失败: status={}, response={}",
                    statusCode, responseBody);

            if (statusCode == 401 || statusCode == 403) {
                // 认证失败，缓存较长时间
                return cacheInvalidToken(accessToken, "认证失败");
            } else {
                // 其他错误，缓存较短时间
                return cacheInvalidToken(accessToken, "服务错误:" + statusCode);
            }

        } catch (Exception e) {
            // 其他异常
            log.error("处理 OAuth2 userinfo 时发生异常", e);
            return cacheInvalidToken(accessToken, "处理异常");
        }
    }

    /**
     * 缓存无效令牌信息
     *
     * @param accessToken  访问令牌
     * @param errorMessage 错误信息
     * @return 无效用户信息
     */
    private CachedUserInfo cacheInvalidToken(String accessToken, String errorMessage) {
        CachedUserInfo invalidInfo = CachedUserInfo.invalid(errorMessage);
        String negativeCacheKey = NEGATIVE_CACHE_PREFIX + accessToken;

        // 缓存无效令牌信息，避免频繁调用远程接口
        cacheUtils.saveToCache(negativeCacheKey, invalidInfo, negativeCacheTtl);

        log.debug("缓存无效令牌信息: reason={}", errorMessage);
        return invalidInfo;
    }

    /**
     * 强制刷新用户信息缓存
     *
     * @param accessToken 访问令牌
     * @return 用户信息
     */
    public CachedUserInfo refreshUserInfo(String accessToken) {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            return null;
        }

        // 清除现有缓存
        evictCache(accessToken);

        // 重新获取并缓存
        return fetchAndCacheUserInfo(accessToken);
    }

    /**
     * 清除指定令牌的缓存
     *
     * @param accessToken 访问令牌
     */
    public void evictCache(String accessToken) {
        if (accessToken != null) {
            String cacheKey = CACHE_PREFIX + accessToken;
            String negativeCacheKey = NEGATIVE_CACHE_PREFIX + accessToken;

            cacheUtils.deleteCache(cacheKey);
            cacheUtils.deleteCache(negativeCacheKey);

            log.debug("清除令牌缓存: {}", accessToken.substring(0, Math.min(8, accessToken.length())) + "...");
        }
    }

    /**
     * 获取缓存统计信息（用于监控）
     *
     * @return 缓存统计
     */
    public CacheStats getCacheStats() {
        // 可以扩展实现缓存命中率等统计
        CacheStats stats = new CacheStats();
        stats.setCacheTtl(userinfoCacheTtl);
        stats.setNegativeCacheTtl(negativeCacheTtl);
        return stats;
    }

    /**
     * 用户信息缓存实体
     */
    @Data
    public static class CachedUserInfo implements Serializable {
        private Long userId;
        private String username;
        private String email;
        private String role;
        private boolean valid;
        private Date cacheTime;
        private String errorMessage;

        // 构造有效用户信息
        public static CachedUserInfo valid(Long userId, String username, String email, String role) {
            CachedUserInfo info = new CachedUserInfo();
            info.setUserId(userId);
            info.setUsername(username);
            info.setEmail(email);
            info.setRole(role);
            info.setValid(true);
            info.setCacheTime(new Date());
            return info;
        }

        // 构造无效结果
        public static CachedUserInfo invalid(String errorMessage) {
            CachedUserInfo info = new CachedUserInfo();
            info.setValid(false);
            info.setErrorMessage(errorMessage);
            info.setCacheTime(new Date());
            return info;
        }
    }

    /**
     * 缓存统计信息
     */
    @Data
    public static class CacheStats {
        private long cacheTtl;
        private long negativeCacheTtl;
        // 可以扩展更多统计字段
    }
}