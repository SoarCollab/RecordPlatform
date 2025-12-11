package cn.flying.filter;

import cn.flying.common.util.Const;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CORS 跨域过滤器
 * P0-3 安全修复：禁止生产环境使用通配符 Origin
 */
@Slf4j
@Component
@Order(Const.ORDER_CORS)
public class CorsFilter extends HttpFilter {

    @Value("${spring.web.cors.origin}")
    private String origin;

    @Value("${spring.web.cors.credentials}")
    private boolean credentials;

    @Value("${spring.web.cors.methods}")
    private String methods;

    @Value("${spring.profiles.active:local}")
    private String activeProfile;

    private Set<String> allowedOrigins;
    private Set<String> activeProfiles;

    @PostConstruct
    public void init() {
        activeProfiles = parseActiveProfiles(activeProfile);
        allowedOrigins = Arrays.stream(origin.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        // P0-3 修复：检测并警告通配符配置
        if (allowedOrigins.contains("*")) {
            if (isProductionProfile()) {
                log.error("安全警告：生产环境检测到 CORS 通配符配置！这是严重的安全风险，将禁用通配符。" +
                        "请配置具体的允许域名列表：spring.web.cors.origin");
                allowedOrigins.remove("*");
            } else {
                log.warn("CORS 配置使用了通配符 '*'，仅允许在开发环境使用。当前环境: {}", activeProfiles);
            }
        }

        log.info("CORS 过滤器初始化完成，允许的域: {}, 当前环境: {}", allowedOrigins, activeProfiles);
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        this.addCorsHeader(request, response);
        chain.doFilter(request, response);
    }

    private void addCorsHeader(HttpServletRequest request, HttpServletResponse response) {
        String requestOrigin = request.getHeader("Origin");
        String resolvedOrigin = resolveOrigin(requestOrigin);
        if (resolvedOrigin != null) {
            response.addHeader("Access-Control-Allow-Origin", resolvedOrigin);
            response.addHeader("Access-Control-Allow-Methods", methods);
            response.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
            if (credentials) {
                response.addHeader("Access-Control-Allow-Credentials", "true");
            }
        }
    }

    /**
     * 解析并验证请求的 Origin
     * P0-3 修复：
     * - 生产环境禁止通配符
     * - 支持子域名通配符匹配 (如 *.example.com)
     */
    private String resolveOrigin(String requestOrigin) {
        if (requestOrigin == null) {
            return null;
        }

        // P0-3 修复：生产环境下禁止通配符
        if (allowedOrigins.contains("*")) {
            if (isProductionProfile()) {
                log.warn("生产环境拒绝 CORS 通配符请求: Origin={}", requestOrigin);
                return null;
            }
            // 开发环境允许通配符
            return requestOrigin;
        }

        // 精确匹配
        if (allowedOrigins.contains(requestOrigin)) {
            return requestOrigin;
        }

        // 子域名通配符匹配 (如 *.example.com)
        String host = extractHost(requestOrigin);
        if (host == null) {
            return null;
        }
        String lowerHost = host.toLowerCase();
        for (String allowed : allowedOrigins) {
            if (allowed.startsWith("*.") && requestOrigin.endsWith(allowed.substring(1))) {
                // 确保是子域名而不是后缀匹配
                String allowedDomain = allowed.substring(2).toLowerCase();
                if (lowerHost.endsWith(allowedDomain)) {
                    int prefixEndIndex = lowerHost.length() - allowedDomain.length() - 1;
                    if (prefixEndIndex >= 0 && lowerHost.charAt(prefixEndIndex) == '.') {
                        return requestOrigin;
                    }
                }
            }
        }

        return null;
    }

    /**
     * 解析 Origin 头部的主机名，解析失败时返回 null
     */
    private String extractHost(String requestOrigin) {
        try {
            URI uri = URI.create(requestOrigin);
            return uri.getHost();
        } catch (IllegalArgumentException ex) {
            log.warn("无法解析请求 Origin: {}", requestOrigin);
            return null;
        }
    }

    /**
     * 判断是否为生产环境
     */
    private boolean isProductionProfile() {
        return activeProfiles.stream().anyMatch(profile ->
                "prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile));
    }

    /**
     * 解析逗号分隔的 spring.profiles.active，统一转小写并去除空白
     */
    private Set<String> parseActiveProfiles(String profiles) {
        return Arrays.stream(profiles.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }
}
