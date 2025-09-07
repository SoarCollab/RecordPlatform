package cn.flying.identity.aspect;

import cn.flying.identity.config.AuditMonitorConfig;
import cn.flying.identity.dto.AuditLog;
import cn.flying.identity.event.AuditEventPublisher;
import cn.flying.identity.util.IpUtils;
import cn.flying.identity.util.UserAgentUtils;
import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.Arrays;

/**
 * 审计日志切面
 * 用于自动记录方法调用的审计日志
 * 
 * @author flying
 * @date 2024
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditLogAspect {

    private final AuditEventPublisher auditEventPublisher;
    private final AuditMonitorConfig auditMonitorConfig;

    /**
     * 定义切点：所有Controller方法
     */
    @Pointcut("execution(* cn.flying.identity.controller..*.*(..))")
    public void controllerMethods() {}

    /**
     * 定义切点：所有Service方法
     */
    @Pointcut("execution(* cn.flying.identity.service..*.*(..))")
    public void serviceMethods() {}

    /**
     * 环绕通知：记录Controller方法调用
     * 
     * @param joinPoint 连接点
     * @return 方法执行结果
     * @throws Throwable 异常
     */
    @Around("controllerMethods()")
    public Object aroundControllerMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!auditMonitorConfig.getAuditLog().isEnabled()) {
            return joinPoint.proceed();
        }

        long startTime = System.currentTimeMillis();
        String methodName = joinPoint.getSignature().getName();
        String className = joinPoint.getTarget().getClass().getSimpleName();
        Object[] args = joinPoint.getArgs();
        
        // 获取HTTP请求信息
        HttpServletRequest request = getCurrentRequest();
        String clientIp = request != null ? IpUtils.getClientIp(request) : "unknown";
        String userAgent = request != null ? UserAgentUtils.getUserAgent(request) : "unknown";
        String requestUrl = request != null ? request.getRequestURL().toString() : "unknown";
        String requestMethod = request != null ? request.getMethod() : "unknown";
        
        AuditLog auditLog = new AuditLog();
        auditLog.setId(IdUtil.fastSimpleUUID());
        auditLog.setOperationType(getOperationType(methodName, requestMethod));
        auditLog.setModule(getModule(className));
        auditLog.setOperationDesc(String.format("%s.%s", className, methodName));
        auditLog.setClientIp(clientIp);
        auditLog.setUserAgent(userAgent);
        auditLog.setRequestUrl(requestUrl);
        auditLog.setRequestMethod(requestMethod);
        auditLog.setCreateTime(LocalDateTime.now());
        
        // 记录请求参数
        if (auditMonitorConfig.getAuditLog().isLogRequestParams() && args.length > 0) {
            try {
                String requestParams = JSONUtil.toJsonStr(filterSensitiveParams(args));
                auditLog.setRequestParams(requestParams);
            } catch (Exception e) {
                auditLog.setRequestParams("参数序列化失败");
            }
        }
        
        Object result = null;
        boolean success = true;
        String errorMessage = null;
        
        try {
            result = joinPoint.proceed();
        } catch (Exception e) {
            success = false;
            errorMessage = e.getMessage();
            log.error("方法执行异常: {}.{}", className, methodName, e);
            throw e;
        } finally {
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;
            
            auditLog.setIsSuccess(success);
            auditLog.setErrorMessage(errorMessage);
            auditLog.setExecutionTime(duration);
            
            // 记录响应结果
            if (auditMonitorConfig.getAuditLog().isLogResponseResult() && success && result != null) {
                try {
                    String responseResult = JSONUtil.toJsonStr(result);
                    auditLog.setResponseResult(responseResult);
                } catch (Exception e) {
                    auditLog.setResponseResult("响应序列化失败");
                }
            }
            
            // 异步发布审计事件
            if (auditMonitorConfig.getAuditLog().isAsyncEnabled()) {
                auditEventPublisher.publishAuditLogEvent(auditLog);
            } else {
                // 同步记录（这里可以直接调用service保存）
                log.info("审计日志: {}", JSONUtil.toJsonStr(auditLog));
            }
        }
        
        return result;
    }

    /**
     * 异常通知：记录异常信息
     * 
     * @param joinPoint 连接点
     * @param exception 异常
     */
    @AfterThrowing(pointcut = "serviceMethods()", throwing = "exception")
    public void afterThrowing(JoinPoint joinPoint, Exception exception) {
        if (!auditMonitorConfig.getAuditLog().isEnabled()) {
            return;
        }

        String methodName = joinPoint.getSignature().getName();
        String className = joinPoint.getTarget().getClass().getSimpleName();
        
        // 记录系统异常日志
        AuditLog auditLog = new AuditLog();
        auditLog.setId(IdUtil.fastSimpleUUID());
        auditLog.setOperationType("SYSTEM_ERROR");
        auditLog.setModule("SYSTEM");
        auditLog.setOperationDesc(String.format("系统异常: %s.%s", className, methodName));
        auditLog.setIsSuccess(false);
        auditLog.setErrorMessage(exception.getMessage());
        auditLog.setCreateTime(LocalDateTime.now());
        
        HttpServletRequest request = getCurrentRequest();
        if (request != null) {
            auditLog.setClientIp(IpUtils.getClientIp(request));
            auditLog.setUserAgent(UserAgentUtils.getUserAgent(request));
        }
        
        auditEventPublisher.publishAuditLogEvent(auditLog, "SYSTEM_ERROR");
    }

    /**
     * 获取当前HTTP请求
     * 
     * @return HTTP请求对象
     */
    private HttpServletRequest getCurrentRequest() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attributes != null ? attributes.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 根据方法名和请求方法获取操作类型
     * 
     * @param methodName 方法名
     * @param requestMethod 请求方法
     * @return 操作类型
     */
    private String getOperationType(String methodName, String requestMethod) {
        String lowerMethodName = methodName.toLowerCase();
        
        if (lowerMethodName.contains("login")) {
            return AuditMonitorConfig.Constants.OPERATION_LOGIN;
        } else if (lowerMethodName.contains("logout")) {
            return AuditMonitorConfig.Constants.OPERATION_LOGOUT;
        } else if (lowerMethodName.contains("create") || lowerMethodName.contains("add") || lowerMethodName.contains("save")) {
            return AuditMonitorConfig.Constants.OPERATION_CREATE;
        } else if (lowerMethodName.contains("update") || lowerMethodName.contains("modify") || lowerMethodName.contains("edit")) {
            return AuditMonitorConfig.Constants.OPERATION_UPDATE;
        } else if (lowerMethodName.contains("delete") || lowerMethodName.contains("remove")) {
            return AuditMonitorConfig.Constants.OPERATION_DELETE;
        } else if (lowerMethodName.contains("export")) {
            return AuditMonitorConfig.Constants.OPERATION_EXPORT;
        } else if (lowerMethodName.contains("import")) {
            return AuditMonitorConfig.Constants.OPERATION_IMPORT;
        } else if ("POST".equals(requestMethod)) {
            return AuditMonitorConfig.Constants.OPERATION_CREATE;
        } else if ("PUT".equals(requestMethod) || "PATCH".equals(requestMethod)) {
            return AuditMonitorConfig.Constants.OPERATION_UPDATE;
        } else if ("DELETE".equals(requestMethod)) {
            return AuditMonitorConfig.Constants.OPERATION_DELETE;
        } else {
            return AuditMonitorConfig.Constants.OPERATION_VIEW;
        }
    }

    /**
     * 根据类名获取模块
     * 
     * @param className 类名
     * @return 模块名
     */
    private String getModule(String className) {
        String lowerClassName = className.toLowerCase();
        
        if (lowerClassName.contains("auth") || lowerClassName.contains("login")) {
            return AuditMonitorConfig.Constants.MODULE_AUTH;
        } else if (lowerClassName.contains("user")) {
            return AuditMonitorConfig.Constants.MODULE_USER;
        } else if (lowerClassName.contains("role")) {
            return AuditMonitorConfig.Constants.MODULE_ROLE;
        } else if (lowerClassName.contains("permission")) {
            return AuditMonitorConfig.Constants.MODULE_PERMISSION;
        } else if (lowerClassName.contains("oauth")) {
            return AuditMonitorConfig.Constants.MODULE_OAUTH;
        } else if (lowerClassName.contains("record")) {
            return AuditMonitorConfig.Constants.MODULE_RECORD;
        } else if (lowerClassName.contains("file")) {
            return AuditMonitorConfig.Constants.MODULE_FILE;
        } else {
            return AuditMonitorConfig.Constants.MODULE_SYSTEM;
        }
    }

    /**
     * 过滤敏感参数
     * 
     * @param args 方法参数
     * @return 过滤后的参数
     */
    private Object[] filterSensitiveParams(Object[] args) {
        if (args == null || args.length == 0) {
            return args;
        }
        
        return Arrays.stream(args)
            .map(arg -> {
                if (arg == null) {
                    return null;
                }
                
                String argStr = arg.toString();
                // 过滤密码等敏感信息
                if (argStr.toLowerCase().contains("password") || 
                    argStr.toLowerCase().contains("token") ||
                    argStr.toLowerCase().contains("secret")) {
                    return "[FILTERED]"; 
                }
                
                return arg;
            })
            .toArray();
    }
}