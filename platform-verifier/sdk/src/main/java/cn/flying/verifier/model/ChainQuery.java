package cn.flying.verifier.model;

/** Immutable query identity needed to resolve one live batch root. */
public record ChainQuery(
        String chainType,
        String chainId,
        String groupId,
        String contractAddress,
        String batchNo,
        String expectedTransactionHash,
        String expectedMerkleRoot
) {
}
