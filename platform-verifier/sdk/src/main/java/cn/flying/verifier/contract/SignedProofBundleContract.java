package cn.flying.verifier.contract;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Frozen signed proof ZIP v2 schemas, archive metadata, policy, and explanatory text.
 */
public final class SignedProofBundleContract {

    public static final String MANIFEST_SCHEMA = "record-platform-proof-manifest.v2";
    public static final String CHUNK_SCHEMA = "record-platform-proof-chunk-manifest.v2";
    public static final String MERKLE_SCHEMA = "record-platform-proof-merkle.v2";
    public static final String CHAIN_SCHEMA = "record-platform-proof-chain-receipt.v2";
    public static final String POLICY_SCHEMA = "record-platform-proof-verification-policy.v2";
    public static final String SOURCE_CHUNK_MANIFEST_SCHEMA = "cn.flying.chunk-manifest.v1";

    public static final String MANIFEST_ENTRY = "manifest.json";
    public static final String FILE_HASH_ENTRY = "file.hash";
    public static final String CHUNK_MANIFEST_ENTRY = "chunk-manifest.json";
    public static final String MERKLE_PROOF_ENTRY = "merkle-proof.json";
    public static final String BLOCKCHAIN_RECEIPT_ENTRY = "blockchain-receipt.json";
    public static final String SIGNATURE_ENTRY = "issuer-signature.jws";
    public static final String VERIFICATION_POLICY_ENTRY = "verification-policy.json";
    public static final String README_ENTRY = "README.verify.md";

    public static final String JSON_MEDIA_TYPE = "application/json";
    public static final String TEXT_MEDIA_TYPE = "text/plain; charset=utf-8";
    public static final String MARKDOWN_MEDIA_TYPE = "text/markdown; charset=utf-8";
    public static final String JWS_MEDIA_TYPE = "application/jose";

    public static final int MAX_ENTRY_BYTES = 1024 * 1024;
    public static final int MAX_TOTAL_ENTRY_BYTES = 4 * 1024 * 1024;
    public static final int MAX_ARCHIVE_BYTES = 5 * 1024 * 1024;
    public static final int MAX_CHUNKS = 128;
    public static final int MAX_PROOF_NODES = 64;
    public static final LocalDateTime FIXED_ZIP_LOCAL_TIME = LocalDateTime.of(1980, 1, 2, 0, 0);

    public static final List<String> ENTRY_ORDER = List.of(
            MANIFEST_ENTRY,
            FILE_HASH_ENTRY,
            CHUNK_MANIFEST_ENTRY,
            MERKLE_PROOF_ENTRY,
            BLOCKCHAIN_RECEIPT_ENTRY,
            SIGNATURE_ENTRY,
            VERIFICATION_POLICY_ENTRY,
            README_ENTRY);

    public static final List<String> EVIDENCE_ENTRY_ORDER = List.of(
            FILE_HASH_ENTRY,
            CHUNK_MANIFEST_ENTRY,
            MERKLE_PROOF_ENTRY,
            BLOCKCHAIN_RECEIPT_ENTRY,
            VERIFICATION_POLICY_ENTRY,
            README_ENTRY);

    public static final String README = """
            # RecordPlatform Signed Proof Bundle v2

            1. Verify `issuer-signature.jws` as compact JWS EdDSA and require its decoded payload to equal `manifest.json` byte-for-byte.
            2. Verify every evidence entry listed by `manifest.json` using its SHA-256 and exact byte length.
            3. Hash the original file with SHA-256 and compare it with `file.hash` and `chunk-manifest.json.contentHash`.
            4. Re-split the original bytes by ordered chunk sizes and compare each `plainHash`; inspect `cipherHash` as stored-object evidence.
            5. Recompute the Merkle leaf from `merkle-proof.json.evidenceHash`, apply the ordered proof path, and compare the result with the batch chain root.
            6. Enforce the signed chain receipt matrix: `batchChainRoot` is exactly 64 hexadecimal characters without `0x`; `CHAIN_WRITE` requires a 64-hex transaction hash with optional `0x`; `CHAIN_QUERY_BEFORE_WRITE` and `CHAIN_QUERY_AFTER_WRITE` require an absent transaction hash.
            7. Validate the immutable `record-platform-contract-registry-entry.v1` Sharing entry, its canonical registry fingerprint, semantic version, chain/group identity, 20-byte address, `ABI-CANONICAL-JSON-SHA256-V1` fingerprints, paired deployment transaction/block, effective time, ACTIVE/DEPRECATED status, and `REDEPLOY_ADDRESS` strategy.
            8. The signed `issuedStatus` is only ACTIVE or SUPERSEDED. Query `statusLocation` for the current ACTIVE, REVOKED, SUPERSEDED, or INVALID state. INVALID is terminal and is used only when a previously persisted canonical manifest, JWS, signing-key identity, or immutable issuance snapshot deterministically drifts; its reason is `immutable_snapshot_validation_failed`. Storage, Merkle, registry, or receipt dependency/read failures reject only the current export and do not change lifecycle state.

            Evidence schemas are `record-platform-proof-chunk-manifest.v2`, `record-platform-proof-merkle.v2`, `record-platform-proof-chain-receipt.v2`, and `record-platform-proof-verification-policy.v2`. `verification-policy.json` is signed evidence and contains the machine-readable exact rules.

            The ZIP contains exactly eight STORED entries in fixed order: `manifest.json`, `file.hash`, `chunk-manifest.json`, `merkle-proof.json`, `blockchain-receipt.json`, `issuer-signature.jws`, `verification-policy.json`, and `README.verify.md`. `manifest.json` hashes the six evidence entries; it does not hash itself or `issuer-signature.jws`. Both `file.hash` and `issuer-signature.jws` contain exactly one trailing LF byte.
            """;

    private SignedProofBundleContract() {
    }

    /**
     * Returns the exact signed verification policy supported by producer and verifier v2.
     *
     * @return immutable verification policy
     */
    public static SignedProofBundleModel.VerificationPolicyEvidence expectedVerificationPolicy() {
        return new SignedProofBundleModel.VerificationPolicyEvidence(
                POLICY_SCHEMA,
                List.of(CHUNK_SCHEMA, MERKLE_SCHEMA, CHAIN_SCHEMA, POLICY_SCHEMA),
                "SHA-256",
                "file.hash = 'sha256:' + lowercase(hex(sha256(originalFileBytes)))",
                "manifestHash = 'sha256:' + lowercase(hex(sha256(canonical source manifest JSON)))",
                "lowercase(hex(sha256(utf8('leaf\\n' + evidenceHash.trim()))))",
                "lowercase(hex(sha256(utf8('node\\n' + leftHash.trim() + '\\n' + rightHash.trim()))))",
                "apply proofPath from leaf to root; LEFT prepends sibling and RIGHT appends sibling",
                new SignedProofBundleModel.ChainReceiptPolicy(
                        "^[0-9A-Fa-f]{64}$",
                        "^(?:0x)?[0-9A-Fa-f]{64}$",
                        "CHAIN_WRITE",
                        List.of("CHAIN_QUERY_BEFORE_WRITE", "CHAIN_QUERY_AFTER_WRITE"),
                        "write source requires a matching transaction hash",
                        "query sources require transaction hash to be absent"),
                new SignedProofBundleModel.ContractRegistryPolicy(
                        "record-platform-contract-registry-entry.v1",
                        "Sharing",
                        "^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?$",
                        List.of("LOCAL_FISCO", "BSN_FISCO", "BSN_BESU"),
                        List.of("LOCAL_FISCO", "BSN_FISCO"),
                        "groupId is required for FISCO chain types and absent for BSN_BESU",
                        "^0x[0-9a-f]{40}$",
                        "ABI-CANONICAL-JSON-SHA256-V1",
                        "^sha256:[0-9a-f]{64}$",
                        "deploymentTransactionHash and non-negative deploymentBlockNumber are both present or both absent",
                        List.of("ACTIVE", "DEPRECATED"),
                        "RFC3339 offset date-time not later than verification time",
                        "REDEPLOY_ADDRESS",
                        "'sha256:' + lowercase(hex(sha256(utf8(join ordered field=value lines with LF))))",
                        List.of(
                                "schemaVersion",
                                "contractName",
                                "semanticVersion",
                                "chainType",
                                "chainId",
                                "groupId",
                                "contractAddress",
                                "abiFingerprintAlgorithm",
                                "abiSha256",
                                "artifactBytecodeSha256",
                                "onChainCodeSha256",
                                "deploymentTransactionHash",
                                "deploymentBlockNumber",
                                "status",
                                "effectiveAt",
                                "upgradeStrategy")),
                "JWS compact serialization with EdDSA; decoded payload must equal manifest.json bytes",
                "issuedStatus is ACTIVE or SUPERSEDED; current status comes from statusLocation; INVALID is terminal only for deterministic persisted immutable snapshot drift with reason immutable_snapshot_validation_failed; dependency/read validation failures do not change lifecycle state",
                "exactly eight STORED entries, fixed order, fixed timestamp, no nested or additional entries",
                "UTF-8 unless otherwise stated; file.hash and ASCII issuer-signature.jws each end with exactly one LF byte");
    }

    /**
     * Returns the media type expected for one manifest-bound evidence entry.
     *
     * @param entryName fixed evidence entry name
     * @return expected media type, or {@code null} for an unknown entry
     */
    public static String mediaTypeForEvidenceEntry(String entryName) {
        return switch (entryName) {
            case FILE_HASH_ENTRY -> TEXT_MEDIA_TYPE;
            case CHUNK_MANIFEST_ENTRY, MERKLE_PROOF_ENTRY,
                    BLOCKCHAIN_RECEIPT_ENTRY, VERIFICATION_POLICY_ENTRY -> JSON_MEDIA_TYPE;
            case README_ENTRY -> MARKDOWN_MEDIA_TYPE;
            default -> null;
        };
    }
}
