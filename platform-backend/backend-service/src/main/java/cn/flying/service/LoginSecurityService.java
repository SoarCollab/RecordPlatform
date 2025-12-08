package cn.flying.service;

import cn.flying.common.util.Const;
import cn.flying.common.util.TenantKeyUtils;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 登录安全服务
 * 提供登录失败计数、账户锁定、解锁等功能
 * 注：登录失败计数 Key 不进行租户隔离（登录时租户上下文未建立）
 */
@Service
public class LoginSecurityService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 构建登录失败计数的 Redis Key
     */
    private String buildKey(String username) {
        return TenantKeyUtils.smartKey(Const.LOGIN_FAIL_COUNT + username);
    }

    /**
     * 检查账户是否被锁定
     *
     * @param username 用户名或邮箱
     * @return true 如果账户被锁定
     */
    public boolean isAccountLocked(String username) {
        String key = buildKey(username);
        String countStr = stringRedisTemplate.opsForValue().get(key);
        if (countStr == null) {
            return false;
        }
        try {
            int count = Integer.parseInt(countStr);
            return count >= Const.LOGIN_MAX_ATTEMPTS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 获取剩余锁定时间（秒）
     *
     * @param username 用户名或邮箱
     * @return 剩余锁定时间，-1 表示未锁定或已过期
     */
    public long getRemainingLockTime(String username) {
        String key = buildKey(username);
        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (ttl == null || ttl < 0) {
            return -1;
        }
        String countStr = stringRedisTemplate.opsForValue().get(key);
        if (countStr == null) {
            return -1;
        }
        try {
            int count = Integer.parseInt(countStr);
            if (count >= Const.LOGIN_MAX_ATTEMPTS) {
                return ttl;
            }
        } catch (NumberFormatException ignored) {
        }
        return -1;
    }

    /**
     * 记录登录失败
     *
     * @param username 用户名或邮箱
     * @return 当前失败次数
     */
    public int recordLoginFailure(String username) {
        String key = buildKey(username);
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count == null) {
            count = 1L;
        }
        // 设置过期时间（锁定时长）
        stringRedisTemplate.expire(key, Const.LOGIN_LOCK_DURATION, TimeUnit.SECONDS);
        return count.intValue();
    }

    /**
     * 清除登录失败记录（登录成功时调用）
     *
     * @param username 用户名或邮箱
     */
    public void clearLoginFailure(String username) {
        String key = buildKey(username);
        stringRedisTemplate.delete(key);
    }

    /**
     * 获取剩余尝试次数
     *
     * @param username 用户名或邮箱
     * @return 剩余尝试次数
     */
    public int getRemainingAttempts(String username) {
        String key = buildKey(username);
        String countStr = stringRedisTemplate.opsForValue().get(key);
        if (countStr == null) {
            return Const.LOGIN_MAX_ATTEMPTS;
        }
        try {
            int count = Integer.parseInt(countStr);
            return Math.max(0, Const.LOGIN_MAX_ATTEMPTS - count);
        } catch (NumberFormatException e) {
            return Const.LOGIN_MAX_ATTEMPTS;
        }
    }
}
