package cn.flying.verifier;

import cn.flying.verifier.contract.SignedProofBundleContract;
import cn.flying.verifier.crypto.CanonicalJson;
import cn.flying.verifier.crypto.MerkleProofs;
import cn.flying.verifier.model.ChainRootEvidence;
import cn.flying.verifier.model.PublicProofStatus;
import cn.flying.verifier.model.PublicSigningKey;
import cn.flying.verifier.model.VerificationCheckStatus;
import cn.flying.verifier.model.VerificationCode;
import cn.flying.verifier.model.VerificationOutcome;
import cn.flying.verifier.model.VerificationReport;
import cn.flying.verifier.resolver.Resolution;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end SDK tests for integrity, trust, lifecycle, chain, malformed input, and resource outcomes.
 */
class DefaultProofVerifierTest {

    @TempDir
    Path directory;

    private DefaultProofVerifier verifier;
    private VerifierTestFixture.Fixture fixture;
    private final CanonicalJson json = new CanonicalJson();

    /** Creates a new signed archive for each independent test mutation. */
    @BeforeEach
    void setUp() throws Exception {
        verifier = new DefaultProofVerifier();
        fixture = new VerifierTestFixture().create(directory);
    }

    /** Requires every local and online verification layer before returning VALID. */
    @Test
    void shouldReturnValidOnlyWhenEveryLayerPasses() {
        VerificationReport report = verifier.verify(
                fixture.original(), fixture.proof(), VerifierTestFixture.context(fixture));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.VALID);
        assertThat(report.checks()).isNotEmpty()
                .allMatch(check -> check.status() == VerificationCheckStatus.PASS);
        assertThat(report.summary().proofId()).isEqualTo(VerifierTestFixture.PROOF_ID);
        assertThat(report.summary().computedContentHash()).isEqualTo(report.summary().contentHash());
        assertThat(report.summary().computedMerkleRoot()).isEqualTo(report.summary().merkleRoot());
        assertThat(report.summary().currentStatus()).isEqualTo("ACTIVE");
        assertThat(report.summary().liveBlockNumber()).isEqualTo(101L);
        assertThat(report.summary().keySource()).isEqualTo(fixture.key().source());
        assertThat(report.summary().contractAddress()).isEqualTo(VerifierTestFixture.CONTRACT_ADDRESS);
    }

    /** Keeps locally valid evidence indeterminate when all external trust resolution is disabled. */
    @Test
    void shouldNeverTreatOfflineVerificationAsValid() {
        VerificationReport report = verifier.verify(
                fixture.original(), fixture.proof(), VerificationContext.offline());

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.INDETERMINATE);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(
                        VerificationCode.SIGNING_KEY_UNAVAILABLE,
                        VerificationCode.STATUS_UNAVAILABLE,
                        VerificationCode.LIVE_CHAIN_UNAVAILABLE);
    }

    /** Converts a broken caller clock into a deterministic safe error report. */
    @Test
    void shouldFailSafelyWhenVerificationClockIsBroken() {
        Clock broken = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public java.time.Instant instant() {
                throw new IllegalStateException("broken clock");
            }
        };
        VerificationContext context = new VerificationContext(
                VerificationLimits.defaults(), null, null, null, broken);

        VerificationReport report = verifier.verify(fixture.original(), fixture.proof(), context);

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.ERROR);
        assertThat(report.verifiedAt()).isEqualTo(Instant.EPOCH.toString());
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.INTERNAL_ERROR);
    }

    /** Detects same-length original-file tampering through full and chunk hashes. */
    @Test
    void shouldRejectTamperedOriginalBytes() throws Exception {
        Path tampered = directory.resolve("tampered.txt");
        byte[] bytes = fixture.originalBytes();
        bytes[0] ^= 1;
        Files.write(tampered, bytes);

        VerificationReport report = verifier.verify(tampered, fixture.proof(), VerifierTestFixture.context(fixture));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.INVALID);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.FILE_HASH_MISMATCH, VerificationCode.CHUNK_HASH_MISMATCH);
    }

    /** Detects a signed evidence entry changed without updating the manifest digest. */
    @Test
    void shouldRejectTamperedEvidenceEntry() throws Exception {
        LinkedHashMap<String, byte[]> entries = fixture.mutableEntries();
        entries.put(SignedProofBundleContract.README_ENTRY,
                "tampered readme".getBytes(StandardCharsets.UTF_8));
        Path tampered = directory.resolve("tampered-evidence.zip");
        VerifierTestFixture.writeStoredArchive(tampered, entries);

        VerificationReport report = verifier.verify(fixture.original(), tampered, VerifierTestFixture.context(fixture));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.INVALID);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.ARCHIVE_ENTRY_DIGEST_MISMATCH, VerificationCode.UNSUPPORTED_POLICY);
    }

    /** Never performs outbound trust resolution after a local proof prerequisite has failed. */
    @Test
    void shouldSkipAllResolversForLocallyInvalidEvidence() throws Exception {
        LinkedHashMap<String, byte[]> entries = fixture.mutableEntries();
        entries.put(SignedProofBundleContract.README_ENTRY,
                "locally invalid".getBytes(StandardCharsets.UTF_8));
        Path tampered = directory.resolve("resolver-gate-local.zip");
        VerifierTestFixture.writeStoredArchive(tampered, entries);
        AtomicInteger keyRequests = new AtomicInteger();
        AtomicInteger statusRequests = new AtomicInteger();
        AtomicInteger chainRequests = new AtomicInteger();

        VerificationReport report = verifier.verify(
                fixture.original(),
                tampered,
                countingContext(VerificationLimits.defaults(), keyRequests, statusRequests, chainRequests));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.INVALID);
        assertThat(keyRequests).hasValue(0);
        assertThat(statusRequests).hasValue(0);
        assertThat(chainRequests).hasValue(0);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(
                        VerificationCode.SIGNING_KEY_UNAVAILABLE,
                        VerificationCode.STATUS_UNAVAILABLE,
                        VerificationCode.LIVE_CHAIN_UNAVAILABLE);
    }

    /** Detects compact JWS signature corruption even when every evidence digest still matches. */
    @Test
    void shouldRejectTamperedSignature() throws Exception {
        LinkedHashMap<String, byte[]> entries = fixture.mutableEntries();
        byte[] signature = entries.get(SignedProofBundleContract.SIGNATURE_ENTRY);
        int index = signature.length - 3;
        signature[index] = signature[index] == 'A' ? (byte) 'B' : (byte) 'A';
        Path tampered = directory.resolve("tampered-signature.zip");
        VerifierTestFixture.writeStoredArchive(tampered, entries);

        VerificationReport report = verifier.verify(fixture.original(), tampered, VerifierTestFixture.context(fixture));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.INVALID);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.SIGNATURE_INVALID);
    }

    /** Rejects malformed compact JWS before querying even an otherwise resolvable signing key. */
    @Test
    void shouldRejectMalformedJwsBeforeStatusAndChainResolution() throws Exception {
        assertMalformedJwsRejectedBeforeKeyResolution(
                "malformed-jws-resolved-key.zip",
                Resolution.resolved(fixture.key()));
    }

    /** Rejects malformed compact JWS before a definitive unknown-key lookup can change the outcome. */
    @Test
    void shouldRejectMalformedJwsBeforeUnknownKeyResolution() throws Exception {
        assertMalformedJwsRejectedBeforeKeyResolution(
                "malformed-jws-unknown-key.zip",
                Resolution.notFound("unknown key"));
    }

    /** Rejects malformed compact JWS before a failing key dependency can make verification indeterminate. */
    @Test
    void shouldRejectMalformedJwsBeforeFailedKeyResolution() throws Exception {
        assertMalformedJwsRejectedBeforeKeyResolution(
                "malformed-jws-key-error.zip",
                Resolution.error("key service unavailable"));
    }

    /** Rejects a canonical protected-header identity conflict before querying an unknown key. */
    @Test
    void shouldRejectProtectedHeaderIdentityMismatchBeforeKeyResolution() throws Exception {
        byte[] signatureEntry = fixture.entries().get(SignedProofBundleContract.SIGNATURE_ENTRY);
        String compact = new String(signatureEntry, StandardCharsets.US_ASCII).trim();
        String[] parts = compact.split("\\.", -1);
        ObjectNode header = (ObjectNode) json.mapperCopy().readTree(
                Base64.getUrlDecoder().decode(parts[0]));
        header.put("kid", "untrusted-key-id");
        parts[0] = Base64.getUrlEncoder().withoutPadding().encodeToString(json.canonicalBytes(header));

        assertSignatureEntryRejectedBeforeKeyResolution(
                "conflicting-jws-header.zip",
                (String.join(".", parts) + "\n").getBytes(StandardCharsets.US_ASCII),
                Resolution.notFound("unknown key"));
    }

    /** Rejects a canonical JWS payload that differs from manifest bytes before any key lookup. */
    @Test
    void shouldRejectJwsPayloadMismatchBeforeKeyResolution() throws Exception {
        byte[] signatureEntry = fixture.entries().get(SignedProofBundleContract.SIGNATURE_ENTRY);
        String[] parts = new String(signatureEntry, StandardCharsets.US_ASCII).trim().split("\\.", -1);
        parts[1] = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "different-manifest".getBytes(StandardCharsets.UTF_8));

        assertSignatureEntryRejectedBeforeKeyResolution(
                "conflicting-jws-payload.zip",
                (String.join(".", parts) + "\n").getBytes(StandardCharsets.US_ASCII),
                Resolution.error("key service unavailable"));
    }

    /** Detects a canonical manifest identity change through the detached trusted signature. */
    @Test
    void shouldRejectCanonicalManifestTampering() throws Exception {
        ObjectNode manifest = objectEntry(SignedProofBundleContract.MANIFEST_ENTRY);
        String changedProofId = "rp-proof-" + "9".repeat(64);
        manifest.put("proofId", changedProofId);
        manifest.put("statusLocation", "/api/v1/public/proofs/" + changedProofId + "/status");
        Path tampered = writeMutatedJsonEntry(
                "tampered-manifest.zip", SignedProofBundleContract.MANIFEST_ENTRY, manifest);

        VerificationReport report = verifier.verify(
                fixture.original(), tampered, VerifierTestFixture.context(fixture));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.INVALID);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.SIGNATURE_INVALID);
    }

    /** Treats a trusted issuer's current revocation as a definitive invalid result. */
    @Test
    void shouldRejectCurrentlyRevokedProof() {
        PublicProofStatus revoked = new PublicProofStatus(
                fixture.status().proofId(),
                "REVOKED",
                "2",
                fixture.status().issuedStatus(),
                fixture.status().keyId(),
                fixture.status().keyVersion(),
                "operator_revocation",
                fixture.status().issuedAt(),
                VerifierTestFixture.NOW.toString(),
                "fixture-status");
        VerificationContext context = new VerificationContext(
                VerificationLimits.defaults(),
                (keyId, keyVersion) -> Resolution.resolved(fixture.key()),
                proofId -> Resolution.resolved(revoked),
                query -> Resolution.resolved(fixture.chain()),
                Clock.fixed(VerifierTestFixture.NOW, ZoneOffset.UTC));

        VerificationReport report = verifier.verify(fixture.original(), fixture.proof(), context);

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.INVALID);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.STATUS_REVOKED);
    }

    /** Rejects a status response whose version or immutable issuance timestamp is inconsistent. */
    @Test
    void shouldRejectMismatchedStatusSnapshot() {
        PublicProofStatus mismatch = new PublicProofStatus(
                fixture.status().proofId(),
                "ACTIVE",
                "0",
                fixture.status().issuedStatus(),
                fixture.status().keyId(),
                fixture.status().keyVersion(),
                null,
                "2026-07-13T00:00:00Z",
                fixture.status().updatedAt(),
                fixture.status().source());
        VerificationContext context = new VerificationContext(
                VerificationLimits.defaults(),
                (keyId, keyVersion) -> Resolution.resolved(fixture.key()),
                proofId -> Resolution.resolved(mismatch),
                query -> Resolution.resolved(fixture.chain()),
                Clock.fixed(VerifierTestFixture.NOW, ZoneOffset.UTC));

        VerificationReport report = verifier.verify(fixture.original(), fixture.proof(), context);

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.INVALID);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.STATUS_INVALID);
    }

    /** 验证状态更新时间早于签发时间时，严格拒绝该公开状态身份。 */
    @Test
    void shouldRejectStatusUpdatedBeforeIssuance() {
        String updatedAt = Instant.parse(fixture.status().issuedAt()).minusMillis(1).toString();

        assertStatusTimestampRejected(updatedAt);
    }

    /** 验证状态更新时间晚于校验时钟时，严格拒绝该未来状态时间。 */
    @Test
    void shouldRejectStatusUpdatedAfterVerificationClock() {
        String updatedAt = VerifierTestFixture.NOW.plusMillis(1).toString();

        assertStatusTimestampRejected(updatedAt);
    }

    /** Rejects non-canonical or out-of-range lifecycle version strings from custom resolvers. */
    @ParameterizedTest
    @ValueSource(strings = {"+1", "01", "9223372036854775808"})
    void shouldRejectNonCanonicalStatusVersions(String statusVersion) {
        PublicProofStatus status = new PublicProofStatus(
                fixture.status().proofId(),
                fixture.status().status(),
                statusVersion,
                fixture.status().issuedStatus(),
                fixture.status().keyId(),
                fixture.status().keyVersion(),
                fixture.status().reason(),
                fixture.status().issuedAt(),
                fixture.status().updatedAt(),
                fixture.status().source());
        VerificationContext context = new VerificationContext(
                VerificationLimits.defaults(),
                (keyId, keyVersion) -> Resolution.resolved(fixture.key()),
                proofId -> Resolution.resolved(status),
                query -> Resolution.resolved(fixture.chain()),
                Clock.fixed(VerifierTestFixture.NOW, ZoneOffset.UTC));

        VerificationReport report = verifier.verify(fixture.original(), fixture.proof(), context);

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.INVALID);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.STATUS_INVALID);
    }

    /** Rejects a live gateway root that does not match the signed batch root. */
    @Test
    void shouldRejectLiveChainRootMismatch() {
        ChainRootEvidence mismatch = new ChainRootEvidence(
                fixture.chain().schemaVersion(),
                fixture.chain().chainType(),
                fixture.chain().chainId(),
                fixture.chain().groupId(),
                fixture.chain().contractAddress(),
                fixture.chain().batchNo(),
                "f".repeat(64),
                fixture.chain().transactionHash(),
                fixture.chain().blockNumber(),
                fixture.chain().source());
        VerificationContext context = new VerificationContext(
                VerificationLimits.defaults(),
                (keyId, keyVersion) -> Resolution.resolved(fixture.key()),
                proofId -> Resolution.resolved(fixture.status()),
                query -> Resolution.resolved(mismatch),
                Clock.fixed(VerifierTestFixture.NOW, ZoneOffset.UTC));

        VerificationReport report = verifier.verify(fixture.original(), fixture.proof(), context);

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.INVALID);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.LIVE_CHAIN_MISMATCH);
        assertThat(report.summary().liveBlockNumber()).isNull();
        assertThat(report.summary().liveChainSource()).isNull();
    }

    /** Keeps an otherwise valid proof indeterminate when the key identity is unknown. */
    @Test
    void shouldReportUnknownSigningKeyWithoutTrustingEmbeddedMetadata() {
        VerificationContext context = new VerificationContext(
                VerificationLimits.defaults(),
                (keyId, keyVersion) -> Resolution.notFound("unknown key"),
                proofId -> Resolution.resolved(fixture.status()),
                query -> Resolution.resolved(fixture.chain()),
                Clock.fixed(VerifierTestFixture.NOW, ZoneOffset.UTC));

        VerificationReport report = verifier.verify(fixture.original(), fixture.proof(), context);

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.INDETERMINATE);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.SIGNING_KEY_UNKNOWN);
    }

    /** Converts strict JSON null into a bounded input-format error instead of a null dereference. */
    @Test
    void shouldRejectNullJsonDocumentSafely() throws Exception {
        LinkedHashMap<String, byte[]> entries = fixture.mutableEntries();
        entries.put(SignedProofBundleContract.CHUNK_MANIFEST_ENTRY,
                "null".getBytes(StandardCharsets.UTF_8));
        Path malformed = directory.resolve("null-json.zip");
        VerifierTestFixture.writeStoredArchive(malformed, entries);

        VerificationReport report = verifier.verify(fixture.original(), malformed, VerifierTestFixture.context(fixture));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.ERROR);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.JSON_INVALID);
    }

    /** Rejects a null chunk element without falling through to an internal null dereference. */
    @Test
    void shouldRejectNullChunkElementSafely() throws Exception {
        ObjectNode chunk = objectEntry(SignedProofBundleContract.CHUNK_MANIFEST_ENTRY);
        ((ArrayNode) chunk.get("chunks")).set(0, NullNode.getInstance());
        Path malformed = writeMutatedJsonEntry(
                "null-chunk.zip", SignedProofBundleContract.CHUNK_MANIFEST_ENTRY, chunk);

        VerificationReport report = verifier.verify(
                fixture.original(), malformed, VerifierTestFixture.context(fixture));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.INVALID);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.CHUNK_MANIFEST_INVALID)
                .doesNotContain(VerificationCode.INTERNAL_ERROR);
    }

    /** Rejects a null Merkle sibling at the strict JSON boundary without leaking an internal error. */
    @Test
    void shouldRejectNullMerkleProofNodeSafely() throws Exception {
        ObjectNode merkle = objectEntry(SignedProofBundleContract.MERKLE_PROOF_ENTRY);
        ((ArrayNode) merkle.get("proofPath")).addNull();
        Path malformed = writeMutatedJsonEntry(
                "null-merkle-node.zip", SignedProofBundleContract.MERKLE_PROOF_ENTRY, merkle);

        VerificationReport report = verifier.verify(
                fixture.original(), malformed, VerifierTestFixture.context(fixture));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.ERROR);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.JSON_INVALID)
                .doesNotContain(VerificationCode.INTERNAL_ERROR);
    }

    /** Bounds and removes control characters from attacker-controlled report summary fields. */
    @Test
    void shouldSanitizeUntrustedSummaryText() throws Exception {
        ObjectNode manifest = objectEntry(SignedProofBundleContract.MANIFEST_ENTRY);
        manifest.put("proofId", "rp-proof-\u001b[31m\n" + "x".repeat(700));
        Path malformed = writeMutatedJsonEntry(
                "unsafe-summary.zip", SignedProofBundleContract.MANIFEST_ENTRY, manifest);

        VerificationReport report = verifier.verify(
                fixture.original(), malformed, VerifierTestFixture.context(fixture));

        assertThat(report.summary().proofId()).hasSize(512);
        assertThat(report.summary().proofId().chars().anyMatch(Character::isISOControl)).isFalse();
    }

    /** Rejects ordinary compressed ZIP entries before processing signed evidence bytes. */
    @Test
    void shouldRejectDeflatedArchive() throws Exception {
        Path malformed = directory.resolve("deflated.zip");
        VerifierTestFixture.writeDeflatedArchive(malformed, fixture.mutableEntries());

        VerificationReport report = verifier.verify(fixture.original(), malformed, VerifierTestFixture.context(fixture));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.ERROR);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.ARCHIVE_ENTRY_INVALID);
    }

    /** Rejects a Unix symbolic-link entry even when its name, bytes, CRC, and order otherwise match. */
    @Test
    void shouldRejectSymlinkArchiveEntry() throws Exception {
        Path malformed = directory.resolve("symlink-entry.zip");
        VerifierTestFixture.writeSymlinkArchive(malformed, fixture.mutableEntries());

        assertArchiveInputError(malformed);
    }

    /** Rejects symbolic links for both outer proof and original-file inputs. */
    @Test
    void shouldRejectSymbolicLinkInputPaths() throws Exception {
        Path linkedProof = directory.resolve("linked-proof.zip");
        Path linkedOriginal = directory.resolve("linked-original.txt");
        Files.createSymbolicLink(linkedProof, fixture.proof().getFileName());
        Files.createSymbolicLink(linkedOriginal, fixture.original().getFileName());

        VerificationReport proofReport = verifier.verify(
                fixture.original(), linkedProof, VerifierTestFixture.context(fixture));
        VerificationReport originalReport = verifier.verify(
                linkedOriginal, fixture.proof(), VerifierTestFixture.context(fixture));

        assertThat(proofReport.outcome()).isEqualTo(VerificationOutcome.ERROR);
        assertThat(proofReport.checks()).extracting(check -> check.code())
                .contains(VerificationCode.ARCHIVE_MALFORMED);
        assertThat(originalReport.outcome()).isEqualTo(VerificationOutcome.ERROR);
        assertThat(originalReport.checks()).extracting(check -> check.code())
                .contains(VerificationCode.VERIFICATION_IO_ERROR);
    }

    /** Rejects an absent original path as an explicit processing error rather than a false mismatch. */
    @Test
    void shouldReportMissingOriginalAsError() {
        VerificationReport report = verifier.verify(
                directory.resolve("missing.bin"), fixture.proof(), VerifierTestFixture.context(fixture));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.ERROR);
        assertThat(report.checks()).anyMatch(check ->
                check.status() == VerificationCheckStatus.ERROR
                        && check.code() == VerificationCode.VERIFICATION_IO_ERROR);
    }

    /** Rejects every known non-active current lifecycle state. */
    @ParameterizedTest
    @ValueSource(strings = {"SUPERSEDED", "INVALID", "UNSUPPORTED"})
    void shouldRejectEveryNonActiveCurrentStatus(String currentStatus) {
        PublicProofStatus status = new PublicProofStatus(
                fixture.status().proofId(),
                currentStatus,
                "2",
                fixture.status().issuedStatus(),
                fixture.status().keyId(),
                fixture.status().keyVersion(),
                "lifecycle_change",
                fixture.status().issuedAt(),
                VerifierTestFixture.NOW.toString(),
                fixture.status().source());
        VerificationContext context = new VerificationContext(
                VerificationLimits.defaults(),
                (keyId, keyVersion) -> Resolution.resolved(fixture.key()),
                proofId -> Resolution.resolved(status),
                query -> Resolution.resolved(fixture.chain()),
                Clock.fixed(VerifierTestFixture.NOW, ZoneOffset.UTC));

        VerificationReport report = verifier.verify(fixture.original(), fixture.proof(), context);

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.INVALID);
        assertThat(report.checks()).anyMatch(check -> check.category().equals("status")
                && check.status() == VerificationCheckStatus.FAIL);
    }

    /** Rejects a resolved key whose fingerprint does not match signed metadata. */
    @Test
    void shouldRejectTrustedKeyFingerprintMismatch() {
        var wrongKey = new PublicSigningKey(
                fixture.key().keyId(),
                fixture.key().keyVersion(),
                fixture.key().algorithm(),
                fixture.key().publicKeySpki(),
                "sha256:" + "f".repeat(64),
                fixture.key().source());
        VerificationContext context = new VerificationContext(
                VerificationLimits.defaults(),
                (keyId, keyVersion) -> Resolution.resolved(wrongKey),
                proofId -> Resolution.resolved(fixture.status()),
                query -> Resolution.resolved(fixture.chain()),
                Clock.fixed(VerifierTestFixture.NOW, ZoneOffset.UTC));

        VerificationReport report = verifier.verify(fixture.original(), fixture.proof(), context);

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.INVALID);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.SIGNATURE_INVALID);
        assertThat(report.summary().keySource()).isNull();
    }

    /** Rejects an oversized resolver-supplied SPKI before Base64 decoding or signature work. */
    @Test
    void shouldBoundCustomSigningKeyResolverValues() {
        PublicSigningKey oversized = new PublicSigningKey(
                fixture.key().keyId(),
                fixture.key().keyVersion(),
                fixture.key().algorithm(),
                "A".repeat(3000),
                fixture.key().publicKeyFingerprint(),
                fixture.key().source());
        VerificationContext context = new VerificationContext(
                VerificationLimits.defaults(),
                (keyId, keyVersion) -> Resolution.resolved(oversized),
                proofId -> Resolution.resolved(fixture.status()),
                query -> Resolution.resolved(fixture.chain()),
                Clock.fixed(VerifierTestFixture.NOW, ZoneOffset.UTC));

        VerificationReport report = verifier.verify(fixture.original(), fixture.proof(), context);

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.INVALID);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.SIGNATURE_INVALID);
    }

    /** Requires an absent live transaction when the signed query-confirmed receipt has no transaction. */
    @Test
    void shouldRejectUnexpectedLiveTransactionForQueryConfirmedReceipt() throws Exception {
        VerifierTestFixture.Fixture queryFixture = new VerifierTestFixture().createQueryConfirmed(directory);
        ChainRootEvidence unexpectedTransaction = new ChainRootEvidence(
                queryFixture.chain().schemaVersion(),
                queryFixture.chain().chainType(),
                queryFixture.chain().chainId(),
                queryFixture.chain().groupId(),
                queryFixture.chain().contractAddress(),
                queryFixture.chain().batchNo(),
                queryFixture.chain().merkleRoot(),
                VerifierTestFixture.TRANSACTION_HASH,
                queryFixture.chain().blockNumber(),
                queryFixture.chain().source());
        VerificationContext context = new VerificationContext(
                VerificationLimits.defaults(),
                (keyId, keyVersion) -> Resolution.resolved(queryFixture.key()),
                proofId -> Resolution.resolved(queryFixture.status()),
                query -> Resolution.resolved(unexpectedTransaction),
                Clock.fixed(VerifierTestFixture.NOW, ZoneOffset.UTC));

        VerificationReport report = verifier.verify(
                queryFixture.original(), queryFixture.proof(), context);

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.INVALID);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.LIVE_CHAIN_MISMATCH);
    }

    /** Accepts optional-prefix and hexadecimal-case variations of the same live transaction hash. */
    @Test
    void shouldMatchEquivalentLiveTransactionHashEncoding() {
        String equivalentTransaction = fixture.chain().transactionHash()
                .replaceFirst("^0x", "")
                .toUpperCase(Locale.ROOT);
        ChainRootEvidence equivalent = new ChainRootEvidence(
                fixture.chain().schemaVersion(),
                fixture.chain().chainType(),
                fixture.chain().chainId(),
                fixture.chain().groupId(),
                fixture.chain().contractAddress(),
                fixture.chain().batchNo(),
                fixture.chain().merkleRoot(),
                equivalentTransaction,
                fixture.chain().blockNumber(),
                fixture.chain().source());
        VerificationContext context = new VerificationContext(
                VerificationLimits.defaults(),
                (keyId, keyVersion) -> Resolution.resolved(fixture.key()),
                proofId -> Resolution.resolved(fixture.status()),
                query -> Resolution.resolved(equivalent),
                Clock.fixed(VerifierTestFixture.NOW, ZoneOffset.UTC));

        VerificationReport report = verifier.verify(fixture.original(), fixture.proof(), context);

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.VALID);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.LIVE_CHAIN_MATCH);
    }

    /** Rejects duplicate JSON properties and trailing tokens through the strict parser. */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"schemaVersion\":\"a\",\"schemaVersion\":\"b\"}",
            "{} {}"
    })
    void shouldRejectDuplicateOrTrailingJson(String malformedJson) throws Exception {
        LinkedHashMap<String, byte[]> entries = fixture.mutableEntries();
        entries.put(SignedProofBundleContract.CHUNK_MANIFEST_ENTRY,
                malformedJson.getBytes(StandardCharsets.UTF_8));
        Path malformed = directory.resolve("strict-json-" + Math.abs(malformedJson.hashCode()) + ".zip");
        VerifierTestFixture.writeStoredArchive(malformed, entries);

        VerificationReport report = verifier.verify(fixture.original(), malformed, VerifierTestFixture.context(fixture));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.ERROR);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.JSON_INVALID);
    }

    /** Rejects an unsupported signed evidence schema and an oversized Merkle path. */
    @Test
    void shouldRejectUnknownSchemaAndProofPathOverflow() throws Exception {
        var mapper = json.mapperCopy();
        var chunk = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                fixture.entries().get(SignedProofBundleContract.CHUNK_MANIFEST_ENTRY));
        chunk.put("schemaVersion", "record-platform-proof-chunk-manifest.v999");
        chunk.put("sourceSchema", "cn.flying.chunk-manifest.v999");
        var merkle = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                fixture.entries().get(SignedProofBundleContract.MERKLE_PROOF_ENTRY));
        var path = merkle.putArray("proofPath");
        for (int index = 0; index <= SignedProofBundleContract.MAX_PROOF_NODES; index++) {
            var node = path.addObject();
            node.put("hash", "a".repeat(64));
            node.put("position", "LEFT");
        }
        LinkedHashMap<String, byte[]> entries = fixture.mutableEntries();
        entries.put(SignedProofBundleContract.CHUNK_MANIFEST_ENTRY, json.canonicalBytes(chunk));
        entries.put(SignedProofBundleContract.MERKLE_PROOF_ENTRY, json.canonicalBytes(merkle));
        Path malformed = directory.resolve("unsupported-contract.zip");
        VerifierTestFixture.writeStoredArchive(malformed, entries);

        VerificationReport report = verifier.verify(fixture.original(), malformed, VerifierTestFixture.context(fixture));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.INVALID);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.CHUNK_MANIFEST_INVALID, VerificationCode.MERKLE_PROOF_INVALID);
    }

    /** Rejects a Merkle path whose sibling directions cannot belong to its declared leaf index. */
    @Test
    void shouldBindMerklePathToDeclaredLeafIndex() throws Exception {
        ObjectNode merkle = objectEntry(SignedProofBundleContract.MERKLE_PROOF_ENTRY);
        merkle.put("leafIndex", 1);
        Path malformed = writeMutatedJsonEntry(
                "merkle-leaf-index.zip", SignedProofBundleContract.MERKLE_PROOF_ENTRY, merkle);

        assertInvalidCode(malformed, VerificationCode.MERKLE_PROOF_INVALID);
    }

    /** Rejects signed Merkle siblings that are semantically equal but not canonical lowercase hex. */
    @Test
    void shouldRejectNonCanonicalMerkleSiblingHash() throws Exception {
        ObjectNode merkle = objectEntry(SignedProofBundleContract.MERKLE_PROOF_ENTRY);
        ArrayNode proofPath = (ArrayNode) merkle.get("proofPath");
        proofPath.removeAll();
        ObjectNode sibling = proofPath.addObject();
        sibling.put("position", "RIGHT");
        sibling.put("hash", "A".repeat(64));
        String root = MerkleProofs.calculateParentHash(merkle.get("leafHash").asText(), "a".repeat(64));
        merkle.put("merkleRoot", root);
        ObjectNode receipt = objectEntry(SignedProofBundleContract.BLOCKCHAIN_RECEIPT_ENTRY);
        receipt.put("batchChainRoot", root);

        LinkedHashMap<String, byte[]> entries = fixture.mutableEntries();
        entries.put(SignedProofBundleContract.MERKLE_PROOF_ENTRY, json.canonicalBytes(merkle));
        entries.put(SignedProofBundleContract.BLOCKCHAIN_RECEIPT_ENTRY, json.canonicalBytes(receipt));
        Path malformed = directory.resolve("noncanonical-merkle-node.zip");
        VerifierTestFixture.writeStoredArchive(malformed, entries);

        assertInvalidCode(malformed, VerificationCode.MERKLE_PROOF_INVALID);
    }

    /** Rejects unsupported signed hash and signature algorithms before any trust lookup. */
    @Test
    void shouldRejectUnknownAlgorithms() throws Exception {
        ObjectNode chunk = objectEntry(SignedProofBundleContract.CHUNK_MANIFEST_ENTRY);
        chunk.put("hashAlgorithm", "SHA-512");
        ObjectNode manifest = objectEntry(SignedProofBundleContract.MANIFEST_ENTRY);
        ((ObjectNode) manifest.get("signature")).put("algorithm", "RS256");
        LinkedHashMap<String, byte[]> entries = fixture.mutableEntries();
        entries.put(SignedProofBundleContract.CHUNK_MANIFEST_ENTRY, json.canonicalBytes(chunk));
        entries.put(SignedProofBundleContract.MANIFEST_ENTRY, json.canonicalBytes(manifest));
        Path malformed = directory.resolve("unknown-algorithms.zip");
        VerifierTestFixture.writeStoredArchive(malformed, entries);

        VerificationReport report = verifier.verify(
                fixture.original(), malformed, VerifierTestFixture.context(fixture));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.INVALID);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.MANIFEST_INVALID, VerificationCode.CHUNK_MANIFEST_INVALID);
    }

    /** Requires canonical prefixed hashes for manifest digests and registry fingerprints. */
    @Test
    void shouldRejectNonCanonicalSignedDigestFields() throws Exception {
        ObjectNode manifest = objectEntry(SignedProofBundleContract.MANIFEST_ENTRY);
        ObjectNode firstDigest = (ObjectNode) ((ArrayNode) manifest.get("entries")).get(0);
        firstDigest.put("sha256", firstDigest.get("sha256").asText().substring("sha256:".length()));
        Path manifestArchive = writeMutatedJsonEntry(
                "raw-manifest-digest.zip", SignedProofBundleContract.MANIFEST_ENTRY, manifest);

        ObjectNode receipt = objectEntry(SignedProofBundleContract.BLOCKCHAIN_RECEIPT_ENTRY);
        ObjectNode registry = (ObjectNode) receipt.get("contractRegistry");
        registry.put("registryFingerprint",
                registry.get("registryFingerprint").asText().substring("sha256:".length()));
        Path registryArchive = writeMutatedJsonEntry(
                "raw-registry-fingerprint.zip", SignedProofBundleContract.BLOCKCHAIN_RECEIPT_ENTRY, receipt);

        assertInvalidCode(manifestArchive, VerificationCode.ARCHIVE_ENTRY_DIGEST_MISMATCH);
        assertInvalidCode(registryArchive, VerificationCode.CONTRACT_REGISTRY_INVALID);
    }

    /** Rejects an empty signed receipt without turning missing receipt fields into an internal failure. */
    @Test
    void shouldRejectMissingChainReceiptFieldsWithoutInternalError() throws Exception {
        ObjectNode emptyReceipt = json.mapperCopy().createObjectNode();
        Path malformed = writeMutatedJsonEntry(
                "missing-chain-receipt-fields.zip",
                SignedProofBundleContract.BLOCKCHAIN_RECEIPT_ENTRY,
                emptyReceipt);

        VerificationReport report = verifier.verify(
                fixture.original(), malformed, VerifierTestFixture.context(fixture));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.INVALID);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(
                        VerificationCode.CHAIN_RECEIPT_INVALID,
                        VerificationCode.CONTRACT_REGISTRY_INVALID)
                .doesNotContain(VerificationCode.INTERNAL_ERROR);
    }

    /** Rejects chunk cardinality overflow and overflow in the signed chunk-size sum. */
    @Test
    void shouldRejectChunkCountAndSizeSumOverflow() throws Exception {
        ObjectNode countOverflow = objectEntry(SignedProofBundleContract.CHUNK_MANIFEST_ENTRY);
        ArrayNode tooMany = countOverflow.putArray("chunks");
        ObjectNode sourceChunk = objectEntry(SignedProofBundleContract.CHUNK_MANIFEST_ENTRY);
        ObjectNode seed = (ObjectNode) ((ArrayNode) sourceChunk.get("chunks")).get(0);
        for (int index = 0; index <= SignedProofBundleContract.MAX_CHUNKS; index++) {
            ObjectNode item = seed.deepCopy();
            item.put("index", index);
            item.put("size", 1);
            tooMany.add(item);
        }
        countOverflow.put("chunkCount", tooMany.size());
        countOverflow.put("chunkSize", 1);
        countOverflow.put("totalSize", tooMany.size());
        Path excessiveCount = writeMutatedJsonEntry(
                "chunk-count-overflow.zip", SignedProofBundleContract.CHUNK_MANIFEST_ENTRY, countOverflow);

        ObjectNode sumOverflow = objectEntry(SignedProofBundleContract.CHUNK_MANIFEST_ENTRY);
        ArrayNode chunks = sumOverflow.putArray("chunks");
        for (int index = 0; index < 2; index++) {
            ObjectNode item = seed.deepCopy();
            item.put("index", index);
            item.put("size", Long.MAX_VALUE);
            chunks.add(item);
        }
        sumOverflow.put("chunkCount", 2);
        sumOverflow.put("chunkSize", Long.MAX_VALUE);
        sumOverflow.put("totalSize", 1);
        Path excessiveSum = writeMutatedJsonEntry(
                "chunk-sum-overflow.zip", SignedProofBundleContract.CHUNK_MANIFEST_ENTRY, sumOverflow);

        assertInvalidCode(excessiveCount, VerificationCode.CHUNK_MANIFEST_INVALID);
        assertInvalidCode(excessiveSum, VerificationCode.CHUNK_MANIFEST_INVALID);
    }

    /** Distinguishes an operator byte limit from an invalid signed chunk contract. */
    @Test
    void shouldReportOriginalFileLimitAsProcessingError() {
        VerificationLimits defaults = VerificationLimits.defaults();
        VerificationLimits oneByteLimit = new VerificationLimits(
                1,
                defaults.maxArchiveBytes(),
                defaults.maxEntryBytes(),
                defaults.maxTotalEntryBytes(),
                defaults.maxChunks(),
                defaults.maxProofNodes());
        VerificationContext context = new VerificationContext(
                oneByteLimit,
                (keyId, keyVersion) -> Resolution.resolved(fixture.key()),
                proofId -> Resolution.resolved(fixture.status()),
                query -> Resolution.resolved(fixture.chain()),
                Clock.fixed(VerifierTestFixture.NOW, ZoneOffset.UTC));

        VerificationReport report = verifier.verify(fixture.original(), fixture.proof(), context);

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.ERROR);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.FILE_TOO_LARGE)
                .doesNotContain(VerificationCode.CHUNK_MANIFEST_INVALID);
    }

    /** Detects chunk, Merkle, and signed chain-root evidence tampering at their own layers. */
    @Test
    void shouldRejectChunkMerkleAndReceiptTampering() throws Exception {
        ObjectNode chunk = objectEntry(SignedProofBundleContract.CHUNK_MANIFEST_ENTRY);
        ((ObjectNode) ((ArrayNode) chunk.get("chunks")).get(0))
                .put("plainHash", "sha256:" + "f".repeat(64));
        Path chunkTamper = writeMutatedJsonEntry(
                "chunk-tamper.zip", SignedProofBundleContract.CHUNK_MANIFEST_ENTRY, chunk);

        ObjectNode merkle = objectEntry(SignedProofBundleContract.MERKLE_PROOF_ENTRY);
        merkle.put("merkleRoot", "f".repeat(64));
        Path merkleTamper = writeMutatedJsonEntry(
                "merkle-tamper.zip", SignedProofBundleContract.MERKLE_PROOF_ENTRY, merkle);

        ObjectNode receipt = objectEntry(SignedProofBundleContract.BLOCKCHAIN_RECEIPT_ENTRY);
        receipt.put("batchChainRoot", "f".repeat(64));
        Path receiptTamper = writeMutatedJsonEntry(
                "receipt-tamper.zip", SignedProofBundleContract.BLOCKCHAIN_RECEIPT_ENTRY, receipt);

        assertInvalidCode(chunkTamper, VerificationCode.CHUNK_HASH_MISMATCH);
        assertInvalidCode(merkleTamper, VerificationCode.MERKLE_PROOF_INVALID);
        assertInvalidCode(receiptTamper, VerificationCode.CHAIN_RECEIPT_INVALID);
    }

    /** Rejects any ninth entry and traversal-shaped entry name before evidence processing. */
    @Test
    void shouldRejectExtraAndTraversalEntries() throws Exception {
        LinkedHashMap<String, byte[]> extraEntries = fixture.mutableEntries();
        extraEntries.put("extra.txt", new byte[]{1});
        Path extra = directory.resolve("extra-entry.zip");
        VerifierTestFixture.writeStoredArchive(extra, extraEntries);

        LinkedHashMap<String, byte[]> traversalEntries = new LinkedHashMap<>();
        traversalEntries.put("../manifest.json",
                fixture.entries().get(SignedProofBundleContract.MANIFEST_ENTRY));
        for (String name : SignedProofBundleContract.ENTRY_ORDER.subList(
                1, SignedProofBundleContract.ENTRY_ORDER.size())) {
            traversalEntries.put(name, fixture.entries().get(name));
        }
        Path traversal = directory.resolve("traversal-entry.zip");
        VerifierTestFixture.writeStoredArchive(traversal, traversalEntries);

        assertArchiveInputError(extra);
        assertArchiveInputError(traversal);
    }

    /** Rejects absolute, nested, and backslash entry names before reading any payload. */
    @ParameterizedTest
    @ValueSource(strings = {"/manifest.json", "nested/manifest.json", "nested\\manifest.json"})
    void shouldRejectNonFlatArchiveEntryNames(String invalidName) throws Exception {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(invalidName, fixture.entries().get(SignedProofBundleContract.MANIFEST_ENTRY));
        for (String name : SignedProofBundleContract.ENTRY_ORDER.subList(
                1, SignedProofBundleContract.ENTRY_ORDER.size())) {
            entries.put(name, fixture.entries().get(name));
        }
        Path malformed = directory.resolve("non-flat-entry-" + Math.abs(invalidName.hashCode()) + ".zip");
        VerifierTestFixture.writeStoredArchive(malformed, entries);

        assertArchiveInputError(malformed);
    }

    /** Rejects a physical duplicate entry even when total count and individual bytes are bounded. */
    @Test
    void shouldRejectDuplicateArchiveEntry() throws Exception {
        Path malformed = directory.resolve("duplicate-entry.zip");
        VerifierTestFixture.writeDuplicateArchive(malformed, fixture.mutableEntries());

        assertArchiveInputError(malformed);
    }

    /** Enforces both per-entry and aggregate logical payload limits below the outer ZIP cap. */
    @Test
    void shouldRejectOversizedEntryAndLogicalPayload() throws Exception {
        LinkedHashMap<String, byte[]> oversizedEntry = fixture.mutableEntries();
        oversizedEntry.put(
                SignedProofBundleContract.MANIFEST_ENTRY,
                new byte[SignedProofBundleContract.MAX_ENTRY_BYTES + 1]);
        Path perEntry = directory.resolve("oversized-entry.zip");
        VerifierTestFixture.writeStoredArchive(perEntry, oversizedEntry);

        LinkedHashMap<String, byte[]> excessiveTotal = fixture.mutableEntries();
        for (String name : SignedProofBundleContract.ENTRY_ORDER.subList(0, 5)) {
            excessiveTotal.put(name, new byte[900_000]);
        }
        Path logicalTotal = directory.resolve("oversized-logical-total.zip");
        VerifierTestFixture.writeStoredArchive(logicalTotal, excessiveTotal);

        assertArchiveInputError(perEntry);
        VerificationReport report = verifier.verify(
                fixture.original(), logicalTotal, VerifierTestFixture.context(fixture));
        assertThat(report.outcome()).isEqualTo(VerificationOutcome.ERROR);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.ARCHIVE_TOO_LARGE);
    }

    /** Rejects an outer archive larger than the hard byte cap without opening it. */
    @Test
    void shouldRejectOversizedOuterArchive() throws Exception {
        Path oversized = directory.resolve("oversized.zip");
        try (OutputStream output = Files.newOutputStream(oversized)) {
            byte[] block = new byte[8192];
            int remaining = SignedProofBundleContract.MAX_ARCHIVE_BYTES + 1;
            while (remaining > 0) {
                int count = Math.min(block.length, remaining);
                output.write(block, 0, count);
                remaining -= count;
            }
        }

        VerificationReport report = verifier.verify(
                fixture.original(), oversized, VerifierTestFixture.context(fixture));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.ERROR);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.ARCHIVE_TOO_LARGE);
    }

    /** Rejects a corrupted STORED payload whose central-directory CRC still describes original bytes. */
    @Test
    void shouldRejectCrcCorruption() throws Exception {
        byte[] archive = Files.readAllBytes(fixture.proof());
        byte[] marker = SignedProofBundleContract.MANIFEST_SCHEMA.getBytes(StandardCharsets.UTF_8);
        int markerIndex = indexOf(archive, marker);
        assertThat(markerIndex).isGreaterThanOrEqualTo(0);
        archive[markerIndex] ^= 1;
        Path corrupted = directory.resolve("crc-corrupted.zip");
        Files.write(corrupted, archive);

        VerificationReport report = verifier.verify(
                fixture.original(), corrupted, VerifierTestFixture.context(fixture));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.ERROR);
        assertThat(report.checks()).anyMatch(check ->
                check.code() == VerificationCode.ARCHIVE_ENTRY_INVALID
                        || check.code() == VerificationCode.ARCHIVE_MALFORMED);
    }

    /** Rejects a central-directory declared size that disagrees with STORED compressed bytes. */
    @Test
    void shouldRejectInconsistentDeclaredEntrySize() throws Exception {
        byte[] archive = Files.readAllBytes(fixture.proof());
        int centralDirectory = indexOf(archive, new byte[]{0x50, 0x4b, 0x01, 0x02});
        assertThat(centralDirectory).isGreaterThanOrEqualTo(0);
        int uncompressedSizeOffset = centralDirectory + 24;
        int originalSize = readLittleEndianInt(archive, uncompressedSizeOffset);
        writeLittleEndianInt(archive, uncompressedSizeOffset, originalSize + 1);
        Path malformed = directory.resolve("size-mismatch.zip");
        Files.write(malformed, archive);

        assertArchiveInputError(malformed);
    }

    /** Rejects unsigned ZIP comments, extra fields, prepended bytes, and trailing bytes. */
    @Test
    void shouldRejectUnsignedZipEnvelopeMetadata() throws Exception {
        Path extra = directory.resolve("entry-extra.zip");
        VerifierTestFixture.writeStoredArchiveWithEntryExtra(extra, fixture.mutableEntries());
        Path comment = directory.resolve("archive-comment.zip");
        VerifierTestFixture.writeStoredArchiveWithComment(comment, fixture.mutableEntries());

        byte[] valid = Files.readAllBytes(fixture.proof());
        byte[] prepended = new byte[valid.length + 1];
        System.arraycopy(valid, 0, prepended, 1, valid.length);
        Path leading = directory.resolve("prepended.zip");
        Files.write(leading, prepended);
        byte[] appended = java.util.Arrays.copyOf(valid, valid.length + 1);
        appended[appended.length - 1] = 1;
        Path trailing = directory.resolve("trailing.zip");
        Files.write(trailing, appended);

        assertArchiveInputError(extra);
        assertArchiveInputError(comment);
        assertArchiveInputError(leading);
        assertArchiveInputError(trailing);
    }

    /** Rejects same-length local-name aliases and unsigned central-directory attributes. */
    @Test
    void shouldRejectRawZipHeaderDrift() throws Exception {
        byte[] localNameAlias = Files.readAllBytes(fixture.proof());
        int localNameOffset = 30;
        localNameAlias[localNameOffset] = (byte) 'x';
        Path aliased = directory.resolve("local-name-alias.zip");
        Files.write(aliased, localNameAlias);

        byte[] centralAttribute = Files.readAllBytes(fixture.proof());
        int centralDirectory = indexOf(centralAttribute, new byte[]{0x50, 0x4b, 0x01, 0x02});
        assertThat(centralDirectory).isGreaterThanOrEqualTo(0);
        centralAttribute[centralDirectory + 36] = 1;
        Path attributed = directory.resolve("central-attribute.zip");
        Files.write(attributed, centralAttribute);

        assertArchiveInputError(aliased);
        assertArchiveInputError(attributed);
    }

    /** Finds one exact byte marker without decoding arbitrary ZIP bytes as text. */
    private int indexOf(byte[] haystack, byte[] needle) {
        for (int index = 0; index <= haystack.length - needle.length; index++) {
            boolean matches = true;
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[index + offset] != needle[offset]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return index;
            }
        }
        return -1;
    }

    /** Reads one unsigned ZIP metadata word in little-endian byte order. */
    private int readLittleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    /** Writes one ZIP metadata word in little-endian byte order. */
    private void writeLittleEndianInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    /** Parses one fixture JSON entry as a mutable object for a focused negative vector. */
    private ObjectNode objectEntry(String entryName) throws Exception {
        return (ObjectNode) json.mapperCopy().readTree(fixture.entries().get(entryName));
    }

    /** Rewrites exactly one canonical JSON entry while preserving the frozen physical ZIP layout. */
    private Path writeMutatedJsonEntry(String fileName, String entryName, ObjectNode value) throws Exception {
        LinkedHashMap<String, byte[]> entries = fixture.mutableEntries();
        entries.put(entryName, json.canonicalBytes(value));
        Path archive = directory.resolve(fileName);
        VerifierTestFixture.writeStoredArchive(archive, entries);
        return archive;
    }

    /** Creates online resolvers that count every attempted trust dependency lookup. */
    private VerificationContext countingContext(
            VerificationLimits limits,
            AtomicInteger keyRequests,
            AtomicInteger statusRequests,
            AtomicInteger chainRequests
    ) {
        return new VerificationContext(
                limits,
                (keyId, keyVersion) -> {
                    keyRequests.incrementAndGet();
                    return Resolution.resolved(fixture.key());
                },
                proofId -> {
                    statusRequests.incrementAndGet();
                    return Resolution.resolved(fixture.status());
                },
                query -> {
                    chainRequests.incrementAndGet();
                    return Resolution.resolved(fixture.chain());
                },
                Clock.fixed(VerifierTestFixture.NOW, ZoneOffset.UTC));
    }

    /** Requires malformed compact JWS bytes to fail locally without invoking any trust resolver. */
    private void assertMalformedJwsRejectedBeforeKeyResolution(
            String fileName,
            Resolution<PublicSigningKey> keyResolution
    ) throws Exception {
        assertSignatureEntryRejectedBeforeKeyResolution(
                fileName,
                "not-a-compact-jws\n".getBytes(StandardCharsets.US_ASCII),
                keyResolution);
    }

    /** Requires one locally invalid signature entry to fail before any trust resolver is invoked. */
    private void assertSignatureEntryRejectedBeforeKeyResolution(
            String fileName,
            byte[] signatureEntry,
            Resolution<PublicSigningKey> keyResolution
    ) throws Exception {
        LinkedHashMap<String, byte[]> entries = fixture.mutableEntries();
        entries.put(SignedProofBundleContract.SIGNATURE_ENTRY, signatureEntry);
        Path malformed = directory.resolve(fileName);
        VerifierTestFixture.writeStoredArchive(malformed, entries);
        AtomicInteger keyRequests = new AtomicInteger();
        AtomicInteger statusRequests = new AtomicInteger();
        AtomicInteger chainRequests = new AtomicInteger();
        VerificationContext context = new VerificationContext(
                VerificationLimits.defaults(),
                (keyId, keyVersion) -> {
                    keyRequests.incrementAndGet();
                    return keyResolution;
                },
                proofId -> {
                    statusRequests.incrementAndGet();
                    return Resolution.resolved(fixture.status());
                },
                query -> {
                    chainRequests.incrementAndGet();
                    return Resolution.resolved(fixture.chain());
                },
                Clock.fixed(VerifierTestFixture.NOW, ZoneOffset.UTC));

        VerificationReport report = verifier.verify(fixture.original(), malformed, context);

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.INVALID);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.SIGNATURE_INVALID);
        assertThat(keyRequests).hasValue(0);
        assertThat(statusRequests).hasValue(0);
        assertThat(chainRequests).hasValue(0);
    }

    /** Requires a focused malformed proof to reduce to INVALID with the expected layer code. */
    private void assertInvalidCode(Path archive, VerificationCode code) {
        VerificationReport report = verifier.verify(
                fixture.original(), archive, VerifierTestFixture.context(fixture));
        assertThat(report.outcome()).isEqualTo(VerificationOutcome.INVALID);
        assertThat(report.checks()).extracting(check -> check.code()).contains(code);
    }

    /** 使用完整可信身份仅替换状态更新时间，并断言时间顺序失败唯一落到当前状态检查。 */
    private void assertStatusTimestampRejected(String updatedAt) {
        PublicProofStatus status = new PublicProofStatus(
                fixture.status().proofId(),
                fixture.status().status(),
                fixture.status().statusVersion(),
                fixture.status().issuedStatus(),
                fixture.status().keyId(),
                fixture.status().keyVersion(),
                fixture.status().reason(),
                fixture.status().issuedAt(),
                updatedAt,
                fixture.status().source());
        VerificationContext context = new VerificationContext(
                VerificationLimits.defaults(),
                (keyId, keyVersion) -> Resolution.resolved(fixture.key()),
                proofId -> Resolution.resolved(status),
                query -> Resolution.resolved(fixture.chain()),
                Clock.fixed(VerifierTestFixture.NOW, ZoneOffset.UTC));

        VerificationReport report = verifier.verify(fixture.original(), fixture.proof(), context);

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.INVALID);
        assertThat(report.checks())
                .filteredOn(check -> "status.current".equals(check.id()))
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo(VerificationCheckStatus.FAIL);
                    assertThat(check.code()).isEqualTo(VerificationCode.STATUS_INVALID);
                });
    }

    /** Requires a stable archive input error for one malformed ZIP path. */
    private void assertArchiveInputError(Path archive) {
        VerificationReport report = verifier.verify(
                fixture.original(), archive, VerifierTestFixture.context(fixture));
        assertThat(report.outcome()).isEqualTo(VerificationOutcome.ERROR);
        assertThat(report.checks()).extracting(check -> check.code())
                .contains(VerificationCode.ARCHIVE_ENTRY_INVALID);
    }
}
