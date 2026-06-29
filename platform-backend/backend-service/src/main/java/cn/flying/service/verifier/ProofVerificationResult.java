package cn.flying.service.verifier;

import java.util.List;

/**
 * Offline proof verification result and public evidence summary.
 */
public record ProofVerificationResult(
        boolean valid,
        List<ProofVerificationIssue> issues,
        String contractVersion,
        String proofAlgorithm,
        String fileHash,
        String computedFileHash,
        String leafHash,
        String computedLeafHash,
        String merkleRoot,
        String computedMerkleRoot,
        String batchTransactionHash,
        String batchChainFileHash,
        String fileTransactionHash,
        String issuerPlatform,
        String issuerContract,
        String batchStatus
) {
}
