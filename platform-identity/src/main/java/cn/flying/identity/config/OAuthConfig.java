package cn.flying.identity.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import lombok.Getter;

/**
 * OAuth2.0 配置类
 * 定义OAuth相关的配置参数
 * 
 * @author flying
 * @date 2024
 */
@Configuration
@Getter
public class OAuthConfig {

    /**
     * 授权码有效期（秒）
     */
    @Value("${oauth.code.timeout:300}")
    private int codeTimeout;

    /**
     * 访问令牌有效期（秒）
     */
    @Value("${oauth.access-token.timeout:3600}")
    private int accessTokenTimeout;

    /**
     * 刷新令牌有效期（秒）
     */
    @Value("${oauth.refresh-token.timeout:86400}")
    private int refreshTokenTimeout;

    /**
     * 客户端令牌有效期（秒）
     */
    @Value("${oauth.client-token.timeout:7200}")
    private int clientTokenTimeout;

    /**
     * 默认授权范围
     */
    @Value("${oauth.default.scope:read}")
    private String defaultScope;

    /**
     * 是否启用刷新令牌
     */
    @Value("${oauth.refresh-token.enabled:true}")
    private boolean refreshTokenEnabled;

    /**
     * 是否启用客户端凭证模式
     */
    @Value("${oauth.client-credentials.enabled:true}")
    private boolean clientCredentialsEnabled;

    /**
     * Redis键前缀配置
     */
    public static class RedisPrefix {
        public static final String ACCESS_TOKEN = "oauth2:access_token:";
        public static final String REFRESH_TOKEN = "oauth2:refresh_token:";
        public static final String CLIENT_TOKEN = "oauth2:client_token:";
        public static final String AUTH_CODE = "oauth2:code:";
        public static final String USER_TOKEN = "oauth2:user_token:";
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
     * OAuth2.0 授权码状态常量
     */
    public static class CodeStatus {
        public static final int UNUSED = 0;
        public static final int USED = 1;
        public static final int EXPIRED = 2;
    }
}