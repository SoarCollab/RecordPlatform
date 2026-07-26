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
        Integer chunkCount,
        long totalSize,
        String merkleRoot,
        String encryptionAlgorithm,
        String storageBackend,
        ChunkManifestEncryption encryption,
        List<ChunkManifestChunk> chunks
) {

    /**
     * 保留旧 manifest view 构造调用，兼容尚未携带 encryption descriptor 的历史数据。
     */
    public ChunkManifestView(
            Long manifestId,
            Long fileId,
            Integer fileVersion,
            String schemaId,
            String fileHash,
            String manifestHash,
            String hashAlgorithm,
            long chunkSize,
            Integer chunkCount,
            long totalSize,
            String merkleRoot,
            String encryptionAlgorithm,
            String storageBackend,
            List<ChunkManifestChunk> chunks
    ) {
        this(manifestId, fileId, fileVersion, schemaId, fileHash, manifestHash,
                hashAlgorithm, chunkSize, chunkCount, totalSize, merkleRoot,
                encryptionAlgorithm, storageBackend, null, chunks);
    }
}
