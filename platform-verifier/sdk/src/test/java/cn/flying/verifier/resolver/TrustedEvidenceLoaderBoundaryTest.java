package cn.flying.verifier.resolver;

import cn.flying.verifier.VerifierTestFixture;
import cn.flying.verifier.crypto.CanonicalJson;
import cn.flying.verifier.crypto.ProofHashes;
import cn.flying.verifier.model.PublicSigningKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boundary tests for trusted-key byte limits, source sanitization, and Ed25519 validation.
 */
class TrustedEvidenceLoaderBoundaryTest {

    @TempDir
    Path directory;

    private PublicSigningKey validKey;
    private byte[] validJson;

    /** Creates one deterministic valid trust anchor for focused field mutations. */
    @BeforeEach
    void setUp() throws Exception {
        validKey = new VerifierTestFixture().create(directory).key();
        validJson = new CanonicalJson().canonicalBytes(validKey);
    }

    /** Rejects null, empty, oversized, empty-file, and oversized-file trust documents. */
    @Test
    void shouldRejectEveryTrustDocumentSizeBoundary() throws Exception {
        assertThatThrownBy(() -> TrustedEvidenceLoader.loadSigningKey((Path) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrustedEvidenceLoader.loadSigningKey((byte[]) null, "test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrustedEvidenceLoader.loadSigningKey(new byte[0], "test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrustedEvidenceLoader.loadSigningKey(
                new byte[TrustedEvidenceLoader.MAX_TRUST_FILE_BYTES + 1], "test"))
                .isInstanceOf(IllegalArgumentException.class);

        Path empty = directory.resolve("empty-key.json");
        Files.write(empty, new byte[0]);
        assertThatThrownBy(() -> TrustedEvidenceLoader.loadSigningKey(empty))
                .isInstanceOf(IllegalArgumentException.class);

        Path oversized = directory.resolve("oversized-key.json");
        Files.write(oversized, new byte[TrustedEvidenceLoader.MAX_TRUST_FILE_BYTES + 1]);
        assertThatThrownBy(() -> TrustedEvidenceLoader.loadSigningKey(oversized))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Applies the default source, removes controls, trims whitespace, and caps labels at 128 characters. */
    @Test
    void shouldSanitizeEveryCallerSourceVariant() {
        assertThat(TrustedEvidenceLoader.loadSigningKey(validJson, null).source())
                .isEqualTo("trusted-by-caller");
        assertThat(TrustedEvidenceLoader.loadSigningKey(validJson, "   ").source())
                .isEqualTo("trusted-by-caller");
        assertThat(TrustedEvidenceLoader.loadSigningKey(validJson, "\u0000\u0001").source())
                .isEqualTo("trusted-by-caller");
        assertThat(TrustedEvidenceLoader.loadSigningKey(validJson, "  source\nlabel  ").source())
                .isEqualTo("source label");
        assertThat(TrustedEvidenceLoader.loadSigningKey(validJson, "x".repeat(200)).source())
                .hasSize(128);
    }

    /** Rejects every invalid identity, algorithm, fingerprint, and encoded-key field independently. */
    @Test
    void shouldRejectEveryInvalidTrustedKeyField() {
        assertInvalidKey(null);
        assertInvalidKey(key("bad/key", validKey.keyVersion(), validKey.algorithm(),
                validKey.publicKeySpki(), validKey.publicKeyFingerprint()));
        assertInvalidKey(key(validKey.keyId(), null, validKey.algorithm(),
                validKey.publicKeySpki(), validKey.publicKeyFingerprint()));
        assertInvalidKey(key(validKey.keyId(), 0, validKey.algorithm(),
                validKey.publicKeySpki(), validKey.publicKeyFingerprint()));
        assertInvalidKey(key(validKey.keyId(), validKey.keyVersion(), "RS256",
                validKey.publicKeySpki(), validKey.publicKeyFingerprint()));
        assertInvalidKey(key(validKey.keyId(), validKey.keyVersion(), validKey.algorithm(),
                validKey.publicKeySpki(), "raw-digest"));
        assertInvalidKey(key(validKey.keyId(), validKey.keyVersion(), validKey.algorithm(),
                null, validKey.publicKeyFingerprint()));
        assertInvalidKey(key(validKey.keyId(), validKey.keyVersion(), validKey.algorithm(),
                "A".repeat(2049), validKey.publicKeyFingerprint()));

        assertInvalidEncodedKey(new byte[0]);
        assertInvalidEncodedKey(new byte[513]);
        assertInvalidEncodedKey(new byte[]{1, 2, 3});
        assertInvalidKey(key(validKey.keyId(), validKey.keyVersion(), validKey.algorithm(),
                "%%%", validKey.publicKeyFingerprint()));
    }

    /** Covers null and both independent mismatch branches of the exact-identity resolver. */
    @Test
    void shouldResolveOnlyTheExactNonNullTrustedIdentity() {
        assertThat(TrustedEvidenceLoader.resolver(null).resolve(validKey.keyId(), validKey.keyVersion()).state())
                .isEqualTo(ResolutionState.NOT_FOUND);
        assertThat(TrustedEvidenceLoader.resolver(validKey)
                .resolve(validKey.keyId(), validKey.keyVersion() + 1).state())
                .isEqualTo(ResolutionState.NOT_FOUND);
        assertThat(TrustedEvidenceLoader.resolver(validKey)
                .resolve("different-key", validKey.keyVersion()).state())
                .isEqualTo(ResolutionState.NOT_FOUND);
        assertThat(TrustedEvidenceLoader.resolver(validKey)
                .resolve(validKey.keyId(), validKey.keyVersion()).state())
                .isEqualTo(ResolutionState.RESOLVED);
    }

    /** Creates a mutated key while retaining a bounded source label. */
    private PublicSigningKey key(
            String keyId,
            Integer keyVersion,
            String algorithm,
            String publicKeySpki,
            String fingerprint
    ) {
        return new PublicSigningKey(keyId, keyVersion, algorithm, publicKeySpki, fingerprint, "boundary-test");
    }

    /** Requires a selected trust-anchor mutation to fail strict validation. */
    private void assertInvalidKey(PublicSigningKey key) {
        assertThatThrownBy(() -> TrustedEvidenceLoader.validateSigningKey(key))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Encodes bytes with a self-consistent fingerprint so structural SPKI validation is reached. */
    private void assertInvalidEncodedKey(byte[] bytes) {
        PublicSigningKey key = key(
                validKey.keyId(),
                validKey.keyVersion(),
                validKey.algorithm(),
                Base64.getEncoder().encodeToString(bytes),
                ProofHashes.sha256(bytes));
        assertInvalidKey(key);
    }
}
