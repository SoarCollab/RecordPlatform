package cn.flying.storage.service;

import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.FaultDomainManager;
import cn.flying.storage.core.S3Monitor;
import cn.flying.storage.event.NodeTopologyChangeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证域恢复只在 hash placement 的真实副本修复成功后更新 durable degraded 状态。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DomainRecoveryListener Repair Completion Tests")
class DomainRecoveryListenerTest {

    private static final String HASH = "sha256:abc";
    private static final String STORAGE_FIELD = "0:" + HASH;
    private static final String OBJECT_PATH = "tenant/0/" + HASH;

    @Mock
    private DegradedWriteTracker degradedWriteTracker;

    @Mock
    private FaultDomainManager faultDomainManager;

    @Mock
    private ConsistencyRepairService repairService;

    @Mock
    private StorageProperties storageProperties;

    @Mock
    private S3Monitor s3Monitor;

    private DomainRecoveryListener listener;
    private StorageProperties.DegradedWriteConfig config;
    private DegradedWriteTracker.DegradedWriteRecord record;

    /**
     * 注入恢复依赖、合法 placement 和一个在线源节点。
     */
    @BeforeEach
    void setUp() {
        listener = newListener();
        config = new StorageProperties.DegradedWriteConfig();
        config.setTrackForSync(true);
        config.setMaxSyncFailures(3);
        config.setRepairTimeoutSeconds(1);
        when(storageProperties.getDegradedWrite()).thenReturn(config);
        lenient().when(faultDomainManager.getPlannedTargetDomains(HASH))
                .thenReturn(List.of("domain-b"));
        lenient().when(faultDomainManager.getTargetNodeInDomain(HASH, "domain-b"))
                .thenReturn("node-b");
        lenient().when(faultDomainManager.getPlannedTargetNodeInDomain(HASH, "domain-b"))
                .thenReturn("node-b");
        lenient().when(faultDomainManager.getPlannedTargetNodeInDomain(HASH, "domain-c"))
                .thenReturn("node-c");
        lenient().when(faultDomainManager.getPlannedTargetsSnapshot(anyString()))
                .thenAnswer(invocation -> {
                    String objectHash = invocation.getArgument(0);
                    List<String> domains = faultDomainManager.getPlannedTargetDomains(objectHash);
                    if (domains == null) {
                        return null;
                    }
                    Map<String, String> plannedTargets = new LinkedHashMap<>();
                    for (String domain : domains) {
                        plannedTargets.put(
                                domain,
                                faultDomainManager.getPlannedTargetNodeInDomain(objectHash, domain)
                        );
                    }
                    return Collections.unmodifiableMap(plannedTargets);
                });
        lenient().when(faultDomainManager.areNodesOnIndependentPhysicalStorage(
                        anyString(),
                        anyString()
                ))
                .thenReturn(true);
        lenient().when(faultDomainManager.getNodeDomain("node-a"))
                .thenReturn("domain-a");
        lenient().when(s3Monitor.isNodeOnline("node-a")).thenReturn(true);
        record = new DegradedWriteTracker.DegradedWriteRecord(
                HASH,
                List.of("node-a"),
                List.of("domain-b"),
                0L,
                1L
        );
    }

    @Test
    @DisplayName("all online source copy failures should increment the domain failure once")
    void shouldCountOneFailureWhenEveryOnlineSourceCopyFails() {
        stubPendingRecord();
        when(repairService.scheduleImmediateRepairByNodesDetailedAsync(
                OBJECT_PATH, "node-a", "node-b"))
                .thenReturn(completed(ConsistencyRepairService.ImmediateRepairStatus.COPY_FAILED));

        listener.checkPendingSyncs();

        verify(degradedWriteTracker).recordRepairFailure(
                HASH,
                0L,
                "domain-b",
                "node-b",
                "all actual source copy or verification attempts failed",
                3
        );
        verify(degradedWriteTracker, never()).updateMissingDomains(
                HASH, 0L, "domain-b", "node-b");
    }

    @Test
    @DisplayName("successful repair should update the exact tenant domain and hash target")
    void shouldUpdateOnlyAfterRepairSucceeds() {
        stubPendingRecord();
        when(repairService.scheduleImmediateRepairByNodesDetailedAsync(
                OBJECT_PATH, "node-a", "node-b"))
                .thenReturn(completed(ConsistencyRepairService.ImmediateRepairStatus.SUCCEEDED));

        listener.checkPendingSyncs();

        verify(degradedWriteTracker).updateMissingDomains(
                HASH, 0L, "domain-b", "node-b");
        verify(degradedWriteTracker, never()).recordRepairFailure(
                eq(HASH), eq(0L), eq("domain-b"), eq("node-b"), anyString(), eq(3));
    }

    /**
     * 验证拓扑快照出现物理重复时不进入复制，也不调用任何 missing-domain 更新或清理入口。
     */
    @Test
    @DisplayName("duplicate physical target topology should preserve the claimed durable evidence")
    void shouldPreserveClaimWhenPhysicalTargetTopologyIsUnresolved() {
        Map<String, String> unresolvedTargets = new LinkedHashMap<>();
        unresolvedTargets.put("domain-b", null);
        org.mockito.Mockito.doReturn(Collections.unmodifiableMap(unresolvedTargets))
                .when(faultDomainManager)
                .getPlannedTargetsSnapshot(HASH);
        stubPendingRecord();

        listener.checkPendingSyncs();

        verify(degradedWriteTracker).rescheduleClaim(STORAGE_FIELD);
        verify(degradedWriteTracker, never()).updateMissingDomains(
                eq(HASH), eq(0L), anyString(), org.mockito.ArgumentMatchers.<String>any());
        verify(degradedWriteTracker, never()).markSynced(HASH, 0L);
        verifyNoInteractions(repairService);
    }

    /**
     * 验证源和目标是同一物理集群的逻辑别名时，不得启动修复或消耗死信次数。
     */
    @Test
    @DisplayName("same physical source and target should remain pending without repair")
    void shouldRejectSamePhysicalSourceAndTarget() {
        when(faultDomainManager.areNodesOnIndependentPhysicalStorage("node-a", "node-b"))
                .thenReturn(false);
        stubPendingRecord();

        listener.checkPendingSyncs();

        verify(degradedWriteTracker).rescheduleClaim(STORAGE_FIELD);
        verify(degradedWriteTracker, never()).updateMissingDomains(
                HASH, 0L, "domain-b", "node-b");
        verify(degradedWriteTracker, never()).recordRepairFailure(
                eq(HASH), eq(0L), eq("domain-b"), eq("node-b"), anyString(), eq(3));
        verifyNoInteractions(repairService);
    }

    /**
     * 验证复制期间 Nacos 物理身份漂移后，即使 provider 返回成功也不清理 durable evidence。
     */
    @Test
    @DisplayName("physical topology drift during repair should keep the claim pending")
    void shouldPreserveClaimWhenPhysicalTopologyDriftsDuringRepair() {
        when(faultDomainManager.areNodesOnIndependentPhysicalStorage("node-a", "node-b"))
                .thenReturn(true, false);
        stubPendingRecord();
        when(repairService.scheduleImmediateRepairByNodesDetailedAsync(
                OBJECT_PATH, "node-a", "node-b"))
                .thenReturn(completed(ConsistencyRepairService.ImmediateRepairStatus.SUCCEEDED));

        listener.checkPendingSyncs();

        verify(repairService).scheduleImmediateRepairByNodesDetailedAsync(
                OBJECT_PATH, "node-a", "node-b");
        verify(degradedWriteTracker).rescheduleClaim(STORAGE_FIELD);
        verify(degradedWriteTracker, never()).updateMissingDomains(
                HASH, 0L, "domain-b", "node-b");
    }

    @Test
    @DisplayName("record without a repair source should remain pending without consuming DLQ attempts")
    void shouldDeferRecordWithoutWrittenSource() {
        record.setWrittenNodes(List.of());
        stubPendingRecord();

        listener.checkPendingSyncs();

        verify(degradedWriteTracker).rescheduleClaim(STORAGE_FIELD);
        verify(degradedWriteTracker, never()).recordRepairFailure(
                eq(HASH), eq(0L), eq("domain-b"), eq("node-b"), anyString(), eq(3));
        verifyNoInteractions(repairService);
    }

    @Test
    @DisplayName("exceptional completion should remain pending without consuming DLQ attempts")
    void shouldDeferExceptionalRepairCompletion() {
        stubPendingRecord();
        when(repairService.scheduleImmediateRepairByNodesDetailedAsync(
                OBJECT_PATH, "node-a", "node-b"))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("provider unavailable")));

        listener.checkPendingSyncs();

        verify(degradedWriteTracker).rescheduleClaim(STORAGE_FIELD);
        verify(degradedWriteTracker, never()).recordRepairFailure(
                eq(HASH), eq(0L), eq("domain-b"), eq("node-b"), anyString(), eq(3));
        verify(degradedWriteTracker, never()).updateMissingDomains(
                HASH, 0L, "domain-b", "node-b");
    }

    @Test
    @DisplayName("unavailable exact hash target should remain pending without consuming DLQ attempts")
    void shouldDeferWhenExactTargetIsUnavailable() {
        stubPendingRecord();
        when(faultDomainManager.getTargetNodeInDomain(HASH, "domain-b")).thenReturn(null);

        listener.checkPendingSyncs();

        verify(degradedWriteTracker).rescheduleClaim(STORAGE_FIELD);
        verify(degradedWriteTracker, never()).recordRepairFailure(
                eq(HASH), eq(0L), eq("domain-b"), eq("node-b"), anyString(), eq(3));
        verifyNoInteractions(repairService);
    }

    @Test
    @DisplayName("prerequisite and queue deferrals should not consume DLQ attempts")
    void shouldDeferNonCopyStatusesWithoutFailureCount() {
        record.setWrittenNodes(List.of("source-a", "source-c"));
        stubPendingRecord();
        when(faultDomainManager.getNodeDomain("source-a")).thenReturn("domain-a");
        when(faultDomainManager.getNodeDomain("source-c")).thenReturn("domain-c");
        when(s3Monitor.isNodeOnline("source-a")).thenReturn(true);
        when(s3Monitor.isNodeOnline("source-c")).thenReturn(true);
        when(repairService.scheduleImmediateRepairByNodesDetailedAsync(
                OBJECT_PATH, "source-a", "node-b"))
                .thenReturn(completed(
                        ConsistencyRepairService.ImmediateRepairStatus.PREREQUISITE_UNAVAILABLE));
        when(repairService.scheduleImmediateRepairByNodesDetailedAsync(
                OBJECT_PATH, "source-c", "node-b"))
                .thenReturn(completed(
                        ConsistencyRepairService.ImmediateRepairStatus.RETRYABLE_DEFERRED));

        listener.checkPendingSyncs();

        verify(degradedWriteTracker).rescheduleClaim(STORAGE_FIELD);
        verify(degradedWriteTracker, never()).recordRepairFailure(
                eq(HASH), eq(0L), eq("domain-b"), eq("node-b"), anyString(), eq(3));
    }

    @Test
    @DisplayName("second online source should repair after first source content verification fails")
    void shouldTryEveryOnlineSourceUntilOneSucceeds() {
        record.setWrittenNodes(List.of("source-a1", "source-a2"));
        stubPendingRecord();
        when(faultDomainManager.getNodeDomain("source-a1")).thenReturn("domain-a");
        when(faultDomainManager.getNodeDomain("source-a2")).thenReturn("domain-a");
        when(s3Monitor.isNodeOnline("source-a1")).thenReturn(true);
        when(s3Monitor.isNodeOnline("source-a2")).thenReturn(true);
        when(repairService.scheduleImmediateRepairByNodesDetailedAsync(
                OBJECT_PATH, "source-a1", "node-b"))
                .thenReturn(completed(ConsistencyRepairService.ImmediateRepairStatus.COPY_FAILED));
        when(repairService.scheduleImmediateRepairByNodesDetailedAsync(
                OBJECT_PATH, "source-a2", "node-b"))
                .thenReturn(completed(ConsistencyRepairService.ImmediateRepairStatus.SUCCEEDED));

        listener.checkPendingSyncs();

        verify(repairService).scheduleImmediateRepairByNodesDetailedAsync(
                OBJECT_PATH, "source-a1", "node-b");
        verify(repairService).scheduleImmediateRepairByNodesDetailedAsync(
                OBJECT_PATH, "source-a2", "node-b");
        verify(degradedWriteTracker).updateMissingDomains(
                HASH, 0L, "domain-b", "node-b");
        verify(degradedWriteTracker, never()).recordRepairFailure(
                eq(HASH), eq(0L), eq("domain-b"), eq("node-b"), anyString(), eq(3));
    }

    @Test
    @DisplayName("stale prerequisite source should not hide a determined failure from actual sources")
    void shouldCountDeterminedFailureDespiteStaleSource() {
        record.setWrittenNodes(List.of("source-a", "source-stale"));
        stubPendingRecord();
        when(faultDomainManager.getNodeDomain("source-a")).thenReturn("domain-a");
        when(faultDomainManager.getNodeDomain("source-stale")).thenReturn("domain-c");
        when(s3Monitor.isNodeOnline("source-a")).thenReturn(true);
        when(s3Monitor.isNodeOnline("source-stale")).thenReturn(true);
        when(repairService.scheduleImmediateRepairByNodesDetailedAsync(
                OBJECT_PATH, "source-a", "node-b"))
                .thenReturn(completed(ConsistencyRepairService.ImmediateRepairStatus.COPY_FAILED));
        when(repairService.scheduleImmediateRepairByNodesDetailedAsync(
                OBJECT_PATH, "source-stale", "node-b"))
                .thenReturn(completed(
                        ConsistencyRepairService.ImmediateRepairStatus.PREREQUISITE_UNAVAILABLE));

        listener.checkPendingSyncs();

        verify(degradedWriteTracker).recordRepairFailure(
                HASH,
                0L,
                "domain-b",
                "node-b",
                "all actual source copy or verification attempts failed",
                3
        );
        verify(degradedWriteTracker, never()).rescheduleClaim(STORAGE_FIELD);
    }

    @Test
    @DisplayName("successful first domain should stop before a second domain on the same claim")
    void shouldStopAfterFirstDomainSuccessReordersClaim() {
        record.setMissingDomains(List.of("domain-b", "domain-c"));
        when(faultDomainManager.getPlannedTargetDomains(HASH))
                .thenReturn(List.of("domain-b", "domain-c"));
        stubPendingRecord();
        when(repairService.scheduleImmediateRepairByNodesDetailedAsync(
                OBJECT_PATH, "node-a", "node-b"))
                .thenReturn(completed(ConsistencyRepairService.ImmediateRepairStatus.SUCCEEDED));

        listener.checkPendingSyncs();

        verify(degradedWriteTracker).updateMissingDomains(
                HASH, 0L, "domain-b", "node-b");
        verify(repairService, never()).scheduleImmediateRepairByNodesDetailedAsync(
                OBJECT_PATH, "node-a", "node-c");
    }

    @Test
    @DisplayName("failed first domain should stop before a second domain on the same claim")
    void shouldStopAfterFirstDomainFailureReordersClaim() {
        record.setMissingDomains(List.of("domain-b", "domain-c"));
        when(faultDomainManager.getPlannedTargetDomains(HASH))
                .thenReturn(List.of("domain-b", "domain-c"));
        stubPendingRecord();
        when(repairService.scheduleImmediateRepairByNodesDetailedAsync(
                OBJECT_PATH, "node-a", "node-b"))
                .thenReturn(completed(ConsistencyRepairService.ImmediateRepairStatus.COPY_FAILED));

        listener.checkPendingSyncs();

        verify(degradedWriteTracker).recordRepairFailure(
                eq(HASH), eq(0L), eq("domain-b"), eq("node-b"), anyString(), eq(3));
        verify(repairService, never()).scheduleImmediateRepairByNodesDetailedAsync(
                OBJECT_PATH, "node-a", "node-c");
    }

    @Test
    @DisplayName("long offline period should remain pending beyond threshold and repair after recovery")
    void shouldKeepOfflineSourcePendingUntilRecovery() {
        when(degradedWriteTracker.claimPendingSyncs())
                .thenReturn(List.of(claim(STORAGE_FIELD, record)));
        when(s3Monitor.isNodeOnline("node-a"))
                .thenReturn(false, false, false, false, true);
        when(repairService.scheduleImmediateRepairByNodesDetailedAsync(
                OBJECT_PATH, "node-a", "node-b"))
                .thenReturn(completed(ConsistencyRepairService.ImmediateRepairStatus.SUCCEEDED));

        for (int attempt = 0; attempt < 5; attempt++) {
            listener.checkPendingSyncs();
        }

        verify(degradedWriteTracker, times(4)).rescheduleClaim(STORAGE_FIELD);
        verify(degradedWriteTracker).updateMissingDomains(
                HASH, 0L, "domain-b", "node-b");
        verify(degradedWriteTracker, never()).recordRepairFailure(
                eq(HASH), eq(0L), eq("domain-b"), eq("node-b"), anyString(), eq(3));
    }

    @Test
    @DisplayName("obsolete missing placement should be removed without copying")
    void shouldRemoveObsoletePlacementWithoutCopy() {
        record.setMissingDomains(List.of("domain-old", "domain-b"));
        when(degradedWriteTracker.claimPendingSyncs())
                .thenReturn(List.of(claim(STORAGE_FIELD, record)));

        listener.checkPendingSyncs();

        verify(degradedWriteTracker).updateMissingDomains(
                HASH, 0L, "domain-old", null);
        verifyNoInteractions(repairService);
        verify(degradedWriteTracker, never()).updateMissingDomains(
                HASH, 0L, "domain-b", "node-b");
        verify(degradedWriteTracker, never()).recordRepairFailure(
                eq(HASH), eq(0L), eq("domain-old"), anyString(), anyString(), eq(3));
    }

    @Test
    @DisplayName("two listener instances sharing atomic claims should schedule one copy")
    void shouldCopyOnceAcrossTwoListenerInstances() throws Exception {
        AtomicBoolean claimed = new AtomicBoolean(false);
        when(degradedWriteTracker.claimPendingSyncs()).thenAnswer(invocation ->
                claimed.compareAndSet(false, true)
                        ? List.of(claim(STORAGE_FIELD, record))
                        : List.of());
        when(repairService.scheduleImmediateRepairByNodesDetailedAsync(
                OBJECT_PATH, "node-a", "node-b"))
                .thenReturn(completed(ConsistencyRepairService.ImmediateRepairStatus.SUCCEEDED));
        DomainRecoveryListener secondListener = newListener();
        CountDownLatch start = new CountDownLatch(1);
        Thread first = Thread.ofPlatform().start(() -> {
            await(start);
            listener.checkPendingSyncs();
        });
        Thread second = Thread.ofPlatform().start(() -> {
            await(start);
            secondListener.checkPendingSyncs();
        });

        start.countDown();
        first.join(TimeUnit.SECONDS.toMillis(5));
        second.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(first.isAlive()).isFalse();
        assertThat(second.isAlive()).isFalse();
        verify(repairService, times(1)).scheduleImmediateRepairByNodesDetailedAsync(
                OBJECT_PATH, "node-a", "node-b");
        verify(degradedWriteTracker, times(1)).updateMissingDomains(
                HASH, 0L, "domain-b", "node-b");
    }

    @Test
    @DisplayName("never-completing repair should time out and use the extended claim delay")
    void shouldBoundListenerWaitForNeverCompletingRepair() {
        stubPendingRecord();
        when(repairService.scheduleImmediateRepairByNodesDetailedAsync(
                OBJECT_PATH, "node-a", "node-b"))
                .thenReturn(new CompletableFuture<>());

        listener.checkPendingSyncs();

        verify(degradedWriteTracker).rescheduleClaimAfterRepairTimeout(STORAGE_FIELD);
        verify(degradedWriteTracker, never()).recordRepairFailure(
                eq(HASH), eq(0L), eq("domain-b"), eq("node-b"), anyString(), eq(3));
    }

    @Test
    @DisplayName("interrupted listener wait should restore interrupt status and preserve the claim")
    void shouldRestoreInterruptStatusWhileWaitingForRepair() throws Exception {
        stubPendingRecord();
        CountDownLatch waiting = new CountDownLatch(1);
        when(repairService.scheduleImmediateRepairByNodesDetailedAsync(
                OBJECT_PATH, "node-a", "node-b"))
                .thenAnswer(invocation -> {
                    waiting.countDown();
                    return new CompletableFuture<>();
                });
        AtomicBoolean interrupted = new AtomicBoolean(false);
        Thread worker = Thread.ofPlatform().start(() -> {
            listener.checkPendingSyncs();
            interrupted.set(Thread.currentThread().isInterrupted());
        });

        assertThat(waiting.await(2, TimeUnit.SECONDS)).isTrue();
        worker.interrupt();
        worker.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(worker.isAlive()).isFalse();
        assertThat(interrupted).isTrue();
        verify(degradedWriteTracker).rescheduleClaimAfterRepairTimeout(STORAGE_FIELD);
        verify(degradedWriteTracker, never()).recordRepairFailure(
                eq(HASH), eq(0L), eq("domain-b"), eq("node-b"), anyString(), eq(3));
    }

    @Test
    @DisplayName("legacy null tenant should use the deterministic tenant zero path")
    void shouldUseTenantZeroPathForLegacyNullTenantRecord() {
        record.setTenantId(null);
        stubPendingRecord();
        when(repairService.scheduleImmediateRepairByNodesDetailedAsync(
                OBJECT_PATH, "node-a", "node-b"))
                .thenReturn(completed(ConsistencyRepairService.ImmediateRepairStatus.SUCCEEDED));

        listener.checkPendingSyncs();

        verify(repairService).scheduleImmediateRepairByNodesDetailedAsync(
                OBJECT_PATH, "node-a", "node-b");
        verify(degradedWriteTracker).updateMissingDomains(
                HASH, null, "domain-b", "node-b");
    }

    @Test
    @DisplayName("null missing-domain state should fail closed and remain scheduled")
    void shouldKeepNullMissingDomainsWithoutDeletingTruth() {
        record.setMissingDomains(null);
        when(degradedWriteTracker.claimPendingSyncs())
                .thenReturn(List.of(claim(STORAGE_FIELD, record)));

        listener.checkPendingSyncs();

        verify(degradedWriteTracker).rescheduleClaim(STORAGE_FIELD);
        verify(degradedWriteTracker, never()).markSynced(HASH, 0L);
        verifyNoInteractions(repairService);
    }

    @Test
    @DisplayName("unknown placement plan should fail closed and remain scheduled")
    void shouldKeepRecordWhenPlacementPlanIsUnknown() {
        when(faultDomainManager.getPlannedTargetDomains(HASH)).thenReturn(null);
        stubPendingRecord();

        listener.checkPendingSyncs();

        verify(degradedWriteTracker).rescheduleClaim(STORAGE_FIELD);
        verify(degradedWriteTracker, never()).markSynced(HASH, 0L);
        verifyNoInteractions(repairService);
    }

    @Test
    @DisplayName("explicitly empty missing-domain state should remove the completed record")
    void shouldRemoveOnlyExplicitlyCompletedRecord() {
        record.setMissingDomains(List.of());
        when(degradedWriteTracker.claimPendingSyncs())
                .thenReturn(List.of(claim(STORAGE_FIELD, record)));

        listener.checkPendingSyncs();

        verify(degradedWriteTracker).markSynced(HASH, 0L);
        verify(degradedWriteTracker, never()).rescheduleClaim(STORAGE_FIELD);
        verifyNoInteractions(repairService);
    }

    @Test
    @DisplayName("recovery event should immediately release claims for other legal domains")
    void shouldReleaseNonMatchingEventClaimImmediately() {
        record.setMissingDomains(List.of("domain-c"));
        when(faultDomainManager.getPlannedTargetDomains(HASH)).thenReturn(List.of("domain-c"));
        when(degradedWriteTracker.claimPendingSyncs())
                .thenReturn(List.of(claim(STORAGE_FIELD, record)));

        listener.onNodeTopologyChange(new NodeTopologyChangeEvent(
                this,
                "node-b",
                NodeTopologyChangeEvent.TopologyChangeType.NODE_ONLINE,
                "domain-b"
        ));

        verify(degradedWriteTracker).releaseClaim(STORAGE_FIELD);
        verify(degradedWriteTracker, never()).rescheduleClaim(STORAGE_FIELD);
        verifyNoInteractions(repairService);
    }

    /**
     * 配置当前记录为本轮领取结果。
     */
    private void stubPendingRecord() {
        when(degradedWriteTracker.claimPendingSyncs())
                .thenReturn(List.of(claim(STORAGE_FIELD, record)));
    }

    /**
     * 创建使用同一组共享依赖的监听器实例。
     */
    private DomainRecoveryListener newListener() {
        DomainRecoveryListener created = new DomainRecoveryListener();
        ReflectionTestUtils.setField(created, "degradedWriteTracker", degradedWriteTracker);
        ReflectionTestUtils.setField(created, "faultDomainManager", faultDomainManager);
        ReflectionTestUtils.setField(created, "repairService", repairService);
        ReflectionTestUtils.setField(created, "storageProperties", storageProperties);
        ReflectionTestUtils.setField(created, "s3Monitor", s3Monitor);
        return created;
    }

    /**
     * 创建已经完成的详细修复结果 future。
     */
    private CompletableFuture<ConsistencyRepairService.ImmediateRepairResult> completed(
            ConsistencyRepairService.ImmediateRepairStatus status
    ) {
        return CompletableFuture.completedFuture(
                new ConsistencyRepairService.ImmediateRepairResult(status)
        );
    }

    /**
     * 用稳定 Redis field 包装已领取记录。
     */
    private DegradedWriteTracker.ClaimedDegradedWrite claim(
            String storageField,
            DegradedWriteTracker.DegradedWriteRecord claimedRecord
    ) {
        return new DegradedWriteTracker.ClaimedDegradedWrite(storageField, claimedRecord);
    }

    /**
     * 等待并发监听器同时开始。
     */
    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
