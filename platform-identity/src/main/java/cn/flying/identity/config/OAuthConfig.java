package cn.flying.identity.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * OAuth2.0 配置类
 * 定义OAuth相关的配置参数，支持从配置文件动态读取
 * 提供OAuth2.0协议实现所需的各种超时时间、开关配置等
 *
 * @author flying
 * @date 2024
 */
@Configuration
@Getter
public class OAuthConfig {

    /**
     * 授权码有效期（秒）
     * 默认5分钟，符合OAuth2.0规范建议
     */
    @Value("${oauth.code.timeout:300}")
    private int codeTimeout;

    /**
     * 访问令牌有效期（秒）
     * 默认1小时
     */
    @Value("${oauth.access-token.timeout:3600}")
    private int accessTokenTimeout;

    /**
     * 刷新令牌有效期（秒）
     * 默认24小时
     */
    @Value("${oauth.refresh-token.timeout:86400}")
    private int refreshTokenTimeout;

    /**
     * 默认授权范围
     */
    @Value("${oauth.default.scope:read}")
    private String defaultScope;

    /**
     * 是否使用BCrypt加密客户端密钥
     */
    @Value("${oauth.security.use-bcrypt:false}")
    private boolean useBcrypt;

    /**
     * 是否要求状态参数验证（防CSRF攻击）
     */
    @Value("${oauth.security.require-state:true}")
    private boolean requireState;

    /**
     * Redis键前缀
     */
    @Value("${oauth.redis.key-prefix:oauth2:}")
    private String redisKeyPrefix;

    /**
     * 获取Redis键前缀配置
     * 动态生成Redis键前缀，支持配置文件自定义
     */
    public String getAccessTokenPrefix() {
        return redisKeyPrefix + "access_token:";
    }

    public String getRefreshTokenPrefix() {
        return redisKeyPrefix + "refresh_token:";
    }

    public String getClientTokenPrefix() {
        return redisKeyPrefix + "client_token:";
    }

    public String getAuthCodePrefix() {
        return redisKeyPrefix + "code:";
    }

    public String getUserTokenPrefix() {
        return redisKeyPrefix + "user_token:";
    }

    /**
     * OAuth2.0 授权类型常量
     */
    public static class GrantType {
        public static final String AUTHORIZATION_CODE = "authorization_code";
        public static final String REFRESH_TOKEN = "refresh_token";
        public static final String CLIENT_CREDENTIALS = "client_credentials";
        public static final String PASSWORD = "password";
        public static final String IMPLICIT = "implicit";
    }

    /**
     * OAuth2.0 响应类型常量
     */
    public static class ResponseType {
        public static final String CODE = "code";
        public static final String TOKEN = "token";
    }

    /**
     * OAuth2.0 令牌类型常量
     */
    public static class TokenType {
        public static final String BEARER = "Bearer";
        public static final String MAC = "MAC";
    }

    /**
     * OAuth2.0 客户端状态常量
     */
    public static class ClientStatus {
        public static final int DISABLED = 0;
        public static final int ENABLED = 1;
    }

    /**
     * OAuth2.0 授权码状态常量（与数据库一致）
     * 1=有效，0=已使用，-1=已过期
     */
    public static class CodeStatus {
        public static final int VALID = 1;
        public static final int USED = 0;
        public static final int EXPIRED = -1;
    }
}