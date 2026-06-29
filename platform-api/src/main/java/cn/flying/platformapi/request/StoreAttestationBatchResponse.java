package cn.flying.platformapi.request;

/**
 * Dedicated blockchain response for a Merkle attestation batch write.
 *
 * @param transactionHash chain transaction hash
 * @param batchRootHash batch root hash confirmed by the chain
 */
public record StoreAttestationBatchResponse(
        String transactionHash,
        String batchRootHash
) {
}
