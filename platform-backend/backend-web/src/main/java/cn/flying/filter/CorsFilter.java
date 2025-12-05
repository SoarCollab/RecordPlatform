package cn.flying.filter;

import cn.flying.common.util.Const;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Order(Const.ORDER_CORS)
public class CorsFilter extends HttpFilter {

    @Value("${spring.web.cors.origin}")
    private String origin;

    @Value("${spring.web.cors.credentials}")
    private boolean credentials;

    @Value("${spring.web.cors.methods}")
    private String methods;

    private Set<String> allowedOrigins;

    @PostConstruct
    public void init() {
        allowedOrigins = Arrays.stream(origin.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
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

    private String resolveOrigin(String requestOrigin) {
        if (requestOrigin == null) return null;
        if (allowedOrigins.contains("*") || allowedOrigins.contains(requestOrigin)) {
            return requestOrigin;
        }
        return null;
    }
}
