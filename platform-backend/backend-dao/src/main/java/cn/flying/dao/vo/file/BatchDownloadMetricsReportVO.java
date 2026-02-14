package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.Map;

/**
 * 批量下载质量指标上报请求。
 */
@Schema(description = "批量下载质量指标上报请求")
public record BatchDownloadMetricsReportVO(
        @NotBlank(message = "batchId 不能为空")
        @Schema(description = "批次ID", requiredMode = Schema.RequiredMode.REQUIRED)
        String batchId,
        @NotNull(message = "total 不能为空")
        @Positive(message = "total 必须大于 0")
        @Schema(description = "批次总文件数", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer total,
        @NotNull(message = "successCount 不能为空")
        @PositiveOrZero(message = "successCount 不能为负数")
        @Schema(description = "成功文件数", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer successCount,
        @NotNull(message = "failedCount 不能为空")
        @PositiveOrZero(message = "failedCount 不能为负数")
        @Schema(description = "失败文件数", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer failedCount,
        @NotNull(message = "retryCount 不能为空")
        @PositiveOrZero(message = "retryCount 不能为负数")
        @Schema(description = "累计重试次数（不含首次尝试）", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer retryCount,
        @NotNull(message = "durationMs 不能为空")
        @PositiveOrZero(message = "durationMs 不能为负数")
        @Schema(description = "批次耗时（毫秒）", requiredMode = Schema.RequiredMode.REQUIRED)
        Long durationMs,
        @Schema(description = "失败原因分布（reason -> count）")
        Map<String, Integer> failureReasons
) {
}
