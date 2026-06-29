package cn.flying.service.attestation;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds and verifies deterministic SHA-256 Merkle proofs for file attestations.
 */
@Service
public class MerkleTreeService {

    public static final String PROOF_ALGORITHM = "SHA-256-MERKLE-V1";
    private static final String DIGEST_ALGORITHM = "SHA-256";

    /**
     * Builds a deterministic Merkle tree with stable leaf ordering and inclusion proofs.
     *
     * @param inputs file leaves to attest
     * @return Merkle root and proof path for each leaf
     */
    public MerkleTreeResult buildTree(List<MerkleLeafInput> inputs) {
        List<MerkleLeafInput> canonicalInputs = canonicalize(inputs);
        List<List<MerkleProofNode>> proofPaths = new ArrayList<>(canonicalInputs.size());
        List<Node> currentLevel = new ArrayList<>(canonicalInputs.size());

        for (int i = 0; i < canonicalInputs.size(); i++) {
            MerkleLeafInput input = canonicalInputs.get(i);
            proofPaths.add(new ArrayList<>());
            currentLevel.add(new Node(calculateLeafHash(input.fileHash()), List.of(i)));
        }

        while (currentLevel.size() > 1) {
            List<Node> nextLevel = new ArrayList<>((currentLevel.size() + 1) / 2);
            for (int i = 0; i < currentLevel.size(); i += 2) {
                Node left = currentLevel.get(i);
                Node right = i + 1 < currentLevel.size() ? currentLevel.get(i + 1) : left;

                addSiblingProofs(proofPaths, left.leafIndexes(), MerkleProofNode.RIGHT, right.hash());
                if (right != left) {
                    addSiblingProofs(proofPaths, right.leafIndexes(), MerkleProofNode.LEFT, left.hash());
                }

                List<Integer> parentIndexes = new ArrayList<>(left.leafIndexes());
                if (right != left) {
                    parentIndexes.addAll(right.leafIndexes());
                }
                nextLevel.add(new Node(calculateParentHash(left.hash(), right.hash()), parentIndexes));
            }
            currentLevel = nextLevel;
        }

        List<MerkleLeafProof> leaves = new ArrayList<>(canonicalInputs.size());
        for (int i = 0; i < canonicalInputs.size(); i++) {
            MerkleLeafInput input = canonicalInputs.get(i);
            leaves.add(new MerkleLeafProof(
                    input.fileId(),
                    input.fileHash(),
                    calculateLeafHash(input.fileHash()),
                    i,
                    List.copyOf(proofPaths.get(i))
            ));
        }

        return new MerkleTreeResult(PROOF_ALGORITHM, currentLevel.getFirst().hash(), List.copyOf(leaves));
    }

    /**
     * Verifies a leaf hash and proof path against a claimed Merkle root.
     */
    public boolean verifyProof(String leafHash, List<MerkleProofNode> proofPath, String merkleRoot) {
        if (!StringUtils.hasText(merkleRoot)) {
            return false;
        }
        String computedRoot = calculateRootFromProof(leafHash, proofPath);
        return computedRoot != null && computedRoot.equals(merkleRoot.trim());
    }

    /**
     * Recomputes the Merkle root reached by applying a proof path to one leaf hash.
     *
     * @param leafHash starting leaf hash
     * @param proofPath ordered sibling path from leaf to root
     * @return computed root, or null when the input path is malformed
     */
    public String calculateRootFromProof(String leafHash, List<MerkleProofNode> proofPath) {
        if (!StringUtils.hasText(leafHash) || proofPath == null) {
            return null;
        }
        String current = leafHash.trim();
        for (MerkleProofNode proofNode : proofPath) {
            if (proofNode == null || !StringUtils.hasText(proofNode.hash())) {
                return null;
            }
            if (MerkleProofNode.LEFT.equals(proofNode.position())) {
                current = calculateParentHash(proofNode.hash(), current);
            } else if (MerkleProofNode.RIGHT.equals(proofNode.position())) {
                current = calculateParentHash(current, proofNode.hash());
            } else {
                return null;
            }
        }
        return current;
    }

    private List<MerkleLeafInput> canonicalize(List<MerkleLeafInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("Merkle leaf inputs cannot be empty");
        }

        Set<Long> seenFileIds = new HashSet<>();
        List<MerkleLeafInput> canonical = new ArrayList<>(inputs.size());
        for (MerkleLeafInput input : inputs) {
            if (input == null || input.fileId() == null || !StringUtils.hasText(input.fileHash())) {
                throw new IllegalArgumentException("Merkle leaf input fileId and fileHash are required");
            }
            if (!seenFileIds.add(input.fileId())) {
                throw new IllegalArgumentException("Duplicate file ID in Merkle batch: " + input.fileId());
            }
            canonical.add(new MerkleLeafInput(input.fileId(), input.fileHash().trim()));
        }

        canonical.sort(Comparator
                .comparing(MerkleLeafInput::fileHash)
                .thenComparing(MerkleLeafInput::fileId));
        return List.copyOf(canonical);
    }

    private void addSiblingProofs(List<List<MerkleProofNode>> proofPaths, List<Integer> leafIndexes,
                                  String siblingPosition, String siblingHash) {
        MerkleProofNode node = new MerkleProofNode(siblingPosition, siblingHash);
        for (Integer leafIndex : new LinkedHashSet<>(leafIndexes)) {
            proofPaths.get(leafIndex).add(node);
        }
    }

    /**
     * Calculate a public leaf hash from the file hash so exported bundles can verify it offline.
     */
    public String calculateLeafHash(String fileHash) {
        return sha256Hex("leaf\n" + fileHash.trim());
    }

    /**
     * Calculate the Merkle parent hash from ordered left and right child hashes.
     */
    public String calculateParentHash(String leftHash, String rightHash) {
        return sha256Hex("node\n" + leftHash.trim() + "\n" + rightHash.trim());
    }

    /**
     * Calculate SHA-256 as lowercase hexadecimal text.
     */
    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is not available", e);
        }
    }

    private record Node(String hash, List<Integer> leafIndexes) {
    }
}
