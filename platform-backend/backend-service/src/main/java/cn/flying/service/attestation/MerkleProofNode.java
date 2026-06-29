package cn.flying.service.attestation;

/**
 * One sibling node in a Merkle proof path.
 */
public record MerkleProofNode(
        String position,
        String hash
) {
    public static final String LEFT = "LEFT";
    public static final String RIGHT = "RIGHT";
}
