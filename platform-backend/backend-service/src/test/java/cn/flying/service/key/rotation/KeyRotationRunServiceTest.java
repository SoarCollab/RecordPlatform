package cn.flying.service.key.rotation;

import cn.flying.dao.entity.FileKeyEnvelope;
import cn.flying.dao.entity.KeyRotationItem;
import cn.flying.dao.entity.KeyRotationPolicy;
import cn.flying.dao.entity.KeyRotationRun;
import cn.flying.dao.mapper.FileKeyEnvelopeMapper;
import cn.flying.dao.mapper.KeyRotationItemMapper;
import cn.flying.dao.mapper.KeyRotationPolicyMapper;
import cn.flying.dao.mapper.KeyRotationRunMapper;
import cn.flying.service.key.FileKeyEnvelopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies bounded discovery, dry-run behavior, lifecycle controls, and retirement gates.
 */
@ExtendWith(MockitoExtension.class)
class KeyRotationRunServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-27T04:00:00Z");

    @Mock
    private KeyRotationRunMapper runMapper;

    @Mock
    private KeyRotationItemMapper itemMapper;

    @Mock
    private FileKeyEnvelopeMapper envelopeMapper;

    @Mock
    private KeyRotationPolicyMapper policyMapper;

    @Mock
    private KeyRotationAuditService auditService;

    @Mock
    private KeyRotationMetrics metrics;

    private KeyRotationRunService service;

    /**
     * Creates the lifecycle coordinator around isolated mapper and observability boundaries.
     */
    @BeforeEach
    void setUp() {
        service = new KeyRotationRunService(
                runMapper, itemMapper, envelopeMapper, policyMapper, auditService, metrics);
    }

    /**
     * Proves dry-run discovery covers every recipient type without creating executable work.
     */
    @Test
    void shouldDiscoverBoundedDryRunCandidatesForAllRecipientTypes() {
        KeyRotationRun run = run(KeyRotationStates.MODE_DRY_RUN)
                .setStatus(KeyRotationStates.RUN_PLANNED)
                .setSnapshotMaxEnvelopeId(30L)
                .setBatchSize(10);
        List<FileKeyEnvelope> page = List.of(
                envelope(10L, FileKeyEnvelopeService.RECIPIENT_TYPE_OWNER),
                envelope(20L, FileKeyEnvelopeService.RECIPIENT_TYPE_SHARE),
                envelope(30L, FileKeyEnvelopeService.RECIPIENT_TYPE_FRIEND_SHARE));
        when(runMapper.selectRunForUpdate(11L, 101L)).thenReturn(run);
        when(envelopeMapper.selectRotationCandidatePage(11L, 0L, 30L, 10)).thenReturn(page);
        when(itemMapper.insertIgnore(any())).thenReturn(1);
        when(runMapper.selectById(101L)).thenReturn(run);
        ArgumentCaptor<KeyRotationItem> inserted = ArgumentCaptor.forClass(KeyRotationItem.class);

        service.discoverNextPage(11L, 101L, NOW);

        verify(itemMapper, org.mockito.Mockito.times(3)).insertIgnore(inserted.capture());
        assertThat(inserted.getAllValues())
                .extracting(KeyRotationItem::getRecipientType)
                .containsExactly("OWNER", "SHARE", "FRIEND_SHARE");
        assertThat(inserted.getAllValues())
                .allSatisfy(item -> {
                    assertThat(item.getStatus()).isEqualTo(KeyRotationStates.ITEM_SKIPPED);
                    assertThat(item.getOutcome()).isEqualTo("DRY_RUN_CANDIDATE");
                });
        assertThat(run.getDiscoveryComplete()).isEqualTo(1);
        assertThat(run.getScanCursorId()).isEqualTo(30L);
    }

    /**
     * Proves dry-run distinguishes an exact persisted target without claiming provider verification.
     */
    @Test
    void shouldClassifyExactDryRunTargetWithoutProviderCalls() {
        KeyRotationRun run = run(KeyRotationStates.MODE_DRY_RUN)
                .setStatus(KeyRotationStates.RUN_PLANNED)
                .setSnapshotMaxEnvelopeId(10L)
                .setBatchSize(10);
        FileKeyEnvelope target = envelope(10L, FileKeyEnvelopeService.RECIPIENT_TYPE_OWNER)
                .setKeyVersion(2)
                .setKmsProvider("vault-transit")
                .setProviderContractVersion(1)
                .setKmsKeyId("tenant-key")
                .setProviderKeyVersion("7")
                .setWrappingAlgorithm("VAULT-TRANSIT")
                .setContextSchema("external-v2");
        when(runMapper.selectRunForUpdate(11L, 101L)).thenReturn(run);
        when(envelopeMapper.selectRotationCandidatePage(11L, 0L, 10L, 10))
                .thenReturn(List.of(target));
        when(itemMapper.insertIgnore(any())).thenReturn(1);
        when(runMapper.selectById(101L)).thenReturn(run);
        ArgumentCaptor<KeyRotationItem> inserted = ArgumentCaptor.forClass(KeyRotationItem.class);

        service.discoverNextPage(11L, 101L, NOW);

        verify(itemMapper).insertIgnore(inserted.capture());
        assertThat(inserted.getValue().getOutcome()).isEqualTo("DRY_RUN_ALREADY_TARGET");
    }

    /**
     * Proves a clean APPLY run becomes complete but remains blocked until its rollback grace expires.
     */
    @Test
    void shouldCompleteApplyRunWithDeferredRetirement() {
        KeyRotationRun run = run(KeyRotationStates.MODE_APPLY)
                .setStatus(KeyRotationStates.RUN_RUNNING)
                .setDiscoveryComplete(1)
                .setPendingCount(0L)
                .setRunningCount(0L)
                .setFailedCount(0L)
                .setRemainingCount(0L)
                .setGracePeriodSeconds(600L);
        when(runMapper.selectRunForUpdate(11L, 101L)).thenReturn(run);
        when(itemMapper.selectCount(any())).thenReturn(0L);

        KeyRotationRun result = service.refreshAndFinalize(11L, 101L, NOW);

        assertThat(result.getStatus()).isEqualTo(KeyRotationStates.RUN_COMPLETED);
        assertThat(result.getCompletedAt().toInstant()).isEqualTo(NOW);
        assertThat(result.getRetirementStatus()).isEqualTo(KeyRotationStates.RETIREMENT_NOT_READY);
        assertThat(result.getRetirementEligibleAt().toInstant()).isEqualTo(NOW.plusSeconds(600));
        verify(runMapper).updateById(run);
        verify(auditService).record(run, null, 51L, "COMPLETE", "SUCCESS", null);
        verify(metrics).refresh(0L, 0L, true);
    }

    /**
     * Proves only the policy's latest clean APPLY run becomes retirement-ready after grace.
     */
    @Test
    void shouldPromoteLatestCleanApplyRunAfterGrace() {
        KeyRotationRun run = run(KeyRotationStates.MODE_APPLY)
                .setStatus(KeyRotationStates.RUN_COMPLETED)
                .setFailedCount(0L)
                .setRemainingCount(0L)
                .setRetirementStatus(KeyRotationStates.RETIREMENT_NOT_READY)
                .setRetirementEligibleAt(java.util.Date.from(NOW.minusSeconds(1)));
        KeyRotationPolicy policy = new KeyRotationPolicy()
                .setId(71L)
                .setTenantId(11L)
                .setLastRunId(101L)
                .setRetirementStatus(KeyRotationStates.RETIREMENT_NOT_READY);
        when(runMapper.selectRunForUpdate(11L, 101L)).thenReturn(run);
        when(policyMapper.selectTenantPolicyForUpdate(11L)).thenReturn(policy);

        KeyRotationRun result = service.refreshRetirementReadiness(11L, 101L, NOW);

        assertThat(result.getRetirementStatus()).isEqualTo(KeyRotationStates.RETIREMENT_READY);
        assertThat(policy.getRetirementStatus()).isEqualTo(KeyRotationStates.RETIREMENT_READY);
        verify(runMapper).updateById(run);
        verify(policyMapper).updateById(policy);
        verify(auditService).record(run, null, 0L, "RETIREMENT_READY", "SUCCESS", null);
    }

    /**
     * Proves pause and resume preserve cursor/attempt state while using only the allowed matrix.
     */
    @Test
    void shouldPauseAndResumeWithoutResettingProgress() {
        KeyRotationRun run = run(KeyRotationStates.MODE_APPLY)
                .setStatus(KeyRotationStates.RUN_RUNNING)
                .setScanCursorId(500L);
        when(runMapper.selectRunForUpdate(11L, 101L)).thenReturn(run);

        KeyRotationRun paused = service.pause(11L, 51L, 101L);

        assertThat(paused.getStatus()).isEqualTo(KeyRotationStates.RUN_PAUSED);
        assertThat(paused.getScanCursorId()).isEqualTo(500L);

        KeyRotationRun resumed = service.resume(11L, 51L, 101L);

        assertThat(resumed.getStatus()).isEqualTo(KeyRotationStates.RUN_RUNNING);
        assertThat(resumed.getScanCursorId()).isEqualTo(500L);
        verify(runMapper, atLeastOnce()).updateById(run);
        verify(itemMapper, never()).retryFailed(11L, 101L);
    }

    /**
     * Proves cancellation is terminal and prevents the run from re-entering the runnable state matrix.
     */
    @Test
    void shouldCancelRunAtDurableBoundary() {
        KeyRotationRun run = run(KeyRotationStates.MODE_APPLY)
                .setStatus(KeyRotationStates.RUN_RUNNING);
        when(runMapper.selectRunForUpdate(11L, 101L)).thenReturn(run);

        KeyRotationRun cancelled = service.cancel(11L, 51L, 101L);

        assertThat(cancelled.getStatus()).isEqualTo(KeyRotationStates.RUN_CANCELLED);
        assertThat(cancelled.getCompletedAt()).isNotNull();
        verify(auditService).record(run, null, 51L, "CANCEL", "SUCCESS", null);
    }

    /**
     * Proves an explicit retry requeues terminal failures and clears stale retirement eligibility.
     */
    @Test
    void shouldRequeueFailedItemsForExplicitRetry() {
        KeyRotationRun run = run(KeyRotationStates.MODE_APPLY)
                .setStatus(KeyRotationStates.RUN_COMPLETED_WITH_FAILURES)
                .setCompletedAt(java.util.Date.from(NOW))
                .setRetirementEligibleAt(java.util.Date.from(NOW.plusSeconds(600)));
        KeyRotationRun refreshed = run(KeyRotationStates.MODE_APPLY)
                .setStatus(KeyRotationStates.RUN_RUNNING);
        when(runMapper.selectRunForUpdate(11L, 101L)).thenReturn(run);
        when(itemMapper.retryFailed(11L, 101L)).thenReturn(2);
        when(runMapper.selectById(101L)).thenReturn(refreshed);

        assertThat(service.retry(11L, 51L, 101L)).isSameAs(refreshed);

        assertThat(run.getCompletedAt()).isNull();
        assertThat(run.getRetirementEligibleAt()).isNull();
        verify(itemMapper).retryFailed(11L, 101L);
        verify(auditService).record(refreshed, null, 51L, "RETRY", "SUCCESS", null);
    }

    /**
     * Builds one durable run with stable target and retry snapshot fields.
     */
    private KeyRotationRun run(String mode) {
        return new KeyRotationRun()
                .setId(101L)
                .setTenantId(11L)
                .setPolicyId(71L)
                .setPolicyVersion(3L)
                .setMode(mode)
                .setTargetProvider("vault-transit")
                .setTargetProviderContract(1)
                .setTargetKeyId("tenant-key")
                .setTargetProviderKeyVersion("7")
                .setTargetWrappingAlgorithm("VAULT-TRANSIT")
                .setTargetContextSchema("external-v2")
                .setTargetLogicalKeyVersion(2)
                .setBatchSize(25)
                .setMaxItemsPerMinute(100)
                .setMaxAttempts(4)
                .setInitialBackoffSeconds(5L)
                .setMaxBackoffSeconds(60L)
                .setLeaseSeconds(120L)
                .setGracePeriodSeconds(600L)
                .setCreatedBy(51L)
                .setRetirementStatus(KeyRotationStates.RETIREMENT_NOT_READY)
                .setDeleted(0);
    }

    /**
     * Builds one active discovery row for the supplied recipient type.
     */
    private FileKeyEnvelope envelope(Long id, String recipientType) {
        return new FileKeyEnvelope()
                .setId(id)
                .setTenantId(11L)
                .setFileId(id + 100L)
                .setRecipientType(recipientType)
                .setRecipientId(id + 200L)
                .setStatus(FileKeyEnvelopeService.STATUS_ACTIVE)
                .setDeleted(0);
    }
}
