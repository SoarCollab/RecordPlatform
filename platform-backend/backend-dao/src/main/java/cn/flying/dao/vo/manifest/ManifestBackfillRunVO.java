package cn.flying.dao.vo.manifest;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Date;

/**
 * External-ID administrator view of one durable manifest backfill run.
 */
@Schema(description = "Manifest backfill governance run")
public record ManifestBackfillRunVO(
        String id,
        String snapshotRunId,
        String mode,
        String status,
        String snapshotVersion,
        String snapshotDigest,
        long totalCount,
        long pendingCount,
        long backfilledCount,
        long reuploadCount,
        long unrecoverableCount,
        long ignoredCount,
        long failedCount,
        String lastErrorClass,
        Date startedAt,
        Date completedAt,
        Date createTime,
        Date updateTime
) {
}
