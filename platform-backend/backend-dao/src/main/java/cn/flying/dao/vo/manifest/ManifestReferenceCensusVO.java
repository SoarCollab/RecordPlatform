package cn.flying.dao.vo.manifest;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Date;

/**
 * External-ID completion boundary for a reference census.
 */
@Schema(description = "Manifest reference census")
public record ManifestReferenceCensusVO(
        String id,
        String status,
        String censusDigest,
        long knownReferenceCount,
        long unknownHoldCount,
        String lastErrorClass,
        Date completedAt,
        Date createTime
) {
}
