package cn.flying.verifier.crypto;

import cn.flying.verifier.VerificationLimits;
import cn.flying.verifier.contract.SignedProofBundleContract;
import cn.flying.verifier.contract.SignedProofBundleModel;
import cn.flying.verifier.model.PublicSigningKey;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Primitive security tests for strict JSON, constant-format hashes, Merkle rules, and hard limits.
 */
class CanonicalJsonSecurityTest {

    /** Rejects duplicate, unknown, trailing, scalar, and oversized JSON inputs. */
    @Test
    void shouldEnforceStrictBoundedJsonParsing() {
        CanonicalJson json = new CanonicalJson();
        String validKey = """
                {"algorithm":"EdDSA","keyId":"key","keyVersion":1,
                "publicKeyFingerprint":"sha256:%s","publicKeySpki":"AA==","source":"test"}
                """.formatted("a".repeat(64)).replace("\n", "");

        assertThat(json.read(validKey.getBytes(StandardCharsets.UTF_8), PublicSigningKey.class).keyId())
                .isEqualTo("key");
        assertThatThrownBy(() -> json.read(
                (validKey.substring(0, validKey.length() - 1) + ",\"unknown\":1}")
                        .getBytes(StandardCharsets.UTF_8),
                PublicSigningKey.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> json.read(
                "{\"keyId\":\"a\",\"keyId\":\"b\"}".getBytes(StandardCharsets.UTF_8),
                PublicSigningKey.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> json.read(
                (validKey + " {}").getBytes(StandardCharsets.UTF_8),
                PublicSigningKey.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> json.read("null".getBytes(StandardCharsets.UTF_8), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> json.read(
                new byte[CanonicalJson.MAX_DOCUMENT_BYTES + 1], PublicSigningKey.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Enforces parser nesting, string-token, and number-token limits below the document byte cap. */
    @Test
    void shouldRejectDeepLongStringAndLongNumberTokens() {
        CanonicalJson json = new CanonicalJson();
        String deep = "[".repeat(CanonicalJson.MAX_NESTING_DEPTH + 1)
                + "0"
                + "]".repeat(CanonicalJson.MAX_NESTING_DEPTH + 1);
        String longString = "{\"value\":\""
                + "x".repeat(CanonicalJson.MAX_STRING_LENGTH + 1)
                + "\"}";
        String longNumber = "{\"value\":"
                + "1".repeat(CanonicalJson.MAX_NUMBER_LENGTH + 1)
                + "}";

        assertThatThrownBy(() -> json.read(deep.getBytes(StandardCharsets.UTF_8), Object.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> json.read(longString.getBytes(StandardCharsets.UTF_8), Object.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> json.read(longNumber.getBytes(StandardCharsets.UTF_8), Object.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Produces deterministic alphabetical object and map ordering. */
    @Test
    void shouldSerializeCanonicalJsonDeterministically() {
        CanonicalJson json = new CanonicalJson();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("z", 1);
        value.put("a", List.of("x"));
        byte[] canonical = json.canonicalBytes(value);

        assertThat(new String(canonical, StandardCharsets.UTF_8)).isEqualTo("{\"a\":[\"x\"],\"z\":1}");
        assertThat(json.isCanonical(canonical, value)).isTrue();
        assertThat(json.isCanonical((" " + new String(canonical, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8), value)).isFalse();
        assertThat(json.mapperCopy()).isNotNull();
    }

    /** Normalizes only valid SHA-256 text and rejects null digest inputs. */
    @Test
    void shouldNormalizeAndCompareHashesSafely() {
        String digest = ProofHashes.sha256("fixture");

        assertThat(ProofHashes.normalizeSha256(digest.substring(7).toUpperCase()))
                .isEqualTo(digest);
        assertThat(ProofHashes.equalsSha256(digest, "  " + digest.toUpperCase() + "  ")).isTrue();
        assertThat(ProofHashes.equalsSha256(digest, "bad")).isFalse();
        assertThat(ProofHashes.normalizeSha256(null)).isNull();
        assertThat(ProofHashes.normalizeSha256("a".repeat(257))).isNull();
        assertThatThrownBy(() -> ProofHashes.sha256((byte[]) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProofHashes.sha256((String) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ProofHashes.formatDigest(new byte[32]))
                .isEqualTo("sha256:" + "0".repeat(64));
        assertThatThrownBy(() -> ProofHashes.formatDigest(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProofHashes.formatDigest(new byte[31]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Applies ordered Merkle siblings and rejects malformed nodes or positions. */
    @Test
    void shouldApplyOnlyValidMerklePaths() {
        String evidence = ProofHashes.sha256("manifest");
        String leaf = MerkleProofs.calculateLeafHash(evidence);
        String sibling = "a".repeat(64);
        String expected = MerkleProofs.calculateParentHash(leaf, sibling);

        assertThat(MerkleProofs.calculateRootFromProof(
                leaf, List.of(new SignedProofBundleModel.ProofNode("RIGHT", sibling))))
                .isEqualTo(expected);
        assertThat(MerkleProofs.calculateRootFromProof(
                leaf, List.of(new SignedProofBundleModel.ProofNode("RIGHT", "  " + sibling.toUpperCase() + "  "))))
                .isNull();
        assertThat(MerkleProofs.calculateRootFromProof(
                leaf, List.of(new SignedProofBundleModel.ProofNode("MIDDLE", sibling))))
                .isNull();
        assertThat(MerkleProofs.calculateRootFromProof("bad", List.of())).isNull();
        assertThat(MerkleProofs.calculateRootFromProof(
                leaf, List.of(new SignedProofBundleModel.ProofNode("RIGHT", "a".repeat(129)))))
                .isNull();
        assertThatThrownBy(() -> MerkleProofs.calculateLeafHash(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Rejects limits that are non-positive or exceed the frozen proof contract. */
    @Test
    void shouldRejectInvalidVerificationLimits() {
        assertThat(VerificationLimits.defaults().maxArchiveBytes())
                .isEqualTo(SignedProofBundleContract.MAX_ARCHIVE_BYTES);
        assertThatThrownBy(() -> new VerificationLimits(
                0,
                SignedProofBundleContract.MAX_ARCHIVE_BYTES,
                SignedProofBundleContract.MAX_ENTRY_BYTES,
                SignedProofBundleContract.MAX_TOTAL_ENTRY_BYTES,
                SignedProofBundleContract.MAX_CHUNKS,
                SignedProofBundleContract.MAX_PROOF_NODES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VerificationLimits(
                1,
                SignedProofBundleContract.MAX_ARCHIVE_BYTES + 1L,
                SignedProofBundleContract.MAX_ENTRY_BYTES,
                SignedProofBundleContract.MAX_TOTAL_ENTRY_BYTES,
                SignedProofBundleContract.MAX_CHUNKS,
                SignedProofBundleContract.MAX_PROOF_NODES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Keeps producer and verifier on one exact policy object and media-type mapping. */
    @Test
    void shouldExposeFrozenSignedContract() {
        assertThat(SignedProofBundleContract.expectedVerificationPolicy().schemaVersion())
                .isEqualTo(SignedProofBundleContract.POLICY_SCHEMA);
        assertThat(SignedProofBundleContract.mediaTypeForEvidenceEntry(
                SignedProofBundleContract.FILE_HASH_ENTRY))
                .isEqualTo(SignedProofBundleContract.TEXT_MEDIA_TYPE);
        assertThat(SignedProofBundleContract.mediaTypeForEvidenceEntry("unknown")).isNull();
    }

    /** Keeps shared contract lists as one immutable construction-time snapshot. */
    @Test
    void shouldDefensivelyCopyContractLists() {
        java.util.ArrayList<SignedProofBundleModel.ProofNode> source = new java.util.ArrayList<>();
        SignedProofBundleModel.ProofNode node =
                new SignedProofBundleModel.ProofNode("RIGHT", "a".repeat(64));
        source.add(node);
        SignedProofBundleModel.MerkleProofEvidence evidence =
                new SignedProofBundleModel.MerkleProofEvidence(
                        "schema", "type", "hash", "algorithm", "root", "leaf", 0, source);
        source.clear();

        List<SignedProofBundleModel.ProofNode> firstRead = evidence.proofPath();
        List<SignedProofBundleModel.ProofNode> secondRead = evidence.proofPath();
        assertThat(firstRead).containsExactly(node);
        assertThat(secondRead).isSameAs(firstRead);
        assertThatThrownBy(firstRead::clear)
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
