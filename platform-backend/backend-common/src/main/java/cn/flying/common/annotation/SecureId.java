package cn.flying.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 安全ID注解，用于标记需要进行ID安全处理的API
 * 被该注解标记的接口，会对返回的实体ID进行混淆处理
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface SecureId {
    
    /**
     * 是否启用ID混淆（默认启用）
     */
    boolean value() default true;
    
    /**
     * ID字段名称（默认为'id'）
     */
    String field() default "id";
    
    /**
     * 是否隐藏原始ID（默认不隐藏）
     * 如果设为true，则返回值中的原始ID字段将被设为null
     * 如果设为false，则原始ID和混淆ID都会保留
     */
    boolean hideOriginalId() default false;
} 