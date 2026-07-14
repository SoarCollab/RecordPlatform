package cn.flying.platformapi.request;

import cn.flying.platformapi.response.ContractRegistryEntryResponse;

import java.io.Serial;
import java.io.Serializable;

/**
 * Dedicated blockchain request for recording a Merkle attestation batch root.
 *
 * @param tenantId tenant that owns the batch
 * @param batchId internal attestation batch id
 * @param batchNo stable batch number
 * @param proofAlgorithm Merkle proof algorithm
 * @param merkleRoot Merkle batch root hash
 * @param leafCount number of leaves included in the batch
 * @param contractRegistry immutable registry snapshot expected by the caller
 */
public record StoreAttestationBatchRequest(
        Long tenantId,
        Long batchId,
        String batchNo,
        String proofAlgorithm,
        String merkleRoot,
        Integer leafCount,
        ContractRegistryEntryResponse contractRegistry
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
