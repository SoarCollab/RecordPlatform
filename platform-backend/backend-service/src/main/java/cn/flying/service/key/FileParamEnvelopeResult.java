package cn.flying.service.key;

import org.springframework.util.StringUtils;

/**
 * Result of sanitizing file_param and extracting a plaintext data-key token.
 */
public record FileParamEnvelopeResult(
        String sanitizedFileParam,
        String initialKey,
        String algorithmSuite,
        String signatureSuite,
        String kemSuite,
        String proofSuite,
        String encryptionAlgorithm,
        Integer keyVersion,
        String deprecatedAfter
) {

    /**
     * Creates a result for metadata that does not require envelope persistence.
     */
    public static FileParamEnvelopeResult withoutEnvelope(String fileParam) {
        return new FileParamEnvelopeResult(fileParam, null, null, null, null, null, null, null, null);
    }

    /**
     * Returns whether an owner envelope should be persisted for this metadata.
     */
    public boolean requiresEnvelope() {
        return StringUtils.hasText(initialKey);
    }
}
