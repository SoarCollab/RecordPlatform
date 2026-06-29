package cn.flying.platformapi.request;

import java.io.Serializable;
import java.util.List;

/**
 * Requests cleanup of direct-upload staging objects for an abandoned session.
 */
public record AbortDirectMultipartUploadRequest(
        String sessionId,
        List<DirectMultipartCompletedPart> parts
) implements Serializable {
}
