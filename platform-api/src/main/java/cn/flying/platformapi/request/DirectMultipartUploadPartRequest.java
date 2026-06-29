package cn.flying.platformapi.request;

import java.io.Serializable;

/**
 * Describes one chunk object that needs a direct-upload presigned URL.
 */
public record DirectMultipartUploadPartRequest(
        int partIndex,
        String objectName,
        long size,
        String contentType,
        String plainHash,
        String cipherHash,
        String checksumAlgorithm
) implements Serializable {
}
