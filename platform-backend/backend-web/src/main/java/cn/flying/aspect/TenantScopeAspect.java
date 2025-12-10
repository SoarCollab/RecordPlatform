package cn.flying.aspect;

import cn.flying.common.annotation.TenantScope;
import cn.flying.common.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 租户范围切面
 * 处理 @TenantScope 注解，实现声明式的租户上下文控制
 *
 * <p>功能:
 * <ul>
 *   <li>ignoreIsolation=true: 在方法执行期间禁用租户隔离</li>
 *   <li>tenantId>0: 在方法执行期间切换到指定租户</li>
 *   <li>方法执行完毕后自动恢复原始租户上下文</li>
 * </ul>
 *
 * <p>执行顺序: 在事务切面之前执行 (Ordered.HIGHEST_PRECEDENCE + 10)，
 * 确保租户上下文在事务开始前已经设置好
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TenantScopeAspect {

    /**
     * 处理带有 @TenantScope 注解的方法
     *
     * @param pjp 切入点
     * @param tenantScope 注解实例
     * @return 方法返回值
     * @throws Throwable 方法抛出的异常
     */
    @Around("@annotation(tenantScope)")
    public Object handleTenantScope(ProceedingJoinPoint pjp, TenantScope tenantScope) throws Throwable {
        // 保存原始状态
        Long originalTenantId = TenantContext.getTenantId();
        boolean originalIgnoreIsolation = TenantContext.isIgnoreIsolation();

        String methodName = pjp.getSignature().toShortString();

        try {
            // 应用注解配置
            if (tenantScope.ignoreIsolation()) {
                TenantContext.setIgnoreIsolation(true);
                log.debug("[TenantScope] Method {} executing with tenant isolation DISABLED", methodName);
            }

            if (tenantScope.tenantId() > 0) {
                TenantContext.setTenantId(tenantScope.tenantId());
                log.debug("[TenantScope] Method {} executing as tenant {}", methodName, tenantScope.tenantId());
            }

            // 执行目标方法
            return pjp.proceed();

        } finally {
            // 恢复原始状态
            TenantContext.setIgnoreIsolation(originalIgnoreIsolation);

            if (originalTenantId == null) {
                // 如果原来没有设置租户ID，则清除
                TenantContext.clear();
            } else {
                TenantContext.setTenantId(originalTenantId);
            }

            log.debug("[TenantScope] Method {} completed, restored tenant context: tenantId={}, ignoreIsolation={}",
                    methodName, originalTenantId, originalIgnoreIsolation);
        }
    }

    /**
     * 处理类级别的 @TenantScope 注解
     * 当类上有 @TenantScope 注解时，对该类的所有 public 方法生效
     *
     * @param pjp 切入点
     * @param tenantScope 注解实例
     * @return 方法返回值
     * @throws Throwable 方法抛出的异常
     */
    @Around("@within(tenantScope)")
    public Object handleClassLevelTenantScope(ProceedingJoinPoint pjp, TenantScope tenantScope) throws Throwable {
        // 方法级别的注解优先，如果方法上有注解，跳过类级别处理
        // （方法级别的切面会单独处理）
        if (hasMethodLevelAnnotation(pjp)) {
            return pjp.proceed();
        }

        // 复用方法级别的处理逻辑
        return handleTenantScope(pjp, tenantScope);
    }

    /**
     * 检查方法上是否有 @TenantScope 注解
     */
    private boolean hasMethodLevelAnnotation(ProceedingJoinPoint pjp) {
        try {
            var method = ((org.aspectj.lang.reflect.MethodSignature) pjp.getSignature()).getMethod();
            return method.isAnnotationPresent(TenantScope.class);
        } catch (Exception e) {
            return false;
        }
    }
}
