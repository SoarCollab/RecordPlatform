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

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Sa-Token网关鉴权过滤器
 * 实现统一的Token验证和权限控制
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
            validatePermission(requestURI, method);

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
     * @param requestURI 请求URI
     * @return 是否为排除路径
     */
    private boolean isExcludePath(String requestURI) {
        return EXCLUDE_PATHS.stream().anyMatch(excludePath -> {
            // 如果排除路径以"/**"结尾，则进行前缀匹配
            if (excludePath.endsWith("/**")) {
                String prefix = excludePath.substring(0, excludePath.length() - 3);
                return requestURI.startsWith(prefix);
            }
            // 否则进行精确匹配
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
        // 从请求头获取Token
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        // 如果请求头没有Token，尝试从参数获取
        if (token == null || token.trim().isEmpty()) {
            token = request.getParameter("satoken");
        }

        // 验证Token
        if (token != null && !token.trim().isEmpty()) {
            // 设置当前请求的Token
            StpUtil.setTokenValue(token);
        }

        // 检查登录状态
        StpUtil.checkLogin();
    }

    /**
     * 验证权限
     *
     * @param requestURI 请求URI
     * @param method     HTTP方法
     * @throws NotPermissionException 权限不足异常
     * @throws NotRoleException       角色不足异常
     */
    private void validatePermission(String requestURI, String method)
            throws NotPermissionException, NotRoleException {

        // 移除前缀处理
        String cleanUri = requestURI;
        if (requestURI.startsWith(PREFIX)) {
            cleanUri = requestURI.substring(PREFIX.length());
        }

        // 管理员接口权限验证
        if (cleanUri.startsWith("/api/admin")) {
            StpUtil.checkRole("admin");
            return;
        }

        // Token监控接口权限验证
        if (cleanUri.startsWith("/api/token/monitor")) {
            StpUtil.checkRoleOr("admin", "monitor");
            // 进一步细化权限检查
            if (cleanUri.contains("/record") || cleanUri.contains("/handle")) {
                StpUtil.checkPermission("token:monitor:record");
            } else if (cleanUri.contains("/query") || cleanUri.contains("/stats")) {
                StpUtil.checkPermission("token:monitor:query");
            } else if (cleanUri.contains("/export") || cleanUri.contains("/clean")) {
                StpUtil.checkRole("admin"); // 仅管理员可以导出和清理
            }
            return;
        }

        // 审计日志接口权限验证
        if (cleanUri.startsWith("/api/audit")) {
            StpUtil.checkRoleOr("admin", "monitor");
            if (cleanUri.contains("/export") || cleanUri.contains("/cleanup")) {
                StpUtil.checkRole("admin"); // 仅管理员可以导出和清理
            }
            return;
        }

        // OAuth接口权限验证
        if (cleanUri.startsWith("/oauth")) {
            // OAuth授权接口允许已登录用户访问
            StpUtil.checkLogin();
            if (cleanUri.contains("/client")) {
                StpUtil.checkRole("admin"); // 客户端管理仅管理员可操作
            }
            return;
        }

        // SSO接口权限验证
        if (cleanUri.startsWith("/api/sso")) {
            StpUtil.checkLogin();
            return;
        }

        // 用户接口权限验证
        if (cleanUri.startsWith("/api/user")) {
            StpUtil.checkLogin();
            // 用户管理相关接口需要额外权限检查
            if (cleanUri.contains("/admin/")) {
                StpUtil.checkRole("admin");
            }
            return;
        }

        // 验证码接口权限验证
        if (cleanUri.startsWith("/api/verify")) {
            StpUtil.checkLogin();
            // 管理员功能需要管理员权限
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

        // 网关监控接口权限验证
        if (cleanUri.startsWith("/api/admin/gateway")) {
            StpUtil.checkRole("admin");
            return;
        }

        // 用户统计接口权限验证
        if (cleanUri.startsWith("/api/admin/user-stats")) {
            StpUtil.checkRoleOr("admin", "monitor");
            return;
        }

        // 操作日志接口权限验证
        if (cleanUri.startsWith("/api/admin/operation-logs")) {
            StpUtil.checkRoleOr("admin", "monitor");
            if (cleanUri.contains("/batch") || cleanUri.contains("/cleanup")) {
                StpUtil.checkRole("admin"); // 批量操作仅管理员可操作
            }
            return;
        }

        // 默认权限验证 - 需要登录
        StpUtil.checkLogin();
    }

    /**
     * 写入错误响应
     *
     * @param response 响应对象
     * @param result   错误结果
     * @throws IOException IO异常
     */
    private void writeErrorResponse(HttpServletResponse response, Result<?> result) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
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