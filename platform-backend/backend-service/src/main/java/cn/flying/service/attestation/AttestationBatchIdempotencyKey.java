package cn.flying.service.attestation;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 为同租户、同算法和同一组规范化叶子生成稳定的批量存证幂等键。
 */
@Component
public class AttestationBatchIdempotencyKey {

    /**
     * 生成不会受调用方文件顺序影响的 SHA-256 幂等键。
     */
    public String generate(Long tenantId, MerkleTreeResult tree) {
        StringBuilder canonical = new StringBuilder()
                .append("tenant\n").append(tenantId)
                .append("\nalgorithm\n").append(tree.proofAlgorithm())
                .append("\nleaves\n");
        for (MerkleLeafProof leaf : tree.leaves()) {
            canonical.append(leaf.fileId())
                    .append(':')
                    .append(leaf.fileHash().length())
                    .append(':')
                    .append(leaf.fileHash())
                    .append('\n');
        }
        return sha256Hex(canonical.toString());
    }

    /**
     * 计算 UTF-8 文本的 SHA-256 小写十六进制摘要。
     */
    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is not available", e);
        }
    }
}
