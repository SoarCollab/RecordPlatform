package cn.flying.identity.service.impl;

import cn.flying.identity.service.JwtBlacklistService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * JWT 黑名单服务实现
 * 使用 Redis 存储已注销（或被拉黑）的 Token，结合无状态 JWT 模式实现服务端主动失效。
 * 当 Redis 临时不可用时，退化到本地内存黑名单以保持安全性。
 */
@Slf4j
@Service
public class JwtBlacklistServiceImpl implements JwtBlacklistService {

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    // redis 前缀与默认过期时间，从配置读取
    @Value("${redis.prefix.jwt.blacklist:identity:jwt:blacklist:}")
    private String jwtBlacklistPrefix;

    @Value("${cache.expire.jwt.blacklist:7200}")
    private long jwtBlacklistTtlSeconds;

    /**
     * 本地黑名单，value 为过期时间戳（毫秒），Long.MAX_VALUE 表示永久封禁
     */
    private final Map<String, Long> localBlacklist = new ConcurrentHashMap<>();

    @Override
    public void blacklistToken(String token, long ttlSeconds) {
        if (token == null || token.isBlank()) {
            return;
        }

        String key = jwtBlacklistPrefix + token;
        long effectiveTtl = resolveTtl(ttlSeconds);

        try {
            if (ttlSeconds == 0) {
                redisTemplate.opsForValue().set(key, "1");
            } else {
                long ttl = effectiveTtl;
                redisTemplate.opsForValue().set(key, "1", ttl, TimeUnit.SECONDS);
            }
            // 加入本地缓存用于 Redis 可用但下游查询失败时的冗余校验
            storeLocal(token, effectiveTtl, ttlSeconds == 0);
        } catch (DataAccessException ex) {
            log.warn("Redis 不可用，使用本地黑名单降级: {}", ex.getMessage());
            storeLocal(token, effectiveTtl, ttlSeconds == 0);
        }
    }

    @Override
    public boolean isBlacklisted(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }

        cleanupLocal();
        String key = jwtBlacklistPrefix + token;

        try {
            Boolean hasKey = redisTemplate.hasKey(key);
            if (Boolean.TRUE.equals(hasKey)) {
                return true;
            }
        } catch (DataAccessException ex) {
            log.warn("Redis 检查黑名单失败，使用本地缓存: {}", ex.getMessage());
        }

        Long expiresAt = localBlacklist.get(token);
        return expiresAt != null && expiresAt >= Instant.now().toEpochMilli();
    }

    private long resolveTtl(long ttlSeconds) {
        if (ttlSeconds == 0) {
            return 0;
        }
        if (ttlSeconds > 0) {
            return ttlSeconds;
        }
        // 负值或未指定时回退至配置值
        return jwtBlacklistTtlSeconds > 0 ? jwtBlacklistTtlSeconds : TimeUnit.HOURS.toSeconds(2);
    }

    private void storeLocal(String token, long ttlSeconds, boolean permanent) {
        long expiresAt;
        if (permanent) {
            expiresAt = Long.MAX_VALUE;
        } else {
            long durationMillis = TimeUnit.SECONDS.toMillis(ttlSeconds);
            expiresAt = Instant.now().toEpochMilli() + durationMillis;
        }
        localBlacklist.put(token, expiresAt);
    }

    private void cleanupLocal() {
        long now = Instant.now().toEpochMilli();
        localBlacklist.entrySet().removeIf(entry -> entry.getValue() < now);
    }
}
