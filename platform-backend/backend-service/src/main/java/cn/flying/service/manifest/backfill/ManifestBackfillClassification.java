package cn.flying.service.manifest.backfill;

/**
 * Stable evidence classifications exposed by manifest governance.
 */
public enum ManifestBackfillClassification {
    ALREADY_MANIFEST,
    BACKFILLABLE,
    REUPLOAD_REQUIRED,
    UNRECOVERABLE,
    FAILED,
    IGNORED
}
