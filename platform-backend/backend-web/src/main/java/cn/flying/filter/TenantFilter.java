package cn.flying.filter;

import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 租户过滤器
 * 从请求头 X-Tenant-ID 中解析租户ID，设置到 TenantContext
 * 对于未携带租户ID的请求（白名单除外），返回错误
 */
@Component
@Order(Const.SECURITY_ORDER - 10)  // 在 JWT 过滤器之前执行
public class TenantFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantFilter.class);
    private static final String TENANT_HEADER = "X-Tenant-ID";

    /**
     * 白名单路径 - 这些路径不需要租户ID
     * 主要用于健康检查、Swagger文档、公开分享等公共端点
     */
    private static final Set<String> WHITELIST_PATHS = Set.of(
            "/actuator/health",
            "/actuator/info",
            "/swagger-ui",
            "/v3/api-docs",
            "/doc.html",
            "/webjars",
            "/favicon.ico",
            "/error",
            // 公开分享相关端点
            "/api/v1/share",
            "/api/v1/files/getSharingFiles",
            "/api/v1/files/public/download",
            "/api/v1/files/public/decryptInfo",
            // 图片下载端点（img 标签无法携带自定义 header）
            "/api/v1/images/download"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NotNull HttpServletResponse response,
                                    @NotNull FilterChain filterChain) throws ServletException, IOException {
        String traceId = MDC.get(Const.TRACE_ID);
        boolean traceIdGenerated = false;
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
            MDC.put(Const.TRACE_ID, traceId);
            traceIdGenerated = true;
        }
        request.setAttribute(Const.TRACE_ID, traceId);

        // 外层 try/finally 确保无论何种分支都清理 TenantContext，防止线程复用泄漏
        try {
            // 防止线程复用导致的 TenantContext 泄漏（例如：前一次请求在错误分支提前返回未进入 JWT 过滤器清理）
            TenantContext.clear();

            String requestUri = request.getRequestURI();
            String requestPath = request.getServletPath();
            if (requestPath == null || requestPath.isEmpty()) {
                requestPath = requestUri;
            }

            // 检查是否在白名单中
            if (isWhitelisted(requestPath)) {
                // 白名单路径不强制要求租户头，但若请求主动携带租户头则仍写入上下文，确保后续 DB 查询具备正确的 tenant_id 条件
                String tenantIdHeader = request.getHeader(TENANT_HEADER);
                if (tenantIdHeader != null && !tenantIdHeader.isEmpty()) {
                    try {
                        Long tenantId = Long.parseLong(tenantIdHeader);
                        TenantContext.setTenantId(tenantId);
                        request.setAttribute(Const.ATTR_TENANT_ID, tenantId);
                    } catch (NumberFormatException e) {
                        log.warn("租户ID格式错误: {}", tenantIdHeader);
                        sendErrorResponse(response, ResultEnum.PARAM_IS_INVALID, "租户标识格式错误");
                        return;
                    }
                }
                filterChain.doFilter(request, response);
                return;
            }

            // 从请求头获取租户ID
            String tenantIdHeader = request.getHeader(TENANT_HEADER);

            if (tenantIdHeader == null || tenantIdHeader.isEmpty()) {
                // 仅对 SSE 连接接口允许从参数中获取租户ID，因为 EventSource 不支持自定义 Header
                if (requestPath.contains("/sse/connect")) {
                    tenantIdHeader = request.getParameter("x-tenant-id");
                    if (tenantIdHeader == null || tenantIdHeader.isEmpty()) {
                        tenantIdHeader = request.getParameter("tenantId");
                    }
                }
            }

            if (tenantIdHeader == null || tenantIdHeader.isEmpty()) {
                log.warn("请求缺少租户ID: {} {}", request.getMethod(), requestUri);
                sendErrorResponse(response, ResultEnum.PARAM_IS_INVALID, "缺少租户标识 (X-Tenant-ID)");
                return;
            }

            try {
                Long tenantId = Long.parseLong(tenantIdHeader);
                // 设置租户上下文（会被 MyBatis-Plus 租户拦截器使用）
                TenantContext.setTenantId(tenantId);
                // 存储到请求属性，供 JWT 过滤器之后使用
                request.setAttribute(Const.ATTR_TENANT_ID, tenantId);

                log.debug("租户上下文已设置: tenantId={}, uri={}", tenantId, requestUri);

                filterChain.doFilter(request, response);
            } catch (NumberFormatException e) {
                log.warn("租户ID格式错误: {}", tenantIdHeader);
                sendErrorResponse(response, ResultEnum.PARAM_IS_INVALID, "租户标识格式错误");
            }
            // 注意：正常路径不在这里清理 TenantContext，由 JwtAuthenticationFilter 统一清理
        } finally {
            TenantContext.clear();
            if (traceIdGenerated) {
                MDC.remove(Const.TRACE_ID);
            }
        }
    }

    /**
     * 检查路径是否在白名单中
     */
    private boolean isWhitelisted(String uri) {
        return WHITELIST_PATHS.stream().anyMatch(uri::startsWith);
    }

    /**
     * 发送错误响应
     */
    private void sendErrorResponse(HttpServletResponse response, ResultEnum resultEnum, String message) throws IOException {
        response.setContentType("application/json;charset=utf-8");
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);

        Map<String, Object> payload = new LinkedHashMap<>();
        String traceId = MDC.get(Const.TRACE_ID);
        if (traceId != null && !traceId.isEmpty()) {
            payload.put("traceId", traceId);
        }
        payload.put("detail", message);

        PrintWriter writer = response.getWriter();
        writer.write(Result.error(resultEnum, payload).toJson());
        writer.flush();
    }
}
