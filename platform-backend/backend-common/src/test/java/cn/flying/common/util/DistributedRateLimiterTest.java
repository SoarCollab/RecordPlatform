package cn.flying.common.util;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("DistributedRateLimiter Tests")
@ExtendWith(MockitoExtension.class)
class DistributedRateLimiterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private DistributedRateLimiter rateLimiter;

    private static final String COUNTER_KEY = "rate:counter:test";
    private static final String BLOCK_KEY = "rate:block:test";
    private static final int LIMIT = 10;
    private static final int WINDOW_SECONDS = 60;
    private static final int BLOCK_SECONDS = 300;

    @Nested
    @DisplayName("tryAcquireWithBlock Tests")
    class TryAcquireWithBlockTests {

        @Test
        @DisplayName("should return ALLOWED when under limit")
        void tryAcquireWithBlock_returnsAllowedWhenUnderLimit() {
            doReturn(1L).when(redisTemplate).execute(any(), anyList(), any(Object[].class));

            DistributedRateLimiter.RateLimitResult result = rateLimiter.tryAcquireWithBlock(
                    COUNTER_KEY, BLOCK_KEY, LIMIT, WINDOW_SECONDS, BLOCK_SECONDS);

            assertThat(result).isEqualTo(DistributedRateLimiter.RateLimitResult.ALLOWED);
        }

        @Test
        @DisplayName("should return RATE_LIMITED when limit exceeded")
        void tryAcquireWithBlock_returnsRateLimitedWhenExceeded() {
            doReturn(0L).when(redisTemplate).execute(any(), anyList(), any(Object[].class));

            DistributedRateLimiter.RateLimitResult result = rateLimiter.tryAcquireWithBlock(
                    COUNTER_KEY, BLOCK_KEY, LIMIT, WINDOW_SECONDS, BLOCK_SECONDS);

            assertThat(result).isEqualTo(DistributedRateLimiter.RateLimitResult.RATE_LIMITED);
        }

        @Test
        @DisplayName("should return BLOCKED when already blocked")
        void tryAcquireWithBlock_returnsBlockedWhenBlocked() {
            doReturn(-1L).when(redisTemplate).execute(any(), anyList(), any(Object[].class));

            DistributedRateLimiter.RateLimitResult result = rateLimiter.tryAcquireWithBlock(
                    COUNTER_KEY, BLOCK_KEY, LIMIT, WINDOW_SECONDS, BLOCK_SECONDS);

            assertThat(result).isEqualTo(DistributedRateLimiter.RateLimitResult.BLOCKED);
        }

        @Test
        @DisplayName("should return ALLOWED on Redis failure (graceful degradation)")
        void tryAcquireWithBlock_returnsAllowedOnRedisFailure() {
            doThrow(new RuntimeException("Redis connection failed"))
                    .when(redisTemplate).execute(any(), anyList(), any(Object[].class));

            DistributedRateLimiter.RateLimitResult result = rateLimiter.tryAcquireWithBlock(
                    COUNTER_KEY, BLOCK_KEY, LIMIT, WINDOW_SECONDS, BLOCK_SECONDS);

            assertThat(result).isEqualTo(DistributedRateLimiter.RateLimitResult.ALLOWED);
        }

        @Test
        @DisplayName("should handle unexpected result value")
        void tryAcquireWithBlock_handlesUnexpectedResult() {
            doReturn(99L).when(redisTemplate).execute(any(), anyList(), any(Object[].class));

            DistributedRateLimiter.RateLimitResult result = rateLimiter.tryAcquireWithBlock(
                    COUNTER_KEY, BLOCK_KEY, LIMIT, WINDOW_SECONDS, BLOCK_SECONDS);

            // Unexpected values default to ALLOWED
            assertThat(result).isEqualTo(DistributedRateLimiter.RateLimitResult.ALLOWED);
        }
    }

    @Nested
    @DisplayName("tryAcquire Tests (Simple Rate Limiting)")
    class TryAcquireTests {

        @Test
        @DisplayName("should return true when under limit")
        void tryAcquire_returnsTrueWhenUnderLimit() {
            doReturn(1L).when(redisTemplate).execute(any(), anyList(), any(Object[].class));

            boolean result = rateLimiter.tryAcquire(COUNTER_KEY, LIMIT, WINDOW_SECONDS);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false when limit exceeded")
        void tryAcquire_returnsFalseWhenExceeded() {
            doReturn(0L).when(redisTemplate).execute(any(), anyList(), any(Object[].class));

            boolean result = rateLimiter.tryAcquire(COUNTER_KEY, LIMIT, WINDOW_SECONDS);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return true on Redis failure (graceful degradation)")
        void tryAcquire_returnsTrueOnRedisFailure() {
            doThrow(new RuntimeException("Redis connection failed"))
                    .when(redisTemplate).execute(any(), anyList(), any(Object[].class));

            boolean result = rateLimiter.tryAcquire(COUNTER_KEY, LIMIT, WINDOW_SECONDS);

            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("isBlocked Tests")
    class IsBlockedTests {

        @Test
        @DisplayName("should return true when key exists")
        void isBlocked_returnsTrueWhenKeyExists() {
            when(redisTemplate.hasKey(BLOCK_KEY)).thenReturn(true);

            boolean result = rateLimiter.isBlocked(BLOCK_KEY);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false when key does not exist")
        void isBlocked_returnsFalseWhenKeyNotExists() {
            when(redisTemplate.hasKey(BLOCK_KEY)).thenReturn(false);

            boolean result = rateLimiter.isBlocked(BLOCK_KEY);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return false on Redis failure")
        void isBlocked_returnsFalseOnRedisFailure() {
            when(redisTemplate.hasKey(BLOCK_KEY))
                    .thenThrow(new RuntimeException("Redis connection failed"));

            boolean result = rateLimiter.isBlocked(BLOCK_KEY);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should check correct key")
        void isBlocked_checksCorrectKey() {
            when(redisTemplate.hasKey(BLOCK_KEY)).thenReturn(false);

            rateLimiter.isBlocked(BLOCK_KEY);

            verify(redisTemplate).hasKey(BLOCK_KEY);
        }
    }

    @Nested
    @DisplayName("RateLimitResult Enum Tests")
    class RateLimitResultTests {

        @Test
        @DisplayName("should have three result types")
        void rateLimitResult_hasThreeTypes() {
            DistributedRateLimiter.RateLimitResult[] values = DistributedRateLimiter.RateLimitResult.values();

            assertThat(values).hasSize(3);
            assertThat(values).contains(
                    DistributedRateLimiter.RateLimitResult.ALLOWED,
                    DistributedRateLimiter.RateLimitResult.RATE_LIMITED,
                    DistributedRateLimiter.RateLimitResult.BLOCKED
            );
        }
    }
}
