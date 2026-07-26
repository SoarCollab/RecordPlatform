package cn.flying.service.manifest.backfill;

import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.SnowflakeIdGenerator;
import cn.flying.dao.entity.ManifestBackfillItem;
import cn.flying.dao.entity.ManifestBackfillRun;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.ManifestBackfillItemMapper;
import cn.flying.dao.mapper.ManifestBackfillRunMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
     * 初始化运行状态更新使用的 MyBatis-Plus Lambda 元数据。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ManifestBackfillRun.class);
    }

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
     * Persists complete scan and derivative run defaults while preserving the frozen source identity.
     */
    @Test
    void shouldCreateScanAndDerivativeRunsWithCompleteDefaults() {
        ManifestBackfillRun directSource = frozenScan(10L);
        ManifestBackfillRun inheritedSource = frozenScan(11L).setSnapshotRunId(10L);
        when(snowflakeIdGenerator.nextId()).thenReturn(20L, 21L, 22L);
        when(runMapper.selectRunGlobally(10L)).thenReturn(directSource);
        when(runMapper.selectRunGlobally(11L)).thenReturn(inheritedSource);

        ManifestBackfillRun scan = runService.createRun(TENANT_ID, 22L, ManifestBackfillMode.SCAN, null);
        ManifestBackfillRun dryRun = runService.createRun(
                TENANT_ID, 22L, ManifestBackfillMode.DRY_RUN, 10L);
        ManifestBackfillRun apply = runService.createRun(
                TENANT_ID, 22L, ManifestBackfillMode.APPLY, 11L);

        assertThat(scan.getId()).isEqualTo(20L);
        assertThat(scan.getSnapshotRunId()).isNull();
        assertThat(scan.getStatus()).isEqualTo(ManifestBackfillRunStatus.PLANNED.name());
        assertThat(scan.getTotalCount()).isZero();
        assertThat(scan.getFailedCount()).isZero();
        assertThat(dryRun.getSnapshotRunId()).isEqualTo(10L);
        assertThat(apply.getSnapshotRunId()).isEqualTo(10L);
        verify(runMapper, times(3)).insert(any(ManifestBackfillRun.class));
    }

    /**
     * Rejects incomplete, rollout-disabled, and mutable snapshot run creation requests.
     */
    @Test
    void shouldRejectInvalidRunCreationRequests() {
        assertThatThrownBy(() -> runService.createRun(null, 22L, ManifestBackfillMode.SCAN, null))
                .isInstanceOf(GeneralException.class);
        assertThatThrownBy(() -> runService.createRun(TENANT_ID, 22L, ManifestBackfillMode.SCAN, 10L))
                .isInstanceOf(GeneralException.class);
        assertThatThrownBy(() -> runService.createRun(TENANT_ID, 22L, ManifestBackfillMode.DRY_RUN, null))
                .isInstanceOf(GeneralException.class);

        ReflectionTestUtils.setField(runService, "applyEnabled", false);
        assertThatThrownBy(() -> runService.createRun(TENANT_ID, 22L, ManifestBackfillMode.APPLY, 10L))
                .isInstanceOf(GeneralException.class);
        ReflectionTestUtils.setField(runService, "applyEnabled", true);
        when(runMapper.selectRunGlobally(10L)).thenReturn(
                frozenScan(10L).setStatus(ManifestBackfillRunStatus.SCANNING.name()));
        assertThatThrownBy(() -> runService.createRun(TENANT_ID, 22L, ManifestBackfillMode.DRY_RUN, 10L))
                .isInstanceOf(GeneralException.class);

        verify(runMapper, never()).insert(any(ManifestBackfillRun.class));
    }

    /**
     * Applies tenant hiding, bounded lists, normalized item filters, and cursor defaults.
     */
    @Test
    void shouldReadOnlyTenantRunsAndBoundItemQueries() {
        ManifestBackfillRun run = derivativeRun(20L, ManifestBackfillMode.APPLY, "PLANNED", "digest");
        ManifestBackfillItem item = backfillableItem(31L, 20L, "PENDING");
        when(runMapper.selectRunGlobally(20L)).thenReturn(run);
        when(runMapper.selectTenantRuns(TENANT_ID, 1)).thenReturn(List.of(run));
        when(runMapper.selectTenantRuns(TENANT_ID, 100)).thenReturn(List.of(run));
        when(itemMapper.selectItemPage(20L, TENANT_ID, 0L,
                "FAILED", "BACKFILLABLE", null, 100)).thenReturn(List.of(item));

        assertThat(runService.getRun(TENANT_ID, 20L)).isSameAs(run);
        assertThat(runService.getRuns(TENANT_ID, 0)).containsExactly(run);
        assertThat(runService.getRuns(TENANT_ID, 500)).containsExactly(run);
        assertThat(runService.getItems(TENANT_ID, 20L, null,
                " FAILED ", " BACKFILLABLE ", "  ", 500)).containsExactly(item);

        verify(itemMapper).selectItemPage(20L, TENANT_ID, 0L,
                "FAILED", "BACKFILLABLE", null, 100);
        assertThatThrownBy(() -> runService.getRun(null, 20L)).isInstanceOf(GeneralException.class);
        TenantContext.setTenantId(12L);
        assertThatThrownBy(() -> runService.getRuns(TENANT_ID, 10)).isInstanceOf(GeneralException.class);
    }

    /**
     * Pauses and resumes only through durable state transitions and returns the reloaded row.
     */
    @Test
    void shouldPauseAndResumeDurableRunStates() {
        ManifestBackfillRun active = derivativeRun(20L, ManifestBackfillMode.APPLY, "APPLYING", "digest");
        ManifestBackfillRun paused = derivativeRun(20L, ManifestBackfillMode.APPLY, "PAUSED", "digest");
        ManifestBackfillRun planned = derivativeRun(20L, ManifestBackfillMode.APPLY, "PLANNED", "digest");
        when(runMapper.selectRunGlobally(20L)).thenReturn(active, paused, paused, planned);
        when(runMapper.update(isNull(), any())).thenReturn(1, 1);

        assertThat(runService.pause(TENANT_ID, 20L).getStatus()).isEqualTo("PAUSED");
        assertThat(runService.resume(TENANT_ID, 20L).getStatus()).isEqualTo("PLANNED");

        verify(runMapper, times(2)).update(isNull(), any());
    }

    /**
     * Rejects pause and resume requests when the durable compare-and-set no longer owns the state.
     */
    @Test
    void shouldRejectLostPauseAndResumeTransitions() {
        ManifestBackfillRun run = derivativeRun(20L, ManifestBackfillMode.APPLY, "COMPLETED", "digest");
        when(runMapper.selectRunGlobally(20L)).thenReturn(run);
        when(runMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> runService.pause(TENANT_ID, 20L)).isInstanceOf(GeneralException.class);
        assertThatThrownBy(() -> runService.resume(TENANT_ID, 20L)).isInstanceOf(GeneralException.class);
    }

    /**
     * Requeues only an owned failed APPLY item and makes the owning run runnable again.
     */
    @Test
    void shouldRetryOwnedFailedApplyItem() {
        ManifestBackfillItem item = backfillableItem(31L, 20L, "FAILED").setRetryable(1);
        ManifestBackfillRun apply = derivativeRun(20L, ManifestBackfillMode.APPLY, "COMPLETED", "digest");
        ManifestBackfillRun planned = derivativeRun(20L, ManifestBackfillMode.APPLY, "PLANNED", "digest");
        when(itemMapper.selectById(31L)).thenReturn(item);
        when(runMapper.selectRunGlobally(20L)).thenReturn(apply, planned);
        when(itemMapper.requeueFailed(31L, TENANT_ID)).thenReturn(1);

        ManifestBackfillRun result = runService.retryItem(TENANT_ID, 20L, 31L);

        assertThat(result.getStatus()).isEqualTo("PLANNED");
        verify(itemMapper).requeueFailed(31L, TENANT_ID);
        verify(runMapper).update(isNull(), any());
    }

    /**
     * Rejects retry requests for another run, a dry-run item, or a non-retryable failed row.
     */
    @Test
    void shouldRejectInvalidRetryRequests() {
        ManifestBackfillItem wrongRun = backfillableItem(31L, 21L, "FAILED");
        when(itemMapper.selectById(31L)).thenReturn(wrongRun);
        assertThatThrownBy(() -> runService.retryItem(TENANT_ID, 20L, 31L))
                .isInstanceOf(GeneralException.class);

        ManifestBackfillItem item = backfillableItem(32L, 20L, "FAILED");
        when(itemMapper.selectById(32L)).thenReturn(item);
        when(runMapper.selectRunGlobally(20L)).thenReturn(
                derivativeRun(20L, ManifestBackfillMode.DRY_RUN, "COMPLETED", "digest"));
        assertThatThrownBy(() -> runService.retryItem(TENANT_ID, 20L, 32L))
                .isInstanceOf(GeneralException.class);

        ManifestBackfillItem retryable = backfillableItem(33L, 20L, "FAILED");
        when(itemMapper.selectById(33L)).thenReturn(retryable);
        when(runMapper.selectRunGlobally(20L)).thenReturn(
                derivativeRun(20L, ManifestBackfillMode.APPLY, "COMPLETED", "digest"));
        when(itemMapper.requeueFailed(33L, TENANT_ID)).thenReturn(0);
        assertThatThrownBy(() -> runService.retryItem(TENANT_ID, 20L, 33L))
                .isInstanceOf(GeneralException.class);
    }

    /**
     * Honors the worker gate, null poll result, and isolated tenant execution for runnable rows.
     */
    @Test
    void shouldPollRunnableRowsOnlyWhenWorkerEnabled() {
        ReflectionTestUtils.setField(runService, "workerEnabled", false);
        runService.processPlannedRuns();
        verifyNoInteractions(runMapper);

        ReflectionTestUtils.setField(runService, "workerEnabled", true);
        when(runMapper.selectRunnableRuns(any(Date.class), eq(4))).thenReturn(null);
        runService.processPlannedRuns();

        ManifestBackfillRun otherTenant = derivativeRun(
                20L, ManifestBackfillMode.SCAN, "PLANNED", null).setTenantId(12L);
        when(runMapper.selectRunnableRuns(any(Date.class), eq(4))).thenReturn(List.of(otherTenant));
        when(runMapper.selectRunGlobally(20L)).thenReturn(null);
        runService.processPlannedRuns();

        assertThat(TenantContext.requireTenantId()).isEqualTo(TENANT_ID);
        verify(runMapper).selectRunGlobally(20L);
    }

    /**
     * Ignores missing, cross-tenant, and concurrently claimed runs before doing work.
     */
    @Test
    void shouldIgnoreRunWhenOwnershipOrRunClaimIsMissing() {
        runService.processPlannedRun(20L);

        when(runMapper.selectRunGlobally(21L)).thenReturn(
                derivativeRun(21L, ManifestBackfillMode.SCAN, "PLANNED", null).setTenantId(12L));
        runService.processPlannedRun(21L);

        when(runMapper.selectRunGlobally(22L)).thenReturn(
                derivativeRun(22L, ManifestBackfillMode.SCAN, "PLANNED", null));
        when(runMapper.claimPlannedRun(eq(22L), eq("SCANNING"), any(Date.class))).thenReturn(0);
        runService.processPlannedRun(22L);

        verifyNoInteractions(fileMapper, publisher);
    }

    /**
     * Scans deterministic keyset pages, advances the cursor, and seals terminal status counts.
     */
    @Test
    void shouldScanCandidatePagesAndSealSnapshotDigest() {
        ManifestBackfillRun planned = derivativeRun(20L, ManifestBackfillMode.SCAN, "PLANNED", null)
                .setCursorFileId(null);
        ManifestBackfillRun scanning = derivativeRun(20L, ManifestBackfillMode.SCAN, "SCANNING", null);
        cn.flying.dao.dto.File file = new cn.flying.dao.dto.File()
                .setId(40L)
                .setTenantId(TENANT_ID)
                .setUid(22L)
                .setVersion(null);
        ManifestEvidenceResolution resolution = new ManifestEvidenceResolution(
                ManifestBackfillItemStatus.IGNORED,
                ManifestBackfillClassification.IGNORED,
                ManifestBackfillReason.FILE_NOT_SUCCESS,
                false, false, "sha256:" + "a".repeat(64), null, null);
        ManifestBackfillItem persisted = backfillableItem(31L, 20L, "IGNORED")
                .setClassification(ManifestBackfillClassification.IGNORED.name())
                .setReasonCode(ManifestBackfillReason.FILE_NOT_SUCCESS.name());
        when(runMapper.selectRunGlobally(20L)).thenReturn(planned, scanning, scanning, scanning);
        when(runMapper.claimPlannedRun(eq(20L), eq("SCANNING"), any(Date.class))).thenReturn(1);
        when(runMapper.touchRunLease(20L, TENANT_ID, "SCANNING")).thenReturn(1);
        when(fileMapper.selectManifestBackfillCandidates(TENANT_ID, 0L, 100))
                .thenReturn(List.of(file));
        when(fileMapper.selectManifestBackfillCandidates(TENANT_ID, 40L, 100))
                .thenReturn(List.of());
        when(evidenceResolver.resolve(file)).thenReturn(resolution);
        when(snowflakeIdGenerator.nextId()).thenReturn(31L);
        when(itemMapper.selectRunItems(20L, TENANT_ID)).thenReturn(List.of(persisted));

        runService.processPlannedRun(20L);

        ArgumentCaptor<ManifestBackfillItem> itemCaptor = ArgumentCaptor.forClass(ManifestBackfillItem.class);
        verify(itemMapper).insertIgnoreSnapshot(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getFileVersion()).isZero();
        assertThat(itemCaptor.getValue().getStatus()).isEqualTo("IGNORED");
        verify(runMapper).advanceScanCursor(20L, TENANT_ID, 40L);
        ArgumentCaptor<ManifestBackfillRun> updateCaptor = ArgumentCaptor.forClass(ManifestBackfillRun.class);
        verify(runMapper).updateById(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getStatus()).isEqualTo("SNAPSHOT_READY");
        assertThat(updateCaptor.getValue().getIgnoredCount()).isEqualTo(1L);
        assertThat(updateCaptor.getValue().getSnapshotDigest()).startsWith("sha256:");
    }

    /**
     * Converts a lost scan heartbeat into an auditable run failure without publishing a manifest.
     */
    @Test
    void shouldFailRunWhenScanHeartbeatFenceIsLost() {
        ManifestBackfillRun planned = derivativeRun(20L, ManifestBackfillMode.SCAN, "PLANNED", null);
        ManifestBackfillRun scanning = derivativeRun(20L, ManifestBackfillMode.SCAN, "SCANNING", null);
        cn.flying.dao.dto.File file = new cn.flying.dao.dto.File()
                .setId(40L).setTenantId(TENANT_ID).setUid(22L).setVersion(1);
        when(runMapper.selectRunGlobally(20L)).thenReturn(planned, scanning, scanning);
        when(runMapper.claimPlannedRun(eq(20L), eq("SCANNING"), any(Date.class))).thenReturn(1);
        when(fileMapper.selectManifestBackfillCandidates(TENANT_ID, 0L, 100)).thenReturn(List.of(file));
        when(runMapper.touchRunLease(20L, TENANT_ID, "SCANNING")).thenReturn(0);

        runService.processPlannedRun(20L);

        ArgumentCaptor<ManifestBackfillRun> updateCaptor = ArgumentCaptor.forClass(ManifestBackfillRun.class);
        verify(runMapper).updateById(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(updateCaptor.getValue().getLastErrorClass()).isEqualTo("IllegalStateException");
        verify(itemMapper, never()).insertIgnoreSnapshot(any());
    }

    /**
     * Publishes an APPLY batch and classifies every failure family through the exact claim fence.
     */
    @Test
    void shouldApplyFrozenSnapshotAndClassifyPublicationFailures() {
        ManifestBackfillRun source = frozenScan(10L);
        ManifestBackfillRun planned = derivativeRun(20L, ManifestBackfillMode.APPLY, "PLANNED", null);
        ManifestBackfillRun applying = derivativeRun(20L, ManifestBackfillMode.APPLY, "APPLYING",
                source.getSnapshotDigest());
        ManifestBackfillItem sourceBackfillable = backfillableItem(30L, 10L, "PENDING");
        ManifestBackfillItem sourceTerminal = backfillableItem(38L, 10L, "REUPLOAD_REQUIRED")
                .setClassification(ManifestBackfillClassification.REUPLOAD_REQUIRED.name());
        List<ManifestBackfillItem> claimed = List.of(
                runningItem(31L), runningItem(32L), runningItem(33L), runningItem(34L),
                runningItem(35L), runningItem(36L), runningItem(37L));
        when(runMapper.selectRunGlobally(20L)).thenReturn(planned, applying, applying, applying);
        when(runMapper.selectRunGlobally(10L)).thenReturn(source);
        when(runMapper.claimPlannedRun(eq(20L), eq("APPLYING"), any(Date.class))).thenReturn(1);
        when(runMapper.touchRunLease(20L, TENANT_ID, "APPLYING")).thenReturn(1);
        when(itemMapper.selectRunItems(10L, TENANT_ID))
                .thenReturn(List.of(sourceBackfillable, sourceTerminal));
        when(snowflakeIdGenerator.nextId()).thenReturn(41L, 42L);
        ManifestBackfillClaim claim = new ManifestBackfillClaim(TENANT_ID, 20L, "claim-a", claimed);
        when(claimService.claim(eq(20L), eq(TENANT_ID), eq(20), eq(3), eq(120L), any(Instant.class)))
                .thenReturn(claim)
                .thenReturn(null);
        when(publisher.publish(eq(TENANT_ID), eq(20L), any(Long.class), eq("claim-a")))
                .thenAnswer(invocation -> {
                    Long itemId = invocation.getArgument(2);
                    return switch (itemId.intValue()) {
                        case 31 -> null;
                        case 32 -> throw new ManifestEvidenceResolver.ManifestEvidenceChangedException(
                                ManifestBackfillReason.STORAGE_RPC_TRANSIENT);
                        case 33 -> throw new DataAccessResourceFailureException("database unavailable");
                        case 34 -> throw new IllegalStateException(ManifestBackfillReason.CLAIM_LOST.name());
                        case 35 -> throw new IllegalStateException("publication conflict");
                        case 36 -> throw new IllegalArgumentException("unexpected publication state");
                        case 37 -> throw new ManifestEvidenceResolver.ManifestEvidenceChangedException(
                                ManifestBackfillReason.OBJECT_HEAD_MISMATCH);
                        default -> throw new AssertionError("unexpected item id");
                    };
                });
        doAnswer(invocation -> {
            ManifestBackfillItem item = invocation.getArgument(0);
            if (Long.valueOf(33L).equals(item.getId())) {
                throw new IllegalStateException(ManifestBackfillReason.CLAIM_LOST.name());
            }
            return null;
        }).when(claimService).failClaim(any(ManifestBackfillItem.class), eq("claim-a"),
                any(ManifestBackfillReason.class), anyBoolean(), anyString());
        when(itemMapper.selectRunItems(20L, TENANT_ID)).thenReturn(List.of(
                statusItem(51L, "BACKFILLED", 0, 1),
                statusItem(52L, "REUPLOAD_REQUIRED", 0, 1),
                statusItem(53L, "UNRECOVERABLE", 0, 1),
                statusItem(54L, "IGNORED", 0, 1),
                statusItem(55L, "FAILED", 0, 3)));

        runService.processPlannedRun(20L);

        ArgumentCaptor<ManifestBackfillItem> cloneCaptor = ArgumentCaptor.forClass(ManifestBackfillItem.class);
        verify(itemMapper, times(2)).insertIgnoreSnapshot(cloneCaptor.capture());
        assertThat(cloneCaptor.getAllValues()).extracting(ManifestBackfillItem::getStatus)
                .containsExactly("PENDING", "REUPLOAD_REQUIRED");
        verify(claimService).failClaim(claimed.get(1), "claim-a",
                ManifestBackfillReason.STORAGE_RPC_TRANSIENT, true,
                ManifestEvidenceResolver.ManifestEvidenceChangedException.class.getSimpleName());
        verify(claimService).failClaim(claimed.get(4), "claim-a",
                ManifestBackfillReason.MANUAL_REVIEW, false, IllegalStateException.class.getSimpleName());
        verify(claimService).failClaim(claimed.get(5), "claim-a",
                ManifestBackfillReason.MANUAL_REVIEW, false, IllegalArgumentException.class.getSimpleName());
        verify(claimService).failClaim(claimed.get(6), "claim-a",
                ManifestBackfillReason.OBJECT_HEAD_MISMATCH, false,
                ManifestEvidenceResolver.ManifestEvidenceChangedException.class.getSimpleName());
        ArgumentCaptor<ManifestBackfillRun> updateCaptor = ArgumentCaptor.forClass(ManifestBackfillRun.class);
        verify(runMapper, org.mockito.Mockito.atLeast(2)).updateById(updateCaptor.capture());
        ManifestBackfillRun finalUpdate = updateCaptor.getAllValues().getLast();
        assertThat(finalUpdate.getStatus()).isEqualTo("COMPLETED");
        assertThat(finalUpdate.getBackfilledCount()).isEqualTo(1L);
        assertThat(finalUpdate.getReuploadCount()).isEqualTo(1L);
        assertThat(finalUpdate.getUnrecoverableCount()).isEqualTo(1L);
        assertThat(finalUpdate.getIgnoredCount()).isEqualTo(1L);
        assertThat(finalUpdate.getFailedCount()).isEqualTo(1L);
    }

    /**
     * Parses supported modes case-insensitively and rejects empty or unknown values.
     */
    @Test
    void shouldParseOnlySupportedRunModes() {
        assertThat(ManifestBackfillMode.parse(" dry_run ")).isEqualTo(ManifestBackfillMode.DRY_RUN);
        assertThatThrownBy(() -> ManifestBackfillMode.parse(" ")).isInstanceOf(GeneralException.class);
        assertThatThrownBy(() -> ManifestBackfillMode.parse("DELETE")).isInstanceOf(GeneralException.class);
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

    /**
     * Builds one currently owned apply item.
     */
    private ManifestBackfillItem runningItem(Long id) {
        return backfillableItem(id, 20L, ManifestBackfillItemStatus.RUNNING.name())
                .setClaimToken("claim-a")
                .setAttemptCount(1)
                .setLeaseExpiresAt(Date.from(Instant.now().plusSeconds(60)));
    }

    /**
     * Builds one final aggregation fixture with explicit retry exhaustion state.
     */
    private ManifestBackfillItem statusItem(Long id, String status, int retryable, int attempts) {
        return backfillableItem(id, 20L, status)
                .setRetryable(retryable)
                .setAttemptCount(attempts);
    }
}
