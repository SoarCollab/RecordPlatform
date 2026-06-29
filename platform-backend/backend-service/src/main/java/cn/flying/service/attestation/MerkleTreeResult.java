package cn.flying.service.attestation;

import java.util.List;

/**
 * Deterministic Merkle tree output used by attestation batch persistence.
 */
public record MerkleTreeResult(
        String proofAlgorithm,
        String merkleRoot,
        List<MerkleLeafProof> leaves
) {
}
