package cn.flying.test.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class FileTestUtils {

    private static final int DEFAULT_SEED = 12345;
    private static final int DEFAULT_CHUNK_SIZE = 256 * 1024;

    public static byte[] generateTestFileContent(int sizeInKB) {
        return generateTestFileContent(sizeInKB, DEFAULT_SEED);
    }

    public static byte[] generateTestFileContent(int sizeInKB, int seed) {
        Random random = new Random(seed);
        byte[] content = new byte[sizeInKB * 1024];
        random.nextBytes(content);
        return content;
    }

    public static byte[] generateEmptyContent() {
        return new byte[0];
    }

    public static byte[] generateFixedContent(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] generatePatternContent(int sizeInKB, byte pattern) {
        byte[] content = new byte[sizeInKB * 1024];
        for (int i = 0; i < content.length; i++) {
            content[i] = pattern;
        }
        return content;
    }

    public static String calculateSHA256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    public static String calculateMD5(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(content);
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static List<byte[]> splitIntoChunks(byte[] content, int chunkSizeBytes) {
        List<byte[]> chunks = new ArrayList<>();
        int offset = 0;
        while (offset < content.length) {
            int length = Math.min(chunkSizeBytes, content.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(content, offset, chunk, 0, length);
            chunks.add(chunk);
            offset += length;
        }
        return chunks;
    }

    public static List<byte[]> splitIntoChunks(byte[] content) {
        return splitIntoChunks(content, DEFAULT_CHUNK_SIZE);
    }

    public static byte[] mergeChunks(List<byte[]> chunks) {
        int totalSize = chunks.stream().mapToInt(c -> c.length).sum();
        byte[] merged = new byte[totalSize];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, merged, offset, chunk.length);
            offset += chunk.length;
        }
        return merged;
    }

    public static int calculateChunkCount(long fileSizeBytes, int chunkSizeBytes) {
        return (int) Math.ceil((double) fileSizeBytes / chunkSizeBytes);
    }

    public static int calculateChunkCount(long fileSizeBytes) {
        return calculateChunkCount(fileSizeBytes, DEFAULT_CHUNK_SIZE);
    }

    public static byte[] generateChunkForIndex(byte[] fullContent, int chunkIndex, int chunkSizeBytes) {
        int offset = chunkIndex * chunkSizeBytes;
        if (offset >= fullContent.length) {
            throw new IndexOutOfBoundsException("Chunk index " + chunkIndex + " is out of bounds");
        }
        int length = Math.min(chunkSizeBytes, fullContent.length - offset);
        byte[] chunk = new byte[length];
        System.arraycopy(fullContent, offset, chunk, 0, length);
        return chunk;
    }

    public static byte[] generateSmallFile() {
        return generateFixedContent("Small test file content for unit testing");
    }

    public static byte[] generateMediumFile() {
        return generateTestFileContent(100);
    }

    public static byte[] generateLargeFile() {
        return generateTestFileContent(1024);
    }

    public static boolean verifyHash(byte[] content, String expectedHash) {
        return calculateSHA256(content).equalsIgnoreCase(expectedHash);
    }

    public static String generateTestFileName(String extension) {
        return "test_" + System.currentTimeMillis() + "." + extension;
    }

    public static String generateTestFileName() {
        return generateTestFileName("txt");
    }

    public static byte[] corruptContent(byte[] content) {
        if (content.length == 0) {
            return content;
        }
        byte[] corrupted = content.clone();
        corrupted[0] = (byte) (corrupted[0] ^ 0xFF);
        return corrupted;
    }

    public static byte[] truncateContent(byte[] content, int newLength) {
        if (newLength >= content.length) {
            return content.clone();
        }
        byte[] truncated = new byte[newLength];
        System.arraycopy(content, 0, truncated, 0, newLength);
        return truncated;
    }
}
