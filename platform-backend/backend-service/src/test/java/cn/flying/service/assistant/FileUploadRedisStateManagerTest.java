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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                .thenReturn(200L, null);

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
        String stateKey = "file:upload:session:" + sessionId;
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
     * 构造带可信直传计划的 Redis 会话状态。
     */
    private FileUploadState directState(String sessionId) {
        FileUploadState state = new FileUploadState(
                100L,
                "evidence.pdf",
                1024L,
                "application/pdf",
                sessionId,
                1024,
                1);
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
