package cn.flying.service.manifest;

/**
 * One ordered chunk entry in the canonical chunk manifest contract.
 */
public record ChunkManifestChunk(
        int index,
        String plainHash,
        String cipherHash,
        long size,
        String storagePath,
        String storageBackend,
        String etag,
        String checksumAlgorithm,
        Long plainSize,
        Integer frameCount
) {

    /**
     * 保留旧分片构造调用，历史 manifest 不写入明文尺寸和 frame 数量。
     */
    public ChunkManifestChunk(
            int index,
            String plainHash,
            String cipherHash,
            long size,
            String storagePath,
            String storageBackend,
            String etag,
            String checksumAlgorithm
    ) {
        this(index, plainHash, cipherHash, size, storagePath, storageBackend,
                etag, checksumAlgorithm, null, null);
    }
}
