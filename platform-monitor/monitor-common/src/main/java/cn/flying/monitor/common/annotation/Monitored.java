package cn.flying.monitor.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 性能监控注解
 * 标注在需要监控性能的方法上
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Monitored {
    
    /**
     * 指标名称，默认使用方法名
     */
    String value() default "";
    
    /**
     * 指标描述
     */
    String description() default "";
    
    /**
     * 是否记录参数信息
     */
    boolean recordParameters() default false;
    
    /**
     * 是否记录返回值信息
     */
    boolean recordResult() default false;
    
    /**
     * 慢执行阈值（毫秒），超过此时间会记录为慢执行
     */
    long slowThreshold() default 1000;
}