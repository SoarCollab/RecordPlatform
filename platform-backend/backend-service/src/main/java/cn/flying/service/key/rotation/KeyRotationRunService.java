package cn.flying.service.key.rotation;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.dao.entity.FileKeyEnvelope;
import cn.flying.dao.entity.KeyRotationItem;
import cn.flying.dao.entity.KeyRotationPolicy;
import cn.flying.dao.entity.KeyRotationRun;
import cn.flying.dao.mapper.FileKeyEnvelopeMapper;
import cn.flying.dao.mapper.KeyRotationItemMapper;
import cn.flying.dao.mapper.KeyRotationPolicyMapper;
import cn.flying.dao.mapper.KeyRotationRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Owns run discovery, lifecycle controls, aggregate completion, and retirement readiness.
 */
@Service
@RequiredArgsConstructor
public class KeyRotationRunService {

    private static final int MAX_PAGE_SIZE = 100;

    private final KeyRotationRunMapper runMapper;
    private final KeyRotationItemMapper itemMapper;
    private final FileKeyEnvelopeMapper envelopeMapper;
    private final KeyRotationPolicyMapper policyMapper;
    private final KeyRotationAuditService auditService;
    private final KeyRotationMetrics metrics;

    /**
     * Returns the oldest runnable tenant execution.
     */
    public KeyRotationRun findRunnable(Long tenantId) {
        return runMapper.selectOne(new LambdaQueryWrapper<KeyRotationRun>()
                .eq(KeyRotationRun::getTenantId, tenantId)
                .in(KeyRotationRun::getStatus,
                        KeyRotationStates.RUN_PLANNED,
                        KeyRotationStates.RUN_RUNNING)
                .eq(KeyRotationRun::getDeleted, 0)
                .orderByAsc(KeyRotationRun::getId)
                .last("LIMIT 1"));
    }

    /**
     * Discovers one bounded active-envelope page and advances the durable cursor.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public KeyRotationRun discoverNextPage(Long tenantId, Long runId, Instant now) {
        KeyRotationRun run = runMapper.selectRunForUpdate(tenantId, runId);
        if (run == null || (KeyRotationStates.RUN_PAUSED.equals(run.getStatus()))
                || KeyRotationStates.isTerminalRun(run.getStatus())) {
            return run;
        }
        if (KeyRotationStates.RUN_PLANNED.equals(run.getStatus())) {
            run.setStatus(KeyRotationStates.RUN_RUNNING).setStartedAt(Date.from(now));
        }
        if (Integer.valueOf(1).equals(run.getDiscoveryComplete())) {
            runMapper.updateById(run);
            return run;
        }
        long cursor = value(run.getScanCursorId());
        List<FileKeyEnvelope> page = envelopeMapper.selectRotationCandidatePage(
                tenantId, cursor, run.getSnapshotMaxEnvelopeId(), run.getBatchSize());
        if (page == null || page.isEmpty()) {
            run.setDiscoveryComplete(1);
            runMapper.updateById(run);
            runMapper.refreshCounts(tenantId, runId);
            return runMapper.selectById(runId);
        }
        boolean dryRun = KeyRotationStates.MODE_DRY_RUN.equals(run.getMode());
        for (FileKeyEnvelope envelope : page) {
            String dryRunOutcome = matchesFrozenTarget(envelope, run)
                    ? "DRY_RUN_ALREADY_TARGET" : "DRY_RUN_CANDIDATE";
            KeyRotationItem item = new KeyRotationItem()
                    .setId(IdWorker.getId())
                    .setTenantId(tenantId)
                    .setRunId(runId)
                    .setSourceEnvelopeId(envelope.getId())
                    .setFileId(envelope.getFileId())
                    .setRecipientType(envelope.getRecipientType())
                    .setRecipientId(envelope.getRecipientId())
                    .setStatus(dryRun ? KeyRotationStates.ITEM_SKIPPED : KeyRotationStates.ITEM_PENDING)
                    .setOutcome(dryRun ? dryRunOutcome : null)
                    .setRetryable(0)
                    .setAttemptCount(0)
                    .setDeleted(0);
            itemMapper.insertIgnore(item);
        }
        long nextCursor = page.getLast().getId();
        run.setScanCursorId(nextCursor);
        if (page.size() < run.getBatchSize() || nextCursor >= run.getSnapshotMaxEnvelopeId()) {
            run.setDiscoveryComplete(1);
        }
        runMapper.updateById(run);
        runMapper.refreshCounts(tenantId, runId);
        return runMapper.selectById(runId);
    }

    /**
     * Classifies a dry-run row by the full frozen target identity without invoking a provider.
     */
    private boolean matchesFrozenTarget(FileKeyEnvelope envelope, KeyRotationRun run) {
        return java.util.Objects.equals(envelope.getKeyVersion(), run.getTargetLogicalKeyVersion())
                && java.util.Objects.equals(envelope.getKmsProvider(), run.getTargetProvider())
                && java.util.Objects.equals(
                envelope.getProviderContractVersion(), run.getTargetProviderContract())
                && java.util.Objects.equals(envelope.getKmsKeyId(), run.getTargetKeyId())
                && java.util.Objects.equals(
                envelope.getProviderKeyVersion(), run.getTargetProviderKeyVersion())
                && java.util.Objects.equals(
                envelope.getWrappingAlgorithm(), run.getTargetWrappingAlgorithm())
                && java.util.Objects.equals(envelope.getContextSchema(), run.getTargetContextSchema());
    }

    /**
     * Recomputes counters and transitions a sealed run only when no retryable work remains.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public KeyRotationRun refreshAndFinalize(Long tenantId, Long runId, Instant now) {
        runMapper.refreshCounts(tenantId, runId);
        KeyRotationRun run = runMapper.selectRunForUpdate(tenantId, runId);
        if (run == null || KeyRotationStates.isTerminalRun(run.getStatus())
                || KeyRotationStates.RUN_PAUSED.equals(run.getStatus())
                || !Integer.valueOf(1).equals(run.getDiscoveryComplete())) {
            return run;
        }
        long retryableFailures = itemMapper.selectCount(new LambdaQueryWrapper<KeyRotationItem>()
                .eq(KeyRotationItem::getTenantId, tenantId)
                .eq(KeyRotationItem::getRunId, runId)
                .eq(KeyRotationItem::getStatus, KeyRotationStates.ITEM_FAILED)
                .eq(KeyRotationItem::getRetryable, 1)
                .lt(KeyRotationItem::getAttemptCount, run.getMaxAttempts())
                .eq(KeyRotationItem::getDeleted, 0));
        if (value(run.getPendingCount()) > 0 || value(run.getRunningCount()) > 0 || retryableFailures > 0) {
            refreshMetrics(run);
            return run;
        }
        boolean clean = value(run.getFailedCount()) == 0L && value(run.getRemainingCount()) == 0L;
        run.setStatus(clean
                        ? KeyRotationStates.RUN_COMPLETED
                        : KeyRotationStates.RUN_COMPLETED_WITH_FAILURES)
                .setCompletedAt(Date.from(now));
        if (clean && KeyRotationStates.MODE_APPLY.equals(run.getMode())) {
            run.setRetirementEligibleAt(Date.from(now.plusSeconds(run.getGracePeriodSeconds())))
                    .setRetirementStatus(KeyRotationStates.RETIREMENT_NOT_READY);
        }
        runMapper.updateById(run);
        refreshMetrics(run);
        auditService.record(run, null, run.getCreatedBy(), "COMPLETE",
                clean ? "SUCCESS" : "FAILURE", run.getLastErrorCategory());
        return run;
    }

    /**
     * Promotes completion to retirement readiness only after the rollback grace expires.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public KeyRotationRun refreshRetirementReadiness(Long tenantId, Long runId, Instant now) {
        KeyRotationRun run = runMapper.selectRunForUpdate(tenantId, runId);
        if (run == null || !KeyRotationStates.RUN_COMPLETED.equals(run.getStatus())
                || !KeyRotationStates.MODE_APPLY.equals(run.getMode())
                || value(run.getRemainingCount()) != 0L || value(run.getFailedCount()) != 0L
                || run.getRetirementEligibleAt() == null
                || run.getRetirementEligibleAt().toInstant().isAfter(now)
                || KeyRotationStates.RETIREMENT_READY.equals(run.getRetirementStatus())) {
            return run;
        }
        run.setRetirementStatus(KeyRotationStates.RETIREMENT_READY);
        runMapper.updateById(run);
        KeyRotationPolicy policy = policyMapper.selectTenantPolicyForUpdate(tenantId);
        if (policy != null && runId.equals(policy.getLastRunId())) {
            policy.setRetirementStatus(KeyRotationStates.RETIREMENT_READY)
                    .setRetirementEligibleAt(run.getRetirementEligibleAt());
            policyMapper.updateById(policy);
        }
        auditService.record(run, null, 0L, "RETIREMENT_READY", "SUCCESS", null);
        refreshMetrics(run);
        return run;
    }

    /**
     * Re-evaluates the latest completed run after its rollback grace period even when no work remains.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public KeyRotationRun refreshLatestRetirementReadiness(Long tenantId, Instant now) {
        KeyRotationPolicy policy = policyMapper.selectOne(new LambdaQueryWrapper<KeyRotationPolicy>()
                .eq(KeyRotationPolicy::getTenantId, tenantId)
                .eq(KeyRotationPolicy::getDeleted, 0));
        if (policy == null || policy.getLastRunId() == null
                || KeyRotationStates.RETIREMENT_READY.equals(policy.getRetirementStatus())) {
            return null;
        }
        return refreshRetirementReadiness(tenantId, policy.getLastRunId(), now);
    }

    /**
     * Pauses a run at its next discovery or claim boundary.
     */
    @Transactional(rollbackFor = Exception.class)
    public KeyRotationRun pause(Long tenantId, Long actorId, Long runId) {
        return transition(tenantId, actorId, runId, KeyRotationStates.RUN_PAUSED, "PAUSE");
    }

    /**
     * Resumes a paused run without resetting its cursor or item attempts.
     */
    @Transactional(rollbackFor = Exception.class)
    public KeyRotationRun resume(Long tenantId, Long actorId, Long runId) {
        return transition(tenantId, actorId, runId, KeyRotationStates.RUN_RUNNING, "RESUME");
    }

    /**
     * Cancels future discovery and claims while allowing an already-started provider call to settle.
     */
    @Transactional(rollbackFor = Exception.class)
    public KeyRotationRun cancel(Long tenantId, Long actorId, Long runId) {
        return transition(tenantId, actorId, runId, KeyRotationStates.RUN_CANCELLED, "CANCEL");
    }

    /**
     * Requeues terminal items whose stable failure classification permits an explicit retry.
     */
    @Transactional(rollbackFor = Exception.class)
    public KeyRotationRun retry(Long tenantId, Long actorId, Long runId) {
        KeyRotationRun run = runMapper.selectRunForUpdate(tenantId, runId);
        if (run == null || (!KeyRotationStates.RUN_COMPLETED_WITH_FAILURES.equals(run.getStatus())
                && !KeyRotationStates.RUN_FAILED.equals(run.getStatus()))) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "密钥轮换任务不可重试");
        }
        int requeued = itemMapper.retryFailed(tenantId, runId);
        if (requeued == 0) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "密钥轮换任务没有失败项");
        }
        run.setStatus(KeyRotationStates.RUN_RUNNING)
                .setCompletedAt(null)
                .setRetirementStatus(KeyRotationStates.RETIREMENT_NOT_READY)
                .setRetirementEligibleAt(null);
        runMapper.updateById(run);
        runMapper.refreshCounts(tenantId, runId);
        KeyRotationRun refreshed = runMapper.selectById(runId);
        auditService.record(refreshed, null, actorId, "RETRY", "SUCCESS", null);
        return refreshed;
    }

    /**
     * Lists bounded tenant run history newest first.
     */
    public List<KeyRotationRun> listRuns(Long tenantId, int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        return runMapper.selectList(new LambdaQueryWrapper<KeyRotationRun>()
                .eq(KeyRotationRun::getTenantId, tenantId)
                .eq(KeyRotationRun::getDeleted, 0)
                .orderByDesc(KeyRotationRun::getId)
                .last("LIMIT " + bounded));
    }

    /**
     * Returns one tenant-owned run or fails without revealing another tenant's existence.
     */
    public KeyRotationRun getRun(Long tenantId, Long runId) {
        KeyRotationRun run = runMapper.selectOne(new LambdaQueryWrapper<KeyRotationRun>()
                .eq(KeyRotationRun::getTenantId, tenantId)
                .eq(KeyRotationRun::getId, runId)
                .eq(KeyRotationRun::getDeleted, 0));
        if (run == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "密钥轮换任务不存在");
        }
        return run;
    }

    /**
     * Pages sanitized item state by a decoded opaque item-ID cursor.
     */
    public List<KeyRotationItem> listItems(Long tenantId, Long runId, Long afterId, int limit) {
        getRun(tenantId, runId);
        int bounded = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        return itemMapper.selectList(new LambdaQueryWrapper<KeyRotationItem>()
                .eq(KeyRotationItem::getTenantId, tenantId)
                .eq(KeyRotationItem::getRunId, runId)
                .gt(afterId != null, KeyRotationItem::getId, afterId)
                .eq(KeyRotationItem::getDeleted, 0)
                .orderByAsc(KeyRotationItem::getId)
                .last("LIMIT " + bounded));
    }

    /**
     * Applies a fenced lifecycle transition with an explicit state matrix.
     */
    protected KeyRotationRun transition(Long tenantId,
                                        Long actorId,
                                        Long runId,
                                        String target,
                                        String action) {
        KeyRotationRun run = runMapper.selectRunForUpdate(tenantId, runId);
        if (run == null || actorId == null || actorId <= 0 || !allowed(run.getStatus(), target)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "密钥轮换任务状态转换无效");
        }
        run.setStatus(target);
        if (KeyRotationStates.RUN_CANCELLED.equals(target)) {
            run.setCompletedAt(new Date());
        }
        runMapper.updateById(run);
        auditService.record(run, null, actorId, action, "SUCCESS", null);
        return run;
    }

    /**
     * Defines the only supported pause, resume, and cancel transitions.
     */
    private boolean allowed(String source, String target) {
        if (KeyRotationStates.RUN_PAUSED.equals(target)) {
            return KeyRotationStates.RUN_PLANNED.equals(source)
                    || KeyRotationStates.RUN_RUNNING.equals(source);
        }
        if (KeyRotationStates.RUN_RUNNING.equals(target)) {
            return KeyRotationStates.RUN_PAUSED.equals(source);
        }
        if (KeyRotationStates.RUN_CANCELLED.equals(target)) {
            return !KeyRotationStates.isTerminalRun(source);
        }
        return false;
    }

    /**
     * Refreshes low-cardinality gauges from durable counters.
     */
    private void refreshMetrics(KeyRotationRun run) {
        if (run != null) {
            boolean blocked = KeyRotationStates.RUN_COMPLETED_WITH_FAILURES.equals(run.getStatus())
                    || (KeyRotationStates.RUN_COMPLETED.equals(run.getStatus())
                    && !KeyRotationStates.RETIREMENT_READY.equals(run.getRetirementStatus()));
            metrics.refresh(value(run.getRemainingCount()), value(run.getFailedCount()), blocked);
        }
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }
}
