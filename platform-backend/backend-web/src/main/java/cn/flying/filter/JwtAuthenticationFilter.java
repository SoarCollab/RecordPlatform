package cn.flying.filter;

import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import cn.flying.common.util.JwtUtils;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * JWT authentication filter with multi-tenant context support.
 * Validates JWT tokens, sets security context, tenant context and MDC for logging.
 * Also initializes traceId for distributed tracing.
 */
@Component
@Order(Const.SECURITY_ORDER)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Resource
    JwtUtils utils;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NotNull HttpServletResponse response,
                                    @NotNull FilterChain filterChain) throws ServletException, IOException {
        // 初始化 traceId，用于分布式追踪
        String traceId = MDC.get(Const.TRACE_ID);
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
            MDC.put(Const.TRACE_ID, traceId);
        }
        request.setAttribute(Const.TRACE_ID, traceId);

        String authorization = request.getHeader("Authorization");
        DecodedJWT jwt = utils.resolveJwt(authorization);
        if(jwt != null) {
            UserDetails user = utils.toUser(jwt);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // Extract user info from JWT
            Long userId = utils.toId(jwt);
            String userRole = utils.toRole(jwt);
            Long tenantId = utils.toTenantId(jwt);

            // Store in request attributes
            request.setAttribute(Const.ATTR_USER_ID, userId);
            request.setAttribute(Const.ATTR_USER_ROLE, userRole);
            request.setAttribute(Const.ATTR_TENANT_ID, tenantId);

            // Set tenant context for MyBatis-Plus multi-tenant interceptor
            if (tenantId != null) {
                TenantContext.setTenantId(tenantId);
            }

            // Set MDC for logging
            if (userId != null) {
                MDC.put(Const.ATTR_USER_ID, userId.toString());
            }
            if (userRole != null) {
                MDC.put(Const.ATTR_USER_ROLE, userRole);
            }
            if (tenantId != null) {
                MDC.put(Const.ATTR_TENANT_ID, tenantId.toString());
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Cleanup MDC and tenant context
            MDC.remove(Const.TRACE_ID);
            MDC.remove(Const.ATTR_USER_ID);
            MDC.remove(Const.ATTR_USER_ROLE);
            MDC.remove(Const.ATTR_TENANT_ID);
            TenantContext.clear();
        }
    }
}
