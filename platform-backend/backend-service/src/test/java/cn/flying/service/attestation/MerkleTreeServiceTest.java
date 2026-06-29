package cn.flying.service.attestation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MerkleTreeService")
class MerkleTreeServiceTest {

    private final MerkleTreeService merkleTreeService = new MerkleTreeService();

    /**
     * Verifies canonical ordering makes the Merkle root independent of input order.
     */
    @Test
    void buildTree_shouldUseDeterministicCanonicalOrder() {
        MerkleTreeResult first = merkleTreeService.buildTree(List.of(
                new MerkleLeafInput(2L, "hash-b"),
                new MerkleLeafInput(1L, "hash-a"),
                new MerkleLeafInput(3L, "hash-c")
        ));
        MerkleTreeResult second = merkleTreeService.buildTree(List.of(
                new MerkleLeafInput(3L, "hash-c"),
                new MerkleLeafInput(1L, "hash-a"),
                new MerkleLeafInput(2L, "hash-b")
        ));

        assertThat(second.merkleRoot()).isEqualTo(first.merkleRoot());
        assertThat(first.proofAlgorithm()).isEqualTo(MerkleTreeService.PROOF_ALGORITHM);
        assertThat(first.leaves())
                .extracting(MerkleLeafProof::fileId)
                .containsExactly(1L, 2L, 3L);
    }

    /**
     * Verifies every generated proof path validates against the generated root.
     */
    @Test
    void verifyProof_shouldAcceptEveryGeneratedLeafProof() {
        MerkleTreeResult result = merkleTreeService.buildTree(List.of(
                new MerkleLeafInput(1L, "hash-a"),
                new MerkleLeafInput(2L, "hash-b"),
                new MerkleLeafInput(3L, "hash-c")
        ));

        for (MerkleLeafProof leaf : result.leaves()) {
            assertThat(merkleTreeService.verifyProof(leaf.leafHash(), leaf.proofPath(), result.merkleRoot()))
                    .isTrue();
        }
    }

    /**
     * Verifies a single-leaf batch uses the leaf hash as root and needs no proof siblings.
     */
    @Test
    void buildTree_shouldSupportSingleLeafBatch() {
        MerkleTreeResult result = merkleTreeService.buildTree(List.of(
                new MerkleLeafInput(1L, "hash-a")
        ));

        MerkleLeafProof leaf = result.leaves().getFirst();
        assertThat(result.merkleRoot()).isEqualTo(leaf.leafHash());
        assertThat(leaf.proofPath()).isEmpty();
        assertThat(merkleTreeService.verifyProof(leaf.leafHash(), leaf.proofPath(), result.merkleRoot()))
                .isTrue();
    }

    /**
     * Verifies the public proof contract can recompute the leaf hash from fileHash alone.
     */
    @Test
    void buildTree_shouldUsePublicFileHashForLeafHash() {
        MerkleTreeResult first = merkleTreeService.buildTree(List.of(
                new MerkleLeafInput(1L, "hash-a")
        ));
        MerkleTreeResult second = merkleTreeService.buildTree(List.of(
                new MerkleLeafInput(999L, "hash-a")
        ));

        assertThat(second.merkleRoot()).isEqualTo(first.merkleRoot());
        assertThat(second.leaves().getFirst().leafHash()).isEqualTo(first.leaves().getFirst().leafHash());
    }

    /**
     * Verifies duplicate file IDs are rejected so one file cannot appear twice in a batch.
     */
    @Test
    void buildTree_shouldRejectDuplicateFileIds() {
        assertThatThrownBy(() -> merkleTreeService.buildTree(List.of(
                new MerkleLeafInput(1L, "hash-a"),
                new MerkleLeafInput(1L, "hash-b")
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate file ID");
    }

    /**
     * Verifies proof validation fails when a sibling hash is tampered.
     */
    @Test
    void verifyProof_shouldRejectTamperedProofPath() {
        MerkleTreeResult result = merkleTreeService.buildTree(List.of(
                new MerkleLeafInput(1L, "hash-a"),
                new MerkleLeafInput(2L, "hash-b")
        ));
        MerkleLeafProof leaf = result.leaves().getFirst();
        List<MerkleProofNode> tamperedPath = List.of(new MerkleProofNode(
                leaf.proofPath().getFirst().position(),
                "tampered"
        ));

        assertThat(merkleTreeService.verifyProof(leaf.leafHash(), tamperedPath, result.merkleRoot()))
                .isFalse();
    }
}
