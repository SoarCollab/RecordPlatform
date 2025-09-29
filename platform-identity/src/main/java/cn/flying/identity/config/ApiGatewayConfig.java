package cn.flying.identity.config;

import cn.flying.identity.filter.ApiGatewayProxyFilter;
import cn.flying.identity.filter.EnhancedGatewayFilter;
import cn.flying.identity.gateway.discovery.NacosServiceDiscovery;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * API网关配置类
 * 配置过滤器链、CORS、异步任务等
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Configuration
@EnableAsync
@EnableScheduling
public class ApiGatewayConfig {

    /**
     * 注册EnhancedGatewayFilter
     * 负责认证、权限验证、流量监控
     *
     * @param filter 增强网关过滤器
     * @return 过滤器注册Bean
     */
    @Bean
    public FilterRegistrationBean<EnhancedGatewayFilter> enhancedGatewayFilterRegistration(
            EnhancedGatewayFilter filter) {
        FilterRegistrationBean<EnhancedGatewayFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.addUrlPatterns("/*");
        registration.setName("enhancedGatewayFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1); // 优先级设为1
        return registration;
    }

    /**
     * 注册ApiGatewayProxyFilter
     * 负责路由匹配、负载均衡、请求转发
     *
     * @param filter API网关代理过滤器
     * @return 过滤器注册Bean
     */
    @Bean
    public FilterRegistrationBean<ApiGatewayProxyFilter> apiGatewayProxyFilterRegistration(
            ApiGatewayProxyFilter filter) {
        FilterRegistrationBean<ApiGatewayProxyFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        // 只对需要代理的路径生效
        registration.addUrlPatterns(
                "/api/gateway/proxy/*",
                "/api/v1/*",
                "/api/v2/*",
                "/gateway/*"
        );
        registration.setName("apiGatewayProxyFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2); // 优先级设为2，在EnhancedGatewayFilter之后
        return registration;
    }

    /**
     * 配置CORS过滤器
     * 支持跨域请求
     *
     * @return CORS过滤器
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // 允许的域名，生产环境应该配置具体的域名
        config.setAllowedOriginPatterns(List.of("*"));
        // 允许的请求头
        config.setAllowedHeaders(List.of("*"));
        // 允许的方法
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD", "PATCH"));
        // 是否允许携带Cookie
        config.setAllowCredentials(true);
        // 预检请求的缓存时间（秒）
        config.setMaxAge(3600L);
        // 暴露的响应头
        config.setExposedHeaders(Arrays.asList(
                "Authorization",
                "X-Total-Count",
                "X-Request-Id",
                "X-Trace-Id",
                "X-API-Version",
                "X-RateLimit-Limit",
                "X-RateLimit-Remaining",
                "X-RateLimit-Reset",
                "X-Fallback"
        ));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }

    /**
     * 配置服务发现定时任务
     * 定期从Nacos同步服务实例
     *
     * @return Nacos服务发现组件
     */
    @Bean
    public NacosServiceDiscovery nacosServiceDiscovery() {
        return new NacosServiceDiscovery();
    }

    /**
     * HTTP连接池配置
     * 用于配置API网关的HTTP客户端连接池参数
     * @deprecated 已被 ApiGatewayProperties 替代，请使用配置文件方式
     */
    @Deprecated
    public static class HttpPoolConfig {
        /**
         * 最大连接数
         */
        public static final int MAX_TOTAL = 200;

        /**
         * 每个路由的最大连接数
         */
        public static final int MAX_PER_ROUTE = 50;

        /**
         * 连接超时时间（毫秒）
         */
        public static final int CONNECT_TIMEOUT = 5000;

        /**
         * 套接字超时时间（毫秒）
         */
        public static final int SOCKET_TIMEOUT = 30000;

        /**
         * 连接请求超时时间（毫秒）
         */
        public static final int REQUEST_TIMEOUT = 5000;

        /**
         * 连接存活时间（秒）
         */
        public static final int TIME_TO_LIVE = 60;

        /**
         * 空闲连接超时时间（秒）
         */
        public static final int IDLE_TIMEOUT = 30;
    }

    /**
     * 熔断器配置
     * 用于配置熔断器的默认参数
     * @deprecated 已被 ApiGatewayProperties 替代，请使用配置文件方式
     */
    @Deprecated
    public static class CircuitBreakerConfig {
        /**
         * 失败率阈值（百分比）
         */
        public static final float FAILURE_RATE_THRESHOLD = 50.0f;

        /**
         * 慢调用率阈值（百分比）
         */
        public static final float SLOW_CALL_RATE_THRESHOLD = 50.0f;

        /**
         * 慢调用时长阈值（毫秒）
         */
        public static final long SLOW_CALL_DURATION_THRESHOLD = 1000L;

        /**
         * 滑动窗口大小
         */
        public static final int SLIDING_WINDOW_SIZE = 100;

        /**
         * 最小调用次数
         */
        public static final int MINIMUM_NUMBER_OF_CALLS = 10;

        /**
         * 熔断器打开时长（秒）
         */
        public static final long WAIT_DURATION_IN_OPEN_STATE = 60L;

        /**
         * 半开状态允许的调用次数
         */
        public static final int PERMITTED_CALLS_IN_HALF_OPEN = 10;
    }

    /**
     * 缓存配置
     * 用于配置API网关的缓存参数
     * @deprecated 已被 ApiGatewayProperties 替代，请使用配置文件方式
     */
    @Deprecated
    public static class CacheConfig {
        /**
         * 本地缓存最大条目数
         */
        public static final int LOCAL_CACHE_MAX_SIZE = 10000;

        /**
         * 本地缓存过期时间（秒）
         */
        public static final int LOCAL_CACHE_TTL = 300;

        /**
         * Redis缓存过期时间（秒）
         */
        public static final int REDIS_CACHE_TTL = 600;

        /**
         * 缓存预热批次大小
         */
        public static final int WARMUP_BATCH_SIZE = 100;
    }

    /**
     * 限流配置
     * 用于配置API网关的限流参数
     * @deprecated 已被 ApiGatewayProperties 替代，请使用配置文件方式
     */
    @Deprecated
    public static class RateLimitConfig {
        /**
         * 默认QPS限制
         */
        public static final int DEFAULT_QPS = 100;

        /**
         * 突发流量系数
         */
        public static final double BURST_FACTOR = 1.5;

        /**
         * IP维度限流（QPS）
         */
        public static final int IP_RATE_LIMIT = 50;

        /**
         * 用户维度限流（QPS）
         */
        public static final int USER_RATE_LIMIT = 100;

        /**
         * API密钥维度限流（QPS）
         */
        public static final int API_KEY_RATE_LIMIT = 1000;
    }

    /**
     * 负载均衡配置
     * 用于配置负载均衡策略参数
     * @deprecated 已被 ApiGatewayProperties 替代，请使用配置文件方式
     */
    @Deprecated
    public static class LoadBalanceConfig {
        /**
         * 健康检查间隔（秒）
         */
        public static final int HEALTH_CHECK_INTERVAL = 10;

        /**
         * 健康检查超时时间（毫秒）
         */
        public static final int HEALTH_CHECK_TIMEOUT = 3000;

        /**
         * 失败次数阈值（标记为不健康）
         */
        public static final int FAILURE_THRESHOLD = 3;

        /**
         * 成功次数阈值（标记为健康）
         */
        public static final int SUCCESS_THRESHOLD = 2;

        /**
         * 权重更新间隔（秒）
         */
        public static final int WEIGHT_UPDATE_INTERVAL = 30;
    }

    /**
     * 告警配置
     * 用于配置告警通知参数
     * @deprecated 已被 ApiGatewayProperties 替代，请使用配置文件方式
     */
    @Deprecated
    public static class AlertConfig {
        /**
         * 告警聚合窗口（秒）
         */
        public static final int AGGREGATION_WINDOW = 60;

        /**
         * 告警阈值（同一类型告警数）
         */
        public static final int ALERT_THRESHOLD = 10;

        /**
         * 告警冷却时间（秒）
         */
        public static final int COOL_DOWN_PERIOD = 300;

        /**
         * 告警通知方式
         */
        public static final String ALERT_CHANNEL = "email,sms,webhook";
    }
}