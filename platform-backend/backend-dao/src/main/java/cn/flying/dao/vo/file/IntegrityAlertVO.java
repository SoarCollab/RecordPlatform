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
        @Schema(description = "告警严重级别：WARNING、ERROR、CRITICAL")
        String severity,
        @Schema(description = "有界巡检证据")
        String evidence,
        @Schema(description = "Machine-readable manifest lifecycle status")
        String manifestStatus,
        @Schema(description = "Manifest evidence classification")
        String manifestClassification,
        @Schema(description = "Manifest error/reason code")
        String manifestErrorCode,
        @Schema(description = "Whether typed legacy download compatibility is explicitly allowed")
        boolean legacyDownloadAllowed,
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

    /**
     * Keeps existing non-manifest alert construction source-compatible.
     */
    public IntegrityAlertVO(
            String id,
            String fileId,
            String fileHash,
            String actualHash,
            String chainHash,
            String alertType,
            String severity,
            String evidence,
            Integer status,
            String resolvedBy,
            Date resolvedAt,
            String note,
            Date createTime
    ) {
        this(id, fileId, fileHash, actualHash, chainHash, alertType, severity, evidence,
                "ACTIVE", "ALREADY_MANIFEST", null, false,
                status, resolvedBy, resolvedAt, note, createTime);
    }
}
