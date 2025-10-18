package cn.flying.monitor.common.security;

import cn.flying.monitor.common.entity.User;
import cn.flying.monitor.common.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Aspect for enforcing permission-based access control
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class PermissionAspect {
    
    private final UserService userService;
    private final RbacService rbacService;
    
    @Around("@annotation(requirePermission)")
    public Object checkPermission(ProceedingJoinPoint joinPoint, RequirePermission requirePermission) throws Throwable {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authentication required");
        }
        
        // Get current user
        String username = authentication.getName();
        Optional<User> userOpt = userService.findByUsernameOrEmail(username);
        
        if (userOpt.isEmpty()) {
            throw new AccessDeniedException("User not found");
        }
        
        User user = userOpt.get();
        String userId = user.getId().toString();
        
        // Check if account is locked
        if (user.isAccountLocked()) {
            throw new AccessDeniedException("Account is locked");
        }
        
        // Check permissions
        boolean hasPermission = false;
        
        if (requirePermission.value().length > 0) {
            // Must have ALL specified permissions
            hasPermission = rbacService.hasAllPermissions(userId, requirePermission.value());
        } else if (requirePermission.anyOf().length > 0) {
            // Must have ANY of the specified permissions
            hasPermission = rbacService.hasAnyPermission(userId, requirePermission.anyOf());
        } else {
            // No specific permissions required, just authenticated
            hasPermission = true;
        }
        
        if (!hasPermission) {
            log.warn("Access denied for user {} to method {}: {}", 
                username, joinPoint.getSignature().getName(), requirePermission.message());
            throw new AccessDeniedException(requirePermission.message());
        }
        
        return joinPoint.proceed();
    }
}