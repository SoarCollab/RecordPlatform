package cn.flying.identity.filter;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.service.GatewayMonitorService;
import cn.flying.identity.service.JwtBlacklistService;
import cn.flying.identity.util.IdUtils;
import cn.flying.identity.util.IpUtils;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * 增强的网关过滤器
 * 集成流量监控、性能统计、安全验证等功能
 *
 * @author 王贝强
 */
@Slf4j
@Component
@Order(1)
public class EnhancedGatewayFilter implements Filter {

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
            "/api/auth/third-party",
            "/oauth/**",
            "/api/sso/**",
            "/doc.html",
            "/swagger-ui/**",
            "/swagger-resources/**",
            "/v3/api-docs/**",
            "/webjars/**",
            "/druid/**",
            "/static/**",
            "/favicon.ico",
            "/actuator/**",
            "/error",
            "/docs/**"
    );

    @Value("${server.servlet.context-path}")
    String PREFIX;

    @Resource
    private GatewayMonitorService gatewayMonitorService;

    @Resource
    private JwtBlacklistService jwtBlacklistService;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // 生成请求ID
        String requestId = IdUtils.nextIdWithPrefix("REQ");
        MDC.put("requestId", requestId);

        String requestURI = httpRequest.getRequestURI();
        String method = httpRequest.getMethod();
        String clientIp = IpUtils.getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        long startTime = System.currentTimeMillis();

        try {
            // 获取用户ID（如果已登录）
            Long userId = null;
            try {
                if (StpUtil.isLogin()) {
                    userId = StpUtil.getLoginIdAsLong();
                }
            } catch (Exception e) {
                // 忽略获取用户ID的异常
            }

            // 记录请求开始
            gatewayMonitorService.recordRequestStart(requestId, method, requestURI,
                    clientIp, userAgent, userId);

            // 检查流量限制
            Result<Boolean> rateLimitResult = gatewayMonitorService.checkRateLimit(clientIp, userId, requestURI);
            if (rateLimitResult.getCode() == ResultEnum.SUCCESS.getCode() && !rateLimitResult.getData()) {
                writeErrorResponse(httpResponse, Result.error(ResultEnum.SYSTEM_BUSY, null));
                recordRequestEnd(requestId, 429, 0, System.currentTimeMillis() - startTime, "Rate limit exceeded");
                return;
            }

            String url = requestURI;
            if (requestURI.startsWith(PREFIX)) {
                url = requestURI.substring(PREFIX.length());
            }

            // 检查是否为排除路径
            if (isExcludePath(url)) {
                log.debug("请求路径 {} 在排除列表中，跳过鉴权", requestURI);

                // 使用响应包装器以便记录响应信息
                ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(httpResponse);
                chain.doFilter(request, responseWrapper);

                // 记录请求结束
                recordRequestEnd(requestId, responseWrapper.getStatus(),
                        responseWrapper.getContentSize(),
                        System.currentTimeMillis() - startTime, null);

                responseWrapper.copyBodyToResponse();
                return;
            }

            // 执行Token验证
            validateToken(httpRequest);

            // 执行权限验证
            validatePermission(requestURI, method);

            // 使用响应包装器以便记录响应信息
            ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(httpResponse);
            chain.doFilter(request, responseWrapper);

            // 记录请求结束
            recordRequestEnd(requestId, responseWrapper.getStatus(),
                    responseWrapper.getContentSize(),
                    System.currentTimeMillis() - startTime, null);

            responseWrapper.copyBodyToResponse();

        } catch (NotLoginException e) {
            log.warn("用户未登录，请求路径: {}, IP: {}", requestURI, clientIp);
            writeErrorResponse(httpResponse, Result.error(ResultEnum.USER_NOT_LOGGED_IN, null));
            recordRequestEnd(requestId, 401, 0, System.currentTimeMillis() - startTime, "User not logged in");
        } catch (NotRoleException e) {
            log.warn("用户角色不足，请求路径: {}, 需要角色: {}, IP: {}", requestURI, e.getRole(), clientIp);
            writeErrorResponse(httpResponse, Result.error(ResultEnum.PERMISSION_UNAUTHORIZED, null));
            recordRequestEnd(requestId, 403, 0, System.currentTimeMillis() - startTime, "Insufficient role");
        } catch (NotPermissionException e) {
            log.warn("用户权限不足，请求路径: {}, 需要权限: {}, IP: {}", requestURI, e.getPermission(), clientIp);
            writeErrorResponse(httpResponse, Result.error(ResultEnum.PERMISSION_UNAUTHORIZED, null));
            recordRequestEnd(requestId, 403, 0, System.currentTimeMillis() - startTime, "Insufficient permission");
        } catch (Exception e) {
            log.error("网关过滤器处理异常，请求路径: {}, IP: {}", requestURI, clientIp, e);
            writeErrorResponse(httpResponse, Result.error(ResultEnum.SYSTEM_ERROR, null));
            recordRequestEnd(requestId, 500, 0, System.currentTimeMillis() - startTime, e.getMessage());
        } finally {
            // 清理MDC
            MDC.clear();
        }
    }

    /**
     * 写入错误响应
     */
    private void writeErrorResponse(HttpServletResponse response, Result<?> result) throws IOException {
        response.setStatus(getHttpStatusFromResult(result));
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSONUtil.toJsonStr(result));
    }

    /**
     * 记录请求结束
     */
    private void recordRequestEnd(String requestId, int statusCode, long responseSize,
                                  long executionTime, String errorMessage) {
        try {
            gatewayMonitorService.recordRequestEnd(requestId, statusCode, responseSize,
                    executionTime, errorMessage);
        } catch (Exception e) {
            log.error("记录请求结束失败", e);
        }
    }

    /**
     * 检查是否为排除路径
     * 支持精确路径匹配和通配符前缀匹配（如"/doc/**"）
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
            // 黑名单检查：若在黑名单中则视为未登录
            if (jwtBlacklistService != null && jwtBlacklistService.isBlacklisted(token)) {
                throw new NotLoginException("token in blacklist", null, null);
            }
            // 设置当前请求的Token
            StpUtil.setTokenValue(token);
        }

        // 检查登录状态
        StpUtil.checkLogin();
    }

    /**
     * 验证权限
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

        // OAuth和SSO接口需要登录即可
        if (requestURI.startsWith("/oauth") || requestURI.startsWith("/api/sso")) {
            // 已经通过了Token验证，无需额外权限检查
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
     * 从Result获取HTTP状态码
     */
    private int getHttpStatusFromResult(Result<?> result) {
        if (result.getCode() == ResultEnum.USER_NOT_LOGGED_IN.getCode()) {
            return 401;
        } else if (result.getCode() == ResultEnum.PERMISSION_UNAUTHORIZED.getCode()) {
            return 403;
        } else if (result.getCode() == ResultEnum.SYSTEM_BUSY.getCode()) {
            return 429;
        } else {
            return 500;
        }
    }
}
