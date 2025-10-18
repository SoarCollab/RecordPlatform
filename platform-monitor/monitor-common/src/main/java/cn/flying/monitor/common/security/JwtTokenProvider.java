package cn.flying.monitor.common.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

/**
 * JWT令牌提供者 - 增强版本支持MFA
 */
@Slf4j
@Component
public class JwtTokenProvider {

    @Value("${monitor.jwt.secret:monitor-secret-key-2024}")
    private String jwtSecret;

    @Value("${monitor.jwt.expiration:3600}")
    private long jwtExpirationInSeconds;

    @Value("${monitor.jwt.refresh-expiration:86400}")
    private long refreshExpirationInSeconds;

    private Algorithm getAlgorithm() {
        return Algorithm.HMAC256(jwtSecret);
    }

    /**
     * 生成访问令牌
     */
    public String generateAccessToken(String userId, String username, List<String> roles, boolean mfaVerified) {
        Instant now = Instant.now();
        Instant expiry = now.plus(jwtExpirationInSeconds, ChronoUnit.SECONDS);

        return JWT.create()
                .withSubject(userId)
                .withClaim("username", username)
                .withClaim("roles", roles)
                .withClaim("mfa_verified", mfaVerified)
                .withClaim("mfa_required", false) // Will be set based on user settings
                .withClaim("token_type", "access")
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(expiry))
                .withIssuer("monitor-system")
                .sign(getAlgorithm());
    }
    
    /**
     * 生成访问令牌 - 增强版本支持MFA要求检查
     */
    public String generateAccessToken(String userId, String username, List<String> roles, boolean mfaVerified, boolean mfaRequired) {
        Instant now = Instant.now();
        Instant expiry = now.plus(jwtExpirationInSeconds, ChronoUnit.SECONDS);

        return JWT.create()
                .withSubject(userId)
                .withClaim("username", username)
                .withClaim("roles", roles)
                .withClaim("mfa_verified", mfaVerified)
                .withClaim("mfa_required", mfaRequired)
                .withClaim("token_type", "access")
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(expiry))
                .withIssuer("monitor-system")
                .sign(getAlgorithm());
    }
    
    /**
     * 生成预认证令牌（MFA验证前）
     */
    public String generatePreAuthToken(String userId, String username) {
        Instant now = Instant.now();
        Instant expiry = now.plus(600, ChronoUnit.SECONDS); // 10 minutes for MFA completion

        return JWT.create()
                .withSubject(userId)
                .withClaim("username", username)
                .withClaim("mfa_verified", false)
                .withClaim("mfa_required", true)
                .withClaim("token_type", "pre_auth")
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(expiry))
                .withIssuer("monitor-system")
                .sign(getAlgorithm());
    }

    /**
     * 生成刷新令牌
     */
    public String generateRefreshToken(String userId) {
        Instant now = Instant.now();
        Instant expiry = now.plus(refreshExpirationInSeconds, ChronoUnit.SECONDS);

        return JWT.create()
                .withSubject(userId)
                .withClaim("token_type", "refresh")
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(expiry))
                .withIssuer("monitor-system")
                .sign(getAlgorithm());
    }

    /**
     * 验证令牌
     */
    public boolean validateToken(String token) {
        try {
            JWT.require(getAlgorithm())
                    .withIssuer("monitor-system")
                    .build()
                    .verify(token);
            return true;
        } catch (JWTVerificationException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 解析令牌
     */
    public DecodedJWT parseToken(String token) {
        return JWT.require(getAlgorithm())
                .withIssuer("monitor-system")
                .build()
                .verify(token);
    }

    /**
     * 获取用户ID
     */
    public String getUserId(String token) {
        return parseToken(token).getSubject();
    }

    /**
     * 获取用户名
     */
    public String getUsername(String token) {
        return parseToken(token).getClaim("username").asString();
    }

    /**
     * 获取角色列表
     */
    public List<String> getRoles(String token) {
        return parseToken(token).getClaim("roles").asList(String.class);
    }

    /**
     * 检查MFA是否已验证
     */
    public boolean isMfaVerified(String token) {
        Boolean mfaVerified = parseToken(token).getClaim("mfa_verified").asBoolean();
        return mfaVerified != null && mfaVerified;
    }

    /**
     * 检查是否为访问令牌
     */
    public boolean isAccessToken(String token) {
        String tokenType = parseToken(token).getClaim("token_type").asString();
        return "access".equals(tokenType);
    }

    /**
     * 检查是否为刷新令牌
     */
    public boolean isRefreshToken(String token) {
        String tokenType = parseToken(token).getClaim("token_type").asString();
        return "refresh".equals(tokenType);
    }
    
    /**
     * 检查是否为预认证令牌
     */
    public boolean isPreAuthToken(String token) {
        String tokenType = parseToken(token).getClaim("token_type").asString();
        return "pre_auth".equals(tokenType);
    }
    
    /**
     * 检查是否需要MFA验证
     */
    public boolean isMfaRequired(String token) {
        Boolean mfaRequired = parseToken(token).getClaim("mfa_required").asBoolean();
        return mfaRequired != null && mfaRequired;
    }
    
    /**
     * 检查令牌是否完全认证（包括MFA）
     */
    public boolean isFullyAuthenticated(String token) {
        DecodedJWT jwt = parseToken(token);
        Boolean mfaRequired = jwt.getClaim("mfa_required").asBoolean();
        Boolean mfaVerified = jwt.getClaim("mfa_verified").asBoolean();
        
        // If MFA is not required, just check if it's an access token
        if (mfaRequired == null || !mfaRequired) {
            return isAccessToken(token);
        }
        
        // If MFA is required, it must also be verified
        return isAccessToken(token) && mfaVerified != null && mfaVerified;
    }
}