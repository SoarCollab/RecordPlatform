package cn.flying.monitor.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for method-level audit logging
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {
    
    /**
     * Action description
     */
    String action();
    
    /**
     * Resource type
     */
    String resourceType() default "";
    
    /**
     * Resource ID expression (SpEL supported)
     */
    String resourceId() default "";
    
    /**
     * Additional details expression (SpEL supported)
     */
    String details() default "";
    
    /**
     * Whether to log method parameters
     */
    boolean logParameters() default false;
    
    /**
     * Whether to log return value
     */
    boolean logReturnValue() default false;
}