package cn.flying.service.encryption;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * 以固定 frame 内存预算生成和验证 framed AES-GCM v2 对象。
 */
@Component
public class FramedAeadWriter {

    private static final String HASH_PREFIX = "sha256:";
    private static final int IO_BUFFER_SIZE = 64 * 1024;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 生成稳定长度的文件级 DEK；调用方负责将其写入受控会话检查点。
     */
    public byte[] generateFileDek() {
        byte[] dek = new byte[FramedAeadCrypto.FILE_DEK_SIZE];
        secureRandom.nextBytes(dek);
        return dek;
    }

    /**
     * 生成稳定长度的文件级 nonce；调用方负责将其写入受控会话检查点。
     */
    public byte[] generateFileNonce() {
        byte[] nonce = new byte[FramedAeadCrypto.FILE_NONCE_SIZE];
        secureRandom.nextBytes(nonce);
        return nonce;
    }

    /**
     * 将一个明文分片按 1MiB 默认、有界 frame 写成 v2 对象。
     */
    public WriteResult write(
            Path plainPath,
            Path encryptedPath,
            byte[] fileDek,
            byte[] fileNonce,
            int chunkIndex,
            int chunkCount,
            int framePlainSize
    ) throws IOException {
        validatePathsAndKeys(plainPath, encryptedPath, fileDek, fileNonce, framePlainSize);
        long plainSize = Files.size(plainPath);
        if (plainSize <= 0 || plainSize > Integer.MAX_VALUE) {
            throw new IOException("framed chunk plaintext size is invalid");
        }
        int frameCount = FramedAeadCrypto.calculateFrameCount(plainSize, framePlainSize);
        byte[] header = FramedAeadCrypto.buildChunkHeader(
                chunkIndex, chunkCount, framePlainSize, frameCount, plainSize, fileNonce);
        MessageDigest plainDigest = sha256();
        MessageDigest cipherDigest = sha256();
        byte[] frameBuffer = new byte[framePlainSize];
        long remaining = plainSize;
        int frameIndex = 0;

        Path parent = encryptedPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (InputStream input = Files.newInputStream(plainPath);
             OutputStream output = Files.newOutputStream(encryptedPath)) {
            output.write(header);
            cipherDigest.update(header);
            while (remaining > 0) {
                int expectedLength = (int) Math.min(framePlainSize, remaining);
                readFully(input, frameBuffer, expectedLength);
                plainDigest.update(frameBuffer, 0, expectedLength);
                byte[] ciphertext = encryptFrame(
                        frameBuffer, expectedLength, fileDek, fileNonce,
                        chunkIndex, chunkCount, frameIndex, frameCount, plainSize);
                byte[] frameHeader = FramedAeadCrypto.buildFrameHeader(
                        frameIndex, expectedLength, ciphertext.length);
                output.write(frameHeader);
                output.write(ciphertext);
                cipherDigest.update(frameHeader);
                cipherDigest.update(ciphertext);
                remaining -= expectedLength;
                frameIndex++;
            }
            if (input.read() != -1) {
                throw new IOException("framed chunk contains unexpected trailing plaintext");
            }
            output.flush();
        }
        return new WriteResult(
                plainSize,
                Files.size(encryptedPath),
                frameCount,
                HASH_PREFIX + HexFormat.of().formatHex(plainDigest.digest()),
                HASH_PREFIX + HexFormat.of().formatHex(cipherDigest.digest()));
    }

    /**
     * 在不产生明文输出的情况下完整验证 v2 对象的 header、frame 顺序、tag、长度和 hash。
     */
    public WriteResult verify(
            Path encryptedPath,
            byte[] fileDek,
            byte[] fileNonce,
            int chunkIndex,
            int chunkCount,
            int framePlainSize
    ) throws IOException {
        if (encryptedPath == null || !Files.isRegularFile(encryptedPath)) {
            throw new IOException("framed encrypted chunk does not exist");
        }
        validateKeys(fileDek, fileNonce, framePlainSize);
        MessageDigest plainDigest = sha256();
        MessageDigest cipherDigest = sha256();
        try (InputStream input = Files.newInputStream(encryptedPath)) {
            byte[] header = readExact(input, FramedAeadCrypto.CHUNK_HEADER_SIZE);
            cipherDigest.update(header);
            FramedAeadCrypto.ChunkHeader parsed = FramedAeadCrypto.parseChunkHeader(header);
            if (parsed.chunkIndex() != chunkIndex
                    || parsed.chunkCount() != chunkCount
                    || parsed.framePlainSize() != framePlainSize
                    || !FramedAeadCrypto.constantTimeEquals(parsed.fileNonce(), fileNonce)) {
                throw new IOException("framed chunk header does not match upload state");
            }
            long plainSize = parsed.chunkPlainSize();
            int expectedFrameCount = FramedAeadCrypto.calculateFrameCount(plainSize, framePlainSize);
            if (parsed.frameCount() != expectedFrameCount) {
                throw new IOException("framed frame count does not match plaintext size");
            }
            long plainBytes = 0;
            for (int frameIndex = 0; frameIndex < parsed.frameCount(); frameIndex++) {
                byte[] frameHeader = readExact(input, FramedAeadCrypto.FRAME_HEADER_SIZE);
                cipherDigest.update(frameHeader);
                FramedAeadCrypto.FrameHeader frame = FramedAeadCrypto.parseFrameHeader(frameHeader);
                if (frame.frameIndex() != frameIndex
                        || frame.plainLength() > framePlainSize
                        || (frameIndex < parsed.frameCount() - 1 && frame.plainLength() != framePlainSize)
                        || (frameIndex == parsed.frameCount() - 1 && frame.plainLength() <= 0)) {
                    throw new IOException("framed frame coordinate or length is invalid");
                }
                if (frame.cipherLength() > framePlainSize + FramedAeadCrypto.TAG_SIZE) {
                    throw new IOException("framed ciphertext exceeds configured bound");
                }
                byte[] ciphertext = readExact(input, frame.cipherLength());
                cipherDigest.update(ciphertext);
                byte[] plaintext = decryptFrame(
                        ciphertext, fileDek, parsed.fileNonce(), parsed.chunkIndex(), parsed.chunkCount(),
                        frameIndex, parsed.frameCount(), frame.plainLength(), plainSize);
                plainDigest.update(plaintext);
                plainBytes = Math.addExact(plainBytes, plaintext.length);
            }
            if (plainBytes != plainSize || input.read() != -1) {
                throw new IOException("framed object has truncated or trailing bytes");
            }
        } catch (SecurityException e) {
            throw new IOException("framed frame authentication failed", e);
        }
        return new WriteResult(
                Files.size(encryptedPath) == 0 ? 0 : readPlainSize(encryptedPath),
                Files.size(encryptedPath),
                countFrames(encryptedPath),
                HASH_PREFIX + HexFormat.of().formatHex(plainDigest.digest()),
                HASH_PREFIX + HexFormat.of().formatHex(cipherDigest.digest()));
    }

    /**
     * 将二进制文件级 nonce 编码为 manifest 使用的 Base64URL 无填充文本。
     */
    public String encodeFileNonce(byte[] fileNonce) {
        if (fileNonce == null || fileNonce.length != FramedAeadCrypto.FILE_NONCE_SIZE) {
            throw new IllegalArgumentException("file nonce length is invalid");
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(fileNonce);
    }

    private byte[] encryptFrame(
            byte[] plain,
            int plainLength,
            byte[] fileDek,
            byte[] fileNonce,
            int chunkIndex,
            int chunkCount,
            int frameIndex,
            int frameCount,
            long chunkPlainSize
    ) throws IOException {
        try {
            byte[] nonce = FramedAeadCrypto.deriveFrameNonce(fileDek, fileNonce, chunkIndex, frameIndex);
            byte[] key = FramedAeadCrypto.deriveFrameKey(fileDek, fileNonce, chunkIndex, frameIndex);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(FramedAeadCrypto.buildAad(
                    fileNonce, chunkIndex, chunkCount, frameIndex, frameCount, plainLength, chunkPlainSize));
            return cipher.doFinal(plain, 0, plainLength);
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("framed AES-GCM encryption failed", e);
        }
    }

    private byte[] decryptFrame(
            byte[] ciphertext,
            byte[] fileDek,
            byte[] fileNonce,
            int chunkIndex,
            int chunkCount,
            int frameIndex,
            int frameCount,
            int plainLength,
            long chunkPlainSize
    ) throws IOException {
        try {
            byte[] nonce = FramedAeadCrypto.deriveFrameNonce(fileDek, fileNonce, chunkIndex, frameIndex);
            byte[] key = FramedAeadCrypto.deriveFrameKey(fileDek, fileNonce, chunkIndex, frameIndex);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(FramedAeadCrypto.buildAad(
                    fileNonce, chunkIndex, chunkCount, frameIndex, frameCount, plainLength, chunkPlainSize));
            return cipher.doFinal(ciphertext);
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("framed frame authentication failed", e);
        }
    }

    private void validatePathsAndKeys(
            Path plainPath,
            Path encryptedPath,
            byte[] fileDek,
            byte[] fileNonce,
            int framePlainSize
    ) throws IOException {
        if (plainPath == null || !Files.isRegularFile(plainPath)) {
            throw new IOException("framed plaintext chunk does not exist");
        }
        validateKeys(fileDek, fileNonce, framePlainSize);
    }

    private void validateKeys(byte[] fileDek, byte[] fileNonce, int framePlainSize) {
        if (fileDek == null || fileDek.length != FramedAeadCrypto.FILE_DEK_SIZE
                || fileNonce == null || fileNonce.length != FramedAeadCrypto.FILE_NONCE_SIZE
                || framePlainSize < 64 * 1024 || framePlainSize > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("framed AEAD state is invalid");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void readFully(InputStream input, byte[] target, int length) throws IOException {
        int offset = 0;
        while (offset < length) {
            int read = input.read(target, offset, length - offset);
            if (read < 0) {
                throw new EOFException("framed plaintext ended unexpectedly");
            }
            offset += read;
        }
    }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] value = new byte[length];
        readFully(input, value, length);
        return value;
    }

    private static long readPlainSize(Path encryptedPath) throws IOException {
        byte[] header;
        try (InputStream input = Files.newInputStream(encryptedPath)) {
            header = readExact(input, FramedAeadCrypto.CHUNK_HEADER_SIZE);
        }
        return FramedAeadCrypto.parseChunkHeader(header).chunkPlainSize();
    }

    private static int countFrames(Path encryptedPath) throws IOException {
        // verify() 已完成完整解析；这里只返回 header 中的声明数量，避免再次分配对象。
        try (InputStream input = Files.newInputStream(encryptedPath)) {
            return FramedAeadCrypto.parseChunkHeader(
                    readExact(input, FramedAeadCrypto.CHUNK_HEADER_SIZE)).frameCount();
        }
    }

    /**
     * v2 writer/validator 输出的完整 hash 和尺寸证据。
     */
    public record WriteResult(
            long plainSize,
            long cipherSize,
            int frameCount,
            String plainHash,
            String cipherHash
    ) {
    }
}
