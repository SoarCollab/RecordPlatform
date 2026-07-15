package cn.flying.verifier.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared SHA-256 formatting and comparison helpers for proof production and verification.
 */
public final class ProofHashes {

    public static final String HASH_ALGORITHM = "SHA-256";
    public static final String HASH_PREFIX = "sha256:";
    public static final Pattern PREFIXED_SHA256 = Pattern.compile("^sha256:[0-9a-f]{64}$");
    public static final Pattern RAW_SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private ProofHashes() {
    }

    /**
     * Creates a new SHA-256 digest instance.
     *
     * @return SHA-256 digest
     */
    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM does not support SHA-256", e);
        }
    }

    /**
     * Computes a prefixed lowercase SHA-256 digest.
     *
     * @param value input bytes
     * @return {@code sha256:<hex>}
     */
    public static String sha256(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("SHA-256 input is required");
        }
        return HASH_PREFIX + HexFormat.of().formatHex(newDigest().digest(value));
    }

    /**
     * Computes a prefixed SHA-256 digest for UTF-8 text.
     *
     * @param value input text
     * @return {@code sha256:<hex>}
     */
    public static String sha256(String value) {
        if (value == null) {
            throw new IllegalArgumentException("SHA-256 input is required");
        }
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Formats a completed digest as prefixed lowercase SHA-256.
     *
     * @param digest completed digest bytes
     * @return {@code sha256:<hex>}
     */
    public static String formatDigest(byte[] digest) {
        if (digest == null || digest.length != 32) {
            throw new IllegalArgumentException("SHA-256 digest must contain exactly 32 bytes");
        }
        return HASH_PREFIX + HexFormat.of().formatHex(digest);
    }

    /**
     * Normalizes a raw or prefixed SHA-256 value to prefixed lowercase form.
     *
     * @param value digest text
     * @return normalized digest, or {@code null} when malformed
     */
    public static String normalizeSha256(String value) {
        if (value == null || value.length() > 256) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (RAW_SHA256.matcher(normalized).matches()) {
            return HASH_PREFIX + normalized;
        }
        return PREFIXED_SHA256.matcher(normalized).matches() ? normalized : null;
    }

    /**
     * Compares normalized SHA-256 strings without early byte mismatch disclosure.
     *
     * @param left first hash
     * @param right second hash
     * @return true when both valid normalized hashes are equal
     */
    public static boolean equalsSha256(String left, String right) {
        String normalizedLeft = normalizeSha256(left);
        String normalizedRight = normalizeSha256(right);
        if (normalizedLeft == null || normalizedRight == null) {
            return false;
        }
        return MessageDigest.isEqual(
                normalizedLeft.getBytes(StandardCharsets.US_ASCII),
                normalizedRight.getBytes(StandardCharsets.US_ASCII));
    }
}
