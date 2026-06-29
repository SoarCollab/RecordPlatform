package cn.flying.platformapi.response;

import java.io.Serializable;
import java.util.List;

/**
 * Response containing presigned direct-upload URLs for all requested chunks.
 */
public record CreateDirectMultipartUploadResponse(
        String sessionId,
        List<DirectMultipartUploadPartUrl> parts
) implements Serializable {
}
