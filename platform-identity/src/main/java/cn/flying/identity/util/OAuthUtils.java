package cn.flying.identity.util;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.digest.DigestUtil;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Pattern;

/**
 * OAuth2.0 工具类
 * 提供OAuth2.0协议实现所需的各种工具方法
 * 包括令牌生成、URI验证、范围检查、响应构建等功能
 * 遵循RFC 6749 OAuth2.0规范标准
 *
 * @author flying
 * @date 2024
 */
public class OAuthUtils {

    /**
     * 生成授权码
     *
     * @return 授权码
     */
    public static String generateAuthCode() {
        return "AC_" + RandomUtil.randomString(32);
    }

    /**
     * 生成访问令牌
     *
     * @return 访问令牌
     */
    public static String generateAccessToken() {
        return "AT_" + RandomUtil.randomString(64);
    }

    /**
     * 生成刷新令牌
     *
     * @return 刷新令牌
     */
    public static String generateRefreshToken() {
        return "RT_" + RandomUtil.randomString(64);
    }

    /**
     * 生成客户端令牌
     *
     * @return 客户端令牌
     */
    public static String generateClientToken() {
        return "CT_" + RandomUtil.randomString(64);
    }

    /**
     * 生成状态参数
     *
     * @return 状态参数
     */
    public static String generateState() {
        return RandomUtil.randomString(16);
    }

    /**
     * 验证重定向URI是否合法
     *
     * @param redirectUri    重定向URI
     * @param registeredUris 注册的重定向URI列表
     * @return 是否合法
     */
    public static boolean validateRedirectUri(String redirectUri, String registeredUris) {
        return validateGrantType(redirectUri, registeredUris);
    }

    /**
     * 验证授权类型是否合法
     *
     * @param grantType      授权类型
     * @param supportedTypes 支持的授权类型
     * @return 是否合法
     */
    public static boolean validateGrantType(String grantType, String supportedTypes) {
        if (!StringUtils.hasText(grantType) || !StringUtils.hasText(supportedTypes)) {
            return false;
        }

        String[] types = supportedTypes.split(",");
        for (String type : types) {
            if (grantType.trim().equals(type.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 验证授权范围是否合法
     *
     * @param requestScope 请求的授权范围
     * @param clientScope  客户端支持的授权范围
     * @return 是否合法
     */
    public static boolean validateScope(String requestScope, String clientScope) {
        if (!StringUtils.hasText(requestScope)) {
            return true; // 空范围默认合法
        }

        if (!StringUtils.hasText(clientScope)) {
            return false;
        }

        Set<String> clientScopes = new HashSet<>(Arrays.asList(clientScope.split(",")));
        String[] requestScopes = requestScope.split(",");

        for (String scope : requestScopes) {
            if (!clientScopes.contains(scope.trim())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 构建授权响应URL
     *
     * @param redirectUri 重定向URI
     * @param code        授权码
     * @param state       状态参数
     * @return 授权响应URL
     */
    public static String buildAuthResponseUrl(String redirectUri, String code, String state) {
        StringBuilder url = new StringBuilder(redirectUri);
        url.append(redirectUri.contains("?") ? "&" : "?");
        url.append("code=").append(code);

        if (StringUtils.hasText(state)) {
            url.append("&state=").append(state);
        }

        return url.toString();
    }

    /**
     * 构建错误响应URL
     *
     * @param redirectUri      重定向URI
     * @param error            错误代码
     * @param errorDescription 错误描述
     * @param state            状态参数
     * @return 错误响应URL
     */
    public static String buildErrorResponseUrl(String redirectUri, String error,
                                               String errorDescription, String state) {
        StringBuilder url = new StringBuilder(redirectUri);
        url.append(redirectUri.contains("?") ? "&" : "?");
        url.append("error=").append(error);

        if (StringUtils.hasText(errorDescription)) {
            url.append("&error_description=").append(errorDescription);
        }

        if (StringUtils.hasText(state)) {
            url.append("&state=").append(state);
        }

        return url.toString();
    }

    /**
     * 构建令牌响应
     *
     * @param accessToken  访问令牌
     * @param refreshToken 刷新令牌
     * @param expiresIn    过期时间（秒）
     * @param scope        授权范围
     * @return 令牌响应Map
     */
    public static Map<String, Object> buildTokenResponse(String accessToken, String refreshToken,
                                                         int expiresIn, String scope) {
        Map<String, Object> response = new HashMap<>();
        response.put("access_token", accessToken);
        response.put("token_type", "Bearer");
        response.put("expires_in", expiresIn);

        if (StringUtils.hasText(refreshToken)) {
            response.put("refresh_token", refreshToken);
        }

        if (StringUtils.hasText(scope)) {
            response.put("scope", scope);
        }

        return response;
    }

    /**
     * 解析Bearer令牌
     *
     * @param authorization Authorization头
     * @return 令牌值
     */
    public static String parseBearerToken(String authorization) {
        if (!StringUtils.hasText(authorization)) {
            return null;
        }

        if (authorization.startsWith("Bearer ")) {
            return authorization.substring(7);
        }

        return null;
    }

    /**
     * 计算过期时间戳
     *
     * @param timeoutSeconds 超时时间（秒）
     * @return 过期时间戳
     */
    public static long calculateExpiresAt(int timeoutSeconds) {
        return LocalDateTime.now().plusSeconds(timeoutSeconds)
                .toEpochSecond(ZoneOffset.of("+8"));
    }

    /**
     * 检查是否过期
     *
     * @param expiresAt 过期时间戳
     * @return 是否过期
     */
    public static boolean isExpired(long expiresAt) {
        return LocalDateTime.now().toEpochSecond(ZoneOffset.of("+8")) > expiresAt;
    }

    /**
     * 验证客户端密钥
     *
     * @param clientSecret 客户端密钥
     * @param hashedSecret 哈希后的密钥
     * @return 是否匹配
     */
    public static boolean verifyClientSecret(String clientSecret, String hashedSecret) {
        return hashClientSecret(clientSecret).equals(hashedSecret);
    }

    /**
     * 生成客户端密钥哈希
     *
     * @param clientSecret 客户端密钥
     * @return 哈希值
     */
    public static String hashClientSecret(String clientSecret) {
        return DigestUtil.sha256Hex(clientSecret);
    }

    /**
     * 格式化授权范围
     *
     * @param scope 授权范围
     * @return 格式化后的授权范围
     */
    public static String formatScope(String scope) {
        if (!StringUtils.hasText(scope)) {
            return "read";
        }

        // 去重并排序
        Set<String> scopes = new TreeSet<>(Arrays.asList(scope.split(",")));
        return String.join(",", scopes);
    }

    /**
     * 验证URL格式
     *
     * @param url URL字符串
     * @return 是否为合法URL
     */
    public static boolean isValidUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return false;
        }

        String urlPattern = "^(https?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]";
        return Pattern.matches(urlPattern, url);
    }
}