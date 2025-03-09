package cn.flying.common.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Random;
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
    private static final int DEFAULT_THRESHOLD = 100;  // 默认阈值
    private static int monitorThreshold = DEFAULT_THRESHOLD;  // 可配置阈值
    
    // 混淆密钥
    private static String obfuscationKey = "default_security_key";
    
    // ID映射缓存配置
    private static int idMappingExpireHours = 24;  // 默认24小时过期
    private static final String ID_MAPPING_PREFIX = "id:mapping:";

    @Value("${id.monitor.threshold:100}")
    public void setMonitorThreshold(int threshold) {
        IdUtils.monitorThreshold = threshold;
    }
    
    @Value("${id.security.key:RecordPlatform}")
    public void setObfuscationKey(String key) {
        IdUtils.obfuscationKey = key;
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
     * 生成API响应中的外部ID (隐藏实际ID)
     * @param internalId 内部实体ID
     * @return 混淆后的外部ID字符串
     */
    public static String toExternalId(Long internalId) {
        if (internalId == null) return null;
        try {
            // 将ID与密钥组合后进行SHA-256哈希，然后Base64编码
            // 实际应用中可以使用更安全的可逆算法
            String input = internalId + ":" + obfuscationKey;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            // 取前12字节进行Base64编码
            byte[] shortened = new byte[12];
            System.arraycopy(hash, 0, shortened, 0, 12);
            String externalId = Base64.getUrlEncoder().withoutPadding().encodeToString(shortened);
            
            // 在Redis中存储映射关系，设置24小时过期
            try {
                String cacheKey = ID_MAPPING_PREFIX + externalId;
                redisTemplate.opsForValue().set(cacheKey, String.valueOf(internalId), idMappingExpireHours, TimeUnit.HOURS);
            } catch (Exception e) {
                // 缓存操作失败不应影响主流程
                log.warn("存储ID映射关系到Redis失败: {}", e.getMessage());
            }
            
            return externalId;
        } catch (NoSuchAlgorithmException e) {
            log.error("生成外部ID时发生错误", e);
            // 降级策略：使用简单混淆
            String fallbackId = "EX" + (internalId ^ 0x3A3A3A3AL);
            
            // 存储降级映射
            try {
                String cacheKey = ID_MAPPING_PREFIX + fallbackId;
                redisTemplate.opsForValue().set(cacheKey, String.valueOf(internalId), idMappingExpireHours, TimeUnit.HOURS);
            } catch (Exception ex) {
                log.warn("存储降级ID映射关系到Redis失败", ex);
            }
            
            return fallbackId;
        }
    }
    
    /**
     * 生成用户ID的外部表示（专用于用户ID的混淆）
     * @param userId 内部用户ID
     * @return 混淆后的用户ID字符串
     */
    public static String toExternalUserId(Long userId) {
        if (userId == null) return null;
        try {
            // 用户ID使用专门的混淆方法，确保安全性
            String input = "USER:" + userId + ":" + obfuscationKey;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            // 使用较短地表示，但仍确保唯一性
            byte[] shortened = new byte[10];
            System.arraycopy(hash, 0, shortened, 0, 10);
            String externalId = "U" + Base64.getUrlEncoder().withoutPadding().encodeToString(shortened);
            
            // 在Redis中存储映射关系，设置24小时过期
            try {
                String cacheKey = ID_MAPPING_PREFIX + externalId;
                redisTemplate.opsForValue().set(cacheKey, String.valueOf(userId), idMappingExpireHours, TimeUnit.HOURS);
            } catch (Exception e) {
                // 缓存操作失败不应影响主流程
                log.warn("存储用户ID映射关系到Redis失败: {}", e.getMessage());
            }
            
            return externalId;
        } catch (NoSuchAlgorithmException e) {
            log.error("生成外部用户ID时发生错误", e);
            // 降级策略：使用简单混淆，但与普通实体使用不同的混淆值
            String fallbackId = "U" + (userId ^ 0x5A5A5A5A5A5AL);
            
            // 存储降级映射
            try {
                String cacheKey = ID_MAPPING_PREFIX + fallbackId;
                redisTemplate.opsForValue().set(cacheKey, String.valueOf(userId), idMappingExpireHours, TimeUnit.HOURS);
            } catch (Exception ex) {
                log.warn("存储降级用户ID映射关系到Redis失败", ex);
            }
            
            return fallbackId;
        }
    }
    
    /**
     * 从外部ID还原内部ID (需要在安全上下文中使用)
     * 使用Redis存储映射关系实现双向转换
     * @param externalId 外部ID
     * @return 内部ID，如果无法解析则返回null
     */
    public static Long fromExternalId(String externalId) {
        if (externalId == null || externalId.isEmpty()) {
            return null;
        }
        
        try {
            // 1. 尝试从Redis获取映射关系
            String cacheKey = ID_MAPPING_PREFIX + externalId;
            String cachedInternalId = redisTemplate.opsForValue().get(cacheKey);
            
            if (cachedInternalId != null) {
                // 缓存命中，直接返回内部ID
                try {
                    return Long.parseLong(cachedInternalId);
                } catch (NumberFormatException e) {
                    log.error("Redis中存储的内部ID格式错误: {}", cachedInternalId, e);
                    return null;
                }
            }
            
            // 2. 处理简单混淆情况 (针对降级策略使用的异或运算)
            if (externalId.startsWith("EX")) {
                try {
                    long encodedId = Long.parseLong(externalId.substring(2));
                    return encodedId ^ 0x3A3A3A3AL; // 与toExternalId中相同的异或值
                } catch (NumberFormatException e) {
                    log.debug("外部ID不是简单混淆格式: {}", externalId);
                }
            }
            
            // 3. 处理用户ID特定的简单混淆情况
            if (externalId.startsWith("U")) {
                try {
                    long encodedId = Long.parseLong(externalId.substring(1));
                    return encodedId ^ 0x5A5A5A5A5A5AL; // 与toExternalUserId中相同的异或值
                } catch (NumberFormatException e) {
                    log.debug("外部用户ID不是简单混淆格式: {}", externalId);
                }
            }
            
            // 4. 如果是加密ID，需要通过映射表或其他方式查找
            // 此处可以实现更复杂的查找逻辑，如数据库查询
            log.warn("无法解析复杂格式的外部ID: {}，可能需要更复杂的映射机制", externalId);
            return null;
            
        } catch (Exception e) {
            log.error("将外部ID转换为内部ID时发生错误: {}", externalId, e);
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