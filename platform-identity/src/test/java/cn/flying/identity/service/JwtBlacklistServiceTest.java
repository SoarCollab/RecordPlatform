package cn.flying.identity.service;

import cn.flying.identity.service.impl.JwtBlacklistServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * JWT黑名单服务单元测试
 * 测试范围：令牌黑名单管理、过期时间处理、Redis故障处理
 *
 * @author 王贝强
 * @create 2025-01-13
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JwtBlacklistServiceTest {

    @InjectMocks
    private JwtBlacklistServiceImpl jwtBlacklistService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    // 测试数据常量
    private static final String TEST_TOKEN = "test_jwt_token_abc123xyz789";
    private static final String JWT_BLACKLIST_PREFIX = "identity:jwt:blacklist:";
    private static final long DEFAULT_TTL = 7200L; // 2小时
    private static final long CUSTOM_TTL = 3600L;  // 1小时

    @BeforeEach
    void setUp() {
        // 配置Redis Mock
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // 注入配置值
        ReflectionTestUtils.setField(jwtBlacklistService, "jwtBlacklistPrefix", JWT_BLACKLIST_PREFIX);
        ReflectionTestUtils.setField(jwtBlacklistService, "jwtBlacklistTtlSeconds", DEFAULT_TTL);
    }

    @Test
    void testBlacklistToken_Success() {
        // Mock Redis操作
        doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        // 执行测试：使用默认过期时间（传入-1）
        jwtBlacklistService.blacklistToken(TEST_TOKEN, -1);

        // 验证Redis的set操作被调用，使用默认TTL
        verify(valueOperations).set(
            eq(JWT_BLACKLIST_PREFIX + TEST_TOKEN),
            eq("1"),
            eq(DEFAULT_TTL),
            eq(TimeUnit.SECONDS)
        );
    }

    @Test
    void testBlacklistToken_WithCustomExpiry() {
        // Mock Redis操作
        doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        // 执行测试：使用自定义过期时间
        jwtBlacklistService.blacklistToken(TEST_TOKEN, CUSTOM_TTL);

        // 验证Redis的set操作被调用，使用自定义TTL
        verify(valueOperations).set(
            eq(JWT_BLACKLIST_PREFIX + TEST_TOKEN),
            eq("1"),
            eq(CUSTOM_TTL),
            eq(TimeUnit.SECONDS)
        );
    }

    @Test
    void testBlacklistToken_AutoExpiry() {
        // Mock Redis操作
        doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        // 执行测试：传入0应该使用默认过期时间
        jwtBlacklistService.blacklistToken(TEST_TOKEN, 0);

        // 验证Redis的set操作被调用，使用默认TTL（因为0 <= 0）
        verify(valueOperations).set(
            eq(JWT_BLACKLIST_PREFIX + TEST_TOKEN),
            eq("1"),
            eq(DEFAULT_TTL),
            eq(TimeUnit.SECONDS)
        );
    }

    @Test
    void testBlacklistToken_NullToken() {
        // 执行测试：null token应该被忽略
        jwtBlacklistService.blacklistToken(null, CUSTOM_TTL);

        // 验证Redis操作未被调用
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void testBlacklistToken_EmptyToken() {
        // 执行测试：空字符串token应该被忽略
        jwtBlacklistService.blacklistToken("", CUSTOM_TTL);
        jwtBlacklistService.blacklistToken("   ", CUSTOM_TTL);

        // 验证Redis操作未被调用
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void testIsBlacklisted_True() {
        // Mock Token在黑名单中
        when(redisTemplate.hasKey(JWT_BLACKLIST_PREFIX + TEST_TOKEN))
                .thenReturn(true);

        // 执行测试
        boolean result = jwtBlacklistService.isBlacklisted(TEST_TOKEN);

        // 验证结果
        assertTrue(result);

        // 验证Redis的hasKey被调用
        verify(redisTemplate).hasKey(eq(JWT_BLACKLIST_PREFIX + TEST_TOKEN));
    }

    @Test
    void testIsBlacklisted_False() {
        // Mock Token不在黑名单中
        when(redisTemplate.hasKey(JWT_BLACKLIST_PREFIX + TEST_TOKEN))
                .thenReturn(false);

        // 执行测试
        boolean result = jwtBlacklistService.isBlacklisted(TEST_TOKEN);

        // 验证结果
        assertFalse(result);

        // 验证Redis的hasKey被调用
        verify(redisTemplate).hasKey(eq(JWT_BLACKLIST_PREFIX + TEST_TOKEN));
    }

    @Test
    void testIsBlacklisted_NullToken() {
        // 执行测试：null token应该返回false
        boolean result = jwtBlacklistService.isBlacklisted(null);

        // 验证结果
        assertFalse(result);

        // 验证Redis操作未被调用
        verify(redisTemplate, never()).hasKey(anyString());
    }

    @Test
    void testIsBlacklisted_EmptyToken() {
        // 执行测试：空字符串token应该返回false
        assertFalse(jwtBlacklistService.isBlacklisted(""));
        assertFalse(jwtBlacklistService.isBlacklisted("   "));

        // 验证Redis操作未被调用
        verify(redisTemplate, never()).hasKey(anyString());
    }
}
