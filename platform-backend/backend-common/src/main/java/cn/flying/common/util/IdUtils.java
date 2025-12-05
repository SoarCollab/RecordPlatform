package cn.flying.common.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * ID生成工具类，提供多种ID生成策略和安全措施
 */
@Slf4j
@Component
public class IdUtils {

    private static SnowflakeIdGenerator snowflakeIdGenerator;
    private static StringRedisTemplate redisTemplate;
    private static final Random RANDOM = new Random();
    
    // 监控配置
    private static final String MONITOR_KEY_PREFIX = "id:monitor:";
    private static final int DEFAULT_THRESHOLD = 100;
    private static int monitorThreshold = DEFAULT_THRESHOLD;

    // ID映射缓存配置
    private static int idMappingExpireHours = 24;
    private static final String ID_EXTERNAL_PREFIX = "id:ext:";
    private static final String ID_INTERNAL_PREFIX = "id:int:";
    private static final int UUID_COLLISION_RETRY = 3;

    @Value("${id.monitor.threshold:100}")
    public void setMonitorThreshold(int threshold) {
        IdUtils.monitorThreshold = threshold;
    }

    @Value("${id.mapping.expire-hours:24}")
    public void setIdMappingExpireHours(int hours) {
        IdUtils.idMappingExpireHours = hours;
    }

    @Autowired
    public IdUtils(SnowflakeIdGenerator snowflakeIdGenerator, StringRedisTemplate redisTemplate) {
        IdUtils.snowflakeIdGenerator = snowflakeIdGenerator;
        IdUtils.redisTemplate = redisTemplate;
        log.info("ID Utils Initialization completed");
    }

    /**
     * 生成实体ID (数据库实体通用ID)
     * @return 雪花算法生成的ID
     */
    public static Long nextEntityId() {
        long id = snowflakeIdGenerator.nextId();
        monitorIdGeneration("entity"); // 监控ID生成
        return id;
    }

    /**
     * 生成用户ID (适用于用户账号等敏感实体)
     * @return 原始雪花ID（未混淆）
     */
    public static Long nextUserId() {
        long id = snowflakeIdGenerator.nextId();
        monitorIdGeneration("user"); // 监控ID生成
        return id; // 返回原始ID
    }
    
    /**
     * 生成日志ID (适用于日志记录)
     * 使用不同于实体ID的生成策略
     * @return 日志ID
     */
    public static String nextLogId() {
        // 生成策略：时间戳+随机数
        return "L" + System.currentTimeMillis() + 
               String.format("%04d", RANDOM.nextInt(10000));
    }
    
    /**
     * 生成API响应中的外部ID (使用UUID确保不可预测性)
     */
    public static String toExternalId(Long internalId) {
        return generateExternalId(internalId, "E");
    }

    /**
     * 生成用户ID的外部表示
     */
    public static String toExternalUserId(Long userId) {
        return generateExternalId(userId, "U");
    }

    private static String generateExternalId(Long internalId, String prefix) {
        if (internalId == null) return null;
        try {
            String internalKey = ID_INTERNAL_PREFIX + prefix + internalId;
            String existingExternalId = redisTemplate.opsForValue().get(internalKey);
            if (existingExternalId != null) return existingExternalId;

            for (int i = 0; i < UUID_COLLISION_RETRY; i++) {
                String externalId = prefix + UUID.randomUUID().toString().replace("-", "");
                if (storeIdMapping(internalId, externalId, prefix)) return externalId;
            }
            log.error("生成外部ID失败，超过最大重试次数，internalId: {}", internalId);
        } catch (Exception e) {
            log.error("生成外部ID异常，internalId: {}", internalId, e);
        }
        return null;
    }

    private static boolean storeIdMapping(Long internalId, String externalId, String prefix) {
        String externalKey = ID_EXTERNAL_PREFIX + externalId;
        String internalKey = ID_INTERNAL_PREFIX + prefix + internalId;
        String internalIdStr = String.valueOf(internalId);
        Boolean stored = redisTemplate.opsForValue()
                .setIfAbsent(externalKey, internalIdStr, idMappingExpireHours, TimeUnit.HOURS);
        if (Boolean.TRUE.equals(stored)) {
            redisTemplate.opsForValue().set(internalKey, externalId, idMappingExpireHours, TimeUnit.HOURS);
            return true;
        }
        String existingInternalId = redisTemplate.opsForValue().get(externalKey);
        if (internalIdStr.equals(existingInternalId)) {
            redisTemplate.opsForValue().set(internalKey, externalId, idMappingExpireHours, TimeUnit.HOURS);
            return true;
        }
        return false;
    }

    /**
     * 从外部ID还原内部ID
     */
    public static Long fromExternalId(String externalId) {
        if (externalId == null || externalId.isEmpty()) return null;
        try {
            String cacheKey = ID_EXTERNAL_PREFIX + externalId;
            String cachedInternalId = redisTemplate.opsForValue().get(cacheKey);
            if (cachedInternalId != null) return Long.parseLong(cachedInternalId);
            log.warn("未找到外部ID映射: {}", externalId);
            return null;
        } catch (NumberFormatException e) {
            log.error("内部ID格式错误: externalId={}", externalId, e);
            return null;
        } catch (Exception e) {
            log.error("解析外部ID异常: {}", externalId, e);
            return null;
        }
    }

    /**
     * 生成带前缀的ID
     * @param prefix ID前缀
     * @return 带前缀的ID字符串
     */
    public static String nextIdWithPrefix(String prefix) {
        return prefix + nextEntityId();
    }
    
    /**
     * 生成字符串形式的实体ID
     * @return 字符串形式的ID
     */
    public static String nextEntityIdStr() {
        return String.valueOf(nextEntityId());
    }
    
    /**
     * 监控ID生成频率，防止枚举攻击
     * @param type ID类型
     */
    private static void monitorIdGeneration(String type) {
        try {
            String key = MONITOR_KEY_PREFIX + type + ":" + Thread.currentThread().threadId();
            Long count = redisTemplate.opsForValue().increment(key);
            
            // 首次设置过期时间
            if (count != null && count == 1) {
                redisTemplate.expire(key, Duration.ofMinutes(1));
            }
            
            // 检查是否超过阈值
            if (count != null && count > monitorThreshold) {
                log.warn("检测到可能的ID枚举攻击! 线程ID: {}, ID类型: {}, 1分钟内生成数量: {}",
                        Thread.currentThread().threadId(), type, count);
                // 可以在这里添加额外的安全措施，如限流、告警等
            }
        } catch (Exception e) {
            // 确保监控失败不影响正常ID生成
            log.error("ID生成监控失败", e);
        }
    }
} 