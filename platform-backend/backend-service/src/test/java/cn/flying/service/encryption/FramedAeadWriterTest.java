package cn.flying.service.encryption;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证 framed AEAD writer 的固定向量、frame 公式和失败关闭语义。
 */
@DisplayName("FramedAeadWriter")
class FramedAeadWriterTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final int FRAME_SIZE = 64 * 1024;
    private static final byte[] FILE_DEK = HEX.parseHex(
            "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf");
    private static final byte[] FILE_NONCE = HEX.parseHex("0102030405060708090a0b0c0d0e0f10");
    private static final byte[] FIXED_PLAINTEXT = HEX.parseHex(
            "5265636f7264506c6174666f726d206672616d6564204145414420766563746f72");
    private static final String FIXED_ENCODED_HEX =
            "52504632020100000000000000000001000100000000000100000021"
                    + "0102030405060708090a0b0c0d0e0f10"
                    + "000000000000002100000031"
                    + "c98154d047fe1419102926ece729c8fa20d8e7ef26d91b2c5d8aa9091aed0c4524"
                    + "8afe4ca36333045745d0e05b20259122";

    private final FramedAeadWriter writer = new FramedAeadWriter();

    @TempDir
    Path tempDir;

    /**
     * 验证 Java writer 产生与 TypeScript/WebCrypto 相同的完整固定对象和摘要。
     */
    @Test
    void write_shouldMatchJavaAndTypeScriptFixedVector() throws IOException {
        Path plaintext = writeFile("fixed-plain.bin", FIXED_PLAINTEXT);
        Path encrypted = tempDir.resolve("fixed-framed.bin");

        FramedAeadWriter.WriteResult written = writer.write(
                plaintext, encrypted, FILE_DEK, FILE_NONCE, 0, 1, FRAME_SIZE);
        FramedAeadWriter.WriteResult verified = writer.verify(
                encrypted, FILE_DEK, FILE_NONCE, 0, 1, FRAME_SIZE);

        assertThat(HEX.formatHex(Files.readAllBytes(encrypted))).isEqualTo(FIXED_ENCODED_HEX);
        assertThat(written.plainSize()).isEqualTo(33L);
        assertThat(written.cipherSize()).isEqualTo(105L);
        assertThat(written.frameCount()).isEqualTo(1);
        assertThat(written.plainHash())
                .isEqualTo("sha256:f4d4fdd1095424253ffe035d5678961df7b9e12da2b40511811841127c2659a4");
        assertThat(written.cipherHash())
                .isEqualTo("sha256:4a6ba4e73d425291fe80258fb057df70a88837a9783f466080d7bebea4c4b131");
        assertThat(verified).isEqualTo(written);
    }

    /**
     * 验证 frameCount 向上取整公式、最后短帧和对象密文字节公式保持一致。
     */
    @Test
    void write_shouldUseCeilingFrameCountAndExactCipherSizeFormula() throws IOException {
        byte[] plaintextBytes = new byte[FRAME_SIZE * 2 + 1];
        for (int index = 0; index < plaintextBytes.length; index++) {
            plaintextBytes[index] = (byte) (index % 251);
        }
        Path plaintext = writeFile("multi-plain.bin", plaintextBytes);
        Path encrypted = tempDir.resolve("multi-framed.bin");

        FramedAeadWriter.WriteResult written = writer.write(
                plaintext, encrypted, FILE_DEK, FILE_NONCE, 0, 1, FRAME_SIZE);

        long expectedCipherSize = FramedAeadCrypto.CHUNK_HEADER_SIZE
                + plaintextBytes.length
                + 3L * (FramedAeadCrypto.FRAME_HEADER_SIZE + FramedAeadCrypto.TAG_SIZE);
        assertThat(written.frameCount()).isEqualTo(3);
        assertThat(written.cipherSize()).isEqualTo(expectedCipherSize);
        assertThat(Files.size(encrypted)).isEqualTo(expectedCipherSize);
        assertThat(writer.verify(encrypted, FILE_DEK, FILE_NONCE, 0, 1, FRAME_SIZE))
                .isEqualTo(written);
    }

    /**
     * 验证 tag 篡改、错误 key 和 AAD 坐标变化全部认证失败，且异常不泄露 key/nonce。
     */
    @Test
    void verify_shouldRejectTagTamperWrongKeyAndWrongAadWithoutSecretLeak() throws IOException {
        Path encrypted = createFramedFile("authentication", FIXED_PLAINTEXT);
        byte[] encoded = Files.readAllBytes(encrypted);

        byte[] tagTampered = encoded.clone();
        tagTampered[tagTampered.length - 1] ^= 0x01;
        IOException tagFailure = verifyFailure(
                writeFile("tag-tampered.bin", tagTampered), FILE_DEK, FILE_NONCE, 0, 1);

        byte[] wrongKey = FILE_DEK.clone();
        wrongKey[0] ^= 0x01;
        IOException keyFailure = verifyFailure(encrypted, wrongKey, FILE_NONCE, 0, 1);

        byte[] wrongAad = encoded.clone();
        wrongAad[15] = 2;
        IOException aadFailure = verifyFailure(
                writeFile("wrong-aad.bin", wrongAad), FILE_DEK, FILE_NONCE, 0, 2);

        String dekHex = HEX.formatHex(FILE_DEK);
        String nonceHex = HEX.formatHex(FILE_NONCE);
        assertThat(tagFailure.getMessage()).doesNotContain(dekHex, nonceHex);
        assertThat(keyFailure.getMessage()).doesNotContain(dekHex, nonceHex);
        assertThat(aadFailure.getMessage()).doesNotContain(dekHex, nonceHex);
    }

    /**
     * 验证截断、EOF 后追加和 frame 重排均在验证阶段失败关闭。
     */
    @Test
    void verify_shouldRejectTruncatedTrailingAndReorderedObjects() throws IOException {
        byte[] twoFrames = new byte[FRAME_SIZE * 2];
        for (int index = 0; index < twoFrames.length; index++) {
            twoFrames[index] = (byte) (index % 239);
        }
        Path encrypted = createFramedFile("wire-errors", twoFrames);
        byte[] encoded = Files.readAllBytes(encrypted);

        byte[] truncated = Arrays.copyOf(encoded, encoded.length - 1);
        verifyFailure(writeFile("truncated.bin", truncated), FILE_DEK, FILE_NONCE, 0, 1);

        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        trailing[trailing.length - 1] = 0x7f;
        verifyFailure(writeFile("trailing.bin", trailing), FILE_DEK, FILE_NONCE, 0, 1);

        int recordSize = FramedAeadCrypto.FRAME_HEADER_SIZE + FRAME_SIZE + FramedAeadCrypto.TAG_SIZE;
        byte[] reordered = encoded.clone();
        System.arraycopy(encoded, FramedAeadCrypto.CHUNK_HEADER_SIZE + recordSize,
                reordered, FramedAeadCrypto.CHUNK_HEADER_SIZE, recordSize);
        System.arraycopy(encoded, FramedAeadCrypto.CHUNK_HEADER_SIZE,
                reordered, FramedAeadCrypto.CHUNK_HEADER_SIZE + recordSize, recordSize);
        verifyFailure(writeFile("reordered.bin", reordered), FILE_DEK, FILE_NONCE, 0, 1);
    }

    /**
     * 验证随机生成的文件级 DEK 和 nonce 始终使用协议固定长度且每次返回独立数组。
     */
    @Test
    void generateFileMaterial_shouldUseProtocolLengthsAndIndependentArrays() {
        byte[] firstDek = writer.generateFileDek();
        byte[] secondDek = writer.generateFileDek();
        byte[] firstNonce = writer.generateFileNonce();
        byte[] secondNonce = writer.generateFileNonce();

        assertThat(firstDek).hasSize(FramedAeadCrypto.FILE_DEK_SIZE).isNotSameAs(secondDek);
        assertThat(secondDek).hasSize(FramedAeadCrypto.FILE_DEK_SIZE);
        assertThat(firstNonce).hasSize(FramedAeadCrypto.FILE_NONCE_SIZE).isNotSameAs(secondNonce);
        assertThat(secondNonce).hasSize(FramedAeadCrypto.FILE_NONCE_SIZE);
    }

    /**
     * 验证 manifest nonce 使用 Base64URL 无填充编码，并拒绝空值和错误长度。
     */
    @Test
    void encodeFileNonce_shouldUseBase64UrlWithoutPaddingAndRejectInvalidLength() {
        String encoded = writer.encodeFileNonce(FILE_NONCE);

        assertThat(encoded)
                .isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(FILE_NONCE))
                .doesNotContain("=");
        assertThrows(IllegalArgumentException.class, () -> writer.encodeFileNonce(null));
        assertThrows(IllegalArgumentException.class, () -> writer.encodeFileNonce(new byte[15]));
    }

    /**
     * 验证 writer/validator 对空路径、缺失文件和零字节明文均失败关闭。
     */
    @Test
    void writeAndVerify_shouldRejectMissingPathsAndEmptyPlaintext() throws IOException {
        Path missing = tempDir.resolve("missing.bin");
        Path encrypted = tempDir.resolve("invalid-framed.bin");
        Path empty = writeFile("empty.bin", new byte[0]);

        assertThrows(IOException.class, () -> writer.write(
                null, encrypted, FILE_DEK, FILE_NONCE, 0, 1, FRAME_SIZE));
        assertThrows(IOException.class, () -> writer.write(
                missing, encrypted, FILE_DEK, FILE_NONCE, 0, 1, FRAME_SIZE));
        assertThrows(IOException.class, () -> writer.write(
                empty, encrypted, FILE_DEK, FILE_NONCE, 0, 1, FRAME_SIZE));
        assertThrows(IOException.class, () -> writer.verify(
                null, FILE_DEK, FILE_NONCE, 0, 1, FRAME_SIZE));
        assertThrows(IOException.class, () -> writer.verify(
                missing, FILE_DEK, FILE_NONCE, 0, 1, FRAME_SIZE));
    }

    /**
     * 验证文件密钥、文件 nonce 和 frame 大小的每个边界都在分配 frame 缓冲区前被拒绝。
     */
    @Test
    void write_shouldRejectInvalidKeyNonceAndFrameBounds() throws IOException {
        Path plaintext = writeFile("invalid-state-plain.bin", FIXED_PLAINTEXT);
        Path encrypted = tempDir.resolve("invalid-state-framed.bin");

        assertThrows(IllegalArgumentException.class, () -> writer.write(
                plaintext, encrypted, null, FILE_NONCE, 0, 1, FRAME_SIZE));
        assertThrows(IllegalArgumentException.class, () -> writer.write(
                plaintext, encrypted, new byte[31], FILE_NONCE, 0, 1, FRAME_SIZE));
        assertThrows(IllegalArgumentException.class, () -> writer.write(
                plaintext, encrypted, FILE_DEK, null, 0, 1, FRAME_SIZE));
        assertThrows(IllegalArgumentException.class, () -> writer.write(
                plaintext, encrypted, FILE_DEK, new byte[15], 0, 1, FRAME_SIZE));
        assertThrows(IllegalArgumentException.class, () -> writer.write(
                plaintext, encrypted, FILE_DEK, FILE_NONCE, 0, 1,
                FramedAeadCrypto.MIN_FRAME_PLAIN_SIZE - 1));
        assertThrows(IllegalArgumentException.class, () -> writer.write(
                plaintext, encrypted, FILE_DEK, FILE_NONCE, 0, 1,
                FramedAeadCrypto.MAX_FRAME_PLAIN_SIZE + 1));
    }

    /**
     * 写入测试文件并返回路径。
     */
    private Path writeFile(String name, byte[] content) throws IOException {
        Path path = tempDir.resolve(name);
        Files.write(path, content);
        return path;
    }

    /**
     * 使用固定协议参数生成一个 framed 对象。
     */
    private Path createFramedFile(String name, byte[] plaintext) throws IOException {
        Path plainPath = writeFile(name + "-plain.bin", plaintext);
        Path encryptedPath = tempDir.resolve(name + "-framed.bin");
        writer.write(plainPath, encryptedPath, FILE_DEK, FILE_NONCE, 0, 1, FRAME_SIZE);
        return encryptedPath;
    }

    /**
     * 执行验证并返回预期的 IOException，供多个失败关闭场景复用。
     */
    private IOException verifyFailure(
            Path encrypted,
            byte[] fileDek,
            byte[] fileNonce,
            int chunkIndex,
            int chunkCount
    ) {
        return assertThrows(IOException.class, () -> writer.verify(
                encrypted, fileDek, fileNonce, chunkIndex, chunkCount, FRAME_SIZE));
    }
}
