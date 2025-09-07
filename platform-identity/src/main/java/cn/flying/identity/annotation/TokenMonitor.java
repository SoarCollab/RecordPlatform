package cn.flying.identity.annotation;

import java.lang.annotation.*;

/**
 * Token监控注解
 * 用于标记需要监控Token使用情况的方法
 * 
 * @author flying
 * @date 2024
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TokenMonitor {

    /**
     * 事件类型
     * CREATE-创建, USE-使用, REFRESH-刷新, REVOKE-撤销, EXPIRE-过期
     * 
     * @return 事件类型
     */
    String eventType() default "USE";

    /**
     * Token类型
     * ACCESS-访问令牌, REFRESH-刷新令牌, API-API令牌, SSO-单点登录令牌
     * 
     * @return Token类型
     */
    String tokenType() default "ACCESS";

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
    boolean logResult() default false;

    /**
     * 风险评分阈值
     * 超过此阈值将触发告警
     * 
     * @return 风险评分阈值
     */
    int riskThreshold() default 80;

    /**
     * 是否进行异常检测
     * 
     * @return 是否进行异常检测
     */
    boolean detectAbnormal() default true;

    /**
     * 是否异步记录
     * 如果为true，将异步记录监控数据，不影响主业务流程
     * 
     * @return 是否异步记录
     */
    boolean async() default true;

    /**
     * 是否忽略异常
     * 如果为true，即使监控记录失败也不会影响主业务
     * 
     * @return 是否忽略异常
     */
    boolean ignoreException() default true;

    /**
     * 监控级别
     * LOW-低级别, MEDIUM-中级别, HIGH-高级别, CRITICAL-关键级别
     * 
     * @return 监控级别
     */
    String level() default "MEDIUM";

    /**
     * 是否记录客户端信息
     * 
     * @return 是否记录客户端信息
     */
    boolean logClientInfo() default true;

    /**
     * 是否记录设备指纹
     * 
     * @return 是否记录设备指纹
     */
    boolean logDeviceFingerprint() default false;
}