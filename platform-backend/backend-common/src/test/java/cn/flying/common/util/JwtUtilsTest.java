package cn.flying.common.util;

import com.auth0.jwt.interfaces.DecodedJWT;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("JwtUtils Tests")
@ExtendWith(MockitoExtension.class)
class JwtUtilsTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private FlowUtils flowUtils;

    private JwtUtils jwtUtils;

    // Strong random test key (64 characters for good entropy, avoids weak patterns)
    private static final String TEST_KEY = "JwtUniqueKeyForUnit2026RandomSecureTokenGenXyZ789AbCdEfGhIjKlMnOp";
    private static final int TEST_EXPIRE = 24;
    private static final int TEST_LIMIT_BASE = 60;
    private static final int TEST_LIMIT_UPGRADE = 300;
    private static final int TEST_LIMIT_FREQUENCY = 5;

    private MockedStatic<TenantKeyUtils> tenantKeyUtilsMock;

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils();

        // Set @Value fields using reflection
        ReflectionTestUtils.setField(jwtUtils, "KEY", TEST_KEY);
        ReflectionTestUtils.setField(jwtUtils, "EXPIRE", TEST_EXPIRE);
        ReflectionTestUtils.setField(jwtUtils, "LIMIT_BASE", TEST_LIMIT_BASE);
        ReflectionTestUtils.setField(jwtUtils, "LIMIT_UPGRADE", TEST_LIMIT_UPGRADE);
        ReflectionTestUtils.setField(jwtUtils, "LIMIT_FREQUENCY", TEST_LIMIT_FREQUENCY);

        // Set @Resource fields
        ReflectionTestUtils.setField(jwtUtils, "template", redisTemplate);
        ReflectionTestUtils.setField(jwtUtils, "utils", flowUtils);

        // Initialize algorithm and verifier (simulate @PostConstruct)
        ReflectionTestUtils.invokeMethod(jwtUtils, "init");

        // Mock TenantKeyUtils static method
        tenantKeyUtilsMock = mockStatic(TenantKeyUtils.class);
        tenantKeyUtilsMock.when(() -> TenantKeyUtils.tenantKey(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        if (tenantKeyUtilsMock != null) {
            tenantKeyUtilsMock.close();
        }
    }

    @Nested
    @DisplayName("Key Validation Tests")
    class KeyValidationTests {

        @Test
        @DisplayName("should throw for null key")
        void validateKey_throwsForNullKey() {
            JwtUtils newJwtUtils = new JwtUtils();
            ReflectionTestUtils.setField(newJwtUtils, "KEY", null);

            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(newJwtUtils, "validateKey"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT key must be provided");
        }

        @Test
        @DisplayName("should throw for short key")
        void validateKey_throwsForShortKey() {
            JwtUtils newJwtUtils = new JwtUtils();
            ReflectionTestUtils.setField(newJwtUtils, "KEY", "shortkey");

            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(newJwtUtils, "validateKey"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("at least 32 characters");
        }

        @Test
        @DisplayName("should throw for weak key containing password")
        void validateKey_throwsForWeakKey() {
            JwtUtils newJwtUtils = new JwtUtils();
            ReflectionTestUtils.setField(newJwtUtils, "KEY", "mySecretPasswordKeyThatIs32Chars!");

            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(newJwtUtils, "validateKey"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("weak/default key");
        }

        @Test
        @DisplayName("should accept valid strong key")
        void validateKey_acceptsStrongKey() {
            JwtUtils newJwtUtils = new JwtUtils();
            ReflectionTestUtils.setField(newJwtUtils, "KEY", TEST_KEY);

            assertThatCode(() -> ReflectionTestUtils.invokeMethod(newJwtUtils, "validateKey"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should throw for key with low character diversity")
        void validateKey_throwsForLowDiversity() {
            JwtUtils newJwtUtils = new JwtUtils();
            // Key with less than 10 distinct characters
            ReflectionTestUtils.setField(newJwtUtils, "KEY", "aaaaaaaabbbbbbbbccccccccdddddddd");

            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(newJwtUtils, "validateKey"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("weak/default key");
        }
    }

    @Nested
    @DisplayName("Token Creation Tests")
    class TokenCreationTests {

        @Test
        @DisplayName("should create JWT for valid user")
        void createJwt_createsTokenForValidUser() {
            UserDetails user = User.withUsername("testuser")
                    .password("password")
                    .authorities(new SimpleGrantedAuthority("ROLE_user"))
                    .build();

            when(flowUtils.limitOnceUpgradeCheck(anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(true);

            String token = jwtUtils.createJwt(user, "testuser", 123L, 1L);

            assertThat(token).isNotNull();
            assertThat(token).isNotEmpty();
            // Token should be a valid JWT with 3 parts
            assertThat(token.split("\\.")).hasSize(3);
        }

        @Test
        @DisplayName("should return null when rate limited")
        void createJwt_returnsNullWhenRateLimited() {
            UserDetails user = User.withUsername("testuser")
                    .password("password")
                    .authorities(new SimpleGrantedAuthority("ROLE_user"))
                    .build();

            when(flowUtils.limitOnceUpgradeCheck(anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(false);

            String token = jwtUtils.createJwt(user, "testuser", 123L, 1L);

            assertThat(token).isNull();
        }

        @Test
        @DisplayName("should include correct claims in token")
        void createJwt_includesCorrectClaims() {
            UserDetails user = User.withUsername("testuser")
                    .password("password")
                    .authorities(new SimpleGrantedAuthority("ROLE_admin"))
                    .build();

            when(flowUtils.limitOnceUpgradeCheck(anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(true);
            when(redisTemplate.hasKey(anyString())).thenReturn(false);

            String token = jwtUtils.createJwt(user, "testuser", 456L, 789L);
            DecodedJWT jwt = jwtUtils.resolveJwt("Bearer " + token);

            assertThat(jwt).isNotNull();
            assertThat(jwtUtils.toId(jwt)).isEqualTo(456L);
            assertThat(jwtUtils.toTenantId(jwt)).isEqualTo(789L);
            assertThat(jwt.getClaim("name").asString()).isEqualTo("testuser");
        }
    }

    @Nested
    @DisplayName("Token Resolution Tests")
    class TokenResolutionTests {

        private String createValidToken() {
            UserDetails user = User.withUsername("testuser")
                    .password("password")
                    .authorities(new SimpleGrantedAuthority("ROLE_user"))
                    .build();

            when(flowUtils.limitOnceUpgradeCheck(anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(true);

            return jwtUtils.createJwt(user, "testuser", 123L, 1L);
        }

        @Test
        @DisplayName("should resolve valid token")
        void resolveJwt_resolvesValidToken() {
            String token = createValidToken();
            when(redisTemplate.hasKey(anyString())).thenReturn(false);

            DecodedJWT jwt = jwtUtils.resolveJwt("Bearer " + token);

            assertThat(jwt).isNotNull();
            assertThat(jwt.getClaim("id").asLong()).isEqualTo(123L);
        }

        @Test
        @DisplayName("should return null for null token")
        void resolveJwt_returnsNullForNull() {
            DecodedJWT jwt = jwtUtils.resolveJwt(null);

            assertThat(jwt).isNull();
        }

        @Test
        @DisplayName("should return null for empty token")
        void resolveJwt_returnsNullForEmpty() {
            DecodedJWT jwt = jwtUtils.resolveJwt("");

            assertThat(jwt).isNull();
        }

        @Test
        @DisplayName("should return null for token without Bearer prefix")
        void resolveJwt_returnsNullWithoutBearerPrefix() {
            String token = createValidToken();

            DecodedJWT jwt = jwtUtils.resolveJwt(token);

            assertThat(jwt).isNull();
        }

        @Test
        @DisplayName("should return null for blacklisted token")
        void resolveJwt_returnsNullForBlacklistedToken() {
            String token = createValidToken();
            when(redisTemplate.hasKey(anyString())).thenReturn(true);

            DecodedJWT jwt = jwtUtils.resolveJwt("Bearer " + token);

            assertThat(jwt).isNull();
        }

        @Test
        @DisplayName("should return null for invalid token")
        void resolveJwt_returnsNullForInvalidToken() {
            DecodedJWT jwt = jwtUtils.resolveJwt("Bearer invalid.token.here");

            assertThat(jwt).isNull();
        }
    }

    @Nested
    @DisplayName("Token Invalidation Tests")
    class TokenInvalidationTests {

        @Test
        @DisplayName("should invalidate valid token")
        void invalidateJwt_invalidatesValidToken() {
            UserDetails user = User.withUsername("testuser")
                    .password("password")
                    .authorities(new SimpleGrantedAuthority("ROLE_user"))
                    .build();

            when(flowUtils.limitOnceUpgradeCheck(anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(true);
            when(redisTemplate.hasKey(anyString())).thenReturn(false);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);

            String token = jwtUtils.createJwt(user, "testuser", 123L, 1L);
            boolean result = jwtUtils.invalidateJwt("Bearer " + token);

            assertThat(result).isTrue();
            verify(valueOperations).set(anyString(), eq(""), anyLong(), eq(TimeUnit.MILLISECONDS));
        }

        @Test
        @DisplayName("should return false for invalid token format")
        void invalidateJwt_returnsFalseForInvalidToken() {
            boolean result = jwtUtils.invalidateJwt("Bearer invalid.token");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return false for already blacklisted token")
        void invalidateJwt_returnsFalseForAlreadyBlacklisted() {
            UserDetails user = User.withUsername("testuser")
                    .password("password")
                    .authorities(new SimpleGrantedAuthority("ROLE_user"))
                    .build();

            when(flowUtils.limitOnceUpgradeCheck(anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(true);
            when(redisTemplate.hasKey(anyString())).thenReturn(true);

            String token = jwtUtils.createJwt(user, "testuser", 123L, 1L);
            boolean result = jwtUtils.invalidateJwt("Bearer " + token);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("User Extraction Tests")
    class UserExtractionTests {

        private DecodedJWT createAndResolveToken() {
            UserDetails user = User.withUsername("testuser")
                    .password("password")
                    .authorities(
                            new SimpleGrantedAuthority("ROLE_admin"),
                            new SimpleGrantedAuthority("ROLE_user"))
                    .build();

            when(flowUtils.limitOnceUpgradeCheck(anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(true);
            when(redisTemplate.hasKey(anyString())).thenReturn(false);

            String token = jwtUtils.createJwt(user, "testuser", 123L, 456L);
            return jwtUtils.resolveJwt("Bearer " + token);
        }

        @Test
        @DisplayName("should extract user details from JWT")
        void toUser_extractsUserDetails() {
            DecodedJWT jwt = createAndResolveToken();

            UserDetails user = jwtUtils.toUser(jwt);

            assertThat(user.getUsername()).isEqualTo("testuser");
            assertThat(user.getAuthorities())
                    .extracting(auth -> auth.getAuthority())
                    .contains("ROLE_admin", "ROLE_user");
        }

        @Test
        @DisplayName("should extract user ID from JWT")
        void toId_extractsUserId() {
            DecodedJWT jwt = createAndResolveToken();

            Long id = jwtUtils.toId(jwt);

            assertThat(id).isEqualTo(123L);
        }

        @Test
        @DisplayName("should extract tenant ID from JWT")
        void toTenantId_extractsTenantId() {
            DecodedJWT jwt = createAndResolveToken();

            Long tenantId = jwtUtils.toTenantId(jwt);

            assertThat(tenantId).isEqualTo(456L);
        }

        @Test
        @DisplayName("should extract role from JWT")
        void toRole_extractsRole() {
            DecodedJWT jwt = createAndResolveToken();

            String role = jwtUtils.toRole(jwt);

            assertThat(role).isEqualTo("admin");
        }
    }

    @Nested
    @DisplayName("Token Refresh Tests")
    class TokenRefreshTests {

        @Test
        @DisplayName("should refresh valid token")
        void refreshJwt_refreshesValidToken() {
            UserDetails user = User.withUsername("testuser")
                    .password("password")
                    .authorities(new SimpleGrantedAuthority("ROLE_user"))
                    .build();

            when(flowUtils.limitOnceUpgradeCheck(anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(true);
            when(redisTemplate.hasKey(anyString())).thenReturn(false);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);

            String originalToken = jwtUtils.createJwt(user, "testuser", 123L, 1L);
            String refreshedToken = jwtUtils.refreshJwt("Bearer " + originalToken);

            assertThat(refreshedToken).isNotNull();
            assertThat(refreshedToken).isNotEqualTo(originalToken);
        }

        @Test
        @DisplayName("should return null when refreshing with invalid token")
        void refreshJwt_returnsNullForInvalidToken() {
            String refreshedToken = jwtUtils.refreshJwt("Bearer invalid.token");

            assertThat(refreshedToken).isNull();
        }

        @Test
        @DisplayName("should return null when rate limited during refresh")
        void refreshJwt_returnsNullWhenRateLimited() {
            UserDetails user = User.withUsername("testuser")
                    .password("password")
                    .authorities(new SimpleGrantedAuthority("ROLE_user"))
                    .build();

            // First call succeeds (for creating original token)
            // Second call fails (for refresh)
            when(flowUtils.limitOnceUpgradeCheck(anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(true)
                    .thenReturn(false);
            when(redisTemplate.hasKey(anyString())).thenReturn(false);

            String originalToken = jwtUtils.createJwt(user, "testuser", 123L, 1L);
            String refreshedToken = jwtUtils.refreshJwt("Bearer " + originalToken);

            assertThat(refreshedToken).isNull();
        }
    }

    @Nested
    @DisplayName("SSE Token Tests")
    class SseTokenTests {

        @Test
        @DisplayName("should create SSE token")
        void createSseToken_createsToken() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);

            String token = jwtUtils.createSseToken(123L, 456L, "user");

            assertThat(token).isNotNull();
            assertThat(token).hasSize(32); // UUID without dashes
            verify(valueOperations).set(
                    contains("sse:token:"),
                    eq("123:456:user"),
                    eq(Const.SSE_TOKEN_TTL),
                    eq(TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("should validate and consume SSE token")
        void validateAndConsumeSseToken_validatesToken() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.getAndDelete(anyString())).thenReturn("123:456:admin");

            String[] result = jwtUtils.validateAndConsumeSseToken("test-token");

            assertThat(result).isNotNull();
            assertThat(result).hasSize(3);
            assertThat(result[0]).isEqualTo("123");
            assertThat(result[1]).isEqualTo("456");
            assertThat(result[2]).isEqualTo("admin");
        }

        @Test
        @DisplayName("should return null for already consumed token")
        void validateAndConsumeSseToken_returnsNullForConsumed() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.getAndDelete(anyString())).thenReturn(null);

            String[] result = jwtUtils.validateAndConsumeSseToken("consumed-token");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return null for null token")
        void validateAndConsumeSseToken_returnsNullForNull() {
            String[] result = jwtUtils.validateAndConsumeSseToken(null);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return null for empty token")
        void validateAndConsumeSseToken_returnsNullForEmpty() {
            String[] result = jwtUtils.validateAndConsumeSseToken("");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return null for invalid format")
        void validateAndConsumeSseToken_returnsNullForInvalidFormat() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.getAndDelete(anyString())).thenReturn("invalid-format");

            String[] result = jwtUtils.validateAndConsumeSseToken("malformed-token");

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("Expire Time Tests")
    class ExpireTimeTests {

        @Test
        @DisplayName("should calculate expire time correctly")
        void expireTime_calculatesCorrectly() {
            java.util.Date before = new java.util.Date();
            java.util.Date expire = jwtUtils.expireTime();
            java.util.Date after = new java.util.Date();

            // Expire should be approximately EXPIRE hours from now
            long expectedMs = TEST_EXPIRE * 60 * 60 * 1000L;
            assertThat(expire.getTime() - before.getTime())
                    .isGreaterThanOrEqualTo(expectedMs - 1000)
                    .isLessThanOrEqualTo(after.getTime() - before.getTime() + expectedMs + 1000);
        }
    }
}
