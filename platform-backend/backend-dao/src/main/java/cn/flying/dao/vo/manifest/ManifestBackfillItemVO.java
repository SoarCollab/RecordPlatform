package cn.flying.dao.vo.manifest;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Date;

/**
 * Access-controlled per-file backfill result with no raw storage pointer exposure.
 */
@Schema(description = "Manifest backfill item")
public record ManifestBackfillItemVO(
        String id,
        String runId,
        String fileId,
        int fileVersion,
        String status,
        String classification,
        String reasonCode,
        boolean retryable,
        boolean legacyDownloadAllowed,
        String evidenceDigest,
        String manifestId,
        int attemptCount,
        Date nextRetryAt,
        String lastErrorClass,
        Date updateTime
) {
}
