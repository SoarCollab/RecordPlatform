package cn.flying.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 跨域配置类
 * 配置CORS策略，支持前端跨域访问
 */
@Configuration
public class CorsConfig {

    /**
     * 创建CORS过滤器
     *
     * @return CORS过滤器
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // 允许所有域名进行跨域调用
        config.addAllowedOriginPattern("*");

        // 允许所有请求头
        config.addAllowedHeader("*");

        // 允许所有HTTP方法
        config.addAllowedMethod("*");

        // 允许携带凭证信息
        config.setAllowCredentials(true);

        // 预检请求的缓存时间（秒）
        config.setMaxAge(3600L);

        // 暴露的响应头
        config.addExposedHeader("Authorization");
        config.addExposedHeader("Content-Type");
        config.addExposedHeader("X-Requested-With");
        config.addExposedHeader("accept");
        config.addExposedHeader("Origin");
        config.addExposedHeader("Access-Control-Request-Method");
        config.addExposedHeader("Access-Control-Request-Headers");

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }

    /**
     * 创建CORS配置源
     *
     * @return CORS配置源
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // 允许的源
        configuration.addAllowedOriginPattern("*");

        // 允许的方法
        configuration.addAllowedMethod("GET");
        configuration.addAllowedMethod("POST");
        configuration.addAllowedMethod("PUT");
        configuration.addAllowedMethod("DELETE");
        configuration.addAllowedMethod("OPTIONS");
        configuration.addAllowedMethod("PATCH");

        // 允许的请求头
        configuration.addAllowedHeader("*");

        // 允许携带凭证
        configuration.setAllowCredentials(true);

        // 预检请求缓存时间
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }
}