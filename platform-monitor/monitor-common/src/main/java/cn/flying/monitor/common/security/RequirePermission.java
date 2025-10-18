package cn.flying.monitor.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for method-level permission checking
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    
    /**
     * Required permissions (user must have ALL of these)
     */
    String[] value() default {};
    
    /**
     * Alternative permissions (user must have ANY of these)
     */
    String[] anyOf() default {};
    
    /**
     * Error message when permission is denied
     */
    String message() default "Access denied: insufficient permissions";
}