package cn.flying.service.manifest.backfill;

import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.SnowflakeIdGenerator;
import cn.flying.dao.entity.ManifestBackfillItem;
import cn.flying.dao.entity.ManifestBackfillRun;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.ManifestBackfillItemMapper;
import cn.flying.dao.mapper.ManifestBackfillRunMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies immutable derivative runs, crash recovery polling, and bounded retry exhaustion.
 */
@ExtendWith(MockitoExtension.class)
class ManifestBackfillRunServiceTest {

    private static final Long TENANT_ID = 11L;

    @Mock
    private ManifestBackfillRunMapper runMapper;

    @Mock
    private ManifestBackfillItemMapper itemMapper;

    @Mock
    private FileMapper fileMapper;

    @Mock
    private ManifestEvidenceResolver evidenceResolver;

    @Mock
    private ManifestBackfillClaimService claimService;

    @Mock
    private ChunkManifestBackfillPublisher publisher;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private ManifestBackfillRunService runService;

    /**
     * Creates a rollout-enabled service under one strict tenant context.
     */
    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        runService = new ManifestBackfillRunService(
                runMapper, itemMapper, fileMapper, evidenceResolver, claimService,
                publisher, snowflakeIdGenerator, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(runService, "workerEnabled", true);
        ReflectionTestUtils.setField(runService, "applyEnabled", true);
        ReflectionTestUtils.setField(runService, "runLeaseSeconds", 300L);
    }

    /**
     * Clears tenant state after each run lifecycle example.
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * Rejects a service caller attempting to create work for another tenant.
     */
    @Test
    void shouldRejectCrossTenantRunCreation() {
        assertThatThrownBy(() -> runService.createRun(12L, 22L, ManifestBackfillMode.SCAN, null))
                .isInstanceOf(GeneralException.class);

        verify(runMapper, never()).insert(any(ManifestBackfillRun.class));
    }

    /**
     * Clones a dry-run from frozen evidence, preserves its digest, and performs no product write.
     */
    @Test
    void shouldCompleteDryRunWithFrozenDigestAndNoProductWrites() {
        ManifestBackfillRun source = frozenScan(10L);
        ManifestBackfillRun planned = derivativeRun(20L, ManifestBackfillMode.DRY_RUN, "PLANNED", null);
        ManifestBackfillRun running = derivativeRun(20L, ManifestBackfillMode.DRY_RUN, "SCANNING",
                source.getSnapshotDigest());
        ManifestBackfillItem sourceItem = backfillableItem(30L, 10L, "PENDING");
        ManifestBackfillItem clonedItem = backfillableItem(31L, 20L, "PENDING");
        when(runMapper.selectRunGlobally(20L)).thenReturn(planned, running);
        when(runMapper.selectRunGlobally(10L)).thenReturn(source);
        when(runMapper.claimPlannedRun(eq(20L), eq("SCANNING"), any(Date.class))).thenReturn(1);
        when(itemMapper.selectRunItems(10L, TENANT_ID)).thenReturn(List.of(sourceItem));
        when(itemMapper.selectRunItems(20L, TENANT_ID)).thenReturn(List.of(clonedItem));
        when(snowflakeIdGenerator.nextId()).thenReturn(31L);

        runService.processPlannedRun(20L);

        ArgumentCaptor<ManifestBackfillRun> updates = ArgumentCaptor.forClass(ManifestBackfillRun.class);
        verify(runMapper, org.mockito.Mockito.atLeast(2)).updateById(updates.capture());
        ManifestBackfillRun finalUpdate = updates.getAllValues().getLast();
        assertThat(finalUpdate.getStatus()).isEqualTo(ManifestBackfillRunStatus.COMPLETED.name());
        assertThat(finalUpdate.getSnapshotDigest()).isEqualTo(source.getSnapshotDigest());
        verify(fileMapper, never()).selectManifestBackfillCandidates(any(), any(Long.class), any(Integer.class));
        verify(publisher, never()).publish(any(), any(), any(), any());
    }

    /**
     * Leaves an apply run planned while a retryable item is waiting for its next claim window.
     */
    @Test
    void shouldReplanApplyRunWhileRetryableWorkRemains() {
        ManifestBackfillRun source = frozenScan(10L);
        ManifestBackfillRun planned = derivativeRun(20L, ManifestBackfillMode.APPLY, "PLANNED", null);
        ManifestBackfillRun applying = derivativeRun(20L, ManifestBackfillMode.APPLY, "APPLYING",
                source.getSnapshotDigest());
        ManifestBackfillItem sourceItem = backfillableItem(30L, 10L, "PENDING");
        ManifestBackfillItem waiting = backfillableItem(31L, 20L, "FAILED")
                .setRetryable(1)
                .setAttemptCount(1)
                .setNextRetryAt(new Date(System.currentTimeMillis() + 60_000L));
        when(runMapper.selectRunGlobally(20L)).thenReturn(planned, applying, applying);
        when(runMapper.selectRunGlobally(10L)).thenReturn(source);
        when(runMapper.claimPlannedRun(eq(20L), eq("APPLYING"), any(Date.class))).thenReturn(1);
        when(runMapper.touchRunLease(20L, TENANT_ID, "APPLYING")).thenReturn(1);
        when(itemMapper.selectRunItems(10L, TENANT_ID)).thenReturn(List.of(sourceItem));
        when(itemMapper.selectRunItems(20L, TENANT_ID)).thenReturn(List.of(waiting));
        when(snowflakeIdGenerator.nextId()).thenReturn(31L);
        when(claimService.claim(eq(20L), eq(TENANT_ID), eq(20), eq(3), eq(120L), any()))
                .thenReturn(null);

        runService.processPlannedRun(20L);

        ArgumentCaptor<ManifestBackfillRun> updates = ArgumentCaptor.forClass(ManifestBackfillRun.class);
        verify(runMapper, org.mockito.Mockito.atLeast(2)).updateById(updates.capture());
        assertThat(updates.getAllValues().getLast().getStatus())
                .isEqualTo(ManifestBackfillRunStatus.PLANNED.name());
    }

    /**
     * Polls both planned and stale running work through a bounded recovery cutoff.
     */
    @Test
    void shouldPollUsingStaleRunLeaseCutoff() {
        when(runMapper.selectRunnableRuns(any(Date.class), eq(4))).thenReturn(List.of());

        runService.processPlannedRuns();

        verify(runMapper).selectRunnableRuns(any(Date.class), eq(4));
    }

    /**
     * Converts the final retryable failure into a stable exhausted terminal reason.
     */
    @Test
    void shouldStopRetryingAfterThirdClaimAttempt() {
        ManifestBackfillClaimService boundedClaimService = new ManifestBackfillClaimService(itemMapper);
        ManifestBackfillItem item = backfillableItem(31L, 20L, "RUNNING").setAttemptCount(3);
        when(itemMapper.completeClaim(
                TENANT_ID, 20L, 31L, "claim-a", "FAILED", "FAILED", "MAX_ATTEMPTS_EXHAUSTED",
                null, 0, null, "TransientDatabaseException"))
                .thenReturn(1);

        boundedClaimService.failClaim(item, "claim-a", ManifestBackfillReason.DATABASE_TRANSIENT,
                true, "TransientDatabaseException");

        verify(itemMapper).completeClaim(
                TENANT_ID, 20L, 31L, "claim-a", "FAILED", "FAILED", "MAX_ATTEMPTS_EXHAUSTED",
                null, 0, null, "TransientDatabaseException");
    }

    /**
     * Leaves a lease-lost item unfinished so a later worker can reclaim it instead of failing the run.
     */
    @Test
    void shouldReplanApplyRunWhenPublisherLosesClaim() {
        ManifestBackfillRun source = frozenScan(10L);
        ManifestBackfillRun planned = derivativeRun(20L, ManifestBackfillMode.APPLY, "PLANNED", null);
        ManifestBackfillRun applying = derivativeRun(20L, ManifestBackfillMode.APPLY, "APPLYING",
                source.getSnapshotDigest());
        ManifestBackfillItem sourceItem = backfillableItem(30L, 10L, "PENDING");
        ManifestBackfillItem claimedItem = backfillableItem(31L, 20L, "RUNNING")
                .setAttemptCount(1)
                .setClaimToken("claim-a")
                .setLeaseExpiresAt(new Date(System.currentTimeMillis() + 60_000L));
        when(runMapper.selectRunGlobally(20L)).thenReturn(planned, applying, applying);
        when(runMapper.selectRunGlobally(10L)).thenReturn(source);
        when(runMapper.claimPlannedRun(eq(20L), eq("APPLYING"), any(Date.class))).thenReturn(1);
        when(runMapper.touchRunLease(20L, TENANT_ID, "APPLYING")).thenReturn(1);
        when(itemMapper.selectRunItems(10L, TENANT_ID)).thenReturn(List.of(sourceItem));
        when(itemMapper.selectRunItems(20L, TENANT_ID)).thenReturn(List.of(claimedItem));
        when(snowflakeIdGenerator.nextId()).thenReturn(31L);
        when(claimService.claim(eq(20L), eq(TENANT_ID), eq(20), eq(3), eq(120L), any()))
                .thenReturn(new ManifestBackfillClaim(TENANT_ID, 20L, "claim-a", List.of(claimedItem)))
                .thenReturn(null);
        when(publisher.publish(TENANT_ID, 20L, 31L, "claim-a"))
                .thenThrow(new IllegalStateException(ManifestBackfillReason.CLAIM_LOST.name()));

        runService.processPlannedRun(20L);

        ArgumentCaptor<ManifestBackfillRun> updates = ArgumentCaptor.forClass(ManifestBackfillRun.class);
        verify(runMapper, org.mockito.Mockito.atLeast(2)).updateById(updates.capture());
        assertThat(updates.getAllValues().getLast().getStatus())
                .isEqualTo(ManifestBackfillRunStatus.PLANNED.name());
        verify(claimService, never()).failClaim(any(), any(), any(), anyBoolean(), any());
    }

    /**
     * Keeps database resolver failures retryable while classifying unexpected code failures for review.
     */
    @Test
    void shouldClassifyResolverExceptionsWithoutGuessingStorageFailure() {
        cn.flying.dao.dto.File file = new cn.flying.dao.dto.File()
                .setId(40L)
                .setTenantId(TENANT_ID);
        when(evidenceResolver.resolve(file))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"))
                .thenThrow(new IllegalArgumentException("invalid resolver state"));

        ManifestEvidenceResolution databaseFailure = ReflectionTestUtils.invokeMethod(
                runService, "resolveSafely", file);
        ManifestEvidenceResolution unexpectedFailure = ReflectionTestUtils.invokeMethod(
                runService, "resolveSafely", file);

        assertThat(databaseFailure).isNotNull();
        assertThat(databaseFailure.reason()).isEqualTo(ManifestBackfillReason.DATABASE_TRANSIENT);
        assertThat(databaseFailure.retryable()).isTrue();
        assertThat(unexpectedFailure).isNotNull();
        assertThat(unexpectedFailure.reason()).isEqualTo(ManifestBackfillReason.MANUAL_REVIEW);
        assertThat(unexpectedFailure.retryable()).isFalse();
    }

    /**
     * Builds one frozen scan boundary.
     */
    private ManifestBackfillRun frozenScan(Long id) {
        return new ManifestBackfillRun()
                .setId(id)
                .setTenantId(TENANT_ID)
                .setMode(ManifestBackfillMode.SCAN.name())
                .setStatus(ManifestBackfillRunStatus.SNAPSHOT_READY.name())
                .setSnapshotVersion("manifest-backfill-snapshot.v1")
                .setSnapshotDigest("sha256:" + "4".repeat(64))
                .setDeleted(0);
    }

    /**
     * Builds one derivative run phase fixture.
     */
    private ManifestBackfillRun derivativeRun(
            Long id,
            ManifestBackfillMode mode,
            String status,
            String snapshotDigest
    ) {
        return new ManifestBackfillRun()
                .setId(id)
                .setTenantId(TENANT_ID)
                .setSnapshotRunId(10L)
                .setMode(mode.name())
                .setStatus(status)
                .setSnapshotVersion("manifest-backfill-snapshot.v1")
                .setSnapshotDigest(snapshotDigest)
                .setDeleted(0);
    }

    /**
     * Builds one immutable candidate fixture.
     */
    private ManifestBackfillItem backfillableItem(Long id, Long runId, String status) {
        return new ManifestBackfillItem()
                .setId(id)
                .setRunId(runId)
                .setTenantId(TENANT_ID)
                .setFileId(40L)
                .setFileVersion(1)
                .setOwnerUserId(22L)
                .setStatus(status)
                .setClassification(ManifestBackfillClassification.BACKFILLABLE.name())
                .setReasonCode(ManifestBackfillReason.BACKFILLABLE_EVIDENCE.name())
                .setRetryable(0)
                .setEvidenceDigest("sha256:" + "5".repeat(64))
                .setAttemptCount(0)
                .setDeleted(0);
    }
}
