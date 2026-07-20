package cn.flying.common.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证关键缓存写入不会吞掉 Redis 故障。
 */
@ExtendWith(MockitoExtension.class)
class CacheUtilsTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private HashOperations<String, String, String> hashOperations;

    @InjectMocks
    private CacheUtils cacheUtils;

    /**
     * 为缓存工具绑定字符串与哈希操作代理。
     */
    @BeforeEach
    void setUpRedisOperations() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().doReturn(hashOperations).when(stringRedisTemplate).opsForHash();
    }

    /**
     * 验证关键 SET EX 写入将底层异常直接暴露给调用方。
     */
    @Test
    void saveToCacheOrThrowShouldPropagateRedisFailure() {
        doThrow(new IllegalStateException("redis unavailable"))
                .when(valueOperations)
                .set("critical-key", "{\"value\":1}", 300L, TimeUnit.SECONDS);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> cacheUtils.saveToCacheOrThrow("critical-key", new Value(1), 300L));

        assertEquals("redis unavailable", error.getMessage());
    }

    /**
     * 验证关键写入使用单次带 TTL 的 SET 命令。
     */
    @Test
    void saveToCacheOrThrowShouldUseAtomicSetWithExpiry() {
        cacheUtils.saveToCacheOrThrow("critical-key", new Value(1), 300L);

        verify(valueOperations).set(
                "critical-key", "{\"value\":1}", 300L, TimeUnit.SECONDS);
    }

    /**
     * 验证 TTL 读取保留 Redis 的缺失键等哨兵值供上层失败关闭。
     */
    @Test
    void getExpireSecondsOrThrowShouldReturnRedisTtl() {
        when(stringRedisTemplate.getExpire("critical-key", TimeUnit.SECONDS)).thenReturn(-2L);

        assertEquals(-2L, cacheUtils.getExpireSecondsOrThrow("critical-key"));
    }

    /**
     * 验证关键哈希写入将底层异常直接暴露给调用方。
     */
    @Test
    void hashPutOrThrowShouldPropagateRedisFailure() {
        byte[] keyData = new byte[]{1, 2, 3};
        doThrow(new IllegalStateException("hash unavailable"))
                .when(hashOperations)
                .put("chunk-keys", "chunk_0", "__BYTES__:AQID");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> cacheUtils.hashPutOrThrow("chunk-keys", "chunk_0", keyData));

        assertEquals("hash unavailable", error.getMessage());
    }

    /**
     * 验证字符串哈希以原始值完成 HSETNX 与读取往返，不引入 JSON 双引号。
     */
    @Test
    void stringHashShouldRoundTripWithoutJsonQuotes() {
        String hash = "a".repeat(43);
        when(hashOperations.putIfAbsent("chunk-hashes", "chunk_0", hash))
                .thenReturn(true);
        when(hashOperations.get("chunk-hashes", "chunk_0")).thenReturn(hash);

        assertTrue(cacheUtils.hashPutIfAbsentOrThrow(
                "chunk-hashes", "chunk_0", hash));
        assertEquals(hash, cacheUtils.hashGetOrThrow(
                "chunk-hashes", "chunk_0", String.class));

        verify(hashOperations).putIfAbsent("chunk-hashes", "chunk_0", hash);
    }

    /**
     * 验证原子上传证据脚本以 HSETNX 固化哈希，且异值冲突不会执行后续 SADD。
     */
    @Test
    void atomicChunkEvidenceShouldRejectConflictingStableHashBeforeSetAdd() {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(List.of("uploaded", "hashes")),
                eq("0"), eq("chunk_0"), eq("\"new-hash\"")))
                .thenReturn(-1L);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> cacheUtils.atomicAddToSetAndHash(
                        "uploaded", "0", "hashes", "chunk_0", "\"new-hash\""));

        assertTrue(error.getMessage().contains("不同稳定值"));
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<DefaultRedisScript> scriptCaptor =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(stringRedisTemplate).execute(
                scriptCaptor.capture(),
                eq(List.of("uploaded", "hashes")),
                eq("0"), eq("chunk_0"), eq("\"new-hash\""));
        String script = scriptCaptor.getValue().getScriptAsString();
        assertTrue(script.contains("HSETNX"));
        assertTrue(script.indexOf("HSETNX") < script.indexOf("SADD"));
    }

    /**
     * 验证暂停脚本在同一原子操作中校验会话对象、续期证据并记录暂停时间。
     */
    @Test
    void atomicPauseTransitionShouldFenceTerminalStateBeforeSetAdd() {
        List<String> forbiddenStatuses = List.of(
                "completed",
                "finalization_manual_reconciliation_required",
                "cleanup_manual_required");
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(List.of(
                        "session-state", "paused-sessions", "paused-at", "activity-at", "uploaded")),
                eq("session-1"),
                eq("123456789"),
                eq("86400"),
                eq("completed"),
                eq("finalization_manual_reconciliation_required"),
                eq("cleanup_manual_required")))
                .thenReturn(0L);

        long result = cacheUtils.atomicPauseSessionIfJsonStatusAllowed(
                "session-state",
                "paused-sessions",
                "paused-at",
                "activity-at",
                "session-1",
                forbiddenStatuses,
                123456789L,
                86400L,
                List.of("uploaded"));

        assertEquals(0L, result);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<DefaultRedisScript> scriptCaptor =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(stringRedisTemplate).execute(
                scriptCaptor.capture(),
                eq(List.of(
                        "session-state", "paused-sessions", "paused-at", "activity-at", "uploaded")),
                eq("session-1"),
                eq("123456789"),
                eq("86400"),
                eq("completed"),
                eq("finalization_manual_reconciliation_required"),
                eq("cleanup_manual_required"));
        String script = scriptCaptor.getValue().getScriptAsString();
        assertTrue(script.contains("redis.call('GET', KEYS[1])"));
        assertTrue(script.contains("pcall(cjson.decode, payload)"));
        assertTrue(script.contains("type(status) ~= 'string'"));
        assertTrue(script.contains("type(clientId) ~= 'string'"));
        assertTrue(script.contains("clientId ~= ARGV[1]"));
        assertTrue(script.indexOf("for i = 4, #ARGV") < script.indexOf("redis.call('SADD'"));
        assertTrue(script.contains("redis.call('SET', KEYS[3], ARGV[2], 'EX', ARGV[3])"));
        assertTrue(script.contains("redis.call('SET', KEYS[4], ARGV[2], 'EX', ARGV[3])"));
        assertTrue(script.contains("redis.call('EXPIRE', KEYS[1], ARGV[3])"));
    }

    /**
     * 验证损坏的 JSON 主状态不能被当作活跃会话继续写入暂停索引。
     */
    @Test
    void atomicPauseTransitionShouldFailClosedForDamagedState() {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(List.of("session-state", "paused-sessions", "paused-at", "activity-at")),
                eq("session-1"),
                eq("123456789"),
                eq("86400"),
                eq("completed")))
                .thenReturn(-2L);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> cacheUtils.atomicPauseSessionIfJsonStatusAllowed(
                        "session-state",
                        "paused-sessions",
                        "paused-at",
                        "activity-at",
                        "session-1",
                        List.of("completed"),
                        123456789L,
                        86400L,
                        List.of()));

        assertTrue(error.getMessage().contains("JSON 主状态损坏"));
    }

    /**
     * 验证活动续期只写独立时间证据，不会用迟到快照覆盖主 JSON。
     */
    @Test
    void atomicActivityTouchShouldNotRewriteMainState() {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(List.of("session-state", "activity-at", "uploaded")),
                eq("session-1"), eq("123456789"), eq("86400"), eq("completed")))
                .thenReturn(1L);

        assertEquals(1L, cacheUtils.atomicTouchSessionIfJsonStatusAllowed(
                "session-state",
                "activity-at",
                "session-1",
                List.of("completed"),
                123456789L,
                86400L,
                List.of("uploaded")));

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<DefaultRedisScript> scriptCaptor =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(stringRedisTemplate).execute(
                scriptCaptor.capture(),
                eq(List.of("session-state", "activity-at", "uploaded")),
                eq("session-1"), eq("123456789"), eq("86400"), eq("completed"));
        String script = scriptCaptor.getValue().getScriptAsString();
        assertTrue(script.contains("redis.call('EXPIRE', KEYS[1], ARGV[3])"));
        assertTrue(script.contains("redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])"));
        assertTrue(!script.contains("redis.call('SET', KEYS[1]"));
    }

    /**
     * 验证恢复脚本在同一 Redis 原子操作内删除暂停成员和 pause-at，再写活动证据。
     */
    @Test
    void atomicResumeShouldKeepPauseMembershipAndTimestampPaired() {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(List.of(
                        "session-state", "paused-sessions", "paused-at", "activity-at", "uploaded")),
                eq("session-1"), eq("123456789"), eq("86400"), eq("completed")))
                .thenReturn(2L);

        assertEquals(2L, cacheUtils.atomicResumeSessionIfJsonStatusAllowed(
                "session-state",
                "paused-sessions",
                "paused-at",
                "activity-at",
                "session-1",
                List.of("completed"),
                123456789L,
                86400L,
                List.of("uploaded")));

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<DefaultRedisScript> scriptCaptor =
                ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(stringRedisTemplate).execute(
                scriptCaptor.capture(),
                eq(List.of(
                        "session-state", "paused-sessions", "paused-at", "activity-at", "uploaded")),
                eq("session-1"), eq("123456789"), eq("86400"), eq("completed"));
        String script = scriptCaptor.getValue().getScriptAsString();
        assertTrue(script.contains("redis.call('SREM', KEYS[2], ARGV[1])"));
        assertTrue(script.contains("redis.call('DEL', KEYS[3])"));
        assertTrue(script.indexOf("redis.call('SREM', KEYS[2]")
                < script.indexOf("redis.call('SET', KEYS[4]"));
    }

    /**
     * 提供稳定 JSON 形态的测试数据。
     */
    private record Value(int value) {
    }
}
