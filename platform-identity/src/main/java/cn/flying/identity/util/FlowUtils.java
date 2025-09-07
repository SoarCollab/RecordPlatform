package cn.flying.identity.util;

import cn.flying.identity.constant.Const;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 流量控制工具类
 * 用于限制请求频率
 *
 * @author 王贝强
 */
@Slf4j
@Component
public class FlowUtils {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 检查IP地址的邮件验证码请求频率
     *
     * @param ipAddress    IP地址
     * @param limitSeconds 限制时间（秒）
     * @return true-通过检查，false-被限流
     */
    public boolean checkEmailVerifyLimit(String ipAddress, int limitSeconds) {
        String key = Const.VERIFY_EMAIL_LIMIT + ipAddress;
        return limitOnceCheck(key, limitSeconds);
    }

    /**
     * 针对指定键进行单次检查限流
     *
     * @param key       限流键
     * @param blockTime 阻塞时间（秒）
     * @return true-通过限流检查，false-被限流
     */
    public boolean limitOnceCheck(String key, int blockTime) {
        try {
            // 检查是否存在限流键
            if (stringRedisTemplate.hasKey(key)) {
                return false; // 已被限流
            }

            // 设置限流键，过期时间为阻塞时间
            stringRedisTemplate.opsForValue().set(key, "1", blockTime, TimeUnit.SECONDS);
            return true; // 通过限流检查
        } catch (Exception e) {
            log.error("限流检查异常，键: {}", key, e);
            // 异常情况下允许通过，避免影响正常业务
            return true;
        }
    }

    /**
     * 检查用户登录频率
     *
     * @param identifier  用户标识（用户名、邮箱或IP）
     * @param maxAttempts 最大尝试次数
     * @param timeWindow  时间窗口（秒）
     * @return true-通过检查，false-被限流
     */
    public boolean checkLoginLimit(String identifier, int maxAttempts, int timeWindow) {
        String key = "login:limit:" + identifier;
        return limitCountCheck(key, maxAttempts, timeWindow);
    }

    /**
     * 针对指定键进行计数限流
     *
     * @param key        限流键
     * @param maxCount   最大计数
     * @param timeWindow 时间窗口（秒）
     * @return true-通过限流检查，false-被限流
     */
    public boolean limitCountCheck(String key, int maxCount, int timeWindow) {
        try {
            // 获取当前计数
            String countStr = stringRedisTemplate.opsForValue().get(key);
            int currentCount = countStr != null ? Integer.parseInt(countStr) : 0;

            if (currentCount >= maxCount) {
                return false; // 超过最大计数，被限流
            }

            // 增加计数
            Long newCount = stringRedisTemplate.opsForValue().increment(key);

            // 如果是第一次计数，设置过期时间
            if (newCount != null && newCount == 1) {
                stringRedisTemplate.expire(key, timeWindow, TimeUnit.SECONDS);
            }

            return true; // 通过限流检查
        } catch (Exception e) {
            log.error("计数限流检查异常，键: {}", key, e);
            // 异常情况下允许通过，避免影响正常业务
            return true;
        }
    }

    /**
     * 记录登录失败次数
     *
     * @param identifier 用户标识
     * @param timeWindow 时间窗口（秒）
     * @return 当前失败次数
     */
    public int recordLoginFailure(String identifier, int timeWindow) {
        try {
            String key = "login:failure:" + identifier;
            Long count = stringRedisTemplate.opsForValue().increment(key);

            // 如果是第一次失败，设置过期时间
            if (count != null && count == 1) {
                stringRedisTemplate.expire(key, timeWindow, TimeUnit.SECONDS);
            }

            return count != null ? count.intValue() : 1;
        } catch (Exception e) {
            log.error("记录登录失败次数异常，标识: {}", identifier, e);
            return 1;
        }
    }

    /**
     * 清除登录失败记录
     *
     * @param identifier 用户标识
     */
    public void clearLoginFailure(String identifier) {
        try {
            String key = "login:failure:" + identifier;
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.error("清除登录失败记录异常，标识: {}", identifier, e);
        }
    }

    /**
     * 获取登录失败次数
     *
     * @param identifier 用户标识
     * @return 失败次数
     */
    public int getLoginFailureCount(String identifier) {
        try {
            String key = "login:failure:" + identifier;
            String countStr = stringRedisTemplate.opsForValue().get(key);
            return countStr != null ? Integer.parseInt(countStr) : 0;
        } catch (Exception e) {
            log.error("获取登录失败次数异常，标识: {}", identifier, e);
            return 0;
        }
    }

    /**
     * 针对于在时间段内多次请求限制，如3秒内限制请求20次
     *
     * @param counterKey 计数键
     * @param frequency  请求频率
     * @param period     计数周期
     * @return true-通过限流检查，false-被限流
     */
    public boolean limitPeriodCountCheck(String counterKey, int frequency, int period) {
        try {
            // 获取当前计数
            String countStr = stringRedisTemplate.opsForValue().get(counterKey);

            if (countStr != null) {
                // 增加计数
                Long newCount = stringRedisTemplate.opsForValue().increment(counterKey);

                // 如果计数不连续，重新设置过期时间
                int currentCount = Integer.parseInt(countStr);
                if (newCount != null && newCount != currentCount + 1) {
                    stringRedisTemplate.expire(counterKey, period, TimeUnit.SECONDS);
                }

                // 检查是否超过频率限制
                return newCount == null || newCount <= frequency;
            } else {
                // 第一次请求，设置计数为1并设置过期时间
                stringRedisTemplate.opsForValue().set(counterKey, "1", period, TimeUnit.SECONDS);
                return true; // 通过限流检查
            }
        } catch (Exception e) {
            log.error("周期限流检查异常，键: {}", counterKey, e);
            // 异常情况下允许通过，避免影响正常业务
            return true;
        }
    }

    /**
     * 检查API调用频率
     *
     * @param apiKey     API标识
     * @param maxCalls   最大调用次数
     * @param timeWindow 时间窗口（秒）
     * @return true-通过检查，false-被限流
     */
    public boolean checkApiLimit(String apiKey, int maxCalls, int timeWindow) {
        String key = "api:limit:" + apiKey;
        return limitCountCheck(key, maxCalls, timeWindow);
    }

    /**
     * 移除指定的限流键
     *
     * @param key 限流键
     */
    public void removeLimit(String key) {
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.error("移除限流键异常，键: {}", key, e);
        }
    }

    /**
     * 获取限流键的剩余时间
     *
     * @param key 限流键
     * @return 剩余时间（秒），-1表示键不存在或无过期时间
     */
    public long getLimitRemainTime(String key) {
        try {
            return stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("获取限流键剩余时间异常，键: {}", key, e);
            return -1;
        }
    }
}
