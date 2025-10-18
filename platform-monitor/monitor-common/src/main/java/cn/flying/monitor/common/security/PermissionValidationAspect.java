package cn.flying.monitor.common.security;

import cn.flying.monitor.common.exception.PermissionDeniedException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Aspect for validating permissions using @RequirePermission annotation
 */
@Aspect
@Component
public class PermissionValidationAspect {

    @Autowired
    private RbacService rbacService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Around("@annotation(requirePermission)")
    public Object validatePermission(ProceedingJoinPoint joinPoint, RequirePermission requirePermission) throws Throwable {
        String userId = getCurrentUserId();
        
        if (userId == null) {
            throw new PermissionDeniedException("User not authenticated");
        }

        // Check required permissions (ALL must be present)
        String[] requiredPermissions = requirePermission.value();
        if (requiredPermissions.length > 0) {
            for (String permission : requiredPermissions) {
                if (!rbacService.hasPermission(userId, permission)) {
                    throw new PermissionDeniedException(requirePermission.message() + 
                        " - Missing permission: " + permission);
                }
            }
        }

        // Check alternative permissions (ANY can be present)
        String[] anyOfPermissions = requirePermission.anyOf();
        if (anyOfPermissions.length > 0) {
            boolean hasAnyPermission = false;
            for (String permission : anyOfPermissions) {
                if (rbacService.hasPermission(userId, permission)) {
                    hasAnyPermission = true;
                    break;
                }
            }
            
            if (!hasAnyPermission) {
                throw new PermissionDeniedException(requirePermission.message() + 
                    " - Missing any of required permissions");
            }
        }

        return joinPoint.proceed();
    }

    @Around("@within(requirePermission)")
    public Object validateClassPermission(ProceedingJoinPoint joinPoint, RequirePermission requirePermission) throws Throwable {
        return validatePermission(joinPoint, requirePermission);
    }

    private String getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        // Try to get user ID from JWT token
        Object principal = authentication.getPrincipal();
        if (principal instanceof String) {
            String token = (String) principal;
            if (jwtTokenProvider.validateToken(token)) {
                return jwtTokenProvider.getUserId(token);
            }
        }

        // Fallback to authentication name
        return authentication.getName();
    }
}