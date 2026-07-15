package cn.flying.verifier.resolver;

import cn.flying.verifier.VerifierTestFixture;
import cn.flying.verifier.crypto.CanonicalJson;
import cn.flying.verifier.model.PublicSigningKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Local trust-anchor tests for bounded bytes, regular files, symbolic links, and exact identity matching.
 */
class TrustedEvidenceLoaderTest {

    @TempDir
    Path directory;

    private VerifierTestFixture.Fixture fixture;
    private byte[] keyJson;

    /** Creates one valid public-key trust document. */
    @BeforeEach
    void setUp() throws Exception {
        fixture = new VerifierTestFixture().create(directory);
        keyJson = new CanonicalJson().canonicalBytes(fixture.key());
    }

    /** Loads both byte and file inputs while replacing caller-controlled source labels. */
    @Test
    void shouldLoadBoundedTrustedKeyAndResolveExactIdentity() throws Exception {
        Path keyFile = directory.resolve("key.json");
        Files.write(keyFile, keyJson);

        PublicSigningKey fromBytes = TrustedEvidenceLoader.loadSigningKey(keyJson, "caller-source");
        PublicSigningKey fromFile = TrustedEvidenceLoader.loadSigningKey(keyFile);
        SigningKeyResolver resolver = TrustedEvidenceLoader.resolver(fromFile);

        assertThat(fromBytes.source()).isEqualTo("caller-source");
        assertThat(fromFile.source()).isEqualTo("trusted-local-file");
        assertThat(resolver.resolve(fixture.key().keyId(), fixture.key().keyVersion()).state())
                .isEqualTo(ResolutionState.RESOLVED);
        assertThat(resolver.resolve(fixture.key().keyId(), fixture.key().keyVersion() + 1).state())
                .isEqualTo(ResolutionState.NOT_FOUND);
    }

    /** Rejects absent, oversized, malformed, and symbolic-link trust files. */
    @Test
    void shouldRejectUnsafeTrustInputs() throws Exception {
        assertThatThrownBy(() -> TrustedEvidenceLoader.loadSigningKey(new byte[0], "test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrustedEvidenceLoader.loadSigningKey(
                new byte[TrustedEvidenceLoader.MAX_TRUST_FILE_BYTES + 1], "test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrustedEvidenceLoader.loadSigningKey(
                "{".getBytes(java.nio.charset.StandardCharsets.UTF_8), "test"))
                .isInstanceOf(IllegalArgumentException.class);
        PublicSigningKey fingerprintMismatch = new PublicSigningKey(
                fixture.key().keyId(),
                fixture.key().keyVersion(),
                fixture.key().algorithm(),
                fixture.key().publicKeySpki(),
                "sha256:" + "0".repeat(64),
                "test");
        assertThatThrownBy(() -> TrustedEvidenceLoader.loadSigningKey(
                new CanonicalJson().canonicalBytes(fingerprintMismatch), "test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrustedEvidenceLoader.loadSigningKey(directory.resolve("missing.json")))
                .isInstanceOf(IllegalArgumentException.class);

        Path real = directory.resolve("real-key.json");
        Path symbolic = directory.resolve("linked-key.json");
        Files.write(real, keyJson);
        Files.createSymbolicLink(symbolic, real.getFileName());
        assertThatThrownBy(() -> TrustedEvidenceLoader.loadSigningKey(symbolic))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Handles a malformed trust model with a null boxed version without unboxing failure. */
    @Test
    void shouldNotUnboxNullTrustedKeyVersion() {
        PublicSigningKey key = new PublicSigningKey(
                fixture.key().keyId(),
                null,
                fixture.key().algorithm(),
                fixture.key().publicKeySpki(),
                fixture.key().publicKeyFingerprint(),
                "test");

        assertThat(TrustedEvidenceLoader.resolver(key).resolve(key.keyId(), 1).state())
                .isEqualTo(ResolutionState.NOT_FOUND);
    }
}
