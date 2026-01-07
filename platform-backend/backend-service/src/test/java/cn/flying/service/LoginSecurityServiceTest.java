package cn.flying.service;

import cn.flying.common.util.Const;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * LoginSecurityService Unit Tests
 *
 * Tests for login security features including:
 * - Account lockout after max failed attempts
 * - Login failure counting
 * - Remaining attempts tracking
 * - Lock time remaining
 * - Clearing failures on success
 */
@DisplayName("LoginSecurityService Tests")
@ExtendWith(MockitoExtension.class)
class LoginSecurityServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private LoginSecurityService loginSecurityService;

    private static final String TEST_USERNAME = "testuser@example.com";
    private static final String REDIS_KEY = Const.LOGIN_FAIL_COUNT + TEST_USERNAME;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Nested
    @DisplayName("isAccountLocked Tests")
    class IsAccountLockedTests {

        @Test
        @DisplayName("should return false when no failure record exists")
        void noRecord_returnsFalse() {
            when(valueOperations.get(REDIS_KEY)).thenReturn(null);

            boolean result = loginSecurityService.isAccountLocked(TEST_USERNAME);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return false when failure count is below max attempts")
        void belowMaxAttempts_returnsFalse() {
            when(valueOperations.get(REDIS_KEY)).thenReturn(String.valueOf(Const.LOGIN_MAX_ATTEMPTS - 1));

            boolean result = loginSecurityService.isAccountLocked(TEST_USERNAME);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return true when failure count equals max attempts")
        void equalsMaxAttempts_returnsTrue() {
            when(valueOperations.get(REDIS_KEY)).thenReturn(String.valueOf(Const.LOGIN_MAX_ATTEMPTS));

            boolean result = loginSecurityService.isAccountLocked(TEST_USERNAME);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return true when failure count exceeds max attempts")
        void exceedsMaxAttempts_returnsTrue() {
            when(valueOperations.get(REDIS_KEY)).thenReturn(String.valueOf(Const.LOGIN_MAX_ATTEMPTS + 5));

            boolean result = loginSecurityService.isAccountLocked(TEST_USERNAME);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false for invalid count value")
        void invalidCountValue_returnsFalse() {
            when(valueOperations.get(REDIS_KEY)).thenReturn("not-a-number");

            boolean result = loginSecurityService.isAccountLocked(TEST_USERNAME);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return false for empty string value")
        void emptyStringValue_returnsFalse() {
            when(valueOperations.get(REDIS_KEY)).thenReturn("");

            boolean result = loginSecurityService.isAccountLocked(TEST_USERNAME);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("getRemainingLockTime Tests")
    class GetRemainingLockTimeTests {

        @Test
        @DisplayName("should return -1 when no TTL exists")
        void noTtl_returnsNegativeOne() {
            when(stringRedisTemplate.getExpire(REDIS_KEY, TimeUnit.SECONDS)).thenReturn(-2L);

            long result = loginSecurityService.getRemainingLockTime(TEST_USERNAME);

            assertThat(result).isEqualTo(-1);
        }

        @Test
        @DisplayName("should return -1 when key has no expiration")
        void noExpiration_returnsNegativeOne() {
            when(stringRedisTemplate.getExpire(REDIS_KEY, TimeUnit.SECONDS)).thenReturn(-1L);

            long result = loginSecurityService.getRemainingLockTime(TEST_USERNAME);

            assertThat(result).isEqualTo(-1);
        }

        @Test
        @DisplayName("should return -1 when TTL is null")
        void nullTtl_returnsNegativeOne() {
            when(stringRedisTemplate.getExpire(REDIS_KEY, TimeUnit.SECONDS)).thenReturn(null);

            long result = loginSecurityService.getRemainingLockTime(TEST_USERNAME);

            assertThat(result).isEqualTo(-1);
        }

        @Test
        @DisplayName("should return -1 when count value is null")
        void nullCountValue_returnsNegativeOne() {
            when(stringRedisTemplate.getExpire(REDIS_KEY, TimeUnit.SECONDS)).thenReturn(600L);
            when(valueOperations.get(REDIS_KEY)).thenReturn(null);

            long result = loginSecurityService.getRemainingLockTime(TEST_USERNAME);

            assertThat(result).isEqualTo(-1);
        }

        @Test
        @DisplayName("should return -1 when count is below max attempts")
        void belowMaxAttempts_returnsNegativeOne() {
            when(stringRedisTemplate.getExpire(REDIS_KEY, TimeUnit.SECONDS)).thenReturn(600L);
            when(valueOperations.get(REDIS_KEY)).thenReturn(String.valueOf(Const.LOGIN_MAX_ATTEMPTS - 1));

            long result = loginSecurityService.getRemainingLockTime(TEST_USERNAME);

            assertThat(result).isEqualTo(-1);
        }

        @Test
        @DisplayName("should return TTL when account is locked")
        void accountLocked_returnsTtl() {
            long expectedTtl = 600L;
            when(stringRedisTemplate.getExpire(REDIS_KEY, TimeUnit.SECONDS)).thenReturn(expectedTtl);
            when(valueOperations.get(REDIS_KEY)).thenReturn(String.valueOf(Const.LOGIN_MAX_ATTEMPTS));

            long result = loginSecurityService.getRemainingLockTime(TEST_USERNAME);

            assertThat(result).isEqualTo(expectedTtl);
        }

        @Test
        @DisplayName("should return -1 for invalid count value")
        void invalidCountValue_returnsNegativeOne() {
            when(stringRedisTemplate.getExpire(REDIS_KEY, TimeUnit.SECONDS)).thenReturn(600L);
            when(valueOperations.get(REDIS_KEY)).thenReturn("invalid");

            long result = loginSecurityService.getRemainingLockTime(TEST_USERNAME);

            assertThat(result).isEqualTo(-1);
        }
    }

    @Nested
    @DisplayName("recordLoginFailure Tests")
    class RecordLoginFailureTests {

        @Test
        @DisplayName("should increment failure count and set expiration")
        void firstFailure_incrementsAndSetsExpiration() {
            when(valueOperations.increment(REDIS_KEY)).thenReturn(1L);

            int result = loginSecurityService.recordLoginFailure(TEST_USERNAME);

            assertThat(result).isEqualTo(1);
            verify(valueOperations).increment(REDIS_KEY);
            verify(stringRedisTemplate).expire(REDIS_KEY, Const.LOGIN_LOCK_DURATION, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("should return correct count after multiple failures")
        void multipleFailures_returnsCorrectCount() {
            when(valueOperations.increment(REDIS_KEY)).thenReturn(3L);

            int result = loginSecurityService.recordLoginFailure(TEST_USERNAME);

            assertThat(result).isEqualTo(3);
            verify(stringRedisTemplate).expire(REDIS_KEY, Const.LOGIN_LOCK_DURATION, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("should return 1 when increment returns null")
        void incrementReturnsNull_returnsOne() {
            when(valueOperations.increment(REDIS_KEY)).thenReturn(null);

            int result = loginSecurityService.recordLoginFailure(TEST_USERNAME);

            assertThat(result).isEqualTo(1);
            verify(stringRedisTemplate).expire(REDIS_KEY, Const.LOGIN_LOCK_DURATION, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("should refresh expiration on each failure")
        void eachFailure_refreshesExpiration() {
            when(valueOperations.increment(REDIS_KEY)).thenReturn(1L, 2L, 3L);

            loginSecurityService.recordLoginFailure(TEST_USERNAME);
            loginSecurityService.recordLoginFailure(TEST_USERNAME);
            loginSecurityService.recordLoginFailure(TEST_USERNAME);

            verify(stringRedisTemplate, times(3)).expire(REDIS_KEY, Const.LOGIN_LOCK_DURATION, TimeUnit.SECONDS);
        }
    }

    @Nested
    @DisplayName("clearLoginFailure Tests")
    class ClearLoginFailureTests {

        @Test
        @DisplayName("should delete the failure record")
        void clearsRecord() {
            loginSecurityService.clearLoginFailure(TEST_USERNAME);

            verify(stringRedisTemplate).delete(REDIS_KEY);
        }

        @Test
        @DisplayName("should clear record for different usernames independently")
        void clearsIndependently() {
            String anotherUser = "another@example.com";
            String anotherKey = Const.LOGIN_FAIL_COUNT + anotherUser;

            loginSecurityService.clearLoginFailure(TEST_USERNAME);
            loginSecurityService.clearLoginFailure(anotherUser);

            verify(stringRedisTemplate).delete(REDIS_KEY);
            verify(stringRedisTemplate).delete(anotherKey);
        }
    }

    @Nested
    @DisplayName("getRemainingAttempts Tests")
    class GetRemainingAttemptsTests {

        @Test
        @DisplayName("should return max attempts when no failure record exists")
        void noRecord_returnsMaxAttempts() {
            when(valueOperations.get(REDIS_KEY)).thenReturn(null);

            int result = loginSecurityService.getRemainingAttempts(TEST_USERNAME);

            assertThat(result).isEqualTo(Const.LOGIN_MAX_ATTEMPTS);
        }

        @Test
        @DisplayName("should return correct remaining attempts")
        void withFailures_returnsCorrectRemaining() {
            when(valueOperations.get(REDIS_KEY)).thenReturn("2");

            int result = loginSecurityService.getRemainingAttempts(TEST_USERNAME);

            assertThat(result).isEqualTo(Const.LOGIN_MAX_ATTEMPTS - 2);
        }

        @Test
        @DisplayName("should return 0 when at max attempts")
        void atMaxAttempts_returnsZero() {
            when(valueOperations.get(REDIS_KEY)).thenReturn(String.valueOf(Const.LOGIN_MAX_ATTEMPTS));

            int result = loginSecurityService.getRemainingAttempts(TEST_USERNAME);

            assertThat(result).isEqualTo(0);
        }

        @Test
        @DisplayName("should return 0 when exceeds max attempts")
        void exceedsMaxAttempts_returnsZero() {
            when(valueOperations.get(REDIS_KEY)).thenReturn(String.valueOf(Const.LOGIN_MAX_ATTEMPTS + 10));

            int result = loginSecurityService.getRemainingAttempts(TEST_USERNAME);

            assertThat(result).isEqualTo(0);
        }

        @Test
        @DisplayName("should return max attempts for invalid count value")
        void invalidCountValue_returnsMaxAttempts() {
            when(valueOperations.get(REDIS_KEY)).thenReturn("not-a-number");

            int result = loginSecurityService.getRemainingAttempts(TEST_USERNAME);

            assertThat(result).isEqualTo(Const.LOGIN_MAX_ATTEMPTS);
        }

        @Test
        @DisplayName("should return max attempts for empty string value")
        void emptyStringValue_returnsMaxAttempts() {
            when(valueOperations.get(REDIS_KEY)).thenReturn("");

            int result = loginSecurityService.getRemainingAttempts(TEST_USERNAME);

            assertThat(result).isEqualTo(Const.LOGIN_MAX_ATTEMPTS);
        }
    }

    @Nested
    @DisplayName("Integration Scenario Tests")
    class IntegrationScenarioTests {

        @Test
        @DisplayName("should lock account after max failed attempts")
        void lockAfterMaxFailedAttempts() {
            // Simulate max attempts
            when(valueOperations.increment(REDIS_KEY))
                    .thenReturn(1L, 2L, 3L, 4L, 5L);
            when(valueOperations.get(REDIS_KEY))
                    .thenReturn(String.valueOf(Const.LOGIN_MAX_ATTEMPTS));

            // Record failures
            for (int i = 0; i < Const.LOGIN_MAX_ATTEMPTS; i++) {
                loginSecurityService.recordLoginFailure(TEST_USERNAME);
            }

            // Verify locked
            boolean locked = loginSecurityService.isAccountLocked(TEST_USERNAME);
            int remaining = loginSecurityService.getRemainingAttempts(TEST_USERNAME);

            assertThat(locked).isTrue();
            assertThat(remaining).isEqualTo(0);
        }

        @Test
        @DisplayName("should unlock account after clearing failures")
        void unlockAfterClearingFailures() {
            // Setup: account is locked
            when(valueOperations.get(REDIS_KEY))
                    .thenReturn(String.valueOf(Const.LOGIN_MAX_ATTEMPTS))
                    .thenReturn(null);

            // Verify locked first
            assertThat(loginSecurityService.isAccountLocked(TEST_USERNAME)).isTrue();

            // Clear failures
            loginSecurityService.clearLoginFailure(TEST_USERNAME);

            // Verify unlocked
            assertThat(loginSecurityService.isAccountLocked(TEST_USERNAME)).isFalse();
        }

        @Test
        @DisplayName("should handle email usernames correctly")
        void handleEmailUsernames() {
            String emailUsername = "user@company.example.com";
            String expectedKey = Const.LOGIN_FAIL_COUNT + emailUsername;
            when(valueOperations.increment(expectedKey)).thenReturn(1L);

            int result = loginSecurityService.recordLoginFailure(emailUsername);

            assertThat(result).isEqualTo(1);
            verify(valueOperations).increment(expectedKey);
        }

        @Test
        @DisplayName("should handle special characters in username")
        void handleSpecialCharactersInUsername() {
            String specialUsername = "user+tag@example.com";
            String expectedKey = Const.LOGIN_FAIL_COUNT + specialUsername;
            when(valueOperations.increment(expectedKey)).thenReturn(1L);

            int result = loginSecurityService.recordLoginFailure(specialUsername);

            assertThat(result).isEqualTo(1);
            verify(valueOperations).increment(expectedKey);
        }
    }

    @Nested
    @DisplayName("Key Construction Tests")
    class KeyConstructionTests {

        @Test
        @DisplayName("should use correct key prefix")
        void usesCorrectKeyPrefix() {
            when(valueOperations.get(REDIS_KEY)).thenReturn("1");

            loginSecurityService.getRemainingAttempts(TEST_USERNAME);

            verify(valueOperations).get(REDIS_KEY);
            assertThat(REDIS_KEY).startsWith(Const.LOGIN_FAIL_COUNT);
        }

        @Test
        @DisplayName("should not add tenant prefix to login key")
        void noTenantPrefixForLoginKey() {
            // Login keys should NOT have tenant prefix because
            // tenant context is not established during login
            String expectedKey = Const.LOGIN_FAIL_COUNT + TEST_USERNAME;
            when(valueOperations.get(expectedKey)).thenReturn("1");

            loginSecurityService.getRemainingAttempts(TEST_USERNAME);

            verify(valueOperations).get(expectedKey);
            assertThat(expectedKey).doesNotContain("tenant:");
        }
    }
}
