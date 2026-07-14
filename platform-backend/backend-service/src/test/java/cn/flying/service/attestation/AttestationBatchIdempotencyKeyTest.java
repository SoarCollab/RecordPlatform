package cn.flying.service.attestation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AttestationBatchIdempotencyKeyTest {

    private final MerkleTreeService treeService = new MerkleTreeService();
    private final AttestationBatchIdempotencyKey keyGenerator = new AttestationBatchIdempotencyKey();

    /**
     * 验证调用方文件顺序不会改变同租户 batch 的稳定幂等键。
     */
    @Test
    void generate_shouldBeStableAcrossInputOrder() {
        MerkleTreeResult first = treeService.buildTree(List.of(
                new MerkleLeafInput(11L, "hash-b"),
                new MerkleLeafInput(12L, "hash-a")));
        MerkleTreeResult reordered = treeService.buildTree(List.of(
                new MerkleLeafInput(12L, "hash-a"),
                new MerkleLeafInput(11L, "hash-b")));

        assertThat(keyGenerator.generate(7L, first))
                .isEqualTo(keyGenerator.generate(7L, reordered))
                .hasSize(64);
    }

    /**
     * 验证租户和任一不可变叶子字段都会参与幂等键计算。
     */
    @Test
    void generate_shouldSeparateTenantsAndLeafIdentity() {
        MerkleTreeResult original = treeService.buildTree(List.of(new MerkleLeafInput(11L, "hash-a")));
        MerkleTreeResult otherFile = treeService.buildTree(List.of(new MerkleLeafInput(12L, "hash-a")));

        String originalKey = keyGenerator.generate(7L, original);

        assertThat(keyGenerator.generate(8L, original)).isNotEqualTo(originalKey);
        assertThat(keyGenerator.generate(7L, otherFile)).isNotEqualTo(originalKey);
    }
}
