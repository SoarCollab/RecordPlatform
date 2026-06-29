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
        String checksumAlgorithm
) {
}
