package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 配额灰度扩容审计写入请求。
 */
@Schema(description = "配额灰度扩容审计写入请求")
public record QuotaRolloutAuditUpsertVO(
        @NotBlank(message = "batchId 不能为空")
        @Size(max = 64, message = "batchId 长度不能超过 64")
        @Schema(description = "灰度批次ID", requiredMode = Schema.RequiredMode.REQUIRED)
        String batchId,
        @NotNull(message = "tenantId 不能为空")
        @Positive(message = "tenantId 必须大于 0")
        @Schema(description = "租户ID", requiredMode = Schema.RequiredMode.REQUIRED)
        Long tenantId,
        @NotNull(message = "observationStartTime 不能为空")
        @Schema(description = "观察窗口开始时间", requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime observationStartTime,
        @NotNull(message = "observationEndTime 不能为空")
        @Schema(description = "观察窗口结束时间", requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime observationEndTime,
        @NotNull(message = "sampledRequestCount 不能为空")
        @PositiveOrZero(message = "sampledRequestCount 不能为负数")
        @Schema(description = "观察样本请求数", requiredMode = Schema.RequiredMode.REQUIRED)
        Long sampledRequestCount,
        @NotNull(message = "exceededRequestCount 不能为空")
        @PositiveOrZero(message = "exceededRequestCount 不能为负数")
        @Schema(description = "超限命中数", requiredMode = Schema.RequiredMode.REQUIRED)
        Long exceededRequestCount,
        @NotNull(message = "falsePositiveCount 不能为空")
        @PositiveOrZero(message = "falsePositiveCount 不能为负数")
        @Schema(description = "误判数", requiredMode = Schema.RequiredMode.REQUIRED)
        Long falsePositiveCount,
        @NotBlank(message = "rollbackDecision 不能为空")
        @Size(max = 32, message = "rollbackDecision 长度不能超过 32")
        @Schema(description = "回滚决策：KEEP_ENFORCE/FORCE_SHADOW/EXTEND_OBSERVATION", requiredMode = Schema.RequiredMode.REQUIRED)
        String rollbackDecision,
        @Size(max = 255, message = "rollbackReason 长度不能超过 255")
        @Schema(description = "回滚或延长观察原因")
        String rollbackReason,
        @Size(max = 512, message = "evidenceLink 长度不能超过 512")
        @Schema(description = "证据链接（工单/文档/CI）")
        String evidenceLink
) {
}
