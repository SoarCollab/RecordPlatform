package cn.flying.identity.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 性能监控切面
 * 监控API Gateway相关接口的执行时间和调用频率
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
@Aspect
@Component
public class ApiPerformanceAspect {

    /**
     * 用于统计方法调用次数
     */
    private final ConcurrentHashMap<String, AtomicLong> methodCallCount = new ConcurrentHashMap<>();

    /**
     * 用于统计方法总耗时
     */
    private final ConcurrentHashMap<String, AtomicLong> methodTotalTime = new ConcurrentHashMap<>();

    /**
     * 用于记录方法最大耗时
     */
    private final ConcurrentHashMap<String, Long> methodMaxTime = new ConcurrentHashMap<>();

    /**
     * 慢查询阈值（毫秒）
     */
    private static final long SLOW_QUERY_THRESHOLD = 1000L;

    /**
     * 定义切入点：所有API Gateway控制器的方法
     */
    @Pointcut("execution(* cn.flying.identity.controller.apigateway.*Controller.*(..))")
    public void apiControllerMethods() {
    }

    /**
     * 定义切入点：所有API Gateway服务层的方法
     */
    @Pointcut("execution(* cn.flying.identity.service.impl.apigateway.*ServiceImpl.*(..))")
    public void apiServiceMethods() {
    }

    /**
     * 监控控制器方法执行
     *
     * @param joinPoint 连接点
     * @return 方法执行结果
     * @throws Throwable 异常
     */
    @Around("apiControllerMethods()")
    public Object monitorController(ProceedingJoinPoint joinPoint) throws Throwable {
        return monitorMethod(joinPoint, "Controller");
    }

    /**
     * 监控服务层方法执行
     *
     * @param joinPoint 连接点
     * @return 方法执行结果
     * @throws Throwable 异常
     */
    @Around("apiServiceMethods()")
    public Object monitorService(ProceedingJoinPoint joinPoint) throws Throwable {
        return monitorMethod(joinPoint, "Service");
    }

    /**
     * 通用方法监控逻辑
     *
     * @param joinPoint 连接点
     * @param layer     层级（Controller/Service）
     * @return 方法执行结果
     * @throws Throwable 异常
     */
    private Object monitorMethod(ProceedingJoinPoint joinPoint, String layer) throws Throwable {
        // 获取方法签名
        String methodName = joinPoint.getSignature().toShortString();

        // 记录开始时间
        long startTime = System.currentTimeMillis();

        try {
            // 执行方法
            Object result = joinPoint.proceed();

            // 记录成功执行
            long executionTime = System.currentTimeMillis() - startTime;
            recordMetrics(methodName, executionTime);

            // 如果是慢查询，记录警告日志
            if (executionTime > SLOW_QUERY_THRESHOLD) {
                log.warn("【慢查询】{} 层方法 {} 执行时间: {}ms", layer, methodName, executionTime);
            } else if (log.isDebugEnabled()) {
                log.debug("{} 层方法 {} 执行时间: {}ms", layer, methodName, executionTime);
            }

            return result;

        } catch (Throwable e) {
            // 记录失败执行
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("{} 层方法 {} 执行失败，耗时: {}ms，异常: {}",
                      layer, methodName, executionTime, e.getMessage());
            throw e;
        }
    }

    /**
     * 记录方法执行指标
     *
     * @param methodName    方法名
     * @param executionTime 执行时间（毫秒）
     */
    private void recordMetrics(String methodName, long executionTime) {
        // 更新调用次数
        methodCallCount.computeIfAbsent(methodName, k -> new AtomicLong(0)).incrementAndGet();

        // 更新总耗时
        methodTotalTime.computeIfAbsent(methodName, k -> new AtomicLong(0)).addAndGet(executionTime);

        // 更新最大耗时
        methodMaxTime.merge(methodName, executionTime, Long::max);

        // 每100次调用输出一次统计信息
        long callCount = methodCallCount.get(methodName).get();
        if (callCount % 100 == 0) {
            long totalTime = methodTotalTime.get(methodName).get();
            long maxTime = methodMaxTime.get(methodName);
            long avgTime = totalTime / callCount;

            log.info("【性能统计】方法: {}，调用次数: {}，平均耗时: {}ms，最大耗时: {}ms",
                     methodName, callCount, avgTime, maxTime);
        }
    }

    /**
     * 获取所有方法的性能统计信息
     *
     * @return 性能统计信息
     */
    public String getPerformanceStats() {
        StringBuilder stats = new StringBuilder("\n========== API性能统计 ==========\n");

        methodCallCount.forEach((method, count) -> {
            long totalTime = methodTotalTime.get(method).get();
            long maxTime = methodMaxTime.getOrDefault(method, 0L);
            long avgTime = count.get() > 0 ? totalTime / count.get() : 0;

            stats.append(String.format("方法: %s\n", method));
            stats.append(String.format("  - 调用次数: %d\n", count.get()));
            stats.append(String.format("  - 总耗时: %dms\n", totalTime));
            stats.append(String.format("  - 平均耗时: %dms\n", avgTime));
            stats.append(String.format("  - 最大耗时: %dms\n", maxTime));
            stats.append("\n");
        });

        stats.append("==================================\n");
        return stats.toString();
    }

    /**
     * 重置性能统计数据
     */
    public void resetStats() {
        methodCallCount.clear();
        methodTotalTime.clear();
        methodMaxTime.clear();
        log.info("性能统计数据已重置");
    }
}