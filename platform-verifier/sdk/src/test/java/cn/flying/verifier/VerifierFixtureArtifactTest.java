package cn.flying.verifier;

import cn.flying.verifier.crypto.CanonicalJson;
import cn.flying.verifier.crypto.ProofHashes;
import cn.flying.verifier.model.VerificationOutcome;
import cn.flying.verifier.model.VerificationReport;
import cn.flying.verifier.resolver.Resolution;
import cn.flying.verifier.resolver.TrustedEvidenceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Materializes reproducible verifier examples as CI artifacts and proves byte stability.
 */
class VerifierFixtureArtifactTest {

    @TempDir
    Path comparisonDirectory;

    /** Writes the public fixture set and verifies that a second materialization is byte-identical. */
    @Test
    void shouldMaterializeReproduciblePublicVerifierFixture() throws Exception {
        Path output = Path.of(System.getProperty("basedir"), "target", "verifier-fixtures");
        VerifierTestFixture builder = new VerifierTestFixture();
        VerifierTestFixture.Fixture fixture = builder.create(output);
        VerifierTestFixture.Fixture comparison = builder.create(comparisonDirectory);
        CanonicalJson json = new CanonicalJson();

        Files.write(output.resolve("trusted-key.json"), json.canonicalBytes(fixture.key()));
        VerificationContext offlineTrustedContext = new VerificationContext(
                VerificationLimits.defaults(),
                TrustedEvidenceLoader.resolver(TrustedEvidenceLoader.loadSigningKey(
                        output.resolve("trusted-key.json"))),
                proofId -> Resolution.unavailable("Current proof status resolution is disabled"),
                query -> Resolution.unavailable("Live chain root resolution is disabled"),
                Clock.fixed(VerifierTestFixture.NOW, ZoneOffset.UTC));
        VerificationReport report = new DefaultProofVerifier().verify(
                fixture.original(), fixture.proof(), offlineTrustedContext);
        Files.write(output.resolve("expected-offline-report.json"), json.canonicalBytes(report));
        Files.writeString(
                output.resolve("SHA256SUMS"),
                rawSha256(Files.readAllBytes(fixture.original())) + "  original.txt\n"
                        + rawSha256(Files.readAllBytes(fixture.proof())) + "  proof.zip\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                output.resolve("README.txt"),
                "Run: java -jar record-platform-verifier-exec.jar verify "
                        + "--file original.txt --proof proof.zip --trusted-key trusted-key.json\n"
                        + "Local trust passes, but offline status/chain resolution remains INDETERMINATE.\n"
                        + "expected-offline-report.json contains that result with a fixed example verifiedAt; "
                        + "a real CLI run uses its current UTC time.\n",
                StandardCharsets.UTF_8);

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.INDETERMINATE);
        assertThat(Files.readAllBytes(comparison.original()))
                .isEqualTo(Files.readAllBytes(fixture.original()));
        assertThat(Files.readAllBytes(comparison.proof()))
                .isEqualTo(Files.readAllBytes(fixture.proof()));
        assertThat(output.resolve("trusted-key.json")).isRegularFile();
        assertThat(output.resolve("expected-offline-report.json")).isRegularFile();
        assertThat(Files.readString(output.resolve("SHA256SUMS"), StandardCharsets.UTF_8))
                .matches("[0-9a-f]{64}  original\\.txt\\n[0-9a-f]{64}  proof\\.zip\\n");
    }

    /** Returns standard sha256sum-compatible lowercase hexadecimal text without an algorithm prefix. */
    private String rawSha256(byte[] value) {
        return ProofHashes.sha256(value).substring(ProofHashes.HASH_PREFIX.length());
    }
}
