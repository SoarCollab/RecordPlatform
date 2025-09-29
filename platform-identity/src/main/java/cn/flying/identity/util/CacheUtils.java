package cn.flying.identity.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 缓存工具类
 * 提供统一的缓存操作方法，简化Redis使用并提供一致的错误处理
 * 
 * @author 王贝强
 */
@Slf4j
@Component
public class CacheUtils {
    
    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;
    
    /**
     * 获取缓存值，如果不存在则从数据源获取并缓存
     * 
     * @param key 缓存键
     * @param dataSupplier 数据提供者
     * @param timeout 过期时间
     * @param timeUnit 时间单位
     * @return 缓存值
     */
    public String getOrSet(String key, Supplier<String> dataSupplier, long timeout, TimeUnit timeUnit) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                return value;
            }

            // 从数据源获取
            value = dataSupplier.get();
            if (value != null) {
                try {
                    redisTemplate.opsForValue().set(key, value, timeout, timeUnit);
                } catch (Exception e) {
                    log.error("设置缓存失败, key: {}, 数据已从数据源获取", key, e);
                    // 设置缓存失败，但数据已从数据源获取，直接返回
                }
            }
            return value;
        } catch (Exception e) {
            log.error("缓存操作失败, key: {}", key, e);
            // 缓存读取失败时直接从数据源获取
            return dataSupplier.get();
        }
    }
    
    /**
     * 设置缓存
     * 
     * @param key 缓存键
     * @param value 缓存值
     * @param timeout 过期时间
     * @param timeUnit 时间单位
     */
    public void set(String key, String value, long timeout, TimeUnit timeUnit) {
        try {
            redisTemplate.opsForValue().set(key, value, timeout, timeUnit);
        } catch (Exception e) {
            log.error("设置缓存失败, key: {}, value: {}", key, value, e);
        }
    }
    
    /**
     * 获取缓存值
     * 
     * @param key 缓存键
     * @return 缓存值
     */
    public String get(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error("获取缓存失败, key: {}", key, e);
            return null;
        }
    }
    
    /**
     * 删除缓存
     * 
     * @param key 缓存键
     */
    public void delete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("删除缓存失败, key: {}", key, e);
        }
    }
    
    /**
     * 检查缓存是否存在
     * 
     * @param key 缓存键
     * @return 是否存在
     */
    public boolean exists(String key) {
        try {
            return redisTemplate.hasKey(key);
        } catch (Exception e) {
            log.error("检查缓存存在性失败, key: {}", key, e);
            return false;
        }
    }
    
    /**
     * 递增操作
     * 
     * @param key 缓存键
     * @param delta 递增值
     * @return 递增后的值
     */
    public Long increment(String key, long delta) {
        try {
            return redisTemplate.opsForValue().increment(key, delta);
        } catch (Exception e) {
            log.error("缓存递增操作失败, key: {}, delta: {}", key, delta, e);
            return null;
        }
    }
    
    /**
     * 设置过期时间
     * 
     * @param key 缓存键
     * @param timeout 过期时间
     * @param timeUnit 时间单位
     */
    public void expire(String key, long timeout, TimeUnit timeUnit) {
        try {
            redisTemplate.expire(key, timeout, timeUnit);
        } catch (Exception e) {
            log.error("设置缓存过期时间失败, key: {}", key, e);
        }
    }
    
    /**
     * 获取剩余过期时间
     * 
     * @param key 缓存键
     * @param timeUnit 时间单位
     * @return 剩余过期时间
     */
    public Long getExpire(String key, TimeUnit timeUnit) {
        try {
            return redisTemplate.getExpire(key, timeUnit);
        } catch (Exception e) {
            log.error("获取缓存过期时间失败, key: {}", key, e);
            return null;
        }
    }
}
