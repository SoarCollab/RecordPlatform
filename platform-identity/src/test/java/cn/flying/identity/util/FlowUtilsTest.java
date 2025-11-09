package cn.flying.identity.util;

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

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FlowUtils 单元测试
 * 测试流量控制功能
 *
 * @author 王贝强
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FlowUtilsTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private FlowUtils flowUtils;

    /**
     * 测试前准备
     */
    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void testCheckEmailVerifyLimit_Pass() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(60L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        boolean result = flowUtils.checkEmailVerifyLimit("192.168.1.1", 60);

        assertTrue(result);
        verify(valueOperations).setIfAbsent(anyString(), eq("1"), eq(60L), eq(TimeUnit.SECONDS));
    }

    @Test
    void testCheckEmailVerifyLimit_Blocked() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(60L), eq(TimeUnit.SECONDS)))
                .thenReturn(false);

        boolean result = flowUtils.checkEmailVerifyLimit("192.168.1.1", 60);

        assertFalse(result);
        verify(valueOperations).setIfAbsent(anyString(), eq("1"), eq(60L), eq(TimeUnit.SECONDS));
    }

    @Test
    void testLimitOnceCheck_Pass() {
        when(valueOperations.setIfAbsent("test:key", "1", 30L, TimeUnit.SECONDS))
                .thenReturn(true);

        boolean result = flowUtils.limitOnceCheck("test:key", 30);

        assertTrue(result);
        verify(valueOperations).setIfAbsent("test:key", "1", 30L, TimeUnit.SECONDS);
    }

    @Test
    void testLimitOnceCheck_Blocked() {
        when(valueOperations.setIfAbsent("test:key", "1", 30L, TimeUnit.SECONDS))
                .thenReturn(false);

        boolean result = flowUtils.limitOnceCheck("test:key", 30);

        assertFalse(result);
    }

    @Test
    void testLimitOnceCheck_Exception() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new RuntimeException("Redis连接失败"));

        boolean result = flowUtils.limitOnceCheck("test:key", 30);

        // 异常情况下应该允许通过
        assertTrue(result);
    }

    @Test
    void testCheckLoginLimit_Pass() {
        when(valueOperations.increment(anyString())).thenReturn(1L);

        boolean result = flowUtils.checkLoginLimit("user123", 5, 300);

        assertTrue(result);
        verify(stringRedisTemplate).expire(anyString(), eq(300L), eq(TimeUnit.SECONDS));
    }

    @Test
    void testCheckLoginLimit_Blocked() {
        when(valueOperations.increment(anyString())).thenReturn(6L);

        boolean result = flowUtils.checkLoginLimit("user123", 5, 300);

        assertFalse(result);
    }

    @Test
    void testLimitCountCheck_FirstTime() {
        when(valueOperations.increment("test:count")).thenReturn(1L);

        boolean result = flowUtils.limitCountCheck("test:count", 10, 60);

        assertTrue(result);
        verify(stringRedisTemplate).expire("test:count", 60L, TimeUnit.SECONDS);
    }

    @Test
    void testLimitCountCheck_WithinLimit() {
        when(valueOperations.increment("test:count")).thenReturn(5L);

        boolean result = flowUtils.limitCountCheck("test:count", 10, 60);

        assertTrue(result);
    }

    @Test
    void testLimitCountCheck_ExceedLimit() {
        when(valueOperations.increment("test:count")).thenReturn(11L);

        boolean result = flowUtils.limitCountCheck("test:count", 10, 60);

        assertFalse(result);
    }

    @Test
    void testLimitCountCheck_NullReturn() {
        when(valueOperations.increment("test:count")).thenReturn(null);

        boolean result = flowUtils.limitCountCheck("test:count", 10, 60);

        assertFalse(result);
    }

    @Test
    void testRecordLoginFailure_FirstFailure() {
        when(valueOperations.increment(anyString())).thenReturn(1L);

        int count = flowUtils.recordLoginFailure("user123", 300);

        assertEquals(1, count);
        verify(stringRedisTemplate).expire(anyString(), eq(300L), eq(TimeUnit.SECONDS));
    }

    @Test
    void testRecordLoginFailure_MultipleFailures() {
        when(valueOperations.increment(anyString())).thenReturn(3L);

        int count = flowUtils.recordLoginFailure("user123", 300);

        assertEquals(3, count);
        verify(stringRedisTemplate, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void testRecordLoginFailure_Exception() {
        when(valueOperations.increment(anyString())).thenThrow(new RuntimeException("Redis错误"));

        int count = flowUtils.recordLoginFailure("user123", 300);

        assertEquals(1, count);
    }

    @Test
    void testClearLoginFailure_Success() {
        when(stringRedisTemplate.delete(anyString())).thenReturn(true);

        assertDoesNotThrow(() -> flowUtils.clearLoginFailure("user123"));

        verify(stringRedisTemplate).delete(anyString());
    }

    @Test
    void testClearLoginFailure_Exception() {
        when(stringRedisTemplate.delete(anyString())).thenThrow(new RuntimeException("Redis错误"));

        assertDoesNotThrow(() -> flowUtils.clearLoginFailure("user123"));
    }

    @Test
    void testGetLoginFailureCount_Success() {
        when(valueOperations.get(anyString())).thenReturn("3");

        int count = flowUtils.getLoginFailureCount("user123");

        assertEquals(3, count);
    }

    @Test
    void testGetLoginFailureCount_NoRecord() {
        when(valueOperations.get(anyString())).thenReturn(null);

        int count = flowUtils.getLoginFailureCount("user123");

        assertEquals(0, count);
    }

    @Test
    void testGetLoginFailureCount_Exception() {
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis错误"));

        int count = flowUtils.getLoginFailureCount("user123");

        assertEquals(0, count);
    }

    @Test
    void testLimitPeriodCountCheck_FirstRequest() {
        when(valueOperations.increment("period:key")).thenReturn(1L);

        boolean result = flowUtils.limitPeriodCountCheck("period:key", 20, 3);

        assertTrue(result);
        verify(stringRedisTemplate).expire("period:key", 3L, TimeUnit.SECONDS);
    }

    @Test
    void testLimitPeriodCountCheck_WithinFrequency() {
        when(valueOperations.increment("period:key")).thenReturn(15L);

        boolean result = flowUtils.limitPeriodCountCheck("period:key", 20, 3);

        assertTrue(result);
    }

    @Test
    void testLimitPeriodCountCheck_ExceedFrequency() {
        when(valueOperations.increment("period:key")).thenReturn(21L);

        boolean result = flowUtils.limitPeriodCountCheck("period:key", 20, 3);

        assertFalse(result);
    }

    @Test
    void testCheckApiLimit_Pass() {
        when(valueOperations.increment(anyString())).thenReturn(50L);

        boolean result = flowUtils.checkApiLimit("api123", 100, 60);

        assertTrue(result);
    }

    @Test
    void testCheckApiLimit_Blocked() {
        when(valueOperations.increment(anyString())).thenReturn(101L);

        boolean result = flowUtils.checkApiLimit("api123", 100, 60);

        assertFalse(result);
    }

    @Test
    void testRemoveLimit_Success() {
        when(stringRedisTemplate.delete("limit:key")).thenReturn(true);

        assertDoesNotThrow(() -> flowUtils.removeLimit("limit:key"));

        verify(stringRedisTemplate).delete("limit:key");
    }

    @Test
    void testRemoveLimit_Exception() {
        when(stringRedisTemplate.delete(anyString())).thenThrow(new RuntimeException("Redis错误"));

        assertDoesNotThrow(() -> flowUtils.removeLimit("limit:key"));
    }

    @Test
    void testGetLimitRemainTime_Success() {
        when(stringRedisTemplate.getExpire("limit:key", TimeUnit.SECONDS)).thenReturn(45L);

        long remainTime = flowUtils.getLimitRemainTime("limit:key");

        assertEquals(45L, remainTime);
    }

    @Test
    void testGetLimitRemainTime_NoExpire() {
        when(stringRedisTemplate.getExpire("limit:key", TimeUnit.SECONDS)).thenReturn(-1L);

        long remainTime = flowUtils.getLimitRemainTime("limit:key");

        assertEquals(-1L, remainTime);
    }

    @Test
    void testGetLimitRemainTime_Exception() {
        when(stringRedisTemplate.getExpire(anyString(), any(TimeUnit.class)))
                .thenThrow(new RuntimeException("Redis错误"));

        long remainTime = flowUtils.getLimitRemainTime("limit:key");

        assertEquals(-1L, remainTime);
    }
}
