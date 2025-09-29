package cn.flying.service;

import cn.flying.common.util.CacheUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.util.Arrays;
import java.util.UUID;

/**
 * Token存储服务
 * 使用Redis Session管理用户的OAuth2 Token
 * 支持Session-Cookie方式，对前端完全透明
 *
 * @author Claude Code
 * @since 2025-01-16
 */
@Slf4j
@Service
public class TokenStorageService {

    private static final String SESSION_PREFIX = "sso:session:";
    private static final String TOKEN_SESSION_COOKIE = "SSO_SESSION_ID";

    @Resource
    private CacheUtils cacheUtils;

    @Value("${sso.session.timeout:7200}")
    private long sessionTimeout; // 默认2小时

    @Value("${sso.session.cookie.secure:false}")
    private boolean secureCookie; // 生产环境应设为true（HTTPS）

    @Value("${sso.session.cookie.http-only:true}")
    private boolean httpOnlyCookie; // 防止XSS攻击

    @Value("${sso.session.cookie.same-site:Lax}")
    private String sameSite; // CSRF防护

    /**
     * 存储用户Token到Session
     * 生成session ID并存储到Cookie，token信息存储到Redis
     *
     * @param response     HTTP响应
     * @param accessToken  访问令牌
     * @param refreshToken 刷新令牌
     * @param expiresIn    过期时间（秒）
     * @param scope        授权范围
     * @return Session ID
     */
    public String storeToken(HttpServletResponse response, String accessToken,
                             String refreshToken, int expiresIn, String scope) {
        try {
            // 生成session ID
            String sessionId = generateSessionId();
            String sessionKey = SESSION_PREFIX + sessionId;

            // 构建token信息
            TokenInfo tokenInfo = new TokenInfo();
            tokenInfo.setAccessToken(accessToken);
            tokenInfo.setRefreshToken(refreshToken);
            tokenInfo.setExpiresIn(expiresIn);
            tokenInfo.setScope(scope);
            tokenInfo.setCreateTime(System.currentTimeMillis());

            // 存储到Redis
            cacheUtils.saveToCache(sessionKey, tokenInfo, sessionTimeout);

            // 设置Cookie
            Cookie cookie = new Cookie(TOKEN_SESSION_COOKIE, sessionId);
            cookie.setPath("/");
            cookie.setMaxAge((int) sessionTimeout);
            cookie.setHttpOnly(httpOnlyCookie);
            cookie.setSecure(secureCookie);
            // SameSite属性需要通过Set-Cookie头设置
            response.addHeader("Set-Cookie", buildSetCookieHeader(cookie));

            log.info("存储用户Token成功: sessionId={}, expiresIn={}", sessionId, expiresIn);
            return sessionId;

        } catch (Exception e) {
            log.error("存储Token失败", e);
            return null;
        }
    }

    /**
     * 生成Session ID
     */
    private String generateSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 构建Set-Cookie头（包含SameSite属性）
     */
    private String buildSetCookieHeader(Cookie cookie) {
        StringBuilder sb = new StringBuilder();
        sb.append(cookie.getName()).append("=").append(cookie.getValue());
        sb.append("; Path=").append(cookie.getPath());
        sb.append("; Max-Age=").append(cookie.getMaxAge());

        if (cookie.isHttpOnly()) {
            sb.append("; HttpOnly");
        }

        if (cookie.getSecure()) {
            sb.append("; Secure");
        }

        if (sameSite != null && !sameSite.isEmpty()) {
            sb.append("; SameSite=").append(sameSite);
        }

        return sb.toString();
    }

    /**
     * 从请求中获取Token信息
     *
     * @param request HTTP请求
     * @return Token信息，如果不存在或已过期返回null
     */
    public TokenInfo getToken(HttpServletRequest request) {
        try {
            // 从Cookie中获取session ID
            String sessionId = getSessionIdFromCookie(request);
            if (sessionId == null) {
                log.debug("未找到session cookie");
                return null;
            }

            // 从Redis中获取token信息
            String sessionKey = SESSION_PREFIX + sessionId;
            TokenInfo tokenInfo = cacheUtils.takeFormCache(sessionKey, TokenInfo.class);

            if (tokenInfo == null) {
                log.debug("Session不存在或已过期: sessionId={}", sessionId);
                return null;
            }

            // 检查token是否过期
            long currentTime = System.currentTimeMillis();
            long tokenAge = (currentTime - tokenInfo.getCreateTime()) / 1000;

            if (tokenAge >= tokenInfo.getExpiresIn()) {
                log.info("Token已过期: sessionId={}, age={}s, expiresIn={}s",
                        sessionId, tokenAge, tokenInfo.getExpiresIn());
                // 不立即删除，等待刷新token或自然过期
                tokenInfo.setExpired(true);
            }

            return tokenInfo;

        } catch (Exception e) {
            log.error("获取Token失败", e);
            return null;
        }
    }

    /**
     * 从Cookie中获取Session ID
     */
    private String getSessionIdFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        return Arrays.stream(cookies)
                .filter(cookie -> TOKEN_SESSION_COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    /**
     * 更新Token信息
     * 用于刷新token后更新session中的token
     *
     * @param request      HTTP请求
     * @param accessToken  新的访问令牌
     * @param refreshToken 新的刷新令牌
     * @param expiresIn    过期时间
     * @return 是否成功
     */
    public boolean updateToken(HttpServletRequest request, String accessToken,
                               String refreshToken, int expiresIn) {
        try {
            String sessionId = getSessionIdFromCookie(request);
            if (sessionId == null) {
                log.warn("无法更新Token：session ID不存在");
                return false;
            }

            String sessionKey = SESSION_PREFIX + sessionId;
            TokenInfo tokenInfo = cacheUtils.takeFormCache(sessionKey, TokenInfo.class);

            if (tokenInfo == null) {
                log.warn("无法更新Token：session不存在");
                return false;
            }

            // 更新token信息
            tokenInfo.setAccessToken(accessToken);
            tokenInfo.setRefreshToken(refreshToken);
            tokenInfo.setExpiresIn(expiresIn);
            tokenInfo.setCreateTime(System.currentTimeMillis());
            tokenInfo.setExpired(false);

            // 保存到Redis
            cacheUtils.saveToCache(sessionKey, tokenInfo, sessionTimeout);

            log.info("更新Token成功: sessionId={}", sessionId);
            return true;

        } catch (Exception e) {
            log.error("更新Token失败", e);
            return false;
        }
    }

    /**
     * 清除Token
     * 用户登出时调用
     *
     * @param request  HTTP请求
     * @param response HTTP响应
     * @return 是否成功
     */
    public boolean clearToken(HttpServletRequest request, HttpServletResponse response) {
        try {
            String sessionId = getSessionIdFromCookie(request);
            if (sessionId == null) {
                log.debug("无session可清除");
                return true;
            }

            // 删除Redis中的session
            String sessionKey = SESSION_PREFIX + sessionId;
            cacheUtils.deleteCache(sessionKey);

            // 删除Cookie
            Cookie cookie = new Cookie(TOKEN_SESSION_COOKIE, "");
            cookie.setPath("/");
            cookie.setMaxAge(0);
            cookie.setHttpOnly(httpOnlyCookie);
            response.addCookie(cookie);

            log.info("清除Token成功: sessionId={}", sessionId);
            return true;

        } catch (Exception e) {
            log.error("清除Token失败", e);
            return false;
        }
    }

    /**
     * 延长Session有效期
     *
     * @param request HTTP请求
     * @return 是否成功
     */
    public boolean extendSession(HttpServletRequest request) {
        try {
            String sessionId = getSessionIdFromCookie(request);
            if (sessionId == null) {
                return false;
            }

            String sessionKey = SESSION_PREFIX + sessionId;
            TokenInfo tokenInfo = cacheUtils.takeFormCache(sessionKey, TokenInfo.class);

            if (tokenInfo != null) {
                // 重新设置过期时间
                cacheUtils.saveToCache(sessionKey, tokenInfo, sessionTimeout);
                log.debug("延长Session有效期: sessionId={}", sessionId);
                return true;
            }

            return false;

        } catch (Exception e) {
            log.error("延长Session失败", e);
            return false;
        }
    }

    /**
     * Token信息数据类
     */
    @Data
    public static class TokenInfo implements Serializable {
        private String accessToken;
        private String refreshToken;
        private int expiresIn;
        private String scope;
        private long createTime;
        private boolean expired;

        /**
         * 是否需要刷新
         * 当剩余时间少于1/3时建议刷新
         */
        public boolean shouldRefresh() {
            return getRemainingTime() < (expiresIn / 3);
        }

        /**
         * 获取剩余有效时间（秒）
         */
        public long getRemainingTime() {
            long elapsed = (System.currentTimeMillis() - createTime) / 1000;
            return Math.max(0, expiresIn - elapsed);
        }
    }
}
