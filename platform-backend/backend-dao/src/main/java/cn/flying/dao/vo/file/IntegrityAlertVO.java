package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Date;

/**
 * Integrity alert view object for external API responses.
 * Uses external ID instead of raw Long.
 */
@Schema(description = "完整性告警信息")
public record IntegrityAlertVO(
        @Schema(description = "告警ID（外部ID）")
        String id,
        @Schema(description = "文件ID（外部ID）")
        String fileId,
        @Schema(description = "文件哈希")
        String fileHash,
        @Schema(description = "实际哈希")
        String actualHash,
        @Schema(description = "链上哈希")
        String chainHash,
        @Schema(description = "告警类型")
        String alertType,
        @Schema(description = "告警状态")
        Integer status,
        @Schema(description = "处理人ID（外部ID）")
        String resolvedBy,
        @Schema(description = "处理时间")
        Date resolvedAt,
        @Schema(description = "处理备注")
        String note,
        @Schema(description = "创建时间")
        Date createTime
) {
}
