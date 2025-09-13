package cn.flying.identity.service.impl;

import cn.flying.identity.service.JwtBlacklistService;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * JWT 黑名单服务实现
 * 使用 Redis 存储已注销（或被拉黑）的 Token，结合无状态 JWT 模式实现服务端主动失效。
 */
@Service
public class JwtBlacklistServiceImpl implements JwtBlacklistService {

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    // redis 前缀与默认过期时间，从 application-redis.yml 读取
    @Value("${redis.prefix.jwt.blacklist:identity:jwt:blacklist:}")
    private String jwtBlacklistPrefix;

    @Value("${cache.expire.jwt.blacklist:7200}")
    private long jwtBlacklistTtlSeconds;

    @Override
    public void blacklistToken(String token, long ttlSeconds) {
        if (token == null || token.isBlank()) {
            return;
        }
        String key = jwtBlacklistPrefix + token;
        long ttl = ttlSeconds > 0 ? ttlSeconds : jwtBlacklistTtlSeconds;
        redisTemplate.opsForValue().set(key, "1", ttl, TimeUnit.SECONDS);
    }

    @Override
    public boolean isBlacklisted(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String key = jwtBlacklistPrefix + token;
        return redisTemplate.hasKey(key);
    }
}
