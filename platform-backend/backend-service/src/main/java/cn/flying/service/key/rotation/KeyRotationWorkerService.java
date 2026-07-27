package cn.flying.service.key.rotation;

import cn.flying.dao.entity.KeyRotationItem;
import cn.flying.dao.entity.KeyRotationRun;
import cn.flying.service.key.AutomatedEnvelopeRotationResult;
import cn.flying.service.key.FileKeyEnvelopeService;
import cn.flying.service.key.KeyWrappingFailureCategory;
import cn.flying.service.key.WrappingKeyReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Executes one bounded discovery and claim cycle for a tenant.
 */
@Service
@RequiredArgsConstructor
public class KeyRotationWorkerService {

    private final KeyRotationRunService runService;
    private final KeyRotationClaimService claimService;
    private final FileKeyEnvelopeService envelopeService;
    private final KeyRotationAuditService auditService;
    private final KeyRotationMetrics metrics;
    private final KeyRotationAlertService alertService;

    /**
     * Advances the oldest runnable tenant run without holding database locks across provider calls.
     */
    public void runTenant(Long tenantId) {
        Instant now = Instant.now();
        KeyRotationRun run = runService.findRunnable(tenantId);
        if (run == null) {
            return;
        }
        run = runService.discoverNextPage(tenantId, run.getId(), now);
        if (run == null || !KeyRotationStates.RUN_RUNNING.equals(run.getStatus())) {
            return;
        }
        if (KeyRotationStates.MODE_DRY_RUN.equals(run.getMode())) {
            runService.refreshAndFinalize(tenantId, run.getId(), Instant.now());
            return;
        }

        KeyRotationClaim claim = claimService.claim(run, run.getBatchSize(), Instant.now());
        if (claim != null) {
            WrappingKeyReference target = targetReference(run);
            for (KeyRotationItem item : claim.items()) {
                processOne(run, item, claim.claimToken(), target);
            }
        }
        KeyRotationRun finalized = runService.refreshAndFinalize(tenantId, run.getId(), Instant.now());
        if (finalized != null) {
            runService.refreshRetirementReadiness(tenantId, finalized.getId(), Instant.now());
        }
    }

    /**
     * Renews, rotates, and completes one claimed envelope with stable failure classification.
     */
    private void processOne(KeyRotationRun run,
                            KeyRotationItem item,
                            String claimToken,
                            WrappingKeyReference target) {
        try {
            claimService.renew(run, item, claimToken, Instant.now());
            AutomatedEnvelopeRotationResult result = envelopeService.rotateEnvelopeForAutomation(
                    item.getSourceEnvelopeId(), item.getId(), target,
                    run.getTargetLogicalKeyVersion(), run.getCreatedBy(), "AUTO_ROTATION");
            claimService.complete(run, item, claimToken, result, Instant.now());
            metrics.recordItem(result.outcome(), result.failureCategory().name());
            auditService.record(run, item.getId(), run.getCreatedBy(), "ROTATE_ITEM",
                    result.failureCategory() == KeyWrappingFailureCategory.NONE ? "SUCCESS" : "FAILURE",
                    result.failureCategory().name());
            if (result.failureCategory() != KeyWrappingFailureCategory.NONE
                    && (!result.retryable() || exhausted(run, item))) {
                alertService.terminalFailure(result.failureCategory().name());
            }
        } catch (RuntimeException failure) {
            claimService.failUnexpected(run, item, claimToken, failure, Instant.now());
            metrics.recordItem("FAILED", KeyWrappingFailureCategory.INTERNAL.name());
            auditService.record(run, item.getId(), run.getCreatedBy(), "ROTATE_ITEM",
                    "FAILURE", KeyWrappingFailureCategory.INTERNAL.name());
            if (exhausted(run, item)) {
                alertService.terminalFailure(KeyWrappingFailureCategory.INTERNAL.name());
            }
        }
    }

    /**
     * Returns whether the current claimed attempt has reached the immutable run limit.
     */
    private boolean exhausted(KeyRotationRun run, KeyRotationItem item) {
        return item.getAttemptCount() != null && item.getAttemptCount() >= run.getMaxAttempts();
    }

    /**
     * Reconstructs the exact target routing snapshot without consulting current active config.
     */
    private WrappingKeyReference targetReference(KeyRotationRun run) {
        return new WrappingKeyReference(
                run.getTargetProvider(), run.getTargetProviderContract(), run.getTargetKeyId(),
                run.getTargetProviderKeyVersion(), run.getTargetWrappingAlgorithm(),
                run.getTargetContextSchema());
    }
}
