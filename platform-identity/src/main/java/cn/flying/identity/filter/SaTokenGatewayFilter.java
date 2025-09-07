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
@Order(1)
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
            "/swagger-ui",
            "/swagger-resources",
            "/v3/api-docs",
            "/webjars",
            "/druid",
            "/static",
            "/favicon.ico",
            "/actuator"
    );
    private final ObjectMapper objectMapper = new ObjectMapper();

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
     * @throws IOException      IO异常
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
            // 检查是否为排除路径
            if (isExcludePath(requestURI)) {
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
     *
     * @param requestURI 请求URI
     * @return 是否为排除路径
     */
    private boolean isExcludePath(String requestURI) {
        return EXCLUDE_PATHS.stream().anyMatch(requestURI::startsWith);
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

        // 管理员接口权限验证
        if (requestURI.startsWith("/api/admin")) {
            StpUtil.checkRole("admin");
            return;
        }

        // 用户接口权限验证
        if (requestURI.startsWith("/api/user")) {
            StpUtil.checkRoleOr("admin", "user");
            return;
        }

        // 根据HTTP方法进行权限验证
        switch (method.toUpperCase()) {
            case "GET":
                // 读取权限
                StpUtil.checkPermissionOr("read", "admin");
                break;
            case "POST":
            case "PUT":
            case "PATCH":
                // 写入权限
                StpUtil.checkPermissionOr("write", "admin");
                break;
            case "DELETE":
                // 删除权限
                StpUtil.checkPermissionOr("delete", "admin");
                break;
            default:
                // 其他方法需要基本权限
                StpUtil.checkPermissionOr("basic", "admin");
                break;
        }
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