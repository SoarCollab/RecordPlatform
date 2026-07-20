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
     * 严格读取关键缓存对象；键缺失返回空，键存在但反序列化失败时直接抛错。
     */
    public <T> T takeFormCacheOrThrow(String key, Class<T> dataType) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        T parsed = JsonConverter.parse(json, dataType);
        if (parsed == null) {
            throw new IllegalStateException("缓存反序列化结果为空: " + key);
        }
        return parsed;
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
     * 使用原子 SET EX 保存关键缓存数据，并将序列化或 Redis 写入失败直接抛给调用方。
     */
    public <T> void saveToCacheOrThrow(String key, T data, long expireSeconds) {
        if (expireSeconds <= 0) {
            throw new IllegalArgumentException("缓存过期时间必须大于零");
        }
        String json = JsonConverter.toJson(data);
        if (json == null) {
            throw new IllegalStateException("缓存数据序列化失败: " + key);
        }
        stringRedisTemplate.opsForValue().set(key, json, expireSeconds, TimeUnit.SECONDS);
    }

    /**
     * 读取缓存键的秒级剩余生存时间，并将 Redis 读取失败直接抛给调用方。
     */
    public long getExpireSecondsOrThrow(String key) {
        Long expireSeconds = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (expireSeconds == null) {
            throw new IllegalStateException("缓存过期时间读取失败: " + key);
        }
        return expireSeconds;
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
     * 设置关键缓存键的过期时间，并在 Redis 未接受设置时失败关闭。
     */
    public void setExpireOrThrow(String key, long expire, TimeUnit timeUnit) {
        if (expire <= 0) {
            throw new IllegalArgumentException("缓存过期时间必须大于零");
        }
        if (!Boolean.TRUE.equals(stringRedisTemplate.expire(key, expire, timeUnit))) {
            throw new IllegalStateException("缓存过期时间设置失败: " + key);
        }
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
     * 保存关键哈希字段，并将序列化或 Redis 写入失败直接抛给调用方。
     */
    public <T> void hashPutOrThrow(String key, String hashKey, T value) {
        HashOperations<String, String, String> hashOps = stringRedisTemplate.opsForHash();
        if (value instanceof byte[] byteArray) {
            String base64Value = BYTES_PREFIX + Base64.getEncoder().encodeToString(byteArray);
            hashOps.put(key, hashKey, base64Value);
            return;
        }

        String json = JsonConverter.toJson(value);
        if (json == null) {
            throw new IllegalStateException("哈希字段序列化失败: " + key + ":" + hashKey);
        }
        hashOps.put(key, hashKey, json);
    }

    /**
     * 仅在字段不存在时保存关键哈希值，并将 Redis 或序列化故障直接抛给调用方。
     */
    public <T> boolean hashPutIfAbsentOrThrow(String key, String hashKey, T value) {
        HashOperations<String, String, String> hashOps = stringRedisTemplate.opsForHash();
        String serialized;
        if (value instanceof byte[] byteArray) {
            serialized = BYTES_PREFIX + Base64.getEncoder().encodeToString(byteArray);
        } else {
            serialized = JsonConverter.toJson(value);
            if (serialized == null) {
                throw new IllegalStateException("哈希字段序列化失败: " + key + ":" + hashKey);
            }
        }
        Boolean inserted = hashOps.putIfAbsent(key, hashKey, serialized);
        if (inserted == null) {
            throw new IllegalStateException("哈希字段条件写入结果为空: " + key + ":" + hashKey);
        }
        return inserted;
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
     * 严格读取关键哈希字段；字段缺失返回空，编码或 JSON 损坏时直接抛错。
     */
    @SuppressWarnings("unchecked")
    public <T> T hashGetOrThrow(String key, String hashKey, Class<T> type) {
        HashOperations<String, String, String> hashOps = stringRedisTemplate.opsForHash();
        String value = hashOps.get(key, hashKey);
        if (value == null) {
            return null;
        }
        if (value.startsWith(BYTES_PREFIX) && type.equals(byte[].class)) {
            return (T) Base64.getDecoder().decode(value.substring(BYTES_PREFIX.length()));
        }
        T parsed = JsonConverter.parse(value, type);
        if (parsed == null) {
            throw new IllegalStateException("哈希字段反序列化结果为空: " + key + ":" + hashKey);
        }
        return parsed;
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
     * 仅当哈希字段仍等于期望字符串值时原子删除，避免并发会话误删新映射。
     */
    public boolean hashDeleteIfValueMatchesOrThrow(
            String key,
            String hashKey,
            String expectedValue
    ) {
        String serializedExpected = JsonConverter.toJson(expectedValue);
        if (serializedExpected == null) {
            throw new IllegalStateException("哈希条件删除期望值序列化失败: " + key + ":" + hashKey);
        }
        String luaScript = """
                if redis.call('HGET', KEYS[1], ARGV[1]) == ARGV[2] then
                    return redis.call('HDEL', KEYS[1], ARGV[1])
                end
                return 0
                """;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(luaScript, Long.class);
        Long result = stringRedisTemplate.execute(
                script,
                List.of(key),
                hashKey,
                serializedExpected);
        if (result == null) {
            throw new IllegalStateException("哈希字段条件删除结果为空: " + key + ":" + hashKey);
        }
        return result > 0;
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

    /**
     * 批量保存关键哈希字段，并将序列化、空写或 Redis 故障直接抛给调用方。
     */
    public <T> void hashPutAllOrThrow(String key, Map<String, T> map) {
        if (map == null || map.isEmpty()) {
            return;
        }

        Map<String, String> stringMap = new HashMap<>(map.size());
        for (Map.Entry<String, T> entry : map.entrySet()) {
            T value = entry.getValue();
            if (value instanceof byte[] byteArray) {
                stringMap.put(
                        entry.getKey(),
                        BYTES_PREFIX + Base64.getEncoder().encodeToString(byteArray));
                continue;
            }
            String jsonValue = JsonConverter.toJson(value);
            if (jsonValue == null) {
                throw new IllegalStateException(
                        "哈希字段序列化失败: " + key + ":" + entry.getKey());
            }
            stringMap.put(entry.getKey(), jsonValue);
        }
        stringRedisTemplate.opsForHash().putAll(key, stringMap);
    }

    // ===== Set操作 =====

    /**
     * 向集合添加元素
     */
    public void setAdd(String key, String... values) {
        stringRedisTemplate.opsForSet().add(key, values);
    }

    /**
     * 向关键集合写入成员，并将空结果或 Redis 故障直接抛给调用方。
     */
    public long setAddOrThrow(String key, String... values) {
        Long added = stringRedisTemplate.opsForSet().add(key, values);
        if (added == null) {
            throw new IllegalStateException("缓存集合写入结果为空: " + key);
        }
        return added;
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
     * 原子读取 JSON 主状态、拒绝指定终态，并一次性刷新暂停生命周期证据。
     *
     * <p>脚本不重新编码主 JSON，避免 Redis cjson 把空对象改成空数组；它只刷新主状态和
     * 已存在辅助键的 TTL，并用独立 pause-at 键记录本次暂停时间。返回值约定：1 表示转换
     * 成功，0 表示命中禁止状态，-1 表示主状态不存在。JSON 不是合法会话对象、Redis 空响应
     * 或未知脚本结果均失败关闭。</p>
     *
     * @param stateKey JSON 主状态键
     * @param setKey 暂停集合键
     * @param pausedAtKey 独立暂停时间键
     * @param activityAtKey 独立活动时间键
     * @param member 会话成员
     * @param forbiddenStatuses 禁止转换的状态值，按小写比较
     * @param pausedAtMillis 暂停线性化时间
     * @param ttlSeconds 主状态、暂停时间和辅助证据统一 TTL
     * @param auxiliaryKeys 已存在时需要同步续期的辅助证据键
     * @return 1、0 或 -1
     */
    public long atomicPauseSessionIfJsonStatusAllowed(
            String stateKey,
            String setKey,
            String pausedAtKey,
            String activityAtKey,
            String member,
            List<String> forbiddenStatuses,
            long pausedAtMillis,
            long ttlSeconds,
            List<String> auxiliaryKeys
    ) {
        if (stateKey == null || stateKey.isBlank()
                || setKey == null || setKey.isBlank()
                || pausedAtKey == null || pausedAtKey.isBlank()
                || activityAtKey == null || activityAtKey.isBlank()
                || member == null || member.isBlank()
                || forbiddenStatuses == null || forbiddenStatuses.isEmpty()
                || forbiddenStatuses.stream().anyMatch(
                        status -> status == null || status.isBlank())
                || pausedAtMillis <= 0
                || ttlSeconds <= 0
                || auxiliaryKeys == null
                || auxiliaryKeys.stream().anyMatch(key -> key == null || key.isBlank())) {
            throw new IllegalArgumentException("JSON 状态条件集合写入参数无效");
        }

        String luaScript = """
                local payload = redis.call('GET', KEYS[1])
                if not payload then
                    return -1
                end
                local decoded, state = pcall(cjson.decode, payload)
                if not decoded or type(state) ~= 'table' then
                    return -2
                end
                local status = state['status']
                local clientId = state['clientId']
                if type(status) ~= 'string'
                        or type(clientId) ~= 'string'
                        or clientId ~= ARGV[1] then
                    return -2
                end
                local normalized = string.lower(status)
                for i = 4, #ARGV do
                    if normalized == ARGV[i] then
                        return 0
                    end
                end
                local setType = redis.call('TYPE', KEYS[2]).ok
                local pausedAtType = redis.call('TYPE', KEYS[3]).ok
                local activityAtType = redis.call('TYPE', KEYS[4]).ok
                if (setType ~= 'none' and setType ~= 'set')
                        or (pausedAtType ~= 'none' and pausedAtType ~= 'string')
                        or (activityAtType ~= 'none' and activityAtType ~= 'string') then
                    return -3
                end
                redis.call('EXPIRE', KEYS[1], ARGV[3])
                redis.call('SADD', KEYS[2], ARGV[1])
                redis.call('SET', KEYS[3], ARGV[2], 'EX', ARGV[3])
                redis.call('SET', KEYS[4], ARGV[2], 'EX', ARGV[3])
                for i = 5, #KEYS do
                    if redis.call('EXISTS', KEYS[i]) == 1 then
                        redis.call('EXPIRE', KEYS[i], ARGV[3])
                    end
                end
                return 1
                """;
        List<String> arguments = new ArrayList<>(forbiddenStatuses.size() + 3);
        arguments.add(member);
        arguments.add(String.valueOf(pausedAtMillis));
        arguments.add(String.valueOf(ttlSeconds));
        forbiddenStatuses.stream()
                .map(status -> status.toLowerCase(Locale.ROOT))
                .forEach(arguments::add);
        List<String> keys = new ArrayList<>(auxiliaryKeys.size() + 4);
        keys.add(stateKey);
        keys.add(setKey);
        keys.add(pausedAtKey);
        keys.add(activityAtKey);
        keys.addAll(auxiliaryKeys);

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(luaScript, Long.class);
        Long result = stringRedisTemplate.execute(
                script,
                keys,
                arguments.toArray());
        if (result == null) {
            throw new IllegalStateException("JSON 状态条件集合写入结果为空: " + stateKey);
        }
        if (result == -2L) {
            throw new IllegalStateException("JSON 主状态损坏，拒绝集合转换: " + stateKey);
        }
        if (result == -3L) {
            throw new IllegalStateException("会话生命周期证据键类型损坏，拒绝集合转换: " + stateKey);
        }
        if (result != -1L && result != 0L && result != 1L) {
            throw new IllegalStateException("JSON 状态条件集合写入返回未知结果: " + stateKey);
        }
        return result;
    }

    /**
     * 原子校验 JSON 会话仍可活动并刷新主状态、活动证据与辅助证据 TTL。
     *
     * <p>该脚本不会重写主 JSON，因此迟到的活动刷新不能覆盖完成或人工终态。返回值：
     * 1 表示已刷新，0 表示终态未刷新，-1 表示会话不存在；损坏对象失败关闭。</p>
     *
     * @param stateKey JSON 主状态键
     * @param activityAtKey 独立活动时间键
     * @param member 会话ID
     * @param forbiddenStatuses 禁止续期的状态
     * @param activityAtMillis 本次活动时间
     * @param ttlSeconds 续期秒数
     * @param auxiliaryKeys 已存在时同步续期的辅助证据键
     * @return 1、0 或 -1
     */
    public long atomicTouchSessionIfJsonStatusAllowed(
            String stateKey,
            String activityAtKey,
            String member,
            List<String> forbiddenStatuses,
            long activityAtMillis,
            long ttlSeconds,
            List<String> auxiliaryKeys
    ) {
        validateSessionLifecycleArguments(
                stateKey,
                activityAtKey,
                member,
                forbiddenStatuses,
                activityAtMillis,
                ttlSeconds,
                auxiliaryKeys);
        String luaScript = """
                local payload = redis.call('GET', KEYS[1])
                if not payload then
                    return -1
                end
                local decoded, state = pcall(cjson.decode, payload)
                if not decoded or type(state) ~= 'table' then
                    return -2
                end
                local status = state['status']
                local clientId = state['clientId']
                if type(status) ~= 'string'
                        or type(clientId) ~= 'string'
                        or clientId ~= ARGV[1] then
                    return -2
                end
                local normalized = string.lower(status)
                for i = 4, #ARGV do
                    if normalized == ARGV[i] then
                        return 0
                    end
                end
                local activityAtType = redis.call('TYPE', KEYS[2]).ok
                if activityAtType ~= 'none' and activityAtType ~= 'string' then
                    return -3
                end
                redis.call('EXPIRE', KEYS[1], ARGV[3])
                redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
                for i = 3, #KEYS do
                    if redis.call('EXISTS', KEYS[i]) == 1 then
                        redis.call('EXPIRE', KEYS[i], ARGV[3])
                    end
                end
                return 1
                """;
        return executeSessionLifecycleScript(
                luaScript,
                List.of(stateKey, activityAtKey),
                auxiliaryKeys,
                member,
                forbiddenStatuses,
                activityAtMillis,
                ttlSeconds,
                "活动时间续期");
    }

    /**
     * 原子恢复暂停会话，同时删除成对暂停证据并刷新独立活动时间。
     *
     * <p>返回值：2 表示移除了暂停成员，1 表示会话原本未暂停，0 表示终态仅清理暂停
     * 证据但不续期，-1 表示主状态不存在；损坏对象失败关闭。</p>
     *
     * @param stateKey JSON 主状态键
     * @param setKey 暂停集合键
     * @param pausedAtKey 暂停时间键
     * @param activityAtKey 活动时间键
     * @param member 会话ID
     * @param forbiddenStatuses 禁止续期的状态
     * @param activityAtMillis 恢复时间
     * @param ttlSeconds 续期秒数
     * @param auxiliaryKeys 已存在时同步续期的辅助证据键
     * @return 2、1、0 或 -1
     */
    public long atomicResumeSessionIfJsonStatusAllowed(
            String stateKey,
            String setKey,
            String pausedAtKey,
            String activityAtKey,
            String member,
            List<String> forbiddenStatuses,
            long activityAtMillis,
            long ttlSeconds,
            List<String> auxiliaryKeys
    ) {
        if (setKey == null || setKey.isBlank() || pausedAtKey == null || pausedAtKey.isBlank()) {
            throw new IllegalArgumentException("恢复暂停会话参数无效");
        }
        validateSessionLifecycleArguments(
                stateKey,
                activityAtKey,
                member,
                forbiddenStatuses,
                activityAtMillis,
                ttlSeconds,
                auxiliaryKeys);
        String luaScript = """
                local payload = redis.call('GET', KEYS[1])
                if not payload then
                    return -1
                end
                local decoded, state = pcall(cjson.decode, payload)
                if not decoded or type(state) ~= 'table' then
                    return -2
                end
                local status = state['status']
                local clientId = state['clientId']
                if type(status) ~= 'string'
                        or type(clientId) ~= 'string'
                        or clientId ~= ARGV[1] then
                    return -2
                end
                local normalized = string.lower(status)
                local setType = redis.call('TYPE', KEYS[2]).ok
                local pausedAtType = redis.call('TYPE', KEYS[3]).ok
                local activityAtType = redis.call('TYPE', KEYS[4]).ok
                if (setType ~= 'none' and setType ~= 'set')
                        or (pausedAtType ~= 'none' and pausedAtType ~= 'string')
                        or (activityAtType ~= 'none' and activityAtType ~= 'string') then
                    return -3
                end
                for i = 4, #ARGV do
                    if normalized == ARGV[i] then
                        redis.call('SREM', KEYS[2], ARGV[1])
                        redis.call('DEL', KEYS[3])
                        return 0
                    end
                end
                local removed = redis.call('SREM', KEYS[2], ARGV[1])
                redis.call('DEL', KEYS[3])
                redis.call('EXPIRE', KEYS[1], ARGV[3])
                redis.call('SET', KEYS[4], ARGV[2], 'EX', ARGV[3])
                for i = 5, #KEYS do
                    if redis.call('EXISTS', KEYS[i]) == 1 then
                        redis.call('EXPIRE', KEYS[i], ARGV[3])
                    end
                end
                return removed + 1
                """;
        List<String> keys = new ArrayList<>(auxiliaryKeys.size() + 4);
        keys.add(stateKey);
        keys.add(setKey);
        keys.add(pausedAtKey);
        keys.add(activityAtKey);
        keys.addAll(auxiliaryKeys);
        long result = executeSessionLifecycleScript(
                luaScript,
                keys,
                List.of(),
                member,
                forbiddenStatuses,
                activityAtMillis,
                ttlSeconds,
                "暂停会话恢复");
        if (result != -1L && result != 0L && result != 1L && result != 2L) {
            throw new IllegalStateException("暂停会话恢复返回未知结果: " + stateKey);
        }
        return result;
    }

    /**
     * 校验会话生命周期 Lua 的公共参数。
     */
    private void validateSessionLifecycleArguments(
            String stateKey,
            String activityAtKey,
            String member,
            List<String> forbiddenStatuses,
            long activityAtMillis,
            long ttlSeconds,
            List<String> auxiliaryKeys
    ) {
        if (stateKey == null || stateKey.isBlank()
                || activityAtKey == null || activityAtKey.isBlank()
                || member == null || member.isBlank()
                || forbiddenStatuses == null || forbiddenStatuses.isEmpty()
                || forbiddenStatuses.stream().anyMatch(
                        status -> status == null || status.isBlank())
                || activityAtMillis <= 0
                || ttlSeconds <= 0
                || auxiliaryKeys == null
                || auxiliaryKeys.stream().anyMatch(key -> key == null || key.isBlank())) {
            throw new IllegalArgumentException("会话生命周期脚本参数无效");
        }
    }

    /**
     * 执行统一的会话生命周期脚本，并把损坏、空响应和未知结果映射为失败关闭。
     */
    private long executeSessionLifecycleScript(
            String luaScript,
            List<String> prefixKeys,
            List<String> auxiliaryKeys,
            String member,
            List<String> forbiddenStatuses,
            long activityAtMillis,
            long ttlSeconds,
            String operationName
    ) {
        List<String> keys = new ArrayList<>(prefixKeys.size() + auxiliaryKeys.size());
        keys.addAll(prefixKeys);
        keys.addAll(auxiliaryKeys);
        List<String> arguments = new ArrayList<>(forbiddenStatuses.size() + 3);
        arguments.add(member);
        arguments.add(String.valueOf(activityAtMillis));
        arguments.add(String.valueOf(ttlSeconds));
        forbiddenStatuses.stream()
                .map(status -> status.toLowerCase(Locale.ROOT))
                .forEach(arguments::add);
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(luaScript, Long.class);
        Long result = stringRedisTemplate.execute(script, keys, arguments.toArray());
        if (result == null) {
            throw new IllegalStateException(operationName + "结果为空: " + prefixKeys.getFirst());
        }
        if (result == -2L) {
            throw new IllegalStateException("JSON 主状态损坏，拒绝" + operationName + ": "
                    + prefixKeys.getFirst());
        }
        if (result == -3L) {
            throw new IllegalStateException("会话生命周期证据键类型损坏，拒绝" + operationName + ": "
                    + prefixKeys.getFirst());
        }
        if (result < -1L || result > 2L) {
            throw new IllegalStateException(operationName + "返回未知结果: " + prefixKeys.getFirst());
        }
        return result;
    }

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
                local setType = redis.call('TYPE', KEYS[1]).ok
                local hashType = redis.call('TYPE', KEYS[2]).ok
                if setType ~= 'none' and setType ~= 'set' then
                    return -2
                end
                if hashType ~= 'none' and hashType ~= 'hash' then
                    return -3
                end
                local current = redis.call('HGET', KEYS[2], ARGV[2])
                if current and current ~= ARGV[3] then
                    return -1
                end
                if not current then
                    local inserted = redis.call('HSETNX', KEYS[2], ARGV[2], ARGV[3])
                    if inserted ~= 1 then
                        return 0
                    end
                end
                redis.call('SADD', KEYS[1], ARGV[1])
                return 1
                """;

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(luaScript, Long.class);
        Long result = stringRedisTemplate.execute(script,
                List.of(setKey, hashKey),
                setValue, hashField, hashValue);
        if (result == null) {
            throw new IllegalStateException("原子分片证据写入结果为空: " + setKey);
        }
        if (result == -1L) {
            throw new IllegalStateException("同一分片哈希已存在不同稳定值: " + hashField);
        }
        if (result == -2L) {
            throw new IllegalStateException("原子分片证据 Set 键类型损坏: " + setKey);
        }
        if (result == -3L) {
            throw new IllegalStateException("原子分片证据 Hash 键类型损坏: " + hashKey);
        }
        if (result != 1L) {
            throw new IllegalStateException("原子分片证据写入未完成: " + setKey);
        }
        return true;
    }
}
