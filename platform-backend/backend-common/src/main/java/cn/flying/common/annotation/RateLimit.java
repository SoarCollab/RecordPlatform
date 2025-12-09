package cn.flying.common.annotation;

import java.lang.annotation.*;

/**
 * 接口限流注解
 * 支持根据角色设置不同的限流阈值
 *
 * 使用示例:
 * - @RateLimit(limit = 10, period = 60) // 普通用户60秒内最多10次
 * - @RateLimit(limit = 10, period = 60, adminLimit = 100) // 管理员60秒内最多100次
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /**
     * 普通用户限制次数
     */
    int limit() default 60;

    /**
     * 时间周期（秒）
     */
    int period() default 60;

    /**
     * 管理员限制次数（默认为普通用户的5倍）
     */
    int adminLimit() default -1;

    /**
     * 监控员限制次数（默认与管理员相同）
     */
    int monitorLimit() default -1;

    /**
     * 限流的维度
     * USER: 按用户ID限流
     * IP: 按IP限流
     * API: 按接口限流（全局）
     */
    LimitType type() default LimitType.USER;

    /**
     * 自定义限流key前缀（可选）
     */
    String key() default "";

    enum LimitType {
        USER,   // 按用户
        IP,     // 按IP
        API     // 按接口（全局）
    }
}
