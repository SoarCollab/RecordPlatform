package cn.flying.monitor.server.service;

import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * OAuth2 Token管理服务
 * 用于管理从platform-identity获取的OAuth2令牌
 */
@Slf4j
@Service
public class OAuthTokenService {

    private static final String TOKEN_KEY_PREFIX = "oauth:token:";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private OAuth2AuthorizedClientService authorizedClientService;

    /**
     * 从OAuth2认证令牌中提取access token
     *
     * @param authentication OAuth2认证令牌
     * @return access token值
     */
    public String extractAccessToken(OAuth2AuthenticationToken authentication) {
        String clientRegistrationId = authentication.getAuthorizedClientRegistrationId();
        String principalName = authentication.getName();

        OAuth2AuthorizedClient client = authorizedClientService
                .loadAuthorizedClient(clientRegistrationId, principalName);

        if (client != null && client.getAccessToken() != null) {
            return client.getAccessToken().getTokenValue();
        }

        log.warn("无法提取access token，clientRegistrationId={}, principalName={}",
                clientRegistrationId, principalName);
        return null;
    }

    /**
     * 保存token信息到Redis
     *
     * @param userId         monitor系统的用户ID
     * @param authentication OAuth2认证令牌
     */
    public void saveTokenInfo(Integer userId, OAuth2AuthenticationToken authentication) {
        String clientRegistrationId = authentication.getAuthorizedClientRegistrationId();
        String principalName = authentication.getName();

        OAuth2AuthorizedClient client = authorizedClientService
                .loadAuthorizedClient(clientRegistrationId, principalName);

        if (client == null) {
            log.warn("无法保存token信息：client为null，userId={}", userId);
            return;
        }

        OAuth2AccessToken accessToken = client.getAccessToken();
        OAuth2RefreshToken refreshToken = client.getRefreshToken();

        if (accessToken == null) {
            log.warn("无法保存token信息：accessToken为null，userId={}", userId);
            return;
        }

        TokenInfo tokenInfo = new TokenInfo();
        tokenInfo.setUserId(userId);
        tokenInfo.setAccessToken(accessToken.getTokenValue());
        tokenInfo.setAccessTokenExpireTime(accessToken.getExpiresAt());

        if (refreshToken != null) {
            tokenInfo.setRefreshToken(refreshToken.getTokenValue());
            tokenInfo.setRefreshTokenExpireTime(refreshToken.getExpiresAt());
        }

        tokenInfo.setCreateTime(Instant.now());

        String key = TOKEN_KEY_PREFIX + userId;
        String value = JSON.toJSONString(tokenInfo);

        // 设置过期时间为refresh token的有效期（如果有），否则为access token的有效期
        long expiresIn = getTokenExpiresIn(tokenInfo);
        if (expiresIn > 0) {
            stringRedisTemplate.opsForValue().set(key, value, expiresIn, TimeUnit.SECONDS);
            log.info("保存OAuth token到Redis成功，userId={}, expiresIn={}秒", userId, expiresIn);
        } else {
            stringRedisTemplate.opsForValue().set(key, value);
            log.info("保存OAuth token到Redis成功（无过期时间），userId={}", userId);
        }
    }

    /**
     * 计算token的过期时间（秒）
     *
     * @param tokenInfo token信息
     * @return 过期时间（秒），如果无法计算返回-1
     */
    private long getTokenExpiresIn(TokenInfo tokenInfo) {
        Instant expireTime = null;

        // 优先使用refresh token的过期时间（更长）
        if (tokenInfo.getRefreshTokenExpireTime() != null) {
            expireTime = tokenInfo.getRefreshTokenExpireTime();
        } else if (tokenInfo.getAccessTokenExpireTime() != null) {
            expireTime = tokenInfo.getAccessTokenExpireTime();
        }

        if (expireTime == null) {
            return -1;
        }

        Instant now = Instant.now();
        Duration duration = Duration.between(now, expireTime);
        return duration.getSeconds();
    }

    /**
     * 获取用户的access token
     *
     * @param userId monitor系统的用户ID
     * @return access token值，如果不存在返回null
     */
    public String getAccessToken(Integer userId) {
        TokenInfo tokenInfo = getTokenInfo(userId);
        return tokenInfo != null ? tokenInfo.getAccessToken() : null;
    }

    /**
     * 获取用户的token信息
     *
     * @param userId monitor系统的用户ID
     * @return token信息，如果不存在返回null
     */
    public TokenInfo getTokenInfo(Integer userId) {
        String key = TOKEN_KEY_PREFIX + userId;
        String value = stringRedisTemplate.opsForValue().get(key);

        if (value != null) {
            return JSON.parseObject(value, TokenInfo.class);
        }
        return null;
    }

    /**
     * 检查token是否即将过期（5分钟内）
     *
     * @param userId monitor系统的用户ID
     * @return true-即将过期，false-未过期或不存在
     */
    public boolean isTokenExpiringSoon(Integer userId) {
        TokenInfo tokenInfo = getTokenInfo(userId);
        if (tokenInfo == null || tokenInfo.getAccessTokenExpireTime() == null) {
            return false;
        }

        Instant now = Instant.now();
        Instant expireTime = tokenInfo.getAccessTokenExpireTime();
        Duration duration = Duration.between(now, expireTime);

        // 如果5分钟内过期，返回true
        return duration.toMinutes() < 5;
    }

    /**
     * 清除用户的token
     *
     * @param userId monitor系统的用户ID
     */
    public void removeToken(Integer userId) {
        String key = TOKEN_KEY_PREFIX + userId;
        Boolean deleted = stringRedisTemplate.delete(key);
        log.info("清除OAuth token，userId={}, 删除成功={}", userId, deleted);
    }

    /**
     * Token信息数据结构
     */
    @Data
    public static class TokenInfo {
        /**
         * monitor系统的用户ID
         */
        private Integer userId;

        /**
         * 访问令牌
         */
        private String accessToken;

        /**
         * 访问令牌过期时间
         */
        private Instant accessTokenExpireTime;

        /**
         * 刷新令牌
         */
        private String refreshToken;

        /**
         * 刷新令牌过期时间
         */
        private Instant refreshTokenExpireTime;

        /**
         * 创建时间
         */
        private Instant createTime;
    }
}
