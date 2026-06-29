package cn.flying.platformapi.response;

import java.io.Serializable;
import java.util.List;

/**
 * Response returned after storage validates and promotes direct-uploaded chunks.
 */
public record CompleteDirectMultipartUploadResponse(
        String sessionId,
        List<DirectMultipartCompletedPartVO> parts
) implements Serializable {
}
