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
     * @return persisted completed batch
     */
    AttestationBatch createBatch(Long userId, List<Long> fileIds);
}
