package cn.flying.identity.filter;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.stp.StpUtil;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import jakarta.annotation.Resource;
import cn.flying.identity.service.JwtBlacklistService;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Sa-Token网关鉴权过滤器
 * 实现统一的Token验证和权限控制。
 * 注意：对 OAuth 与 SSO 相关端点采用放行或轻鉴权策略，避免与授权流程冲突。
 */
@Component
@Order(2)
public class SaTokenGatewayFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(SaTokenGatewayFilter.class);
    /**
     * 不需要鉴权的路径列表
     */
    private static final List<String> EXCLUDE_PATHS = Arrays.asList(
            "/api/auth/login",
            "/api/auth/signin",
            "/api/auth/register",
            "/api/auth/signup",
            "/api/auth/verify-code",
            "/api/auth/reset-password",
            "/api/auth/status",
            "/oauth/**",           // OAuth 全量交由具体Controller/Service处理
            "/api/sso/**",         // SSO 全量放行到具体逻辑
            "/doc.html",
            "/swagger-ui/**",
            "/swagger-resources/**",
            "/v3/api-docs/**",
            "/webjars/**",
            "/druid/**",
            "/static/**",
            "/favicon.ico",
            "/actuator/**",
            "/docs/**"
    );
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${server.servlet.context-path}")
    String PREFIX;

    @Resource
    private JwtBlacklistService jwtBlacklistService;

    /**
     * 过滤器初始化
     *
     * @param filterConfig 过滤器配置
     */
    @Override
    public void init(FilterConfig filterConfig) {
        logger.info("Sa-Token网关鉴权过滤器初始化完成");
    }

    /**
     * 执行过滤逻辑
     *
     * @param request  请求对象
     * @param response 响应对象
     * @param chain    过滤器链
     * @throws IOException IO异常
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String requestURI = httpRequest.getRequestURI();
        String method = httpRequest.getMethod();

        logger.debug("网关鉴权过滤器处理请求: {} {}", method, requestURI);

        try {

            String url = requestURI;
            if (requestURI.startsWith(PREFIX)) {
                url = requestURI.substring(PREFIX.length());
            }

            // 检查是否为排除路径
            if (isExcludePath(url)) {
                logger.debug("请求路径 {} 在排除列表中，跳过鉴权", requestURI);
                chain.doFilter(request, response);
                return;
            }

            // 执行Token验证
            validateToken(httpRequest);

            // 执行权限验证
            validatePermission(url, method);

            // 验证通过，继续执行
            chain.doFilter(request, response);

        } catch (NotLoginException e) {
            logger.warn("用户未登录，请求路径: {}", requestURI);
            writeErrorResponse(httpResponse, Result.error(ResultEnum.USER_NOT_LOGGED_IN, null));
        } catch (NotRoleException e) {
            logger.warn("用户角色不足，请求路径: {}, 需要角色: {}", requestURI, e.getRole());
            writeErrorResponse(httpResponse, Result.error(ResultEnum.PERMISSION_UNAUTHORIZED, null));
        } catch (NotPermissionException e) {
            logger.warn("用户权限不足，请求路径: {}, 需要权限: {}", requestURI, e.getPermission());
            writeErrorResponse(httpResponse, Result.error(ResultEnum.PERMISSION_UNAUTHORIZED, null));
        } catch (Exception e) {
            logger.error("网关鉴权过滤器处理异常，请求路径: {}", requestURI, e);
            writeErrorResponse(httpResponse, Result.error(ResultEnum.SYSTEM_ERROR, null));
        }
    }

    /**
     * 检查是否为排除路径
     * 支持精确路径匹配和通配符前缀匹配（如"/doc/**"）
     *
     * @param requestURI 请求URI（已去除context-path的相对路径）
     * @return 是否为排除路径
     */
    private boolean isExcludePath(String requestURI) {
        return EXCLUDE_PATHS.stream().anyMatch(excludePath -> {
            if (excludePath.endsWith("/**")) {
                String prefix = excludePath.substring(0, excludePath.length() - 3);
                return requestURI.startsWith(prefix);
            }
            return requestURI.equals(excludePath);
        });
    }

    /**
     * 验证Token
     *
     * @param request 请求对象
     * @throws NotLoginException 未登录异常
     */
    private void validateToken(HttpServletRequest request) throws NotLoginException {
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        if (token == null || token.trim().isEmpty()) {
            token = request.getParameter("satoken");
        }
        if (token != null && !token.trim().isEmpty()) {
            if (jwtBlacklistService != null && jwtBlacklistService.isBlacklisted(token)) {
                throw new NotLoginException("token in blacklist", null, null);
            }
            StpUtil.setTokenValue(token);
        }
        StpUtil.checkLogin();
    }

    /**
     * 验证权限
     *
     * @param cleanUri 已去除context-path的URI
     * @param method   HTTP方法
     * @throws NotPermissionException 权限不足异常
     * @throws NotRoleException       角色不足异常
     */
    private void validatePermission(String cleanUri, String method)
            throws NotPermissionException, NotRoleException {

        // 管理员接口权限验证
        if (cleanUri.startsWith("/api/admin")) {
            StpUtil.checkRole("admin");
            return;
        }

        // Token监控接口权限验证
        if (cleanUri.startsWith("/api/token/monitor")) {
            StpUtil.checkRoleOr("admin", "monitor");
            if (cleanUri.contains("/record") || cleanUri.contains("/handle")) {
                StpUtil.checkPermission("token:monitor:record");
            } else if (cleanUri.contains("/query") || cleanUri.contains("/stats")) {
                StpUtil.checkPermission("token:monitor:query");
            } else if (cleanUri.contains("/export") || cleanUri.contains("/clean")) {
                StpUtil.checkRole("admin");
            }
            return;
        }

        // 审计日志接口权限验证
        if (cleanUri.startsWith("/api/audit")) {
            StpUtil.checkRoleOr("admin", "monitor");
            if (cleanUri.contains("/export") || cleanUri.contains("/cleanup")) {
                StpUtil.checkRole("admin");
            }
            return;
        }

        // 网关监控接口
        if (cleanUri.startsWith("/api/admin/gateway")) {
            StpUtil.checkRole("admin");
            return;
        }

        // 用户统计接口
        if (cleanUri.startsWith("/api/admin/user-stats")) {
            StpUtil.checkRoleOr("admin", "monitor");
            return;
        }

        // 操作日志接口
        if (cleanUri.startsWith("/api/admin/operation-logs")) {
            StpUtil.checkRoleOr("admin", "monitor");
            if (cleanUri.contains("/batch") || cleanUri.contains("/cleanup")) {
                StpUtil.checkRole("admin");
            }
            return;
        }

        // 用户接口（自身信息与少量变更）
        if (cleanUri.startsWith("/api/user")) {
            StpUtil.checkLogin();
            if (cleanUri.contains("/admin/")) {
                StpUtil.checkRole("admin");
            }
            return;
        }

        // 验证码接口
        if (cleanUri.startsWith("/api/verify")) {
            StpUtil.checkLogin();
            if (cleanUri.contains("/admin/")) {
                StpUtil.checkRole("admin");
            }
            return;
        }

        // 第三方认证接口
        if (cleanUri.startsWith("/api/auth/third-party")) {
            StpUtil.checkLogin();
            return;
        }

        // 默认权限验证 - 需要登录
        StpUtil.checkLogin();
    }

    /**
     * 写入错误响应
     * 根据业务错误码返回合适的HTTP状态码（401/403/429/500）。
     *
     * @param response 响应对象
     * @param result   错误结果
     * @throws IOException IO异常
     */
    private void writeErrorResponse(HttpServletResponse response, Result<?> result) throws IOException {
        int status;
        if (result.getCode() == ResultEnum.USER_NOT_LOGGED_IN.getCode()) {
            status = HttpServletResponse.SC_UNAUTHORIZED;
        } else if (result.getCode() == ResultEnum.PERMISSION_UNAUTHORIZED.getCode()) {
            status = HttpServletResponse.SC_FORBIDDEN;
        } else if (result.getCode() == ResultEnum.SYSTEM_BUSY.getCode()) {
            status = 429;
        } else {
            status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        }
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        String jsonResponse = objectMapper.writeValueAsString(result);
        response.getWriter().write(jsonResponse);
        response.getWriter().flush();
    }

    /**
     * 过滤器销毁
     */
    @Override
    public void destroy() {
        logger.info("Sa-Token网关鉴权过滤器销毁");
    }
}