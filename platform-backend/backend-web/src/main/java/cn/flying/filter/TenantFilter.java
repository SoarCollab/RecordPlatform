package cn.flying.filter;

import cn.flying.common.constant.ErrorPayload;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import cn.flying.common.util.ErrorPayloadFactory;
import cn.flying.common.util.SensitiveDataMasker;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Enumeration;
import java.util.Set;
import java.util.UUID;

/**
 * 租户过滤器
 * 对需要租户隔离的请求解析 X-Tenant-ID 并设置 TenantContext。
 * 匿名公开 proof/share 路径不信任调用者提供的租户头，其他未携带租户ID的请求（白名单除外）返回错误。
 */
@Component
@Order(Const.SECURITY_ORDER - 10)  // 在 JWT 过滤器之前执行
public class TenantFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantFilter.class);
    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String SSE_CONNECT_PATH = "/api/v1/sse/connect";
    private static final String PUBLIC_KEY_GRANT_CONSUME_PATH = "/api/v1/public/key-grants/consume";

    /**
     * 全局公开 proof 路径不依赖租户查询，必须忽略匿名调用者提供的租户头。
     */
    private static final Set<String> TENANT_HEADER_IGNORED_PATHS = Set.of(
            "/api/v1/public/proofs",
            "/api/v1/public/proof-keys"
    );

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
            "/api/v1/public/shares",
            // 公开 proof 状态和历史签名公钥
            "/api/v1/public/proofs",
            "/api/v1/public/proof-keys",
            // 图片下载端点（img 标签无法携带自定义 header）
            "/api/v1/images/download"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
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
            String maskedRequestUri = SensitiveDataMasker.maskSensitivePathSegments(requestUri);
            String requestPath = request.getServletPath();
            if (requestPath == null || requestPath.isEmpty()) {
                requestPath = requestUri;
            }
            String routeMatchingPath = SensitiveDataMasker.normalizePathForRouteMatching(requestPath);

            boolean whitelisted = isWhitelisted(routeMatchingPath);

            // 匿名公开资源必须完整忽略调用者租户头，包括重复或畸形值
            if (whitelisted && shouldIgnoreTenantHeader(request.getMethod(), routeMatchingPath)) {
                filterChain.doFilter(request, response);
                return;
            }

            // 租户身份头必须唯一，避免代理与 Servlet 对重复头采用不同解释
            if (hasMultipleTenantHeaders(request)) {
                log.warn("请求包含重复的租户ID头: {} {}", request.getMethod(), maskedRequestUri);
                sendErrorResponse(response, ResultEnum.PARAM_IS_INVALID, "租户标识格式错误");
                return;
            }

            String tenantIdHeader = request.getHeader(TENANT_HEADER);

            // 显式空头属于畸形租户身份，不能降级成“未提供”或让 SSE query 覆盖
            if (tenantIdHeader != null && tenantIdHeader.isEmpty()) {
                log.warn("租户ID头为空: {} {}", request.getMethod(), maskedRequestUri);
                sendErrorResponse(response, ResultEnum.PARAM_IS_INVALID, "租户标识格式错误");
                return;
            }

            boolean sseConnectRequest = SSE_CONNECT_PATH.equals(routeMatchingPath);
            if (tenantIdHeader == null) {
                // 仅对 SSE 连接接口允许从参数中获取租户提示，因为 EventSource 不支持自定义 Header
                if (sseConnectRequest) {
                    tenantIdHeader = request.getParameter("x-tenant-id");
                    if (tenantIdHeader == null || tenantIdHeader.isEmpty()) {
                        tenantIdHeader = request.getParameter("tenantId");
                    }
                }
            }

            if (tenantIdHeader == null || tenantIdHeader.isEmpty()) {
                if (whitelisted && !isSharePathFamily(routeMatchingPath)) {
                    filterChain.doFilter(request, response);
                    return;
                }
                log.warn("请求缺少租户ID: {} {}", request.getMethod(), maskedRequestUri);
                sendErrorResponse(response, ResultEnum.PARAM_IS_INVALID, "缺少租户标识 (X-Tenant-ID)");
                return;
            }

            Long tenantId;
            try {
                tenantId = Long.parseLong(tenantIdHeader);
            } catch (NumberFormatException e) {
                log.warn("租户ID格式错误: {}", tenantIdHeader);
                sendErrorResponse(response, ResultEnum.PARAM_IS_INVALID, "租户标识格式错误");
                return;
            }

            if (sseConnectRequest) {
                // SSE 匿名握手只能获得 Redis namespace 提示，短令牌消费成功前不能建立权威租户上下文
                request.setAttribute(Const.ATTR_SSE_TENANT_HINT, tenantId);
                log.debug("SSE 租户提示已解析: uri={}", maskedRequestUri);
                filterChain.doFilter(request, response);
                return;
            }

            // 设置租户上下文（会被 MyBatis-Plus 租户拦截器使用）
            TenantContext.setTenantId(tenantId);
            // 存储到请求属性，供 JWT 过滤器之后使用
            request.setAttribute(Const.ATTR_TENANT_ID, tenantId);

            log.debug("租户上下文已设置: tenantId={}, uri={}", tenantId, maskedRequestUri);

            // 解析异常捕获必须止于租户头，避免吞掉下游业务抛出的 NumberFormatException
            filterChain.doFilter(request, response);
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
        if (uri == null) {
            return false;
        }
        if (uri.matches("^/api/v1/shares/[^/]+/info/?$")) {
            return true;
        }
        if (uri.matches("^/api/v1/shares/[^/]+/files/?$")) {
            return true;
        }
        if (PUBLIC_KEY_GRANT_CONSUME_PATH.equals(uri)) {
            return true;
        }
        return WHITELIST_PATHS.stream().anyMatch(prefix -> matchesPathOrDescendant(uri, prefix));
    }

    /**
     * 判断白名单路径是否必须忽略请求租户头，并使用方法及路径段边界避免扩大匿名分享范围。
     *
     * @param method HTTP 方法
     * @param uri 请求路径
     * @return 是否忽略租户头
     */
    private boolean shouldIgnoreTenantHeader(String method, String uri) {
        boolean publicProof = TENANT_HEADER_IGNORED_PATHS.stream()
                .anyMatch(prefix -> matchesPathOrDescendant(uri, prefix));
        if (publicProof) {
            return true;
        }
        if ("POST".equalsIgnoreCase(method) && PUBLIC_KEY_GRANT_CONSUME_PATH.equals(uri)) {
            return true;
        }
        if (!"GET".equalsIgnoreCase(method)) {
            return false;
        }
        if (uri.matches("^/api/v1/shares/[^/]+/(info|files)/?$")) {
            return true;
        }
        return uri.matches("^/api/v1/public/shares/[^/]+/files/[^/]+/(chunks|decrypt-info)/?$");
    }

    /**
     * 判断请求是否属于分享路径族，避免通用前缀白名单放宽非匿名 GET 或受保护 mutation。
     *
     * @param uri 请求路径
     * @return 是否属于公开或受保护分享路径族
     */
    private boolean isSharePathFamily(String uri) {
        return matchesPathOrDescendant(uri, "/api/v1/shares")
                || matchesPathOrDescendant(uri, "/api/v1/public/shares")
                || matchesPathOrDescendant(uri, "/api/v1/public/key-grants");
    }

    /**
     * 检查请求是否包含多个租户身份头；重复值同样视为有歧义并拒绝。
     */
    private boolean hasMultipleTenantHeaders(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders(TENANT_HEADER);
        if (values == null || !values.hasMoreElements()) {
            return false;
        }
        values.nextElement();
        return values.hasMoreElements();
    }

    /**
     * 按完整路径段匹配白名单族，避免相似字符串前缀被误认为公开路径。
     */
    private boolean matchesPathOrDescendant(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    /**
     * 发送错误响应
     */
    private void sendErrorResponse(HttpServletResponse response, ResultEnum resultEnum, String message) throws IOException {
        response.setContentType("application/json;charset=utf-8");
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);

        String traceId = MDC.get(Const.TRACE_ID);
        ErrorPayload payload = ErrorPayloadFactory.of(traceId, message);

        PrintWriter writer = response.getWriter();
        writer.write(Result.error(resultEnum, payload).toJson());
        writer.flush();
    }
}
