package cn.flying.storage.service;

import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.S3ClientManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证 staging 清理的集群领取、分片锁、claim fencing、双对象删除与失败保留。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DirectUploadStagingCleanupService Unit Tests")
class DirectUploadStagingCleanupServiceTest {

    @Mock
    private S3ClientManager clientManager;

    @Mock
    private DirectUploadStagingTracker stagingTracker;

    @Mock
    private DirectUploadLockManager lockManager;

    @Mock
    private DirectUploadOperationIntentStore operationIntentStore;

    @Mock
    private DirectUploadLockManager.LockHandle lockHandle;

    @Mock
    private S3Client s3Client;

    @Mock
    private S3ClientManager.TopologyLease topologyLease;

    private StorageProperties properties;
    private SimpleMeterRegistry meterRegistry;
    private DirectUploadStagingCleanupService service;
    private DirectUploadStagingTracker.TrackedStaging tracked;
    private AtomicLong monotonicNanos;
    private DirectUploadOperationIntentStore.OperationIntent operationIntent;

    /**
     * 使用真实配置与指标注册表创建清理服务。
     */
    @BeforeEach
    void setUp() {
        properties = new StorageProperties();
        meterRegistry = new SimpleMeterRegistry();
        monotonicNanos = new AtomicLong();
        operationIntent = new DirectUploadOperationIntentStore.OperationIntent(
                "test-intent-key",
                "test-intent-fence-key",
                "test-intent-value",
                DirectUploadOperationIntentStore.OperationMode.CLEANUP,
                "a".repeat(64),
                "a".repeat(64),
                1L
        );
        service = createService(
                clientManager,
                stagingTracker,
                lockManager,
                monotonicNanos
        );
        org.mockito.Mockito.lenient()
                .when(clientManager.acquireTopologyLease())
                .thenReturn(topologyLease);
        DirectUploadStagingDescriptor descriptor = new DirectUploadStagingDescriptor(
                7L,
                "session-1",
                1,
                "node-a",
                "tenant/7/staging/direct-upload/session-1/part-1"
        );
        tracked = new DirectUploadStagingTracker.TrackedStaging(
                "encoded-member",
                descriptor,
                "batch-1:1",
                1L
        );
        org.mockito.Mockito.lenient()
                .when(operationIntentStore.followOrCreateCleanup(any()))
                .thenReturn(Optional.of(operationIntent));
    }

    /**
     * 关闭测试指标注册表。
     */
    @AfterEach
    void tearDown() {
        meterRegistry.close();
    }

    @Test
    @DisplayName("disabled cleanup should not claim the expiry index")
    void shouldSkipWhenCleanupIsDisabled() {
        properties.getDirectUpload().setCleanupEnabled(false);

        service.cleanupExpiredStagingObjects();

        verifyNoInteractions(stagingTracker, lockManager, clientManager);
    }

    @Test
    @DisplayName("Redis claim failure should fail safe before any storage access")
    void shouldFailSafeWhenClaimFails() {
        when(stagingTracker.claimExpired(200)).thenThrow(new IllegalStateException("Redis unavailable"));

        service.cleanupExpiredStagingObjects();

        verifyNoInteractions(lockManager, clientManager, s3Client);
        assertThat(counter("claim_failure")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("busy part lock should reschedule only the current claim and release the batch gate")
    void shouldPreserveLockedPart() {
        DirectUploadStagingTracker.ClaimBatch batch = batchOf(tracked);
        when(stagingTracker.claimExpired(200)).thenReturn(batch);
        when(lockManager.tryAcquireForCleanup(tracked.descriptor())).thenReturn(Optional.empty());
        when(stagingTracker.rescheduleClaim(tracked)).thenReturn(true);

        service.cleanupExpiredStagingObjects();

        verifyNoInteractions(clientManager, s3Client);
        verify(stagingTracker).rescheduleClaim(tracked);
        verify(stagingTracker, never()).completeClaim(any());
        verify(stagingTracker).releaseClaimBatch(batch);
        assertThat(counter("locked")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("refreshed generation should fail pre-delete fencing and preserve both objects")
    void shouldNotDeleteWhenClaimWasSuperseded() {
        DirectUploadStagingTracker.ClaimBatch batch = batchOf(tracked);
        when(stagingTracker.claimExpired(200)).thenReturn(batch);
        when(lockManager.tryAcquireForCleanup(tracked.descriptor())).thenReturn(Optional.of(lockHandle));
        when(stagingTracker.isClaimCurrent(tracked)).thenReturn(false);

        service.cleanupExpiredStagingObjects();

        verifyNoInteractions(clientManager, s3Client);
        verify(stagingTracker, never()).completeClaim(any());
        verify(stagingTracker, never()).rescheduleClaim(any());
        verify(lockHandle).close();
        assertThat(counter("claim_superseded")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("successful cleanup should delete original and sealed objects before CAS completion")
    void shouldDeleteBothObjectsBeforeCompletingClaim() {
        DirectUploadStagingTracker.ClaimBatch batch = batchOf(tracked);
        stubSuccessfulCleanup(batch);

        service.cleanupExpiredStagingObjects();

        InOrder ordered = inOrder(stagingTracker, clientManager, topologyLease, s3Client, lockHandle);
        ordered.verify(stagingTracker).claimExpired(200);
        ordered.verify(stagingTracker).isClaimCurrent(tracked);
        ordered.verify(clientManager).acquireTopologyLease();
        ordered.verify(topologyLease).getClient("node-a");
        ArgumentCaptor<DeleteObjectRequest> requestCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        ordered.verify(stagingTracker).isClaimCurrent(tracked);
        ordered.verify(s3Client).deleteObject(requestCaptor.capture());
        ordered.verify(stagingTracker).isClaimCurrent(tracked);
        ordered.verify(s3Client).deleteObject(requestCaptor.capture());
        ordered.verify(stagingTracker).completeClaim(tracked);
        ordered.verify(topologyLease).close();
        ordered.verify(lockHandle).close();
        ordered.verify(stagingTracker).releaseClaimBatch(batch);
        assertThat(requestCaptor.getAllValues())
                .extracting(DeleteObjectRequest::key)
                .containsExactly(
                        "tenant/7/staging/direct-upload/session-1/part-1",
                        "tenant/7/staging/direct-upload/session-1/part-1.sealed"
                );
        assertThat(requestCaptor.getAllValues()).allSatisfy(request -> {
            assertThat(request.bucket()).isEqualTo("node-a");
            assertThat(request.overrideConfiguration()).isPresent();
            assertThat(request.overrideConfiguration().orElseThrow().apiCallTimeout()).isPresent();
            assertThat(request.overrideConfiguration().orElseThrow().apiCallAttemptTimeout()).isPresent();
        });
        assertThat(counter("success")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("missing original should still delete an existing sealed staging object")
    void shouldDeleteSealedWhenOriginalIsAlreadyMissing() {
        DirectUploadStagingTracker.ClaimBatch batch = batchOf(tracked);
        when(stagingTracker.claimExpired(200)).thenReturn(batch);
        when(lockManager.tryAcquireForCleanup(tracked.descriptor())).thenReturn(Optional.of(lockHandle));
        when(stagingTracker.isClaimCurrent(tracked)).thenReturn(true);
        when(stagingTracker.completeClaim(tracked)).thenReturn(true);
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("NoSuchKey").build())
                .thenReturn(DeleteObjectResponse.builder().build());
        when(topologyLease.getClient("node-a")).thenReturn(s3Client);

        service.cleanupExpiredStagingObjects();

        ArgumentCaptor<DeleteObjectRequest> requests = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client, times(2)).deleteObject(requests.capture());
        assertThat(requests.getAllValues().get(1).key()).endsWith(".sealed");
        verify(stagingTracker).completeClaim(tracked);
        assertThat(counter("success")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("sealed delete failure should retain and reschedule the claim")
    void shouldRetainClaimWhenSealedDeleteFails() {
        DirectUploadStagingTracker.ClaimBatch batch = batchOf(tracked);
        when(stagingTracker.claimExpired(200)).thenReturn(batch);
        when(lockManager.tryAcquireForCleanup(tracked.descriptor())).thenReturn(Optional.of(lockHandle));
        when(stagingTracker.isClaimCurrent(tracked)).thenReturn(true);
        when(stagingTracker.rescheduleClaim(tracked)).thenReturn(true);
        when(topologyLease.getClient("node-a")).thenReturn(s3Client);
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build())
                .thenThrow(S3Exception.builder().statusCode(503).message("unavailable").build());

        service.cleanupExpiredStagingObjects();

        verify(s3Client, times(2)).deleteObject(any(DeleteObjectRequest.class));
        verify(stagingTracker, never()).completeClaim(any());
        verify(stagingTracker).rescheduleClaim(tracked);
        verify(lockHandle).close();
        assertThat(counter("failure")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("unavailable client should reschedule before releasing the part lock")
    void shouldRescheduleWhenClientIsUnavailable() {
        DirectUploadStagingTracker.ClaimBatch batch = batchOf(tracked);
        when(stagingTracker.claimExpired(200)).thenReturn(batch);
        when(lockManager.tryAcquireForCleanup(tracked.descriptor())).thenReturn(Optional.of(lockHandle));
        when(stagingTracker.isClaimCurrent(tracked)).thenReturn(true);
        when(stagingTracker.rescheduleClaim(tracked)).thenReturn(true);
        when(topologyLease.getClient("node-a")).thenReturn(null);

        service.cleanupExpiredStagingObjects();

        InOrder ordered = inOrder(stagingTracker, lockHandle);
        ordered.verify(stagingTracker).rescheduleClaim(tracked);
        ordered.verify(lockHandle).close();
        verify(stagingTracker, never()).completeClaim(any());
        assertThat(counter("client_unavailable")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("post-delete stale completion should retain the replacement lifecycle")
    void shouldRetainReplacementWhenCompletionIsFenced() {
        DirectUploadStagingTracker.ClaimBatch batch = batchOf(tracked);
        when(stagingTracker.claimExpired(200)).thenReturn(batch);
        when(lockManager.tryAcquireForCleanup(tracked.descriptor())).thenReturn(Optional.of(lockHandle));
        when(stagingTracker.isClaimCurrent(tracked)).thenReturn(true);
        when(stagingTracker.completeClaim(tracked)).thenReturn(false);
        when(topologyLease.getClient("node-a")).thenReturn(s3Client);
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        service.cleanupExpiredStagingObjects();

        verify(s3Client, times(2)).deleteObject(any(DeleteObjectRequest.class));
        verify(stagingTracker, never()).rescheduleClaim(any());
        assertThat(counter("completion_fenced")).isEqualTo(1.0);
        assertThat(counter("success")).isZero();
    }

    @Test
    @DisplayName("Redis reschedule failure should not stop a later claimed member")
    void shouldFailClosedAndContinueWhenRedisRescheduleFails() {
        DirectUploadStagingDescriptor laterDescriptor = new DirectUploadStagingDescriptor(
                7L,
                "session-2",
                2,
                "node-b",
                "tenant/7/staging/direct-upload/session-2/part-2"
        );
        DirectUploadStagingTracker.TrackedStaging later = new DirectUploadStagingTracker.TrackedStaging(
                "later-member", laterDescriptor, "batch-1:2", 2L
        );
        DirectUploadStagingTracker.ClaimBatch batch = batchOf(tracked, later);
        DirectUploadLockManager.LockHandle laterLock = org.mockito.Mockito.mock(
                DirectUploadLockManager.LockHandle.class
        );
        when(stagingTracker.claimExpired(200)).thenReturn(batch);
        when(lockManager.tryAcquireForCleanup(tracked.descriptor())).thenReturn(Optional.empty());
        when(lockManager.tryAcquireForCleanup(laterDescriptor)).thenReturn(Optional.of(laterLock));
        doThrow(new IllegalStateException("Redis unavailable"))
                .when(stagingTracker).rescheduleClaim(tracked);
        when(stagingTracker.isClaimCurrent(later)).thenReturn(true);
        when(stagingTracker.completeClaim(later)).thenReturn(true);
        when(topologyLease.getClient("node-b")).thenReturn(s3Client);
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        service.cleanupExpiredStagingObjects();

        verify(stagingTracker).completeClaim(later);
        verify(s3Client, times(2)).deleteObject(any(DeleteObjectRequest.class));
        verify(stagingTracker).releaseClaimBatch(batch);
        assertThat(counter("reschedule_failure")).isEqualTo(1.0);
        assertThat(counter("success")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("batch release failure should be observable and rely on the finite lease")
    void shouldObserveBatchReleaseFailure() {
        DirectUploadStagingTracker.ClaimBatch batch = batchOf();
        when(stagingTracker.claimExpired(200)).thenReturn(batch);
        doThrow(new IllegalStateException("Redis unavailable"))
                .when(stagingTracker).releaseClaimBatch(batch);

        service.cleanupExpiredStagingObjects();

        assertThat(counter("batch_release_failure")).isEqualTo(1.0);
        verifyNoInteractions(lockManager, clientManager, s3Client);
    }

    @Test
    @DisplayName("batch deadline should cap each DELETE and reschedule untouched claims before lease expiry")
    void shouldStopBeforeClaimLeaseAndRescheduleUntouchedClaims() {
        DirectUploadStagingDescriptor laterDescriptor = new DirectUploadStagingDescriptor(
                7L,
                "session-2",
                2,
                "node-b",
                "tenant/7/staging/direct-upload/session-2/part-2"
        );
        DirectUploadStagingTracker.TrackedStaging later = new DirectUploadStagingTracker.TrackedStaging(
                "later-member", laterDescriptor, "batch-1:2", 2L
        );
        DirectUploadStagingTracker.ClaimBatch batch = new DirectUploadStagingTracker.ClaimBatch(
                "batch-1",
                List.of(tracked, later),
                Duration.ofSeconds(180).toMillis()
        );
        when(stagingTracker.claimExpired(200)).thenReturn(batch);
        when(lockManager.tryAcquireForCleanup(tracked.descriptor())).thenReturn(Optional.of(lockHandle));
        when(stagingTracker.isClaimCurrent(tracked)).thenReturn(true);
        when(stagingTracker.completeClaim(tracked)).thenReturn(true);
        when(stagingTracker.rescheduleClaims(List.of(later))).thenReturn(1);
        when(topologyLease.getClient("node-a")).thenReturn(s3Client);
        AtomicInteger deleteCount = new AtomicInteger();
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(invocation -> {
            if (deleteCount.getAndIncrement() == 0) {
                monotonicNanos.set(Duration.ofSeconds(145).toNanos());
            } else {
                monotonicNanos.set(Duration.ofSeconds(150).toNanos());
            }
            return DeleteObjectResponse.builder().build();
        });

        service.cleanupExpiredStagingObjects();

        ArgumentCaptor<DeleteObjectRequest> requests = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client, times(2)).deleteObject(requests.capture());
        assertThat(requests.getAllValues().get(0).overrideConfiguration().orElseThrow().apiCallTimeout())
                .contains(Duration.ofSeconds(60));
        assertThat(requests.getAllValues().get(1).overrideConfiguration().orElseThrow().apiCallTimeout())
                .contains(Duration.ofSeconds(5));
        verify(stagingTracker).completeClaim(tracked);
        verify(stagingTracker).rescheduleClaims(List.of(later));
        verify(lockManager, never()).tryAcquireForCleanup(laterDescriptor);
        verify(stagingTracker).releaseClaimBatch(batch);
        assertThat(monotonicNanos.get()).isLessThan(Duration.ofSeconds(180).toNanos());
        assertThat(counter("budget_exhausted")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Redis batch reschedule failure should preserve claims and still release the gate")
    void shouldPreserveClaimsWhenBudgetRescheduleFails() {
        DirectUploadStagingTracker.ClaimBatch batch = new DirectUploadStagingTracker.ClaimBatch(
                "batch-1",
                List.of(tracked),
                Duration.ofSeconds(180).toMillis()
        );
        when(stagingTracker.claimExpired(200)).thenAnswer(invocation -> {
            monotonicNanos.set(Duration.ofSeconds(150).toNanos());
            return batch;
        });
        doThrow(new IllegalStateException("Redis unavailable"))
                .when(stagingTracker).rescheduleClaims(List.of(tracked));

        service.cleanupExpiredStagingObjects();

        verify(stagingTracker).rescheduleClaims(List.of(tracked));
        verify(stagingTracker).releaseClaimBatch(batch);
        verifyNoInteractions(lockManager, clientManager, s3Client);
        assertThat(counter("reschedule_failure")).isEqualTo(1.0);
        assertThat(counter("budget_exhausted")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("second instance should not enter provider cleanup until the budgeted owner releases its gate")
    void shouldPreventCrossInstanceOverlapPastBatchBudget() throws Exception {
        DirectUploadStagingDescriptor laterDescriptor = new DirectUploadStagingDescriptor(
                7L,
                "session-2",
                2,
                "node-b",
                "tenant/7/staging/direct-upload/session-2/part-2"
        );
        DirectUploadStagingTracker.TrackedStaging later = new DirectUploadStagingTracker.TrackedStaging(
                "later-member", laterDescriptor, "batch-1:2", 2L
        );
        DirectUploadStagingTracker.ClaimBatch batch = new DirectUploadStagingTracker.ClaimBatch(
                "batch-1",
                List.of(tracked, later),
                Duration.ofSeconds(180).toMillis()
        );
        AtomicBoolean gateHeld = new AtomicBoolean(false);
        AtomicInteger claimAttempts = new AtomicInteger();
        when(stagingTracker.claimExpired(200)).thenAnswer(invocation -> {
            claimAttempts.incrementAndGet();
            return gateHeld.compareAndSet(false, true)
                    ? batch
                    : DirectUploadStagingTracker.ClaimBatch.empty();
        });
        doAnswer(invocation -> {
            DirectUploadStagingTracker.ClaimBatch released = invocation.getArgument(0);
            if ("batch-1".equals(released.batchToken())) {
                gateHeld.set(false);
            }
            return null;
        }).when(stagingTracker).releaseClaimBatch(any(DirectUploadStagingTracker.ClaimBatch.class));
        when(lockManager.tryAcquireForCleanup(tracked.descriptor())).thenReturn(Optional.of(lockHandle));
        when(stagingTracker.isClaimCurrent(tracked)).thenReturn(true);
        when(stagingTracker.rescheduleClaim(any())).thenReturn(true);
        when(stagingTracker.rescheduleClaims(List.of(later))).thenReturn(1);
        when(topologyLease.getClient("node-a")).thenReturn(s3Client);
        CountDownLatch firstDeleteStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstDelete = new CountDownLatch(1);
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(invocation -> {
            firstDeleteStarted.countDown();
            if (!releaseFirstDelete.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting to advance the batch budget");
            }
            return DeleteObjectResponse.builder().build();
        });

        S3ClientManager secondClientManager = org.mockito.Mockito.mock(S3ClientManager.class);
        DirectUploadLockManager secondLockManager = org.mockito.Mockito.mock(DirectUploadLockManager.class);
        AtomicLong secondClock = new AtomicLong();
        DirectUploadStagingCleanupService secondService = createService(
                secondClientManager,
                stagingTracker,
                secondLockManager,
                secondClock
        );
        when(secondLockManager.tryAcquireForCleanup(any())).thenReturn(Optional.empty());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> firstRun = executor.submit(service::cleanupExpiredStagingObjects);
            assertThat(firstDeleteStarted.await(5, TimeUnit.SECONDS)).isTrue();

            secondService.cleanupExpiredStagingObjects();

            verifyNoInteractions(secondLockManager, secondClientManager);
            assertThat(gateHeld).isTrue();

            monotonicNanos.set(Duration.ofSeconds(150).toNanos());
            releaseFirstDelete.countDown();
            firstRun.get(5, TimeUnit.SECONDS);

            assertThat(monotonicNanos.get()).isLessThan(Duration.ofSeconds(180).toNanos());
            assertThat(gateHeld).isFalse();
            verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
            verify(stagingTracker).rescheduleClaims(List.of(later));

            secondService.cleanupExpiredStagingObjects();

            verify(secondLockManager, times(2)).tryAcquireForCleanup(any());
            verifyNoInteractions(secondClientManager);
            assertThat(gateHeld).isFalse();
            assertThat(claimAttempts).hasValue(3);
        } finally {
            releaseFirstDelete.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * 配置一次 original 与 sealed 均可删除的完整成功路径。
     */
    private void stubSuccessfulCleanup(DirectUploadStagingTracker.ClaimBatch batch) {
        when(stagingTracker.claimExpired(200)).thenReturn(batch);
        when(lockManager.tryAcquireForCleanup(tracked.descriptor())).thenReturn(Optional.of(lockHandle));
        when(stagingTracker.isClaimCurrent(tracked)).thenReturn(true);
        when(stagingTracker.completeClaim(tracked)).thenReturn(true);
        when(topologyLease.getClient("node-a")).thenReturn(s3Client);
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());
    }

    /**
     * 创建一个带稳定 gate token 的领取批次。
     */
    private DirectUploadStagingTracker.ClaimBatch batchOf(
            DirectUploadStagingTracker.TrackedStaging... entries
    ) {
        return new DirectUploadStagingTracker.ClaimBatch("batch-1", List.of(entries));
    }

    /**
     * 创建使用可控单调时钟的清理服务，避免租约边界测试依赖真实等待。
     */
    private DirectUploadStagingCleanupService createService(
            S3ClientManager serviceClientManager,
            DirectUploadStagingTracker serviceTracker,
            DirectUploadLockManager serviceLockManager,
            AtomicLong monotonicClock
    ) {
        return new DirectUploadStagingCleanupService(
                serviceClientManager,
                properties,
                serviceTracker,
                serviceLockManager,
                operationIntentStore,
                meterRegistry
        ) {
            @Override
            long currentMonotonicTimeNanos() {
                return monotonicClock.get();
            }
        };
    }

    /**
     * 返回指定结果标签的生命周期清理计数。
     */
    private double counter(String result) {
        return meterRegistry.counter(
                "storage_direct_upload_staging_cleanup_total",
                "result",
                result
        ).count();
    }
}
