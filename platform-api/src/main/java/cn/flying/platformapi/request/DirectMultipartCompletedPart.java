package cn.flying.platformapi.request;

import java.io.Serializable;

/**
 * Carries storage metadata needed to verify and promote one uploaded staging object.
 */
public record DirectMultipartCompletedPart(
        int partIndex,
        String stagingObjectName,
        String finalObjectName,
        String nodeName,
        String storagePath,
        long size,
        String eTag,
        String plainHash,
        String cipherHash,
        String checksumAlgorithm
) implements Serializable {
}
