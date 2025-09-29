package cn.flying.identity.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * API网关配置属性
 * 集中管理API网关的各种配置参数，替代硬编码的常量
 *
 * @author 王贝强
 */
@Data
@Component
@ConfigurationProperties(prefix = "api-gateway")
public class ApiGatewayProperties {

    /**
     * 协议配置
     */
    private String protocol = "http";

    /**
     * HTTP连接池配置
     */
    private HttpPool httpPool = new HttpPool();

    /**
     * 熔断器配置
     */
    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    /**
     * 缓存配置
     */
    private Cache cache = new Cache();

    /**
     * 限流配置
     */
    private RateLimit rateLimit = new RateLimit();

    /**
     * 负载均衡配置
     */
    private LoadBalance loadBalance = new LoadBalance();

    /**
     * 告警配置
     */
    private Alert alert = new Alert();

    @Data
    public static class HttpPool {
        /**
         * 最大连接数
         */
        private int maxTotal = 200;

        /**
         * 每个路由的最大连接数
         */
        private int maxPerRoute = 50;

        /**
         * 连接超时时间（毫秒）
         */
        private int connectTimeout = 5000;

        /**
         * 套接字超时时间（毫秒）
         */
        private int socketTimeout = 30000;

        /**
         * 连接请求超时时间（毫秒）
         */
        private int requestTimeout = 5000;

        /**
         * 连接存活时间（秒）
         */
        private int timeToLive = 60;

        /**
         * 空闲连接超时时间（秒）
         */
        private int idleTimeout = 30;
    }

    @Data
    public static class CircuitBreaker {
        /**
         * 失败率阈值（百分比）
         */
        private float failureRateThreshold = 50.0f;

        /**
         * 慢调用率阈值（百分比）
         */
        private float slowCallRateThreshold = 50.0f;

        /**
         * 慢调用时长阈值（毫秒）
         */
        private long slowCallDurationThreshold = 1000L;

        /**
         * 滑动窗口大小
         */
        private int slidingWindowSize = 100;

        /**
         * 最小调用次数
         */
        private int minimumNumberOfCalls = 10;

        /**
         * 熔断器打开时长（秒）
         */
        private long waitDurationInOpenState = 60L;

        /**
         * 半开状态允许的调用次数
         */
        private int permittedCallsInHalfOpen = 10;
    }

    @Data
    public static class Cache {
        /**
         * 本地缓存最大条目数
         */
        private int localCacheMaxSize = 10000;

        /**
         * 本地缓存过期时间（秒）
         */
        private int localCacheTtl = 300;

        /**
         * Redis缓存过期时间（秒）
         */
        private int redisCacheTtl = 600;

        /**
         * 缓存预热批次大小
         */
        private int warmupBatchSize = 100;
    }

    @Data
    public static class RateLimit {
        /**
         * 默认QPS限制
         */
        private int defaultQps = 100;

        /**
         * 突发流量系数
         */
        private double burstFactor = 1.5;

        /**
         * IP维度限流（QPS）
         */
        private int ipRateLimit = 50;

        /**
         * 用户维度限流（QPS）
         */
        private int userRateLimit = 100;

        /**
         * API密钥维度限流（QPS）
         */
        private int apiKeyRateLimit = 1000;
    }

    @Data
    public static class LoadBalance {
        /**
         * 健康检查间隔（秒）
         */
        private int healthCheckInterval = 10;

        /**
         * 健康检查超时时间（毫秒）
         */
        private int healthCheckTimeout = 3000;

        /**
         * 失败次数阈值（标记为不健康）
         */
        private int failureThreshold = 3;

        /**
         * 成功次数阈值（标记为健康）
         */
        private int successThreshold = 2;

        /**
         * 权重更新间隔（秒）
         */
        private int weightUpdateInterval = 30;
    }

    @Data
    public static class Alert {
        /**
         * 告警聚合窗口（秒）
         */
        private int aggregationWindow = 60;

        /**
         * 告警阈值（同一类型告警数）
         */
        private int alertThreshold = 10;

        /**
         * 告警冷却时间（秒）
         */
        private int coolDownPeriod = 300;

        /**
         * 告警通知方式
         */
        private String alertChannel = "email,sms,webhook";
    }
}