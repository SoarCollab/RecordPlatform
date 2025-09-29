package cn.flying.identity.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ID工具类单元测试
 * 测试范围：雪花ID生成、ID混淆/还原、监控机制、缓存操作
 *
 * @author 王贝强
 * @create 2025-01-14
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IdUtilsTest {

    // 测试数据常量
    private static final Long TEST_ID = 123456789L;

    private static final Long TEST_USER_ID = 987654321L;

    private static final String TEST_EXTERNAL_ID = "AbCdEfGhIjKl";

    private static final String TEST_EXTERNAL_USER_ID = "UAbCdEfGhIjK";

    private static final String TEST_LOG_ID_PREFIX = "L";

    private static final String TEST_PREFIX = "TEST_";

    private static final String TEST_OBFUSCATION_KEY = "test_security_key";

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        // 设置静态字段
        ReflectionTestUtils.setField(IdUtils.class, "snowflakeIdGenerator", snowflakeIdGenerator);
        ReflectionTestUtils.setField(IdUtils.class, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(IdUtils.class, "obfuscationKey", TEST_OBFUSCATION_KEY);
        ReflectionTestUtils.setField(IdUtils.class, "idMappingExpireHours", 24);
        ReflectionTestUtils.setField(IdUtils.class, "monitorThreshold", 100);

        // 配置Redis Mock
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ==================== 用户ID生成测试 ====================

    @Test
    void testNextUserId_Success() {
        // Mock雪花ID生成器
        when(snowflakeIdGenerator.nextId()).thenReturn(TEST_USER_ID);

        // Mock Redis操作
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

        // 执行测试
        Long result = IdUtils.nextUserId();

        // 验证结果
        assertEquals(TEST_USER_ID, result);

        // 验证雪花ID生成器被调用
        verify(snowflakeIdGenerator).nextId();

        // 验证监控相关的Redis操作被调用
        verify(valueOperations).increment(contains("id:monitor:user:"));
        verify(redisTemplate).expire(anyString(), any(Duration.class));
    }

    @Test
    void testNextUserId_MonitoringThresholdExceeded() {
        // Mock雪花ID生成器
        when(snowflakeIdGenerator.nextId()).thenReturn(TEST_USER_ID);

        // Mock Redis操作 - 超过阈值
        when(valueOperations.increment(anyString())).thenReturn(150L); // 超过默认阈值100

        // 执行测试
        Long result = IdUtils.nextUserId();

        // 验证结果 - 即使超过阈值也应该成功生成ID
        assertEquals(TEST_USER_ID, result);

        // 验证监控操作被调用
        verify(valueOperations).increment(contains("id:monitor:user:"));
    }

    @Test
    void testNextUserId_MonitoringException() {
        // Mock雪花ID生成器
        when(snowflakeIdGenerator.nextId()).thenReturn(TEST_USER_ID);

        // Mock Redis操作异常
        when(valueOperations.increment(anyString()))
                .thenThrow(new RuntimeException("Redis连接失败"));

        // 执行测试 - 监控异常不应影响ID生成
        Long result = IdUtils.nextUserId();

        // 验证结果
        assertEquals(TEST_USER_ID, result);

        // 验证雪花ID生成器被调用
        verify(snowflakeIdGenerator).nextId();
    }

    // ==================== 日志ID生成测试 ====================

    @Test
    void testNextLogId_Format() {
        // 执行测试
        String result = IdUtils.nextLogId();

        // 验证结果格式
        assertNotNull(result);
        assertTrue(result.startsWith(TEST_LOG_ID_PREFIX));
        assertTrue(result.length() > 14); // L + 13位时间戳 + 4位随机数
    }

    @Test
    void testNextLogId_Uniqueness() {
        // 执行多次测试
        String id1 = IdUtils.nextLogId();
        String id2 = IdUtils.nextLogId();

        // 验证唯一性
        assertNotEquals(id1, id2);
    }

    // ==================== 实体ID生成测试 ====================

    @Test
    void testNextEntityId_Success() {
        // Mock雪花ID生成器
        when(snowflakeIdGenerator.nextId()).thenReturn(TEST_ID);

        // Mock Redis操作
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

        // 执行测试
        Long result = IdUtils.nextEntityId();

        // 验证结果
        assertEquals(TEST_ID, result);

        // 验证监控操作
        verify(valueOperations).increment(contains("id:monitor:entity:"));
    }

    @Test
    void testNextEntityIdStr_Success() {
        // Mock雪花ID生成器
        when(snowflakeIdGenerator.nextId()).thenReturn(TEST_ID);

        // Mock Redis操作
        when(valueOperations.increment(anyString())).thenReturn(1L);

        // 执行测试
        String result = IdUtils.nextEntityIdStr();

        // 验证结果
        assertEquals(TEST_ID.toString(), result);
    }

    @Test
    void testNextIdWithPrefix_Success() {
        // Mock雪花ID生成器
        when(snowflakeIdGenerator.nextId()).thenReturn(TEST_ID);

        // Mock Redis操作
        when(valueOperations.increment(anyString())).thenReturn(1L);

        // 执行测试
        String result = IdUtils.nextIdWithPrefix(TEST_PREFIX);

        // 验证结果
        assertTrue(result.startsWith(TEST_PREFIX));
        assertTrue(result.endsWith(TEST_ID.toString()));
        assertEquals(TEST_PREFIX + TEST_ID, result);
    }

    // ==================== 外部ID生成测试 ====================

    @Test
    void testToExternalId_Success() {
        // Mock Redis操作
        doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        // 执行测试
        String result = IdUtils.toExternalId(TEST_ID);

        // 验证结果
        assertNotNull(result);
        assertNotEquals(TEST_ID.toString(), result); // 确保已混淆
        assertTrue(!result.isEmpty());

        // 验证Redis缓存操作
        verify(valueOperations).set(
                startsWith("id:mapping:"),
                eq(TEST_ID.toString()),
                eq(24L),
                eq(TimeUnit.HOURS)
        );
    }

    @Test
    void testToExternalId_Null() {
        // 执行测试
        IdUtils.toExternalId(null);
        String result = null;

        // 验证结果
        assertNull(result);

        // 验证Redis操作未被调用
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void testToExternalId_RedisCacheFailed() {
        // Mock Redis操作失败
        doThrow(new RuntimeException("Redis连接失败"))
                .when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        // 执行测试 - Redis失败不应影响ID生成
        String result = IdUtils.toExternalId(TEST_ID);

        // 验证结果
        assertNotNull(result);
        assertNotEquals(TEST_ID.toString(), result);
    }

    // ==================== 外部用户ID生成测试 ====================

    @Test
    void testToExternalUserId_Success() {
        // Mock Redis操作
        doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        // 执行测试
        String result = IdUtils.toExternalUserId(TEST_USER_ID);

        // 验证结果
        assertNotNull(result);
        assertTrue(result.startsWith("U")); // 用户ID应该以U开头
        assertNotEquals(TEST_USER_ID.toString(), result); // 确保已混淆

        // 验证Redis缓存操作
        verify(valueOperations).set(
                startsWith("id:mapping:"),
                eq(TEST_USER_ID.toString()),
                eq(24L),
                eq(TimeUnit.HOURS)
        );
    }

    @Test
    void testToExternalUserId_Null() {
        // 执行测试
        IdUtils.toExternalUserId(null);
        String result = null;

        // 验证结果
        assertNull(result);

        // 验证Redis操作未被调用
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void testToExternalUserId_RedisCacheFailed() {
        // Mock Redis操作失败
        doThrow(new RuntimeException("Redis连接失败"))
                .when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        // 执行测试 - Redis失败不应影响ID生成
        String result = IdUtils.toExternalUserId(TEST_USER_ID);

        // 验证结果
        assertNotNull(result);
        assertTrue(result.startsWith("U"));
    }

    // ==================== 外部ID还原测试 ====================

    @Test
    void testFromExternalId_Success_FromCache() {
        // Mock Redis缓存命中
        when(valueOperations.get("id:mapping:" + TEST_EXTERNAL_ID))
                .thenReturn(TEST_ID.toString());

        // 执行测试
        Long result = IdUtils.fromExternalId(TEST_EXTERNAL_ID);

        // 验证结果
        assertEquals(TEST_ID, result);

        // 验证Redis操作
        verify(valueOperations).get("id:mapping:" + TEST_EXTERNAL_ID);
    }

    @Test
    void testFromExternalId_CacheMiss_SimpleFallback() {
        // Mock Redis缓存未命中
        when(valueOperations.get(anyString())).thenReturn(null);

        // 测试简单混淆格式的ID（以EX开头）
        String simpleExternalId = "EX" + (TEST_ID ^ 0x3A3A3A3AL);

        // 执行测试
        Long result = IdUtils.fromExternalId(simpleExternalId);

        // 验证结果
        assertEquals(TEST_ID, result);
    }

    @Test
    void testFromExternalId_UserIdSimpleFallback() {
        // Mock Redis缓存未命中
        when(valueOperations.get(anyString())).thenReturn(null);

        // 测试用户ID简单混淆格式的ID（以U开头）
        String simpleUserExternalId = "U" + (TEST_USER_ID ^ 0x5A5A5A5A5A5AL);

        // 执行测试
        Long result = IdUtils.fromExternalId(simpleUserExternalId);

        // 验证结果
        assertEquals(TEST_USER_ID, result);
    }

    @Test
    void testFromExternalId_Null() {
        // 执行测试
        IdUtils.fromExternalId(null);
        Long result = null;

        // 验证结果
        assertNull(result);

        // 验证Redis操作未被调用
        verify(valueOperations, never()).get(anyString());
    }

    @Test
    void testFromExternalId_Empty() {
        // 执行测试
        Long result = IdUtils.fromExternalId("");

        // 验证结果
        assertNull(result);

        // 验证Redis操作未被调用
        verify(valueOperations, never()).get(anyString());
    }

    @Test
    void testFromExternalId_InvalidCachedValue() {
        // Mock Redis缓存命中但值无效
        when(valueOperations.get(anyString())).thenReturn("invalid_number");

        // 执行测试
        Long result = IdUtils.fromExternalId(TEST_EXTERNAL_ID);

        // 验证结果
        assertNull(result);
    }

    @Test
    void testFromExternalId_RedisException() {
        // Mock Redis操作异常
        when(valueOperations.get(anyString()))
                .thenThrow(new RuntimeException("Redis连接失败"));

        // 执行测试
        Long result = IdUtils.fromExternalId(TEST_EXTERNAL_ID);

        // 验证结果
        assertNull(result);
    }

    @Test
    void testFromExternalId_UnknownFormat() {
        // Mock Redis缓存未命中
        when(valueOperations.get(anyString())).thenReturn(null);

        // 测试未知格式的外部ID
        String unknownFormatId = "UNKNOWN_FORMAT_123";

        // 执行测试
        Long result = IdUtils.fromExternalId(unknownFormatId);

        // 验证结果
        assertNull(result);
    }

    @Test
    void testFromExternalId_InvalidSimpleFormat() {
        // Mock Redis缓存未命中
        when(valueOperations.get(anyString())).thenReturn(null);

        // 测试无效的简单混淆格式（EX后面跟非数字）
        String invalidSimpleId = "EXinvalid";

        // 执行测试
        Long result = IdUtils.fromExternalId(invalidSimpleId);

        // 验证结果
        assertNull(result);
    }

    // ==================== ID混淆和还原的往返测试 ====================

    @Test
    void testIdObfuscationRoundTrip_GeneralId() {
        // Mock Redis操作
        doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(valueOperations.get(anyString())).thenReturn(TEST_ID.toString());

        // 执行往返测试
        String externalId = IdUtils.toExternalId(TEST_ID);
        Long restoredId = IdUtils.fromExternalId(externalId);

        // 验证往返结果
        assertEquals(TEST_ID, restoredId);
    }

    @Test
    void testIdObfuscationRoundTrip_UserId() {
        // Mock Redis操作
        doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(valueOperations.get(anyString())).thenReturn(TEST_USER_ID.toString());

        // 执行往返测试
        String externalUserId = IdUtils.toExternalUserId(TEST_USER_ID);
        Long restoredUserId = IdUtils.fromExternalId(externalUserId);

        // 验证往返结果
        assertEquals(TEST_USER_ID, restoredUserId);
    }

    // ==================== 监控阈值设置测试 ====================

    @Test
    void testSetMonitorThreshold() {
        // 创建IdUtils实例用于测试setter
        IdUtils idUtils = new IdUtils(snowflakeIdGenerator, redisTemplate);

        // 执行测试
        idUtils.setMonitorThreshold(200);

        // 验证静态字段被更新
        Integer threshold = (Integer) ReflectionTestUtils.getField(IdUtils.class, "monitorThreshold");
        assertEquals(200, threshold);
    }

    @Test
    void testSetObfuscationKey() {
        // 创建IdUtils实例用于测试setter
        IdUtils idUtils = new IdUtils(snowflakeIdGenerator, redisTemplate);

        // 执行测试
        String newKey = "new_security_key";
        idUtils.setObfuscationKey(newKey);

        // 验证静态字段被更新
        String key = (String) ReflectionTestUtils.getField(IdUtils.class, "obfuscationKey");
        assertEquals(newKey, key);
    }

    @Test
    void testSetIdMappingExpireHours() {
        // 创建IdUtils实例用于测试setter
        IdUtils idUtils = new IdUtils(snowflakeIdGenerator, redisTemplate);

        // 执行测试
        idUtils.setIdMappingExpireHours(48);

        // 验证静态字段被更新
        Integer expireHours = (Integer) ReflectionTestUtils.getField(IdUtils.class, "idMappingExpireHours");
        assertEquals(48, expireHours);
    }

    // ==================== 边界条件测试 ====================

    @Test
    void testIdGeneration_MultipleThreads() throws InterruptedException {
        // Mock雪花ID生成器返回不同的ID
        when(snowflakeIdGenerator.nextId())
                .thenReturn(100L, 200L, 300L, 400L, 500L);

        // Mock Redis操作
        when(valueOperations.increment(anyString())).thenReturn(1L);

        // 并发执行ID生成
        Thread[] threads = new Thread[5];
        Long[] results = new Long[5];

        for (int i = 0; i < 5; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                results[index] = IdUtils.nextEntityId();
            });
        }

        // 启动所有线程
        for (Thread thread : threads) {
            thread.start();
        }

        // 等待所有线程完成
        for (Thread thread : threads) {
            thread.join();
        }

        // 验证结果 - 所有ID都应该成功生成
        for (Long result : results) {
            assertNotNull(result);
        }

        // 验证雪花ID生成器被调用了5次
        verify(snowflakeIdGenerator, times(5)).nextId();
    }

    @Test
    void testIdGeneration_LargeNumbers() {
        // 测试大数字ID
        Long largeId = Long.MAX_VALUE - 1000;
        when(snowflakeIdGenerator.nextId()).thenReturn(largeId);

        // Mock Redis操作
        when(valueOperations.increment(anyString())).thenReturn(1L);
        doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(valueOperations.get(anyString())).thenReturn(largeId.toString());

        // 执行测试
        Long generatedId = IdUtils.nextEntityId();
        String externalId = IdUtils.toExternalId(largeId);
        Long restoredId = IdUtils.fromExternalId(externalId);

        // 验证结果
        assertEquals(largeId, generatedId);
        assertNotNull(externalId);
        assertEquals(largeId, restoredId);
    }
}