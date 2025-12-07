package cn.flying.common.lock;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁注解。
 * 用于标记需要分布式锁保护的方法，防止多实例并发执行。
 * 主要应用于定时任务、幂等操作等场景。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedLock {

    /**
     * 锁的 key，支持 SpEL 表达式。
     * 默认使用方法全限定名作为 key。
     */
    String key() default "";

    /**
     * 等待获取锁的最大时间。
     * 默认 0 表示不等待，立即返回。
     */
    long waitTime() default 0;

    /**
     * 锁的自动释放时间（租约时间）。
     * 默认 60 秒，防止死锁。
     */
    long leaseTime() default 60;

    /**
     * 时间单位，默认秒。
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /**
     * 获取锁失败时是否抛出异常。
     * false 时静默跳过，适用于定时任务场景。
     */
    boolean throwOnFailure() default false;
}
