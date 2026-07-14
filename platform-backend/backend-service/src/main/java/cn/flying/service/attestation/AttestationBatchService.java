package cn.flying.service.attestation;

import cn.flying.dao.entity.AttestationBatch;

import java.util.List;

/**
 * Creates Merkle attestation batches from existing successful file records.
 */
public interface AttestationBatchService {

    /**
     * Creates a Merkle attestation batch for files owned by the current tenant.
     *
     * @param userId user requesting batch creation
     * @param fileIds internal file IDs to include
     * @return persisted batch in its current confirmed, retry, or manual-review state
     */
    AttestationBatch createBatch(Long userId, List<Long> fileIds);

    /**
     * Creates a locally durable pending batch from a production candidate claim.
     *
     * @param claim database-leased candidate snapshot
     * @return persisted batch before the separate chain submission step
     */
    AttestationBatch createProductionBatch(AttestationCandidateClaim claim);

    /**
     * Safely claims and submits one persisted batch through the recoverable chain state machine.
     *
     * @param batchId persisted attestation batch ID
     * @return batch in its state after this submission attempt
     */
    AttestationBatch submitBatch(Long batchId);
}
