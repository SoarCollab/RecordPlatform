package cn.flying.monitor.server.handler;

import cn.flying.monitor.server.entity.dto.Account;
import cn.flying.monitor.server.service.AccountService;
import cn.flying.monitor.server.service.OAuthTokenService;
import jakarta.annotation.Resource;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * OAuth2登录成功处理器
 * 处理通过platform-identity OAuth2单点登录成功后的逻辑
 */
@Slf4j
@Component
public class OAuth2LoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    @Resource
    private AccountService accountService;

    @Resource
    private OAuthTokenService oAuthTokenService;

    /**
     * 处理OAuth2认证成功事件
     *
     * @param request        HttpServletRequest对象
     * @param response       HttpServletResponse对象
     * @param authentication 认证信息（OAuth2AuthenticationToken）
     * @throws IOException      IO异常
     * @throws ServletException Servlet异常
     */
    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {

        // 验证authentication类型
        if (!(authentication instanceof OAuth2AuthenticationToken oauthToken)) {
            log.warn("认证类型不是OAuth2AuthenticationToken: {}", authentication.getClass().getName());
            response.sendRedirect("/login?error=invalid_auth_type");
            return;
        }

        OAuth2User oAuth2User = oauthToken.getPrincipal();
        String provider = oauthToken.getAuthorizedClientRegistrationId();

        // 从OAuth2用户信息中提取属性
        Map<String, Object> attributes = oAuth2User.getAttributes();
        String username = extractUsername(attributes);
        String email = extractEmail(attributes);
        Long oauthUserId = extractOAuthUserId(attributes);

        if (username == null || oauthUserId == null) {
            log.error("无法从OAuth2用户信息中提取必要字段: username={}, oauthUserId={}",
                    username, oauthUserId);
            response.sendRedirect("/login?error=missing_user_info");
            return;
        }

        log.info("OAuth2登录成功 - provider={}, username={}, oauthUserId={}",
                provider, username, oauthUserId);

        try {
            // 查找或创建本地用户
            Account account = accountService.findOrCreateOAuthUser(
                    provider, oauthUserId, username, email);

            log.info("本地用户处理完成 - accountId={}, username={}", account.getId(), account.getUsername());

            // 保存OAuth token信息到Redis
            oAuthTokenService.saveTokenInfo(account.getId(), oauthToken);

            // 在session中保存用户ID（用于后续请求识别用户）
            request.getSession().setAttribute("userId", account.getId());
            request.getSession().setAttribute("username", account.getUsername());
            request.getSession().setAttribute("authType", "oauth");

            log.info("OAuth2登录流程完成，重定向到首页 - userId={}", account.getId());

            // 重定向到首页或原始请求的页面
            super.onAuthenticationSuccess(request, response, authentication);

        } catch (Exception e) {
            log.error("处理OAuth2登录成功事件时发生错误", e);
            response.sendRedirect("/login?error=internal_error");
        }
    }

    /**
     * 从OAuth2用户属性中提取用户名
     *
     * @param attributes OAuth2用户属性
     * @return 用户名，如果不存在返回null
     */
    private String extractUsername(Map<String, Object> attributes) {
        // 尝试多种可能的用户名字段
        if (attributes.containsKey("username")) {
            return (String) attributes.get("username");
        }
        if (attributes.containsKey("name")) {
            return (String) attributes.get("name");
        }
        if (attributes.containsKey("preferred_username")) {
            return (String) attributes.get("preferred_username");
        }
        return null;
    }

    /**
     * 从OAuth2用户属性中提取邮箱
     *
     * @param attributes OAuth2用户属性
     * @return 邮箱地址，如果不存在返回null
     */
    private String extractEmail(Map<String, Object> attributes) {
        if (attributes.containsKey("email")) {
            return (String) attributes.get("email");
        }
        return null;
    }

    /**
     * 从OAuth2用户属性中提取用户ID
     *
     * @param attributes OAuth2用户属性
     * @return 用户ID，如果不存在或无法解析返回null
     */
    private Long extractOAuthUserId(Map<String, Object> attributes) {
        Object idObj = attributes.get("id");
        switch (idObj) {
            case null -> {
                return null;
            }

            // 处理不同类型的ID
            case Long l -> {
                return l;
            }
            case Integer i -> {
                return i.longValue();
            }
            case String s -> {
                try {
                    return Long.parseLong(s);
                } catch (NumberFormatException e) {
                    log.warn("无法将用户ID转换为Long: {}", idObj);
                    return null;
                }
            }
            case Number number -> {
                return number.longValue();
            }
            default -> {
            }
        }

        log.warn("无法识别的用户ID类型: {}", idObj.getClass().getName());
        return null;
    }
}
