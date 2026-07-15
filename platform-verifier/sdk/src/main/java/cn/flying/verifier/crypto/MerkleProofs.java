package cn.flying.verifier.crypto;

import cn.flying.verifier.contract.SignedProofBundleModel;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Shared deterministic SHA-256 Merkle leaf, parent, and inclusion-path implementation.
 */
public final class MerkleProofs {

    public static final String PROOF_ALGORITHM = "SHA-256-MERKLE-V1";
    public static final String LEFT = "LEFT";
    public static final String RIGHT = "RIGHT";

    private MerkleProofs() {
    }

    /**
     * Calculates the public leaf hash for one evidence hash.
     *
     * @param evidenceHash canonical evidence hash
     * @return raw lowercase SHA-256 hex
     */
    public static String calculateLeafHash(String evidenceHash) {
        if (evidenceHash == null || evidenceHash.isBlank() || evidenceHash.length() > 256) {
            throw new IllegalArgumentException("Merkle evidence hash is required");
        }
        return rawSha256("leaf\n" + evidenceHash.trim());
    }

    /**
     * Calculates one ordered Merkle parent hash.
     *
     * @param leftHash raw left-child hash
     * @param rightHash raw right-child hash
     * @return raw lowercase SHA-256 hex
     */
    public static String calculateParentHash(String leftHash, String rightHash) {
        if (leftHash == null
                || rightHash == null
                || leftHash.isBlank()
                || rightHash.isBlank()
                || leftHash.length() > 128
                || rightHash.length() > 128) {
            throw new IllegalArgumentException("Merkle child hashes are required");
        }
        return rawSha256("node\n" + leftHash.trim() + "\n" + rightHash.trim());
    }

    /**
     * Applies an ordered proof path and returns the reached root.
     *
     * @param leafHash starting raw leaf hash
     * @param proofPath ordered sibling nodes
     * @return computed raw root, or {@code null} for malformed input
     */
    public static String calculateRootFromProof(
            String leafHash,
            List<SignedProofBundleModel.ProofNode> proofPath
    ) {
        if (leafHash == null || !ProofHashes.RAW_SHA256.matcher(leafHash).matches()
                || proofPath == null) {
            return null;
        }
        String current = leafHash;
        for (SignedProofBundleModel.ProofNode node : proofPath) {
            if (node == null || node.position() == null || node.hash() == null
                    || node.position().length() > 16
                    || !ProofHashes.RAW_SHA256.matcher(node.hash()).matches()) {
                return null;
            }
            String siblingHash = node.hash();
            if (LEFT.equals(node.position())) {
                current = calculateParentHash(siblingHash, current);
            } else if (RIGHT.equals(node.position())) {
                current = calculateParentHash(current, siblingHash);
            } else {
                return null;
            }
        }
        return current;
    }

    /**
     * Computes raw lowercase SHA-256 for one UTF-8 Merkle preimage.
     */
    private static String rawSha256(String value) {
        String prefixed = ProofHashes.sha256(value.getBytes(StandardCharsets.UTF_8));
        return prefixed.substring(ProofHashes.HASH_PREFIX.length());
    }
}
