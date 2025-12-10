package cn.flying.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 租户范围控制注解
 * 用于声明式控制方法执行时的租户隔离行为
 *
 * <p>使用场景:
 * <ul>
 *   <li>定时任务需要跨租户查询时，使用 ignoreIsolation = true</li>
 *   <li>系统级操作需要切换到特定租户执行时，指定 tenantId</li>
 * </ul>
 *
 * <p>使用示例:
 * <pre>
 * // 跨租户查询（忽略租户隔离）
 * {@code @TenantScope(ignoreIsolation = true)}
 * {@code @Scheduled(cron = "0 0 3 * * ?")}
 * public void cleanupDeletedFiles() { ... }
 *
 * // 切换到指定租户执行
 * {@code @TenantScope(tenantId = 1)}
 * public void migrateDataForTenant() { ... }
 * </pre>
 *
 * <p>注意:
 * <ul>
 *   <li>此注解由 TenantScopeAspect 切面处理</li>
 *   <li>方法执行完毕后会自动恢复原始租户上下文</li>
 *   <li>ignoreIsolation 和 tenantId 可以同时使用，ignoreIsolation 优先</li>
 * </ul>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TenantScope {

    /**
     * 是否忽略租户隔离
     * 设置为 true 时，MyBatis-Plus 租户拦截器将跳过租户过滤，
     * 可以查询/操作所有租户的数据
     *
     * @return true 表示忽略租户隔离
     */
    boolean ignoreIsolation() default false;

    /**
     * 指定租户ID
     * 用于将当前执行上下文切换到指定租户
     * 值为 -1 时表示使用当前上下文的租户ID（不切换）
     *
     * @return 目标租户ID，-1 表示不切换
     */
    long tenantId() default -1;
}
