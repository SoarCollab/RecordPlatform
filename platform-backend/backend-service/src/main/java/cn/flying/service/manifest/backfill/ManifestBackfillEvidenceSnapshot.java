package cn.flying.service.manifest.backfill;

import cn.flying.service.manifest.ChunkManifestDraft;

/**
 * Access-controlled normalized evidence persisted for insert-only publication revalidation.
 *
 * @param tenantId tenant ID
 * @param fileId file ID
 * @param fileVersion stable file version
 * @param ownerUserId chain pointer owner
 * @param chainRecordId immutable chain record identifier
 * @param contentHash optional original-content digest
 * @param manifestHash canonical manifest hash
 * @param manifestDraft normalized manifest draft
 */
public record ManifestBackfillEvidenceSnapshot(
        Long tenantId,
        Long fileId,
        Integer fileVersion,
        Long ownerUserId,
        String chainRecordId,
        String contentHash,
        String manifestHash,
        ChunkManifestDraft manifestDraft
) {
}
