package cn.flying.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 缓存工具类，用于缓存一些数据，减少数据库访问次数，提高性能
 */
@Slf4j
@Component
@Getter
public class CacheUtils {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    private static final String BYTES_PREFIX = "__BYTES__:";

    // ===== 基本操作 =====

    /**
     * 从缓存中获取数据并转换为指定类型
     */
    public <T> T takeFormCache(String key, Class<T> dataType){
        String s = stringRedisTemplate.opsForValue().get(key);
        if(s == null) return null;
        try {
            // 使用 JsonConverter 进行反序列化
            return JsonConverter.parse(s, dataType);
        } catch (Exception e) {
            log.error("缓存反序列化失败 (key: {}): {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 从缓存中获取列表数据并转换为指定类型列表
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> takeListFormCache(String key, Class<T> itemType){
        String s = stringRedisTemplate.opsForValue().get(key);
        if(s == null) return null;
        try {
            // 使用 JsonConverter 进行列表反序列化
            return JsonConverter.parse(s, List.class, itemType);
        } catch (Exception e) {
            log.error("缓存列表反序列化失败 (key: {}): {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 保存数据到缓存
     */
    public <T> void saveToCache(String key, T data, long expire) {
        try {
            // 使用 JsonConverter 进行序列化
            String json = JsonConverter.toJson(data);
            if (json != null) { // JsonConverter.toJson 可能返回 null
                stringRedisTemplate.opsForValue().set(key, json, expire, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.error("保存到缓存失败 (key: {}): {}", key, e.getMessage());
        }
    }

    /**
     * 保存数据到缓存，无过期时间
     */
    public <T> void saveToCache(String key, T data) {
        try {
            // 使用 JsonConverter 进行序列化
            String json = JsonConverter.toJson(data);
            if (json != null) { // JsonConverter.toJson 可能返回 null
                stringRedisTemplate.opsForValue().set(key, json);
            }
        } catch (Exception e) {
            log.error("保存到缓存失败 (key: {}): {}", key, e.getMessage());
        }
    }

    /**
     * 删除匹配模式的缓存
     */
    public void deleteCachePattern(String key){
        Set<String> keys = Optional.of(stringRedisTemplate.keys(key)).orElse(Collections.emptySet());
        stringRedisTemplate.delete(keys);
    }

    /**
     * 删除指定key的缓存
     */
    public void deleteCache(String key){
        stringRedisTemplate.delete(key);
    }

    /**
     * 设置key的过期时间
     */
    public void setExpire(String key, long expire, TimeUnit timeUnit) {
        stringRedisTemplate.expire(key, expire, timeUnit);
    }

    /**
     * 判断key是否存在
     */
    public boolean hasKey(String key) {
        return stringRedisTemplate.hasKey(key);
    }

    // ===== Hash操作 =====

    /**
     * 保存哈希表字段
     */
    public <T> void hashPut(String key, String hashKey, T value) {
        try {
            HashOperations<String, String, String> hashOps = stringRedisTemplate.opsForHash();
            if (value instanceof byte[] byteArray) {
                // 特殊处理字节数组，因为无法直接JSON序列化
                String base64Value = BYTES_PREFIX + Base64.getEncoder().encodeToString(byteArray);
                hashOps.put(key, hashKey, base64Value);
            } else {
                // 使用 JsonConverter 进行序列化
                String json = JsonConverter.toJson(value);
                if (json != null) { // JsonConverter.toJson 可能返回 null
                    hashOps.put(key, hashKey, json);
                }
            }
        } catch (Exception e) {
            // 记录日志但不抛出异常
            log.error("保存哈希表字段时发生异常", e);
        }
    }

    /**
     * 获取哈希表字段值
     */
    @SuppressWarnings("unchecked")
    public <T> T hashGet(String key, String hashKey, Class<T> type) {
        HashOperations<String, String, String> hashOps = stringRedisTemplate.opsForHash();
        String value = hashOps.get(key, hashKey);
        if (value == null) return null;

        try {
            if (value.startsWith(BYTES_PREFIX) && type.equals(byte[].class)) {
                // 处理字节数组的特殊情况
                String base64 = value.substring(BYTES_PREFIX.length());
                return (T) Base64.getDecoder().decode(base64);
            }
            // 使用 JsonConverter 进行反序列化
            return JsonConverter.parse(value, type);
        } catch (Exception e) {
            log.error("获取哈希表字段并反序列化失败 (key: {}, hashKey: {}): {}", key, hashKey, e.getMessage());
            return null;
        }
    }

    /**
     * 获取哈希表所有字段
     */
    public Map<Object, Object> hashGetAll(String key) {
        return stringRedisTemplate.opsForHash().entries(key);
    }

    /**
     * 删除哈希表字段
     */
    public void hashDelete(String key, String... hashKeys) {
        stringRedisTemplate.opsForHash().delete(key, (Object[]) hashKeys);
    }

    /**
     * 判断哈希表字段是否存在
     */
    public boolean hashHasKey(String key, String hashKey) {
        return stringRedisTemplate.opsForHash().hasKey(key, hashKey);
    }

    /**
     * 获取哈希表中的所有字段名
     */
    public Set<Object> hashKeys(String key) {
        return stringRedisTemplate.opsForHash().keys(key);
    }

    /**
     * 将整个Map放入哈希表
     */
    public <T> void hashPutAll(String key, Map<String, T> map) {
        if (map == null || map.isEmpty()) return;

        try {
            Map<String, String> stringMap = new HashMap<>(map.size());
            for (Map.Entry<String, T> entry : map.entrySet()) {
                if (entry.getValue() instanceof byte[] byteArray) {
                    String base64Value = BYTES_PREFIX + Base64.getEncoder().encodeToString(byteArray);
                    stringMap.put(entry.getKey(), base64Value);
                } else {
                    // 使用 JsonConverter 进行序列化
                    String jsonValue = JsonConverter.toJson(entry.getValue());
                    if (jsonValue != null) { // JsonConverter.toJson 可能返回 null
                        stringMap.put(entry.getKey(), jsonValue);
                    }
                }
            }
            stringRedisTemplate.opsForHash().putAll(key, stringMap);
        } catch (Exception e) {
            // 记录日志但不抛出异常
            log.error("保存哈希表字段时发生异常", e);
        }
    }

    // ===== Set操作 =====

    /**
     * 向集合添加元素
     */
    public void setAdd(String key, String... values) {
        stringRedisTemplate.opsForSet().add(key, values);
    }

    /**
     * 从集合移除元素
     */
    public void setRemove(String key, Object... values) {
        stringRedisTemplate.opsForSet().remove(key, values);
    }

    /**
     * 获取集合所有元素
     */
    public Set<String> setMembers(String key) {
        return stringRedisTemplate.opsForSet().members(key);
    }

    /**
     * 判断元素是否在集合中
     */
    public boolean setIsMember(String key, Object value) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(key, value));
    }

    /**
     * 获取集合大小
     */
    public long setSize(String key) {
        Long size = stringRedisTemplate.opsForSet().size(key);
        return size != null ? size : 0;
    }

    /**
     * 向集合添加整数元素
     */
    public void setAddIntegers(String key, Set<Integer> intSet) {
        if (intSet == null || intSet.isEmpty()) return;
        String[] values = intSet.stream()
                .map(String::valueOf)
                .toArray(String[]::new);
        setAdd(key, values);
    }

    /**
     * 获取集合中的整数元素
     */
    public Set<Integer> getIntegerSet(String key) {
        Set<String> stringSet = setMembers(key);
        if (stringSet == null || stringSet.isEmpty()) return new HashSet<>();

        return stringSet.stream()
                .map(Integer::valueOf)
                .collect(Collectors.toSet());
    }

    // ===== 原子操作 (Lua Script) =====

    /**
     * 原子性地向 Set 添加元素并向 Hash 添加字段
     * 使用 Lua 脚本确保操作原子性，避免高并发下的数据不一致
     *
     * @param setKey      Set 的键
     * @param setValue    要添加到 Set 的值
     * @param hashKey     Hash 的键
     * @param hashField   Hash 的字段名
     * @param hashValue   Hash 的字段值
     * @return 执行是否成功
     */
    public boolean atomicAddToSetAndHash(String setKey, String setValue,
                                         String hashKey, String hashField, String hashValue) {
        String luaScript = """
                redis.call('SADD', KEYS[1], ARGV[1])
                redis.call('HSET', KEYS[2], ARGV[2], ARGV[3])
                return 1
                """;

        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(luaScript, Long.class);
            Long result = stringRedisTemplate.execute(script,
                    List.of(setKey, hashKey),
                    setValue, hashField, hashValue);
            return result != null && result == 1;
        } catch (Exception e) {
            log.error("原子操作失败 (setKey: {}, hashKey: {}): {}", setKey, hashKey, e.getMessage());
            return false;
        }
    }

    /**
     * 原子性地向 Set 添加元素、向 Hash 添加字段，并更新另一个 Hash 中的时间戳
     * 适用于需要同时更新上传状态和最后活动时间的场景
     *
     * @param setKey          Set 的键
     * @param setValue        要添加到 Set 的值
     * @param hashKey         Hash 的键
     * @param hashField       Hash 的字段名
     * @param hashValue       Hash 的字段值
     * @param stateKey        状态 Hash 的键
     * @param timestampField  时间戳字段名
     * @param timestamp       时间戳值
     * @return 执行是否成功
     */
    public boolean atomicAddChunkWithTimestamp(String setKey, String setValue,
                                                String hashKey, String hashField, String hashValue,
                                                String stateKey, String timestampField, String timestamp) {
        String luaScript = """
                redis.call('SADD', KEYS[1], ARGV[1])
                redis.call('HSET', KEYS[2], ARGV[2], ARGV[3])
                redis.call('HSET', KEYS[3], ARGV[4], ARGV[5])
                return 1
                """;

        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(luaScript, Long.class);
            Long result = stringRedisTemplate.execute(script,
                    List.of(setKey, hashKey, stateKey),
                    setValue, hashField, hashValue, timestampField, timestamp);
            return result != null && result == 1;
        } catch (Exception e) {
            log.error("原子操作失败: {}", e.getMessage());
            return false;
        }
    }
}
