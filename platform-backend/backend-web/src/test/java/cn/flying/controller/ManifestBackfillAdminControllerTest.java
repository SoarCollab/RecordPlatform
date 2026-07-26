package cn.flying.controller;

import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecureIdCodec;
import cn.flying.dao.entity.ManifestBackfillItem;
import cn.flying.dao.entity.ManifestBackfillRun;
import cn.flying.dao.entity.ManifestReferenceCensus;
import cn.flying.dao.entity.ManifestReferenceSweepMark;
import cn.flying.dao.vo.manifest.ManifestBackfillCreateRequest;
import cn.flying.dao.vo.manifest.ManifestReferenceSweepMarkRequest;
import cn.flying.service.manifest.backfill.ManifestBackfillMode;
import cn.flying.service.manifest.backfill.ManifestBackfillRunService;
import cn.flying.service.manifest.backfill.ManifestReferenceCensusService;
import cn.flying.service.manifest.backfill.ManifestReferenceSweepService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies the external-ID and response-shaping boundary of manifest governance administration.
 */
@ExtendWith(MockitoExtension.class)
class ManifestBackfillAdminControllerTest {

    private static final Long TENANT_ID = 11L;
    private static final Long USER_ID = 22L;

    @Mock
    private ManifestBackfillRunService runService;

    @Mock
    private ManifestReferenceCensusService censusService;

    @Mock
    private ManifestReferenceSweepService sweepService;

    private ManifestBackfillAdminController controller;

    /**
     * Installs a deterministic external-ID codec and creates the thin controller boundary.
     */
    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(
                IdUtils.class,
                "secureIdCodec",
                new SecureIdCodec("SecureTestKey4UnitTests2026XyZ789AbCdEfGhIjKlMnOpQrStUvWxYz1234"));
        controller = new ManifestBackfillAdminController(runService, censusService, sweepService);
    }

    /**
     * Creates scan and derivative runs while decoding only the derivative snapshot identifier.
     */
    @Test
    void shouldCreateScanAndApplyRunsFromTypedRequests() {
        ManifestBackfillRun scan = run(101L, null, "SCAN", "PLANNED");
        ManifestBackfillRun apply = run(102L, 101L, "APPLY", "PLANNED");
        String snapshotExternalId = IdUtils.toExternalId(101L);
        when(runService.createRun(TENANT_ID, USER_ID, ManifestBackfillMode.SCAN, null)).thenReturn(scan);
        when(runService.createRun(TENANT_ID, USER_ID, ManifestBackfillMode.APPLY, 101L)).thenReturn(apply);

        var scanResult = controller.createRun(
                USER_ID, TENANT_ID, new ManifestBackfillCreateRequest(" scan ", null));
        var applyResult = controller.createRun(
                USER_ID, TENANT_ID, new ManifestBackfillCreateRequest("apply", snapshotExternalId));

        assertThat(IdUtils.fromExternalId(scanResult.getData().id())).isEqualTo(101L);
        assertThat(scanResult.getData().totalCount()).isZero();
        assertThat(IdUtils.fromExternalId(applyResult.getData().snapshotRunId())).isEqualTo(101L);
        verify(runService).createRun(TENANT_ID, USER_ID, ManifestBackfillMode.SCAN, null);
        verify(runService).createRun(TENANT_ID, USER_ID, ManifestBackfillMode.APPLY, 101L);
    }

    /**
     * Rejects unknown modes and malformed external IDs before invoking the run service.
     */
    @Test
    void shouldRejectInvalidModeAndExternalIdentifiers() {
        assertThatThrownBy(() -> controller.createRun(
                USER_ID, TENANT_ID, new ManifestBackfillCreateRequest("erase", null)))
                .isInstanceOf(GeneralException.class);
        assertThatThrownBy(() -> controller.getRun(TENANT_ID, "not-an-external-id"))
                .isInstanceOf(GeneralException.class);

        verifyNoInteractions(runService);
    }

    /**
     * Lists, reads, pauses, resumes, and retries runs through decoded external identifiers.
     */
    @Test
    void shouldDelegateRunLifecycleOperationsWithExternalIds() {
        ManifestBackfillRun run = run(101L, null, "APPLY", "PAUSED");
        String runId = IdUtils.toExternalId(101L);
        String itemId = IdUtils.toExternalId(201L);
        when(runService.getRuns(TENANT_ID, 25)).thenReturn(List.of(run));
        when(runService.getRun(TENANT_ID, 101L)).thenReturn(run);
        when(runService.pause(TENANT_ID, 101L)).thenReturn(run);
        when(runService.resume(TENANT_ID, 101L)).thenReturn(run);
        when(runService.retryItem(TENANT_ID, 101L, 201L)).thenReturn(run);

        assertThat(controller.listRuns(TENANT_ID, 25).getData()).hasSize(1);
        assertThat(IdUtils.fromExternalId(controller.getRun(TENANT_ID, runId).getData().id()))
                .isEqualTo(101L);
        assertThat(controller.pause(TENANT_ID, runId).getData().status()).isEqualTo("PAUSED");
        assertThat(controller.resume(TENANT_ID, runId).getData().status()).isEqualTo("PAUSED");
        assertThat(controller.retryItem(TENANT_ID, runId, itemId).getData().status()).isEqualTo("PAUSED");

        verify(runService).getRuns(TENANT_ID, 25);
        verify(runService).pause(TENANT_ID, 101L);
        verify(runService).resume(TENANT_ID, 101L);
        verify(runService).retryItem(TENANT_ID, 101L, 201L);
    }

    /**
     * Maps item pages without exposing evidence payloads and emits a cursor only for a full page.
     */
    @Test
    void shouldMapItemsAndEmitOnlyFullPageCursor() {
        String runId = IdUtils.toExternalId(101L);
        String cursor = IdUtils.toExternalId(199L);
        ManifestBackfillItem first = item(201L);
        ManifestBackfillItem second = item(202L).setAttemptCount(null).setLegacyDownloadAllowed(0);
        when(runService.getItems(TENANT_ID, 101L, 199L,
                "FAILED", "BACKFILLABLE", "DATABASE_TRANSIENT", 2))
                .thenReturn(List.of(first, second));
        when(runService.getItems(TENANT_ID, 101L, null,
                null, null, null, 3))
                .thenReturn(List.of(first));

        var fullPage = controller.listItems(TENANT_ID, runId, cursor,
                "FAILED", "BACKFILLABLE", "DATABASE_TRANSIENT", 2).getData();
        var shortPage = controller.listItems(TENANT_ID, runId, null,
                null, null, null, 3).getData();

        assertThat(fullPage.records()).hasSize(2);
        assertThat(IdUtils.fromExternalId(fullPage.nextCursor())).isEqualTo(202L);
        assertThat(fullPage.records().getFirst().retryable()).isTrue();
        assertThat(fullPage.records().getFirst().legacyDownloadAllowed()).isTrue();
        assertThat(fullPage.records().getFirst().attemptCount()).isEqualTo(2);
        assertThat(shortPage.nextCursor()).isNull();
        assertThat(shortPage.records().getFirst().evidenceDigest()).startsWith("sha256:");
    }

    /**
     * Creates a census and a sweep mark while preserving exact object identity in the response.
     */
    @Test
    void shouldCreateCensusAndSweepMark() {
        Date completedAt = new Date(1_700_000_000_000L);
        ManifestReferenceCensus census = new ManifestReferenceCensus()
                .setId(301L)
                .setTenantId(TENANT_ID)
                .setStatus("COMPLETED")
                .setCensusDigest("sha256:" + "a".repeat(64))
                .setKnownReferenceCount(null)
                .setUnknownHoldCount(2L)
                .setCompletedAt(completedAt);
        ManifestReferenceSweepMark mark = new ManifestReferenceSweepMark()
                .setId(401L)
                .setTenantId(TENANT_ID)
                .setPathTenantId(TENANT_ID)
                .setStoragePath("storage/tenant/11/chunk")
                .setCipherHash("sha256:" + "b".repeat(64))
                .setContentLength(512L)
                .setEtag("etag-1")
                .setMarkCensusId(301L)
                .setStatus("MARKED");
        when(censusService.createCensus(TENANT_ID)).thenReturn(census);
        when(sweepService.markObject(TENANT_ID, mark.getStoragePath(), mark.getCipherHash()))
                .thenReturn(mark);

        var censusResult = controller.createCensus(TENANT_ID).getData();
        var markResult = controller.markSweepObject(TENANT_ID,
                new ManifestReferenceSweepMarkRequest(mark.getStoragePath(), mark.getCipherHash())).getData();

        assertThat(IdUtils.fromExternalId(censusResult.id())).isEqualTo(301L);
        assertThat(censusResult.knownReferenceCount()).isZero();
        assertThat(censusResult.unknownHoldCount()).isEqualTo(2L);
        assertThat(IdUtils.fromExternalId(markResult.markCensusId())).isEqualTo(301L);
        assertThat(markResult.storagePath()).isEqualTo(mark.getStoragePath());
        assertThat(markResult.cipherHash()).isEqualTo(mark.getCipherHash());
        verify(censusService).createCensus(TENANT_ID);
        verify(sweepService).markObject(TENANT_ID, mark.getStoragePath(), mark.getCipherHash());
    }

    /**
     * Builds a run response fixture with nullable counters to exercise response normalization.
     */
    private ManifestBackfillRun run(Long id, Long snapshotRunId, String mode, String status) {
        return new ManifestBackfillRun()
                .setId(id)
                .setTenantId(TENANT_ID)
                .setSnapshotRunId(snapshotRunId)
                .setMode(mode)
                .setStatus(status)
                .setSnapshotVersion("manifest-backfill-snapshot.v1")
                .setSnapshotDigest("sha256:" + "c".repeat(64))
                .setTotalCount(null)
                .setPendingCount(1L)
                .setBackfilledCount(2L)
                .setReuploadCount(3L)
                .setUnrecoverableCount(4L)
                .setIgnoredCount(5L)
                .setFailedCount(6L)
                .setDeleted(0);
    }

    /**
     * Builds a terminal item response fixture with sensitive evidence kept only in the entity.
     */
    private ManifestBackfillItem item(Long id) {
        return new ManifestBackfillItem()
                .setId(id)
                .setRunId(101L)
                .setTenantId(TENANT_ID)
                .setFileId(501L)
                .setFileVersion(3)
                .setStatus("FAILED")
                .setClassification("BACKFILLABLE")
                .setReasonCode("DATABASE_TRANSIENT")
                .setRetryable(1)
                .setLegacyDownloadAllowed(1)
                .setEvidenceDigest("sha256:" + "d".repeat(64))
                .setEvidencePayload("sensitive-payload")
                .setManifestId(601L)
                .setAttemptCount(2)
                .setDeleted(0);
    }
}
