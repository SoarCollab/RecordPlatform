package cn.flying.common.util;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * IdUtils 测试类
 * 测试ID生成和转换工具的核心功能
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IdUtilsTest {

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private IdUtils idUtils;

    @BeforeEach
    void setUp() {
        // 设置静态字段
        ReflectionTestUtils.setField(IdUtils.class, "snowflakeIdGenerator", snowflakeIdGenerator);
        ReflectionTestUtils.setField(IdUtils.class, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(IdUtils.class, "obfuscationKey", "test_key");
        ReflectionTestUtils.setField(IdUtils.class, "idMappingExpireHours", 24);
        ReflectionTestUtils.setField(IdUtils.class, "monitorThreshold", 100);

        // Mock Redis操作
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("测试生成实体ID")
    void testNextEntityId() {
        // Given
        long expectedId = 123456789L;
        when(snowflakeIdGenerator.nextId()).thenReturn(expectedId);

        // When
        Long actualId = IdUtils.nextEntityId();

        // Then
        assertEquals(expectedId, actualId, "生成的实体ID应该与预期一致");
        verify(snowflakeIdGenerator, times(1)).nextId();
    }

    @Test
    @DisplayName("测试生成用户ID")
    void testNextUserId() {
        // Given
        long expectedId = 987654321L;
        when(snowflakeIdGenerator.nextId()).thenReturn(expectedId);

        // When
        Long actualId = IdUtils.nextUserId();

        // Then
        assertEquals(expectedId, actualId, "生成的用户ID应该与预期一致");
        verify(snowflakeIdGenerator, times(1)).nextId();
    }

    @Test
    @DisplayName("测试生成日志ID")
    void testNextLogId() {
        // Given
        long expectedId = 123456789L;
        when(snowflakeIdGenerator.nextId()).thenReturn(expectedId);

        // When
        String logId = IdUtils.nextLogId();

        // Then
        assertNotNull(logId, "日志ID不应为null");
        assertTrue(logId.startsWith("L"), "日志ID应该以L开头");
        assertEquals("L" + expectedId, logId, "日志ID应该是L+雪花ID");
        verify(snowflakeIdGenerator, times(1)).nextId();
    }

    @Test
    @DisplayName("测试日志ID唯一性")
    void testNextLogId_uniqueness() {
        // Given
        Set<String> logIds = new HashSet<>();
        int count = 1000;
        java.util.concurrent.atomic.AtomicLong counter = new java.util.concurrent.atomic.AtomicLong(0);
        
        // Mock snowflakeIdGenerator返回递增的唯一ID
        when(snowflakeIdGenerator.nextId()).thenAnswer(invocation -> 
                System.currentTimeMillis() + counter.incrementAndGet());

        // When
        for (int i = 0; i < count; i++) {
            logIds.add(IdUtils.nextLogId());
        }

        // Then
        assertEquals(count, logIds.size(), "生成的日志ID应该全部唯一");
    }

    @Test
    @DisplayName("测试内部ID转外部ID")
    void testToExternalId_success() {
        // Given
        Long internalId = 123456789L;
        doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any());

        // When
        String externalId = IdUtils.toExternalId(internalId);

        // Then
        assertNotNull(externalId, "外部ID不应为null");
        assertFalse(externalId.isEmpty(), "外部ID不应为空");
        assertNotEquals(internalId.toString(), externalId, "外部ID应该与内部ID不同");
        verify(valueOperations, times(1)).set(anyString(), eq(internalId.toString()), eq(24L), any());
    }

    @Test
    @DisplayName("测试null ID转换返回null")
    void testToExternalId_nullInput() {
        // When
        String externalId = IdUtils.toExternalId(null);

        // Then
        assertNull(externalId, "null输入应该返回null");
    }

    @Test
    @DisplayName("测试外部ID转内部ID - Redis缓存命中")
    void testFromExternalId_cacheHit() {
        // Given
        String externalId = "testExternalId123";
        Long expectedInternalId = 123456789L;
        when(valueOperations.get(anyString())).thenReturn(expectedInternalId.toString());

        // When
        Long actualInternalId = IdUtils.fromExternalId(externalId);

        // Then
        assertEquals(expectedInternalId, actualInternalId, "应该返回缓存的内部ID");
        verify(valueOperations, times(1)).get(anyString());
    }

    @Test
    @DisplayName("测试外部ID转内部ID - 简单混淆格式(EX前缀)")
    void testFromExternalId_simpleObfuscation() {
        // Given
        Long originalId = 123456789L;
        Long obfuscatedId = originalId ^ 0x3A3A3A3AL;
        String externalId = "EX" + obfuscatedId;
        when(valueOperations.get(anyString())).thenReturn(null); // 缓存未命中

        // When
        Long decodedId = IdUtils.fromExternalId(externalId);

        // Then
        assertEquals(originalId, decodedId, "应该能够解码简单混淆的ID");
    }

    @Test
    @DisplayName("测试外部ID转内部ID - 用户ID混淆格式(U前缀)")
    void testFromExternalId_userIdObfuscation() {
        // Given
        Long originalUserId = 987654321L;
        Long obfuscatedUserId = originalUserId ^ 0x5A5A5A5A5A5AL;
        String externalId = "U" + obfuscatedUserId;
        when(valueOperations.get(anyString())).thenReturn(null); // 缓存未命中

        // When
        Long decodedId = IdUtils.fromExternalId(externalId);

        // Then
        assertEquals(originalUserId, decodedId, "应该能够解码用户ID混淆格式");
    }

    @Test
    @DisplayName("测试外部ID转内部ID - null或空输入")
    void testFromExternalId_nullOrEmpty() {
        // When & Then
        assertNull(IdUtils.fromExternalId(null), "null输入应该返回null");
        assertNull(IdUtils.fromExternalId(""), "空字符串输入应该返回null");
    }

    @Test
    @DisplayName("测试外部ID转内部ID - 无效格式")
    void testFromExternalId_invalidFormat() {
        // Given
        String invalidExternalId = "INVALID_FORMAT_12345";
        when(valueOperations.get(anyString())).thenReturn(null);

        // When
        Long result = IdUtils.fromExternalId(invalidExternalId);

        // Then
        assertNull(result, "无效格式应该返回null");
    }

    @Test
    @DisplayName("测试用户ID转外部ID")
    void testToExternalUserId_success() {
        // Given
        Long userId = 123456789L;
        doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any());

        // When
        String externalUserId = IdUtils.toExternalUserId(userId);

        // Then
        assertNotNull(externalUserId, "外部用户ID不应为null");
        assertTrue(externalUserId.startsWith("U"), "外部用户ID应该以U开头");
        assertNotEquals(userId.toString(), externalUserId, "外部用户ID应该与内部ID不同");
        verify(valueOperations, times(1)).set(anyString(), eq(userId.toString()), eq(24L), any());
    }

    @Test
    @DisplayName("测试用户ID转外部ID - null输入")
    void testToExternalUserId_nullInput() {
        // When
        String externalUserId = IdUtils.toExternalUserId(null);

        // Then
        assertNull(externalUserId, "null输入应该返回null");
    }

    @Test
    @DisplayName("测试生成带前缀的ID")
    void testNextIdWithPrefix() {
        // Given
        String prefix = "TEST_";
        long id = 123456789L;
        when(snowflakeIdGenerator.nextId()).thenReturn(id);

        // When
        String idWithPrefix = IdUtils.nextIdWithPrefix(prefix);

        // Then
        assertEquals(prefix + id, idWithPrefix, "应该返回带前缀的ID");
        assertTrue(idWithPrefix.startsWith(prefix), "ID应该以指定前缀开头");
    }

    @Test
    @DisplayName("测试生成字符串形式的实体ID")
    void testNextEntityIdStr() {
        // Given
        long id = 123456789L;
        when(snowflakeIdGenerator.nextId()).thenReturn(id);

        // When
        String idStr = IdUtils.nextEntityIdStr();

        // Then
        assertEquals(String.valueOf(id), idStr, "应该返回字符串形式的ID");
    }

    @Test
    @DisplayName("测试Redis异常时的降级策略 - toExternalId")
    void testToExternalId_redisException() {
        // Given
        Long internalId = 123456789L;
        doThrow(new RuntimeException("Redis连接失败")).when(valueOperations)
                .set(anyString(), anyString(), anyLong(), any());

        // When
        String externalId = IdUtils.toExternalId(internalId);

        // Then
        assertNotNull(externalId, "即使Redis失败，也应该返回ID");
        assertFalse(externalId.isEmpty(), "降级生成的ID不应为空");
    }

    @Test
    @DisplayName("测试外部ID转内部ID - Redis异常")
    void testFromExternalId_redisException() {
        // Given
        String externalId = "EX123456";
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis异常"));

        // When & Then
        assertDoesNotThrow(() -> IdUtils.fromExternalId(externalId),
                "Redis异常不应该导致程序崩溃");
    }

    @Test
    @DisplayName("测试并发生成实体ID")
    void testNextEntityId_concurrent() throws InterruptedException {
        // Given
        int threadCount = 10;
        int idsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        Set<Long> allIds = Collections.synchronizedSet(new HashSet<>());
        java.util.concurrent.atomic.AtomicLong counter = new java.util.concurrent.atomic.AtomicLong(0);

        // Mock snowflakeIdGenerator返回递增的唯一ID
        when(snowflakeIdGenerator.nextId()).thenAnswer(invocation -> 
                System.currentTimeMillis() + counter.incrementAndGet());

        // When
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < idsPerThread; j++) {
                        allIds.add(IdUtils.nextEntityId());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Then
        assertEquals(threadCount * idsPerThread, allIds.size(),
                "并发生成的ID应该全部唯一");
    }

    @Test
    @DisplayName("测试外部ID和内部ID双向转换")
    void testExternalId_roundTrip() {
        // Given
        Long originalId = 123456789L;
        Map<String, String> cache = new ConcurrentHashMap<>();

        // Mock Redis存储
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            cache.put(key, value);
            return null;
        }).when(valueOperations).set(anyString(), anyString(), anyLong(), any());

        when(valueOperations.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return cache.get(key);
        });

        // When
        String externalId = IdUtils.toExternalId(originalId);
        Long decodedId = IdUtils.fromExternalId(externalId);

        // Then
        assertEquals(originalId, decodedId, "双向转换后应该得到原始ID");
    }

    @Test
    @DisplayName("测试用户ID双向转换")
    void testExternalUserId_roundTrip() {
        // Given
        Long originalUserId = 987654321L;
        Map<String, String> cache = new ConcurrentHashMap<>();

        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            cache.put(key, value);
            return null;
        }).when(valueOperations).set(anyString(), anyString(), anyLong(), any());

        when(valueOperations.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return cache.get(key);
        });

        // When
        String externalUserId = IdUtils.toExternalUserId(originalUserId);
        Long decodedUserId = IdUtils.fromExternalId(externalUserId);

        // Then
        assertEquals(originalUserId, decodedUserId, "用户ID双向转换后应该得到原始ID");
    }

    @Test
    @DisplayName("测试ID监控阈值配置")
    void testSetMonitorThreshold() {
        // Given
        IdUtils utils = new IdUtils(snowflakeIdGenerator, redisTemplate);
        int newThreshold = 200;

        // When
        utils.setMonitorThreshold(newThreshold);

        // Then
        // 验证阈值已设置（通过反射或日志验证）
        Integer threshold = (Integer) ReflectionTestUtils.getField(IdUtils.class, "monitorThreshold");
        assertEquals(newThreshold, threshold, "监控阈值应该已更新");
    }

    @Test
    @DisplayName("测试混淆密钥配置")
    void testSetObfuscationKey() {
        // Given
        IdUtils utils = new IdUtils(snowflakeIdGenerator, redisTemplate);
        String newKey = "new_secret_key";

        // When
        utils.setObfuscationKey(newKey);

        // Then
        String key = (String) ReflectionTestUtils.getField(IdUtils.class, "obfuscationKey");
        assertEquals(newKey, key, "混淆密钥应该已更新");
    }

    @Test
    @DisplayName("测试ID映射过期时间配置")
    void testSetIdMappingExpireHours() {
        // Given
        IdUtils utils = new IdUtils(snowflakeIdGenerator, redisTemplate);
        int newExpireHours = 48;

        // When
        utils.setIdMappingExpireHours(newExpireHours);

        // Then
        Integer expireHours = (Integer) ReflectionTestUtils.getField(IdUtils.class, "idMappingExpireHours");
        assertEquals(newExpireHours, expireHours, "过期时间应该已更新");
    }

    @Test
    @DisplayName("测试不同密钥生成不同的外部ID")
    void testToExternalId_differentKeys() {
        // Given
        Long internalId = 123456789L;
        doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any());

        // When
        ReflectionTestUtils.setField(IdUtils.class, "obfuscationKey", "key1");
        String externalId1 = IdUtils.toExternalId(internalId);

        ReflectionTestUtils.setField(IdUtils.class, "obfuscationKey", "key2");
        String externalId2 = IdUtils.toExternalId(internalId);

        // Then
        assertNotEquals(externalId1, externalId2,
                "不同的密钥应该生成不同的外部ID");
    }

    @Test
    @DisplayName("测试外部ID格式的安全性")
    void testToExternalId_security() {
        // Given
        Long internalId = 123456789L;
        doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any());

        // When
        String externalId = IdUtils.toExternalId(internalId);

        // Then
        // 外部ID不应该包含原始ID的任何可识别部分
        assertFalse(externalId.contains(internalId.toString()),
                "外部ID不应包含原始ID");
        // 外部ID应该使用URL安全的Base64编码
        assertTrue(externalId.matches("^[A-Za-z0-9_-]+$"),
                "外部ID应该只包含URL安全字符");
    }
}
