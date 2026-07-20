package cn.flying.service.assistant;

import cn.flying.common.util.CacheUtils;
import cn.flying.common.util.JsonConverter;
import cn.flying.dao.vo.file.FileUploadState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 验证上传状态 Redis 管理器的索引闭环与人工对账 TTL 合同。
 */
@ExtendWith(MockitoExtension.class)
class FileUploadRedisStateManagerTest {

    private static final long SESSION_TTL_SECONDS = TimeUnit.DAYS.toSeconds(1);
    private static final String CHUNK_HASH_A = "A".repeat(43);
    private static final String CHUNK_HASH_B = "B".repeat(43);

    @Mock
    private CacheUtils cacheUtils;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock stateLock;

    @InjectMocks
    private FileUploadRedisStateManager stateManager;

    /**
     * 为每个测试提供可成功获取的会话状态锁。
     */
    @BeforeEach
    void setUpStateLock() throws InterruptedException {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(stateLock);
        lenient().when(stateLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        lenient().when(cacheUtils.hashPutIfAbsentOrThrow(
                anyString(), anyString(), any())).thenReturn(true);
        lenient().when(cacheUtils.atomicTouchSessionIfJsonStatusAllowed(
                anyString(), anyString(), anyString(), anyList(),
                anyLong(), anyLong(), anyList())).thenReturn(1L);
    }

    /**
     * 验证状态键先过期时只清理 active/paused 索引，辅助证据保留到自身 TTL 自然到期。
     */
    @Test
    void removeSessionShouldClearIndexesWhenStateAlreadyExpired() {
        String sessionId = "orphan-session";
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:" + sessionId,
                FileUploadState.class)).thenReturn(null);

        stateManager.removeSession(sessionId, "");

        verify(cacheUtils).deleteCache("file:upload:paused_at:" + sessionId);
        verify(cacheUtils, never()).deleteCache("file:upload:uploaded_chunks:" + sessionId);
        verify(cacheUtils).setRemove("file:upload:active_sessions", sessionId);
        verify(cacheUtils).setRemove("file:upload:paused_sessions", sessionId);
        verify(cacheUtils, never()).hashDelete(eq("file:upload:filename_suid_mapping"), anyString());
    }

    /**
     * 验证活跃主状态与暂停索引由同一个 Redis 条件脚本完成转换。
     */
    @Test
    void addPausedSessionShouldUseAtomicStatusFencedTransition() {
        when(cacheUtils.atomicPauseSessionIfJsonStatusAllowed(
                eq("file:upload:session:pause-session"),
                eq("file:upload:paused_sessions"),
                eq("file:upload:paused_at:pause-session"),
                eq("file:upload:activity_at:pause-session"),
                eq("pause-session"),
                anyList(),
                anyLong(),
                eq(TimeUnit.DAYS.toSeconds(1)),
                anyList())).thenReturn(1L);

        FileUploadRedisStateManager.PauseTransitionResult result =
                stateManager.addPausedSession("pause-session");

        assertEquals(FileUploadRedisStateManager.PauseTransitionResult.PAUSED, result);
        verify(cacheUtils).atomicPauseSessionIfJsonStatusAllowed(
                eq("file:upload:session:pause-session"),
                eq("file:upload:paused_sessions"),
                eq("file:upload:paused_at:pause-session"),
                eq("file:upload:activity_at:pause-session"),
                eq("pause-session"),
                eq(List.of(
                        "completed",
                        "finalization_manual_reconciliation_required",
                        "cleanup_manual_required")),
                anyLong(),
                eq(TimeUnit.DAYS.toSeconds(1)),
                eq(List.of(
                        "file:upload:uploaded_chunks:pause-session",
                        "file:upload:processed_chunks:pause-session",
                        "file:upload:chunk_hashes:pause-session",
                        "file:upload:chunk_keys:pause-session")));
        verify(cacheUtils, never()).setAddOrThrow(anyString(), anyString());
    }

    /**
     * 验证终态与缺失主状态由原子脚本显式返回，服务层无需依赖锁后旧快照判断。
     */
    @Test
    void addPausedSessionShouldExposeTerminalAndMissingFenceResults() {
        when(cacheUtils.atomicPauseSessionIfJsonStatusAllowed(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyList(),
                anyLong(), anyLong(), anyList()))
                .thenReturn(0L, -1L);

        assertEquals(
                FileUploadRedisStateManager.PauseTransitionResult.TERMINAL,
                stateManager.addPausedSession("terminal-session"));
        assertEquals(
                FileUploadRedisStateManager.PauseTransitionResult.SESSION_NOT_FOUND,
                stateManager.addPausedSession("missing-session"));
    }

    /**
     * 用闩锁模拟 pause 持有的 watchdog 锁失效后迟到：completed 先落 Redis 时，旧 pause 必须失败。
     */
    @Test
    void latePauseAfterWatchdogLossShouldFailWhenCompletedStateWins() throws Exception {
        String sessionId = "late-pause-session";
        FileUploadState initial = directState(sessionId);
        AtomicReference<FileUploadState> redisState = new AtomicReference<>(copyState(initial));
        AtomicLong redisTtl = new AtomicLong(TimeUnit.DAYS.toSeconds(1));
        AtomicBoolean paused = new AtomicBoolean(false);
        CountDownLatch pauseReadyForAtomicTransition = new CountDownLatch(1);
        CountDownLatch allowLatePause = new CountDownLatch(1);
        configureStateBackingStore(sessionId, redisState, redisTtl);
        when(cacheUtils.atomicPauseSessionIfJsonStatusAllowed(
                eq("file:upload:session:" + sessionId),
                eq("file:upload:paused_sessions"),
                eq("file:upload:paused_at:" + sessionId),
                eq("file:upload:activity_at:" + sessionId),
                eq(sessionId),
                anyList(),
                anyLong(),
                anyLong(),
                anyList())).thenAnswer(invocation -> {
                    pauseReadyForAtomicTransition.countDown();
                    assertTrue(allowLatePause.await(5, TimeUnit.SECONDS));
                    if ("completed".equalsIgnoreCase(redisState.get().getStatus())) {
                        return 0L;
                    }
                    paused.set(true);
                    return 1L;
                });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var latePause = executor.submit(() -> stateManager.addPausedSession(sessionId));
            assertTrue(pauseReadyForAtomicTransition.await(5, TimeUnit.SECONDS));

            stateManager.markCompleted(sessionId, "encoded-user", 300);
            allowLatePause.countDown();

            assertEquals(
                    FileUploadRedisStateManager.PauseTransitionResult.TERMINAL,
                    latePause.get(5, TimeUnit.SECONDS));
            assertFalse(paused.get());
            assertEquals("completed", redisState.get().getStatus());
            verify(cacheUtils).setRemove("file:upload:paused_sessions", sessionId);
        } finally {
            allowLatePause.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * 验证恢复会话由同一个 Lua 原子删除暂停成员和 pause-at，并刷新活动证据。
     */
    @Test
    void removePausedSessionShouldUseAtomicResumeTransition() {
        when(cacheUtils.atomicResumeSessionIfJsonStatusAllowed(
                eq("file:upload:session:pause-session"),
                eq("file:upload:paused_sessions"),
                eq("file:upload:paused_at:pause-session"),
                eq("file:upload:activity_at:pause-session"),
                eq("pause-session"),
                anyList(), anyLong(), anyLong(), anyList()))
                .thenReturn(2L);

        assertTrue(stateManager.removePausedSession("pause-session"));
        verify(cacheUtils, never()).setRemove("file:upload:paused_sessions", "pause-session");
        verify(cacheUtils, never()).deleteCache("file:upload:paused_at:pause-session");
    }

    /**
     * 验证未暂停会话恢复时仍完成活动续期，但返回原本未暂停。
     */
    @Test
    void removePausedSessionShouldReturnFalseWhenAtomicResumeFindsNoMember() {
        when(cacheUtils.atomicResumeSessionIfJsonStatusAllowed(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyList(), anyLong(), anyLong(), anyList()))
                .thenReturn(1L);

        assertFalse(stateManager.removePausedSession("pause-session"));
    }

    /**
     * 验证暂停清理以 pause-at 为活动时间下限，并兼容历史会话缺少该键。
     */
    @Test
    void pauseAwareActivityShouldPreferPauseTimestampAndFallbackForLegacyState() {
        FileUploadState state = directState("pause-activity-session");
        state.setLastActivityTime(100L);
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:activity_at:pause-activity-session", Long.class))
                .thenReturn(null);
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:paused_at:pause-activity-session", Long.class))
                .thenReturn(200L)
                .thenReturn(null);

        assertEquals(200L, stateManager.getPauseAwareLastActivityTime(state));
        assertEquals(100L, stateManager.getPauseAwareLastActivityTime(state));
    }

    /**
     * 验证活动续期在完成态之后才线性化时不会重写主 JSON 或延长完成态 TTL。
     */
    @Test
    void lateActivityTouchShouldNotResurrectCompletedState() throws Exception {
        String sessionId = "late-activity-session";
        FileUploadState initial = directState(sessionId);
        AtomicReference<FileUploadState> redisState = new AtomicReference<>(copyState(initial));
        AtomicLong redisTtl = new AtomicLong(TimeUnit.DAYS.toSeconds(1));
        CountDownLatch touchReady = new CountDownLatch(1);
        CountDownLatch allowTouch = new CountDownLatch(1);
        configureStateBackingStore(sessionId, redisState, redisTtl);
        when(cacheUtils.atomicTouchSessionIfJsonStatusAllowed(
                eq("file:upload:session:" + sessionId),
                eq("file:upload:activity_at:" + sessionId),
                eq(sessionId),
                anyList(), anyLong(), anyLong(), anyList()))
                .thenAnswer(invocation -> {
                    touchReady.countDown();
                    assertTrue(allowTouch.await(5, TimeUnit.SECONDS));
                    return "completed".equalsIgnoreCase(redisState.get().getStatus()) ? 0L : 1L;
                });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var lateTouch = executor.submit(() -> stateManager.updateLastActivityTime(sessionId));
            assertTrue(touchReady.await(5, TimeUnit.SECONDS));

            stateManager.markCompleted(sessionId, "encoded-user", 300L);
            allowTouch.countDown();
            lateTouch.get(5, TimeUnit.SECONDS);

            assertEquals("completed", redisState.get().getStatus());
            assertEquals(300L, redisTtl.get());
        } finally {
            allowTouch.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * 验证人工对账使用专用 TTL 写入且不再经过默认 updateState 覆盖。
     */
    @Test
    void retainManualStateShouldPreserveBoundedDiagnosticTtlAndExitIndexes() {
        long diagnosticTtl = TimeUnit.DAYS.toSeconds(7);
        FileUploadState state = new FileUploadState(
                100L,
                "evidence.pdf",
                1024L,
                "application/pdf",
                "manual-session",
                1024,
                1);
        state.setSuid("encoded-user");
        state.setTenantId(77L);
        when(cacheUtils.hasKey(startsWith("file:upload:"))).thenReturn(true);
        when(cacheUtils.getExpireSecondsOrThrow(startsWith("file:upload:")))
                .thenReturn(diagnosticTtl);
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:manual-session",
                FileUploadState.class)).thenReturn(state);
        when(cacheUtils.getExpireSecondsOrThrow(
                "file:upload:session:manual-session")).thenReturn(diagnosticTtl);

        stateManager.retainManualReconciliationState(
                state,
                "finalization_manual_reconciliation_required",
                diagnosticTtl);

        assertEquals("finalization_manual_reconciliation_required", state.getStatus());
        verify(cacheUtils).saveToCacheOrThrow(
                "file:upload:session:manual-session",
                state,
                diagnosticTtl);
        verify(cacheUtils).setRemove("file:upload:active_sessions", "manual-session");
        verify(cacheUtils).setRemove("file:upload:paused_sessions", "manual-session");
        verify(cacheUtils).hashDeleteIfValueMatchesOrThrow(
                "file:upload:filename_suid_mapping",
                "77:12:evidence.pdf:12:encoded-user",
                "manual-session");
        verify(cacheUtils).setExpireOrThrow(
                "file:upload:uploaded_chunks:manual-session", diagnosticTtl, TimeUnit.SECONDS);
        verify(cacheUtils).setExpireOrThrow(
                "file:upload:processed_chunks:manual-session", diagnosticTtl, TimeUnit.SECONDS);
        verify(cacheUtils).setExpireOrThrow(
                "file:upload:chunk_hashes:manual-session", diagnosticTtl, TimeUnit.SECONDS);
        verify(cacheUtils).setExpireOrThrow(
                "file:upload:chunk_keys:manual-session", diagnosticTtl, TimeUnit.SECONDS);
    }

    /**
     * 验证最后分片持有的旧快照在最终化完成后迟到写入时，不能覆盖检查点或 completed TTL。
     */
    @Test
    void staleProgressSnapshotShouldNotOverwriteCompletedFinalization() throws Exception {
        FileUploadState initial = directState("race-session");
        AtomicReference<FileUploadState> redisState = new AtomicReference<>(copyState(initial));
        AtomicLong redisTtl = new AtomicLong(TimeUnit.DAYS.toSeconds(1));
        configureStateBackingStore("race-session", redisState, redisTtl);

        FileUploadState staleProgress = copyState(initial);
        staleProgress.setLastActivityTime(initial.getLastActivityTime() + 10);
        FileUploadState finalization = copyState(initial);
        finalization.setPrepareStored(true);
        finalization.setPreparedFileId(7001L);
        finalization.setContentHash(
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        finalization.setDirectFinalizationStage("MANIFEST_STORED");
        finalization.setDirectFileId(7001L);
        finalization.setDirectFileHash("chain-file-hash");
        finalization.setDirectTransactionHash("tx-hash");
        finalization.setDirectManifestHash("manifest-hash");

        CountDownLatch staleSnapshotCaptured = new CountDownLatch(1);
        CountDownLatch allowStaleWrite = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var staleWrite = executor.submit(() -> {
                staleSnapshotCaptured.countDown();
                assertTrue(allowStaleWrite.await(5, TimeUnit.SECONDS));
                stateManager.updateState(staleProgress);
                return null;
            });
            assertTrue(staleSnapshotCaptured.await(5, TimeUnit.SECONDS));

            stateManager.updateState(finalization);
            stateManager.markCompleted("race-session", "encoded-user", 300);
            allowStaleWrite.countDown();
            staleWrite.get(5, TimeUnit.SECONDS);

            FileUploadState persisted = redisState.get();
            assertEquals("completed", persisted.getStatus());
            assertEquals("MANIFEST_STORED", persisted.getDirectFinalizationStage());
            assertEquals(7001L, persisted.getPreparedFileId());
            assertEquals("manifest-hash", persisted.getDirectManifestHash());
            assertEquals(300L, redisTtl.get());
        } finally {
            allowStaleWrite.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * 验证人工对账状态写入七天 TTL 后，活动时间刷新和旧对象更新都不会缩回默认二十四小时。
     */
    @Test
    void manualReconciliationTtlShouldSurviveLateActivityAndStateUpdates() {
        long diagnosticTtl = TimeUnit.DAYS.toSeconds(7);
        FileUploadState initial = directState("manual-race-session");
        FileUploadState stale = copyState(initial);
        AtomicReference<FileUploadState> redisState = new AtomicReference<>(copyState(initial));
        AtomicLong redisTtl = new AtomicLong(TimeUnit.DAYS.toSeconds(1));
        configureStateBackingStore("manual-race-session", redisState, redisTtl);

        stateManager.retainManualReconciliationState(
                initial,
                "finalization_manual_reconciliation_required",
                diagnosticTtl);
        stateManager.updateLastActivityTime("manual-race-session");
        stateManager.updateState(stale);

        assertEquals("finalization_manual_reconciliation_required", redisState.get().getStatus());
        assertEquals(diagnosticTtl, redisTtl.get());
    }

    /**
     * 验证状态键已经过期后，迟到的整对象更新不能重建会话或重新加入活跃生命周期。
     */
    @Test
    void staleUpdateShouldNotRecreateExpiredSession() {
        FileUploadState stale = directState("expired-session");
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:expired-session",
                FileUploadState.class)).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> stateManager.updateState(stale));

        verify(cacheUtils, never()).saveToCacheOrThrow(
                eq("file:upload:session:expired-session"), any(FileUploadState.class), anyLong());
        verify(cacheUtils).setRemove("file:upload:active_sessions", "expired-session");
        verify(cacheUtils).setRemove("file:upload:paused_sessions", "expired-session");
    }

    /**
     * 验证底层缓存静默丢弃 completed 写入时必须失败关闭，且不得先移除生命周期索引。
     */
    @Test
    void markCompletedShouldFailWhenCacheWriteIsSilentlyDropped() {
        FileUploadState initial = directState("dropped-completion-session");
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:dropped-completion-session",
                FileUploadState.class)).thenAnswer(invocation -> copyState(initial));

        assertThrows(
                IllegalStateException.class,
                () -> stateManager.markCompleted(
                        "dropped-completion-session", "encoded-user", 300));

        verify(cacheUtils).saveToCacheOrThrow(
                eq("file:upload:session:dropped-completion-session"),
                any(FileUploadState.class),
                eq(300L));
        verify(cacheUtils, never()).setRemove(
                "file:upload:active_sessions", "dropped-completion-session");
        verify(cacheUtils, never()).hashDelete(
                eq("file:upload:filename_suid_mapping"), anyString());
    }

    /**
     * 验证主状态已写成 completed 但辅助 TTL 刷新失败时，重复调用会补齐 TTL 并退出调度索引。
     */
    @Test
    void markCompletedRetryShouldConvergeAfterAuxiliaryTtlFailure() {
        String sessionId = "partial-completion-session";
        String uploadedKey = "file:upload:uploaded_chunks:" + sessionId;
        FileUploadState initial = directState(sessionId);
        AtomicReference<FileUploadState> redisState = new AtomicReference<>(copyState(initial));
        AtomicLong redisTtl = new AtomicLong(TimeUnit.DAYS.toSeconds(1));
        configureStateBackingStore(sessionId, redisState, redisTtl);
        when(cacheUtils.hasKey(uploadedKey)).thenReturn(true);
        when(cacheUtils.getExpireSecondsOrThrow(uploadedKey)).thenReturn(300L);
        doThrow(new IllegalStateException("expire unavailable"))
                .doNothing()
                .when(cacheUtils)
                .setExpireOrThrow(uploadedKey, 300L, TimeUnit.SECONDS);

        assertThrows(
                IllegalStateException.class,
                () -> stateManager.markCompleted(sessionId, "encoded-user", 300));

        assertEquals("completed", redisState.get().getStatus());
        assertEquals(300L, redisTtl.get());
        verify(cacheUtils, never()).setRemove("file:upload:active_sessions", sessionId);
        clearInvocations(cacheUtils);

        stateManager.markCompleted(sessionId, "encoded-user", 300);

        verify(cacheUtils).setExpireOrThrow(uploadedKey, 300L, TimeUnit.SECONDS);
        verify(cacheUtils).setRemove("file:upload:active_sessions", sessionId);
        verify(cacheUtils).setRemove("file:upload:paused_sessions", sessionId);
    }

    /**
     * 验证最终化检查点静默丢写时不能继续刷新辅助进度键或进入后续不可逆步骤。
     */
    @Test
    void finalizationCheckpointShouldFailWhenCacheWriteIsSilentlyDropped() {
        FileUploadState initial = directState("dropped-checkpoint-session");
        FileUploadState checkpoint = copyState(initial);
        checkpoint.setPrepareStored(true);
        checkpoint.setPreparedFileId(7002L);
        checkpoint.setContentHash(
                "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        checkpoint.setDirectFinalizationStage("PREPARE_STORED");
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:dropped-checkpoint-session",
                FileUploadState.class)).thenAnswer(invocation -> copyState(initial));

        assertThrows(IllegalStateException.class, () -> stateManager.updateState(checkpoint));

        verify(cacheUtils).saveToCacheOrThrow(
                eq("file:upload:session:dropped-checkpoint-session"),
                any(FileUploadState.class),
                eq(TimeUnit.DAYS.toSeconds(1)));
        verify(cacheUtils, never()).setAddIntegers(anyString(), any());
    }

    /**
     * 验证旧人工态载荷相同但 SET EX 失败时仍失败关闭且不清理索引。
     */
    @Test
    void manualStateShouldFailWhenSamePayloadTtlExtensionWriteFails() {
        long diagnosticTtl = TimeUnit.DAYS.toSeconds(7);
        FileUploadState manual = directState("manual-write-failure-session");
        manual.setStatus("finalization_manual_reconciliation_required");
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:manual-write-failure-session",
                FileUploadState.class)).thenReturn(copyState(manual));
        doThrow(new IllegalStateException("redis write failed"))
                .when(cacheUtils)
                .saveToCacheOrThrow(
                        eq("file:upload:session:manual-write-failure-session"),
                        any(FileUploadState.class),
                        eq(diagnosticTtl));

        assertThrows(
                IllegalStateException.class,
                () -> stateManager.retainManualReconciliationState(
                        manual,
                        "finalization_manual_reconciliation_required",
                        diagnosticTtl));

        verify(cacheUtils, never()).setRemove(
                "file:upload:active_sessions", "manual-write-failure-session");
        verify(cacheUtils, never()).hashDelete(
                eq("file:upload:filename_suid_mapping"), anyString());
    }

    /**
     * 验证相同人工态载荷仍会把短 TTL 原子延长到七天并完成 TTL 回读校验。
     */
    @Test
    void manualStateShouldExtendSamePayloadShortTtlToSevenDays() {
        long diagnosticTtl = TimeUnit.DAYS.toSeconds(7);
        FileUploadState manual = directState("manual-ttl-extension-session");
        manual.setStatus("finalization_manual_reconciliation_required");
        AtomicReference<FileUploadState> redisState = new AtomicReference<>(copyState(manual));
        AtomicLong redisTtl = new AtomicLong(300L);
        configureStateBackingStore("manual-ttl-extension-session", redisState, redisTtl);

        stateManager.retainManualReconciliationState(
                manual,
                "finalization_manual_reconciliation_required",
                diagnosticTtl);

        assertEquals(diagnosticTtl, redisTtl.get());
        verify(cacheUtils).getExpireSecondsOrThrow(
                "file:upload:session:manual-ttl-extension-session");
    }

    /**
     * 验证分片密钥底层哈希写异常会阻断调用链。
     */
    @Test
    void addChunkKeyShouldPropagateHashWriteFailure() {
        byte[] keyData = new byte[]{1, 2, 3};
        AtomicReference<FileUploadState> redisState =
                new AtomicReference<>(directState("key-write-session"));
        AtomicLong redisTtl = new AtomicLong(TimeUnit.DAYS.toSeconds(1));
        configureStateBackingStore("key-write-session", redisState, redisTtl);
        doThrow(new IllegalStateException("hash write failed"))
                .when(cacheUtils)
                .hashPutIfAbsentOrThrow(
                        "file:upload:chunk_keys:key-write-session", "0", keyData);

        assertThrows(
                IllegalStateException.class,
                () -> stateManager.addChunkKey("key-write-session", 0, keyData));

        verify(cacheUtils).hashGetOrThrow(
                "file:upload:chunk_keys:key-write-session", "0", byte[].class);
    }

    /**
     * 验证分片密钥静默丢写会被逐字节回读校验识别。
     */
    @Test
    void addChunkKeyShouldFailWhenHashWriteIsSilentlyDropped() {
        byte[] keyData = new byte[]{1, 2, 3};
        AtomicReference<FileUploadState> redisState =
                new AtomicReference<>(directState("key-drop-session"));
        AtomicLong redisTtl = new AtomicLong(TimeUnit.DAYS.toSeconds(1));
        configureStateBackingStore("key-drop-session", redisState, redisTtl);
        when(cacheUtils.hashGetOrThrow(
                "file:upload:chunk_keys:key-drop-session", "0", byte[].class))
                .thenReturn(null);

        assertThrows(
                IllegalStateException.class,
                () -> stateManager.addChunkKey("key-drop-session", 0, keyData));

        verify(cacheUtils).hashPutIfAbsentOrThrow(
                "file:upload:chunk_keys:key-drop-session", "0", keyData);
    }

    /**
     * 验证迟到快照不得用同分片的不同哈希覆盖 Redis 主状态权威证据。
     */
    @Test
    void staleUpdateShouldFailClosedOnConflictingChunkHash() {
        FileUploadState current = directState("hash-conflict-session");
        current.getChunkHashes().put("chunk_0", "stable-hash");
        FileUploadState stale = copyState(current);
        stale.getChunkHashes().put("chunk_0", "different-hash");
        AtomicReference<FileUploadState> redisState =
                new AtomicReference<>(copyState(current));
        AtomicLong redisTtl = new AtomicLong(TimeUnit.DAYS.toSeconds(1));
        configureStateBackingStore("hash-conflict-session", redisState, redisTtl);

        assertThrows(IllegalStateException.class, () -> stateManager.updateState(stale));

        assertEquals("stable-hash", redisState.get().getChunkHashes().get("chunk_0"));
        verify(cacheUtils, never()).saveToCacheOrThrow(
                eq("file:upload:session:hash-conflict-session"),
                any(FileUploadState.class),
                anyLong());
    }

    /**
     * 验证迟到快照不得用同分片的不同密钥覆盖 Redis 主状态权威证据。
     */
    @Test
    void staleUpdateShouldFailClosedOnConflictingChunkKey() {
        FileUploadState current = directState("key-conflict-session");
        current.getKeys().put(0, new byte[]{1, 2, 3});
        FileUploadState stale = copyState(current);
        stale.getKeys().put(0, new byte[]{9, 8, 7});
        AtomicReference<FileUploadState> redisState =
                new AtomicReference<>(copyState(current));
        AtomicLong redisTtl = new AtomicLong(TimeUnit.DAYS.toSeconds(1));
        configureStateBackingStore("key-conflict-session", redisState, redisTtl);

        assertThrows(IllegalStateException.class, () -> stateManager.updateState(stale));

        assertTrue(java.util.Arrays.equals(
                new byte[]{1, 2, 3}, redisState.get().getKeys().get(0)));
        verify(cacheUtils, never()).saveToCacheOrThrow(
                eq("file:upload:session:key-conflict-session"),
                any(FileUploadState.class),
                anyLong());
    }

    /**
     * 验证主状态新增哈希时，辅助 hash 中既有不同值不会被 updateState 批量覆盖。
     */
    @Test
    void updateStateShouldFailClosedOnConflictingAuxiliaryChunkHash() {
        FileUploadState current = directState("aux-hash-conflict-session");
        FileUploadState update = copyState(current);
        update.getChunkHashes().put("chunk_0", "new-hash");
        AtomicReference<FileUploadState> redisState =
                new AtomicReference<>(copyState(current));
        AtomicLong redisTtl = new AtomicLong(TimeUnit.DAYS.toSeconds(1));
        configureStateBackingStore("aux-hash-conflict-session", redisState, redisTtl);
        when(cacheUtils.hashPutIfAbsentOrThrow(
                "file:upload:chunk_hashes:aux-hash-conflict-session", "chunk_0", "new-hash"))
                .thenReturn(false);
        when(cacheUtils.hashGetOrThrow(
                "file:upload:chunk_hashes:aux-hash-conflict-session", "chunk_0", String.class))
                .thenReturn("stable-hash");

        assertThrows(IllegalStateException.class, () -> stateManager.updateState(update));

        verify(cacheUtils, never()).hashPutAllOrThrow(
                eq("file:upload:chunk_hashes:aux-hash-conflict-session"), anyMap());
    }

    /**
     * 验证主状态新增密钥时，辅助 hash 中既有不同字节不会被 updateState 批量覆盖。
     */
    @Test
    void updateStateShouldFailClosedOnConflictingAuxiliaryChunkKey() {
        FileUploadState current = directState("aux-key-conflict-session");
        FileUploadState update = copyState(current);
        byte[] expected = new byte[]{1, 2, 3};
        update.getKeys().put(0, expected);
        AtomicReference<FileUploadState> redisState =
                new AtomicReference<>(copyState(current));
        AtomicLong redisTtl = new AtomicLong(TimeUnit.DAYS.toSeconds(1));
        configureStateBackingStore("aux-key-conflict-session", redisState, redisTtl);
        when(cacheUtils.hashPutIfAbsentOrThrow(
                "file:upload:chunk_keys:aux-key-conflict-session", "0", expected))
                .thenReturn(false);
        when(cacheUtils.hashGetOrThrow(
                "file:upload:chunk_keys:aux-key-conflict-session", "0", byte[].class))
                .thenReturn(new byte[]{9, 8, 7});

        assertThrows(IllegalStateException.class, () -> stateManager.updateState(update));

        verify(cacheUtils, never()).hashPutAllOrThrow(
                eq("file:upload:chunk_keys:aux-key-conflict-session"), anyMap());
    }

    /**
     * 验证辅助 hash JSON 损坏时读取失败关闭且保留全部 Redis 现场。
     */
    @Test
    void getStateShouldFailClosedOnCorruptAuxiliaryChunkHashJson() {
        FileUploadState current = directState("corrupt-aux-session");
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:corrupt-aux-session",
                FileUploadState.class)).thenReturn(current);
        when(cacheUtils.hashGetAll(
                "file:upload:chunk_hashes:corrupt-aux-session"))
                .thenReturn(Map.of("chunk_0", "not-json"));

        assertThrows(
                IllegalStateException.class,
                () -> stateManager.getState("corrupt-aux-session"));

        verify(cacheUtils, never()).deleteCache(anyString());
        verify(cacheUtils, never()).setRemove(anyString(), anyString());
    }

    /**
     * 验证辅助 Hash 中合法的 JSON 字符串会先反序列化，再按 SHA-256 Base64URL 合同校验。
     */
    @Test
    void getStateShouldAcceptSerializedAuxiliaryChunkHash() {
        String sessionId = "valid-aux-session";
        String chunkHash = "A".repeat(43);
        FileUploadState current = directState(sessionId);
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:" + sessionId,
                FileUploadState.class)).thenReturn(current);
        when(cacheUtils.hashGetAll(
                "file:upload:chunk_hashes:" + sessionId))
                .thenReturn(Map.of("chunk_0", JsonConverter.toJson(chunkHash)));

        FileUploadState restored = stateManager.getState(sessionId);

        assertEquals(chunkHash, restored.getChunkHashes().get("chunk_0"));
    }

    /**
     * 验证整对象更新时 uploaded Set 写故障会直接失败，不能静默接受辅助证据丢写。
     */
    @Test
    void updateStateShouldPropagateStrictUploadedSetWriteFailure() {
        FileUploadState current = directState("set-write-failure-session");
        FileUploadState update = copyState(current);
        update.getUploadedChunks().add(0);
        AtomicReference<FileUploadState> redisState =
                new AtomicReference<>(copyState(current));
        AtomicLong redisTtl = new AtomicLong(TimeUnit.DAYS.toSeconds(1));
        configureStateBackingStore("set-write-failure-session", redisState, redisTtl);
        doThrow(new IllegalStateException("set unavailable"))
                .when(cacheUtils)
                .setAddOrThrow(
                        "file:upload:uploaded_chunks:set-write-failure-session", "0");

        assertThrows(IllegalStateException.class, () -> stateManager.updateState(update));

        verify(cacheUtils, never()).setExpireOrThrow(
                "file:upload:uploaded_chunks:set-write-failure-session",
                TimeUnit.DAYS.toSeconds(1),
                TimeUnit.SECONDS);
    }

    /**
     * 验证辅助 uploaded 集合中的越界分片索引会使状态读取失败关闭。
     */
    @Test
    void getStateShouldRejectOutOfRangeAuxiliaryChunkIndex() {
        FileUploadState current = directState("out-of-range-session");
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:out-of-range-session",
                FileUploadState.class)).thenReturn(current);
        when(cacheUtils.getIntegerSet(
                "file:upload:uploaded_chunks:out-of-range-session"))
                .thenReturn(Set.of(1));

        assertThrows(
                IllegalStateException.class,
                () -> stateManager.getState("out-of-range-session"));

        verify(cacheUtils, never()).deleteCache(anyString());
    }

    /**
     * 验证辅助密钥解码故障必须由严格读取直接抛出，不能回退 null 后继续。
     */
    @Test
    void getChunkKeysShouldPropagateStrictAuxiliaryDecodeFailure() {
        FileUploadState current = directState("corrupt-key-session");
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:corrupt-key-session",
                FileUploadState.class)).thenReturn(current);
        when(cacheUtils.hashGetAll("file:upload:chunk_keys:corrupt-key-session"))
                .thenReturn(Map.of("0", "corrupt"));
        doThrow(new IllegalStateException("invalid bytes"))
                .when(cacheUtils)
                .hashGetOrThrow(
                        "file:upload:chunk_keys:corrupt-key-session", "0", byte[].class);

        assertThrows(
                IllegalStateException.class,
                () -> stateManager.getChunkKeys("corrupt-key-session"));

        verify(cacheUtils, never()).hashGet(
                "file:upload:chunk_keys:corrupt-key-session", "0", byte[].class);
    }

    /**
     * 验证新建传统上传会话会先持久化主状态，再闭合 active 索引和恢复映射。
     */
    @Test
    void saveNewStateShouldPersistLifecycleAndResumeMapping() {
        String sessionId = "new-legacy-session";
        String stateKey = "file:upload:session:" + sessionId;
        String mappingField = "77:12:evidence.pdf:12:encoded-user";
        FileUploadState state = legacyState(sessionId, 1);
        AtomicReference<FileUploadState> redisState = new AtomicReference<>();
        AtomicLong redisTtl = new AtomicLong();
        AtomicBoolean active = new AtomicBoolean(false);
        configureStateBackingStore(sessionId, redisState, redisTtl);
        when(cacheUtils.setAddOrThrow("file:upload:active_sessions", sessionId))
                .thenAnswer(invocation -> {
                    active.set(true);
                    return 1L;
                });
        when(cacheUtils.setIsMember("file:upload:active_sessions", sessionId))
                .thenAnswer(invocation -> active.get());
        when(cacheUtils.hashGetOrThrow(
                "file:upload:filename_suid_mapping", mappingField, String.class))
                .thenReturn(sessionId);

        stateManager.saveNewState(state, "encoded-user");

        assertEquals(sessionId, redisState.get().getClientId());
        assertEquals(SESSION_TTL_SECONDS, redisTtl.get());
        assertTrue(active.get());
        verify(cacheUtils).saveToCacheOrThrow(stateKey, state, SESSION_TTL_SECONDS);
        verify(cacheUtils).hashPutIfAbsentOrThrow(
                "file:upload:filename_suid_mapping", mappingField, sessionId);
    }

    /**
     * 验证重复会话 ID 在任何索引写入前失败关闭，防止覆盖现有恢复状态。
     */
    @Test
    void saveNewStateShouldRejectDuplicateSessionBeforeLifecycleWrites() {
        FileUploadState existing = directState("duplicate-session");
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:duplicate-session", FileUploadState.class))
                .thenReturn(existing);

        assertThrows(
                IllegalStateException.class,
                () -> stateManager.saveNewState(copyState(existing), "encoded-user"));

        verify(cacheUtils, never()).saveToCacheOrThrow(anyString(), any(), anyLong());
        verify(cacheUtils, never()).setAddOrThrow(anyString(), anyString());
    }

    /**
     * 验证恢复映射写入未落地时，仅在严格回读确认本次主状态后回滚全部新会话证据。
     */
    @Test
    void saveNewStateShouldRollbackVerifiedCreationWhenResumeMappingIsLost() {
        String sessionId = "rollback-new-session";
        String stateKey = "file:upload:session:" + sessionId;
        FileUploadState state = legacyState(sessionId, 1);
        AtomicReference<FileUploadState> redisState = new AtomicReference<>();
        AtomicLong redisTtl = new AtomicLong();
        configureStateBackingStore(sessionId, redisState, redisTtl);
        when(cacheUtils.setIsMember("file:upload:active_sessions", sessionId))
                .thenReturn(true);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> stateManager.saveNewState(state, "encoded-user"));

        assertTrue(failure.getMessage().contains("映射写入后校验失败"));
        verify(cacheUtils).setRemove("file:upload:active_sessions", sessionId);
        verify(cacheUtils).deleteCache("file:upload:uploaded_chunks:" + sessionId);
        verify(cacheUtils).deleteCache("file:upload:processed_chunks:" + sessionId);
        verify(cacheUtils).deleteCache("file:upload:chunk_hashes:" + sessionId);
        verify(cacheUtils).deleteCache("file:upload:chunk_keys:" + sessionId);
        verify(cacheUtils).deleteCache(stateKey);
    }

    /**
     * 验证原子分片证据写入成功后会刷新两个 TTL、回读组合证据并续期活动时间。
     */
    @Test
    void addUploadedChunkWithHashShouldPersistAndVerifyCompleteEvidence() {
        String sessionId = "uploaded-evidence-session";
        String uploadedKey = "file:upload:uploaded_chunks:" + sessionId;
        String hashesKey = "file:upload:chunk_hashes:" + sessionId;
        AtomicReference<FileUploadState> redisState =
                new AtomicReference<>(directState(sessionId));
        AtomicLong redisTtl = new AtomicLong(SESSION_TTL_SECONDS);
        AtomicBoolean member = new AtomicBoolean(false);
        AtomicReference<String> persistedHash = new AtomicReference<>();
        configureStateBackingStore(sessionId, redisState, redisTtl);
        when(cacheUtils.setIsMember(uploadedKey, "0"))
                .thenAnswer(invocation -> member.get());
        when(cacheUtils.hashGetOrThrow(hashesKey, "chunk_0", String.class))
                .thenAnswer(invocation -> persistedHash.get());
        when(cacheUtils.atomicAddToSetAndHash(
                uploadedKey,
                "0",
                hashesKey,
                "chunk_0",
                JsonConverter.toJson(CHUNK_HASH_A)))
                .thenAnswer(invocation -> {
                    member.set(true);
                    persistedHash.set(CHUNK_HASH_A);
                    return true;
                });
        when(cacheUtils.getExpireSecondsOrThrow(uploadedKey))
                .thenReturn(SESSION_TTL_SECONDS);
        when(cacheUtils.getExpireSecondsOrThrow(hashesKey))
                .thenReturn(SESSION_TTL_SECONDS);

        assertTrue(stateManager.addUploadedChunkWithHash(sessionId, 0, CHUNK_HASH_A));

        assertTrue(member.get());
        assertEquals(CHUNK_HASH_A, persistedHash.get());
        verify(cacheUtils).setExpireOrThrow(
                uploadedKey, SESSION_TTL_SECONDS, TimeUnit.SECONDS);
        verify(cacheUtils).setExpireOrThrow(
                hashesKey, SESSION_TTL_SECONDS, TimeUnit.SECONDS);
        verify(cacheUtils).atomicTouchSessionIfJsonStatusAllowed(
                eq("file:upload:session:" + sessionId),
                eq("file:upload:activity_at:" + sessionId),
                eq(sessionId),
                anyList(),
                anyLong(),
                eq(SESSION_TTL_SECONDS),
                anyList());
    }

    /**
     * 验证 Lua 响应异常但 Set 与 Hash 均已精确提交时可安全收敛为成功。
     */
    @Test
    void addUploadedChunkWithHashShouldAcceptUncertainResponseAfterExactReadback() {
        String sessionId = "uncertain-uploaded-session";
        String uploadedKey = "file:upload:uploaded_chunks:" + sessionId;
        String hashesKey = "file:upload:chunk_hashes:" + sessionId;
        AtomicReference<FileUploadState> redisState =
                new AtomicReference<>(directState(sessionId));
        AtomicLong redisTtl = new AtomicLong(SESSION_TTL_SECONDS);
        AtomicBoolean member = new AtomicBoolean(false);
        AtomicReference<String> persistedHash = new AtomicReference<>();
        configureStateBackingStore(sessionId, redisState, redisTtl);
        when(cacheUtils.setIsMember(uploadedKey, "0"))
                .thenAnswer(invocation -> member.get());
        when(cacheUtils.hashGetOrThrow(hashesKey, "chunk_0", String.class))
                .thenAnswer(invocation -> persistedHash.get());
        when(cacheUtils.atomicAddToSetAndHash(
                eq(uploadedKey), eq("0"), eq(hashesKey), eq("chunk_0"), anyString()))
                .thenAnswer(invocation -> {
                    member.set(true);
                    persistedHash.set(CHUNK_HASH_A);
                    throw new IllegalStateException("redis response lost");
                });
        when(cacheUtils.getExpireSecondsOrThrow(uploadedKey))
                .thenReturn(SESSION_TTL_SECONDS);
        when(cacheUtils.getExpireSecondsOrThrow(hashesKey))
                .thenReturn(SESSION_TTL_SECONDS);

        assertTrue(stateManager.addUploadedChunkWithHash(sessionId, 0, CHUNK_HASH_A));

        verify(cacheUtils).atomicAddToSetAndHash(
                uploadedKey,
                "0",
                hashesKey,
                "chunk_0",
                JsonConverter.toJson(CHUNK_HASH_A));
    }

    /**
     * 验证 Lua 明确失败且组合证据均未提交时传播原始失败，不进入 TTL 或活动续期。
     */
    @Test
    void addUploadedChunkWithHashShouldFailWhenAtomicEvidenceIsAbsent() {
        String sessionId = "absent-uploaded-session";
        String uploadedKey = "file:upload:uploaded_chunks:" + sessionId;
        String hashesKey = "file:upload:chunk_hashes:" + sessionId;
        AtomicReference<FileUploadState> redisState =
                new AtomicReference<>(directState(sessionId));
        AtomicLong redisTtl = new AtomicLong(SESSION_TTL_SECONDS);
        configureStateBackingStore(sessionId, redisState, redisTtl);
        when(cacheUtils.atomicAddToSetAndHash(
                eq(uploadedKey), eq("0"), eq(hashesKey), eq("chunk_0"), anyString()))
                .thenReturn(false);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> stateManager.addUploadedChunkWithHash(sessionId, 0, CHUNK_HASH_A));

        assertTrue(failure.getMessage().contains("原子分片证据写入返回失败"));
        verify(cacheUtils, never()).setExpireOrThrow(
                eq(uploadedKey), anyLong(), any(TimeUnit.class));
        verify(cacheUtils, never()).atomicTouchSessionIfJsonStatusAllowed(
                anyString(), anyString(), anyString(), anyList(), anyLong(), anyLong(), anyList());
    }

    /**
     * 验证原子分片证据只提交 Set 时识别为部分提交并失败关闭。
     */
    @Test
    void addUploadedChunkWithHashShouldRejectPartialAtomicEvidence() {
        String sessionId = "partial-uploaded-session";
        String uploadedKey = "file:upload:uploaded_chunks:" + sessionId;
        String hashesKey = "file:upload:chunk_hashes:" + sessionId;
        AtomicReference<FileUploadState> redisState =
                new AtomicReference<>(directState(sessionId));
        AtomicLong redisTtl = new AtomicLong(SESSION_TTL_SECONDS);
        configureStateBackingStore(sessionId, redisState, redisTtl);
        when(cacheUtils.setIsMember(uploadedKey, "0"))
                .thenReturn(false, true);
        when(cacheUtils.atomicAddToSetAndHash(
                eq(uploadedKey), eq("0"), eq(hashesKey), eq("chunk_0"), anyString()))
                .thenReturn(false);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> stateManager.addUploadedChunkWithHash(sessionId, 0, CHUNK_HASH_A));

        assertTrue(failure.getMessage().contains("部分提交或冲突状态"));
        assertTrue(failure.getCause() instanceof IllegalStateException);
    }

    /**
     * 验证主状态与辅助证据均已存在时幂等重试不重复调用 Lua，但仍校验 TTL 和活动时间。
     */
    @Test
    void addUploadedChunkWithHashShouldReuseMatchingExistingEvidence() {
        String sessionId = "existing-uploaded-session";
        String uploadedKey = "file:upload:uploaded_chunks:" + sessionId;
        String hashesKey = "file:upload:chunk_hashes:" + sessionId;
        FileUploadState state = directState(sessionId);
        state.getUploadedChunks().add(0);
        state.getChunkHashes().put("chunk_0", CHUNK_HASH_A);
        AtomicReference<FileUploadState> redisState = new AtomicReference<>(state);
        AtomicLong redisTtl = new AtomicLong(SESSION_TTL_SECONDS);
        configureStateBackingStore(sessionId, redisState, redisTtl);
        when(cacheUtils.setIsMember(uploadedKey, "0")).thenReturn(true);
        when(cacheUtils.hashGetOrThrow(hashesKey, "chunk_0", String.class))
                .thenReturn(CHUNK_HASH_A);
        when(cacheUtils.getExpireSecondsOrThrow(uploadedKey))
                .thenReturn(SESSION_TTL_SECONDS);
        when(cacheUtils.getExpireSecondsOrThrow(hashesKey))
                .thenReturn(SESSION_TTL_SECONDS);

        assertTrue(stateManager.addUploadedChunkWithHash(sessionId, 0, CHUNK_HASH_A));

        verify(cacheUtils, never()).atomicAddToSetAndHash(
                anyString(), anyString(), anyString(), anyString(), anyString());
        verify(cacheUtils).atomicTouchSessionIfJsonStatusAllowed(
                eq("file:upload:session:" + sessionId),
                eq("file:upload:activity_at:" + sessionId),
                eq(sessionId),
                anyList(), anyLong(), anyLong(), anyList());
    }

    /**
     * 验证主状态或辅助 Hash 出现同分片不同哈希时均在写入前失败关闭。
     */
    @Test
    void addUploadedChunkWithHashShouldRejectConflictingExistingEvidence() {
        FileUploadState mainConflict = directState("main-upload-hash-conflict");
        mainConflict.getUploadedChunks().add(0);
        mainConflict.getChunkHashes().put("chunk_0", CHUNK_HASH_A);
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:main-upload-hash-conflict", FileUploadState.class))
                .thenReturn(mainConflict);

        assertThrows(
                IllegalStateException.class,
                () -> stateManager.addUploadedChunkWithHash(
                        "main-upload-hash-conflict", 0, CHUNK_HASH_B));

        FileUploadState auxiliaryConflict = directState("aux-upload-hash-conflict");
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:aux-upload-hash-conflict", FileUploadState.class))
                .thenReturn(auxiliaryConflict);
        when(cacheUtils.hashGetOrThrow(
                "file:upload:chunk_hashes:aux-upload-hash-conflict",
                "chunk_0",
                String.class)).thenReturn(CHUNK_HASH_B);

        assertThrows(
                IllegalStateException.class,
                () -> stateManager.addUploadedChunkWithHash(
                        "aux-upload-hash-conflict", 0, CHUNK_HASH_A));
        verify(cacheUtils, never()).atomicAddToSetAndHash(
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    /**
     * 验证非法哈希、缺失会话和保护终态均不能新增上传分片证据。
     */
    @Test
    void addUploadedChunkWithHashShouldValidateInputAndMutableSession() {
        assertThrows(
                IllegalArgumentException.class,
                () -> stateManager.addUploadedChunkWithHash("invalid-hash-session", 0, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> stateManager.addUploadedChunkWithHash("invalid-hash-session", 0, "short"));

        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:missing-uploaded-session", FileUploadState.class))
                .thenReturn(null);
        assertThrows(
                IllegalStateException.class,
                () -> stateManager.addUploadedChunkWithHash(
                        "missing-uploaded-session", 0, CHUNK_HASH_A));
        verify(cacheUtils).setRemove(
                "file:upload:active_sessions", "missing-uploaded-session");

        FileUploadState terminal = directState("terminal-uploaded-session");
        terminal.setStatus("completed");
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:terminal-uploaded-session", FileUploadState.class))
                .thenReturn(terminal);
        assertThrows(
                IllegalStateException.class,
                () -> stateManager.addUploadedChunkWithHash(
                        "terminal-uploaded-session", 0, CHUNK_HASH_A));
    }

    /**
     * 验证处理完成分片会写入 Set、校验 TTL 与成员，并通过独立活动证据续期会话。
     */
    @Test
    void addProcessedChunkShouldPersistEvidenceAndTouchActivity() {
        String sessionId = "processed-evidence-session";
        String processedKey = "file:upload:processed_chunks:" + sessionId;
        AtomicReference<FileUploadState> redisState =
                new AtomicReference<>(directState(sessionId));
        AtomicLong redisTtl = new AtomicLong(SESSION_TTL_SECONDS);
        AtomicBoolean member = new AtomicBoolean(false);
        configureStateBackingStore(sessionId, redisState, redisTtl);
        when(cacheUtils.setIsMember(processedKey, "0"))
                .thenAnswer(invocation -> member.get());
        when(cacheUtils.setAddOrThrow(processedKey, "0"))
                .thenAnswer(invocation -> {
                    member.set(true);
                    return 1L;
                });
        when(cacheUtils.getExpireSecondsOrThrow(processedKey))
                .thenReturn(SESSION_TTL_SECONDS);

        stateManager.addProcessedChunk(sessionId, 0);

        assertTrue(member.get());
        verify(cacheUtils).setExpireOrThrow(
                processedKey, SESSION_TTL_SECONDS, TimeUnit.SECONDS);
        verify(cacheUtils).atomicTouchSessionIfJsonStatusAllowed(
                eq("file:upload:session:" + sessionId),
                eq("file:upload:activity_at:" + sessionId),
                eq(sessionId),
                anyList(), anyLong(), anyLong(), anyList());
    }

    /**
     * 验证 Set 写响应异常但成员回读已提交时可继续，避免无意义地重复处理分片。
     */
    @Test
    void addProcessedChunkShouldAcceptUncertainWriteAfterMemberReadback() {
        String sessionId = "uncertain-processed-session";
        String processedKey = "file:upload:processed_chunks:" + sessionId;
        AtomicReference<FileUploadState> redisState =
                new AtomicReference<>(directState(sessionId));
        AtomicLong redisTtl = new AtomicLong(SESSION_TTL_SECONDS);
        AtomicBoolean member = new AtomicBoolean(false);
        configureStateBackingStore(sessionId, redisState, redisTtl);
        when(cacheUtils.setIsMember(processedKey, "0"))
                .thenAnswer(invocation -> member.get());
        when(cacheUtils.setAddOrThrow(processedKey, "0"))
                .thenAnswer(invocation -> {
                    member.set(true);
                    throw new IllegalStateException("set response lost");
                });
        when(cacheUtils.getExpireSecondsOrThrow(processedKey))
                .thenReturn(SESSION_TTL_SECONDS);

        stateManager.addProcessedChunk(sessionId, 0);

        assertTrue(member.get());
        verify(cacheUtils).atomicTouchSessionIfJsonStatusAllowed(
                eq("file:upload:session:" + sessionId),
                eq("file:upload:activity_at:" + sessionId),
                eq(sessionId),
                anyList(), anyLong(), anyLong(), anyList());
    }

    /**
     * 验证 Set 写入静默丢失时在 TTL 续期前失败关闭。
     */
    @Test
    void addProcessedChunkShouldFailWhenSetMemberIsStillAbsent() {
        String sessionId = "lost-processed-session";
        String processedKey = "file:upload:processed_chunks:" + sessionId;
        AtomicReference<FileUploadState> redisState =
                new AtomicReference<>(directState(sessionId));
        AtomicLong redisTtl = new AtomicLong(SESSION_TTL_SECONDS);
        configureStateBackingStore(sessionId, redisState, redisTtl);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> stateManager.addProcessedChunk(sessionId, 0));

        assertTrue(failure.getMessage().contains("缓存集合写入后校验失败"));
        verify(cacheUtils, never()).setExpireOrThrow(
                eq(processedKey), anyLong(), any(TimeUnit.class));
    }

    /**
     * 验证主状态已有处理证据时不会重复写 Set，但仍校验辅助成员和 TTL。
     */
    @Test
    void addProcessedChunkShouldReuseMainStateEvidence() {
        String sessionId = "existing-processed-session";
        String processedKey = "file:upload:processed_chunks:" + sessionId;
        FileUploadState state = directState(sessionId);
        state.getProcessedChunks().add(0);
        AtomicReference<FileUploadState> redisState = new AtomicReference<>(state);
        AtomicLong redisTtl = new AtomicLong(SESSION_TTL_SECONDS);
        configureStateBackingStore(sessionId, redisState, redisTtl);
        when(cacheUtils.setIsMember(processedKey, "0")).thenReturn(true);
        when(cacheUtils.getExpireSecondsOrThrow(processedKey))
                .thenReturn(SESSION_TTL_SECONDS);

        stateManager.addProcessedChunk(sessionId, 0);

        verify(cacheUtils, never()).setAddOrThrow(processedKey, "0");
        verify(cacheUtils).atomicTouchSessionIfJsonStatusAllowed(
                eq("file:upload:session:" + sessionId),
                eq("file:upload:activity_at:" + sessionId),
                eq(sessionId),
                anyList(), anyLong(), anyLong(), anyList());
    }

    /**
     * 验证首次创建稳定分片密钥会写入辅助 Hash、校验 TTL，并把同一值持久化到主状态。
     */
    @Test
    void getOrCreateChunkKeyShouldPersistStableKeyInAuxiliaryAndMainState() {
        String sessionId = "stable-key-session";
        String keysKey = "file:upload:chunk_keys:" + sessionId;
        byte[] candidate = new byte[]{1, 2, 3, 4};
        AtomicReference<FileUploadState> redisState =
                new AtomicReference<>(directState(sessionId));
        AtomicLong redisTtl = new AtomicLong(SESSION_TTL_SECONDS);
        configureStateBackingStore(sessionId, redisState, redisTtl);
        when(cacheUtils.hashGetOrThrow(keysKey, "0", byte[].class))
                .thenReturn(candidate);
        when(cacheUtils.getExpireSecondsOrThrow(keysKey))
                .thenReturn(SESSION_TTL_SECONDS);

        byte[] persisted = stateManager.getOrCreateChunkKey(sessionId, 0, candidate);

        assertArrayEquals(candidate, persisted);
        assertArrayEquals(candidate, redisState.get().getKeys().get(0));
        verify(cacheUtils).hashPutIfAbsentOrThrow(keysKey, "0", candidate);
        verify(cacheUtils).setExpireOrThrow(
                keysKey, SESSION_TTL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 验证主状态已有稳定密钥时忽略新的候选值，而 addChunkKey 的强一致包装会拒绝该冲突。
     */
    @Test
    void chunkKeyCreationShouldReuseMainStateAndRejectDifferentRequiredValue() {
        String sessionId = "existing-stable-key-session";
        String keysKey = "file:upload:chunk_keys:" + sessionId;
        byte[] stable = new byte[]{4, 3, 2, 1};
        byte[] conflictingCandidate = new byte[]{9, 9, 9, 9};
        FileUploadState state = directState(sessionId);
        state.getKeys().put(0, stable);
        AtomicReference<FileUploadState> redisState = new AtomicReference<>(state);
        AtomicLong redisTtl = new AtomicLong(SESSION_TTL_SECONDS);
        configureStateBackingStore(sessionId, redisState, redisTtl);
        when(cacheUtils.hashGetOrThrow(keysKey, "0", byte[].class))
                .thenReturn(stable);
        when(cacheUtils.getExpireSecondsOrThrow(keysKey))
                .thenReturn(SESSION_TTL_SECONDS);

        assertArrayEquals(
                stable,
                stateManager.getOrCreateChunkKey(sessionId, 0, conflictingCandidate));
        assertThrows(
                IllegalStateException.class,
                () -> stateManager.addChunkKey(sessionId, 0, conflictingCandidate));

        verify(cacheUtils, times(2)).hashPutIfAbsentOrThrow(keysKey, "0", stable);
        verify(cacheUtils, never()).hashPutIfAbsentOrThrow(
                keysKey, "0", conflictingCandidate);
    }

    /**
     * 验证 HSETNX 响应异常但严格回读为相同字节时继续完成主状态检查点。
     */
    @Test
    void getOrCreateChunkKeyShouldAcceptUncertainWriteWithExactReadback() {
        String sessionId = "uncertain-stable-key-session";
        String keysKey = "file:upload:chunk_keys:" + sessionId;
        byte[] candidate = new byte[]{7, 7, 7};
        AtomicReference<FileUploadState> redisState =
                new AtomicReference<>(directState(sessionId));
        AtomicLong redisTtl = new AtomicLong(SESSION_TTL_SECONDS);
        configureStateBackingStore(sessionId, redisState, redisTtl);
        doThrow(new IllegalStateException("hsetnx response lost"))
                .when(cacheUtils)
                .hashPutIfAbsentOrThrow(keysKey, "0", candidate);
        when(cacheUtils.hashGetOrThrow(keysKey, "0", byte[].class))
                .thenReturn(candidate);
        when(cacheUtils.getExpireSecondsOrThrow(keysKey))
                .thenReturn(SESSION_TTL_SECONDS);

        assertArrayEquals(
                candidate,
                stateManager.getOrCreateChunkKey(sessionId, 0, candidate));
        assertArrayEquals(candidate, redisState.get().getKeys().get(0));
    }

    /**
     * 验证辅助 Hash 已被不同稳定密钥占用时拒绝覆盖，且不续期或写回主状态。
     */
    @Test
    void getOrCreateChunkKeyShouldRejectConflictingAuxiliaryValue() {
        String sessionId = "conflicting-stable-key-session";
        String keysKey = "file:upload:chunk_keys:" + sessionId;
        byte[] candidate = new byte[]{1, 2, 3};
        AtomicReference<FileUploadState> redisState =
                new AtomicReference<>(directState(sessionId));
        AtomicLong redisTtl = new AtomicLong(SESSION_TTL_SECONDS);
        configureStateBackingStore(sessionId, redisState, redisTtl);
        when(cacheUtils.hashPutIfAbsentOrThrow(keysKey, "0", candidate))
                .thenReturn(false);
        when(cacheUtils.hashGetOrThrow(keysKey, "0", byte[].class))
                .thenReturn(new byte[]{3, 2, 1});

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> stateManager.getOrCreateChunkKey(sessionId, 0, candidate));

        assertTrue(failure.getMessage().contains("不同值占用"));
        verify(cacheUtils, never()).setExpireOrThrow(
                eq(keysKey), anyLong(), any(TimeUnit.class));
        verify(cacheUtils, never()).saveToCacheOrThrow(
                eq("file:upload:session:" + sessionId), any(), anyLong());
    }

    /**
     * 验证两个公开密钥入口都拒绝 null 或空字节，避免创建不可恢复的空密钥。
     */
    @Test
    void chunkKeyCreationShouldRejectMissingCandidateBytes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> stateManager.getOrCreateChunkKey("invalid-key-session", 0, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> stateManager.getOrCreateChunkKey(
                        "invalid-key-session", 0, new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> stateManager.addChunkKey("invalid-key-session", 0, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> stateManager.addChunkKey("invalid-key-session", 0, new byte[0]));
        verifyNoInteractions(redissonClient);
    }

    /**
     * 验证分片密钥读取会合并主状态与辅助 Hash，并逐项校验索引和值一致性。
     */
    @Test
    void getChunkKeysShouldMergeMainAndAuxiliaryEvidence() {
        String sessionId = "merged-key-read-session";
        String keysKey = "file:upload:chunk_keys:" + sessionId;
        byte[] mainKey = new byte[]{1, 1, 1};
        byte[] auxiliaryKey = new byte[]{2, 2, 2};
        FileUploadState state = directState(sessionId, 2);
        state.getKeys().put(0, mainKey);
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:" + sessionId, FileUploadState.class))
                .thenReturn(state);
        when(cacheUtils.hashGetAll(keysKey))
                .thenReturn(Map.of("0", "encoded-main", "1", "encoded-auxiliary"));
        when(cacheUtils.hashGetOrThrow(keysKey, "0", byte[].class))
                .thenReturn(mainKey);
        when(cacheUtils.hashGetOrThrow(keysKey, "1", byte[].class))
                .thenReturn(auxiliaryKey);

        Map<Integer, byte[]> restored = stateManager.getChunkKeys(sessionId);

        assertArrayEquals(mainKey, restored.get(0));
        assertArrayEquals(auxiliaryKey, restored.get(1));
        assertEquals(Set.of(0, 1), restored.keySet());
    }

    /**
     * 验证辅助 Hash 为空时仍返回主状态密钥的防御性副本。
     */
    @Test
    void getChunkKeysShouldReturnMainStateCopyWhenAuxiliaryHashIsEmpty() {
        String sessionId = "main-only-key-session";
        byte[] mainKey = new byte[]{5, 6, 7};
        FileUploadState state = directState(sessionId);
        state.getKeys().put(0, mainKey);
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:" + sessionId, FileUploadState.class))
                .thenReturn(state);

        Map<Integer, byte[]> restored = stateManager.getChunkKeys(sessionId);

        assertArrayEquals(mainKey, restored.get(0));
        assertNotSame(mainKey, restored.get(0));
    }

    /**
     * 验证孤儿辅助 Hash、非法字段类型、非数字字段和越界字段均失败关闭。
     */
    @Test
    void getChunkKeysShouldRejectMalformedOrOrphanAuxiliaryIndexes() {
        when(cacheUtils.hashGetAll("file:upload:chunk_keys:orphan-key-session"))
                .thenReturn(Map.of("0", "encoded"));
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:orphan-key-session", FileUploadState.class))
                .thenReturn(null);
        assertThrows(
                IllegalStateException.class,
                () -> stateManager.getChunkKeys("orphan-key-session"));

        FileUploadState invalidTypeState = directState("typed-key-session", 2);
        when(cacheUtils.hashGetAll("file:upload:chunk_keys:typed-key-session"))
                .thenReturn(Map.<Object, Object>of(0, "encoded"));
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:typed-key-session", FileUploadState.class))
                .thenReturn(invalidTypeState);
        assertThrows(
                IllegalStateException.class,
                () -> stateManager.getChunkKeys("typed-key-session"));

        FileUploadState nonNumericState = directState("nonnumeric-key-session", 2);
        when(cacheUtils.hashGetAll("file:upload:chunk_keys:nonnumeric-key-session"))
                .thenReturn(Map.of("part-zero", "encoded"));
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:nonnumeric-key-session", FileUploadState.class))
                .thenReturn(nonNumericState);
        assertThrows(
                IllegalStateException.class,
                () -> stateManager.getChunkKeys("nonnumeric-key-session"));

        FileUploadState outOfRangeState = directState("range-key-session");
        when(cacheUtils.hashGetAll("file:upload:chunk_keys:range-key-session"))
                .thenReturn(Map.of("1", "encoded"));
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:range-key-session", FileUploadState.class))
                .thenReturn(outOfRangeState);
        assertThrows(
                IllegalStateException.class,
                () -> stateManager.getChunkKeys("range-key-session"));
    }

    /**
     * 验证主状态与辅助 Hash 的同分片密钥不同或辅助值缺失时均失败关闭。
     */
    @Test
    void getChunkKeysShouldRejectMissingOrConflictingAuxiliaryValues() {
        String conflictSession = "mismatched-key-session";
        String conflictKey = "file:upload:chunk_keys:" + conflictSession;
        FileUploadState conflictState = directState(conflictSession);
        conflictState.getKeys().put(0, new byte[]{1, 2, 3});
        when(cacheUtils.hashGetAll(conflictKey)).thenReturn(Map.of("0", "encoded"));
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:" + conflictSession, FileUploadState.class))
                .thenReturn(conflictState);
        when(cacheUtils.hashGetOrThrow(conflictKey, "0", byte[].class))
                .thenReturn(new byte[]{3, 2, 1});
        assertThrows(
                IllegalStateException.class,
                () -> stateManager.getChunkKeys(conflictSession));

        String missingSession = "missing-aux-key-session";
        String missingKey = "file:upload:chunk_keys:" + missingSession;
        when(cacheUtils.hashGetAll(missingKey)).thenReturn(Map.of("0", "encoded"));
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:" + missingSession, FileUploadState.class))
                .thenReturn(directState(missingSession));
        when(cacheUtils.hashGetOrThrow(missingKey, "0", byte[].class))
                .thenReturn(null);
        assertThrows(
                IllegalStateException.class,
                () -> stateManager.getChunkKeys(missingSession));
    }

    /**
     * 验证读取会话会单调合并 uploaded、processed、哈希、密钥和独立活动时间证据。
     */
    @Test
    void getStateShouldMergeAllAuxiliaryEvidenceWithoutLosingMainProgress() {
        String sessionId = "complete-state-read-session";
        String stateKey = "file:upload:session:" + sessionId;
        String keysKey = "file:upload:chunk_keys:" + sessionId;
        byte[] mainKey = new byte[]{1, 2, 3};
        byte[] auxiliaryKey = new byte[]{4, 5, 6};
        FileUploadState state = directState(sessionId, 2);
        state.setLastActivityTime(100L);
        state.getUploadedChunks().add(0);
        state.getProcessedChunks().add(0);
        state.getChunkHashes().put("chunk_0", CHUNK_HASH_A);
        state.getKeys().put(0, mainKey);
        when(cacheUtils.takeFormCacheOrThrow(stateKey, FileUploadState.class))
                .thenReturn(state);
        when(cacheUtils.getIntegerSet("file:upload:uploaded_chunks:" + sessionId))
                .thenReturn(Set.of(1));
        when(cacheUtils.getIntegerSet("file:upload:processed_chunks:" + sessionId))
                .thenReturn(Set.of(1));
        when(cacheUtils.hashGetAll("file:upload:chunk_hashes:" + sessionId))
                .thenReturn(Map.of("chunk_1", JsonConverter.toJson(CHUNK_HASH_B)));
        when(cacheUtils.hashGetAll(keysKey))
                .thenReturn(Map.of("1", "encoded"));
        when(cacheUtils.hashGetOrThrow(keysKey, "1", byte[].class))
                .thenReturn(auxiliaryKey);
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:activity_at:" + sessionId, Long.class))
                .thenReturn(200L);

        FileUploadState restored = stateManager.getState(sessionId);

        assertEquals(Set.of(0, 1), restored.getUploadedChunks());
        assertEquals(Set.of(0, 1), restored.getProcessedChunks());
        assertEquals(CHUNK_HASH_A, restored.getChunkHashes().get("chunk_0"));
        assertEquals(CHUNK_HASH_B, restored.getChunkHashes().get("chunk_1"));
        assertArrayEquals(mainKey, restored.getKeys().get(0));
        assertArrayEquals(auxiliaryKey, restored.getKeys().get(1));
        assertEquals(200L, restored.getLastActivityTime());
    }

    /**
     * 验证合法恢复映射仅在租户、文件名、SUID 和传统上传类型全部匹配时返回。
     */
    @Test
    void getSessionIdByFileClientKeyShouldReturnOnlyMatchingLegacySession() {
        String sessionId = "valid-resume-session";
        String mappingField = "77:12:evidence.pdf:12:encoded-user";
        when(cacheUtils.hashGetOrThrow(
                "file:upload:filename_suid_mapping", mappingField, String.class))
                .thenReturn(sessionId);
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:" + sessionId, FileUploadState.class))
                .thenReturn(legacyState(sessionId, 1));

        assertEquals(
                sessionId,
                stateManager.getSessionIdByFileClientKey(
                        77L, "evidence.pdf", "encoded-user"));
        verify(cacheUtils, never()).hashDeleteIfValueMatchesOrThrow(
                anyString(), anyString(), anyString());
    }

    /**
     * 验证过期或身份不匹配的恢复映射会条件删除并同步清理调度索引。
     */
    @Test
    void getSessionIdByFileClientKeyShouldRemoveStaleMappingAndIndexes() {
        String sessionId = "stale-resume-session";
        String mappingField = "77:12:evidence.pdf:12:encoded-user";
        when(cacheUtils.hashGetOrThrow(
                "file:upload:filename_suid_mapping", mappingField, String.class))
                .thenReturn(sessionId, (String) null);
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:" + sessionId, FileUploadState.class))
                .thenReturn(directState(sessionId));

        assertNull(stateManager.getSessionIdByFileClientKey(
                77L, "evidence.pdf", "encoded-user"));

        verify(cacheUtils).hashDeleteIfValueMatchesOrThrow(
                "file:upload:filename_suid_mapping", mappingField, sessionId);
        verify(cacheUtils).setRemove("file:upload:active_sessions", sessionId);
        verify(cacheUtils).setRemove("file:upload:paused_sessions", sessionId);
    }

    /**
     * 验证传统会话移除会使用状态内 SUID 释放恢复映射，并删除全部主辅键与索引。
     */
    @Test
    void removeSessionShouldDeleteLegacyMappingAndAllOwnedEvidence() {
        String sessionId = "remove-legacy-session";
        String mappingField = "77:12:evidence.pdf:12:encoded-user";
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:" + sessionId, FileUploadState.class))
                .thenReturn(legacyState(sessionId, 1));
        when(cacheUtils.hashGetOrThrow(
                "file:upload:filename_suid_mapping", mappingField, String.class))
                .thenReturn(null);

        stateManager.removeSession(sessionId, "");

        verify(cacheUtils).hashDeleteIfValueMatchesOrThrow(
                "file:upload:filename_suid_mapping", mappingField, sessionId);
        verify(cacheUtils).deleteCache("file:upload:uploaded_chunks:" + sessionId);
        verify(cacheUtils).deleteCache("file:upload:processed_chunks:" + sessionId);
        verify(cacheUtils).deleteCache("file:upload:chunk_hashes:" + sessionId);
        verify(cacheUtils).deleteCache("file:upload:chunk_keys:" + sessionId);
        verify(cacheUtils).deleteCache("file:upload:session:" + sessionId);
        verify(cacheUtils).setRemove("file:upload:active_sessions", sessionId);
        verify(cacheUtils).setRemove("file:upload:paused_sessions", sessionId);
    }

    /**
     * 验证恢复映射条件删除后仍指向当前会话时停止数据删除，保留可诊断现场。
     */
    @Test
    void removeSessionShouldFailClosedWhenResumeMappingStillOwned() {
        String sessionId = "owned-resume-session";
        String mappingField = "77:12:evidence.pdf:12:encoded-user";
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:" + sessionId, FileUploadState.class))
                .thenReturn(legacyState(sessionId, 1));
        when(cacheUtils.hashGetOrThrow(
                "file:upload:filename_suid_mapping", mappingField, String.class))
                .thenReturn(sessionId);

        assertThrows(
                IllegalStateException.class,
                () -> stateManager.removeSession(sessionId, "encoded-user"));

        verify(cacheUtils, never()).deleteCache(
                "file:upload:uploaded_chunks:" + sessionId);
        verify(cacheUtils, never()).deleteCache("file:upload:session:" + sessionId);
        verify(cacheUtils, never()).setRemove("file:upload:active_sessions", sessionId);
    }

    /**
     * 验证恢复 Lua 的缺失和未知返回码均失败关闭，缺失会话同时清理孤儿索引。
     */
    @Test
    void removePausedSessionShouldFailClosedForMissingOrUnknownTransition() {
        when(cacheUtils.atomicResumeSessionIfJsonStatusAllowed(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyList(), anyLong(), anyLong(), anyList()))
                .thenReturn(-1L, 9L);

        assertThrows(
                IllegalStateException.class,
                () -> stateManager.removePausedSession("missing-resume-session"));
        verify(cacheUtils).setRemove(
                "file:upload:active_sessions", "missing-resume-session");
        assertThrows(
                IllegalStateException.class,
                () -> stateManager.removePausedSession("unknown-resume-session"));
    }

    /**
     * 验证暂停和恢复入口拒绝空 ID，并拒绝 Lua 未定义返回码。
     */
    @Test
    void pauseTransitionsShouldValidateSessionAndKnownResultCodes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> stateManager.addPausedSession(" "));
        assertThrows(
                IllegalArgumentException.class,
                () -> stateManager.removePausedSession(null));
        when(cacheUtils.atomicPauseSessionIfJsonStatusAllowed(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyList(),
                anyLong(), anyLong(), anyList())).thenReturn(7L);
        assertThrows(
                IllegalStateException.class,
                () -> stateManager.addPausedSession("unknown-pause-session"));
    }

    /**
     * 验证活动续期的缺失和未知返回码失败关闭，终态返回码则不重写状态。
     */
    @Test
    void updateLastActivityTimeShouldHandleAllFailClosedResults() {
        when(cacheUtils.atomicTouchSessionIfJsonStatusAllowed(
                anyString(), anyString(), anyString(), anyList(),
                anyLong(), anyLong(), anyList()))
                .thenReturn(-1L, 0L, 9L);

        assertThrows(
                IllegalStateException.class,
                () -> stateManager.updateLastActivityTime("missing-touch-session"));
        verify(cacheUtils).setRemove(
                "file:upload:active_sessions", "missing-touch-session");
        stateManager.updateLastActivityTime("terminal-touch-session");
        assertThrows(
                IllegalStateException.class,
                () -> stateManager.updateLastActivityTime("unknown-touch-session"));
        assertThrows(
                IllegalArgumentException.class,
                () -> stateManager.updateLastActivityTime(""));
    }

    /**
     * 验证锁内执行入口会恢复完整辅助状态，并对空操作和缺失会话失败关闭。
     */
    @Test
    void executeWithSessionStateLockShouldRestoreStateAndValidateAction() {
        String sessionId = "locked-action-session";
        FileUploadState state = directState(sessionId);
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:" + sessionId, FileUploadState.class))
                .thenReturn(state);

        assertEquals(
                sessionId,
                stateManager.executeWithSessionStateLock(
                        sessionId, FileUploadState::getClientId));
        assertThrows(
                IllegalArgumentException.class,
                () -> stateManager.executeWithSessionStateLock(sessionId, null));

        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:missing-locked-action", FileUploadState.class))
                .thenReturn(null);
        assertThrows(
                IllegalStateException.class,
                () -> stateManager.executeWithSessionStateLock(
                        "missing-locked-action", FileUploadState::getClientId));
        verify(cacheUtils).setRemove(
                "file:upload:active_sessions", "missing-locked-action");
    }

    /**
     * 验证跨实例状态锁繁忙时不读取或修改任何会话证据。
     */
    @Test
    void sessionStateMutationShouldFailBeforeRedisReadsWhenLockIsBusy() throws Exception {
        when(stateLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(false);

        assertThrows(
                IllegalStateException.class,
                () -> stateManager.addProcessedChunk("busy-lock-session", 0));

        verify(cacheUtils, never()).takeFormCacheOrThrow(
                anyString(), eq(FileUploadState.class));
        verify(stateLock, never()).unlock();
    }

    /**
     * 验证暂停成员查询、活跃集合空值归一化和原值返回的公开读取合同。
     */
    @Test
    void sessionIndexReadsShouldExposePausedAndNormalizeMissingActiveSet() {
        when(cacheUtils.setIsMember("file:upload:paused_sessions", "paused-index-session"))
                .thenReturn(true);
        when(cacheUtils.setMembers("file:upload:active_sessions"))
                .thenReturn(null, Set.of("active-session"));

        assertTrue(stateManager.isSessionPaused("paused-index-session"));
        assertTrue(stateManager.getAllActiveSessionIds().isEmpty());
        assertEquals(Set.of("active-session"), stateManager.getAllActiveSessionIds());
    }

    /**
     * 验证完成入口拒绝非法 TTL，状态已过期时只收敛孤儿索引而不重建主状态。
     */
    @Test
    void markCompletedShouldValidateTtlAndHandleExpiredSession() {
        assertThrows(
                IllegalArgumentException.class,
                () -> stateManager.markCompleted("invalid-completed-session", "user", 0));
        when(cacheUtils.takeFormCacheOrThrow(
                "file:upload:session:expired-completed-session", FileUploadState.class))
                .thenReturn(null);

        stateManager.markCompleted("expired-completed-session", "user", 300);

        verify(cacheUtils).setRemove(
                "file:upload:active_sessions", "expired-completed-session");
        verify(cacheUtils, never()).saveToCacheOrThrow(
                eq("file:upload:session:expired-completed-session"), any(), anyLong());
    }

    /**
     * 验证处理分片在最终成员回读丢失时失败关闭，不刷新活动时间。
     */
    @Test
    void addProcessedChunkShouldRejectEvidenceLostAfterTtlRefresh() {
        String sessionId = "post-write-lost-processed-session";
        String processedKey = "file:upload:processed_chunks:" + sessionId;
        AtomicReference<FileUploadState> redisState =
                new AtomicReference<>(directState(sessionId));
        AtomicLong redisTtl = new AtomicLong(SESSION_TTL_SECONDS);
        configureStateBackingStore(sessionId, redisState, redisTtl);
        when(cacheUtils.setIsMember(processedKey, "0"))
                .thenReturn(false, true, false);
        when(cacheUtils.getExpireSecondsOrThrow(processedKey))
                .thenReturn(SESSION_TTL_SECONDS);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> stateManager.addProcessedChunk(sessionId, 0));

        assertTrue(failure.getMessage().contains("处理分片证据写入后校验失败"));
        verify(cacheUtils, never()).atomicTouchSessionIfJsonStatusAllowed(
                anyString(), anyString(), anyString(), anyList(), anyLong(), anyLong(), anyList());
    }

    /**
     * 验证上传分片组合证据在 TTL 刷新后最终回读丢失时仍失败关闭。
     */
    @Test
    void addUploadedChunkWithHashShouldRejectEvidenceLostAfterTtlRefresh() {
        String sessionId = "post-write-lost-uploaded-session";
        String uploadedKey = "file:upload:uploaded_chunks:" + sessionId;
        String hashesKey = "file:upload:chunk_hashes:" + sessionId;
        AtomicReference<FileUploadState> redisState =
                new AtomicReference<>(directState(sessionId));
        AtomicLong redisTtl = new AtomicLong(SESSION_TTL_SECONDS);
        configureStateBackingStore(sessionId, redisState, redisTtl);
        when(cacheUtils.setIsMember(uploadedKey, "0"))
                .thenReturn(false, true, false);
        when(cacheUtils.hashGetOrThrow(hashesKey, "chunk_0", String.class))
                .thenReturn(null, CHUNK_HASH_A);
        when(cacheUtils.atomicAddToSetAndHash(
                eq(uploadedKey), eq("0"), eq(hashesKey), eq("chunk_0"), anyString()))
                .thenReturn(true);
        when(cacheUtils.getExpireSecondsOrThrow(uploadedKey))
                .thenReturn(SESSION_TTL_SECONDS);
        when(cacheUtils.getExpireSecondsOrThrow(hashesKey))
                .thenReturn(SESSION_TTL_SECONDS);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> stateManager.addUploadedChunkWithHash(sessionId, 0, CHUNK_HASH_A));

        assertTrue(failure.getMessage().contains("上传分片与哈希写入后校验失败"));
        verify(cacheUtils, never()).atomicTouchSessionIfJsonStatusAllowed(
                anyString(), anyString(), anyString(), anyList(), anyLong(), anyLong(), anyList());
    }

    /**
     * 构造带可信直传计划的 Redis 会话状态。
     */
    private FileUploadState directState(String sessionId) {
        return directState(sessionId, 1);
    }

    /**
     * 构造指定分片数量的可信直传 Redis 会话状态。
     */
    private FileUploadState directState(String sessionId, int totalChunks) {
        FileUploadState state = new FileUploadState(
                100L,
                "evidence.pdf",
                1024L,
                "application/pdf",
                sessionId,
                1024,
                totalChunks);
        state.setTenantId(77L);
        state.setSuid("encoded-user");
        state.setDirectUpload(true);
        state.setDirectUploadParts(List.of(new FileUploadState.DirectUploadPartState(
                0,
                1024L,
                "sha256:plain",
                "sha256:cipher",
                "SHA-256",
                "https://storage.example/upload",
                9999999999L,
                "storage/tenant/77/chunk/0",
                "tenant/77/staging/direct-upload/" + sessionId + "/part-0",
                "tenant/77/final/direct-upload/" + sessionId + "/part-0",
                "node-a")));
        return state;
    }

    /**
     * 构造启用文件名恢复映射的传统上传 Redis 会话状态。
     */
    private FileUploadState legacyState(String sessionId, int totalChunks) {
        FileUploadState state = directState(sessionId, totalChunks);
        state.setDirectUpload(false);
        state.setDirectUploadParts(List.of());
        return state;
    }

    /**
     * 用 JSON 往返构造与 Redis 反序列化等价的独立状态快照。
     */
    private FileUploadState copyState(FileUploadState state) {
        return JsonConverter.parse(JsonConverter.toJson(state), FileUploadState.class);
    }

    /**
     * 为状态键配置线程安全的内存后端，记录每次整对象写入使用的 TTL。
     */
    private void configureStateBackingStore(
            String sessionId,
            AtomicReference<FileUploadState> state,
            AtomicLong ttl
    ) {
        String stateKey = "file:upload:session:" + sessionId;
        when(cacheUtils.takeFormCacheOrThrow(stateKey, FileUploadState.class))
                .thenAnswer(invocation -> {
                    FileUploadState current = state.get();
                    return current == null ? null : copyState(current);
                });
        lenient().doAnswer(invocation -> {
            FileUploadState updated = invocation.getArgument(1);
            long updatedTtl = invocation.getArgument(2);
            state.set(copyState(updated));
            ttl.set(updatedTtl);
            return null;
        }).when(cacheUtils).saveToCacheOrThrow(eq(stateKey), any(FileUploadState.class), anyLong());
        lenient().when(cacheUtils.getExpireSecondsOrThrow(stateKey))
                .thenAnswer(invocation -> ttl.get());
    }
}
