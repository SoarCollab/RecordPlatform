package cn.flying.listener;

import cn.flying.common.util.Const;
import cn.flying.common.util.JsonConverter;
import cn.flying.dao.dto.Account;
import cn.flying.dao.dto.SysOperationLog;
import cn.flying.service.AccountService;
import cn.flying.service.SysOperationLogService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authentication.event.LogoutSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证事件监听器，统一记录登录与退出日志。
 */
@Component
@Slf4j
public class AuthEventListener {

    private static final String LOGIN_URI = "/api/v1/auth/login";
    private static final String LOGOUT_URI = "/api/v1/auth/logout";

    @Resource
    private SysOperationLogService operationLogService;

    @Resource
    private AccountService accountService;

    /**
     * 监听登录成功事件并写入操作日志。
     */
    @EventListener
    public void onAuthenticationSuccess(AuthenticationSuccessEvent event) {
        HttpServletRequest request = currentRequest();
        if (!isTargetEndpoint(request, LOGIN_URI)) {
            return;
        }

        try {
            Authentication authentication = event.getAuthentication();
            String username = resolveUsername(authentication);
            String userId = resolveUserId(username, request);

            SysOperationLog log = buildOperationLog(
                    request,
                    "登录校验模块",
                    "登录",
                    "用户登录",
                    "AuthEventListener.onAuthenticationSuccess"
            );
            log.setUsername(username != null ? username : "未登录");
            log.setUserId(userId);
            log.setRequestParam(buildAuthRequestParams(username, request));

            operationLogService.saveOperationLog(log);
        } catch (Exception e) {
            log.warn("记录登录操作日志失败", e);
        }
    }

    /**
     * 监听退出成功事件并写入操作日志。
     */
    @EventListener
    public void onLogoutSuccess(LogoutSuccessEvent event) {
        HttpServletRequest request = currentRequest();
        if (!isTargetEndpoint(request, LOGOUT_URI)) {
            return;
        }

        try {
            Authentication authentication = event.getAuthentication();
            String username = resolveUsername(authentication);
            String userId = resolveUserId(username, request);

            SysOperationLog log = buildOperationLog(
                    request,
                    "登录校验模块",
                    "退出",
                    "用户退出登录",
                    "AuthEventListener.onLogoutSuccess"
            );
            log.setUsername(username != null ? username : "未登录");
            log.setUserId(userId);
            log.setRequestParam(buildAuthRequestParams(username, request));

            operationLogService.saveOperationLog(log);
        } catch (Exception e) {
            log.warn("记录退出操作日志失败", e);
        }
    }

    /**
     * 构建基础操作日志对象并填充请求信息。
     */
    private SysOperationLog buildOperationLog(HttpServletRequest request,
                                              String module,
                                              String operationType,
                                              String description,
                                              String methodName) {
        SysOperationLog log = new SysOperationLog();
        log.setModule(module);
        log.setOperationType(operationType);
        log.setDescription(description);
        log.setMethod(methodName);
        log.setStatus(0);
        log.setOperationTime(LocalDateTime.now());
        log.setExecutionTime(0L);

        if (request != null) {
            log.setRequestUrl(request.getRequestURI());
            log.setRequestMethod(request.getMethod());
            log.setRequestIp(getClientIp(request));
        }

        return log;
    }

    /**
     * 解析认证对象中的用户名，忽略匿名认证。
     */
    private String resolveUsername(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        if (authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof User user) {
            return user.getUsername();
        }
        return principal != null ? principal.toString() : null;
    }

    /**
     * 解析用户ID，优先取请求属性，若无则按用户名查询。
     */
    private String resolveUserId(String username, HttpServletRequest request) {
        if (request != null && request.getAttribute(Const.ATTR_USER_ID) != null) {
            return String.valueOf(request.getAttribute(Const.ATTR_USER_ID));
        }
        if (username == null || username.isBlank()) {
            return null;
        }
        Account account = accountService.findAccountByNameOrEmail(username);
        return account != null && account.getId() != null ? String.valueOf(account.getId()) : null;
    }

    /**
     * 获取当前线程绑定的 HTTP 请求对象。
     */
    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes != null ? attributes.getRequest() : null;
    }

    /**
     * 判断是否为目标认证端点请求，避免记录无关事件。
     */
    private boolean isTargetEndpoint(HttpServletRequest request, String targetUri) {
        if (request == null) {
            return false;
        }
        String requestUri = request.getRequestURI();
        return requestUri != null && requestUri.startsWith(targetUri);
    }

    /**
     * 构建认证相关的请求参数日志内容。
     */
    private String buildAuthRequestParams(String username, HttpServletRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (username != null && !username.isBlank()) {
            params.put("username", username);
        }
        if (request != null && request.getAttribute(Const.ATTR_TENANT_ID) != null) {
            params.put("tenantId", request.getAttribute(Const.ATTR_TENANT_ID));
        }
        return params.isEmpty() ? null : JsonConverter.toJsonWithPretty(params);
    }

    /**
     * 获取客户端IP地址，兼容反向代理场景。
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
