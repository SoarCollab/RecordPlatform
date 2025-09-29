package cn.flying.identity.gateway.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * API网关多级缓存管理器
 * 实现L1（本地缓存）+ L2（Redis缓存）的多级缓存架构
 * <p>
 * 核心功能：
 * 1. 多级缓存管理（L1: Caffeine, L2: Redis）
 * 2. 缓存预热支持
 * 3. 缓存统计和监控
 * 4. 自动缓存失效和刷新
 * 5. 缓存穿透保护
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
@Component
public class ApiGatewayCacheManager {

    /**
     * JSON序列化器
     */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /**
     * 通用缓存映射
     */
    private final Map<String, Cache<String, Object>> cacheMap = new ConcurrentHashMap<>();
    /**
     * 缓存命中率统计
     */
    private final Map<String, CacheStatistics> statisticsMap = new ConcurrentHashMap<>();
    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    /**
     * L1缓存最大条目数
     */
    @Value("${api.gateway.cache.l1.max-size:10000}")
    private long l1MaxSize;

    /**
     * L1缓存过期时间（秒）
     */
    @Value("${api.gateway.cache.l1.expire-seconds:60}")
    private long l1ExpireSeconds;

    /**
     * L2缓存过期时间（秒）
     */
    @Value("${api.gateway.cache.l2.expire-seconds:3600}")
    private long l2ExpireSeconds;

    /**
     * 是否启用缓存统计
     */
    @Value("${api.gateway.cache.enable-stats:true}")
    private boolean enableStats;

    /**
     * 路由缓存
     */
    private Cache<String, Object> routeCache;

    /**
     * API密钥缓存
     */
    private Cache<String, Object> apiKeyCache;

    /**
     * 权限缓存
     */
    private Cache<String, Object> permissionCache;

    /**
     * 初始化缓存
     */
    @PostConstruct
    public void init() {
        log.info("初始化API网关多级缓存管理器...");

        // 创建路由缓存
        routeCache = createCache("route", l1MaxSize * 2, l1ExpireSeconds);
        cacheMap.put("route", routeCache);

        // 创建API密钥缓存
        apiKeyCache = createCache("apiKey", l1MaxSize, l1ExpireSeconds * 2);
        cacheMap.put("apiKey", apiKeyCache);

        // 创建权限缓存
        permissionCache = createCache("permission", l1MaxSize, l1ExpireSeconds);
        cacheMap.put("permission", permissionCache);

        log.info("API网关多级缓存管理器初始化完成");
    }

    /**
     * 创建缓存实例
     *
     * @param name          缓存名称
     * @param maxSize       最大条目数
     * @param expireSeconds 过期时间（秒）
     * @return 缓存实例
     */
    private Cache<String, Object> createCache(String name, long maxSize, long expireSeconds) {
        Caffeine<Object, Object> builder = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(expireSeconds, TimeUnit.SECONDS);

        if (enableStats) {
            builder.recordStats();
            statisticsMap.put(name, new CacheStatistics(name));
        }

        log.info("创建缓存: name={}, maxSize={}, expireSeconds={}", name, maxSize, expireSeconds);
        return builder.build();
    }

    /**
     * 获取缓存值（简化版，不需要loader）
     */
    public Object get(String cacheKey) {
        // 解析缓存键，格式：cacheName:key
        int index = cacheKey.indexOf(':');
        if (index <= 0) {
            return null;
        }
        String cacheName = cacheKey.substring(0, index);
        String key = cacheKey.substring(index + 1);

        Cache<String, Object> cache = cacheMap.get(cacheName);
        if (cache == null) {
            return null;
        }

        // 尝试从L1获取
        Object value = cache.getIfPresent(key);
        if (value != null) {
            return value;
        }

        // 尝试从L2获取
        String redisKey = buildRedisKey(cacheName, key);
        String redisValue = redisTemplate.opsForValue().get(redisKey);
        if (redisValue != null) {
            try {
                return objectMapper.readValue(redisValue, Object.class);
            } catch (Exception e) {
                return redisValue;
            }
        }

        return null;
    }

    /**
     * 构建Redis缓存键
     *
     * @param cacheName 缓存名称
     * @param key       缓存键
     * @return Redis键
     */
    private String buildRedisKey(String cacheName, String key) {
        return "api:gateway:cache:" + cacheName + ":" + key;
    }

    /**
     * 删除缓存值（从多级缓存删除）
     *
     * @param cacheName 缓存名称
     * @param key       缓存键
     */
    public void evict(String cacheName, String key) {
        Cache<String, Object> cache = cacheMap.get(cacheName);
        if (cache != null) {
            cache.invalidate(key);
        }

        String redisKey = buildRedisKey(cacheName, key);
        redisTemplate.delete(redisKey);

        log.debug("缓存删除: cacheName={}, key={}", cacheName, key);
    }

    /**
     * 清空指定缓存
     * 修复：使用SCAN命令替代keys命令，避免阻塞Redis
     *
     * @param cacheName 缓存名称
     */
    public void clear(String cacheName) {
        Cache<String, Object> cache = cacheMap.get(cacheName);
        if (cache != null) {
            cache.invalidateAll();
        }

        // 使用SCAN命令批量删除，避免keys命令阻塞
        String pattern = buildRedisKey(cacheName, "*");
        Set<String> keysToDelete = new HashSet<>();

        // 使用SCAN命令分批获取keys
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            try (Cursor<byte[]> cursor = connection.scan(
                    ScanOptions.scanOptions()
                            .match(pattern)
                            .count(100)
                            .build())) {
                cursor.forEachRemaining(key -> {
                    String keyStr = new String(key, StandardCharsets.UTF_8);
                    keysToDelete.add(keyStr);

                    // 批量删除，每100个key删除一次
                    if (keysToDelete.size() >= 100) {
                        redisTemplate.delete(keysToDelete);
                        keysToDelete.clear();
                    }
                });
            } catch (Exception e) {
                log.error("扫描Redis键失败: pattern={}", pattern, e);
            }

            // 删除剩余的keys
            if (!keysToDelete.isEmpty()) {
                redisTemplate.delete(keysToDelete);
            }

            return null;
        });

        log.info("缓存清空: cacheName={}", cacheName);
    }

    /**
     * 路由缓存快捷方法
     */
    public Object getRoute(String path, String method, Supplier<Object> loader) {
        String key = path + ":" + method;
        return get("route", key, loader);
    }

    /**
     * 获取缓存值（支持多级缓存）
     * 修复了序列化类型不匹配的问题，使用JSON统一序列化
     *
     * @param cacheName 缓存名称
     * @param key       缓存键
     * @param loader    缓存加载器（当缓存未命中时调用）
     * @param <T>       值类型
     * @return 缓存值
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String cacheName, String key, Supplier<T> loader) {
        Cache<String, Object> cache = cacheMap.get(cacheName);
        if (cache == null) {
            log.warn("缓存不存在: cacheName={}", cacheName);
            return loader.get();
        }

        // 1. 尝试从L1缓存获取
        T value = (T) cache.getIfPresent(key);
        if (value != null) {
            recordHit(cacheName, true);
            log.debug("L1缓存命中: cacheName={}, key={}", cacheName, key);
            return value;
        }

        // 2. 尝试从L2缓存（Redis）获取
        String redisKey = buildRedisKey(cacheName, key);
        String redisValue = redisTemplate.opsForValue().get(redisKey);
        if (redisValue != null) {
            recordHit(cacheName, false);
            log.debug("L2缓存命中: cacheName={}, key={}", cacheName, key);

            // JSON反序列化
            try {
                // 尝试解析为JSON对象
                value = (T) objectMapper.readValue(redisValue, Object.class);
            } catch (Exception e) {
                // 如果解析失败，可能是简单字符串，直接返回
                log.debug("JSON反序列化失败，作为字符串处理: {}", e.getMessage());
                value = (T) redisValue;
            }

            // 回填L1缓存
            cache.put(key, value);
            return value;
        }

        // 3. 缓存未命中，从数据源加载
        recordMiss(cacheName);
        log.debug("缓存未命中: cacheName={}, key={}", cacheName, key);

        value = loader.get();
        if (value != null) {
            // 写入多级缓存
            put(cacheName, key, value);
        }

        return value;
    }

    /**
     * 记录缓存命中
     *
     * @param cacheName 缓存名称
     * @param isL1Hit   是否L1命中
     */
    private void recordHit(String cacheName, boolean isL1Hit) {
        CacheStatistics stats = statisticsMap.get(cacheName);
        if (stats != null) {
            if (isL1Hit) {
                stats.recordL1Hit();
            } else {
                stats.recordL2Hit();
            }
        }
    }

    /**
     * 记录缓存未命中
     *
     * @param cacheName 缓存名称
     */
    private void recordMiss(String cacheName) {
        CacheStatistics stats = statisticsMap.get(cacheName);
        if (stats != null) {
            stats.recordMiss();
        }
    }

    /**
     * 设置缓存值（写入多级缓存）
     * 修复了序列化问题，使用JSON序列化
     *
     * @param cacheName 缓存名称
     * @param key       缓存键
     * @param value     缓存值
     */
    public void put(String cacheName, String key, Object value) {
        put(cacheName, key, value, l2ExpireSeconds);
    }

    /**
     * 设置缓存值（写入多级缓存，支持自定义过期时间）
     *
     * @param cacheName     缓存名称
     * @param key           缓存键
     * @param value         缓存值
     * @param expireSeconds 过期时间（秒）
     */
    public void put(String cacheName, String key, Object value, long expireSeconds) {
        Cache<String, Object> cache = cacheMap.get(cacheName);
        if (cache == null) {
            log.warn("缓存不存在: cacheName={}", cacheName);
            return;
        }

        // 1. 写入L1缓存
        cache.put(key, value);

        // 2. 写入L2缓存（Redis）- 使用JSON序列化
        String redisKey = buildRedisKey(cacheName, key);
        try {
            String jsonValue;
            if (value instanceof String) {
                // 字符串直接存储
                jsonValue = (String) value;
            } else {
                // 对象转JSON
                jsonValue = objectMapper.writeValueAsString(value);
            }
            redisTemplate.opsForValue().set(redisKey, jsonValue, expireSeconds, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            log.error("缓存序列化失败: cacheName={}, key={}", cacheName, key, e);
            // 降级处理：使用toString()
            redisTemplate.opsForValue().set(redisKey, value.toString(), expireSeconds, TimeUnit.SECONDS);
        }

        log.debug("缓存写入: cacheName={}, key={}", cacheName, key);
    }

    /**
     * API密钥缓存快捷方法
     */
    public Object getApiKey(String apiKey, Supplier<Object> loader) {
        return get("apiKey", apiKey, loader);
    }

    /**
     * 权限缓存快捷方法
     */
    public Boolean getPermission(Long appId, Long interfaceId, Supplier<Boolean> loader) {
        String key = appId + ":" + interfaceId;
        return get("permission", key, loader);
    }

    /**
     * 缓存预热
     * 优化：使用Redis管道批量写入，提升性能
     *
     * @param cacheName 缓存名称
     * @param data      预热数据
     */
    public void warmup(String cacheName, Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return;
        }

        Cache<String, Object> cache = cacheMap.get(cacheName);
        if (cache == null) {
            log.warn("缓存不存在，无法预热: cacheName={}", cacheName);
            return;
        }

        // 批量写入L1缓存
        cache.putAll(data);

        // 使用Redis管道批量写入L2缓存
        redisTemplate.executePipelined((RedisCallback<Void>) connection -> {
            data.forEach((key, value) -> {
                String redisKey = buildRedisKey(cacheName, key);
                try {
                    String jsonValue;
                    if (value instanceof String) {
                        jsonValue = (String) value;
                    } else {
                        jsonValue = objectMapper.writeValueAsString(value);
                    }
                    connection.stringCommands().setEx(
                            redisKey.getBytes(StandardCharsets.UTF_8),
                            l2ExpireSeconds,
                            jsonValue.getBytes(StandardCharsets.UTF_8)
                    );
                } catch (JsonProcessingException e) {
                    log.error("缓存预热序列化失败: key={}", key, e);
                    // 降级处理
                    connection.stringCommands().setEx(
                            redisKey.getBytes(StandardCharsets.UTF_8),
                            l2ExpireSeconds,
                            value.toString().getBytes(StandardCharsets.UTF_8)
                    );
                }
            });
            return null;
        });

        log.info("缓存预热完成: cacheName={}, size={}", cacheName, data.size());
    }

    /**
     * 获取缓存统计信息
     *
     * @return 统计信息映射
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new ConcurrentHashMap<>();

        cacheMap.forEach((name, cache) -> {
            Map<String, Object> cacheStats = new ConcurrentHashMap<>();
            cacheStats.put("size", cache.estimatedSize());

            if (enableStats) {
                CacheStats caffeineStats = cache.stats();
                cacheStats.put("hitCount", caffeineStats.hitCount());
                cacheStats.put("missCount", caffeineStats.missCount());
                cacheStats.put("hitRate", caffeineStats.hitRate());
                cacheStats.put("evictionCount", caffeineStats.evictionCount());
                cacheStats.put("loadSuccessCount", caffeineStats.loadSuccessCount());
                cacheStats.put("loadFailureCount", caffeineStats.loadFailureCount());
                cacheStats.put("averageLoadPenalty", caffeineStats.averageLoadPenalty());
            }

            // 添加自定义统计
            CacheStatistics customStats = statisticsMap.get(name);
            if (customStats != null) {
                cacheStats.put("l1HitCount", customStats.getL1HitCount());
                cacheStats.put("l2HitCount", customStats.getL2HitCount());
                cacheStats.put("totalMissCount", customStats.getMissCount());
                cacheStats.put("overallHitRate", customStats.getOverallHitRate());
            }

            stats.put(name, cacheStats);
        });

        return stats;
    }

    /**
     * 缓存统计信息内部类
     */
    private static class CacheStatistics {
        @Getter
        private final String cacheName;
        @Getter
        private long l1HitCount = 0;
        @Getter
        private long l2HitCount = 0;
        @Getter
        private long missCount = 0;

        public CacheStatistics(String cacheName) {
            this.cacheName = cacheName;
        }

        public synchronized void recordL1Hit() {
            l1HitCount++;
        }

        public synchronized void recordL2Hit() {
            l2HitCount++;
        }

        public synchronized void recordMiss() {
            missCount++;
        }

        public double getOverallHitRate() {
            long totalHits = l1HitCount + l2HitCount;
            long totalRequests = totalHits + missCount;
            if (totalRequests == 0) {
                return 0;
            }
            return (double) totalHits / totalRequests;
        }
    }
}