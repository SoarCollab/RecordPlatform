package cn.flying.filter;

import cn.flying.common.util.Const;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 安全 HTTP 头过滤器
 * 添加标准安全响应头，防止 XSS、点击劫持、MIME 嗅探等攻击
 */
@Component
@Order(Const.ORDER_SECURITY_HEADER)
public class SecurityHeaderFilter extends HttpFilter {

    @Value("${spring.web.security.frame-options:DENY}")
    private String frameOptions;

    @Value("${spring.web.security.content-type-options:nosniff}")
    private String contentTypeOptions;

    @Value("${spring.web.security.xss-protection:1; mode=block}")
    private String xssProtection;

    @Value("${spring.web.security.hsts-max-age:31536000}")
    private long hstsMaxAge;

    @Value("${spring.web.security.hsts-include-subdomains:true}")
    private boolean hstsIncludeSubDomains;

    @Value("${spring.web.security.csp:default-src 'self'}")
    private String contentSecurityPolicy;

    @Value("${spring.web.security.referrer-policy:strict-origin-when-cross-origin}")
    private String referrerPolicy;

    @Value("${spring.web.security.enabled:true}")
    private boolean enabled;

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (enabled) {
            addSecurityHeaders(response);
        }

        chain.doFilter(request, response);
    }

    private void addSecurityHeaders(HttpServletResponse response) {
        // 防止点击劫持
        response.setHeader("X-Frame-Options", frameOptions);

        // 防止 MIME 类型嗅探
        response.setHeader("X-Content-Type-Options", contentTypeOptions);

        // XSS 保护（旧版浏览器）
        response.setHeader("X-XSS-Protection", xssProtection);

        // HTTP 严格传输安全（仅 HTTPS）
        String hstsValue = "max-age=" + hstsMaxAge;
        if (hstsIncludeSubDomains) {
            hstsValue += "; includeSubDomains";
        }
        response.setHeader("Strict-Transport-Security", hstsValue);

        // 内容安全策略
        response.setHeader("Content-Security-Policy", contentSecurityPolicy);

        // 引用策略
        response.setHeader("Referrer-Policy", referrerPolicy);

        // 禁止权限策略（限制浏览器功能）
        response.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
    }
}
