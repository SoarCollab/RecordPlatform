package cn.flying.service.verifier;

import java.util.List;

/**
 * Offline proof verification result and public evidence summary.
 *
 * <p>{@code fileHash} is the legacy chain record ID used by the Merkle proof,
 * while {@code computedFileHash} is the informational SHA-256 of the supplied
 * original bytes. Content validation is performed with ordered object
 * {@code plainHash}/{@code plainSize} evidence, so these two result fields are
 * intentionally not compared.</p>
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
