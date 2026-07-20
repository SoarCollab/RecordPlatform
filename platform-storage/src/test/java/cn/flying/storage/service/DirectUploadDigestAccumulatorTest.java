package cn.flying.storage.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证整文件摘要累加器只提交已验证分片，并输出规范 SHA-256 内容哈希。
 */
@DisplayName("DirectUploadDigestAccumulator Unit Tests")
class DirectUploadDigestAccumulatorTest {

    /**
     * 验证失败候选未提交时不会污染后续成功候选的摘要状态。
     */
    @Test
    @DisplayName("uncommitted fork should not contaminate committed state")
    void shouldIgnoreUncommittedFailedFork() throws Exception {
        DirectUploadDigestAccumulator accumulator = DirectUploadDigestAccumulator.sha256();
        MessageDigest failedCandidate = accumulator.fork();
        failedCandidate.update(bytes("failed-replica"));

        MessageDigest successfulCandidate = accumulator.fork();
        successfulCandidate.update(bytes("verified-replica"));
        accumulator.commit(successfulCandidate);

        assertThat(accumulator.finishHash()).isEqualTo(expectedHash(bytes("verified-replica")));
    }

    /**
     * 验证成功候选提交后会成为整文件摘要的当前状态。
     */
    @Test
    @DisplayName("committed fork should update accumulated digest")
    void shouldCommitSuccessfulFork() throws Exception {
        DirectUploadDigestAccumulator accumulator = DirectUploadDigestAccumulator.sha256();
        MessageDigest candidate = accumulator.fork();
        candidate.update(bytes("verified-part"));

        accumulator.commit(candidate);

        assertThat(accumulator.finishHash()).isEqualTo(expectedHash(bytes("verified-part")));
    }

    /**
     * 验证多个分片按提交顺序连续参与整文件摘要计算。
     */
    @Test
    @DisplayName("multiple committed parts should accumulate in order")
    void shouldAccumulateMultiplePartsInOrder() throws Exception {
        DirectUploadDigestAccumulator accumulator = DirectUploadDigestAccumulator.sha256();
        byte[] firstPart = bytes("part-one");
        byte[] secondPart = bytes("part-two");
        byte[] thirdPart = bytes("part-three");

        for (byte[] part : new byte[][]{firstPart, secondPart, thirdPart}) {
            MessageDigest candidate = accumulator.fork();
            candidate.update(part);
            accumulator.commit(candidate);
        }

        assertThat(accumulator.finishHash())
                .isEqualTo(expectedHash(firstPart, secondPart, thirdPart))
                .isNotEqualTo(expectedHash(thirdPart, secondPart, firstPart));
    }

    /**
     * 验证最终摘要使用小写十六进制并带规范的 sha256 前缀。
     */
    @Test
    @DisplayName("finishHash should return canonical sha256 content hash")
    void shouldReturnCanonicalSha256Hash() {
        String contentHash = DirectUploadDigestAccumulator.sha256().finishHash();

        assertThat(contentHash)
                .startsWith("sha256:")
                .matches("sha256:[0-9a-f]{64}")
                .isEqualTo("sha256:e3b0c44298fc1c149afbf4c8996fb924"
                        + "27ae41e4649b934ca495991b7852b855");
    }

    /**
     * 将测试文本转换为确定性的 UTF-8 字节。
     */
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 按给定顺序计算测试期望的规范 SHA-256 内容哈希。
     */
    private static String expectedHash(byte[]... chunks) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (byte[] chunk : chunks) {
            digest.update(chunk);
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }
}
