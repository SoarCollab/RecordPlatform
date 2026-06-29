package cn.flying.service.manifest;

import java.util.List;

/**
 * Input payload used to create or hash a chunk manifest before persistence.
 */
public record ChunkManifestDraft(
        String schemaId,
        String fileHash,
        String hashAlgorithm,
        long chunkSize,
        long totalSize,
        String merkleRoot,
        String encryptionAlgorithm,
        String storageBackend,
        List<ChunkManifestChunk> chunks
) {
}
