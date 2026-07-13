package cn.flying.service.manifest;

import java.util.Map;
import java.util.Set;

/**
 * Tenant-scoped active manifest batch used by scheduled system callers.
 *
 * @param manifests active manifest selected for each file ID
 * @param duplicateFileIds file IDs that have more than one active manifest row
 */
public record ChunkManifestBatchView(
        Map<Long, ChunkManifestView> manifests,
        Set<Long> duplicateFileIds
) {

    /**
     * Defensively snapshots the batch so callers cannot mutate query results.
     */
    public ChunkManifestBatchView {
        manifests = manifests == null ? Map.of() : Map.copyOf(manifests);
        duplicateFileIds = duplicateFileIds == null ? Set.of() : Set.copyOf(duplicateFileIds);
    }

    /**
     * Returns an immutable empty batch for empty input or no matching manifests.
     */
    public static ChunkManifestBatchView empty() {
        return new ChunkManifestBatchView(Map.of(), Set.of());
    }
}
