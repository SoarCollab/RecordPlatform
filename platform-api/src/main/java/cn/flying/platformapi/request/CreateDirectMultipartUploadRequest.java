package cn.flying.platformapi.request;

import java.io.Serializable;
import java.util.List;

/**
 * Requests presigned object-storage URLs for an application-level multipart upload.
 */
public record CreateDirectMultipartUploadRequest(
        String sessionId,
        String fileName,
        long totalSize,
        int chunkSize,
        String contentType,
        List<DirectMultipartUploadPartRequest> parts
) implements Serializable {
}
