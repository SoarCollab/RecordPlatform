package cn.flying.identity.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 测试环境 Redis 替身实现，使用内存结构模拟 Redis 行为，规避受限环境无法绑定端口的问题。
 */
@TestConfiguration
public class EmbeddedRedisTestConfig {

    @Bean(name = "stringRedisTemplate")
    @Primary
    public StringRedisTemplate inMemoryStringRedisTemplate() {
        return new InMemoryStringRedisTemplate();
    }

    @Bean
    @Primary
    public JavaMailSender testJavaMailSender() {
        return new JavaMailSenderImpl();
    }

    /**
     * 简单内存版 StringRedisTemplate，覆盖项目内常用操作。
     */
    private static final class InMemoryStringRedisTemplate extends StringRedisTemplate {

        private final ConcurrentMap<String, String> valueStore = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Map<String, String>> hashStore = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Set<String>> setStore = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Long> ttlStore = new ConcurrentHashMap<>();

        private final ValueOperations<String, String> valueOps = new InMemoryValueOperations();
        private final HashOperations<String, String, String> hashOps = new InMemoryHashOperations();
        private final SetOperations<String, String> setOps = new InMemorySetOperations();

        @Override
        public void afterPropertiesSet() {
            // 内存实现无需连接工厂，覆盖父类校验逻辑
        }

        @Override
        public <T> T execute(org.springframework.data.redis.core.RedisCallback<T> action) {
            // 内存实现暂不支持底层连接操作
            throw new UnsupportedOperationException("In-memory redis template does not expose raw connection.");
        }

        @Override
        public Boolean delete(String key) {
            boolean removed = false;
            removed |= valueStore.remove(key) != null;
            removed |= hashStore.remove(key) != null;
            removed |= setStore.remove(key) != null;
            ttlStore.remove(key);
            return removed;
        }

        @Override
        public Long delete(Collection<String> keys) {
            long count = 0;
            for (String key : keys) {
                if (delete(key)) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public Set<String> keys(String pattern) {
            Set<String> matched = new HashSet<>();
            Pattern regex = globToRegex(pattern);
            for (String key : allKeys()) {
                if (regex.matcher(key).matches()) {
                    matched.add(key);
                }
            }
            return matched;
        }

        @Override
        public Boolean expire(String key, long timeout, TimeUnit unit) {
            if (!keyExists(key)) {
                return Boolean.FALSE;
            }
            long expireAt = System.currentTimeMillis() + unit.toMillis(timeout);
            ttlStore.put(key, expireAt);
            return Boolean.TRUE;
        }

        @Override
        public HashOperations<String, String, String> opsForHash() {
            return hashOps;
        }

        @Override
        public SetOperations<String, String> opsForSet() {
            return setOps;
        }

        @Override
        public ValueOperations<String, String> opsForValue() {
            return valueOps;
        }

        private boolean keyExists(String key) {
            purgeIfExpired(key);
            return valueStore.containsKey(key) || hashStore.containsKey(key) || setStore.containsKey(key);
        }

        private Pattern globToRegex(String pattern) {
            if (pattern == null || pattern.isEmpty()) {
                return Pattern.compile(".*");
            }
            StringBuilder builder = new StringBuilder();
            char[] chars = pattern.toCharArray();
            for (char ch : chars) {
                switch (ch) {
                    case '*':
                        builder.append(".*");
                        break;
                    case '?':
                        builder.append('.');
                        break;
                    case '.':
                    case '$':
                    case '^':
                    case '{':
                    case '}':
                    case '[':
                    case ']':
                    case '(':
                    case ')':
                    case '+':
                    case '|':
                    case '\\':
                        builder.append('\\').append(ch);
                        break;
                    default:
                        builder.append(ch);
                }
            }
            return Pattern.compile(builder.toString());
        }

        private Set<String> allKeys() {
            Set<String> keys = new HashSet<>();
            valueStore.keySet().forEach(this::purgeIfExpired);
            hashStore.keySet().forEach(this::purgeIfExpired);
            setStore.keySet().forEach(this::purgeIfExpired);
            keys.addAll(valueStore.keySet());
            keys.addAll(hashStore.keySet());
            keys.addAll(setStore.keySet());
            return keys;
        }

        private void purgeIfExpired(String key) {
            Long expireAt = ttlStore.get(key);
            if (expireAt != null && System.currentTimeMillis() > expireAt) {
                valueStore.remove(key);
                hashStore.remove(key);
                setStore.remove(key);
                ttlStore.remove(key);
            }
        }

        private final class InMemoryValueOperations implements ValueOperations<String, String> {

            @Override
            public void set(String key, String value) {
                purgeIfExpired(key);
                valueStore.put(key, value);
                hashStore.remove(key);
                setStore.remove(key);
                ttlStore.remove(key);
            }

            @Override
            public void set(String key, String value, long timeout, TimeUnit unit) {
                set(key, value);
                expire(key, timeout, unit);
            }

            @Override
            public Boolean setIfAbsent(String key, String value) {
                purgeIfExpired(key);
                boolean absent = valueStore.putIfAbsent(key, value) == null;
                if (absent) {
                    hashStore.remove(key);
                    setStore.remove(key);
                    ttlStore.remove(key);
                }
                return absent;
            }

            @Override
            public Boolean setIfAbsent(String key, String value, long timeout, TimeUnit unit) {
                Boolean success = setIfAbsent(key, value);
                if (success) {
                    expire(key, timeout, unit);
                }
                return success;
            }

            @Override
            public Boolean setIfPresent(String key, String value) {
                purgeIfExpired(key);
                if (!valueStore.containsKey(key)) {
                    return Boolean.FALSE;
                }
                set(key, value);
                return Boolean.TRUE;
            }

            @Override
            public Boolean setIfPresent(String key, String value, long timeout, TimeUnit unit) {
                Boolean success = setIfPresent(key, value);
                if (Boolean.TRUE.equals(success)) {
                    expire(key, timeout, unit);
                }
                return success;
            }

            @Override
            public void multiSet(Map<? extends String, ? extends String> map) {
                map.forEach(this::set);
            }

            @Override
            public Boolean multiSetIfAbsent(Map<? extends String, ? extends String> map) {
                for (String key : map.keySet()) {
                    purgeIfExpired(key);
                    if (valueStore.containsKey(key)) {
                        return Boolean.FALSE;
                    }
                }
                map.forEach(this::set);
                return Boolean.TRUE;
            }

            @Override
            public String get(Object key) {
                String redisKey = String.valueOf(key);
                purgeIfExpired(redisKey);
                return valueStore.get(redisKey);
            }

            @Override
            public String getAndDelete(String key) {
                purgeIfExpired(key);
                String value = valueStore.remove(key);
                ttlStore.remove(key);
                return value;
            }

            @Override
            public String getAndExpire(String key, long timeout, TimeUnit unit) {
                String value = get(key);
                if (value != null) {
                    expire(key, timeout, unit);
                }
                return value;
            }

            @Override
            public String getAndExpire(String key, java.time.Duration duration) {
                String value = get(key);
                if (value != null) {
                    expire(key, duration.toMillis(), TimeUnit.MILLISECONDS);
                }
                return value;
            }

            @Override
            public String getAndPersist(String key) {
                String value = get(key);
                if (value != null) {
                    ttlStore.remove(key);
                }
                return value;
            }

            @Override
            public String getAndSet(String key, String value) {
                purgeIfExpired(key);
                String previous = valueStore.put(key, value);
                ttlStore.remove(key);
                hashStore.remove(key);
                setStore.remove(key);
                return previous;
            }

            @Override
            public List<String> multiGet(Collection<String> keys) {
                List<String> result = new ArrayList<>();
                for (String key : keys) {
                    result.add(get(key));
                }
                return result;
            }

            @Override
            public Long increment(String key) {
                return increment(key, 1L);
            }

            @Override
            public Long increment(String key, long delta) {
                purgeIfExpired(key);
                long current = 0;
                String existing = valueStore.get(key);
                if (existing != null) {
                    current = Long.parseLong(existing);
                }
                current += delta;
                valueStore.put(key, Long.toString(current));
                return current;
            }

            @Override
            public Double increment(String key, double delta) {
                purgeIfExpired(key);
                double current = 0;
                String existing = valueStore.get(key);
                if (existing != null) {
                    current = Double.parseDouble(existing);
                }
                current += delta;
                valueStore.put(key, Double.toString(current));
                return current;
            }

            @Override
            public Long decrement(String key) {
                return increment(key, -1L);
            }

            @Override
            public Long decrement(String key, long delta) {
                return increment(key, -delta);
            }

            @Override
            public Integer append(String key, String value) {
                String existing = get(key);
                String next = (existing == null ? "" : existing) + value;
                set(key, next);
                return next.length();
            }

            @Override
            public String get(String key, long start, long end) {
                String value = get(key);
                if (value == null) {
                    return null;
                }
                int length = value.length();
                int from = (int) Math.max(0, start);
                int to = (int) Math.min(length, end >= 0 ? end + 1 : length + end);
                if (from >= to) {
                    return "";
                }
                return value.substring(from, to);
            }

            @Override
            public void set(String key, String value, long offset) {
                throw new UnsupportedOperationException("set with offset not implemented in memory redis.");
            }

            @Override
            public Long size(String key) {
                String value = get(key);
                return value == null ? 0L : (long) value.length();
            }

            @Override
            public Boolean setBit(String key, long offset, boolean value) {
                throw new UnsupportedOperationException("setBit not implemented in memory redis.");
            }

            @Override
            public Boolean getBit(String key, long offset) {
                throw new UnsupportedOperationException("getBit not implemented in memory redis.");
            }

            @Override
            public List<Long> bitField(String key, org.springframework.data.redis.connection.BitFieldSubCommands subCommands) {
                throw new UnsupportedOperationException("bitField not implemented in memory redis.");
            }

            @Override
            public RedisOperations<String, String> getOperations() {
                return InMemoryStringRedisTemplate.this;
            }
        }

        private final class InMemoryHashOperations implements HashOperations<String, String, String> {

            @Override
            public Long delete(String key, Object... hashKeys) {
                purgeIfExpired(key);
                Map<String, String> map = hashStore.get(key);
                if (map == null) {
                    return 0L;
                }
                long removed = 0;
                for (Object hashKey : hashKeys) {
                    if (map.remove(String.valueOf(hashKey)) != null) {
                        removed++;
                    }
                }
                if (map.isEmpty()) {
                    hashStore.remove(key);
                } else {
                    hashStore.put(key, new LinkedHashMap<>(map));
                }
                return removed;
            }

            @Override
            public Boolean hasKey(String key, Object hashKey) {
                purgeIfExpired(key);
                Map<String, String> map = hashStore.get(key);
                return map != null && map.containsKey(String.valueOf(hashKey));
            }

            @Override
            public String get(String key, Object hashKey) {
                purgeIfExpired(key);
                Map<String, String> map = hashStore.get(key);
                if (map == null) {
                    return null;
                }
                return map.get(String.valueOf(hashKey));
            }

            @Override
            public List<String> multiGet(String key, Collection<String> hashKeys) {
                purgeIfExpired(key);
                Map<String, String> map = hashStore.get(key);
                List<String> result = new ArrayList<>();
                for (String hashKey : hashKeys) {
                    result.add(map != null ? map.get(hashKey) : null);
                }
                return result;
            }

            @Override
            public Long increment(String key, String hashKey, long delta) {
                purgeIfExpired(key);
                Map<String, String> map = hashStore.computeIfAbsent(key, k -> new LinkedHashMap<>());
                long current = map.containsKey(hashKey) ? Long.parseLong(map.get(hashKey)) : 0;
                current += delta;
                map.put(hashKey, Long.toString(current));
                return current;
            }

            // 其余方法暂未用到
            @Override
            public Double increment(String key, String hashKey, double delta) {
                throw new UnsupportedOperationException("double increment not implemented in memory redis.");
            }

            @Override
            public String randomKey(String key) {
                purgeIfExpired(key);
                Map<String, String> map = hashStore.get(key);
                if (map == null || map.isEmpty()) {
                    return null;
                }
                List<String> keys = new ArrayList<>(map.keySet());
                return keys.get(new Random().nextInt(keys.size()));
            }

            @Override
            public Map.Entry<String, String> randomEntry(String key) {
                purgeIfExpired(key);
                Map<String, String> map = hashStore.get(key);
                if (map == null || map.isEmpty()) {
                    return null;
                }
                List<Map.Entry<String, String>> entries = new ArrayList<>(map.entrySet());
                return entries.get(new Random().nextInt(entries.size()));
            }

            @Override
            public List<String> randomKeys(String key, long count) {
                purgeIfExpired(key);
                Map<String, String> map = hashStore.get(key);
                if (map == null || map.isEmpty()) {
                    return Collections.emptyList();
                }
                List<String> keys = new ArrayList<>(map.keySet());
                Collections.shuffle(keys);
                return keys.subList(0, (int) Math.min(count, keys.size()));
            }

            @Override
            public Map<String, String> randomEntries(String key, long count) {
                purgeIfExpired(key);
                Map<String, String> map = hashStore.get(key);
                if (map == null || map.isEmpty()) {
                    return Collections.emptyMap();
                }
                List<Map.Entry<String, String>> entries = new ArrayList<>(map.entrySet());
                Collections.shuffle(entries);
                Map<String, String> result = new LinkedHashMap<>();
                for (int i = 0; i < Math.min(count, entries.size()); i++) {
                    Map.Entry<String, String> entry = entries.get(i);
                    result.put(entry.getKey(), entry.getValue());
                }
                return result;
            }

            @Override
            public Set<String> keys(String key) {
                purgeIfExpired(key);
                Map<String, String> map = hashStore.get(key);
                return map == null ? Collections.emptySet() : new LinkedHashSet<>(map.keySet());
            }

            @Override
            public Long lengthOfValue(String key, String hashKey) {
                purgeIfExpired(key);
                Map<String, String> map = hashStore.get(key);
                if (map == null) {
                    return 0L;
                }
                String value = map.get(hashKey);
                return value == null ? 0L : (long) value.length();
            }

            @Override
            public Long size(String key) {
                purgeIfExpired(key);
                Map<String, String> map = hashStore.get(key);
                return map == null ? 0L : (long) map.size();
            }

            @Override
            public void putAll(String key, Map<? extends String, ? extends String> m) {
                purgeIfExpired(key);
                Map<String, String> copy = new LinkedHashMap<>(m);
                hashStore.put(key, copy);
                valueStore.remove(key);
                setStore.remove(key);
                ttlStore.remove(key);
            }

            @Override
            public void put(String key, String hashKey, String value) {
                purgeIfExpired(key);
                hashStore.compute(key, (k, existing) -> {
                    Map<String, String> map = existing != null ? new LinkedHashMap<>(existing) : new LinkedHashMap<>();
                    map.put(hashKey, value);
                    return map;
                });
                valueStore.remove(key);
                setStore.remove(key);
                ttlStore.remove(key);
            }

            @Override
            public Boolean putIfAbsent(String key, String hashKey, String value) {
                purgeIfExpired(key);
                Map<String, String> map = hashStore.computeIfAbsent(key, k -> new LinkedHashMap<>());
                if (map.containsKey(hashKey)) {
                    return Boolean.FALSE;
                }
                map.put(hashKey, value);
                return Boolean.TRUE;
            }

            @Override
            public List<String> values(String key) {
                purgeIfExpired(key);
                Map<String, String> map = hashStore.get(key);
                return map == null ? Collections.emptyList() : new ArrayList<>(map.values());
            }

            @Override
            public Map<String, String> entries(String key) {
                purgeIfExpired(key);
                Map<String, String> map = hashStore.get(key);
                if (map == null) {
                    return Collections.emptyMap();
                }
                return new LinkedHashMap<>(map);
            }

            @Override
            public Cursor<Map.Entry<String, String>> scan(String key, org.springframework.data.redis.core.ScanOptions options) {
                throw new UnsupportedOperationException("hash scan not implemented in memory redis.");
            }

            @Override
            public RedisOperations<String, ?> getOperations() {
                return InMemoryStringRedisTemplate.this;
            }
        }

        private final class InMemorySetOperations implements SetOperations<String, String> {

            @Override
            public Long add(String key, String... values) {
                purgeIfExpired(key);
                Set<String> set = setStore.computeIfAbsent(key, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()));
                long added = 0;
                for (String value : values) {
                    if (set.add(value)) {
                        added++;
                    }
                }
                valueStore.remove(key);
                hashStore.remove(key);
                ttlStore.remove(key);
                return added;
            }

            @Override
            public Long remove(String key, Object... values) {
                purgeIfExpired(key);
                Set<String> set = setStore.get(key);
                if (set == null) {
                    return 0L;
                }
                long removed = 0;
                for (Object value : values) {
                    if (set.remove(String.valueOf(value))) {
                        removed++;
                    }
                }
                if (set.isEmpty()) {
                    setStore.remove(key);
                }
                return removed;
            }

            @Override
            public String pop(String key) {
                List<String> popped = pop(key, 1);
                return popped.isEmpty() ? null : popped.get(0);
            }

            @Override
            public List<String> pop(String key, long count) {
                purgeIfExpired(key);
                Set<String> set = setStore.get(key);
                if (set == null || set.isEmpty() || count <= 0) {
                    return Collections.emptyList();
                }
                List<String> result = new ArrayList<>();
                Iterator<String> iterator = set.iterator();
                while (iterator.hasNext() && result.size() < count) {
                    result.add(iterator.next());
                    iterator.remove();
                }
                if (set.isEmpty()) {
                    setStore.remove(key);
                }
                return result;
            }

            @Override
            public Boolean move(String key, String value, String destKey) {
                purgeIfExpired(key);
                Set<String> source = setStore.get(key);
                if (source == null || !source.remove(value)) {
                    return Boolean.FALSE;
                }
                setStore.computeIfAbsent(destKey, k -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(value);
                if (source.isEmpty()) {
                    setStore.remove(key);
                }
                ttlStore.remove(destKey);
                return Boolean.TRUE;
            }

            @Override
            public Long size(String key) {
                purgeIfExpired(key);
                Set<String> set = setStore.get(key);
                return set == null ? 0L : (long) set.size();
            }

            @Override
            public Boolean isMember(String key, Object value) {
                purgeIfExpired(key);
                Set<String> set = setStore.get(key);
                return set != null && set.contains(String.valueOf(value));
            }

            @Override
            public Map<Object, Boolean> isMember(String key, Object... values) {
                Map<Object, Boolean> result = new LinkedHashMap<>();
                for (Object value : values) {
                    result.put(value, isMember(key, value));
                }
                return result;
            }

            @Override
            public Set<String> intersect(String key, String otherKey) {
                return intersect(key, Collections.singleton(otherKey));
            }

            @Override
            public Set<String> intersect(String key, Collection<String> otherKeys) {
                purgeIfExpired(key);
                Set<String> base = new LinkedHashSet<>(members(key));
                for (String otherKey : otherKeys) {
                    base.retainAll(members(otherKey));
                }
                return base;
            }

            @Override
            public Set<String> intersect(Collection<String> keys) {
                Iterator<String> iterator = keys.iterator();
                if (!iterator.hasNext()) {
                    return Collections.emptySet();
                }
                String first = iterator.next();
                Set<String> result = new LinkedHashSet<>(members(first));
                while (iterator.hasNext()) {
                    result.retainAll(members(iterator.next()));
                }
                return result;
            }

            @Override
            public Long intersectAndStore(String key, String otherKey, String destKey) {
                return intersectAndStore(key, Collections.singleton(otherKey), destKey);
            }

            @Override
            public Long intersectAndStore(String key, Collection<String> otherKeys, String destKey) {
                Set<String> result = intersect(key, otherKeys);
                store(destKey, result);
                return (long) result.size();
            }

            @Override
            public Long intersectAndStore(Collection<String> keys, String destKey) {
                Set<String> result = intersect(keys);
                store(destKey, result);
                return (long) result.size();
            }

            @Override
            public Set<String> union(String key, String otherKey) {
                return union(Arrays.asList(key, otherKey));
            }

            @Override
            public Set<String> union(String key, Collection<String> otherKeys) {
                List<String> keys = new ArrayList<>();
                keys.add(key);
                keys.addAll(otherKeys);
                return union(keys);
            }

            @Override
            public Set<String> union(Collection<String> keys) {
                Set<String> result = new LinkedHashSet<>();
                for (String key : keys) {
                    result.addAll(members(key));
                }
                return result;
            }

            @Override
            public Long unionAndStore(String key, String otherKey, String destKey) {
                return unionAndStore(Arrays.asList(key, otherKey), destKey);
            }

            @Override
            public Long unionAndStore(String key, Collection<String> otherKeys, String destKey) {
                List<String> keys = new ArrayList<>();
                keys.add(key);
                keys.addAll(otherKeys);
                return unionAndStore(keys, destKey);
            }

            @Override
            public Long unionAndStore(Collection<String> keys, String destKey) {
                Set<String> result = union(keys);
                store(destKey, result);
                return (long) result.size();
            }

            @Override
            public Set<String> difference(String key, String otherKey) {
                return difference(key, Collections.singleton(otherKey));
            }

            @Override
            public Set<String> difference(String key, Collection<String> otherKeys) {
                purgeIfExpired(key);
                Set<String> result = new LinkedHashSet<>(members(key));
                for (String otherKey : otherKeys) {
                    result.removeAll(members(otherKey));
                }
                return result;
            }

            @Override
            public Set<String> difference(Collection<String> keys) {
                Iterator<String> iterator = keys.iterator();
                if (!iterator.hasNext()) {
                    return Collections.emptySet();
                }
                String first = iterator.next();
                Set<String> result = new LinkedHashSet<>(members(first));
                while (iterator.hasNext()) {
                    result.removeAll(members(iterator.next()));
                }
                return result;
            }

            @Override
            public Long differenceAndStore(String key, String otherKey, String destKey) {
                return differenceAndStore(key, Collections.singleton(otherKey), destKey);
            }

            @Override
            public Long differenceAndStore(String key, Collection<String> otherKeys, String destKey) {
                Set<String> result = difference(key, otherKeys);
                store(destKey, result);
                return (long) result.size();
            }

            @Override
            public Long differenceAndStore(Collection<String> keys, String destKey) {
                Set<String> result = difference(keys);
                store(destKey, result);
                return (long) result.size();
            }

            @Override
            public Set<String> members(String key) {
                purgeIfExpired(key);
                Set<String> set = setStore.get(key);
                return set == null ? Collections.emptySet() : new LinkedHashSet<>(set);
            }

            @Override
            public String randomMember(String key) {
                purgeIfExpired(key);
                Set<String> set = setStore.get(key);
                if (set == null || set.isEmpty()) {
                    return null;
                }
                int index = new Random().nextInt(set.size());
                Iterator<String> iterator = set.iterator();
                for (int i = 0; i < index && iterator.hasNext(); i++) {
                    iterator.next();
                }
                return iterator.hasNext() ? iterator.next() : null;
            }

            @Override
            public Set<String> distinctRandomMembers(String key, long count) {
                List<String> elements = new ArrayList<>(members(key));
                Collections.shuffle(elements);
                return new LinkedHashSet<>(elements.subList(0, (int) Math.min(count, elements.size())));
            }

            @Override
            public List<String> randomMembers(String key, long count) {
                if (count <= 0) {
                    return Collections.emptyList();
                }
                List<String> result = new ArrayList<>();
                List<String> elements = new ArrayList<>(members(key));
                if (elements.isEmpty()) {
                    return result;
                }
                Random random = new Random();
                for (int i = 0; i < count; i++) {
                    result.add(elements.get(random.nextInt(elements.size())));
                }
                return result;
            }

            @Override
            public Cursor<String> scan(String key, org.springframework.data.redis.core.ScanOptions options) {
                throw new UnsupportedOperationException("set scan not implemented in memory redis.");
            }

            @Override
            public RedisOperations<String, String> getOperations() {
                return InMemoryStringRedisTemplate.this;
            }

            private void store(String key, Set<String> values) {
                Set<String> target = Collections.newSetFromMap(new ConcurrentHashMap<>());
                target.addAll(values);
                setStore.put(key, target);
                valueStore.remove(key);
                hashStore.remove(key);
                ttlStore.remove(key);
            }
        }
    }
}
