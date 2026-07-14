package cn.flying.service.attestation;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.entity.AttestationBatch;
import cn.flying.dao.mapper.AttestationBatchMapper;
import cn.flying.dao.vo.attestation.AttestationBatchCandidateStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 编排租户级生产 candidate admission、flush 和链提交恢复。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttestationBatchProductionServiceImpl implements AttestationBatchProductionService {

    private final AttestationBatchProductionProperties properties;
    private final AttestationBatchCandidatePersistenceService candidatePersistence;
    private final AttestationBatchFlushPolicy flushPolicy;
    private final AttestationBatchService batchService;
    private final AttestationBatchMapper batchMapper;
    private final AttestationBatchProductionMetrics metrics;

    /**
     * 在当前租户上下文中执行一次有界的恢复、admission 和 flush。
     */
    @Override
    public AttestationBatchProductionRunResult runTenant(Long tenantId, boolean force) {
        requireCurrentTenant(tenantId);
        if (!properties.isEnabled()) {
            metrics.recordRunDisabled();
            return AttestationBatchProductionRunResult.disabled(force);
        }

        RunAccumulator result = new RunAccumulator(force);
        try {
            Instant now = Instant.now();
            int maxAttempts = properties.effectiveCandidateMaxAttempts();
            int expiredDeadLetters = candidatePersistence.recoverExpiredClaims(
                    tenantId, maxAttempts, now);
            result.candidatesDeadLettered += expiredDeadLetters;
            metrics.recordCandidateDeadLetter(expiredDeadLetters);

            int remainingBatchBudget = recoverDueBatches(tenantId, now, result);
            AttestationCandidateAdmissionResult admission = candidatePersistence.seedEligibleCandidates(
                    tenantId, properties.effectiveSeedLimit(), Date.from(now));
            result.candidatesAdmitted += admission.totalCandidates();
            result.candidatesDeadLettered += admission.deadLetterCandidates();
            metrics.recordAdmission(admission);

            flushReadyCandidates(tenantId, force, remainingBatchBudget, result);
            metrics.recordRunCompleted();
            return result.toResult();
        } catch (RuntimeException failure) {
            metrics.recordRunFailed();
            log.error("Production attestation run failed: tenantId={}, force={}, reason={}",
                    tenantId, force, safeMessage(failure));
            throw failure;
        }
    }

    /**
     * 查询当前租户的生产配置、candidate backlog 和可恢复 batch 数量。
     */
    @Override
    public AttestationBatchProductionStatus getStatus(Long tenantId) {
        requireCurrentTenant(tenantId);
        AttestationBatchCandidateStats stats = candidatePersistence.stats(tenantId);
        long dueBatches = batchMapper.countDueBatches(
                tenantId, new Date(), AttestationBatchServiceImpl.MAX_ATTEMPTS);
        return new AttestationBatchProductionStatus(
                properties.isEnabled(),
                properties.effectiveMinBatchSize(),
                properties.effectiveMaxBatchSize(),
                properties.effectiveMaxWaitSeconds(),
                properties.effectiveSeedLimit(),
                properties.effectiveMaxBatchesPerRun(),
                stats.getReadyCount(),
                stats.getClaimedCount(),
                stats.getBatchedCount(),
                stats.getDeadLetterCount(),
                cloneDate(stats.getOldestReadyAt()),
                dueBatches);
    }

    /**
     * 优先恢复已落库但尚未确认的 batch，并返回本轮剩余创建预算。
     */
    private int recoverDueBatches(Long tenantId, Instant now, RunAccumulator result) {
        int budget = properties.effectiveMaxBatchesPerRun();
        List<Long> dueBatchIds = batchMapper.selectDueBatchIds(
                tenantId,
                Date.from(now),
                AttestationBatchServiceImpl.MAX_ATTEMPTS,
                budget);
        if (dueBatchIds == null || dueBatchIds.isEmpty()) {
            return budget;
        }
        for (Long batchId : dueBatchIds) {
            AttestationBatch batch = batchService.submitBatch(batchId);
            result.batchesRecovered++;
            result.addBatch(batch);
            metrics.recordBatchStatus(batch);
        }
        return Math.max(0, budget - dueBatchIds.size());
    }

    /**
     * 按 size/time/force policy 领取 candidate 并创建有上限的生产 batch。
     */
    private void flushReadyCandidates(Long tenantId,
                                      boolean force,
                                      int batchBudget,
                                      RunAccumulator result) {
        for (int batchIndex = 0; batchIndex < batchBudget; batchIndex++) {
            Instant decisionTime = Instant.now();
            AttestationBatchCandidateStats stats = candidatePersistence.stats(tenantId);
            if (!flushPolicy.shouldFlush(stats, decisionTime, force, properties)) {
                result.thresholdDeferred = stats.getReadyCount() > 0;
                return;
            }

            int claimSize = flushPolicy.claimSize(stats, properties);
            AttestationCandidateClaim claim = candidatePersistence.claimCandidates(
                    tenantId,
                    claimSize,
                    properties.effectiveCandidateMaxAttempts(),
                    properties.effectiveClaimLeaseSeconds(),
                    decisionTime);
            if (claim == null || claim.size() == 0) {
                return;
            }
            result.candidatesClaimed += claim.size();

            AttestationBatch pendingBatch;
            try {
                pendingBatch = batchService.createProductionBatch(claim);
                metrics.recordBatchCreated(claim.size(), claim.oldestEligibleAt(), Instant.now());
            } catch (RuntimeException failure) {
                int deadLettered = candidatePersistence.releaseClaim(
                        claim,
                        properties.effectiveCandidateMaxAttempts(),
                        safeCandidateError(failure));
                result.candidatesDeadLettered += deadLettered;
                metrics.recordCandidateDeadLetter(deadLettered);
                throw failure;
            }

            result.batchesCreated++;
            AttestationBatch submittedBatch = batchService.submitBatch(pendingBatch.getId());
            result.addBatch(submittedBatch);
            metrics.recordBatchStatus(submittedBatch);
        }
    }

    /**
     * 防止系统调用方把一个租户参数放进另一个租户上下文执行。
     */
    private void requireCurrentTenant(Long tenantId) {
        Long currentTenantId = TenantContext.requireTenantId();
        if (!Objects.equals(currentTenantId, tenantId)) {
            throw new GeneralException(
                    ResultEnum.PERMISSION_UNAUTHORIZED,
                    "生产批次租户上下文不匹配");
        }
    }

    /**
     * 克隆 Date，避免向控制器泄露可变实体状态。
     */
    private Date cloneDate(Date value) {
        return value == null ? null : new Date(value.getTime());
    }

    /**
     * 提取不为空的异常摘要供内部日志使用。
     */
    private String safeMessage(Throwable throwable) {
        return throwable.getMessage() != null
                ? throwable.getMessage()
                : throwable.getClass().getSimpleName();
    }

    /**
     * 生成不包含 SQL、路径或业务数据的 candidate 持久化错误分类。
     */
    private String safeCandidateError(Throwable throwable) {
        return "Batch creation failed: " + throwable.getClass().getSimpleName();
    }

    /**
     * 收集一次运行中的计数，最终转换为不可变结果。
     */
    private static final class RunAccumulator {
        private final boolean force;
        private final List<Long> batchIds = new ArrayList<>();
        private int candidatesAdmitted;
        private int candidatesClaimed;
        private int candidatesDeadLettered;
        private int batchesRecovered;
        private int batchesCreated;
        private int batchesCompleted;
        private int batchesRetrying;
        private int batchesManualReview;
        private boolean thresholdDeferred;

        private RunAccumulator(boolean force) {
            this.force = force;
        }

        /**
         * 记录 batch ID 和链状态分类。
         */
        private void addBatch(AttestationBatch batch) {
            if (batch == null) {
                batchesRetrying++;
                return;
            }
            if (batch.getId() != null) {
                batchIds.add(batch.getId());
            }
            if (AttestationBatchStatus.COMPLETED.value().equals(batch.getStatus())) {
                batchesCompleted++;
            } else if (AttestationBatchStatus.MANUAL_REVIEW.value().equals(batch.getStatus())) {
                batchesManualReview++;
            } else {
                batchesRetrying++;
            }
        }

        /**
         * 生成对外只读的一轮运行结果。
         */
        private AttestationBatchProductionRunResult toResult() {
            return new AttestationBatchProductionRunResult(
                    true,
                    force,
                    candidatesAdmitted,
                    candidatesClaimed,
                    candidatesDeadLettered,
                    batchesRecovered,
                    batchesCreated,
                    batchesCompleted,
                    batchesRetrying,
                    batchesManualReview,
                    thresholdDeferred,
                    List.copyOf(batchIds));
        }
    }
}
