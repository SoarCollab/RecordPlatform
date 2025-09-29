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
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 缓存工具类单元测试
 * 测试范围：缓存基本操作、获取或设置、错误处理、过期时间管理
 *
 * @author 王贝强
 * @create 2025-01-14
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CacheUtilsTest {

    @InjectMocks
    private CacheUtils cacheUtils;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private Supplier<String> dataSupplier;

    // 测试数据常量
    private static final String TEST_KEY = "test:cache:key";
    private static final String TEST_VALUE = "test_cache_value";
    private static final String TEST_SUPPLIED_VALUE = "supplied_value";
    private static final long TEST_TIMEOUT = 3600L;
    private static final TimeUnit TEST_TIME_UNIT = TimeUnit.SECONDS;
    private static final long TEST_DELTA = 5L;

    @BeforeEach
    void setUp() {
        // 配置RedisTemplate Mock
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ==================== getOrSet方法测试 ====================

    @Test
    void testGetOrSet_CacheHit() {
        // Mock缓存命中
        when(valueOperations.get(TEST_KEY)).thenReturn(TEST_VALUE);

        // 执行测试
        String result = cacheUtils.getOrSet(TEST_KEY, dataSupplier, TEST_TIMEOUT, TEST_TIME_UNIT);

        // 验证结果
        assertEquals(TEST_VALUE, result);

        // 验证缓存被查询，但数据提供者未被调用
        verify(valueOperations).get(TEST_KEY);
        verify(dataSupplier, never()).get();
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void testGetOrSet_CacheMiss_SupplierReturnsValue() {
        // Mock缓存未命中
        when(valueOperations.get(TEST_KEY)).thenReturn(null);
        when(dataSupplier.get()).thenReturn(TEST_SUPPLIED_VALUE);

        // 执行测试
        String result = cacheUtils.getOrSet(TEST_KEY, dataSupplier, TEST_TIMEOUT, TEST_TIME_UNIT);

        // 验证结果
        assertEquals(TEST_SUPPLIED_VALUE, result);

        // 验证缓存被查询，数据提供者被调用，新值被缓存
        verify(valueOperations).get(TEST_KEY);
        verify(dataSupplier).get();
        verify(valueOperations).set(TEST_KEY, TEST_SUPPLIED_VALUE, TEST_TIMEOUT, TEST_TIME_UNIT);
    }

    @Test
    void testGetOrSet_CacheMiss_SupplierReturnsNull() {
        // Mock缓存未命中，数据提供者返回null
        when(valueOperations.get(TEST_KEY)).thenReturn(null);
        when(dataSupplier.get()).thenReturn(null);

        // 执行测试
        String result = cacheUtils.getOrSet(TEST_KEY, dataSupplier, TEST_TIMEOUT, TEST_TIME_UNIT);

        // 验证结果
        assertNull(result);

        // 验证缓存被查询，数据提供者被调用，但null值不会被缓存
        verify(valueOperations).get(TEST_KEY);
        verify(dataSupplier).get();
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void testGetOrSet_RedisException_FallbackToSupplier() {
        // Mock Redis异常
        when(valueOperations.get(TEST_KEY)).thenThrow(new RuntimeException("Redis连接失败"));
        when(dataSupplier.get()).thenReturn(TEST_SUPPLIED_VALUE);

        // 执行测试
        String result = cacheUtils.getOrSet(TEST_KEY, dataSupplier, TEST_TIMEOUT, TEST_TIME_UNIT);

        // 验证结果 - 应该回退到数据提供者
        assertEquals(TEST_SUPPLIED_VALUE, result);

        // 验证数据提供者被调用
        verify(dataSupplier).get();
    }

    @Test
    void testGetOrSet_SetCacheException() {
        // Mock缓存未命中，但设置缓存时异常
        when(valueOperations.get(TEST_KEY)).thenReturn(null);
        when(dataSupplier.get()).thenReturn(TEST_SUPPLIED_VALUE);
        doThrow(new RuntimeException("设置缓存失败"))
                .when(valueOperations).set(TEST_KEY, TEST_SUPPLIED_VALUE, TEST_TIMEOUT, TEST_TIME_UNIT);

        // 执行测试 - 设置缓存失败不应影响返回结果
        String result = cacheUtils.getOrSet(TEST_KEY, dataSupplier, TEST_TIMEOUT, TEST_TIME_UNIT);

        // 验证结果
        assertEquals(TEST_SUPPLIED_VALUE, result);

        // 验证操作被调用
        verify(valueOperations).get(TEST_KEY);
        verify(dataSupplier).get();
        verify(valueOperations).set(TEST_KEY, TEST_SUPPLIED_VALUE, TEST_TIMEOUT, TEST_TIME_UNIT);
    }

    // ==================== set方法测试 ====================

    @Test
    void testSet_Success() {
        // 执行测试
        cacheUtils.set(TEST_KEY, TEST_VALUE, TEST_TIMEOUT, TEST_TIME_UNIT);

        // 验证Redis操作被调用
        verify(valueOperations).set(TEST_KEY, TEST_VALUE, TEST_TIMEOUT, TEST_TIME_UNIT);
    }

    @Test
    void testSet_Exception() {
        // Mock Redis异常
        doThrow(new RuntimeException("设置缓存失败"))
                .when(valueOperations).set(TEST_KEY, TEST_VALUE, TEST_TIMEOUT, TEST_TIME_UNIT);

        // 执行测试 - 不应抛出异常
        assertDoesNotThrow(() -> {
            cacheUtils.set(TEST_KEY, TEST_VALUE, TEST_TIMEOUT, TEST_TIME_UNIT);
        });

        // 验证Redis操作被调用
        verify(valueOperations).set(TEST_KEY, TEST_VALUE, TEST_TIMEOUT, TEST_TIME_UNIT);
    }

    // ==================== get方法测试 ====================

    @Test
    void testGet_Success() {
        // Mock Redis返回值
        when(valueOperations.get(TEST_KEY)).thenReturn(TEST_VALUE);

        // 执行测试
        String result = cacheUtils.get(TEST_KEY);

        // 验证结果
        assertEquals(TEST_VALUE, result);

        // 验证Redis操作被调用
        verify(valueOperations).get(TEST_KEY);
    }

    @Test
    void testGet_NotFound() {
        // Mock Redis返回null
        when(valueOperations.get(TEST_KEY)).thenReturn(null);

        // 执行测试
        String result = cacheUtils.get(TEST_KEY);

        // 验证结果
        assertNull(result);

        // 验证Redis操作被调用
        verify(valueOperations).get(TEST_KEY);
    }

    @Test
    void testGet_Exception() {
        // Mock Redis异常
        when(valueOperations.get(TEST_KEY)).thenThrow(new RuntimeException("获取缓存失败"));

        // 执行测试
        String result = cacheUtils.get(TEST_KEY);

        // 验证结果 - 异常时应返回null
        assertNull(result);

        // 验证Redis操作被调用
        verify(valueOperations).get(TEST_KEY);
    }

    // ==================== delete方法测试 ====================

    @Test
    void testDelete_Success() {
        // Mock Redis删除操作
        when(redisTemplate.delete(TEST_KEY)).thenReturn(true);

        // 执行测试
        cacheUtils.delete(TEST_KEY);

        // 验证Redis操作被调用
        verify(redisTemplate).delete(TEST_KEY);
    }

    @Test
    void testDelete_Exception() {
        // Mock Redis异常
        when(redisTemplate.delete(TEST_KEY)).thenThrow(new RuntimeException("删除缓存失败"));

        // 执行测试 - 不应抛出异常
        assertDoesNotThrow(() -> {
            cacheUtils.delete(TEST_KEY);
        });

        // 验证Redis操作被调用
        verify(redisTemplate).delete(TEST_KEY);
    }

    // ==================== exists方法测试 ====================

    @Test
    void testExists_True() {
        // Mock Redis返回true
        when(redisTemplate.hasKey(TEST_KEY)).thenReturn(true);

        // 执行测试
        boolean result = cacheUtils.exists(TEST_KEY);

        // 验证结果
        assertTrue(result);

        // 验证Redis操作被调用
        verify(redisTemplate).hasKey(TEST_KEY);
    }

    @Test
    void testExists_False() {
        // Mock Redis返回false
        when(redisTemplate.hasKey(TEST_KEY)).thenReturn(false);

        // 执行测试
        boolean result = cacheUtils.exists(TEST_KEY);

        // 验证结果
        assertFalse(result);

        // 验证Redis操作被调用
        verify(redisTemplate).hasKey(TEST_KEY);
    }

    @Test
    void testExists_Null() {
        // Mock Redis返回null
        when(redisTemplate.hasKey(TEST_KEY)).thenReturn(null);

        // 执行测试
        boolean result = cacheUtils.exists(TEST_KEY);

        // 验证结果 - null应该被当作false
        assertFalse(result);

        // 验证Redis操作被调用
        verify(redisTemplate).hasKey(TEST_KEY);
    }

    @Test
    void testExists_Exception() {
        // Mock Redis异常
        when(redisTemplate.hasKey(TEST_KEY)).thenThrow(new RuntimeException("检查缓存存在性失败"));

        // 执行测试
        boolean result = cacheUtils.exists(TEST_KEY);

        // 验证结果 - 异常时应返回false
        assertFalse(result);

        // 验证Redis操作被调用
        verify(redisTemplate).hasKey(TEST_KEY);
    }

    // ==================== increment方法测试 ====================

    @Test
    void testIncrement_Success() {
        // Mock Redis递增操作
        Long expectedValue = 15L;
        when(valueOperations.increment(TEST_KEY, TEST_DELTA)).thenReturn(expectedValue);

        // 执行测试
        Long result = cacheUtils.increment(TEST_KEY, TEST_DELTA);

        // 验证结果
        assertEquals(expectedValue, result);

        // 验证Redis操作被调用
        verify(valueOperations).increment(TEST_KEY, TEST_DELTA);
    }

    @Test
    void testIncrement_Exception() {
        // Mock Redis异常
        when(valueOperations.increment(TEST_KEY, TEST_DELTA))
                .thenThrow(new RuntimeException("缓存递增操作失败"));

        // 执行测试
        Long result = cacheUtils.increment(TEST_KEY, TEST_DELTA);

        // 验证结果 - 异常时应返回null
        assertNull(result);

        // 验证Redis操作被调用
        verify(valueOperations).increment(TEST_KEY, TEST_DELTA);
    }

    // ==================== expire方法测试 ====================

    @Test
    void testExpire_Success() {
        // Mock Redis过期时间设置
        when(redisTemplate.expire(TEST_KEY, TEST_TIMEOUT, TEST_TIME_UNIT)).thenReturn(true);

        // 执行测试
        cacheUtils.expire(TEST_KEY, TEST_TIMEOUT, TEST_TIME_UNIT);

        // 验证Redis操作被调用
        verify(redisTemplate).expire(TEST_KEY, TEST_TIMEOUT, TEST_TIME_UNIT);
    }

    @Test
    void testExpire_Exception() {
        // Mock Redis异常
        when(redisTemplate.expire(TEST_KEY, TEST_TIMEOUT, TEST_TIME_UNIT))
                .thenThrow(new RuntimeException("设置缓存过期时间失败"));

        // 执行测试 - 不应抛出异常
        assertDoesNotThrow(() -> {
            cacheUtils.expire(TEST_KEY, TEST_TIMEOUT, TEST_TIME_UNIT);
        });

        // 验证Redis操作被调用
        verify(redisTemplate).expire(TEST_KEY, TEST_TIMEOUT, TEST_TIME_UNIT);
    }

    // ==================== getExpire方法测试 ====================

    @Test
    void testGetExpire_Success() {
        // Mock Redis获取过期时间
        Long expectedExpire = 1800L;
        when(redisTemplate.getExpire(TEST_KEY, TEST_TIME_UNIT)).thenReturn(expectedExpire);

        // 执行测试
        Long result = cacheUtils.getExpire(TEST_KEY, TEST_TIME_UNIT);

        // 验证结果
        assertEquals(expectedExpire, result);

        // 验证Redis操作被调用
        verify(redisTemplate).getExpire(TEST_KEY, TEST_TIME_UNIT);
    }

    @Test
    void testGetExpire_KeyNotExists() {
        // Mock Redis返回-2（键不存在）
        when(redisTemplate.getExpire(TEST_KEY, TEST_TIME_UNIT)).thenReturn(-2L);

        // 执行测试
        Long result = cacheUtils.getExpire(TEST_KEY, TEST_TIME_UNIT);

        // 验证结果
        assertEquals(-2L, result);

        // 验证Redis操作被调用
        verify(redisTemplate).getExpire(TEST_KEY, TEST_TIME_UNIT);
    }

    @Test
    void testGetExpire_KeyNeverExpires() {
        // Mock Redis返回-1（键永不过期）
        when(redisTemplate.getExpire(TEST_KEY, TEST_TIME_UNIT)).thenReturn(-1L);

        // 执行测试
        Long result = cacheUtils.getExpire(TEST_KEY, TEST_TIME_UNIT);

        // 验证结果
        assertEquals(-1L, result);

        // 验证Redis操作被调用
        verify(redisTemplate).getExpire(TEST_KEY, TEST_TIME_UNIT);
    }

    @Test
    void testGetExpire_Exception() {
        // Mock Redis异常
        when(redisTemplate.getExpire(TEST_KEY, TEST_TIME_UNIT))
                .thenThrow(new RuntimeException("获取缓存过期时间失败"));

        // 执行测试
        Long result = cacheUtils.getExpire(TEST_KEY, TEST_TIME_UNIT);

        // 验证结果 - 异常时应返回null
        assertNull(result);

        // 验证Redis操作被调用
        verify(redisTemplate).getExpire(TEST_KEY, TEST_TIME_UNIT);
    }

    // ==================== 综合场景测试 ====================

    @Test
    void testCacheLifecycle_Complete() {
        // 1. 初始检查缓存不存在
        when(redisTemplate.hasKey(TEST_KEY)).thenReturn(false);
        assertFalse(cacheUtils.exists(TEST_KEY));

        // 2. 设置缓存
        cacheUtils.set(TEST_KEY, TEST_VALUE, TEST_TIMEOUT, TEST_TIME_UNIT);
        verify(valueOperations).set(TEST_KEY, TEST_VALUE, TEST_TIMEOUT, TEST_TIME_UNIT);

        // 3. 检查缓存存在
        when(redisTemplate.hasKey(TEST_KEY)).thenReturn(true);
        assertTrue(cacheUtils.exists(TEST_KEY));

        // 4. 获取缓存值
        when(valueOperations.get(TEST_KEY)).thenReturn(TEST_VALUE);
        assertEquals(TEST_VALUE, cacheUtils.get(TEST_KEY));

        // 5. 检查过期时间
        when(redisTemplate.getExpire(TEST_KEY, TEST_TIME_UNIT)).thenReturn(TEST_TIMEOUT);
        assertEquals(TEST_TIMEOUT, cacheUtils.getExpire(TEST_KEY, TEST_TIME_UNIT));

        // 6. 删除缓存
        when(redisTemplate.delete(TEST_KEY)).thenReturn(true);
        cacheUtils.delete(TEST_KEY);
        verify(redisTemplate).delete(TEST_KEY);

        // 7. 再次检查缓存不存在
        when(redisTemplate.hasKey(TEST_KEY)).thenReturn(false);
        assertFalse(cacheUtils.exists(TEST_KEY));
    }

    @Test
    void testIncrement_MultipleOperations() {
        // 模拟多次递增操作
        when(valueOperations.increment(TEST_KEY, 1L))
                .thenReturn(1L, 2L, 3L, 4L, 5L);

        // 执行多次递增
        for (int i = 1; i <= 5; i++) {
            Long result = cacheUtils.increment(TEST_KEY, 1L);
            assertEquals(Long.valueOf(i), result);
        }

        // 验证递增操作被调用了5次
        verify(valueOperations, times(5)).increment(TEST_KEY, 1L);
    }

    @Test
    void testGetOrSet_MultipleSupplierCalls() {
        // Mock缓存未命中
        when(valueOperations.get(TEST_KEY)).thenReturn(null);

        // 模拟数据提供者每次返回不同的值
        when(dataSupplier.get())
                .thenReturn("value1")
                .thenReturn("value2")
                .thenReturn("value3");

        // 执行多次getOrSet
        String result1 = cacheUtils.getOrSet(TEST_KEY, dataSupplier, TEST_TIMEOUT, TEST_TIME_UNIT);
        String result2 = cacheUtils.getOrSet(TEST_KEY, dataSupplier, TEST_TIMEOUT, TEST_TIME_UNIT);
        String result3 = cacheUtils.getOrSet(TEST_KEY, dataSupplier, TEST_TIMEOUT, TEST_TIME_UNIT);

        // 验证结果
        assertEquals("value1", result1);
        assertEquals("value2", result2);
        assertEquals("value3", result3);

        // 验证数据提供者被调用了3次
        verify(dataSupplier, times(3)).get();
    }

    // ==================== 边界条件测试 ====================

    @Test
    void testOperations_WithNullKey() {
        // 测试null键的处理
        String nullKey = null;

        // 这些操作应该能正常执行（由Redis处理null键）
        assertDoesNotThrow(() -> {
            cacheUtils.get(nullKey);
            cacheUtils.set(nullKey, TEST_VALUE, TEST_TIMEOUT, TEST_TIME_UNIT);
            cacheUtils.delete(nullKey);
            cacheUtils.exists(nullKey);
            cacheUtils.increment(nullKey, TEST_DELTA);
            cacheUtils.expire(nullKey, TEST_TIMEOUT, TEST_TIME_UNIT);
            cacheUtils.getExpire(nullKey, TEST_TIME_UNIT);
        });
    }

    @Test
    void testOperations_WithEmptyKey() {
        // 测试空字符串键的处理
        String emptyKey = "";

        // 这些操作应该能正常执行
        assertDoesNotThrow(() -> {
            cacheUtils.get(emptyKey);
            cacheUtils.set(emptyKey, TEST_VALUE, TEST_TIMEOUT, TEST_TIME_UNIT);
            cacheUtils.delete(emptyKey);
            cacheUtils.exists(emptyKey);
            cacheUtils.increment(emptyKey, TEST_DELTA);
            cacheUtils.expire(emptyKey, TEST_TIMEOUT, TEST_TIME_UNIT);
            cacheUtils.getExpire(emptyKey, TEST_TIME_UNIT);
        });
    }

    @Test
    void testSet_WithNullValue() {
        // 测试设置null值
        assertDoesNotThrow(() -> {
            cacheUtils.set(TEST_KEY, null, TEST_TIMEOUT, TEST_TIME_UNIT);
        });

        // 验证Redis操作被调用
        verify(valueOperations).set(TEST_KEY, null, TEST_TIMEOUT, TEST_TIME_UNIT);
    }

    @Test
    void testIncrement_WithZeroDelta() {
        // Mock零增量递增
        when(valueOperations.increment(TEST_KEY, 0L)).thenReturn(10L);

        // 执行测试
        Long result = cacheUtils.increment(TEST_KEY, 0L);

        // 验证结果
        assertEquals(10L, result);

        // 验证Redis操作被调用
        verify(valueOperations).increment(TEST_KEY, 0L);
    }

    @Test
    void testIncrement_WithNegativeDelta() {
        // Mock负数递增（实际是递减）
        when(valueOperations.increment(TEST_KEY, -3L)).thenReturn(7L);

        // 执行测试
        Long result = cacheUtils.increment(TEST_KEY, -3L);

        // 验证结果
        assertEquals(7L, result);

        // 验证Redis操作被调用
        verify(valueOperations).increment(TEST_KEY, -3L);
    }
}