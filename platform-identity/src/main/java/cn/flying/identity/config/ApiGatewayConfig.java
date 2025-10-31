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
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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

    private final IdentitySecurityProperties identitySecurityProperties;

    public ApiGatewayConfig(IdentitySecurityProperties identitySecurityProperties) {
        this.identitySecurityProperties = identitySecurityProperties;
    }

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
        List<String> allowedOrigins = identitySecurityProperties.getAllowedOrigins().stream()
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
        List<String> allowedOriginPatterns = identitySecurityProperties.getAllowedOriginPatterns().stream()
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
        boolean hasExplicitOrigins = !allowedOrigins.isEmpty();
        boolean hasExplicitPatterns = !allowedOriginPatterns.isEmpty();

        if (hasExplicitOrigins) {
            config.setAllowedOrigins(allowedOrigins);
        }
        if (hasExplicitPatterns) {
            config.setAllowedOriginPatterns(allowedOriginPatterns);
        }

        boolean allowCredentials = identitySecurityProperties.isAllowCredentials()
                && (hasExplicitOrigins || hasExplicitPatterns);
        config.setAllowCredentials(allowCredentials);
        config.setMaxAge(identitySecurityProperties.getMaxAge());

        config.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "X-Requested-With",
                "X-Trace-Id",
                "X-Request-Id"
        ));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD", "PATCH"));
        config.setAllowCredentials(hasExplicitOrigins);
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
     * 配置RestTemplate
     * 用于HTTP客户端调用
     *
     * @param builder RestTemplate构建器
     * @return RestTemplate实例
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(30))
                .build();
    }
}
