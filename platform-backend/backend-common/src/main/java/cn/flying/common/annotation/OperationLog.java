package cn.flying.common.annotation;

import io.swagger.v3.oas.annotations.media.Schema;

import java.lang.annotation.*;

/**
 * 操作日志注解
 * 用于标记需要记录操作日志的方法
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Schema(description = "操作日志注解")
public @interface OperationLog {
    
    /**
     * 操作模块
     */
    @Schema(description = "操作模块")
    String module() default "";
    
    /**
     * 操作类型（如：增加、修改、删除、查询等）
     */
    @Schema(description = "操作类型")
    String operationType() default "";
    
    /**
     * 操作描述
     */
    @Schema(description = "操作描述")
    String description() default "";
    
    /**
     * 是否保存请求参数
     */
    @Schema(description = "是否保存请求参数")
    boolean saveRequestData() default true;
    
    /**
     * 是否保存响应数据
     */
    @Schema(description = "是否保存响应数据")
    boolean saveResponseData() default false;
} 