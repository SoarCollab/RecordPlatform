package cn.flying.verifier.model;

/**
 * Safe evidence summary displayed by CLI and Web without returning original bytes.
 */
public record VerificationSummary(
        String proofId,
        String manifestSchema,
        String fileId,
        Integer fileVersion,
        String leafId,
        String batchNo,
        String issuedAt,
        String issuedStatus,
        String currentStatus,
        String keyId,
        Integer keyVersion,
        String keyFingerprint,
        String keySource,
        String contentHash,
        String computedContentHash,
        String merkleRoot,
        String computedMerkleRoot,
        String chainType,
        String chainId,
        String groupId,
        String contractAddress,
        String contractVersion,
        String abiFingerprint,
        String batchTransactionHash,
        Long liveBlockNumber,
        String liveChainSource
) {
}
