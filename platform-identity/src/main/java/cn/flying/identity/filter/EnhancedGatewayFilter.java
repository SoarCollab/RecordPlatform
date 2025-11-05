package cn.flying.identity.filter;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.config.AuthWhitelistProperties;
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
import java.util.List;

/**
 * 增强的网关过滤器
 * 集成流量监控、性能统计、安全验证等功能。
 */
@Slf4j
@Component
@Order(1)
public class EnhancedGatewayFilter implements Filter {

    private final AuthWhitelistProperties authWhitelistProperties;

    @Value("${server.servlet.context-path:}")
    String PREFIX;

    @Resource
    private GatewayMonitorService gatewayMonitorService;

    @Resource
    private JwtBlacklistService jwtBlacklistService;

    public EnhancedGatewayFilter(AuthWhitelistProperties authWhitelistProperties) {
        this.authWhitelistProperties = authWhitelistProperties;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // 生成请求ID并写入MDC，便于链路追踪
        String requestId = IdUtils.nextIdWithPrefix("REQ");
        MDC.put("requestId", requestId);

        String requestURI = httpRequest.getRequestURI();
        String method = httpRequest.getMethod();
        String normalizedUri = normalizePath(requestURI);
        String clientIp = IpUtils.getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        long startTime = System.currentTimeMillis();

        try {
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
            boolean rateLimitAllowed = gatewayMonitorService.checkRateLimit(clientIp, userId, requestURI);
            if (!rateLimitAllowed) {
                writeErrorResponse(httpResponse, Result.error(ResultEnum.SYSTEM_BUSY, null));
                recordRequestEnd(requestId, 429, 0, System.currentTimeMillis() - startTime, "Rate limit exceeded");
                return;
            }

            // 检查是否为排除路径
            if (isExcludePath(normalizedUri)) {
                log.debug("请求路径 {} 在排除列表中，跳过鉴权", requestURI);

                ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(httpResponse);
                chain.doFilter(request, responseWrapper);

                recordRequestEnd(requestId, responseWrapper.getStatus(),
                        responseWrapper.getContentSize(),
                        System.currentTimeMillis() - startTime, null);

                responseWrapper.copyBodyToResponse();
                return;
            }

            // 执行Token验证
            validateToken(httpRequest);

            // 执行权限验证（按路径与方法细分）
            validatePermission(normalizedUri, method);

            ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(httpResponse);
            chain.doFilter(request, responseWrapper);

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
     * 根据业务错误码映射为标准HTTP状态码。
     */
    private void writeErrorResponse(HttpServletResponse response, Result<?> result) throws IOException {
        response.setStatus(getHttpStatusFromResult(result));
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSONUtil.toJsonStr(result));
    }

    /**
     * 记录请求结束
     * 将请求的收尾指标写入监控服务中。
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
     * 支持通配符后缀 /** 的前缀匹配与精确匹配。
     */
    private boolean isExcludePath(String requestURI) {
        List<String> excludePaths = authWhitelistProperties.getAllPublicPatterns();
        return excludePaths.stream().anyMatch(excludePath -> {
            if (excludePath.endsWith("/**")) {
                String prefix = excludePath.substring(0, excludePath.length() - 3);
                return requestURI.startsWith(prefix);
            }
            return requestURI.equals(excludePath);
        });
    }

    private String normalizePath(String requestURI) {
        String normalized = requestURI;
        if (PREFIX != null && !PREFIX.isEmpty()) {
            while (normalized.startsWith(PREFIX)) {
                normalized = normalized.substring(PREFIX.length());
            }
        }
        final String defaultContext = "/identity";
        if (normalized.startsWith(defaultContext)) {
            normalized = normalized.substring(defaultContext.length());
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized.isEmpty() ? "/" : normalized;
    }

    /**
     * 验证Token
     * 支持从Authorization Bearer或参数satoken读取，并校验黑名单。
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
     * 简要按路径进行角色/权限校验，复杂细粒度控制可在Controller层实现。
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

        // OAuth和SSO接口需要登录即可（由具体端点内再做细化逻辑）
        if (requestURI.startsWith("/oauth") || requestURI.startsWith("/api/sso")) {
            return;
        }

        switch (method.toUpperCase()) {
            case "GET":
                StpUtil.checkPermissionOr("read", "admin");
                break;
            case "POST":
            case "PUT":
            case "PATCH":
                StpUtil.checkPermissionOr("write", "admin");
                break;
            case "DELETE":
                StpUtil.checkPermissionOr("delete", "admin");
                break;
            default:
                StpUtil.checkPermissionOr("basic", "admin");
                break;
        }
    }

    /**
     * 将业务错误码映射为HTTP状态码
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
