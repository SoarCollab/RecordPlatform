package cn.flying.service.manifest.backfill;

/**
 * Durable manifest backfill run phases.
 */
public enum ManifestBackfillRunStatus {
    PLANNED,
    SCANNING,
    SNAPSHOT_READY,
    APPLYING,
    PAUSED,
    COMPLETED,
    FAILED
}
