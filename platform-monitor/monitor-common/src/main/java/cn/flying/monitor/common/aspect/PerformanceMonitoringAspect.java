package cn.flying.monitor.common.aspect;

import cn.flying.monitor.common.annotation.Monitored;
import cn.flying.monitor.common.service.CustomMetricsService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;

/**
 * 性能监控切面
 * 自动收集标注了@Monitored的方法的性能指标
 */
@Slf4j
@Aspect
@Component
public class PerformanceMonitoringAspect {

    private final MeterRegistry meterRegistry;
    private final CustomMetricsService customMetricsService;

    public PerformanceMonitoringAspect(MeterRegistry meterRegistry, CustomMetricsService customMetricsService) {
        this.meterRegistry = meterRegistry;
        this.customMetricsService = customMetricsService;
    }

    /**
     * 监控Service层方法性能
     */
    @Around("execution(* cn.flying.monitor.*.service..*(..))")
    public Object monitorServiceMethods(ProceedingJoinPoint joinPoint) throws Throwable {
        return monitorMethod(joinPoint, "service");
    }

    /**
     * 监控Controller层方法性能
     */
    @Around("execution(* cn.flying.monitor.*.controller..*(..))")
    public Object monitorControllerMethods(ProceedingJoinPoint joinPoint) throws Throwable {
        return monitorMethod(joinPoint, "controller");
    }

    /**
     * 监控Repository层方法性能
     */
    @Around("execution(* cn.flying.monitor.*.mapper..*(..))")
    public Object monitorRepositoryMethods(ProceedingJoinPoint joinPoint) throws Throwable {
        return monitorMethod(joinPoint, "repository");
    }

    /**
     * 监控标注了@Monitored注解的方法
     */
    @Around("@annotation(cn.flying.monitor.common.annotation.Monitored)")
    public Object monitorAnnotatedMethods(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Monitored monitored = method.getAnnotation(Monitored.class);
        
        return monitorAnnotatedMethod(joinPoint, monitored);
    }

    /**
     * 通用方法监控逻辑
     */
    private Object monitorMethod(ProceedingJoinPoint joinPoint, String layer) throws Throwable {
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        String fullMethodName = className + "." + methodName;

        Instant startTime = Instant.now();
        boolean success = true;
        Throwable exception = null;

        try {
            Object result = joinPoint.proceed();
            return result;
        } catch (Throwable e) {
            success = false;
            exception = e;
            throw e;
        } finally {
            Duration duration = Duration.between(startTime, Instant.now());

            // 记录方法执行时间
            Timer.builder("monitor.method.execution.time")
                .description("方法执行时间")
                .tag("layer", layer)
                .tag("class", className)
                .tag("method", methodName)
                .tag("success", String.valueOf(success))
                .register(meterRegistry)
                .record(duration);

            // 记录方法调用次数
            customMetricsService.createCounter(
                "monitor.method.invocations",
                "方法调用次数",
                "layer", layer,
                "class", className,
                "method", methodName,
                "success", String.valueOf(success)
            ).increment();

            // 记录异常
            if (exception != null) {
                customMetricsService.createCounter(
                    "monitor.method.exceptions",
                    "方法异常统计",
                    "layer", layer,
                    "class", className,
                    "method", methodName,
                    "exception_type", exception.getClass().getSimpleName()
                ).increment();
            }

            // 记录慢方法
            if (duration.toMillis() > 500) { // 超过500ms的方法
                customMetricsService.createCounter(
                    "monitor.method.slow.executions",
                    "慢方法执行统计",
                    "layer", layer,
                    "class", className,
                    "method", methodName,
                    "duration_range", getDurationRange(duration)
                ).increment();

                log.warn("慢方法执行: {}.{} - {}ms", className, methodName, duration.toMillis());
            }

            log.debug("方法执行指标: {} - {}ms - 成功: {}", fullMethodName, duration.toMillis(), success);
        }
    }

    /**
     * 获取执行时间范围
     */
    private String getDurationRange(Duration duration) {
        long millis = duration.toMillis();
        if (millis < 100) {
            return "0-100ms";
        } else if (millis < 500) {
            return "100-500ms";
        } else if (millis < 1000) {
            return "500ms-1s";
        } else if (millis < 5000) {
            return "1-5s";
        } else {
            return "5s+";
        }
    }

    /**
     * 监控标注了@Monitored注解的方法
     */
    private Object monitorAnnotatedMethod(ProceedingJoinPoint joinPoint, Monitored monitored) throws Throwable {
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        String metricName = monitored.value().isEmpty() ? methodName : monitored.value();
        String description = monitored.description().isEmpty() ? "自定义监控方法" : monitored.description();

        Instant startTime = Instant.now();
        boolean success = true;
        Throwable exception = null;
        Object result = null;

        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable e) {
            success = false;
            exception = e;
            throw e;
        } finally {
            Duration duration = Duration.between(startTime, Instant.now());

            // 记录自定义方法执行时间
            Timer.builder("monitor.custom.method.execution.time")
                .description(description)
                .tag("class", className)
                .tag("method", metricName)
                .tag("success", String.valueOf(success))
                .register(meterRegistry)
                .record(duration);

            // 记录方法调用次数
            customMetricsService.createCounter(
                "monitor.custom.method.invocations",
                description + " - 调用次数",
                "class", className,
                "method", metricName,
                "success", String.valueOf(success)
            ).increment();

            // 记录参数信息（如果启用）
            if (monitored.recordParameters()) {
                Object[] args = joinPoint.getArgs();
                customMetricsService.createCounter(
                    "monitor.custom.method.parameters",
                    description + " - 参数统计",
                    "class", className,
                    "method", metricName,
                    "parameter_count", String.valueOf(args.length)
                ).increment();
            }

            // 记录返回值信息（如果启用）
            if (monitored.recordResult() && result != null) {
                customMetricsService.createCounter(
                    "monitor.custom.method.results",
                    description + " - 返回值统计",
                    "class", className,
                    "method", metricName,
                    "result_type", result.getClass().getSimpleName()
                ).increment();
            }

            // 检查是否为慢执行
            if (duration.toMillis() > monitored.slowThreshold()) {
                customMetricsService.createCounter(
                    "monitor.custom.method.slow.executions",
                    description + " - 慢执行统计",
                    "class", className,
                    "method", metricName,
                    "threshold", String.valueOf(monitored.slowThreshold())
                ).increment();

                log.warn("慢方法执行 [{}]: {}.{} - {}ms (阈值: {}ms)", 
                        description, className, metricName, duration.toMillis(), monitored.slowThreshold());
            }

            log.debug("自定义方法监控 [{}]: {}.{} - {}ms - 成功: {}", 
                     description, className, metricName, duration.toMillis(), success);
        }
    }
}