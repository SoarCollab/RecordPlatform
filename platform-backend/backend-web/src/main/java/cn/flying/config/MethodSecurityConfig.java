package cn.flying.config;

import cn.flying.security.CustomMethodSecurityExpressionHandler;
import cn.flying.service.PermissionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * 方法级安全配置
 * 启用 @PreAuthorize/@PostAuthorize 注解支持
 * 注册自定义 SpEL 表达式处理器
 */
@Configuration
@EnableMethodSecurity()
public class MethodSecurityConfig {

    private final PermissionService permissionService;

    public MethodSecurityConfig(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Bean
    public MethodSecurityExpressionHandler methodSecurityExpressionHandler() {
        return new CustomMethodSecurityExpressionHandler(permissionService);
    }
}
