package cn.flying.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 资源所有权校验注解
 * 用于标记需要校验当前用户是否为资源所有者的方法
 *
 * 使用示例:
 * <pre>
 * {@code @RequireOwnership(resourceIdParam = "fileId", resourceClass = File.class)}
 * public void deleteFile(Long fileId) { ... }
 * </pre>
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireOwnership {

    /**
     * 资源ID参数名，默认为"id"
     * 该参数应为方法的参数名
     */
    String resourceIdParam() default "id";

    /**
     * 资源所有者ID字段名，默认为"uid"
     * 用于从资源实体中获取所有者ID
     */
    String ownerIdField() default "uid";

    /**
     * 资源实体类
     * 用于通过资源ID查询资源实体以获取所有者ID
     */
    Class<?> resourceClass() default Void.class;

    /**
     * 是否允许管理员绕过所有权检查
     * 默认为true
     */
    boolean adminBypass() default true;

    /**
     * 权限不足时的错误消息
     */
    String message() default "无权操作此资源";
}
