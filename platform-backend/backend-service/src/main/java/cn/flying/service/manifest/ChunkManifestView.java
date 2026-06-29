package cn.flying.service.manifest;

import java.util.List;

/**
 * Persisted chunk manifest view returned to backend callers.
 */
public record ChunkManifestView(
        Long manifestId,
        Long fileId,
        Integer fileVersion,
        String schemaId,
        String fileHash,
        String manifestHash,
        String hashAlgorithm,
        long chunkSize,
        long totalSize,
        String merkleRoot,
        String encryptionAlgorithm,
        String storageBackend,
        List<ChunkManifestChunk> chunks
) {
}
