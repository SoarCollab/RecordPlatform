package cn.flying.verifier.internal;

import cn.flying.verifier.VerificationContext;
import cn.flying.verifier.VerificationLimits;
import cn.flying.verifier.contract.SignedProofBundleContract;
import cn.flying.verifier.contract.SignedProofBundleModel;
import cn.flying.verifier.model.ChainQuery;
import cn.flying.verifier.model.VerificationCheck;
import cn.flying.verifier.model.VerificationCheckStatus;
import cn.flying.verifier.model.VerificationCode;
import cn.flying.verifier.model.VerificationOutcome;
import cn.flying.verifier.model.VerificationReport;
import cn.flying.verifier.resolver.ResolutionState;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies small SDK value objects and the ordered verification-result accumulator. */
class CoreVerifierContractsTest {

    /** Keeps parsed archive bytes isolated from caller mutations and null entry payloads. */
    @Test
    void shouldSnapshotParsedArchiveBytes() {
        byte[] manifest = {1, 2, 3};
        LinkedHashMap<String, byte[]> source = new LinkedHashMap<>();
        source.put("manifest.json", manifest);
        source.put("empty.txt", null);

        ParsedProofArchive archive = new ParsedProofArchive(source);
        manifest[0] = 9;
        source.clear();
        byte[] firstRead = archive.required("manifest.json");
        firstRead[1] = 9;
        archive.entries().get("manifest.json")[2] = 9;

        assertThat(archive.required("manifest.json")).containsExactly(1, 2, 3);
        assertThat(archive.required("empty.txt")).isEmpty();
        assertThatThrownBy(() -> archive.entries().put("extra.txt", new byte[0]))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(new ParsedProofArchive(null).entries()).isEmpty();
    }

    /** Reports a stable format error when a required archive entry is absent. */
    @Test
    void shouldRejectMissingRequiredArchiveEntry() {
        ParsedProofArchive archive = new ParsedProofArchive(Map.of());

        ProofFormatException error = assertThrows(
                ProofFormatException.class, () -> archive.required("manifest.json"));

        assertThat(error.code()).isEqualTo(VerificationCode.ARCHIVE_ENTRY_INVALID);
        assertThat(error).hasMessage("Required proof archive entry is missing: manifest.json");
    }

    /** Executes every offline and null-resolver fallback without inventing a trust result. */
    @Test
    void shouldProvideFailClosedVerificationContexts() {
        VerificationContext offline = VerificationContext.offline();
        ChainQuery query = new ChainQuery("chain", "group", "contract", "tx", "record", "root", "batch");

        assertThat(offline.signingKeyResolver().resolve("key", 1).state())
                .isEqualTo(ResolutionState.UNAVAILABLE);
        assertThat(offline.proofStatusResolver().resolve("proof").message())
                .isEqualTo("Proof status resolution is disabled");
        assertThat(offline.chainRootResolver().resolve(query).message())
                .isEqualTo("Live chain resolution is disabled");

        VerificationContext fallback = new VerificationContext(null, null, null, null, null);
        assertThat(fallback.limits()).isEqualTo(VerificationLimits.defaults());
        assertThat(fallback.clock().getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(fallback.signingKeyResolver().resolve("key", 1).message())
                .isEqualTo("Signing key resolver is unavailable");
        assertThat(fallback.proofStatusResolver().resolve("proof").message())
                .isEqualTo("Proof status resolver is unavailable");
        assertThat(fallback.chainRootResolver().resolve(query).message())
                .isEqualTo("Live chain resolver is unavailable");
    }

    /** Bounds report evidence, snapshots checks, and preserves deterministic outcome precedence. */
    @Test
    void shouldNormalizeAccumulatedChecksAndApplyOutcomePrecedence() {
        LinkedHashMap<String, String> evidence = new LinkedHashMap<>();
        evidence.put(null, "discarded");
        evidence.put("discarded-null-value", null);
        evidence.put(" control\u0001key ", " value\u0002safe ");
        evidence.put("k".repeat(80), "v".repeat(600));
        for (int index = 0; index < 14; index++) {
            evidence.put("evidence-" + index, "value-" + index);
        }

        VerificationAccumulator accumulator = new VerificationAccumulator();
        accumulator.fail(null, null, VerificationCode.MANIFEST_INVALID, null, evidence);
        List<VerificationCheck> snapshot = accumulator.checks();
        VerificationCheck failure = snapshot.getFirst();

        assertThat(failure.status()).isEqualTo(VerificationCheckStatus.FAIL);
        assertThat(failure.id()).isNull();
        assertThat(failure.category()).isNull();
        assertThat(failure.message()).isNull();
        assertThat(failure.evidence()).hasSize(14)
                .containsEntry("control key", "value safe")
                .doesNotContainKeys("evidence-12", "evidence-13");
        assertThat(failure.evidence().keySet()).anySatisfy(key -> assertThat(key).hasSize(64));
        assertThat(failure.evidence().values()).anySatisfy(value -> assertThat(value).hasSize(512));
        assertThat(accumulator.outcome()).isEqualTo(VerificationOutcome.INVALID);
        assertThatThrownBy(() -> snapshot.add(failure))
                .isInstanceOf(UnsupportedOperationException.class);

        accumulator.indeterminate("status", "trust", VerificationCode.STATUS_UNAVAILABLE, "offline");
        assertThat(snapshot).hasSize(1);
        assertThat(accumulator.outcome()).isEqualTo(VerificationOutcome.INVALID);
        accumulator.error("archive", "input", VerificationCode.ARCHIVE_MALFORMED, "malformed");
        assertThat(accumulator.outcome()).isEqualTo(VerificationOutcome.ERROR);

        VerificationAccumulator passing = new VerificationAccumulator();
        passing.pass("archive", "input", VerificationCode.ARCHIVE_VALID, "valid");
        passing.pass("archive-details", "input", VerificationCode.ARCHIVE_VALID, "valid", null);
        assertThat(passing.outcome()).isEqualTo(VerificationOutcome.VALID);
        assertThat(passing.checks()).allSatisfy(check -> assertThat(check.evidence()).isEmpty());
    }

    /** Rejects every independently invalid SDK resource-limit dimension. */
    @Test
    void shouldRejectEveryInvalidVerificationLimitDimension() {
        long archive = SignedProofBundleContract.MAX_ARCHIVE_BYTES;
        int entry = SignedProofBundleContract.MAX_ENTRY_BYTES;
        int total = SignedProofBundleContract.MAX_TOTAL_ENTRY_BYTES;
        int chunks = SignedProofBundleContract.MAX_CHUNKS;
        int nodes = SignedProofBundleContract.MAX_PROOF_NODES;
        List<Supplier<VerificationLimits>> invalidLimits = List.of(
                () -> new VerificationLimits(1, 0, entry, total, chunks, nodes),
                () -> new VerificationLimits(1, archive, 0, total, chunks, nodes),
                () -> new VerificationLimits(1, archive, entry, 0, chunks, nodes),
                () -> new VerificationLimits(1, archive, entry, total, 0, nodes),
                () -> new VerificationLimits(1, archive, entry, total, chunks, 0),
                () -> new VerificationLimits(1, archive, entry + 1, total, chunks, nodes),
                () -> new VerificationLimits(1, archive, entry, total + 1, chunks, nodes),
                () -> new VerificationLimits(1, archive, entry, total, chunks + 1, nodes),
                () -> new VerificationLimits(1, archive, entry, total, chunks, nodes + 1));

        for (Supplier<VerificationLimits> invalidLimit : invalidLimits) {
            assertThatThrownBy(invalidLimit::get)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Verification limits exceed the signed proof contract");
        }
    }

    /** Normalizes nullable public report collections to immutable empty values. */
    @Test
    void shouldNormalizeNullableReportCollections() {
        VerificationCheck check = new VerificationCheck(
                "archive", "input", VerificationCheckStatus.PASS,
                VerificationCode.ARCHIVE_VALID, "valid", null);
        VerificationReport report = new VerificationReport(
                VerificationReport.SCHEMA_VERSION,
                VerificationOutcome.VALID,
                "2026-07-15T00:00:00Z",
                VerificationReport.VERIFIER_VERSION,
                null,
                null);

        assertThat(check.evidence()).isEmpty();
        assertThatThrownBy(() -> check.evidence().put("key", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(report.checks()).isEmpty();
        assertThatThrownBy(() -> report.checks().add(check))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** Preserves incomplete producer records so the verifier can reject them at its validation boundary. */
    @Test
    void shouldPreserveIncompleteProducerRecordsForValidation() {
        SignedProofBundleModel.ManifestSeed seed = new SignedProofBundleModel.ManifestSeed(
                "proof", "file", 2, "leaf", "batch", "2026-07-15T00:00:00Z", "ACTIVE", "/status");
        SignedProofBundleModel.EvidencePayloads payloads = new SignedProofBundleModel.EvidencePayloads(
                "sha256:" + "a".repeat(64), null, null, null, null, "verify locally");
        SignedProofBundleModel.MerkleProofEvidence merkle =
                new SignedProofBundleModel.MerkleProofEvidence(
                        "schema", "type", "hash", "algorithm", "root", "leaf", 0, null);

        assertThat(seed.proofId()).isEqualTo("proof");
        assertThat(seed.issuedStatus()).isEqualTo("ACTIVE");
        assertThat(payloads.contentHash()).isEqualTo("sha256:" + "a".repeat(64));
        assertThat(payloads.chunkManifest()).isNull();
        assertThat(payloads.merkleProof()).isNull();
        assertThat(merkle.proofPath()).isNull();
    }
}
