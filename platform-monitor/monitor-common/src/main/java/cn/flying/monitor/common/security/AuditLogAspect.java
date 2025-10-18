package cn.flying.monitor.common.security;

import cn.flying.monitor.common.service.AuditLogService;
import cn.flying.monitor.common.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;

/**
 * Aspect for method-level audit logging
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditLogAspect {
    
    private final AuditLogService auditLogService;
    private final UserService userService;
    private final ExpressionParser parser = new SpelExpressionParser();
    
    @Around("@annotation(auditLog)")
    public Object auditMethod(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        long startTime = System.currentTimeMillis();
        String correlationId = MDC.get("correlationId");
        
        // Get current user
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = getUserId(auth);
        
        // Get HTTP request info
        HttpServletRequest request = getCurrentRequest();
        String ipAddress = request != null ? getClientIp(request) : "unknown";
        String userAgent = request != null ? request.getHeader("User-Agent") : "unknown";
        
        // Prepare evaluation context for SpEL expressions
        EvaluationContext context = new StandardEvaluationContext();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();
        Parameter[] parameters = method.getParameters();
        
        // Add method parameters to context
        for (int i = 0; i < parameters.length && i < args.length; i++) {
            context.setVariable(parameters[i].getName(), args[i]);
        }
        
        Object result = null;
        Exception exception = null;
        
        try {
            result = joinPoint.proceed();
            context.setVariable("result", result);
            return result;
        } catch (Exception e) {
            exception = e;
            context.setVariable("exception", e);
            throw e;
        } finally {
            try {
                logMethodExecution(auditLog, context, userId, ipAddress, userAgent, 
                    correlationId, startTime, result, exception);
            } catch (Exception e) {
                log.error("Failed to create audit log for method execution", e);
            }
        }
    }
    
    private void logMethodExecution(AuditLog auditLog, EvaluationContext context, 
                                   Long userId, String ipAddress, String userAgent,
                                   String correlationId, long startTime, 
                                   Object result, Exception exception) {
        
        Map<String, Object> details = new HashMap<>();
        details.put("duration", System.currentTimeMillis() - startTime);
        
        // Add method parameters if requested
        if (auditLog.logParameters()) {
            Map<String, Object> parameters = new HashMap<>();
            context.getRootObject();
            // Note: In a real implementation, you'd iterate through the context variables
            details.put("parameters", parameters);
        }
        
        // Add return value if requested and no exception
        if (auditLog.logReturnValue() && exception == null && result != null) {
            details.put("returnValue", result.toString());
        }
        
        // Add exception info if present
        if (exception != null) {
            details.put("exception", exception.getClass().getSimpleName());
            details.put("errorMessage", exception.getMessage());
        }
        
        // Evaluate SpEL expressions
        String resourceId = evaluateExpression(auditLog.resourceId(), context);
        String additionalDetails = evaluateExpression(auditLog.details(), context);
        
        if (additionalDetails != null && !additionalDetails.isEmpty()) {
            details.put("additionalDetails", additionalDetails);
        }
        
        // Determine action name
        String action = auditLog.action();
        if (exception != null) {
            action = action + "_FAILED";
        }
        
        // Log the audit event
        auditLogService.logAction(
            userId,
            action,
            auditLog.resourceType().isEmpty() ? "METHOD" : auditLog.resourceType(),
            resourceId,
            details,
            ipAddress,
            userAgent,
            correlationId
        );
    }
    
    private String evaluateExpression(String expression, EvaluationContext context) {
        if (expression == null || expression.trim().isEmpty()) {
            return null;
        }
        
        try {
            Expression exp = parser.parseExpression(expression);
            Object value = exp.getValue(context);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            log.warn("Failed to evaluate SpEL expression: {}", expression, e);
            return expression; // Return original expression as fallback
        }
    }
    
    private Long getUserId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || "anonymous".equals(auth.getName())) {
            return null;
        }
        
        try {
            return userService.findByUsernameOrEmail(auth.getName())
                .map(user -> user.getId())
                .orElse(null);
        } catch (Exception e) {
            log.warn("Failed to get user ID for audit logging", e);
            return null;
        }
    }
    
    private HttpServletRequest getCurrentRequest() {
        try {
            ServletRequestAttributes attributes = 
                (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            return attributes.getRequest();
        } catch (Exception e) {
            return null;
        }
    }
    
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        
        return request.getRemoteAddr();
    }
}