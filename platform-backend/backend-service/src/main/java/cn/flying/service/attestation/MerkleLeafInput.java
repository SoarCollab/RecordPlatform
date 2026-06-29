package cn.flying.service.attestation;

/**
 * Canonical input for one file leaf in a Merkle attestation tree.
 */
public record MerkleLeafInput(
        Long fileId,
        String fileHash
) {
}
