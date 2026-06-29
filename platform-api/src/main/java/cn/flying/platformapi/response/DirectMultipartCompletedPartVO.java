package cn.flying.platformapi.response;

import java.io.Serializable;

/**
 * Final storage metadata for one promoted direct-upload chunk.
 */
public record DirectMultipartCompletedPartVO(
        int partIndex,
        String storagePath,
        long size,
        String eTag,
        String plainHash,
        String cipherHash,
        String checksumAlgorithm
) implements Serializable {
}
