package cn.flying.dao.vo.manifest;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Date;

/**
 * External-ID view of one independent mark/grace/delete lifecycle.
 */
@Schema(description = "Reference sweep mark")
public record ManifestReferenceSweepMarkVO(
        String id,
        String storagePath,
        String cipherHash,
        long contentLength,
        String etag,
        String markCensusId,
        String status,
        Date protectionUntil,
        String reasonCode,
        Date deletedAt
) {
}
