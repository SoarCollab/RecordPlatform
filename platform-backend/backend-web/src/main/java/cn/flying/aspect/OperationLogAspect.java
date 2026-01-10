package cn.flying.aspect;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.JsonConverter;
import cn.flying.dao.dto.SysOperationLog;
import cn.flying.service.SysOperationLogService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 操作日志切面
 */
@Aspect
@Component
@Slf4j
public class OperationLogAspect {

    @Resource
    private SysOperationLogService operationLogService;
    
    // 忽略的非业务URL前缀（保留 /api/file 等核心接口的审计记录）
    private final Set<String> ignores = Set.of(
            "/favicon.ico",
            "/webjars",
            "/doc.html",
            "/swagger-ui",
            "/v3/api-docs",
            "/api/system/logs",
            "/api/v1/system/audit"
    );

    /**
     * 设置操作日志切入点 记录操作日志 在注解的位置切入代码
     */
    @Pointcut("@annotation(cn.flying.common.annotation.OperationLog)")
    public void operationLogPointCut() {
    }

    /**
     * 环绕通知，记录请求开始和结束，计算执行时间
     *
     * @param joinPoint 切入点
     * @return 方法执行结果
     * @throws Throwable 可能抛出的异常
     */
    @Around("operationLogPointCut()")
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        
        // 获取请求信息
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            if (!isIgnoreUrl(request.getServletPath())) {
                // 设置请求ID到MDC
                String reqId = IdUtils.nextLogId();
                MDC.put("reqId", reqId);
                // 记录请求开始日志
                logRequestStart(request, joinPoint);
            }
        }
        
        // 执行目标方法
        Object result = null;
        Exception exception = null;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Exception e) {
            exception = e;
            throw e;
        } finally {
            // 记录操作日志
            long executionTime = System.currentTimeMillis() - startTime;
            handleLog(joinPoint, result, exception, executionTime);
            // 清理MDC
            MDC.remove("reqId");
        }
    }

    /**
     * 判定当前请求url是否不需要日志记录
     * @param url 路径
     * @return 是否忽略
     */
    private boolean isIgnoreUrl(String url){
        for (String ignore : ignores) {
            if(url.startsWith(ignore)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 记录请求开始的日志
     * @param request 请求对象
     * @param joinPoint 切入点
     */
    private void logRequestStart(HttpServletRequest request, JoinPoint joinPoint) {
        try {
            // 获取当前登录用户信息
            Object principal = SecurityContextHolder.getContext().getAuthentication() != null ?
                    SecurityContextHolder.getContext().getAuthentication().getPrincipal() : null;
            
            String username = "未登录";
            String authorities = "";
            
            if (principal instanceof User user) {
                username = user.getUsername();
                authorities = user.getAuthorities().toString();
            }
            
            // 获取请求参数
            String requestParams = getRequestParams(joinPoint);
            
            // 获取操作注解信息
            OperationLog operationLogAnnotation = getOperationLogAnnotation(joinPoint);
            String module = operationLogAnnotation != null ? operationLogAnnotation.module() : "";
            String operationType = operationLogAnnotation != null ? operationLogAnnotation.operationType() : "";
            String description = operationLogAnnotation != null ? operationLogAnnotation.description() : "";
            
            // 记录请求开始的日志
            log.info("操作日志 - 开始 | 模块: {} | 类型: {} | 描述: {} | URL: \"{}\" ({}) | IP: {} | 用户: {} | 角色: {} | 参数: {}",
                    module, operationType, description, request.getServletPath(), request.getMethod(),
                    getClientIp(request), username, authorities, requestParams);
            
        } catch (Exception e) {
            log.error("记录请求开始日志异常", e);
        }
    }

    /**
     * 处理日志记录
     *
     * @param joinPoint 切入点
     * @param result 返回结果
     * @param e 异常
     * @param executionTime 执行时间(毫秒)
     */
    private void handleLog(JoinPoint joinPoint, Object result, Exception e, long executionTime) {
        try {
            // 获取注解信息
            OperationLog operationLogAnnotation = getOperationLogAnnotation(joinPoint);
            if (operationLogAnnotation == null) {
                return;
            }

            // 获取请求信息
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return;
            }
            
            HttpServletRequest request = attributes.getRequest();
            if (isIgnoreUrl(request.getServletPath())) {
                return;
            }
            
            // 构建操作日志对象
            SysOperationLog operationLog = new SysOperationLog();
            
            // 设置用户信息
            Object principal = SecurityContextHolder.getContext().getAuthentication() != null ?
                    SecurityContextHolder.getContext().getAuthentication().getPrincipal() : null;
            
            // 从MDC中获取用户ID
            String userIdStr = MDC.get("userId");
            
            if (principal instanceof User user) {
                // 从MDC中获取到的用户ID设置到日志对象中
                if (userIdStr != null) {
                    operationLog.setUserId(userIdStr);
                }
                operationLog.setUsername(user.getUsername());
            } else {
                operationLog.setUsername("未登录");
            }
            
            // 设置请求信息
            operationLog.setRequestIp(getClientIp(request));
            operationLog.setRequestUrl(request.getRequestURI());
            operationLog.setRequestMethod(request.getMethod());
            operationLog.setMethod(joinPoint.getSignature().getDeclaringTypeName() + "." + joinPoint.getSignature().getName());
            
            // 如果从MDC获取用户ID失败，尝试从请求属性中获取
            if (operationLog.getUserId() == null && request.getAttribute(Const.ATTR_USER_ID) != null) {
                try {
                    Object userId = request.getAttribute(Const.ATTR_USER_ID);
                    if (userId != null) {
                        operationLog.setUserId(userId.toString());
                    }
                } catch (Exception ex) {
                    log.warn("从请求属性获取用户ID失败", ex);
                }
            }
            
            // 设置注解信息
            operationLog.setModule(operationLogAnnotation.module());
            operationLog.setOperationType(operationLogAnnotation.operationType());
            operationLog.setDescription(operationLogAnnotation.description());
            
            // 设置请求参数
            if (operationLogAnnotation.saveRequestData()) {
                operationLog.setRequestParam(getRequestParams(joinPoint));
            }
            
            // 设置响应数据
            if (operationLogAnnotation.saveResponseData() && result != null) {
                operationLog.setResponseResult(JsonConverter.toJsonWithPretty(result));
            }
            
            // 设置操作状态和时间
            operationLog.setStatus(e == null ? 0 : 1);
            operationLog.setOperationTime(LocalDateTime.now());
            operationLog.setExecutionTime(executionTime);
            
            // 设置异常信息
            if (e != null) {
                operationLog.setErrorMsg(e.getMessage());
            }
            
            // 保存操作日志
            operationLogService.saveOperationLog(operationLog);
            
            // 记录请求结束的日志
            log.info("操作日志 - 结束 | 模块: {} | 类型: {} | 描述: {} | 用户ID: {} | 用户名: {} | 耗时: {}ms | 状态: {}",
                    operationLog.getModule(), operationLog.getOperationType(), operationLog.getDescription(),
                    operationLog.getUserId(), operationLog.getUsername(),
                    executionTime, e == null ? "成功" : "失败(" + e.getMessage() + ")");
            
        } catch (Exception ex) {
            log.error("记录操作日志异常", ex);
        }
    }

    /**
     * 获取操作日志注解
     *
     * @param joinPoint 切入点
     * @return 操作日志注解
     */
    private OperationLog getOperationLogAnnotation(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        return method.getAnnotation(OperationLog.class);
    }

    /**
     * 获取请求参数
     *
     * @param joinPoint 切入点
     * @return 请求参数字符串
     */
    private String getRequestParams(JoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        List<Object> logArgs = new ArrayList<>();
        for (Object arg : args) {
            if (arg instanceof org.springframework.web.multipart.MultipartFile
                || arg instanceof HttpServletRequest
                || arg instanceof jakarta.servlet.http.HttpServletResponse) {
                continue;
            }
            logArgs.add(arg);
        }
        return logArgs.isEmpty() ? "" : JsonConverter.toJsonWithPretty(logArgs);
    }

    /**
     * 获取客户端IP
     *
     * @param request 请求对象
     * @return IP地址
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 多个代理的情况，第一个IP为客户端真实IP
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
} 
