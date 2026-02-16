package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 配额灰度扩容审计响应。
 */
@Schema(description = "配额灰度扩容审计响应")
public record QuotaRolloutAuditVO(
        @Schema(description = "主键ID")
        Long id,
        @Schema(description = "灰度批次ID")
        String batchId,
        @Schema(description = "租户ID")
        Long tenantId,
        @Schema(description = "观察窗口开始时间")
        LocalDateTime observationStartTime,
        @Schema(description = "观察窗口结束时间")
        LocalDateTime observationEndTime,
        @Schema(description = "观察样本请求数")
        Long sampledRequestCount,
        @Schema(description = "超限命中数")
        Long exceededRequestCount,
        @Schema(description = "误判数")
        Long falsePositiveCount,
        @Schema(description = "误判率（0~1）")
        Double falsePositiveRate,
        @Schema(description = "回滚决策")
        String rollbackDecision,
        @Schema(description = "回滚或延长观察原因")
        String rollbackReason,
        @Schema(description = "证据链接")
        String evidenceLink,
        @Schema(description = "提交人标识")
        String operatorName,
        @Schema(description = "创建时间")
        LocalDateTime createTime,
        @Schema(description = "更新时间")
        LocalDateTime updateTime
) {
}
