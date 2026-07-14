package cn.flying.service.attestation;

import java.util.Date;

/**
 * 当前租户生产 batch 配置与 backlog 状态。
 */
public record AttestationBatchProductionStatus(
        boolean enabled,
        int minBatchSize,
        int maxBatchSize,
        long maxWaitSeconds,
        int seedLimit,
        int maxBatchesPerRun,
        long readyCandidates,
        long claimedCandidates,
        long batchedCandidates,
        long deadLetterCandidates,
        Date oldestReadyAt,
        long dueBatches
) {

    /**
     * 在构造边界复制可变日期，避免调用方保留引用后修改状态快照。
     */
    public AttestationBatchProductionStatus {
        oldestReadyAt = oldestReadyAt == null
                ? null
                : new Date(oldestReadyAt.getTime());
    }

    /**
     * 防止调用方通过返回对象修改内部日期值。
     */
    @Override
    public Date oldestReadyAt() {
        return oldestReadyAt == null ? null : new Date(oldestReadyAt.getTime());
    }
}
