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
        ChunkManifestEncryption encryption,
        List<ChunkManifestChunk> chunks
) {

    /**
     * 保留旧 manifest 构造调用，新增字段为空时维持既有 canonical hash。
     */
    public ChunkManifestDraft(
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
        this(schemaId, fileHash, hashAlgorithm, chunkSize, totalSize, merkleRoot,
                encryptionAlgorithm, storageBackend, null, chunks);
    }
}
