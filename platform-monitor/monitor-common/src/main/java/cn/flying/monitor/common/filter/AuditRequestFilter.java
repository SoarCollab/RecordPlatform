package cn.flying.monitor.common.filter;

import cn.flying.monitor.common.entity.AuditLog;
import cn.flying.monitor.common.service.AuditLogService;
import cn.flying.monitor.common.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Enhanced request filter with comprehensive audit logging
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditRequestFilter extends OncePerRequestFilter {
    
    private final AuditLogService auditLogService;
    private final UserService userService;
    
    // URLs that don't require audit logging
    private final Set<String> ignoreUrls = Set.of(
        "/swagger-ui", "/v3/api-docs", "/doc.html", "/webjars", "/favicon.ico",
        "/actuator/health", "/actuator/metrics"
    );
    
    // Sensitive endpoints that require detailed audit logging
    private final Set<String> sensitiveEndpoints = Set.of(
        "/api/v2/auth/login", "/api/v2/auth/mfa", "/api/v2/users", "/api/v2/roles",
        "/api/v2/clients", "/api/v2/alerts", "/api/v2/ssh"
    );
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, 
                                  FilterChain filterChain) throws ServletException, IOException {
        
        if (shouldIgnoreUrl(request.getServletPath())) {
            filterChain.doFilter(request, response);
            return;
        }
        
        // Generate correlation ID for request tracing
        String correlationId = UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);
        
        // Wrap request and response for content caching
        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        
        long startTime = System.currentTimeMillis();
        
        try {
            // Log request start
            logRequestStart(requestWrapper, correlationId);
            
            // Process request
            filterChain.doFilter(requestWrapper, responseWrapper);
            
            // Log request completion
            logRequestEnd(requestWrapper, responseWrapper, startTime, correlationId);
            
        } catch (Exception e) {
            // Log request failure
            logRequestFailure(requestWrapper, e, startTime, correlationId);
            throw e;
        } finally {
            // Copy response body back
            responseWrapper.copyBodyToResponse();
            MDC.clear();
        }
    }
    
    private void logRequestStart(HttpServletRequest request, String correlationId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth != null && auth.isAuthenticated() ? auth.getName() : "anonymous";
        
        log.info("Request started: {} {} | User: {} | IP: {} | Correlation: {}", 
            request.getMethod(), request.getRequestURI(), username, 
            getClientIp(request), correlationId);
        
        // Audit log for sensitive endpoints
        if (isSensitiveEndpoint(request.getServletPath())) {
            Long userId = getUserId(auth);
            Map<String, Object> details = new HashMap<>();
            details.put("method", request.getMethod());
            details.put("uri", request.getRequestURI());
            details.put("queryString", request.getQueryString());
            details.put("userAgent", request.getHeader("User-Agent"));
            
            auditLogService.logAction(
                userId,
                "REQUEST_START",
                "HTTP_REQUEST",
                request.getRequestURI(),
                details,
                getClientIp(request),
                request.getHeader("User-Agent"),
                correlationId
            );
        }
    }
    
    private void logRequestEnd(HttpServletRequest request, HttpServletResponse response, 
                              long startTime, String correlationId) {
        long duration = System.currentTimeMillis() - startTime;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth != null && auth.isAuthenticated() ? auth.getName() : "anonymous";
        
        log.info("Request completed: {} {} | User: {} | Status: {} | Duration: {}ms | Correlation: {}", 
            request.getMethod(), request.getRequestURI(), username, 
            response.getStatus(), duration, correlationId);
        
        // Audit log for sensitive endpoints or failed requests
        if (isSensitiveEndpoint(request.getServletPath()) || response.getStatus() >= 400) {
            Long userId = getUserId(auth);
            Map<String, Object> details = new HashMap<>();
            details.put("method", request.getMethod());
            details.put("uri", request.getRequestURI());
            details.put("status", response.getStatus());
            details.put("duration", duration);
            
            String action = response.getStatus() >= 400 ? "REQUEST_FAILED" : "REQUEST_COMPLETED";
            
            auditLogService.logAction(
                userId,
                action,
                "HTTP_REQUEST",
                request.getRequestURI(),
                details,
                getClientIp(request),
                request.getHeader("User-Agent"),
                correlationId
            );
        }
        
        // Log permission denied attempts
        if (response.getStatus() == 403) {
            Long userId = getUserId(auth);
            Map<String, Object> details = new HashMap<>();
            details.put("method", request.getMethod());
            details.put("uri", request.getRequestURI());
            details.put("reason", "Access denied");
            
            auditLogService.logAction(
                userId,
                AuditLog.Actions.PERMISSION_DENIED,
                AuditLog.ResourceTypes.SYSTEM,
                request.getRequestURI(),
                details,
                getClientIp(request),
                request.getHeader("User-Agent"),
                correlationId
            );
        }
    }
    
    private void logRequestFailure(HttpServletRequest request, Exception e, 
                                  long startTime, String correlationId) {
        long duration = System.currentTimeMillis() - startTime;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth != null && auth.isAuthenticated() ? auth.getName() : "anonymous";
        
        log.error("Request failed: {} {} | User: {} | Duration: {}ms | Error: {} | Correlation: {}", 
            request.getMethod(), request.getRequestURI(), username, 
            duration, e.getMessage(), correlationId);
        
        // Audit log for request failures
        Long userId = getUserId(auth);
        Map<String, Object> details = new HashMap<>();
        details.put("method", request.getMethod());
        details.put("uri", request.getRequestURI());
        details.put("error", e.getClass().getSimpleName());
        details.put("message", e.getMessage());
        details.put("duration", duration);
        
        auditLogService.logAction(
            userId,
            "REQUEST_ERROR",
            "HTTP_REQUEST",
            request.getRequestURI(),
            details,
            getClientIp(request),
            request.getHeader("User-Agent"),
            correlationId
        );
    }
    
    private boolean shouldIgnoreUrl(String url) {
        return ignoreUrls.stream().anyMatch(url::startsWith);
    }
    
    private boolean isSensitiveEndpoint(String url) {
        return sensitiveEndpoints.stream().anyMatch(url::startsWith);
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
}