package cn.flying.service.key;

/**
 * Internal result for a file key envelope rotation attempt.
 *
 * @param fileHash file hash
 * @param targetKeyVersion configured target key version
 * @param rotatedCount number of envelopes rewrapped
 * @param skippedCount number of envelopes skipped
 */
public record KeyEnvelopeRotationResult(
        String fileHash,
        Integer targetKeyVersion,
        int rotatedCount,
        int skippedCount
) {
}
