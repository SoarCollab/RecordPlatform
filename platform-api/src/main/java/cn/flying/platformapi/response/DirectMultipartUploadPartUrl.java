package cn.flying.platformapi.response;

import java.io.Serializable;

/**
 * Internal storage response for one presigned direct-upload chunk.
 */
public record DirectMultipartUploadPartUrl(
        int partIndex,
        String uploadUrl,
        long expiresAtEpochSeconds,
        String storagePath,
        String stagingObjectName,
        String finalObjectName,
        String nodeName,
        long size
) implements Serializable {
}
