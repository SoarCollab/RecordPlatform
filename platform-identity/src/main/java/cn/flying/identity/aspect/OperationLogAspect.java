package cn.flying.identity.aspect;

import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.annotation.OperationLog;
import cn.flying.identity.dto.OperationLogEntity;
import cn.flying.identity.service.OperationLogService;
import cn.flying.identity.util.IdUtils;
import cn.flying.identity.util.IpUtils;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 操作日志切面处理类
 * 用于处理 @OperationLog 注解，记录用户操作日志
 * 从 platform-backend 迁移而来，适配 SA-Token 框架
 * 
 * @author 王贝强
 */
@Slf4j
@Aspect
@Component
public class OperationLogAspect {

    @Autowired
    private OperationLogService operationLogService;

    /**
     * 定义切点
     */
    @Pointcut("@annotation(cn.flying.identity.annotation.OperationLog)")
    public void operationLogPointCut() {
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
        OperationLog operationLog = getOperationLogAnnotation(joinPoint);
        
        // 记录请求开始日志
        if (request != null && operationLog != null) {
            logRequestStart(request, joinPoint, operationLog);
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
            if (request != null && operationLog != null) {
                if (operationLog.async()) {
                    // 异步记录日志
                    recordOperationLogAsync(request, joinPoint, operationLog, result, exception, executionTime);
                } else {
                    // 同步记录日志
                    recordOperationLog(request, joinPoint, operationLog, result, exception, executionTime);
                }
            }
            
            // 清理MDC
            MDC.clear();
        }
        
        return result;
    }

    /**
     * 记录请求开始日志
     */
    private void logRequestStart(HttpServletRequest request, ProceedingJoinPoint joinPoint, OperationLog operationLog) {
        try {
            String username = getCurrentUsername();
            String userRole = getCurrentUserRole();
            String clientIp = IpUtils.getClientIp(request);
            String requestParams = getRequestParams(joinPoint);
            
            log.info("操作日志 - 开始 | 模块: {} | 类型: {} | 描述: {} | URL: \"{}\" ({}) | IP: {} | 用户: {} | 角色: {} | 参数: {}",
                    operationLog.module(), operationLog.operationType(), operationLog.description(),
                    request.getServletPath(), request.getMethod(), clientIp, username, userRole, requestParams);
        } catch (Exception e) {
            log.error("记录请求开始日志异常", e);
        }
    }

    /**
     * 同步记录操作日志
     */
    private void recordOperationLog(HttpServletRequest request, ProceedingJoinPoint joinPoint, 
                                   OperationLog operationLog, Object result, Exception exception, long executionTime) {
        try {
            OperationLogEntity logEntity = buildOperationLogEntity(request, joinPoint, operationLog, result, exception, executionTime);
            operationLogService.saveOperationLog(logEntity);
            
            // 记录完成日志
            String status = exception == null ? "成功" : "失败";
            log.info("操作日志 - 完成 | 状态: {} | 耗时: {}ms | 模块: {} | 描述: {}",
                    status, executionTime, operationLog.module(), operationLog.description());
        } catch (Exception e) {
            log.error("记录操作日志异常", e);
        }
    }

    /**
     * 异步记录操作日志
     */
    @Async
    public void recordOperationLogAsync(HttpServletRequest request, ProceedingJoinPoint joinPoint, 
                                       OperationLog operationLog, Object result, Exception exception, long executionTime) {
        recordOperationLog(request, joinPoint, operationLog, result, exception, executionTime);
    }

    /**
     * 构建操作日志实体
     */
    private OperationLogEntity buildOperationLogEntity(HttpServletRequest request, ProceedingJoinPoint joinPoint,
                                                      OperationLog operationLog, Object result, Exception exception, long executionTime) {
        OperationLogEntity logEntity = new OperationLogEntity();
        
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
        logEntity.setModule(operationLog.module());
        logEntity.setOperationType(operationLog.operationType());
        logEntity.setDescription(operationLog.description());
        logEntity.setRiskLevel(operationLog.riskLevel());
        
        // 方法信息
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        logEntity.setClassName(signature.getDeclaringTypeName());
        logEntity.setMethodName(signature.getName());
        
        // 请求参数
        if (operationLog.saveRequestData()) {
            logEntity.setRequestParam(getRequestParams(joinPoint));
        }
        
        // 响应数据
        if (operationLog.saveResponseData() && result != null) {
            try {
                logEntity.setResponseResult(JSONUtil.toJsonStr(result));
            } catch (Exception e) {
                logEntity.setResponseResult("序列化响应数据失败: " + e.getMessage());
            }
        }
        
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
    private OperationLog getOperationLogAnnotation(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        return method.getAnnotation(OperationLog.class);
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
}
