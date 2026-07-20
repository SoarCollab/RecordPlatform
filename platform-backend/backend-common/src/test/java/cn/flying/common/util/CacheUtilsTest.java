package cn.flying.common.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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

    @Mock
    private SetOperations<String, String> setOperations;

    @InjectMocks
    private CacheUtils cacheUtils;

    /**
     * 为缓存工具绑定字符串与哈希操作代理。
     */
    @BeforeEach
    void setUpRedisOperations() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().doReturn(hashOperations).when(stringRedisTemplate).opsForHash();
        lenient().when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
    }

    /**
     * 验证严格读取区分缺失键、合法对象和损坏 JSON。
     */
    @Test
    void takeFormCacheOrThrowShouldPreserveMissingAndRejectDamagedValues() {
        when(valueOperations.get("missing-key")).thenReturn(null);
        when(valueOperations.get("valid-key")).thenReturn("{\"value\":7}");
        when(valueOperations.get("damaged-key")).thenReturn("not-json");

        assertNull(cacheUtils.takeFormCacheOrThrow("missing-key", Value.class));
        assertEquals(new Value(7), cacheUtils.takeFormCacheOrThrow("valid-key", Value.class));
        assertThrows(RuntimeException.class,
                () -> cacheUtils.takeFormCacheOrThrow("damaged-key", Value.class));
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
     * 验证严格 SET EX 拒绝无 TTL 和无法序列化的空负载。
     */
    @Test
    void saveToCacheOrThrowShouldRejectInvalidExpiryAndNullPayload() {
        assertThrows(IllegalArgumentException.class,
                () -> cacheUtils.saveToCacheOrThrow("critical-key", new Value(1), 0L));
        assertThrows(IllegalStateException.class,
                () -> cacheUtils.saveToCacheOrThrow("critical-key", null, 60L));

        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any());
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
     * 验证 Redis 未返回 TTL 时严格读取失败关闭。
     */
    @Test
    void getExpireSecondsOrThrowShouldRejectNullRedisResult() {
        when(stringRedisTemplate.getExpire("critical-key", TimeUnit.SECONDS)).thenReturn(null);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> cacheUtils.getExpireSecondsOrThrow("critical-key"));

        assertTrue(error.getMessage().contains("读取失败"));
    }

    /**
     * 验证严格续期仅接受正 TTL 且要求 Redis 明确确认成功。
     */
    @Test
    void setExpireOrThrowShouldRequirePositiveTtlAndRedisConfirmation() {
        assertThrows(IllegalArgumentException.class,
                () -> cacheUtils.setExpireOrThrow("critical-key", 0L, TimeUnit.SECONDS));

        when(stringRedisTemplate.expire("critical-key", 60L, TimeUnit.SECONDS))
                .thenReturn(false);
        assertThrows(IllegalStateException.class,
                () -> cacheUtils.setExpireOrThrow("critical-key", 60L, TimeUnit.SECONDS));

        when(stringRedisTemplate.expire("critical-key", 120L, TimeUnit.SECONDS))
                .thenReturn(true);
        cacheUtils.setExpireOrThrow("critical-key", 120L, TimeUnit.SECONDS);
        verify(stringRedisTemplate).expire("critical-key", 120L, TimeUnit.SECONDS);
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
     * 验证严格哈希写入覆盖 JSON 与二进制两种稳定编码，并拒绝空负载。
     */
    @Test
    void hashPutOrThrowShouldEncodeSupportedValuesAndRejectNullPayload() {
        cacheUtils.hashPutOrThrow("critical-hash", "object", new Value(9));
        cacheUtils.hashPutOrThrow("critical-hash", "bytes", new byte[]{1, 2, 3});

        verify(hashOperations).put("critical-hash", "object", "{\"value\":9}");
        verify(hashOperations).put("critical-hash", "bytes", "__BYTES__:AQID");
        assertThrows(IllegalStateException.class,
                () -> cacheUtils.hashPutOrThrow("critical-hash", "null", null));
    }

    /**
     * 验证 HSETNX 包装器保留已存在结果、二进制编码和 Redis 空响应失败关闭语义。
     */
    @Test
    void hashPutIfAbsentOrThrowShouldHandleStableOutcomes() {
        when(hashOperations.putIfAbsent("critical-hash", "bytes", "__BYTES__:AQID"))
                .thenReturn(true);
        when(hashOperations.putIfAbsent("critical-hash", "existing", "value"))
                .thenReturn(false);
        when(hashOperations.putIfAbsent("critical-hash", "unknown", "value"))
                .thenReturn(null);

        assertTrue(cacheUtils.hashPutIfAbsentOrThrow(
                "critical-hash", "bytes", new byte[]{1, 2, 3}));
        assertFalse(cacheUtils.hashPutIfAbsentOrThrow(
                "critical-hash", "existing", "value"));
        assertThrows(IllegalStateException.class,
                () -> cacheUtils.hashPutIfAbsentOrThrow(
                        "critical-hash", "unknown", "value"));
        assertThrows(IllegalStateException.class,
                () -> cacheUtils.hashPutIfAbsentOrThrow(
                        "critical-hash", "null", null));
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
     * 验证严格哈希读取保持缺失字段、二进制往返和损坏编码失败关闭语义。
     */
    @Test
    void hashGetOrThrowShouldRoundTripBytesAndRejectDamagedEncoding() {
        when(hashOperations.get("critical-hash", "missing")).thenReturn(null);
        when(hashOperations.get("critical-hash", "bytes")).thenReturn("__BYTES__:AQID");
        when(hashOperations.get("critical-hash", "damaged")).thenReturn("__BYTES__:%%%");

        assertNull(cacheUtils.hashGetOrThrow("critical-hash", "missing", byte[].class));
        assertArrayEquals(new byte[]{1, 2, 3},
                cacheUtils.hashGetOrThrow("critical-hash", "bytes", byte[].class));
        assertThrows(IllegalArgumentException.class,
                () -> cacheUtils.hashGetOrThrow("critical-hash", "damaged", byte[].class));
    }

    /**
     * 验证条件删除只在 Lua 明确删除时返回成功，并拒绝空结果或无效期望值。
     */
    @Test
    void hashDeleteIfValueMatchesOrThrowShouldMapLuaOutcomes() {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class), eq(List.of("critical-hash")),
                eq("field-removed"), eq("expected")))
                .thenReturn(1L);
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class), eq(List.of("critical-hash")),
                eq("field-kept"), eq("expected")))
                .thenReturn(0L);
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class), eq(List.of("critical-hash")),
                eq("field-unknown"), eq("expected")))
                .thenReturn(null);

        assertTrue(cacheUtils.hashDeleteIfValueMatchesOrThrow(
                "critical-hash", "field-removed", "expected"));
        assertFalse(cacheUtils.hashDeleteIfValueMatchesOrThrow(
                "critical-hash", "field-kept", "expected"));
        assertThrows(IllegalStateException.class,
                () -> cacheUtils.hashDeleteIfValueMatchesOrThrow(
                        "critical-hash", "field-unknown", "expected"));
        assertThrows(IllegalStateException.class,
                () -> cacheUtils.hashDeleteIfValueMatchesOrThrow(
                        "critical-hash", "field-null", null));
    }

    /**
     * 验证关键哈希批量写入同时保存 JSON 与二进制，并拒绝包含空负载的批次。
     */
    @Test
    void hashPutAllOrThrowShouldEncodeWholeBatchOrRejectIt() {
        Map<String, Object> values = Map.of(
                "object", new Value(11),
                "bytes", new byte[]{1, 2, 3});

        cacheUtils.hashPutAllOrThrow("critical-hash", values);

        verify(hashOperations).putAll("critical-hash", Map.of(
                "object", "{\"value\":11}",
                "bytes", "__BYTES__:AQID"));
        cacheUtils.hashPutAllOrThrow("critical-hash", Map.of());
        cacheUtils.hashPutAllOrThrow("critical-hash", null);
        assertThrows(IllegalStateException.class,
                () -> cacheUtils.hashPutAllOrThrow(
                        "critical-hash", java.util.Collections.singletonMap("null", null)));
    }

    /**
     * 验证关键集合写入保留 Redis 计数，并将空响应视为未知失败。
     */
    @Test
    void setAddOrThrowShouldRequireExplicitRedisCount() {
        when(setOperations.add("critical-set", "a", "b")).thenReturn(2L);
        when(setOperations.add("unknown-set", "a")).thenReturn(null);

        assertEquals(2L, cacheUtils.setAddOrThrow("critical-set", "a", "b"));
        assertThrows(IllegalStateException.class,
                () -> cacheUtils.setAddOrThrow("unknown-set", "a"));
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
        assertTrue(script.contains("redis.call('TYPE', KEYS[1])"));
        assertTrue(script.contains("redis.call('TYPE', KEYS[2])"));
        assertTrue(script.contains("HSETNX"));
        assertTrue(script.indexOf("TYPE") < script.indexOf("HSETNX"));
        assertTrue(script.indexOf("HSETNX") < script.indexOf("SADD"));
    }

    /**
     * 验证分片证据包装器将 Set/Hash 类型污染映射为明确的失败关闭异常。
     */
    @Test
    void atomicChunkEvidenceShouldRejectCorruptedRedisKeyTypes() {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class), eq(List.of("uploaded", "hashes")),
                eq("0"), eq("chunk_0"), eq("hash")))
                .thenReturn(-2L, -3L);

        IllegalStateException setTypeError = assertThrows(
                IllegalStateException.class,
                () -> cacheUtils.atomicAddToSetAndHash(
                        "uploaded", "0", "hashes", "chunk_0", "hash"));
        assertTrue(setTypeError.getMessage().contains("Set 键类型损坏"));

        IllegalStateException hashTypeError = assertThrows(
                IllegalStateException.class,
                () -> cacheUtils.atomicAddToSetAndHash(
                        "uploaded", "0", "hashes", "chunk_0", "hash"));
        assertTrue(hashTypeError.getMessage().contains("Hash 键类型损坏"));
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
     * 验证暂停脚本逐项拒绝缺失身份、终态集合、时间和辅助键。
     */
    @Test
    void atomicPauseTransitionShouldValidateEverySafetyInput() {
        List<Executable> invalidCalls = List.of(
                () -> pauseWith(null, "paused", "paused-at", "activity", "session", List.of("done"), 1L, 1L, List.of()),
                () -> pauseWith("state", " ", "paused-at", "activity", "session", List.of("done"), 1L, 1L, List.of()),
                () -> pauseWith("state", "paused", " ", "activity", "session", List.of("done"), 1L, 1L, List.of()),
                () -> pauseWith("state", "paused", "paused-at", " ", "session", List.of("done"), 1L, 1L, List.of()),
                () -> pauseWith("state", "paused", "paused-at", "activity", " ", List.of("done"), 1L, 1L, List.of()),
                () -> pauseWith("state", "paused", "paused-at", "activity", "session", null, 1L, 1L, List.of()),
                () -> pauseWith("state", "paused", "paused-at", "activity", "session", List.of(), 1L, 1L, List.of()),
                () -> pauseWith("state", "paused", "paused-at", "activity", "session", List.of(" "), 1L, 1L, List.of()),
                () -> pauseWith("state", "paused", "paused-at", "activity", "session", List.of("done"), 0L, 1L, List.of()),
                () -> pauseWith("state", "paused", "paused-at", "activity", "session", List.of("done"), 1L, 0L, List.of()),
                () -> pauseWith("state", "paused", "paused-at", "activity", "session", List.of("done"), 1L, 1L, null),
                () -> pauseWith("state", "paused", "paused-at", "activity", "session", List.of("done"), 1L, 1L, List.of(" ")));

        invalidCalls.forEach(call -> {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, call);
            assertTrue(error.getMessage().contains("参数无效"));
        });
        verify(stringRedisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), any());
    }

    /**
     * 验证暂停脚本对 Redis 空响应和未知返回码均失败关闭。
     */
    @Test
    void atomicPauseTransitionShouldRejectAmbiguousLuaOutcomes() {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class), eq(List.of("state", "paused", "paused-at", "activity")),
                eq("session"), eq("1"), eq("60"), eq("done")))
                .thenReturn(null, 7L);

        assertThrows(IllegalStateException.class,
                () -> pauseWith("state", "paused", "paused-at", "activity", "session",
                        List.of("done"), 1L, 60L, List.of()));
        assertThrows(IllegalStateException.class,
                () -> pauseWith("state", "paused", "paused-at", "activity", "session",
                        List.of("done"), 1L, 60L, List.of()));
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
     * 验证活动续期公共执行器拒绝空响应、损坏状态和未知返回码。
     */
    @Test
    void atomicActivityTouchShouldFailClosedForAmbiguousLuaOutcomes() {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class), eq(List.of("state", "activity")),
                eq("session"), eq("1"), eq("60"), eq("done")))
                .thenReturn(null, -2L, 3L);

        assertThrows(IllegalStateException.class,
                () -> touchWith("state", "activity", "session", List.of("done"), 1L, 60L, List.of()));
        assertThrows(IllegalStateException.class,
                () -> touchWith("state", "activity", "session", List.of("done"), 1L, 60L, List.of()));
        assertThrows(IllegalStateException.class,
                () -> touchWith("state", "activity", "session", List.of("done"), 1L, 60L, List.of()));
    }

    /**
     * 验证活动续期逐项拒绝缺失身份、终态集合、时间和辅助键。
     */
    @Test
    void atomicActivityTouchShouldValidateEverySafetyInput() {
        List<Executable> invalidCalls = List.of(
                () -> touchWith(null, "activity", "session", List.of("done"), 1L, 1L, List.of()),
                () -> touchWith("state", " ", "session", List.of("done"), 1L, 1L, List.of()),
                () -> touchWith("state", "activity", " ", List.of("done"), 1L, 1L, List.of()),
                () -> touchWith("state", "activity", "session", null, 1L, 1L, List.of()),
                () -> touchWith("state", "activity", "session", List.of(), 1L, 1L, List.of()),
                () -> touchWith("state", "activity", "session", List.of(" "), 1L, 1L, List.of()),
                () -> touchWith("state", "activity", "session", List.of("done"), 0L, 1L, List.of()),
                () -> touchWith("state", "activity", "session", List.of("done"), 1L, 0L, List.of()),
                () -> touchWith("state", "activity", "session", List.of("done"), 1L, 1L, null),
                () -> touchWith("state", "activity", "session", List.of("done"), 1L, 1L, List.of(" ")));

        invalidCalls.forEach(call -> assertThrows(IllegalArgumentException.class, call));
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
     * 验证恢复脚本拒绝缺失暂停键及超出协议的 Lua 返回码。
     */
    @Test
    void atomicResumeShouldValidatePauseKeysAndRejectUnknownOutcome() {
        assertThrows(IllegalArgumentException.class,
                () -> cacheUtils.atomicResumeSessionIfJsonStatusAllowed(
                        "state", " ", "paused-at", "activity", "session",
                        List.of("done"), 1L, 60L, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> cacheUtils.atomicResumeSessionIfJsonStatusAllowed(
                        "state", "paused", " ", "activity", "session",
                        List.of("done"), 1L, 60L, List.of()));

        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(List.of("state", "paused", "paused-at", "activity")),
                eq("session"), eq("1"), eq("60"), eq("done")))
                .thenReturn(3L);
        assertThrows(IllegalStateException.class,
                () -> cacheUtils.atomicResumeSessionIfJsonStatusAllowed(
                        "state", "paused", "paused-at", "activity", "session",
                        List.of("done"), 1L, 60L, List.of()));
    }

    /**
     * 验证分片证据 Lua 包装器仅接受明确成功结果。
     */
    @Test
    void atomicChunkEvidenceShouldRejectNullAndIncompleteOutcomes() {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class), eq(List.of("uploaded", "hashes")),
                eq("0"), eq("chunk_0"), eq("hash")))
                .thenReturn(null, 0L, 1L);

        assertThrows(IllegalStateException.class,
                () -> cacheUtils.atomicAddToSetAndHash(
                        "uploaded", "0", "hashes", "chunk_0", "hash"));
        assertThrows(IllegalStateException.class,
                () -> cacheUtils.atomicAddToSetAndHash(
                        "uploaded", "0", "hashes", "chunk_0", "hash"));
        assertTrue(cacheUtils.atomicAddToSetAndHash(
                "uploaded", "0", "hashes", "chunk_0", "hash"));
    }

    /**
     * 以统一参数调用暂停脚本，便于逐项验证安全边界。
     */
    private long pauseWith(
            String stateKey,
            String setKey,
            String pausedAtKey,
            String activityAtKey,
            String member,
            List<String> forbiddenStatuses,
            long timestamp,
            long ttlSeconds,
            List<String> auxiliaryKeys
    ) {
        return cacheUtils.atomicPauseSessionIfJsonStatusAllowed(
                stateKey, setKey, pausedAtKey, activityAtKey, member,
                forbiddenStatuses, timestamp, ttlSeconds, auxiliaryKeys);
    }

    /**
     * 以统一参数调用活动续期脚本，便于逐项验证安全边界。
     */
    private long touchWith(
            String stateKey,
            String activityAtKey,
            String member,
            List<String> forbiddenStatuses,
            long timestamp,
            long ttlSeconds,
            List<String> auxiliaryKeys
    ) {
        return cacheUtils.atomicTouchSessionIfJsonStatusAllowed(
                stateKey, activityAtKey, member, forbiddenStatuses,
                timestamp, ttlSeconds, auxiliaryKeys);
    }

    /**
     * 提供稳定 JSON 形态的测试数据。
     */
    private record Value(int value) {
    }
}
