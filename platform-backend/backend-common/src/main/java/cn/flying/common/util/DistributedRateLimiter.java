package cn.flying.common.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 分布式限流器。
 * 基于 Redis Lua 脚本实现的滑动窗口限流，支持多实例部署。
 * <p>
 * 实现原理：
 * 1. 使用 INCR 原子递增计数器
 * 2. 首次访问时设置过期时间（窗口期）
 * 3. 超过阈值后设置封禁 key
 * 4. 所有操作在单个 Lua 脚本中原子执行
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedRateLimiter {

    /**
     * 滑动窗口限流 + 封禁 Lua 脚本。
     * <p>
     * KEYS[1]: 计数器 key
     * KEYS[2]: 封禁 key
     * ARGV[1]: 窗口期限制次数
     * ARGV[2]: 窗口期（秒）
     * ARGV[3]: 封禁时间（秒）
     * <p>
     * 返回值：
     * 1 = 允许访问
     * 0 = 被限流（在窗口期内超过限制）
     * -1 = 被封禁（已在封禁列表中）
     */
    private static final String RATE_LIMIT_LUA_SCRIPT = """
            -- Check if already blocked
            if redis.call('EXISTS', KEYS[2]) == 1 then
                return -1
            end
            
            local limit = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local blockTime = tonumber(ARGV[3])
            
            -- Increment counter
            local current = redis.call('INCR', KEYS[1])
            
            -- Set expiration on first request
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], window)
            end
            
            -- Check if over limit
            if current > limit then
                -- Set block key
                redis.call('SETEX', KEYS[2], blockTime, '1')
                return 0
            end
            
            return 1
            """;
    /**
     * 简单限流 Lua 脚本（无封禁）。
     * <p>
     * KEYS[1]: 计数器 key
     * ARGV[1]: 窗口期限制次数
     * ARGV[2]: 窗口期（秒）
     * <p>
     * 返回值：
     * 1 = 允许访问
     * 0 = 被限流
     */
    private static final String SIMPLE_RATE_LIMIT_LUA_SCRIPT = """
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            
            local current = redis.call('INCR', key)
            
            if current == 1 then
                redis.call('EXPIRE', key, window)
            end
            
            if current > limit then
                return 0
            end
            
            return 1
            """;
    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> rateLimitScript = new DefaultRedisScript<>(RATE_LIMIT_LUA_SCRIPT, Long.class);
    private final RedisScript<Long> simpleRateLimitScript = new DefaultRedisScript<>(SIMPLE_RATE_LIMIT_LUA_SCRIPT, Long.class);

    /**
     * 执行限流检查（带封禁）。
     *
     * @param counterKey    计数器 key
     * @param blockKey      封禁 key
     * @param limit         窗口期内最大请求数
     * @param windowSeconds 窗口期（秒）
     * @param blockSeconds  超限后封禁时间（秒）
     * @return 限流结果
     */
    public RateLimitResult tryAcquireWithBlock(String counterKey, String blockKey,
                                               int limit, int windowSeconds, int blockSeconds) {
        try {
            List<String> keys = Arrays.asList(counterKey, blockKey);
            Long result = redisTemplate.execute(
                    rateLimitScript,
                    keys,
                    String.valueOf(limit),
                    String.valueOf(windowSeconds),
                    String.valueOf(blockSeconds)
            );

            return switch (result.intValue()) {
                case 1 -> RateLimitResult.ALLOWED;
                case 0 -> RateLimitResult.RATE_LIMITED;
                case -1 -> RateLimitResult.BLOCKED;
                default -> {
                    log.warn("Unexpected rate limit result: {}", result);
                    yield RateLimitResult.ALLOWED;
                }
            };
        } catch (Exception e) {
            log.error("Rate limit check failed: {}", e.getMessage(), e);
            // Redis 故障时放行，避免服务不可用
            return RateLimitResult.ALLOWED;
        }
    }

    /**
     * 执行简单限流检查（无封禁）。
     *
     * @param counterKey    计数器 key
     * @param limit         窗口期内最大请求数
     * @param windowSeconds 窗口期（秒）
     * @return true=允许，false=限流
     */
    public boolean tryAcquire(String counterKey, int limit, int windowSeconds) {
        try {
            List<String> keys = List.of(counterKey);
            Long result = redisTemplate.execute(
                    simpleRateLimitScript,
                    keys,
                    String.valueOf(limit),
                    String.valueOf(windowSeconds)
            );

            return result == 1L;
        } catch (Exception e) {
            log.error("Rate limit check failed: {}", e.getMessage(), e);
            return true; // Redis 故障时放行
        }
    }

    /**
     * 检查是否被封禁。
     *
     * @param blockKey 封禁 key
     * @return true=被封禁
     */
    public boolean isBlocked(String blockKey) {
        try {
            return redisTemplate.hasKey(blockKey);
        } catch (Exception e) {
            log.error("Block check failed: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 限流结果枚举
     */
    public enum RateLimitResult {
        /**
         * 允许访问
         */
        ALLOWED,
        /**
         * 被限流（窗口期内超限）
         */
        RATE_LIMITED,
        /**
         * 被封禁（已在封禁列表中）
         */
        BLOCKED
    }
}
