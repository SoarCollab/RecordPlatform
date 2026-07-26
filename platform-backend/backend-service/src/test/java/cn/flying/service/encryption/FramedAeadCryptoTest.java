package cn.flying.service.encryption;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 framed AEAD 的跨语言密码学、字节序和固定 wire 合同。
 */
@DisplayName("FramedAeadCrypto")
class FramedAeadCryptoTest {

    private static final HexFormat HEX = HexFormat.of();

    /**
     * 验证 RFC 5869 的 HKDF-SHA-256 官方测试向量，锁定 Extract/Expand 字节语义。
     */
    @Test
    void hkdfSha256_shouldMatchRfc5869TestVector() {
        byte[] ikm = HEX.parseHex("0b".repeat(22));
        byte[] salt = HEX.parseHex("000102030405060708090a0b0c");
        byte[] info = HEX.parseHex("f0f1f2f3f4f5f6f7f8f9");

        byte[] prk = FramedAeadCrypto.hkdfExtract(salt, ikm);
        byte[] okm = FramedAeadCrypto.hkdfExpand(prk, info, 42);

        assertThat(HEX.formatHex(prk))
                .isEqualTo("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5");
        assertThat(HEX.formatHex(okm)).isEqualTo(
                "3cb25f25faacd57a90434f64d0362f2a"
                        + "2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                        + "34007208d5b887185865");
    }

    /**
     * 验证 Java 与 TypeScript 共享的固定 key、nonce、AAD、header 和 frame header 向量。
     */
    @Test
    void fixedVector_shouldMatchJavaAndTypeScriptByteContract() {
        byte[] fileDek = HEX.parseHex(
                "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf");
        byte[] fileNonce = HEX.parseHex("0102030405060708090a0b0c0d0e0f10");
        byte[] key = FramedAeadCrypto.deriveFrameKey(fileDek, fileNonce, 0, 0);
        byte[] nonce = FramedAeadCrypto.deriveFrameNonce(fileDek, fileNonce, 0, 0);
        byte[] plaintext = "RecordPlatform framed AEAD vector".getBytes(StandardCharsets.UTF_8);

        byte[] aad = FramedAeadCrypto.buildAad(
                fileNonce, 0, 1, 0, 1, plaintext.length, plaintext.length);
        byte[] chunkHeader = FramedAeadCrypto.buildChunkHeader(
                0, 1, 64 * 1024, 1, plaintext.length, fileNonce);
        byte[] frameHeader = FramedAeadCrypto.buildFrameHeader(
                0, plaintext.length, plaintext.length + FramedAeadCrypto.TAG_SIZE);

        assertThat(HEX.formatHex(key))
                .isEqualTo("15253164a5acfe1831702aa2747b7eb097187da0637b434d581894fb96bc43db");
        assertThat(HEX.formatHex(nonce)).isEqualTo("a63a60144f5db28ef6545e19");
        assertThat(HEX.formatHex(aad)).isEqualTo(
                "636e2e666c79696e672e6672616d65642d616561642e6161642e7632"
                        + "0201"
                        + "0102030405060708090a0b0c0d0e0f10"
                        + "000000000000000100000000000000010000002100000021");
        assertThat(HEX.formatHex(chunkHeader)).isEqualTo(
                "52504632020100000000000000000001000100000000000100000021"
                        + "0102030405060708090a0b0c0d0e0f10");
        assertThat(HEX.formatHex(frameHeader)).isEqualTo("000000000000002100000031");

        FramedAeadCrypto.ChunkHeader parsedChunk = FramedAeadCrypto.parseChunkHeader(chunkHeader);
        FramedAeadCrypto.FrameHeader parsedFrame = FramedAeadCrypto.parseFrameHeader(frameHeader);
        assertThat(parsedChunk.chunkIndex()).isZero();
        assertThat(parsedChunk.chunkCount()).isEqualTo(1);
        assertThat(parsedChunk.framePlainSize()).isEqualTo(64 * 1024);
        assertThat(parsedChunk.frameCount()).isEqualTo(1);
        assertThat(parsedChunk.chunkPlainSize()).isEqualTo(plaintext.length);
        assertThat(parsedFrame.frameIndex()).isZero();
        assertThat(parsedFrame.plainLength()).isEqualTo(plaintext.length);
        assertThat(parsedFrame.cipherLength()).isEqualTo(plaintext.length + 16);
    }

    /**
     * 验证 header/parser 拒绝错误 magic、保留位和 frame 长度，避免大小端或边界漂移。
     */
    @Test
    void parsers_shouldRejectTamperedWireHeaders() {
        byte[] nonce = new byte[FramedAeadCrypto.FILE_NONCE_SIZE];
        byte[] chunkHeader = FramedAeadCrypto.buildChunkHeader(0, 1, 64 * 1024, 1, 1, nonce);
        chunkHeader[0] = 'X';
        assertThatThrownBy(() -> FramedAeadCrypto.parseChunkHeader(chunkHeader))
                .isInstanceOf(IllegalArgumentException.class);

        byte[] validHeader = FramedAeadCrypto.buildChunkHeader(0, 1, 64 * 1024, 1, 1, nonce);
        validHeader[7] = 1;
        assertThatThrownBy(() -> FramedAeadCrypto.parseChunkHeader(validHeader))
                .isInstanceOf(IllegalArgumentException.class);

        byte[] invalidFrame = new byte[FramedAeadCrypto.FRAME_HEADER_SIZE];
        invalidFrame[7] = 1;
        assertThatThrownBy(() -> FramedAeadCrypto.parseFrameHeader(invalidFrame))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 验证固定时间比较同时拒绝长度不一致，避免截断值被误判为相等。
     */
    @Test
    void constantTimeEquals_shouldRequireEqualLengthAndContent() {
        assertThat(FramedAeadCrypto.constantTimeEquals(new byte[]{1, 2}, new byte[]{1, 2})).isTrue();
        assertThat(FramedAeadCrypto.constantTimeEquals(new byte[]{1, 2}, new byte[]{1, 3})).isFalse();
        assertThat(FramedAeadCrypto.constantTimeEquals(new byte[]{1, 2}, new byte[]{1})).isFalse();
    }
}
