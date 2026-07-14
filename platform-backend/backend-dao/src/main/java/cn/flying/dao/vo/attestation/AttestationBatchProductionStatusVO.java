package cn.flying.dao.vo.attestation;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Date;

/**
 * 当前租户生产 Merkle batch 的配置和 backlog 状态。
 */
@Schema(description = "生产 Merkle batch 状态")
public record AttestationBatchProductionStatusVO(
        @Schema(description = "生产批次功能是否启用")
        boolean enabled,
        @Schema(description = "数量 flush 阈值")
        int minBatchSize,
        @Schema(description = "单个 batch 最大 candidate 数")
        int maxBatchSize,
        @Schema(description = "最老 candidate 最大等待秒数")
        long maxWaitSeconds,
        @Schema(description = "单轮最多 admission 数")
        int seedLimit,
        @Schema(description = "单轮最多处理 batch 数")
        int maxBatchesPerRun,
        @Schema(description = "READY candidate 数")
        long readyCandidates,
        @Schema(description = "CLAIMED candidate 数")
        long claimedCandidates,
        @Schema(description = "BATCHED candidate 数")
        long batchedCandidates,
        @Schema(description = "DEAD_LETTER candidate 数")
        long deadLetterCandidates,
        @Schema(description = "最老 READY candidate 的 admission 时间")
        Date oldestReadyAt,
        @Schema(description = "当前可恢复提交的 batch 数")
        long dueBatches
) {

    /**
     * 在响应合同构造边界复制可变日期，避免保留调用方传入的引用。
     */
    public AttestationBatchProductionStatusVO {
        oldestReadyAt = oldestReadyAt == null
                ? null
                : new Date(oldestReadyAt.getTime());
    }

    /**
     * 防止调用方通过响应对象修改日期字段。
     */
    @Override
    public Date oldestReadyAt() {
        return oldestReadyAt == null ? null : new Date(oldestReadyAt.getTime());
    }
}
