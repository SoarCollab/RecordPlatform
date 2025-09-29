package cn.flying.filter;

import cn.flying.service.TokenStorageService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * SSO认证入口点
 * 当用户未认证时，自动重定向到SSO登录页面
 * 这是Spring Security认证流程的入口
 *
 * @author Claude Code
 * @since 2025-01-16
 */
@Slf4j
@Component
public class SSOAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Resource
    private TokenStorageService tokenStorageService;

    /**
     * 处理未认证的请求
     * 判断请求类型并相应处理：
     * - API请求：返回401 JSON响应
     * - 页面请求：重定向到SSO登录页面
     *
     * @param request       HTTP请求
     * @param response      HTTP响应
     * @param authException 认证异常
     * @throws IOException IO异常
     */
    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        String requestUri = request.getRequestURI();
        log.debug("未认证请求: uri={}, method={}", requestUri, request.getMethod());

        // 检查是否已有有效的SSO Session
        TokenStorageService.TokenInfo tokenInfo = tokenStorageService.getToken(request);
        if (tokenInfo != null && !tokenInfo.isExpired()) {
            // 已有有效token，可能是权限不足而非未认证
            log.warn("已认证但被拒绝访问: uri={}, userId可通过token获取", requestUri);
            sendAccessDeniedResponse(response, requestUri);
            return;
        }

        // 判断是API请求还是页面请求
        boolean isApiRequest = isApiRequest(requestUri);

        if (isApiRequest) {
            // API请求：返回401 JSON响应
            sendUnauthorizedJsonResponse(response, requestUri);
        } else {
            // 页面请求：重定向到SSO登录页面
            redirectToSSOLogin(request, response, requestUri);
        }
    }

    /**
     * 判断是否为API请求
     * 根据请求路径判断
     *
     * @param requestUri 请求URI
     * @return 是否为API请求
     */
    private boolean isApiRequest(String requestUri) {
        // API请求通常以/api开头，或者是RESTful风格的路径
        return requestUri.startsWith("/api/") ||
               requestUri.startsWith("/record-platform/api/") ||
               requestUri.contains("/rest/") ||
               requestUri.endsWith(".json");
    }

    /**
     * 发送401 JSON响应
     * 用于API请求的未认证响应
     *
     * @param response   HTTP响应
     * @param requestUri 请求URI
     * @throws IOException IO异常
     */
    private void sendUnauthorizedJsonResponse(HttpServletResponse response, String requestUri)
            throws IOException {

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");

        // 构建标准的Result格式响应
        String jsonResponse = String.format(
                "{\"code\":0,\"data\":null,\"message\":\"未登录或登录已过期，请重新登录\",\"loginRequired\":true,\"loginUrl\":\"/api/oauth2/login\"}",
                requestUri
        );

        response.getWriter().write(jsonResponse);
        response.getWriter().flush();

        log.info("返回401响应: uri={}", requestUri);
    }

    /**
     * 重定向到SSO登录页面
     * 用于页面请求的未认证处理
     *
     * @param request    HTTP请求
     * @param response   HTTP响应
     * @param requestUri 请求URI
     * @throws IOException IO异常
     */
    private void redirectToSSOLogin(HttpServletRequest request,
                                    HttpServletResponse response,
                                    String requestUri) throws IOException {

        // 构建完整的原始请求URL（包含query string）
        String fullUrl = buildFullRequestUrl(request);

        // 将原始URL作为returnUrl参数传递给登录接口
        String loginUrl = "/api/oauth2/login?returnUrl=" +
                URLEncoder.encode(fullUrl, StandardCharsets.UTF_8);

        log.info("重定向到SSO登录: originalUrl={}, loginUrl={}", fullUrl, loginUrl);

        // 执行重定向
        response.sendRedirect(loginUrl);
    }

    /**
     * 构建完整的请求URL（包含query string）
     *
     * @param request HTTP请求
     * @return 完整URL
     */
    private String buildFullRequestUrl(HttpServletRequest request) {
        StringBuilder url = new StringBuilder(request.getRequestURI());

        String queryString = request.getQueryString();
        if (queryString != null && !queryString.isEmpty()) {
            url.append("?").append(queryString);
        }

        return url.toString();
    }

    /**
     * 发送403访问拒绝响应
     * 用于已认证但权限不足的情况
     *
     * @param response   HTTP响应
     * @param requestUri 请求URI
     * @throws IOException IO异常
     */
    private void sendAccessDeniedResponse(HttpServletResponse response, String requestUri)
            throws IOException {

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");

        String jsonResponse = String.format(
                "{\"code\":0,\"data\":null,\"message\":\"权限不足，无法访问该资源\"}",
                requestUri
        );

        response.getWriter().write(jsonResponse);
        response.getWriter().flush();

        log.warn("返回403响应: uri={}", requestUri);
    }
}
