package cn.flying.service.manifest;

/**
 * Insert-only backfill publication result.
 *
 * @param manifest created or validated concurrent active manifest
 * @param created true only when this transaction inserted the manifest
 */
public record BackfilledManifestPublication(
        ChunkManifestView manifest,
        boolean created
) {
}
