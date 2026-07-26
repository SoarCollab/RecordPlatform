package cn.flying.service.manifest;

import java.util.List;
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
     * Loads active manifests and ordered chunks for a bounded set of files in the current tenant.
     * This system-facing method deliberately skips per-file owner queries because callers already
     * hold tenant-scoped file records. Duplicate active rows are reported instead of hidden.
     *
     * @param fileIds tenant-owned internal file IDs, at most 1000 distinct values
     * @return active manifests keyed by file ID plus duplicate-active diagnostics
     */
    ChunkManifestBatchView findActiveManifests(List<Long> fileIds);

    /**
     * Calculates the deterministic manifest hash without persisting the draft.
     *
     * @param draft manifest draft to hash
     * @return sha256-prefixed canonical manifest hash
     */
    String calculateManifestHash(ChunkManifestDraft draft);

    /**
     * 计算不包含密钥材料的 canonical manifest JSON，供下载端独立重算哈希。
     *
     * @param draft manifest draft
     * @return canonical JSON without manifestHash or initialKey
     */
    String calculateCanonicalJson(ChunkManifestDraft draft);
}
