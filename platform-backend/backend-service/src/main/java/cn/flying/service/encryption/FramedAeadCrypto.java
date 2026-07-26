package cn.flying.service.encryption;

import javax.crypto.Mac;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * framed AES-GCM v2 的字节序、HKDF、AAD 和 wire header 工具。
 */
public final class FramedAeadCrypto {

    public static final byte[] MAGIC = new byte[]{'R', 'P', 'F', '2'};
    public static final int FORMAT_VERSION = 2;
    public static final int ALGORITHM_ID_AES_256_GCM = 1;
    public static final int CHUNK_HEADER_SIZE = 44;
    public static final int FRAME_HEADER_SIZE = 12;
    public static final int TAG_SIZE = 16;
    public static final int FILE_NONCE_SIZE = 16;
    public static final int FILE_DEK_SIZE = 32;
    public static final int FRAME_NONCE_SIZE = 12;
    /** 单个分片允许的最大认证 frame 数，防止不可信计数导致长循环。 */
    public static final int MAX_FRAMES_PER_PART = 10_000;
    public static final int MIN_FRAME_PLAIN_SIZE = 64 * 1024;
    public static final int MAX_FRAME_PLAIN_SIZE = 4 * 1024 * 1024;
    public static final String KEY_INFO_PREFIX = "cn.flying.framed-aead.v2/key";
    public static final String NONCE_INFO_PREFIX = "cn.flying.framed-aead.v2/nonce";
    public static final String AAD_PREFIX = "cn.flying.framed-aead.aad.v2";

    private FramedAeadCrypto() {
    }

    /**
     * 构造 44 字节 chunk header，所有整数使用 unsigned big-endian。
     */
    public static byte[] buildChunkHeader(
            int chunkIndex,
            int chunkCount,
            int framePlainSize,
            int frameCount,
            long chunkPlainSize,
            byte[] fileNonce
    ) {
        validateChunkCoordinates(chunkIndex, chunkCount, framePlainSize, frameCount, chunkPlainSize, fileNonce);
        ByteBuffer buffer = ByteBuffer.allocate(CHUNK_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        buffer.put(MAGIC)
                .put((byte) FORMAT_VERSION)
                .put((byte) ALGORITHM_ID_AES_256_GCM)
                .putShort((short) 0)
                .putInt(chunkIndex)
                .putInt(chunkCount)
                .putInt(framePlainSize)
                .putInt(frameCount)
                .putInt((int) chunkPlainSize)
                .put(fileNonce.clone());
        return buffer.array();
    }

    /**
     * 解析并校验 44 字节 chunk header。
     */
    public static ChunkHeader parseChunkHeader(byte[] header) {
        if (header == null || header.length != CHUNK_HEADER_SIZE) {
            throw new IllegalArgumentException("chunk header length is invalid");
        }
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        byte[] magic = new byte[MAGIC.length];
        buffer.get(magic);
        int version = Byte.toUnsignedInt(buffer.get());
        int algorithmId = Byte.toUnsignedInt(buffer.get());
        int flags = Short.toUnsignedInt(buffer.getShort());
        int chunkIndex = buffer.getInt();
        int chunkCount = buffer.getInt();
        int framePlainSize = buffer.getInt();
        int frameCount = buffer.getInt();
        long chunkPlainSize = Integer.toUnsignedLong(buffer.getInt());
        byte[] fileNonce = new byte[FILE_NONCE_SIZE];
        buffer.get(fileNonce);
        if (!Arrays.equals(MAGIC, magic)
                || version != FORMAT_VERSION
                || algorithmId != ALGORITHM_ID_AES_256_GCM
                || flags != 0) {
            throw new IllegalArgumentException("unsupported framed AEAD chunk header");
        }
        validateChunkCoordinates(chunkIndex, chunkCount, framePlainSize, frameCount, chunkPlainSize, fileNonce);
        return new ChunkHeader(version, algorithmId, flags, chunkIndex, chunkCount,
                framePlainSize, frameCount, chunkPlainSize, fileNonce);
    }

    /**
     * 构造 12 字节 frame header。
     */
    public static byte[] buildFrameHeader(int frameIndex, int plainLength, int cipherLength) {
        if (frameIndex < 0 || frameIndex >= MAX_FRAMES_PER_PART
                || plainLength <= 0 || plainLength > MAX_FRAME_PLAIN_SIZE
                || cipherLength <= 0
                || (long) cipherLength != (long) plainLength + TAG_SIZE) {
            throw new IllegalArgumentException("frame header values are invalid");
        }
        return ByteBuffer.allocate(FRAME_HEADER_SIZE)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(frameIndex)
                .putInt(plainLength)
                .putInt(cipherLength)
                .array();
    }

    /**
     * 解析并校验 12 字节 frame header。
     */
    public static FrameHeader parseFrameHeader(byte[] header) {
        if (header == null || header.length != FRAME_HEADER_SIZE) {
            throw new IllegalArgumentException("frame header length is invalid");
        }
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        int frameIndex = buffer.getInt();
        int plainLength = buffer.getInt();
        int cipherLength = buffer.getInt();
        if (frameIndex < 0 || frameIndex >= MAX_FRAMES_PER_PART
                || plainLength <= 0 || plainLength > MAX_FRAME_PLAIN_SIZE
                || cipherLength <= 0
                || (long) cipherLength != (long) plainLength + TAG_SIZE) {
            throw new IllegalArgumentException("frame header values are invalid");
        }
        return new FrameHeader(frameIndex, plainLength, cipherLength);
    }

    /**
     * 使用 RFC 5869 HKDF-SHA-256 派生当前 frame 的 AES key。
     */
    public static byte[] deriveFrameKey(byte[] fileDek, byte[] fileNonce, int chunkIndex, int frameIndex) {
        validateKeyInputs(fileDek, fileNonce, chunkIndex, frameIndex);
        byte[] prk = hkdfExtract(fileNonce, fileDek);
        return hkdfExpand(prk, info(KEY_INFO_PREFIX, chunkIndex, frameIndex), FILE_DEK_SIZE);
    }

    /**
     * 使用 RFC 5869 HKDF-SHA-256 派生当前 frame 的 96-bit nonce。
     */
    public static byte[] deriveFrameNonce(byte[] fileDek, byte[] fileNonce, int chunkIndex, int frameIndex) {
        validateKeyInputs(fileDek, fileNonce, chunkIndex, frameIndex);
        byte[] prk = hkdfExtract(fileNonce, fileDek);
        return hkdfExpand(prk, info(NONCE_INFO_PREFIX, chunkIndex, frameIndex), FRAME_NONCE_SIZE);
    }

    /**
     * 构造与 TypeScript reader 共享的 framed AES-GCM AAD 字节串。
     */
    public static byte[] buildAad(
            byte[] fileNonce,
            int chunkIndex,
            int chunkCount,
            int frameIndex,
            int frameCount,
            int plainLength,
            long chunkPlainSize
    ) {
        if (fileNonce == null || fileNonce.length != FILE_NONCE_SIZE
                || chunkIndex < 0 || chunkCount <= 0 || chunkIndex >= chunkCount
                || frameIndex < 0 || frameCount <= 0 || frameIndex >= frameCount
                || plainLength <= 0 || chunkPlainSize <= 0 || chunkPlainSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("AAD values are invalid");
        }
        ByteBuffer buffer = ByteBuffer.allocate(
                        AAD_PREFIX.getBytes(StandardCharsets.UTF_8).length + 2 + FILE_NONCE_SIZE + 4 * 6)
                .order(ByteOrder.BIG_ENDIAN);
        buffer.put(AAD_PREFIX.getBytes(StandardCharsets.UTF_8))
                .put((byte) FORMAT_VERSION)
                .put((byte) ALGORITHM_ID_AES_256_GCM)
                .put(fileNonce.clone())
                .putInt(chunkIndex)
                .putInt(chunkCount)
                .putInt(frameIndex)
                .putInt(frameCount)
                .putInt(plainLength)
                .putInt((int) chunkPlainSize);
        return buffer.array();
    }

    /**
     * 执行 HKDF-Extract，供协议实现和 RFC 向量测试复用。
     */
    public static byte[] hkdfExtract(byte[] salt, byte[] inputKeyMaterial) {
        if (salt == null || inputKeyMaterial == null) {
            throw new IllegalArgumentException("HKDF inputs are required");
        }
        return hmac(salt, inputKeyMaterial);
    }

    /**
     * 执行 HKDF-Expand，限制输出长度避免不受控内存分配。
     */
    public static byte[] hkdfExpand(byte[] prk, byte[] info, int length) {
        if (prk == null || prk.length != 32 || info == null || length < 0 || length > 255 * 32) {
            throw new IllegalArgumentException("HKDF expand inputs are invalid");
        }
        byte[] output = new byte[length];
        byte[] previous = new byte[0];
        int offset = 0;
        for (int counter = 1; offset < length; counter++) {
            ByteBuffer input = ByteBuffer.allocate(previous.length + info.length + 1);
            input.put(previous).put(info).put((byte) counter);
            previous = hmac(prk, input.array());
            int copyLength = Math.min(previous.length, length - offset);
            System.arraycopy(previous, 0, output, offset, copyLength);
            offset += copyLength;
        }
        return output;
    }

    /**
     * 规范化 hash 比较，避免长度和大小写差异绕过完整性校验。
     */
    public static boolean constantTimeEquals(byte[] left, byte[] right) {
        if (left == null || right == null || left.length != right.length) {
            return false;
        }
        int difference = 0;
        for (int i = 0; i < left.length; i++) {
            difference |= left[i] ^ right[i];
        }
        return difference == 0;
    }

    private static byte[] info(String prefix, int chunkIndex, int frameIndex) {
        byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(prefixBytes.length + 8)
                .order(ByteOrder.BIG_ENDIAN)
                .put(prefixBytes)
                .putInt(chunkIndex)
                .putInt(frameIndex)
                .array();
    }

    private static byte[] hmac(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static void validateKeyInputs(byte[] fileDek, byte[] fileNonce, int chunkIndex, int frameIndex) {
        if (fileDek == null || fileDek.length != FILE_DEK_SIZE
                || fileNonce == null || fileNonce.length != FILE_NONCE_SIZE
                || chunkIndex < 0 || frameIndex < 0) {
            throw new IllegalArgumentException("framed AEAD key inputs are invalid");
        }
    }

    private static void validateChunkCoordinates(
            int chunkIndex,
            int chunkCount,
            int framePlainSize,
            int frameCount,
            long chunkPlainSize,
            byte[] fileNonce
    ) {
        if (fileNonce == null || fileNonce.length != FILE_NONCE_SIZE
                || chunkIndex < 0 || chunkCount <= 0 || chunkIndex >= chunkCount
                || framePlainSize < MIN_FRAME_PLAIN_SIZE || framePlainSize > MAX_FRAME_PLAIN_SIZE
                || frameCount <= 0 || frameCount > MAX_FRAMES_PER_PART
                || chunkPlainSize <= 0 || chunkPlainSize > Integer.MAX_VALUE
                || frameCount != calculateFrameCount(chunkPlainSize, framePlainSize)) {
            throw new IllegalArgumentException("chunk header values are invalid");
        }
    }

    /**
     * 以不溢出的商/余数计算分片需要的 frame 数，并应用数量上限。
     */
    public static int calculateFrameCount(long chunkPlainSize, int framePlainSize) {
        if (chunkPlainSize <= 0 || framePlainSize <= 0) {
            throw new IllegalArgumentException("frame count inputs are invalid");
        }
        long quotient = chunkPlainSize / framePlainSize;
        long remainder = chunkPlainSize % framePlainSize;
        long count = quotient + (remainder == 0 ? 0 : 1);
        if (count <= 0 || count > MAX_FRAMES_PER_PART) {
            throw new IllegalArgumentException("frame count exceeds bounded limit");
        }
        return Math.toIntExact(count);
    }

    /**
     * 已验证的 chunk header 字段。
     */
    public record ChunkHeader(
            int version,
            int algorithmId,
            int flags,
            int chunkIndex,
            int chunkCount,
            int framePlainSize,
            int frameCount,
            long chunkPlainSize,
            byte[] fileNonce
    ) {
        public ChunkHeader {
            fileNonce = fileNonce.clone();
        }
    }

    /**
     * 已验证的 frame header 字段。
     */
    public record FrameHeader(int frameIndex, int plainLength, int cipherLength) {
    }
}
