package cn.flying.monitor.common.security;

import com.auth0.jwt.interfaces.DecodedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JWT令牌提供者测试
 */
class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtSecret", "test-secret-key");
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtExpirationInSeconds", 3600L);
        ReflectionTestUtils.setField(jwtTokenProvider, "refreshExpirationInSeconds", 86400L);
    }

    @Test
    void shouldGenerateValidAccessToken() {
        // Given
        String userId = "123";
        String username = "testuser";
        List<String> roles = List.of("USER", "ADMIN");
        boolean mfaVerified = true;

        // When
        String token = jwtTokenProvider.generateAccessToken(userId, username, roles, mfaVerified);

        // Then
        assertNotNull(token);
        assertTrue(jwtTokenProvider.validateToken(token));
        assertTrue(jwtTokenProvider.isAccessToken(token));
        assertEquals(userId, jwtTokenProvider.getUserId(token));
        assertEquals(username, jwtTokenProvider.getUsername(token));
        assertEquals(roles, jwtTokenProvider.getRoles(token));
        assertTrue(jwtTokenProvider.isMfaVerified(token));
    }

    @Test
    void shouldGenerateValidRefreshToken() {
        // Given
        String userId = "123";

        // When
        String token = jwtTokenProvider.generateRefreshToken(userId);

        // Then
        assertNotNull(token);
        assertTrue(jwtTokenProvider.validateToken(token));
        assertTrue(jwtTokenProvider.isRefreshToken(token));
        assertEquals(userId, jwtTokenProvider.getUserId(token));
    }

    @Test
    void shouldValidateTokenCorrectly() {
        // Given
        String token = jwtTokenProvider.generateAccessToken("123", "test", List.of("USER"), true);

        // When & Then
        assertTrue(jwtTokenProvider.validateToken(token));
        assertFalse(jwtTokenProvider.validateToken("invalid-token"));
        assertFalse(jwtTokenProvider.validateToken(null));
    }

    @Test
    void shouldParseTokenCorrectly() {
        // Given
        String userId = "123";
        String username = "testuser";
        List<String> roles = List.of("USER", "ADMIN");
        boolean mfaVerified = false;
        String token = jwtTokenProvider.generateAccessToken(userId, username, roles, mfaVerified);

        // When
        DecodedJWT decodedJWT = jwtTokenProvider.parseToken(token);

        // Then
        assertNotNull(decodedJWT);
        assertEquals(userId, decodedJWT.getSubject());
        assertEquals(username, decodedJWT.getClaim("username").asString());
        assertEquals(roles, decodedJWT.getClaim("roles").asList(String.class));
        assertFalse(decodedJWT.getClaim("mfa_verified").asBoolean());
        assertEquals("access", decodedJWT.getClaim("token_type").asString());
    }

    @Test
    void shouldHandleMfaVerificationCorrectly() {
        // Given
        String tokenWithMfa = jwtTokenProvider.generateAccessToken("123", "test", List.of("USER"), true);
        String tokenWithoutMfa = jwtTokenProvider.generateAccessToken("123", "test", List.of("USER"), false);

        // When & Then
        assertTrue(jwtTokenProvider.isMfaVerified(tokenWithMfa));
        assertFalse(jwtTokenProvider.isMfaVerified(tokenWithoutMfa));
    }

    @Test
    void shouldDistinguishTokenTypes() {
        // Given
        String accessToken = jwtTokenProvider.generateAccessToken("123", "test", List.of("USER"), true);
        String refreshToken = jwtTokenProvider.generateRefreshToken("123");

        // When & Then
        assertTrue(jwtTokenProvider.isAccessToken(accessToken));
        assertFalse(jwtTokenProvider.isRefreshToken(accessToken));
        
        assertTrue(jwtTokenProvider.isRefreshToken(refreshToken));
        assertFalse(jwtTokenProvider.isAccessToken(refreshToken));
    }
}