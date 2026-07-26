package cn.flying.service.manifest.backfill;

/**
 * Stable per-file lifecycle states for scan and apply runs.
 */
public enum ManifestBackfillItemStatus {
    PENDING,
    RUNNING,
    BACKFILLED,
    REUPLOAD_REQUIRED,
    UNRECOVERABLE,
    FAILED,
    IGNORED
}
