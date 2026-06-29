package cn.flying.platformapi.request;

/**
 * Dedicated blockchain request for recording a Merkle attestation batch root.
 *
 * @param tenantId tenant that owns the batch
 * @param batchId internal attestation batch id
 * @param batchNo stable batch number
 * @param proofAlgorithm Merkle proof algorithm
 * @param merkleRoot Merkle batch root hash
 * @param leafCount number of leaves included in the batch
 */
public record StoreAttestationBatchRequest(
        Long tenantId,
        Long batchId,
        String batchNo,
        String proofAlgorithm,
        String merkleRoot,
        Integer leafCount
) {
}
