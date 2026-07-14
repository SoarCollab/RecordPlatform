package cn.flying.dao.vo.attestation;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 管理员人工触发生产 Merkle batch 的结果。
 */
@Schema(description = "生产 Merkle batch 运行结果")
public record AttestationBatchProductionRunVO(
        @Schema(description = "生产批次功能是否启用")
        boolean enabled,
        @Schema(description = "是否为管理员强制 flush")
        boolean force,
        @Schema(description = "本轮新增 candidate 数量")
        int candidatesAdmitted,
        @Schema(description = "本轮领取 candidate 数量")
        int candidatesClaimed,
        @Schema(description = "本轮进入 dead-letter 的 candidate 数量")
        int candidatesDeadLettered,
        @Schema(description = "本轮恢复提交的已有 batch 数量")
        int batchesRecovered,
        @Schema(description = "本轮创建的 batch 数量")
        int batchesCreated,
        @Schema(description = "本轮确认完成的 batch 数量")
        int batchesCompleted,
        @Schema(description = "本轮仍在等待或重试的 batch 数量")
        int batchesRetrying,
        @Schema(description = "本轮进入人工处理的 batch 数量")
        int batchesManualReview,
        @Schema(description = "是否因数量和时间阈值未满足而延后")
        boolean thresholdDeferred,
        @Schema(description = "涉及的外部 batch ID")
        List<String> batchIds
) {
}
