package cn.flying.platformapi.request;

import java.io.Serializable;
import java.util.List;

/**
 * Requests verification and promotion of direct-uploaded staging chunks.
 */
public record CompleteDirectMultipartUploadRequest(
        String sessionId,
        List<DirectMultipartCompletedPart> parts
) implements Serializable {
}
