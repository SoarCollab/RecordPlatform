package cn.flying.identity.aspect;

import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.annotation.Log;
import cn.flying.identity.config.OperationLogMonitorConfig;
import cn.flying.identity.dto.OperationLog;
import cn.flying.identity.service.OperationLogService;
import cn.flying.identity.util.IdUtils;
import cn.flying.identity.util.IpUtils;
import cn.flying.identity.util.UserAgentUtils;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 操作日志切面处理类
 * 整合了原 AuditLog 功能，用于处理 @Log 注解和自动记录操作日志
 * 支持风险评估、地理位置、设备信息等高级功能
 *
 * @author 王贝强
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperationLogAspect {

    @Resource
    private OperationLogService operationLogService;

    @Resource
    private OperationLogMonitorConfig operationLogMonitorConfig;

    /**
     * 定义切点：使用 @Log 注解的方法
     */
    @Pointcut("@annotation(cn.flying.identity.annotation.Log)")
    public void operationLogPointCut() {
    }

    /**
     * 定义切点：所有Controller方法（自动记录）
     */
    @Pointcut("execution(* cn.flying.identity.controller..*.*(..))")
    public void controllerMethods() {
    }

    /**
     * 定义切点：所有Service方法（异常记录）
     */
    @Pointcut("execution(* cn.flying.identity.service..*.*(..))")
    public void serviceMethods() {
    }

    /**
     * 环绕通知，记录操作日志
     */
    @Around("operationLogPointCut()")
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();

        // 获取请求信息
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = null;
        if (attributes != null) {
            request = attributes.getRequest();

            // 设置请求ID到MDC
            String reqId = IdUtils.nextIdWithPrefix("REQ");
            MDC.put("reqId", reqId);
        }

        // 获取操作注解
        Log log = getOperationLogAnnotation(joinPoint);

        // 记录请求开始日志
        if (request != null && log != null) {
            logRequestStart(request, joinPoint, log);
        }

        Object result = null;
        Exception exception = null;

        try {
            // 执行原方法
            result = joinPoint.proceed();
        } catch (Exception e) {
            exception = e;
            throw e;
        } finally {
            long endTime = System.currentTimeMillis();
            long executionTime = endTime - startTime;

            // 记录操作日志
            if (request != null && log != null) {
                if (log.async()) {
                    // 异步记录日志
                    recordOperationLogAsync(request, joinPoint, log, result, exception, executionTime);
                } else {
                    // 同步记录日志
                    recordOperationLog(request, joinPoint, log, result, exception, executionTime);
                }
            }

            // 清理MDC
            MDC.clear();
        }

        return result;
    }

    /**
     * 环绕通知：自动记录Controller方法调用
     *
     * @param joinPoint 连接点
     * @return 方法执行结果
     * @throws Throwable 异常
     */
    @Around("controllerMethods() && !operationLogPointCut()")
    public Object aroundControllerMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!operationLogMonitorConfig.getOperationLog().isEnabled()) {
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

        // 设置请求ID到MDC
        String reqId = IdUtils.nextIdWithPrefix("REQ");
        MDC.put("reqId", reqId);

        Object result = null;
        Exception exception = null;

        try {
            // 执行原方法
            result = joinPoint.proceed();
        } catch (Exception e) {
            exception = e;
            throw e;
        } finally {
            long endTime = System.currentTimeMillis();
            long executionTime = endTime - startTime;

            // 自动记录操作日志
            try {
                OperationLog logEntity = buildAutoOperationLogEntity(
                        request, joinPoint, result, exception, executionTime,
                        clientIp, userAgent, requestUrl, requestMethod, 
                        methodName, className, args
                );

                if (operationLogMonitorConfig.getOperationLog().isAsyncEnabled()) {
                    recordOperationLogAsync(logEntity);
                } else {
                    operationLogService.saveOperationLog(logEntity);
                }
            } catch (Exception e) {
                log.error("自动记录操作日志异常", e);
            }

            // 清理MDC
            MDC.clear();
        }

        return result;
    }

    /**
     * 异常通知：记录Service方法异常
     */
    @AfterThrowing(pointcut = "serviceMethods()", throwing = "exception")
    public void afterThrowing(JoinPoint joinPoint, Exception exception) {
        if (!operationLogMonitorConfig.getOperationLog().isEnabled()) {
            return;
        }

        try {
            String methodName = joinPoint.getSignature().getName();
            String className = joinPoint.getTarget().getClass().getSimpleName();

            OperationLog logEntity = new OperationLog();
            logEntity.setId(IdUtils.nextUserId());
            logEntity.setOperationTime(LocalDateTime.now());
            logEntity.setModule(getModule(className));
            logEntity.setOperationType("ERROR");
            logEntity.setDescription(String.format("Service异常: %s.%s", className, methodName));
            logEntity.setClassName(className);
            logEntity.setMethodName(methodName);
            logEntity.setStatus(0); // 失败
            logEntity.setErrorMsg(exception.getMessage());
            logEntity.setRiskLevel(calculateRiskLevel(exception.getClass().getSimpleName()));

            // 异步记录
            recordOperationLogAsync(logEntity);
        } catch (Exception e) {
            log.error("记录Service异常日志失败", e);
        }
    }

    /**
     * 记录请求开始日志
     */
    private void logRequestStart(HttpServletRequest request, ProceedingJoinPoint joinPoint, Log log) {
        try {
            String username = getCurrentUsername();
            String userRole = getCurrentUserRole();
            String clientIp = IpUtils.getClientIp(request);
            String requestParams = getRequestParams(joinPoint);

            OperationLogAspect.log.info("操作日志 - 开始 | 模块: {} | 类型: {} | 描述: {} | URL: \"{}\" ({}) | IP: {} | 用户: {} | 角色: {} | 参数: {}",
                    log.module(), log.operationType(), log.description(),
                    request.getServletPath(), request.getMethod(), clientIp, username, userRole, requestParams);
        } catch (Exception e) {
            OperationLogAspect.log.error("记录请求开始日志异常", e);
        }
    }

    /**
     * 同步记录操作日志
     */
    private void recordOperationLog(HttpServletRequest request, ProceedingJoinPoint joinPoint,
                                    Log log, Object result, Exception exception, long executionTime) {
        try {
            OperationLog logEntity = buildOperationLogEntity(request, joinPoint, log, result, exception, executionTime);
            operationLogService.saveOperationLog(logEntity);

            // 记录完成日志
            String status = exception == null ? "成功" : "失败";
            OperationLogAspect.log.info("操作日志 - 完成 | 状态: {} | 耗时: {}ms | 模块: {} | 描述: {}",
                    status, executionTime, log.module(), log.description());
        } catch (Exception e) {
            OperationLogAspect.log.error("记录操作日志异常", e);
        }
    }

    /**
     * 异步记录操作日志
     */
    @Async
    public void recordOperationLogAsync(HttpServletRequest request, ProceedingJoinPoint joinPoint,
                                        Log log, Object result, Exception exception, long executionTime) {
        recordOperationLog(request, joinPoint, log, result, exception, executionTime);
    }

    /**
     * 构建操作日志实体
     */
    private OperationLog buildOperationLogEntity(HttpServletRequest request, ProceedingJoinPoint joinPoint,
                                                 Log log, Object result, Exception exception, long executionTime) {
        OperationLog logEntity = new OperationLog();

        // 基本信息
        logEntity.setId(IdUtils.nextUserId());
        logEntity.setOperationTime(LocalDateTime.now());
        logEntity.setExecutionTime(executionTime);

        // 用户信息
        logEntity.setUserId(getCurrentUserId());
        logEntity.setUsername(getCurrentUsername());
        logEntity.setUserRole(getCurrentUserRole());

        // 请求信息
        logEntity.setRequestUrl(request.getServletPath());
        logEntity.setRequestMethod(request.getMethod());
        logEntity.setClientIp(IpUtils.getClientIp(request));
        logEntity.setUserAgent(request.getHeader("User-Agent"));

        // 操作信息
        logEntity.setModule(log.module());
        logEntity.setOperationType(log.operationType());
        logEntity.setDescription(log.description());
        logEntity.setRiskLevel(log.riskLevel());

        // 方法信息
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        logEntity.setClassName(signature.getDeclaringTypeName());
        logEntity.setMethodName(signature.getName());

        // 请求参数
        if (log.logParams()) {
            logEntity.setRequestParam(getRequestParams(joinPoint));
        }

        // 响应数据
        if (log.logResult() && result != null) {
            try {
                logEntity.setResponseResult(JSONUtil.toJsonStr(result));
            } catch (Exception e) {
                logEntity.setResponseResult("序列化响应数据失败: " + e.getMessage());
            }
        }

        // 新增属性
        logEntity.setSensitive(log.sensitive());
        logEntity.setBusinessType(log.businessType());
        
        // 会话和令牌信息
        logEntity.setSessionId(request.getSession(false) != null ? request.getSession().getId() : null);

        // 异常信息
        if (exception != null) {
            logEntity.setStatus(1); // 失败
            logEntity.setErrorMsg(exception.getMessage());
        } else {
            logEntity.setStatus(0); // 成功
        }

        return logEntity;
    }

    /**
     * 获取操作日志注解
     */
    private Log getOperationLogAnnotation(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        return method.getAnnotation(Log.class);
    }

    /**
     * 获取请求参数
     */
    private String getRequestParams(ProceedingJoinPoint joinPoint) {
        try {
            Object[] args = joinPoint.getArgs();
            if (args == null || args.length == 0) {
                return "{}";
            }

            Map<String, Object> params = new HashMap<>();
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            String[] paramNames = signature.getParameterNames();

            for (int i = 0; i < args.length && i < paramNames.length; i++) {
                Object arg = args[i];
                // 过滤敏感参数
                if (isSensitiveParam(paramNames[i], arg)) {
                    params.put(paramNames[i], "***");
                } else {
                    params.put(paramNames[i], arg);
                }
            }

            return JSONUtil.toJsonStr(params);
        } catch (Exception e) {
            return "获取参数失败: " + e.getMessage();
        }
    }

    /**
     * 判断是否为敏感参数
     */
    private boolean isSensitiveParam(String paramName, Object paramValue) {
        if (paramName == null) {
            return false;
        }

        String lowerParamName = paramName.toLowerCase();
        return lowerParamName.contains("password") ||
                lowerParamName.contains("pwd") ||
                lowerParamName.contains("secret") ||
                lowerParamName.contains("token") ||
                (paramValue instanceof HttpServletRequest);
    }

    /**
     * 获取当前用户ID
     */
    private Long getCurrentUserId() {
        try {
            if (StpUtil.isLogin()) {
                return StpUtil.getLoginIdAsLong();
            }
        } catch (Exception e) {
            // 忽略异常
        }
        return null;
    }

    /**
     * 获取当前用户名
     */
    private String getCurrentUsername() {
        try {
            if (StpUtil.isLogin()) {
                return (String) StpUtil.getSession().get("username");
            }
        } catch (Exception e) {
            // 忽略异常
        }
        return "anonymous";
    }

    /**
     * 获取当前用户角色
     */
    private String getCurrentUserRole() {
        try {
            if (StpUtil.isLogin()) {
                return (String) StpUtil.getSession().get("role");
            }
        } catch (Exception e) {
            // 忽略异常
        }
        return "guest";
    }

    /**
     * 构建自动操作日志实体
     */
    private OperationLog buildAutoOperationLogEntity(HttpServletRequest request, ProceedingJoinPoint joinPoint,
                                                     Object result, Exception exception, long executionTime,
                                                     String clientIp, String userAgent, String requestUrl,
                                                     String requestMethod, String methodName, String className,
                                                     Object[] args) {
        OperationLog logEntity = new OperationLog();

        // 基本信息
        logEntity.setId(IdUtils.nextUserId());
        logEntity.setOperationTime(LocalDateTime.now());
        logEntity.setExecutionTime(executionTime);

        // 用户信息
        logEntity.setUserId(getCurrentUserId());
        logEntity.setUsername(getCurrentUsername());
        logEntity.setUserRole(getCurrentUserRole());

        // 请求信息
        logEntity.setRequestUrl(requestUrl);
        logEntity.setRequestMethod(requestMethod);
        logEntity.setClientIp(clientIp);
        logEntity.setUserAgent(userAgent);

        // 操作信息
        logEntity.setModule(getModule(className));
        logEntity.setOperationType(getOperationType(methodName, requestMethod));
        logEntity.setDescription(String.format("%s.%s", className, methodName));
        logEntity.setRiskLevel(calculateRiskLevel(logEntity.getOperationType()));

        // 方法信息
        logEntity.setClassName(className);
        logEntity.setMethodName(methodName);

        // 请求参数
        if (operationLogMonitorConfig.getOperationLog().isLogRequestParams() && args.length > 0) {
            try {
                String requestParams = JSONUtil.toJsonStr(filterSensitiveParams(args));
                logEntity.setRequestParam(requestParams);
            } catch (Exception e) {
                logEntity.setRequestParam("参数序列化失败");
            }
        }

        // 响应数据
        if (operationLogMonitorConfig.getOperationLog().isLogResponseResult() && result != null) {
            try {
                logEntity.setResponseResult(JSONUtil.toJsonStr(result));
            } catch (Exception e) {
                logEntity.setResponseResult("响应序列化失败");
            }
        }

        // 异常信息
        if (exception != null) {
            logEntity.setStatus(0); // 失败
            logEntity.setErrorMsg(exception.getMessage());
        } else {
            logEntity.setStatus(1); // 成功
        }

        // 敏感操作标记
        String operationType = logEntity.getOperationType();
        boolean sensitive = Arrays.asList(operationLogMonitorConfig.getOperationLog().getSensitiveOperations())
                .contains(operationType);
        logEntity.setSensitive(sensitive);

        // 会话和令牌信息
        if (request != null) {
            logEntity.setSessionId(request.getSession(false) != null ? request.getSession().getId() : null);
        }

        return logEntity;
    }

    /**
     * 获取当前HTTP请求
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
     * 根据方法名获取操作类型
     */
    private String getOperationType(String methodName, String requestMethod) {
        if (methodName.contains("login") || methodName.contains("signin")) {
            return OperationLogMonitorConfig.Constants.OPERATION_LOGIN;
        } else if (methodName.contains("logout") || methodName.contains("signout")) {
            return OperationLogMonitorConfig.Constants.OPERATION_LOGOUT;
        } else if (methodName.contains("create") || methodName.contains("add") || methodName.contains("save") ||
                   methodName.contains("insert") || "POST".equals(requestMethod)) {
            return OperationLogMonitorConfig.Constants.OPERATION_CREATE;
        } else if (methodName.contains("update") || methodName.contains("modify") || methodName.contains("edit") ||
                   "PUT".equals(requestMethod) || "PATCH".equals(requestMethod)) {
            return OperationLogMonitorConfig.Constants.OPERATION_UPDATE;
        } else if (methodName.contains("delete") || methodName.contains("remove") || 
                   "DELETE".equals(requestMethod)) {
            return OperationLogMonitorConfig.Constants.OPERATION_DELETE;
        } else if (methodName.contains("export")) {
            return OperationLogMonitorConfig.Constants.OPERATION_EXPORT;
        } else if (methodName.contains("import")) {
            return OperationLogMonitorConfig.Constants.OPERATION_IMPORT;
        } else if (methodName.contains("register")) {
            return OperationLogMonitorConfig.Constants.OPERATION_CREATE;
        } else if (methodName.contains("reset")) {
            return OperationLogMonitorConfig.Constants.OPERATION_UPDATE;
        } else if (methodName.contains("get") || methodName.contains("list") || methodName.contains("query") ||
                   methodName.contains("find") || "GET".equals(requestMethod)) {
            return OperationLogMonitorConfig.Constants.OPERATION_VIEW;
        }
        return OperationLogMonitorConfig.Constants.OPERATION_VIEW;
    }

    /**
     * 根据类名获取模块
     */
    private String getModule(String className) {
        if (className.contains("Auth") || className.contains("Login")) {
            return OperationLogMonitorConfig.Constants.MODULE_AUTH;
        } else if (className.contains("User") || className.contains("Account")) {
            return OperationLogMonitorConfig.Constants.MODULE_USER;
        } else if (className.contains("Role")) {
            return OperationLogMonitorConfig.Constants.MODULE_ROLE;
        } else if (className.contains("Permission")) {
            return OperationLogMonitorConfig.Constants.MODULE_PERMISSION;
        } else if (className.contains("OAuth") || className.contains("SSO")) {
            return OperationLogMonitorConfig.Constants.MODULE_OAUTH;
        } else if (className.contains("Record") || className.contains("Evidence")) {
            return OperationLogMonitorConfig.Constants.MODULE_RECORD;
        } else if (className.contains("File") || className.contains("Storage")) {
            return OperationLogMonitorConfig.Constants.MODULE_FILE;
        } else if (className.contains("System") || className.contains("Config") || className.contains("Monitor")) {
            return OperationLogMonitorConfig.Constants.MODULE_SYSTEM;
        }
        return OperationLogMonitorConfig.Constants.MODULE_SYSTEM;
    }

    /**
     * 计算风险等级
     */
    private String calculateRiskLevel(String operationType) {
        if (OperationLogMonitorConfig.Constants.OPERATION_DELETE.equals(operationType)) {
            return OperationLogMonitorConfig.Constants.RISK_LEVEL_CRITICAL;
        } else if (OperationLogMonitorConfig.Constants.OPERATION_UPDATE.equals(operationType)) {
            return OperationLogMonitorConfig.Constants.RISK_LEVEL_HIGH;
        } else if (OperationLogMonitorConfig.Constants.OPERATION_EXPORT.equals(operationType)) {
            return OperationLogMonitorConfig.Constants.RISK_LEVEL_MEDIUM;
        }
        return OperationLogMonitorConfig.Constants.RISK_LEVEL_LOW;
    }

    /**
     * 过滤敏感参数
     */
    private Object[] filterSensitiveParams(Object[] args) {
        if (args == null || args.length == 0) {
            return args;
        }

        Object[] filteredArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg instanceof HttpServletRequest || 
                arg instanceof jakarta.servlet.http.HttpServletResponse ||
                (arg instanceof String && ((String) arg).toLowerCase().contains("password"))) {
                filteredArgs[i] = "***";
            } else {
                filteredArgs[i] = arg;
            }
        }
        return filteredArgs;
    }

    /**
     * 异步记录操作日志（重载方法）
     */
    @Async
    public void recordOperationLogAsync(OperationLog logEntity) {
        try {
            operationLogService.saveOperationLog(logEntity);
        } catch (Exception e) {
            log.error("异步记录操作日志异常", e);
        }
    }
}
