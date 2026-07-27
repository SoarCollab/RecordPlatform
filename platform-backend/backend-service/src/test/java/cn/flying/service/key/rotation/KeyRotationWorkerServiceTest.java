package cn.flying.service.key.rotation;

import cn.flying.dao.entity.KeyRotationItem;
import cn.flying.dao.entity.KeyRotationRun;
import cn.flying.service.key.AutomatedEnvelopeRotationResult;
import cn.flying.service.key.FileKeyEnvelopeService;
import cn.flying.service.key.KeyWrappingFailure;
import cn.flying.service.key.KeyWrappingFailureCategory;
import cn.flying.service.key.WrappingKeyReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies bounded worker orchestration for previews, provider failures, revocations, and alerts.
 */
@ExtendWith(MockitoExtension.class)
class KeyRotationWorkerServiceTest {

    @Mock
    private KeyRotationRunService runService;

    @Mock
    private KeyRotationClaimService claimService;

    @Mock
    private FileKeyEnvelopeService envelopeService;

    @Mock
    private KeyRotationAuditService auditService;

    @Mock
    private KeyRotationMetrics metrics;

    @Mock
    private KeyRotationAlertService alertService;

    private KeyRotationWorkerService service;

    /**
     * Creates the worker around isolated lifecycle, provider, and observability boundaries.
     */
    @BeforeEach
    void setUp() {
        service = new KeyRotationWorkerService(
                runService, claimService, envelopeService, auditService, metrics, alertService);
    }

    /**
     * Proves a dry-run seals discovered candidates without any wrapping-provider operation.
     */
    @Test
    void shouldFinalizeDryRunWithoutClaimingOrRotating() {
        KeyRotationRun run = run(KeyRotationStates.MODE_DRY_RUN);
        when(runService.findRunnable(11L)).thenReturn(run);
        when(runService.discoverNextPage(eq(11L), eq(101L), any())).thenReturn(run);

        service.runTenant(11L);

        verify(runService).refreshAndFinalize(eq(11L), eq(101L), any());
        verifyNoInteractions(claimService, envelopeService, auditService, metrics, alertService);
    }

    /**
     * Proves a retryable KMS throttle emits a terminal alert when the immutable attempt limit is exhausted.
     */
    @Test
    void shouldAlertWhenRetryableThrottleExhaustsAttempts() {
        KeyRotationRun run = run(KeyRotationStates.MODE_APPLY).setMaxAttempts(3);
        KeyRotationItem item = item().setAttemptCount(3);
        KeyRotationClaim claim = new KeyRotationClaim(11L, 101L, "claim", List.of(item));
        AutomatedEnvelopeRotationResult result = AutomatedEnvelopeRotationResult.failed(
                KeyWrappingFailure.of(KeyWrappingFailureCategory.THROTTLED, true));
        arrangeApplyRun(run, claim);
        when(envelopeService.rotateEnvelopeForAutomation(
                eq(301L), eq(201L), any(WrappingKeyReference.class), eq(2), eq(51L), eq("AUTO_ROTATION")))
                .thenReturn(result);

        service.runTenant(11L);

        verify(claimService).complete(eq(run), eq(item), eq("claim"), eq(result), any());
        verify(metrics).recordItem("FAILED", "THROTTLED");
        verify(auditService).record(run, 201L, 51L, "ROTATE_ITEM", "FAILURE", "THROTTLED");
        verify(alertService).terminalFailure("THROTTLED");
    }

    /**
     * Proves a revoked share outcome is a successful skip and never becomes a failure alert.
     */
    @Test
    void shouldTreatRevokedRecipientAsIdempotentSkip() {
        KeyRotationRun run = run(KeyRotationStates.MODE_APPLY);
        KeyRotationItem item = item();
        KeyRotationClaim claim = new KeyRotationClaim(11L, 101L, "claim", List.of(item));
        AutomatedEnvelopeRotationResult result = AutomatedEnvelopeRotationResult.completed(
                "SKIPPED_REVOKED", 201L);
        arrangeApplyRun(run, claim);
        when(envelopeService.rotateEnvelopeForAutomation(
                eq(301L), eq(201L), any(WrappingKeyReference.class), eq(2), eq(51L), eq("AUTO_ROTATION")))
                .thenReturn(result);

        service.runTenant(11L);

        verify(claimService).complete(eq(run), eq(item), eq("claim"), eq(result), any());
        verify(metrics).recordItem("SKIPPED_REVOKED", "NONE");
        verify(alertService, never()).terminalFailure(any());
    }

    /**
     * Proves unexpected provider orchestration errors retain class-only evidence and are auditable.
     */
    @Test
    void shouldClassifyUnexpectedFailureWithoutPersistingMessage() {
        KeyRotationRun run = run(KeyRotationStates.MODE_APPLY).setMaxAttempts(3);
        KeyRotationItem item = item().setAttemptCount(1);
        KeyRotationClaim claim = new KeyRotationClaim(11L, 101L, "claim", List.of(item));
        arrangeApplyRun(run, claim);
        when(envelopeService.rotateEnvelopeForAutomation(
                eq(301L), eq(201L), any(WrappingKeyReference.class), eq(2), eq(51L), eq("AUTO_ROTATION")))
                .thenThrow(new IllegalStateException("provider-secret-response"));

        service.runTenant(11L);

        verify(claimService).failUnexpected(eq(run), eq(item), eq("claim"),
                any(IllegalStateException.class), any());
        verify(auditService).record(run, 201L, 51L, "ROTATE_ITEM", "FAILURE", "INTERNAL");
        verify(alertService, never()).terminalFailure(any());
    }

    /**
     * Arranges one runnable APPLY cycle with a supplied claim.
     */
    private void arrangeApplyRun(KeyRotationRun run, KeyRotationClaim claim) {
        when(runService.findRunnable(11L)).thenReturn(run);
        when(runService.discoverNextPage(eq(11L), eq(101L), any())).thenReturn(run);
        when(claimService.claim(eq(run), eq(25), any())).thenReturn(claim);
        when(runService.refreshAndFinalize(eq(11L), eq(101L), any())).thenReturn(run);
    }

    /**
     * Builds one immutable target snapshot used by the worker.
     */
    private KeyRotationRun run(String mode) {
        return new KeyRotationRun()
                .setId(101L)
                .setTenantId(11L)
                .setMode(mode)
                .setStatus(KeyRotationStates.RUN_RUNNING)
                .setBatchSize(25)
                .setMaxAttempts(3)
                .setCreatedBy(51L)
                .setTargetProvider("vault-transit")
                .setTargetProviderContract(1)
                .setTargetKeyId("tenant-key")
                .setTargetProviderKeyVersion("7")
                .setTargetWrappingAlgorithm("VAULT-TRANSIT")
                .setTargetContextSchema("external-v2")
                .setTargetLogicalKeyVersion(2);
    }

    /**
     * Builds one claimed item whose ID is also the deterministic candidate envelope ID.
     */
    private KeyRotationItem item() {
        return new KeyRotationItem()
                .setId(201L)
                .setTenantId(11L)
                .setRunId(101L)
                .setSourceEnvelopeId(301L)
                .setAttemptCount(1);
    }
}
