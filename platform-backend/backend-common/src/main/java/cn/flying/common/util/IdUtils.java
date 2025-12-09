package cn.flying.common.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ID生成工具类，提供多种ID生成策略
 *
 * <h3>功能：</h3>
 * <ul>
 *   <li>雪花算法生成内部ID</li>
 *   <li>AES加密转换内外ID（无需Redis缓存）</li>
 *   <li>日志ID生成</li>
 * </ul>
 */
@Slf4j
@Component
public class IdUtils {

    private static SnowflakeIdGenerator snowflakeIdGenerator;
    private static SecureIdCodec secureIdCodec;
    private static final Random RANDOM = new Random();

    // ID生成频率监控（本地计数，无Redis依赖）
    private static final AtomicLong entityIdCounter = new AtomicLong(0);
    private static final AtomicLong userIdCounter = new AtomicLong(0);
    private static volatile long lastResetTime = System.currentTimeMillis();
    private static final long MONITOR_WINDOW_MS = 60_000; // 1分钟窗口

    @Autowired
    public IdUtils(SnowflakeIdGenerator snowflakeIdGenerator, SecureIdCodec secureIdCodec) {
        IdUtils.snowflakeIdGenerator = snowflakeIdGenerator;
        IdUtils.secureIdCodec = secureIdCodec;
        log.info("IdUtils initialized with SecureIdCodec (stateless ID encryption)");
    }

    /**
     * 生成实体ID (数据库实体通用ID)
     *
     * @return 雪花算法生成的ID
     */
    public static Long nextEntityId() {
        long id = snowflakeIdGenerator.nextId();
        monitorIdGeneration(entityIdCounter, "entity");
        return id;
    }

    /**
     * 生成用户ID (适用于用户账号等敏感实体)
     *
     * @return 雪花算法生成的ID
     */
    public static Long nextUserId() {
        long id = snowflakeIdGenerator.nextId();
        monitorIdGeneration(userIdCounter, "user");
        return id;
    }

    /**
     * 生成日志ID (适用于日志记录)
     *
     * @return 日志ID (格式: L + 时间戳 + 随机数)
     */
    public static String nextLogId() {
        return "L" + System.currentTimeMillis() +
               String.format("%04d", RANDOM.nextInt(10000));
    }

    /**
     * 将内部ID转换为外部ID
     *
     * <p>使用 AES 加密，无需 Redis 缓存，确定性转换。</p>
     *
     * @param internalId 内部数据库ID
     * @return 外部ID (格式: E + Base62编码，约25字符)
     */
    public static String toExternalId(Long internalId) {
        if (internalId == null) {
            return null;
        }
        return secureIdCodec.toExternalId(internalId);
    }

    /**
     * 将用户ID转换为外部ID
     *
     * @param userId 内部用户ID
     * @return 外部用户ID (格式: U + Base62编码，约25字符)
     */
    public static String toExternalUserId(Long userId) {
        if (userId == null) {
            return null;
        }
        return secureIdCodec.toExternalUserId(userId);
    }

    /**
     * 从外部ID还原内部ID
     *
     * <p>使用 AES 解密，无需 Redis 查询。</p>
     *
     * @param externalId 外部ID
     * @return 内部ID，如果解密失败返回null
     */
    public static Long fromExternalId(String externalId) {
        if (externalId == null || externalId.isEmpty()) {
            return null;
        }
        return secureIdCodec.fromExternalId(externalId);
    }

    /**
     * 生成带前缀的ID
     *
     * @param prefix ID前缀
     * @return 带前缀的ID字符串
     */
    public static String nextIdWithPrefix(String prefix) {
        return prefix + nextEntityId();
    }

    /**
     * 生成字符串形式的实体ID
     *
     * @return 字符串形式的ID
     */
    public static String nextEntityIdStr() {
        return String.valueOf(nextEntityId());
    }

    /**
     * 监控ID生成频率（本地监控，无Redis依赖）
     *
     * @param counter 计数器
     * @param type ID类型
     */
    private static void monitorIdGeneration(AtomicLong counter, String type) {
        long now = System.currentTimeMillis();

        // 检查是否需要重置计数器（每分钟重置）
        if (now - lastResetTime > MONITOR_WINDOW_MS) {
            synchronized (IdUtils.class) {
                if (now - lastResetTime > MONITOR_WINDOW_MS) {
                    entityIdCounter.set(0);
                    userIdCounter.set(0);
                    lastResetTime = now;
                }
            }
        }

        long count = counter.incrementAndGet();

        // 超过阈值时警告（默认 1000/分钟）
        if (count == 1000) {
            log.warn("ID生成频率较高: type={}, count={}/min", type, count);
        }
    }
}
