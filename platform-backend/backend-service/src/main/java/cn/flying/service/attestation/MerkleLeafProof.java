package cn.flying.service.attestation;

import java.util.List;

/**
 * Merkle inclusion proof for one canonical file leaf.
 */
public record MerkleLeafProof(
        Long fileId,
        String fileHash,
        String leafHash,
        int leafIndex,
        List<MerkleProofNode> proofPath
) {
}
