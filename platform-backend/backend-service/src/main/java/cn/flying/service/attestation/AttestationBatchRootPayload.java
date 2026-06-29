package cn.flying.service.attestation;

/**
 * Payload committed to the current blockchain storeFile contract for a Merkle batch root.
 */
public record AttestationBatchRootPayload(
        String type,
        String version,
        Long tenantId,
        Long batchId,
        String batchNo,
        String proofAlgorithm,
        String merkleRoot,
        Integer leafCount
) {
    public static final String TYPE = "MERKLE_ATTESTATION_BATCH_ROOT";
    public static final String VERSION = "1.0";

    /**
     * Creates a versioned blockchain payload for an attestation batch root.
     */
    public static AttestationBatchRootPayload of(Long tenantId, Long batchId, String batchNo,
                                                 String proofAlgorithm, String merkleRoot, Integer leafCount) {
        return new AttestationBatchRootPayload(
                TYPE,
                VERSION,
                tenantId,
                batchId,
                batchNo,
                proofAlgorithm,
                merkleRoot,
                leafCount
        );
    }
}
