package cn.flying.service.manifest;

import java.util.Optional;

/**
 * Backend service boundary for persisting and loading chunk manifests.
 */
public interface ChunkManifestService {

    /**
     * Saves a chunk manifest for a file owned by the current tenant.
     *
     * @param userId optional owner user ID; null allows tenant-scoped system callers
     * @param fileId internal file ID
     * @param draft manifest draft to validate and persist
     * @return persisted manifest view
     */
    ChunkManifestView saveManifest(Long userId, Long fileId, ChunkManifestDraft draft);

    /**
     * Finds the active manifest for a file without reading object content.
     *
     * @param userId optional owner user ID; null allows tenant-scoped system callers
     * @param fileId internal file ID
     * @return active manifest when present
     */
    Optional<ChunkManifestView> findActiveManifest(Long userId, Long fileId);

    /**
     * Calculates the deterministic manifest hash without persisting the draft.
     *
     * @param draft manifest draft to hash
     * @return sha256-prefixed canonical manifest hash
     */
    String calculateManifestHash(ChunkManifestDraft draft);
}
