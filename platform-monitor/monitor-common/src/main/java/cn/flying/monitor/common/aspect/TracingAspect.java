package cn.flying.monitor.common.aspect;

import cn.flying.monitor.common.service.CorrelationIdService;
import io.micrometer.tracing.Span;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 链路追踪切面
 * 自动为Service和Repository方法创建Span
 */
@Slf4j
@Aspect
@Component
public class TracingAspect {

    private final CorrelationIdService correlationIdService;

    public TracingAspect(CorrelationIdService correlationIdService) {
        this.correlationIdService = correlationIdService;
    }

    /**
     * 追踪Service层方法
     */
    @Around("execution(* cn.flying.monitor.*.service..*(..))")
    public Object traceServiceMethods(ProceedingJoinPoint joinPoint) throws Throwable {
        return traceMethod(joinPoint, "service");
    }

    /**
     * 追踪Repository层方法
     */
    @Around("execution(* cn.flying.monitor.*.mapper..*(..))")
    public Object traceRepositoryMethods(ProceedingJoinPoint joinPoint) throws Throwable {
        return traceMethod(joinPoint, "repository");
    }

    /**
     * 追踪外部服务调用
     */
    @Around("execution(* cn.flying.monitor.*.client..*(..))")
    public Object traceClientMethods(ProceedingJoinPoint joinPoint) throws Throwable {
        return traceMethod(joinPoint, "external-client");
    }

    /**
     * 通用方法追踪逻辑
     */
    private Object traceMethod(ProceedingJoinPoint joinPoint, String layer) throws Throwable {
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        String spanName = layer + "." + className + "." + methodName;

        Span span = correlationIdService.createSpan(spanName,
            "layer", layer,
            "class", className,
            "method", methodName
        );

        try {
            span.start();
            
            // 添加方法参数信息（仅记录参数数量，避免敏感信息泄露）
            Object[] args = joinPoint.getArgs();
            span.tag("method.parameter_count", String.valueOf(args.length));

            log.debug("开始追踪方法: {}, TraceId: {}, SpanId: {}", 
                     spanName, span.context().traceId(), span.context().spanId());

            // 执行方法
            Object result = joinPoint.proceed();

            // 记录返回值类型
            if (result != null) {
                span.tag("method.return_type", result.getClass().getSimpleName());
            }

            log.debug("完成追踪方法: {}, TraceId: {}", 
                     spanName, span.context().traceId());

            return result;

        } catch (Throwable e) {
            span.tag("error", "true");
            span.tag("error.message", e.getMessage());
            span.tag("error.class", e.getClass().getSimpleName());
            correlationIdService.recordSpanError(e);

            log.error("方法追踪异常: {}, TraceId: {}", 
                     spanName, span.context().traceId(), e);
            throw e;

        } finally {
            span.end();
        }
    }
}