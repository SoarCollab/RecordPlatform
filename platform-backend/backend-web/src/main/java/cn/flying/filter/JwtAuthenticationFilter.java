package cn.flying.filter;

import cn.flying.common.constant.ErrorPayload;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import cn.flying.common.util.ErrorPayloadFactory;
import cn.flying.common.util.JwtUtils;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.UUID;

/**
 * JWT authentication filter with multi-tenant context support.
 * Validates JWT tokens, sets security context, tenant context and MDC for logging.
 * Also initializes traceId for distributed tracing.
 * 
 * 注意：租户ID由 TenantFilter 从请求头 X-Tenant-ID 设置，
 * 此过滤器验证 JWT 中的 tenantId 与请求头一致，防止跨租户攻击。
 */
@Component
@Order(Const.SECURITY_ORDER)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    @Resource
    JwtUtils utils;

    /**
     * 解析并校验 JWT，设置安全上下文与租户相关的日志上下文。
     */
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

        try {
            // 获取 TenantFilter 设置的租户ID（来自请求头 X-Tenant-ID）
            Long headerTenantId = (Long) request.getAttribute(Const.ATTR_TENANT_ID);

            String authorization = request.getHeader("Authorization");
            DecodedJWT jwt = utils.resolveJwt(authorization);
            if(jwt != null) {
                // Extract user info from JWT
                Long userId = utils.toId(jwt);
                String userRole = utils.toRole(jwt);
                Long jwtTenantId = utils.toTenantId(jwt);

                // 验证 JWT 中的 tenantId 与请求头一致（防止跨租户攻击）
                if (!validateTenantMatch(headerTenantId, jwtTenantId, userId, response)) {
                    SecurityContextHolder.clearContext();
                    return;
                }

                // 白名单路径可能绕过 TenantFilter（例如公开分享、图片下载），此时补齐租户上下文
                if (headerTenantId == null && jwtTenantId != null) {
                    TenantContext.setTenantId(jwtTenantId);
                    request.setAttribute(Const.ATTR_TENANT_ID, jwtTenantId);
                    headerTenantId = jwtTenantId;
                }

                UserDetails user = utils.toUser(jwt);
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);

                // Store in request attributes
                request.setAttribute(Const.ATTR_USER_ID, userId);
                request.setAttribute(Const.ATTR_USER_ROLE, userRole);
                // 租户ID已由 TenantFilter 设置，这里不覆盖

                // Set MDC for logging
                if (userId != null) {
                    MDC.put(Const.ATTR_USER_ID, userId.toString());
                }
                if (userRole != null) {
                    MDC.put(Const.ATTR_USER_ROLE, userRole);
                }
                if (headerTenantId != null) {
                    MDC.put(Const.ATTR_TENANT_ID, headerTenantId.toString());
                }
            }

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

    /**
     * 校验请求头租户ID与 JWT 租户ID 是否一致。
     * 拒绝情况：
     * 1. 两者都存在但不相等
     * 2. 请求头有租户ID但 JWT 中没有（防止跨租户令牌滥用）
     */
    private boolean validateTenantMatch(Long headerTenantId,
                                        Long jwtTenantId,
                                        Long userId,
                                        HttpServletResponse response) throws IOException {
        // 请求头有租户ID但 JWT 中没有，拒绝请求（JWT 应包含租户信息）
        if (headerTenantId != null && jwtTenantId == null) {
            log.warn("JWT缺少租户ID: 请求头tenantId={}, userId={}", headerTenantId, userId);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=utf-8");

            String traceId = MDC.get(Const.TRACE_ID);
            ErrorPayload payload = ErrorPayloadFactory.of(traceId, "令牌缺少租户信息");

            PrintWriter writer = response.getWriter();
            writer.write(Result.error(ResultEnum.PERMISSION_UNAUTHORIZED, payload).toJson());
            writer.flush();
            return false;
        }
        // 两者都存在但不相等，拒绝请求
        if (headerTenantId != null && jwtTenantId != null && !Objects.equals(headerTenantId, jwtTenantId)) {
            log.warn("租户ID不匹配: 请求头={}, JWT={}, userId={}", headerTenantId, jwtTenantId, userId);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=utf-8");

            String traceId = MDC.get(Const.TRACE_ID);
            ErrorPayload payload = ErrorPayloadFactory.of(traceId, "租户ID不匹配");

            PrintWriter writer = response.getWriter();
            writer.write(Result.error(ResultEnum.PERMISSION_UNAUTHORIZED, payload).toJson());
            writer.flush();
            return false;
        }
        return true;
    }
}
