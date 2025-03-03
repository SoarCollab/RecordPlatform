package cn.flying.common.util;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 缓存工具类，用于缓存一些数据，减少数据库访问次数，提高性能
 */
@Component
public class CacheUtils {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    public <T> T takeFormCache(String key, Class<T> dataType){
        String s = stringRedisTemplate.opsForValue().get(key);
        if(s == null) return null;
        try {
            return objectMapper.readValue(s, dataType);
        } catch (Exception e) {
            return null;
        }
    }

    public <T> List<T> takeListFormCache(String key, Class<T> itemType){
        String s = stringRedisTemplate.opsForValue().get(key);
        if(s == null) return null;
        try {
            JavaType type = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, itemType);
            return objectMapper.readValue(s, type);
        } catch (Exception e) {
            return null;
        }
    }

    public <T> void saveToCache(String key, T data, long expire) {
        try {
            String json = objectMapper.writeValueAsString(data);
            stringRedisTemplate.opsForValue().set(key, json, expire, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    public void deleteCachePattern(String key){
        Set<String> keys = Optional.of(stringRedisTemplate.keys(key)).orElse(Collections.emptySet());
        stringRedisTemplate.delete(keys);
    }
    public void deleteCache(String key){
        stringRedisTemplate.delete(key);
    }
}
