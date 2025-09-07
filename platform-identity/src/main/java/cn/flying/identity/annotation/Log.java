package cn.flying.identity.annotation;

import java.lang.annotation.*;

/**
 * 操作日志注解
 * 整合了原 AuditLog 功能，用于标记需要记录操作日志的方法
 * 支持审计日志、风险评估、地理位置等高级功能
 *
 * @author 王贝强
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Log {

    /**
     * 操作类型
     * 如果不指定，将根据方法名自动推断
     *
     * @return 操作类型
     */
    String operationType() default "";

    /**
     * 模块名称
     * 如果不指定，将根据类名自动推断
     *
     * @return 模块名称
     */
    String module() default "";

    /**
     * 操作描述
     * 如果不指定，将使用默认格式：类名.方法名
     *
     * @return 操作描述
     */
    String description() default "";

    /**
     * 是否记录请求参数
     *
     * @return 是否记录请求参数
     */
    boolean logParams() default true;

    /**
     * 是否记录响应结果
     *
     * @return 是否记录响应结果
     */
    boolean logResult() default true;

    /**
     * 风险等级
     * LOW-低风险, MEDIUM-中风险, HIGH-高风险, CRITICAL-严重风险
     *
     * @return 风险等级
     */
    String riskLevel() default "LOW";

    /**
     * 是否为敏感操作
     * 敏感操作会进行额外的安全检查和记录
     *
     * @return 是否为敏感操作
     */
    boolean sensitive() default false;

    /**
     * 业务类型
     * 用于业务分类统计
     *
     * @return 业务类型
     */
    String businessType() default "";

    /**
     * 是否异步记录
     * 如果为true，将异步记录审计日志，不影响主业务流程
     *
     * @return 是否异步记录
     */
    boolean async() default true;

    /**
     * 是否忽略异常
     * 如果为true，即使审计日志记录失败也不会影响主业务
     *
     * @return 是否忽略异常
     */
    boolean ignoreException() default true;

    /**
     * 是否记录执行时间
     *
     * @return 是否记录执行时间
     */
    boolean recordExecutionTime() default true;
}
