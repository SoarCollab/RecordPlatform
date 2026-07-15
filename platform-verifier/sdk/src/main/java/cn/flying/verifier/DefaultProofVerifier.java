package cn.flying.verifier;

import cn.flying.verifier.contract.SignedProofBundleContract;
import cn.flying.verifier.contract.SignedProofBundleModel;
import cn.flying.verifier.crypto.CanonicalJson;
import cn.flying.verifier.crypto.ContractRegistryFingerprint;
import cn.flying.verifier.crypto.MerkleProofs;
import cn.flying.verifier.crypto.ProofHashes;
import cn.flying.verifier.internal.ParsedProofArchive;
import cn.flying.verifier.internal.ProofArchiveReader;
import cn.flying.verifier.internal.ProofFormatException;
import cn.flying.verifier.internal.VerificationAccumulator;
import cn.flying.verifier.internal.VerificationSummaryBuilder;
import cn.flying.verifier.model.ChainQuery;
import cn.flying.verifier.model.ChainRootEvidence;
import cn.flying.verifier.model.PublicProofStatus;
import cn.flying.verifier.model.PublicSigningKey;
import cn.flying.verifier.model.VerificationCode;
import cn.flying.verifier.model.VerificationOutcome;
import cn.flying.verifier.model.VerificationReport;
import cn.flying.verifier.resolver.Resolution;
import cn.flying.verifier.resolver.ResolutionState;
import cn.flying.verifier.resolver.TrustedEvidenceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Default Spring-free signed proof ZIP v2 verification engine.
 */
public final class DefaultProofVerifier implements ProofVerifier {

    private static final Pattern PROOF_ID = Pattern.compile("^rp-proof-[0-9a-f]{64}$");
    private static final Pattern EXTERNAL_ID = Pattern.compile("^[A-Za-z0-9_-]{1,192}$");
    private static final Pattern KEY_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final Pattern SEMANTIC_VERSION = Pattern.compile(
            "^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?$");
    private static final Pattern ADDRESS = Pattern.compile("^0x[0-9a-f]{40}$");
    private static final Pattern TRANSACTION_HASH = Pattern.compile("^(?:0x)?[0-9A-Fa-f]{64}$");
    private static final Pattern BATCH_NO = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final Set<String> ALLOWED_CHAIN_TYPES = Set.of("LOCAL_FISCO", "BSN_FISCO", "BSN_BESU");
    private static final Set<String> FISCO_CHAIN_TYPES = Set.of("LOCAL_FISCO", "BSN_FISCO");
    private static final Set<String> ISSUED_STATUSES = Set.of("ACTIVE", "SUPERSEDED");
    private static final Set<String> REGISTRY_STATUSES = Set.of("ACTIVE", "DEPRECATED");
    private static final int STREAM_BUFFER_BYTES = 64 * 1024;

    private final CanonicalJson canonicalJson;
    private final ProofArchiveReader archiveReader;

    /** Creates the default strict verifier. */
    public DefaultProofVerifier() {
        this(new CanonicalJson(), new ProofArchiveReader());
    }

    /** Creates a verifier with explicit shared parser components for deterministic tests. */
    DefaultProofVerifier(CanonicalJson canonicalJson, ProofArchiveReader archiveReader) {
        this.canonicalJson = Objects.requireNonNull(canonicalJson);
        this.archiveReader = Objects.requireNonNull(archiveReader);
    }

    /**
     * Verifies archive structure, signed evidence, original bytes, live chain root, and current status.
     */
    @Override
    public VerificationReport verify(Path originalFile, Path proofArchive, VerificationContext suppliedContext) {
        VerificationContext context = suppliedContext == null ? VerificationContext.offline() : suppliedContext;
        VerificationAccumulator checks = new VerificationAccumulator();
        VerificationSummaryBuilder summary = new VerificationSummaryBuilder();
        Instant verifiedAt = Instant.EPOCH;
        try {
            verifiedAt = Objects.requireNonNull(context.clock().instant(), "Verification clock returned null");
            ParsedProofArchive archive = archiveReader.read(proofArchive, context.limits());
            checks.pass("archive.structure", "archive", VerificationCode.ARCHIVE_VALID,
                    "The proof archive satisfies the fixed eight-entry STORED ZIP contract");

            ParsedEvidence evidence = parseEvidence(archive, checks);
            populateSummary(evidence, summary);
            validateManifest(evidence.manifest(), checks, verifiedAt);
            validateEvidenceDigests(archive, evidence.manifest(), checks);
            validatePolicyAndReadme(evidence, archive, checks);
            boolean chunkManifestValid = validateChunkManifestShape(evidence, context.limits(), checks);
            if (chunkManifestValid) {
                verifyOriginalFile(originalFile, evidence, context.limits(), checks, summary);
            } else {
                checks.indeterminate(
                        "content.original",
                        "content",
                        VerificationCode.CHUNK_MANIFEST_INVALID,
                        "Original-file hashing was skipped because signed chunk evidence is invalid");
            }
            verifyMerkle(evidence, context.limits(), checks, summary);
            validateChainReceipt(evidence, checks, summary, verifiedAt);
            if (checks.outcome() != VerificationOutcome.VALID) {
                markTrustChecksSkipped(
                        checks,
                        true,
                        "Trust resolution was skipped because local proof prerequisites failed");
            } else if (verifySignature(evidence, archive, context, checks, summary)) {
                resolveCurrentStatus(evidence.manifest(), context, checks, summary, verifiedAt);
                resolveLiveChain(evidence, context, checks, summary);
            } else {
                markTrustChecksSkipped(
                        checks,
                        false,
                        "Online trust resolution was skipped because the proof signature was not trusted");
            }
        } catch (ProofFormatException e) {
            checks.error("input.format", "input", e.code(), e.getMessage());
        } catch (RuntimeException e) {
            checks.error("verifier.internal", "verifier", VerificationCode.INTERNAL_ERROR,
                    "The verifier could not safely complete this proof");
        }
        return new VerificationReport(
                VerificationReport.SCHEMA_VERSION,
                checks.outcome(),
                verifiedAt.toString(),
                VerificationReport.VERIFIER_VERSION,
                summary.build(),
                checks.checks());
    }

    /** Strictly parses all JSON entries and records canonical byte mismatches. */
    private ParsedEvidence parseEvidence(ParsedProofArchive archive, VerificationAccumulator checks) {
        SignedProofBundleModel.Manifest manifest = parseCanonical(
                archive, SignedProofBundleContract.MANIFEST_ENTRY,
                SignedProofBundleModel.Manifest.class, checks);
        SignedProofBundleModel.ChunkManifestEvidence chunkManifest = parseCanonical(
                archive, SignedProofBundleContract.CHUNK_MANIFEST_ENTRY,
                SignedProofBundleModel.ChunkManifestEvidence.class, checks);
        SignedProofBundleModel.MerkleProofEvidence merkleProof = parseCanonical(
                archive, SignedProofBundleContract.MERKLE_PROOF_ENTRY,
                SignedProofBundleModel.MerkleProofEvidence.class, checks);
        SignedProofBundleModel.BlockchainReceiptEvidence chainReceipt = parseCanonical(
                archive, SignedProofBundleContract.BLOCKCHAIN_RECEIPT_ENTRY,
                SignedProofBundleModel.BlockchainReceiptEvidence.class, checks);
        SignedProofBundleModel.VerificationPolicyEvidence policy = parseCanonical(
                archive, SignedProofBundleContract.VERIFICATION_POLICY_ENTRY,
                SignedProofBundleModel.VerificationPolicyEvidence.class, checks);
        return new ParsedEvidence(
                manifest,
                chunkManifest,
                merkleProof,
                chainReceipt,
                policy,
                archive.required(SignedProofBundleContract.FILE_HASH_ENTRY));
    }

    /** Parses one strict JSON entry and compares it with its canonical reserialization. */
    private <T> T parseCanonical(
            ParsedProofArchive archive,
            String entryName,
            Class<T> type,
            VerificationAccumulator checks
    ) {
        byte[] bytes = archive.required(entryName);
        try {
            T parsed = canonicalJson.read(bytes, type);
            if (parsed == null) {
                throw new IllegalArgumentException("Proof JSON document must contain an object");
            }
            if (canonicalJson.isCanonical(bytes, parsed)) {
                checks.pass("json." + entryName, "json", VerificationCode.ARCHIVE_VALID,
                        entryName + " is strict canonical JSON");
            } else {
                checks.fail("json." + entryName, "json", VerificationCode.JSON_NON_CANONICAL,
                        entryName + " is not in the signed canonical JSON form");
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            throw new ProofFormatException(
                    VerificationCode.JSON_INVALID,
                    entryName + " is not valid bounded strict JSON",
                    e);
        }
    }

    /** Copies safe manifest and evidence identifiers into the report summary. */
    private void populateSummary(ParsedEvidence evidence, VerificationSummaryBuilder summary) {
        SignedProofBundleModel.Manifest manifest = evidence.manifest();
        summary.proofId = manifest.proofId();
        summary.manifestSchema = manifest.schemaVersion();
        summary.fileId = manifest.fileId();
        summary.fileVersion = manifest.fileVersion();
        summary.leafId = manifest.leafId();
        summary.batchNo = manifest.batchNo();
        summary.issuedAt = manifest.issuedAt();
        summary.issuedStatus = manifest.issuedStatus();
        if (manifest.signature() != null) {
            summary.keyId = manifest.signature().keyId();
            summary.keyVersion = manifest.signature().keyVersion();
            summary.keyFingerprint = manifest.signature().publicKeyFingerprint();
        }
        summary.contentHash = evidence.chunkManifest().contentHash();
        summary.merkleRoot = evidence.merkleProof().merkleRoot();
        SignedProofBundleModel.ContractRegistryEvidence registry = evidence.chainReceipt().contractRegistry();
        if (registry != null) {
            summary.chainType = registry.chainType();
            summary.chainId = registry.chainId();
            summary.groupId = registry.groupId();
            summary.contractAddress = registry.contractAddress();
            summary.contractVersion = registry.semanticVersion();
            summary.abiFingerprint = registry.abiFingerprint();
        }
        summary.batchTransactionHash = evidence.chainReceipt().batchTransactionHash();
    }

    /** Validates top-level identifiers, lifecycle fields, locations, and issuance time. */
    private void validateManifest(
            SignedProofBundleModel.Manifest manifest,
            VerificationAccumulator checks,
            Instant now
    ) {
        boolean valid = manifest != null
                && SignedProofBundleContract.MANIFEST_SCHEMA.equals(manifest.schemaVersion())
                && matches(PROOF_ID, manifest.proofId())
                && matches(EXTERNAL_ID, manifest.fileId())
                && manifest.fileVersion() != null
                && manifest.fileVersion() > 0
                && matches(EXTERNAL_ID, manifest.leafId())
                && matches(BATCH_NO, manifest.batchNo())
                && ISSUED_STATUSES.contains(manifest.issuedStatus())
                && validateIssuedAt(manifest.issuedAt(), now)
                && expectedStatusLocation(manifest).equals(manifest.statusLocation())
                && validateManifestSignatureMetadata(manifest);
        if (valid) {
            checks.pass("manifest.contract", "manifest", VerificationCode.ARCHIVE_VALID,
                    "Manifest schema, identifiers, issuance time, status, and resolver locations are valid");
        } else {
            checks.fail("manifest.contract", "manifest", VerificationCode.MANIFEST_INVALID,
                    "Manifest schema, identifiers, issuance time, status, or resolver locations are invalid");
        }
    }

    /** Validates signing metadata and the exact relative key discovery path. */
    private boolean validateManifestSignatureMetadata(SignedProofBundleModel.Manifest manifest) {
        SignedProofBundleModel.SignatureMetadata signature = manifest.signature();
        if (signature == null
                || !"EdDSA".equals(signature.algorithm())
                || !matches(KEY_ID, signature.keyId())
                || signature.keyVersion() == null
                || signature.keyVersion() <= 0
                || !ProofHashes.PREFIXED_SHA256.matcher(orEmpty(signature.publicKeyFingerprint())).matches()) {
            return false;
        }
        String expected = "/api/v1/public/proof-keys/" + signature.keyId()
                + "/versions/" + signature.keyVersion();
        return expected.equals(signature.verificationKeyLocation());
    }

    /** Validates the six exact manifest evidence digest entries against archive bytes. */
    private void validateEvidenceDigests(
            ParsedProofArchive archive,
            SignedProofBundleModel.Manifest manifest,
            VerificationAccumulator checks
    ) {
        List<SignedProofBundleModel.EntryDigest> digests = manifest.entries();
        boolean valid = digests != null && digests.size() == SignedProofBundleContract.EVIDENCE_ENTRY_ORDER.size();
        if (valid) {
            for (int index = 0; index < digests.size(); index++) {
                SignedProofBundleModel.EntryDigest digest = digests.get(index);
                String expectedName = SignedProofBundleContract.EVIDENCE_ENTRY_ORDER.get(index);
                byte[] bytes = archive.required(expectedName);
                valid = digest != null
                        && expectedName.equals(digest.name())
                        && Objects.equals(
                                SignedProofBundleContract.mediaTypeForEvidenceEntry(expectedName),
                                digest.mediaType())
                        && ProofHashes.PREFIXED_SHA256.matcher(orEmpty(digest.sha256())).matches()
                        && digest.required()
                        && digest.present()
                        && digest.size() == bytes.length
                        && ProofHashes.equalsSha256(digest.sha256(), ProofHashes.sha256(bytes));
                if (!valid) {
                    break;
                }
            }
        }
        if (valid) {
            checks.pass("manifest.entry-digests", "manifest", VerificationCode.ARCHIVE_VALID,
                    "Every signed evidence length and SHA-256 matches the exact archive bytes");
        } else {
            checks.fail("manifest.entry-digests", "manifest",
                    VerificationCode.ARCHIVE_ENTRY_DIGEST_MISMATCH,
                    "One or more signed evidence lengths or SHA-256 digests do not match");
        }
    }

    /** Requires the one supported signed policy and exact explanatory README. */
    private void validatePolicyAndReadme(
            ParsedEvidence evidence,
            ParsedProofArchive archive,
            VerificationAccumulator checks
    ) {
        boolean policyValid = SignedProofBundleContract.expectedVerificationPolicy().equals(evidence.policy());
        if (policyValid) {
            checks.pass("policy.contract", "policy", VerificationCode.ARCHIVE_VALID,
                    "The signed verification policy is the supported v2 policy");
        } else {
            checks.fail("policy.contract", "policy", VerificationCode.UNSUPPORTED_POLICY,
                    "The signed verification policy is unknown or has drifted");
        }
        byte[] expectedReadme = SignedProofBundleContract.README.getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(expectedReadme, archive.required(SignedProofBundleContract.README_ENTRY))) {
            checks.pass("policy.readme", "policy", VerificationCode.ARCHIVE_VALID,
                    "README.verify.md matches the supported signed verification instructions");
        } else {
            checks.fail("policy.readme", "policy", VerificationCode.UNSUPPORTED_POLICY,
                    "README.verify.md does not match the supported signed instructions");
        }
    }

    /** Validates chunk schema, identities, hash fields, order, count, and overflow-safe total size. */
    private boolean validateChunkManifestShape(
            ParsedEvidence evidence,
            VerificationLimits limits,
            VerificationAccumulator checks
    ) {
        SignedProofBundleModel.ChunkManifestEvidence chunk = evidence.chunkManifest();
        SignedProofBundleModel.Manifest manifest = evidence.manifest();
        boolean valid = chunk != null
                && SignedProofBundleContract.CHUNK_SCHEMA.equals(chunk.schemaVersion())
                && Objects.equals(manifest.fileId(), chunk.fileId())
                && Objects.equals(manifest.fileVersion(), chunk.fileVersion())
                && ProofHashes.PREFIXED_SHA256.matcher(orEmpty(chunk.contentHash())).matches()
                && ProofHashes.PREFIXED_SHA256.matcher(orEmpty(chunk.manifestHash())).matches()
                && hasBoundedText(chunk.chainRecordId(), 256)
                && SignedProofBundleContract.SOURCE_CHUNK_MANIFEST_SCHEMA.equals(chunk.sourceSchema())
                && ProofHashes.HASH_ALGORITHM.equals(chunk.hashAlgorithm())
                && chunk.chunkSize() > 0
                && chunk.chunkCount() != null
                && chunk.chunkCount() > 0
                && chunk.chunkCount() <= limits.maxChunks()
                && chunk.totalSize() > 0
                && hasBoundedText(chunk.encryptionAlgorithm(), 128)
                && hasBoundedText(chunk.storageBackend(), 128)
                && chunk.chunks() != null
                && chunk.chunks().size() == chunk.chunkCount();
        long total = 0L;
        if (valid) {
            try {
                for (int index = 0; index < chunk.chunks().size(); index++) {
                    SignedProofBundleModel.ChunkEvidence item = chunk.chunks().get(index);
                    valid = item != null
                            && item.index() == index
                            && item.size() > 0
                            && item.size() <= chunk.chunkSize()
                            && (index == chunk.chunks().size() - 1 || item.size() == chunk.chunkSize())
                            && ProofHashes.PREFIXED_SHA256.matcher(orEmpty(item.plainHash())).matches()
                            && ProofHashes.PREFIXED_SHA256.matcher(orEmpty(item.cipherHash())).matches()
                            && hasBoundedText(item.objectPath(), 2048)
                            && !containsControlCharacter(item.objectPath())
                            && ProofHashes.HASH_ALGORITHM.equals(item.checksumAlgorithm());
                    if (!valid) {
                        break;
                    }
                    total = Math.addExact(total, item.size());
                }
            } catch (ArithmeticException e) {
                valid = false;
            }
        }
        valid = valid && total == chunk.totalSize();
        if (valid) {
            checks.pass("chunks.contract", "content", VerificationCode.CHUNK_MANIFEST_VALID,
                    "Chunk evidence is ordered, bounded, and size-consistent",
                    Map.of("chunkCount", String.valueOf(chunk.chunkCount()),
                            "totalSize", String.valueOf(chunk.totalSize())));
        } else {
            checks.fail("chunks.contract", "content", VerificationCode.CHUNK_MANIFEST_INVALID,
                    "Chunk evidence schema, hashes, order, count, or total size is invalid");
        }
        return valid;
    }

    /** Streams the original file once while computing whole-file and per-chunk SHA-256 values. */
    private void verifyOriginalFile(
            Path originalFile,
            ParsedEvidence evidence,
            VerificationLimits limits,
            VerificationAccumulator checks,
            VerificationSummaryBuilder summary
    ) {
        if (originalFile == null
                || Files.isSymbolicLink(originalFile)
                || !Files.isRegularFile(originalFile, LinkOption.NOFOLLOW_LINKS)) {
            checks.error("content.input", "content", VerificationCode.VERIFICATION_IO_ERROR,
                    "Original input must be a regular non-symbolic-link file");
            return;
        }
        SignedProofBundleModel.ChunkManifestEvidence chunk = evidence.chunkManifest();
        if (chunk.chunks() == null || chunk.chunks().isEmpty()) {
            return;
        }
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = Files.newByteChannel(originalFile, options);
             InputStream input = Channels.newInputStream(channel)) {
            long fileSize = channel.size();
            if (fileSize > limits.maxOriginalFileBytes()) {
                checks.error("content.limit", "content", VerificationCode.FILE_TOO_LARGE,
                        "Original file exceeds the configured verification byte limit");
                return;
            }
            if (fileSize != chunk.totalSize()) {
                checks.fail("content.size", "content", VerificationCode.FILE_HASH_MISMATCH,
                        "Original file size does not match signed chunk evidence");
                return;
            }
            MessageDigest wholeDigest = ProofHashes.newDigest();
            boolean chunksMatch = true;
            byte[] buffer = new byte[STREAM_BUFFER_BYTES];
            for (SignedProofBundleModel.ChunkEvidence item : chunk.chunks()) {
                MessageDigest chunkDigest = ProofHashes.newDigest();
                long remaining = item.size();
                while (remaining > 0) {
                    int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) {
                        chunksMatch = false;
                        break;
                    }
                    wholeDigest.update(buffer, 0, read);
                    chunkDigest.update(buffer, 0, read);
                    remaining -= read;
                }
                if (remaining != 0
                        || !ProofHashes.equalsSha256(item.plainHash(),
                        ProofHashes.formatDigest(chunkDigest.digest()))) {
                    chunksMatch = false;
                }
            }
            if (input.read() != -1) {
                chunksMatch = false;
            }
            String computed = ProofHashes.formatDigest(wholeDigest.digest());
            summary.computedContentHash = computed;
            String fileHashEntry = parseFileHashEntry(evidence.archiveFileHashBytes());
            boolean fullHashMatches = ProofHashes.equalsSha256(computed, fileHashEntry)
                    && ProofHashes.equalsSha256(computed, chunk.contentHash());
            if (fullHashMatches) {
                checks.pass("content.file-hash", "content", VerificationCode.FILE_HASH_VALID,
                        "Original file SHA-256 matches file.hash and chunk-manifest contentHash");
            } else {
                checks.fail("content.file-hash", "content", VerificationCode.FILE_HASH_MISMATCH,
                        "Original file SHA-256 does not match signed content evidence");
            }
            if (chunksMatch) {
                checks.pass("content.chunk-hashes", "content", VerificationCode.CHUNK_MANIFEST_VALID,
                        "Every original-file chunk SHA-256 matches its signed plainHash");
            } else {
                checks.fail("content.chunk-hashes", "content", VerificationCode.CHUNK_HASH_MISMATCH,
                        "One or more original-file chunks do not match signed plainHash evidence");
            }
        } catch (ProofFormatException e) {
            checks.fail("content.file-hash-entry", "content", e.code(), e.getMessage());
        } catch (IOException e) {
            checks.error("content.io", "content", VerificationCode.VERIFICATION_IO_ERROR,
                    "Original file could not be read completely");
        }
    }

    /** Validates the Merkle evidence hash, leaf, ordered path, and declared root. */
    private void verifyMerkle(
            ParsedEvidence evidence,
            VerificationLimits limits,
            VerificationAccumulator checks,
            VerificationSummaryBuilder summary
    ) {
        SignedProofBundleModel.MerkleProofEvidence merkle = evidence.merkleProof();
        boolean valid = merkle != null
                && SignedProofBundleContract.MERKLE_SCHEMA.equals(merkle.schemaVersion())
                && "MANIFEST_HASH".equals(merkle.evidenceType())
                && ProofHashes.PREFIXED_SHA256.matcher(orEmpty(merkle.evidenceHash())).matches()
                && ProofHashes.PREFIXED_SHA256.matcher(
                orEmpty(evidence.chunkManifest().manifestHash())).matches()
                && ProofHashes.equalsSha256(merkle.evidenceHash(), evidence.chunkManifest().manifestHash())
                && MerkleProofs.PROOF_ALGORITHM.equals(merkle.proofAlgorithm())
                && ProofHashes.RAW_SHA256.matcher(orEmpty(merkle.leafHash())).matches()
                && ProofHashes.RAW_SHA256.matcher(orEmpty(merkle.merkleRoot())).matches()
                && merkle.leafIndex() != null
                && merkle.leafIndex() >= 0
                && merkle.proofPath() != null
                && merkle.proofPath().size() <= limits.maxProofNodes()
                && proofPathMatchesLeafIndex(merkle.leafIndex(), merkle.proofPath());
        String computedRoot = null;
        if (valid) {
            String computedLeaf = MerkleProofs.calculateLeafHash(merkle.evidenceHash());
            computedRoot = MerkleProofs.calculateRootFromProof(computedLeaf, merkle.proofPath());
            valid = computedLeaf.equals(merkle.leafHash())
                    && computedRoot != null
                    && computedRoot.equals(merkle.merkleRoot());
        }
        summary.computedMerkleRoot = computedRoot;
        if (valid) {
            checks.pass("merkle.path", "merkle", VerificationCode.MERKLE_PROOF_VALID,
                    "Merkle leaf and ordered proof path reproduce the signed root");
        } else {
            checks.fail("merkle.path", "merkle", VerificationCode.MERKLE_PROOF_INVALID,
                    "Merkle evidence, leaf, proof path, or root is invalid");
        }
    }

    /** Validates signed receipt source semantics and immutable registry identity/fingerprint. */
    private void validateChainReceipt(
            ParsedEvidence evidence,
            VerificationAccumulator checks,
            VerificationSummaryBuilder summary,
            Instant now
    ) {
        SignedProofBundleModel.BlockchainReceiptEvidence receipt = evidence.chainReceipt();
        boolean receiptValid = receipt != null
                && SignedProofBundleContract.CHAIN_SCHEMA.equals(receipt.schemaVersion())
                && hasBoundedText(receipt.chainRecordId(), 256)
                && Objects.equals(receipt.chainRecordId(), evidence.chunkManifest().chainRecordId())
                && ProofHashes.RAW_SHA256.matcher(orEmpty(receipt.batchChainRoot())).matches()
                && receipt.batchChainRoot().equals(evidence.merkleProof().merkleRoot())
                && validOptionalTransactionHash(receipt.fileTransactionHash())
                && validConfirmationMatrix(receipt);
        if (receiptValid) {
            checks.pass("chain.receipt", "chain", VerificationCode.CHAIN_RECEIPT_VALID,
                    "Signed batch receipt source, transaction, chain record, and root are consistent");
        } else {
            checks.fail("chain.receipt", "chain", VerificationCode.CHAIN_RECEIPT_INVALID,
                    "Signed batch receipt source, transaction, chain record, or root is invalid");
        }

        boolean registryValid = validateRegistry(receipt.contractRegistry(), now);
        if (registryValid) {
            checks.pass("chain.registry", "chain", VerificationCode.CONTRACT_REGISTRY_VALID,
                    "Immutable Sharing contract registry snapshot and fingerprint are valid");
        } else {
            checks.fail("chain.registry", "chain", VerificationCode.CONTRACT_REGISTRY_INVALID,
                    "Immutable Sharing contract registry snapshot or fingerprint is invalid");
        }
        if (receipt.contractRegistry() != null) {
            summary.contractVersion = receipt.contractRegistry().semanticVersion();
        }
    }

    /** Resolves a trusted public key and verifies canonical compact EdDSA JWS bytes. */
    private boolean verifySignature(
            ParsedEvidence evidence,
            ParsedProofArchive archive,
            VerificationContext context,
            VerificationAccumulator checks,
            VerificationSummaryBuilder summary
    ) {
        SignedProofBundleModel.SignatureMetadata signatureMetadata = evidence.manifest().signature();
        if (signatureMetadata == null || signatureMetadata.keyVersion() == null) {
            checks.fail("signature.jws", "signature", VerificationCode.SIGNATURE_INVALID,
                    "Manifest signing metadata is missing");
            return false;
        }
        Resolution<PublicSigningKey> resolution;
        try {
            resolution = context.signingKeyResolver().resolve(
                    signatureMetadata.keyId(), signatureMetadata.keyVersion());
        } catch (RuntimeException e) {
            resolution = Resolution.error("Signing key resolver failed");
        }
        if (resolution == null || resolution.state() != ResolutionState.RESOLVED || resolution.value() == null) {
            VerificationCode code = resolution != null && resolution.state() == ResolutionState.NOT_FOUND
                    ? VerificationCode.SIGNING_KEY_UNKNOWN
                    : VerificationCode.SIGNING_KEY_UNAVAILABLE;
            checks.indeterminate("signature.key", "signature", code,
                    safeResolutionMessage(resolution, "Trusted signing key is unavailable"));
            return false;
        }
        PublicSigningKey key;
        try {
            key = TrustedEvidenceLoader.validateSigningKey(resolution.value());
        } catch (IllegalArgumentException e) {
            checks.fail("signature.jws", "signature", VerificationCode.SIGNATURE_INVALID,
                    "Trusted signing key identity, fingerprint, or Ed25519 SPKI is invalid");
            return false;
        }
        boolean metadataMatches = Objects.equals(signatureMetadata.keyId(), key.keyId())
                && Objects.equals(signatureMetadata.keyVersion(), key.keyVersion())
                && Objects.equals(signatureMetadata.algorithm(), key.algorithm())
                && ProofHashes.equalsSha256(signatureMetadata.publicKeyFingerprint(), key.publicKeyFingerprint());
        boolean signatureValid = metadataMatches && verifyCompactJws(
                archive.required(SignedProofBundleContract.MANIFEST_ENTRY),
                archive.required(SignedProofBundleContract.SIGNATURE_ENTRY),
                key);
        if (signatureValid) {
            summary.keySource = key.source();
            checks.pass("signature.jws", "signature", VerificationCode.SIGNATURE_VALID,
                    "Compact EdDSA JWS, manifest payload, trusted key identity, and fingerprint are valid",
                    Map.of("keySource", orEmpty(key.source())));
        } else {
            checks.fail("signature.jws", "signature", VerificationCode.SIGNATURE_INVALID,
                    "Compact JWS, payload, trusted key identity, fingerprint, or Ed25519 signature is invalid");
        }
        return signatureValid;
    }

    /** Marks trust-dependent dimensions as not executed after a failed prerequisite without making network calls. */
    private void markTrustChecksSkipped(
            VerificationAccumulator checks,
            boolean includeSignature,
            String reason
    ) {
        if (includeSignature) {
            checks.indeterminate(
                    "signature.key",
                    "signature",
                    VerificationCode.SIGNING_KEY_UNAVAILABLE,
                    reason);
        }
        checks.indeterminate(
                "status.current",
                "status",
                VerificationCode.STATUS_UNAVAILABLE,
                reason);
        checks.indeterminate(
                "chain.live-root",
                "chain",
                VerificationCode.LIVE_CHAIN_UNAVAILABLE,
                reason);
    }

    /** Resolves and classifies the current proof lifecycle state without caching it locally. */
    private void resolveCurrentStatus(
            SignedProofBundleModel.Manifest manifest,
            VerificationContext context,
            VerificationAccumulator checks,
            VerificationSummaryBuilder summary,
            Instant verifiedAt
    ) {
        Resolution<PublicProofStatus> resolution;
        try {
            resolution = context.proofStatusResolver().resolve(manifest.proofId());
        } catch (RuntimeException e) {
            resolution = Resolution.error("Proof status resolver failed");
        }
        if (resolution == null || resolution.state() != ResolutionState.RESOLVED || resolution.value() == null) {
            VerificationCode code = resolution != null && resolution.state() == ResolutionState.NOT_FOUND
                    ? VerificationCode.STATUS_UNKNOWN
                    : VerificationCode.STATUS_UNAVAILABLE;
            checks.indeterminate("status.current", "status", code,
                    safeResolutionMessage(resolution, "Current proof status is unavailable"));
            return;
        }
        PublicProofStatus status = resolution.value();
        boolean identityValid = Objects.equals(manifest.proofId(), status.proofId())
                && Objects.equals(manifest.issuedStatus(), status.issuedStatus())
                && manifest.signature() != null
                && Objects.equals(manifest.signature().keyId(), status.keyId())
                && Objects.equals(manifest.signature().keyVersion(), status.keyVersion())
                && sameInstant(manifest.issuedAt(), status.issuedAt())
                && positiveStatusVersion(status.statusVersion())
                && statusTimeOrderValid(status.issuedAt(), status.updatedAt(), verifiedAt);
        if (!identityValid) {
            checks.fail("status.current", "status", VerificationCode.STATUS_INVALID,
                    "Current status response does not match the signed proof identity");
            return;
        }
        summary.currentStatus = status.status();
        switch (orEmpty(status.status())) {
            case "ACTIVE" -> checks.pass("status.current", "status", VerificationCode.STATUS_ACTIVE,
                    "Current proof status is ACTIVE");
            case "REVOKED" -> checks.fail("status.current", "status", VerificationCode.STATUS_REVOKED,
                    "Current proof status is REVOKED");
            case "SUPERSEDED" -> checks.fail("status.current", "status", VerificationCode.STATUS_SUPERSEDED,
                    "Current proof status is SUPERSEDED");
            case "INVALID" -> checks.fail("status.current", "status", VerificationCode.STATUS_INVALID,
                    "Current proof status is INVALID");
            default -> checks.fail("status.current", "status", VerificationCode.STATUS_UNKNOWN,
                    "Current proof status value is unsupported");
        }
    }

    /** Resolves a live chain root and compares every configured chain identity dimension. */
    private void resolveLiveChain(
            ParsedEvidence evidence,
            VerificationContext context,
            VerificationAccumulator checks,
            VerificationSummaryBuilder summary
    ) {
        SignedProofBundleModel.ContractRegistryEvidence registry = evidence.chainReceipt().contractRegistry();
        if (registry == null) {
            checks.indeterminate("chain.live-root", "chain", VerificationCode.LIVE_CHAIN_UNKNOWN,
                    "Live chain root cannot be queried without registry evidence");
            return;
        }
        ChainQuery query = new ChainQuery(
                registry.chainType(),
                registry.chainId(),
                registry.groupId(),
                registry.contractAddress(),
                evidence.manifest().batchNo(),
                evidence.chainReceipt().batchTransactionHash(),
                evidence.chainReceipt().batchChainRoot());
        Resolution<ChainRootEvidence> resolution;
        try {
            resolution = context.chainRootResolver().resolve(query);
        } catch (RuntimeException e) {
            resolution = Resolution.error("Live chain resolver failed");
        }
        if (resolution == null || resolution.state() != ResolutionState.RESOLVED || resolution.value() == null) {
            VerificationCode code = resolution != null && resolution.state() == ResolutionState.NOT_FOUND
                    ? VerificationCode.LIVE_CHAIN_UNKNOWN
                    : VerificationCode.LIVE_CHAIN_UNAVAILABLE;
            checks.indeterminate("chain.live-root", "chain", code,
                    safeResolutionMessage(resolution, "Live chain root is unavailable"));
            return;
        }
        ChainRootEvidence live = resolution.value();
        boolean matches = ChainRootEvidence.SCHEMA_VERSION.equals(live.schemaVersion())
                && Objects.equals(query.chainType(), live.chainType())
                && Objects.equals(query.chainId(), live.chainId())
                && Objects.equals(query.groupId(), live.groupId())
                && Objects.equals(query.contractAddress(), live.contractAddress())
                && Objects.equals(query.batchNo(), live.batchNo())
                && Objects.equals(query.expectedMerkleRoot(), live.merkleRoot())
                && sameTransactionHash(query.expectedTransactionHash(), live.transactionHash())
                && live.blockNumber() != null
                && live.blockNumber() >= 0;
        if (matches) {
            summary.liveBlockNumber = live.blockNumber();
            summary.liveChainSource = live.source();
            checks.pass("chain.live-root", "chain", VerificationCode.LIVE_CHAIN_MATCH,
                    "Live chain gateway returned the signed batch root and identity",
                    Map.of("source", orEmpty(live.source()),
                            "blockNumber", String.valueOf(live.blockNumber())));
        } else {
            checks.fail("chain.live-root", "chain", VerificationCode.LIVE_CHAIN_MISMATCH,
                    "Live chain root, transaction, block, or registry identity does not match signed evidence");
        }
    }

    /** Verifies compact-JWS canonical encoding, protected header, payload, key fingerprint, and Ed25519 signature. */
    private boolean verifyCompactJws(byte[] manifestBytes, byte[] jwsEntry, PublicSigningKey key) {
        try {
            String compact = parseAsciiLine(jwsEntry);
            String[] parts = compact.split("\\.", -1);
            if (parts.length != 3) {
                return false;
            }
            Base64.Decoder decoder = Base64.getUrlDecoder();
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            byte[] headerBytes = decoder.decode(parts[0]);
            byte[] payloadBytes = decoder.decode(parts[1]);
            byte[] signatureBytes = decoder.decode(parts[2]);
            if (signatureBytes.length != 64
                    || !parts[0].equals(encoder.encodeToString(headerBytes))
                    || !parts[1].equals(encoder.encodeToString(payloadBytes))
                    || !parts[2].equals(encoder.encodeToString(signatureBytes))
                    || !MessageDigest.isEqual(payloadBytes, manifestBytes)) {
                return false;
            }
            JwsHeader header = canonicalJson.read(headerBytes, JwsHeader.class);
            if (!canonicalJson.isCanonical(headerBytes, header)
                    || !"EdDSA".equals(header.alg())
                    || !Objects.equals(key.keyId(), header.kid())
                    || !Objects.equals(key.keyVersion(), header.keyVersion())
                    || !"JOSE".equals(header.typ())) {
                return false;
            }
            byte[] spki = Base64.getDecoder().decode(key.publicKeySpki());
            if (!ProofHashes.equalsSha256(ProofHashes.sha256(spki), key.publicKeyFingerprint())) {
                return false;
            }
            PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(spki));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            return verifier.verify(signatureBytes);
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            return false;
        }
    }

    /** Validates one immutable contract registry snapshot including recomputed fingerprint. */
    private boolean validateRegistry(SignedProofBundleModel.ContractRegistryEvidence registry, Instant now) {
        if (registry == null
                || !"record-platform-contract-registry-entry.v1".equals(registry.schemaVersion())
                || !"Sharing".equals(registry.contractName())
                || !hasBoundedText(registry.semanticVersion(), 64)
                || !matches(SEMANTIC_VERSION, registry.semanticVersion())
                || !ALLOWED_CHAIN_TYPES.contains(registry.chainType())
                || !hasBoundedText(registry.chainId(), 128)
                || !matches(ADDRESS, registry.contractAddress())
                || !"ABI-CANONICAL-JSON-SHA256-V1".equals(registry.abiFingerprintAlgorithm())
                || !ProofHashes.PREFIXED_SHA256.matcher(orEmpty(registry.abiFingerprint())).matches()
                || !ProofHashes.PREFIXED_SHA256.matcher(orEmpty(registry.artifactBytecodeSha256())).matches()
                || !ProofHashes.PREFIXED_SHA256.matcher(orEmpty(registry.onChainCodeSha256())).matches()
                || !ProofHashes.PREFIXED_SHA256.matcher(orEmpty(registry.registryFingerprint())).matches()
                || !REGISTRY_STATUSES.contains(registry.status())
                || !"REDEPLOY_ADDRESS".equals(registry.upgradeStrategy())
                || !effectiveAtValid(registry.effectiveAt(), now)) {
            return false;
        }
        boolean fisco = FISCO_CHAIN_TYPES.contains(registry.chainType());
        if ((fisco && !hasBoundedText(registry.groupId(), 128))
                || (!fisco && registry.groupId() != null)) {
            return false;
        }
        boolean hasDeploymentTx = registry.deploymentTransactionHash() != null;
        boolean hasDeploymentBlock = registry.deploymentBlockNumber() != null;
        if (hasDeploymentTx != hasDeploymentBlock
                || (hasDeploymentTx && !TRANSACTION_HASH.matcher(registry.deploymentTransactionHash()).matches())
                || (hasDeploymentBlock && registry.deploymentBlockNumber() < 0)) {
            return false;
        }
        return ProofHashes.equalsSha256(
                registry.registryFingerprint(),
                ContractRegistryFingerprint.calculate(registry));
    }

    /** Enforces the write/query confirmation-source transaction matrix. */
    private boolean validConfirmationMatrix(SignedProofBundleModel.BlockchainReceiptEvidence receipt) {
        return switch (orEmpty(receipt.confirmationSource())) {
            case "CHAIN_WRITE" -> receipt.batchTransactionHash() != null
                    && TRANSACTION_HASH.matcher(receipt.batchTransactionHash()).matches();
            case "CHAIN_QUERY_BEFORE_WRITE", "CHAIN_QUERY_AFTER_WRITE" -> receipt.batchTransactionHash() == null;
            default -> false;
        };
    }

    /** Validates an optional transaction hash. */
    private boolean validOptionalTransactionHash(String value) {
        return value == null || TRANSACTION_HASH.matcher(value).matches();
    }

    /** Parses the exact single-line UTF-8 file.hash contract. */
    private String parseFileHashEntry(byte[] bytes) {
        if (bytes == null || bytes.length != ProofHashes.HASH_PREFIX.length() + 64 + 1
                || bytes[bytes.length - 1] != '\n') {
            throw new ProofFormatException(
                    VerificationCode.CHUNK_MANIFEST_INVALID,
                    "file.hash must contain one prefixed SHA-256 value and one trailing LF");
        }
        for (byte value : bytes) {
            if (value < 0) {
                throw new ProofFormatException(
                        VerificationCode.CHUNK_MANIFEST_INVALID,
                        "file.hash must be ASCII-compatible UTF-8 text");
            }
        }
        String value = new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
        if (!ProofHashes.PREFIXED_SHA256.matcher(value).matches()) {
            throw new ProofFormatException(
                    VerificationCode.CHUNK_MANIFEST_INVALID,
                    "file.hash contains an invalid SHA-256 value");
        }
        return value;
    }

    /** Parses exactly one printable ASCII line with one trailing LF. */
    private String parseAsciiLine(byte[] bytes) {
        if (bytes == null || bytes.length < 2 || bytes[bytes.length - 1] != '\n') {
            throw new IllegalArgumentException("Compact JWS must end with one LF");
        }
        for (int index = 0; index < bytes.length - 1; index++) {
            int value = Byte.toUnsignedInt(bytes[index]);
            if (value < 0x21 || value > 0x7e) {
                throw new IllegalArgumentException("Compact JWS must contain printable ASCII only");
            }
        }
        return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
    }

    /** Returns the expected fixed proof-status path. */
    private String expectedStatusLocation(SignedProofBundleModel.Manifest manifest) {
        return "/api/v1/public/proofs/" + orEmpty(manifest.proofId()) + "/status";
    }

    /** Parses issuance time and rejects values later than verification time. */
    private boolean validateIssuedAt(String value, Instant now) {
        return effectiveAtValid(value, now);
    }

    /** Parses an RFC 3339 offset timestamp and rejects future values. */
    private boolean effectiveAtValid(String value, Instant now) {
        if (!hasBoundedText(value, 128) || now == null) {
            return false;
        }
        try {
            return !OffsetDateTime.parse(value).toInstant().isAfter(now);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** Compares two RFC 3339 timestamps by instant instead of presentation offset. */
    private boolean sameInstant(String left, String right) {
        if (!hasBoundedText(left, 128) || !hasBoundedText(right, 128)) {
            return false;
        }
        try {
            return OffsetDateTime.parse(left).toInstant().equals(OffsetDateTime.parse(right).toInstant());
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** Requires a positive signed-64-bit monotonic lifecycle version. */
    private boolean positiveStatusVersion(String value) {
        if (value == null || !value.matches("^[1-9][0-9]{0,18}$")) {
            return false;
        }
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Requires each Merkle sibling direction to agree with the declared zero-based leaf index. */
    private boolean proofPathMatchesLeafIndex(
            int leafIndex,
            List<SignedProofBundleModel.ProofNode> proofPath
    ) {
        int currentIndex = leafIndex;
        for (SignedProofBundleModel.ProofNode node : proofPath) {
            if (node == null
                    || !ProofHashes.RAW_SHA256.matcher(orEmpty(node.hash())).matches()) {
                return false;
            }
            String expectedPosition = (currentIndex & 1) == 0 ? MerkleProofs.RIGHT : MerkleProofs.LEFT;
            if (!expectedPosition.equals(node.position())) {
                return false;
            }
            currentIndex >>>= 1;
        }
        return currentIndex == 0;
    }

    /** Compares optional transaction hashes after removing the allowed prefix and hexadecimal case. */
    private boolean sameTransactionHash(String left, String right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        if (!TRANSACTION_HASH.matcher(left).matches() || !TRANSACTION_HASH.matcher(right).matches()) {
            return false;
        }
        String normalizedLeft = left.startsWith("0x") ? left.substring(2) : left;
        String normalizedRight = right.startsWith("0x") ? right.substring(2) : right;
        return MessageDigest.isEqual(
                normalizedLeft.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
                normalizedRight.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    /** Requires an update timestamp between issuance and the current verification instant. */
    private boolean statusTimeOrderValid(String issuedAt, String updatedAt, Instant now) {
        if (!hasBoundedText(issuedAt, 128) || !hasBoundedText(updatedAt, 128) || now == null) {
            return false;
        }
        try {
            Instant issued = OffsetDateTime.parse(issuedAt).toInstant();
            Instant updated = OffsetDateTime.parse(updatedAt).toInstant();
            return !updated.isBefore(issued) && !updated.isAfter(now);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** Returns a safe dependency diagnostic without raw response content. */
    private String safeResolutionMessage(Resolution<?> resolution, String fallback) {
        if (resolution == null || resolution.message() == null || resolution.message().isBlank()) {
            return fallback;
        }
        String message = resolution.message().trim();
        return message.length() <= 256 ? message : message.substring(0, 256);
    }

    /** Matches one optional string against a bounded pattern. */
    private boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }

    /** Checks one nonblank bounded text field. */
    private boolean hasBoundedText(String value, int maxLength) {
        return value != null
                && !value.isBlank()
                && value.length() <= maxLength
                && !containsControlCharacter(value);
    }

    /** Detects control characters in reportable path evidence. */
    private boolean containsControlCharacter(String value) {
        return value != null && value.chars().anyMatch(Character::isISOControl);
    }

    /** Converts null to an empty string for safe comparisons and evidence rendering. */
    private String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Strict protected JWS header. */
    private record JwsHeader(String alg, String kid, Integer keyVersion, String typ) {
    }

    /** Parsed evidence entries plus deferred access to file.hash bytes. */
    private record ParsedEvidence(
            SignedProofBundleModel.Manifest manifest,
            SignedProofBundleModel.ChunkManifestEvidence chunkManifest,
            SignedProofBundleModel.MerkleProofEvidence merkleProof,
            SignedProofBundleModel.BlockchainReceiptEvidence chainReceipt,
            SignedProofBundleModel.VerificationPolicyEvidence policy,
            byte[] fileHashBytes
    ) {
        private ParsedEvidence {
            fileHashBytes = fileHashBytes == null ? new byte[0] : fileHashBytes.clone();
        }

        private byte[] archiveFileHashBytes() {
            return fileHashBytes.clone();
        }
    }
}
