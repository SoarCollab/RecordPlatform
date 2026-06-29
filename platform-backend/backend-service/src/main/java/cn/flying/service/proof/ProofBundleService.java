package cn.flying.service.proof;

import cn.flying.dao.vo.file.ProofBundleVO;

/**
 * Exports verifier-ready proof bundles from persisted attestation data.
 */
public interface ProofBundleService {

    /**
     * Export a proof bundle for a file version selected by internal file ID.
     *
     * @param userId current user ID for ownership checks
     * @param fileId internal file ID
     * @return deterministic proof bundle
     */
    ProofBundleVO exportByFileId(Long userId, Long fileId);

    /**
     * Export a proof bundle for a persisted attestation leaf selected by internal leaf ID.
     *
     * @param userId current user ID for ownership checks
     * @param leafId internal attestation leaf ID
     * @return deterministic proof bundle
     */
    ProofBundleVO exportByLeafId(Long userId, Long leafId);
}
