package cn.flying.service.attestation;

import java.util.List;

/**
 * 一次租户级生产 batch 运行的有界结果摘要。
 */
public record AttestationBatchProductionRunResult(
        boolean enabled,
        boolean force,
        int candidatesAdmitted,
        int candidatesClaimed,
        int candidatesDeadLettered,
        int batchesRecovered,
        int batchesCreated,
        int batchesCompleted,
        int batchesRetrying,
        int batchesManualReview,
        boolean thresholdDeferred,
        List<Long> batchIds
) {

    /**
     * 冻结本次运行产生的 batch ID 列表，避免结果快照被调用方回写修改。
     */
    public AttestationBatchProductionRunResult {
        batchIds = batchIds == null ? List.of() : List.copyOf(batchIds);
    }

    /**
     * 返回不可变的 batch ID 快照，避免调用方获取 record 的内部可变表示。
     */
    @Override
    public List<Long> batchIds() {
        return List.copyOf(batchIds);
    }

    /**
     * 创建一个不执行任何数据库工作的 disabled 结果。
     */
    public static AttestationBatchProductionRunResult disabled(boolean force) {
        return new AttestationBatchProductionRunResult(
                false, force, 0, 0, 0, 0, 0, 0, 0, 0, false, List.of());
    }
}
