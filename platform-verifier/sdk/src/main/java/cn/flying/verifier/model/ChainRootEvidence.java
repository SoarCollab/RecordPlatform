package cn.flying.verifier.model;

/** Live chain-root response returned by an explicitly configured resolver. */
public record ChainRootEvidence(
        String schemaVersion,
        String chainType,
        String chainId,
        String groupId,
        String contractAddress,
        String batchNo,
        String merkleRoot,
        String transactionHash,
        Long blockNumber,
        String source
) {
    public static final String SCHEMA_VERSION = "record-platform-chain-root-resolution.v1";
}
