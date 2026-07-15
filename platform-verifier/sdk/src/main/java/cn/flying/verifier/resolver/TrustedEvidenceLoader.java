package cn.flying.verifier.resolver;

import cn.flying.verifier.crypto.CanonicalJson;
import cn.flying.verifier.crypto.ProofHashes;
import cn.flying.verifier.model.PublicSigningKey;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict bounded loader for caller/operator-supplied public trust anchors.
 */
public final class TrustedEvidenceLoader {

    public static final int MAX_TRUST_FILE_BYTES = 256 * 1024;
    private static final Pattern KEY_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private TrustedEvidenceLoader() {
    }

    /**
     * Loads a trusted public signing key from one regular JSON file.
     *
     * @param path trusted key JSON path
     * @return parsed key with a local-file source label
     */
    public static PublicSigningKey loadSigningKey(Path path) {
        byte[] bytes = readTrustFile(path);
        PublicSigningKey parsed = validateSigningKey(
                new CanonicalJson().read(bytes, PublicSigningKey.class));
        return new PublicSigningKey(
                parsed.keyId(),
                parsed.keyVersion(),
                parsed.algorithm(),
                parsed.publicKeySpki(),
                parsed.publicKeyFingerprint(),
                "trusted-local-file");
    }

    /**
     * Loads a trusted public signing key from bounded multipart or embedded JSON bytes.
     *
     * @param bytes trusted key JSON bytes
     * @param source safe source label
     * @return parsed key
     */
    public static PublicSigningKey loadSigningKey(byte[] bytes, String source) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_TRUST_FILE_BYTES) {
            throw new IllegalArgumentException("Trusted key JSON size is invalid");
        }
        PublicSigningKey parsed = validateSigningKey(
                new CanonicalJson().read(bytes, PublicSigningKey.class));
        String safeSource = source == null || source.isBlank() ? "trusted-by-caller" : source.trim();
        safeSource = sanitizeSource(safeSource);
        if (safeSource.isBlank()) {
            safeSource = "trusted-by-caller";
        }
        return new PublicSigningKey(
                parsed.keyId(),
                parsed.keyVersion(),
                parsed.algorithm(),
                parsed.publicKeySpki(),
                parsed.publicKeyFingerprint(),
                safeSource);
    }

    /** Creates a resolver that serves exactly one explicitly trusted key identity. */
    public static SigningKeyResolver resolver(PublicSigningKey key) {
        return (keyId, keyVersion) -> key != null
                && java.util.Objects.equals(Integer.valueOf(keyVersion), key.keyVersion())
                && java.util.Objects.equals(keyId, key.keyId())
                ? Resolution.resolved(key)
                : Resolution.notFound("Trusted key file does not contain the requested key identity");
    }

    /**
     * Validates an Ed25519 trust anchor returned by any SDK resolver before cryptographic use.
     *
     * @param key caller- or transport-supplied public key
     * @return the validated key
     */
    public static PublicSigningKey validateSigningKey(PublicSigningKey key) {
        if (key == null
                || !KEY_ID.matcher(java.util.Objects.toString(key.keyId(), "")).matches()
                || key.keyVersion() == null
                || key.keyVersion() <= 0
                || !"EdDSA".equals(key.algorithm())
                || !ProofHashes.PREFIXED_SHA256.matcher(
                java.util.Objects.toString(key.publicKeyFingerprint(), "")).matches()
                || key.publicKeySpki() == null
                || key.publicKeySpki().length() > 2048) {
            throw new IllegalArgumentException("Trusted key identity or algorithm is invalid");
        }
        try {
            byte[] spki = Base64.getDecoder().decode(key.publicKeySpki());
            if (spki.length == 0
                    || spki.length > 512
                    || !ProofHashes.equalsSha256(ProofHashes.sha256(spki), key.publicKeyFingerprint())) {
                throw new IllegalArgumentException("Trusted key fingerprint is invalid");
            }
            KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
            return key;
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IllegalArgumentException("Trusted key SPKI is invalid", e);
        }
    }

    /** Removes control characters and bounds a caller-supplied report source label. */
    private static String sanitizeSource(String value) {
        StringBuilder safe = new StringBuilder(Math.min(value.length(), 128));
        for (int index = 0; index < value.length() && safe.length() < 128; index++) {
            char character = value.charAt(index);
            safe.append(Character.isISOControl(character) ? ' ' : character);
        }
        return safe.toString().trim();
    }

    /** Reads one regular non-symlink trust file within the public trust-file size limit. */
    private static byte[] readTrustFile(Path path) {
        if (path == null
                || Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Trusted key must be a regular non-symbolic-link file");
        }
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = Files.newByteChannel(path, options);
             InputStream input = Channels.newInputStream(channel)) {
            long size = channel.size();
            if (size <= 0 || size > MAX_TRUST_FILE_BYTES) {
                throw new IllegalArgumentException("Trusted key JSON size is invalid");
            }
            byte[] bytes = input.readNBytes(Math.toIntExact(size) + 1);
            if (bytes.length != size || input.read() != -1) {
                throw new IllegalArgumentException("Trusted key JSON size changed while being read");
            }
            return bytes;
        } catch (IOException e) {
            throw new IllegalArgumentException("Trusted key JSON cannot be read", e);
        }
    }
}
