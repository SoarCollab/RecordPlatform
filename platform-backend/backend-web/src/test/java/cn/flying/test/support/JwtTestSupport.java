package cn.flying.test.support;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * JWT test support utilities for generating test tokens in integration tests.
 * Key must match application-test.yml: spring.security.jwt.key
 */
public class JwtTestSupport {

    private static final String TEST_JWT_KEY = "ci-integration-jwt-key-32chars-xK9mN2pL5qR8vW3y";
    private static final String ISSUER = "record-platform";
    private static final String AUDIENCE = "record-platform-api";
    private static final Algorithm ALGORITHM = Algorithm.HMAC512(TEST_JWT_KEY);

    public static final Long DEFAULT_USER_ID = 100L;
    public static final Long DEFAULT_TENANT_ID = 1L;
    public static final String DEFAULT_USERNAME = "testuser";
    public static final String DEFAULT_ROLE = "user";

    public static String generateToken() {
        return generateToken(DEFAULT_USER_ID, DEFAULT_USERNAME, DEFAULT_ROLE, DEFAULT_TENANT_ID);
    }

    public static String generateToken(Long userId) {
        return generateToken(userId, DEFAULT_USERNAME + "_" + userId, DEFAULT_ROLE, DEFAULT_TENANT_ID);
    }

    public static String generateToken(Long userId, String username, String role) {
        return generateToken(userId, username, role, DEFAULT_TENANT_ID);
    }

    public static String generateToken(Long userId, String username, String role, Long tenantId) {
        return generateToken(userId, username, role, tenantId, 1);
    }

    public static String generateToken(Long userId, String username, String role, Long tenantId, int expireHours) {
        List<String> authorities = Arrays.asList("ROLE_" + role);
        return generateTokenWithAuthorities(userId, username, authorities, tenantId, expireHours);
    }

    public static String generateTokenWithAuthorities(Long userId, String username,
                                                      List<String> authorities, Long tenantId, int expireHours) {
        Date now = new Date();
        Date expire = new Date(now.getTime() + (expireHours * 60 * 60 * 1000L));

        return JWT.create()
                .withJWTId(UUID.randomUUID().toString())
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withClaim("id", userId)
                .withClaim("tenantId", tenantId)
                .withClaim("name", username)
                .withClaim("authorities", authorities)
                .withExpiresAt(expire)
                .withIssuedAt(now)
                .sign(ALGORITHM);
    }

    public static String generateExpiredToken(Long userId) {
        Date now = new Date();
        Date expiredAt = new Date(now.getTime() - 1000);

        return JWT.create()
                .withJWTId(UUID.randomUUID().toString())
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withClaim("id", userId)
                .withClaim("tenantId", DEFAULT_TENANT_ID)
                .withClaim("name", DEFAULT_USERNAME)
                .withClaim("authorities", Arrays.asList("ROLE_" + DEFAULT_ROLE))
                .withExpiresAt(expiredAt)
                .withIssuedAt(new Date(now.getTime() - 3600000))
                .sign(ALGORITHM);
    }

    public static String generateTokenWithoutTenant(Long userId, String username, String role) {
        Date now = new Date();
        Date expire = new Date(now.getTime() + (60 * 60 * 1000L));

        return JWT.create()
                .withJWTId(UUID.randomUUID().toString())
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withClaim("id", userId)
                .withClaim("name", username)
                .withClaim("authorities", Arrays.asList("ROLE_" + role))
                .withExpiresAt(expire)
                .withIssuedAt(now)
                .sign(ALGORITHM);
    }

    public static String generateAdminToken(Long userId, Long tenantId) {
        return generateToken(userId, "admin_" + userId, "admin", tenantId);
    }

    public static String generateMonitorToken(Long userId, Long tenantId) {
        return generateToken(userId, "monitor_" + userId, "monitor", tenantId);
    }

    public static String bearerToken(String token) {
        return "Bearer " + token;
    }

    public static String authHeader() {
        return bearerToken(generateToken());
    }

    public static String authHeader(Long userId) {
        return bearerToken(generateToken(userId));
    }

    public static String adminAuthHeader(Long userId, Long tenantId) {
        return bearerToken(generateAdminToken(userId, tenantId));
    }
}
