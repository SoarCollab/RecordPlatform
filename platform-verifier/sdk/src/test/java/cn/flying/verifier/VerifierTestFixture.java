package cn.flying.verifier;

import cn.flying.verifier.contract.SignedProofBundleContract;
import cn.flying.verifier.contract.SignedProofBundleModel;
import cn.flying.verifier.crypto.CanonicalJson;
import cn.flying.verifier.crypto.ContractRegistryFingerprint;
import cn.flying.verifier.crypto.MerkleProofs;
import cn.flying.verifier.crypto.ProofHashes;
import cn.flying.verifier.model.ChainRootEvidence;
import cn.flying.verifier.model.PublicProofStatus;
import cn.flying.verifier.model.PublicSigningKey;
import cn.flying.verifier.resolver.Resolution;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a real compact-JWS signed proof bundle for SDK, CLI, and Web adapter tests.
 */
public final class VerifierTestFixture {

    public static final Instant NOW = Instant.parse("2026-07-15T00:00:00Z");
    public static final String PROOF_ID = "rp-proof-" + "1".repeat(64);
    public static final String FILE_ID = "file-public-1";
    public static final String BATCH_NO = "batch-public-1";
    public static final String KEY_ID = "public-verifier-test-key";
    public static final int KEY_VERSION = 1;
    public static final String TRANSACTION_HASH = "0x" + "a".repeat(64);
    public static final String CONTRACT_ADDRESS = "0x" + "b".repeat(40);

    private static final String FIXTURE_PRIVATE_KEY =
            "MC4CAQAwBQYDK2VwBCIEIM9Y9vKvau3Q"
                    + "SKkiO6r/QAxztslFWL8357aPnWdPcgh6";
    private static final String FIXTURE_PUBLIC_KEY =
            "MCowBQYDK2VwAyEA8wy43NzHvQljIDAPCeh24QcbLmygEvh3wrNBf9fkWNI=";

    private final CanonicalJson json = new CanonicalJson();

    /** Creates one valid fixture with deterministic input bytes and an actual Ed25519 signature. */
    public Fixture create(Path directory) throws Exception {
        return create(directory, "CHAIN_WRITE", TRANSACTION_HASH);
    }

    /** Creates a valid query-confirmed fixture whose signed receipt intentionally has no transaction hash. */
    public Fixture createQueryConfirmed(Path directory) throws Exception {
        return create(directory, "CHAIN_QUERY_AFTER_WRITE", null);
    }

    /** Creates one fixture for the selected valid receipt-source and transaction-hash combination. */
    private Fixture create(Path directory, String confirmationSource, String batchTransactionHash) throws Exception {
        Files.createDirectories(directory);
        byte[] originalBytes = "RecordPlatform public verifier fixture\n".getBytes(StandardCharsets.UTF_8);
        Path original = directory.resolve("original.txt");
        Files.write(original, originalBytes);

        KeyPair keyPair = generateKeyPair();
        String publicSpki = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        String fingerprint = ProofHashes.sha256(keyPair.getPublic().getEncoded());
        PublicSigningKey key = new PublicSigningKey(
                KEY_ID, KEY_VERSION, "EdDSA", publicSpki, fingerprint, "fixture-trust-anchor");

        String contentHash = ProofHashes.sha256(originalBytes);
        String sourceManifestHash = ProofHashes.sha256("source-manifest-v1");
        SignedProofBundleModel.ChunkManifestEvidence chunks = new SignedProofBundleModel.ChunkManifestEvidence(
                SignedProofBundleContract.CHUNK_SCHEMA,
                FILE_ID,
                1,
                contentHash,
                "chain-record-public-1",
                sourceManifestHash,
                SignedProofBundleContract.SOURCE_CHUNK_MANIFEST_SCHEMA,
                "SHA-256",
                originalBytes.length,
                1,
                originalBytes.length,
                "AES-256-GCM",
                "S3_COMPATIBLE",
                List.of(new SignedProofBundleModel.ChunkEvidence(
                        0,
                        contentHash,
                        ProofHashes.sha256("cipher-object-v1"),
                        originalBytes.length,
                        "tenant/public/file/1/chunk/0",
                        "SHA-256")));

        String leafHash = MerkleProofs.calculateLeafHash(sourceManifestHash);
        SignedProofBundleModel.MerkleProofEvidence merkle = new SignedProofBundleModel.MerkleProofEvidence(
                SignedProofBundleContract.MERKLE_SCHEMA,
                "MANIFEST_HASH",
                sourceManifestHash,
                MerkleProofs.PROOF_ALGORITHM,
                leafHash,
                leafHash,
                0,
                List.of());
        SignedProofBundleModel.ContractRegistryEvidence registry = registry();
        SignedProofBundleModel.BlockchainReceiptEvidence receipt =
                new SignedProofBundleModel.BlockchainReceiptEvidence(
                        SignedProofBundleContract.CHAIN_SCHEMA,
                        chunks.chainRecordId(),
                        null,
                        batchTransactionHash,
                        leafHash,
                        confirmationSource,
                        registry);

        LinkedHashMap<String, byte[]> evidence = new LinkedHashMap<>();
        evidence.put(SignedProofBundleContract.FILE_HASH_ENTRY,
                (contentHash + "\n").getBytes(StandardCharsets.UTF_8));
        evidence.put(SignedProofBundleContract.CHUNK_MANIFEST_ENTRY, json.canonicalBytes(chunks));
        evidence.put(SignedProofBundleContract.MERKLE_PROOF_ENTRY, json.canonicalBytes(merkle));
        evidence.put(SignedProofBundleContract.BLOCKCHAIN_RECEIPT_ENTRY, json.canonicalBytes(receipt));
        evidence.put(SignedProofBundleContract.VERIFICATION_POLICY_ENTRY,
                json.canonicalBytes(SignedProofBundleContract.expectedVerificationPolicy()));
        evidence.put(SignedProofBundleContract.README_ENTRY,
                SignedProofBundleContract.README.getBytes(StandardCharsets.UTF_8));

        List<SignedProofBundleModel.EntryDigest> digests = new ArrayList<>();
        for (Map.Entry<String, byte[]> item : evidence.entrySet()) {
            digests.add(new SignedProofBundleModel.EntryDigest(
                    item.getKey(),
                    SignedProofBundleContract.mediaTypeForEvidenceEntry(item.getKey()),
                    ProofHashes.sha256(item.getValue()),
                    item.getValue().length,
                    true,
                    true));
        }
        SignedProofBundleModel.Manifest manifest = new SignedProofBundleModel.Manifest(
                SignedProofBundleContract.MANIFEST_SCHEMA,
                PROOF_ID,
                FILE_ID,
                1,
                "leaf-public-1",
                BATCH_NO,
                "2026-07-14T00:00:00Z",
                "ACTIVE",
                "/api/v1/public/proofs/" + PROOF_ID + "/status",
                new SignedProofBundleModel.SignatureMetadata(
                        "EdDSA",
                        KEY_ID,
                        KEY_VERSION,
                        fingerprint,
                        "/api/v1/public/proof-keys/" + KEY_ID + "/versions/" + KEY_VERSION),
                digests);
        byte[] manifestBytes = json.canonicalBytes(manifest);
        byte[] jws = (sign(manifestBytes, keyPair) + "\n").getBytes(StandardCharsets.US_ASCII);

        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(SignedProofBundleContract.MANIFEST_ENTRY, manifestBytes);
        entries.put(SignedProofBundleContract.FILE_HASH_ENTRY,
                evidence.get(SignedProofBundleContract.FILE_HASH_ENTRY));
        entries.put(SignedProofBundleContract.CHUNK_MANIFEST_ENTRY,
                evidence.get(SignedProofBundleContract.CHUNK_MANIFEST_ENTRY));
        entries.put(SignedProofBundleContract.MERKLE_PROOF_ENTRY,
                evidence.get(SignedProofBundleContract.MERKLE_PROOF_ENTRY));
        entries.put(SignedProofBundleContract.BLOCKCHAIN_RECEIPT_ENTRY,
                evidence.get(SignedProofBundleContract.BLOCKCHAIN_RECEIPT_ENTRY));
        entries.put(SignedProofBundleContract.SIGNATURE_ENTRY, jws);
        entries.put(SignedProofBundleContract.VERIFICATION_POLICY_ENTRY,
                evidence.get(SignedProofBundleContract.VERIFICATION_POLICY_ENTRY));
        entries.put(SignedProofBundleContract.README_ENTRY,
                evidence.get(SignedProofBundleContract.README_ENTRY));

        Path proof = directory.resolve("proof.zip");
        writeStoredArchive(proof, entries);
        PublicProofStatus status = new PublicProofStatus(
                PROOF_ID,
                "ACTIVE",
                "1",
                "ACTIVE",
                KEY_ID,
                KEY_VERSION,
                null,
                "2026-07-14T00:00:00Z",
                "2026-07-14T00:00:00Z",
                "fixture-status");
        ChainRootEvidence chain = new ChainRootEvidence(
                ChainRootEvidence.SCHEMA_VERSION,
                registry.chainType(),
                registry.chainId(),
                registry.groupId(),
                registry.contractAddress(),
                BATCH_NO,
                leafHash,
                batchTransactionHash,
                101L,
                "fixture-chain");
        return new Fixture(original, proof, key, status, chain, entries, originalBytes);
    }

    /** Creates the immutable registry snapshot and its self-consistent canonical fingerprint. */
    private SignedProofBundleModel.ContractRegistryEvidence registry() {
        SignedProofBundleModel.ContractRegistryEvidence seed = new SignedProofBundleModel.ContractRegistryEvidence(
                "record-platform-contract-registry-entry.v1",
                null,
                "Sharing",
                "1.0.0",
                "LOCAL_FISCO",
                "chain-public-1",
                "group0",
                CONTRACT_ADDRESS,
                "ABI-CANONICAL-JSON-SHA256-V1",
                "sha256:" + "2".repeat(64),
                "sha256:" + "3".repeat(64),
                "sha256:" + "4".repeat(64),
                "0x" + "5".repeat(64),
                88L,
                "ACTIVE",
                "2026-07-13T00:00:00Z",
                "REDEPLOY_ADDRESS");
        return new SignedProofBundleModel.ContractRegistryEvidence(
                seed.schemaVersion(),
                ContractRegistryFingerprint.calculate(seed),
                seed.contractName(),
                seed.semanticVersion(),
                seed.chainType(),
                seed.chainId(),
                seed.groupId(),
                seed.contractAddress(),
                seed.abiFingerprintAlgorithm(),
                seed.abiFingerprint(),
                seed.artifactBytecodeSha256(),
                seed.onChainCodeSha256(),
                seed.deploymentTransactionHash(),
                seed.deploymentBlockNumber(),
                seed.status(),
                seed.effectiveAt(),
                seed.upgradeStrategy());
    }

    /** Loads the fixed test-only Ed25519 key pair so fixtures are stable across JCA providers. */
    private KeyPair generateKeyPair() throws Exception {
        KeyFactory factory = KeyFactory.getInstance("Ed25519");
        Base64.Decoder decoder = Base64.getDecoder();
        return new KeyPair(
                factory.generatePublic(new X509EncodedKeySpec(decoder.decode(FIXTURE_PUBLIC_KEY))),
                factory.generatePrivate(new PKCS8EncodedKeySpec(decoder.decode(FIXTURE_PRIVATE_KEY))));
    }

    /** Signs a canonical manifest as compact JWS with canonical protected-header bytes. */
    private String sign(byte[] manifest, KeyPair keyPair) throws Exception {
        byte[] header = json.canonicalBytes(new JwsHeader("EdDSA", KEY_ID, KEY_VERSION, "JOSE"));
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String headerPart = encoder.encodeToString(header);
        String payloadPart = encoder.encodeToString(manifest);
        String signingInput = headerPart + "." + payloadPart;
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + encoder.encodeToString(signer.sign());
    }

    /** Writes exact physical-order STORED entries with the frozen local ZIP timestamp. */
    public static void writeStoredArchive(Path path, Map<String, byte[]> entries) throws IOException {
        writeStoredArchive(path, entries, false, false);
    }

    /** Writes a fixed archive with unsigned entry-extra metadata for negative parser coverage. */
    public static void writeStoredArchiveWithEntryExtra(Path path, Map<String, byte[]> entries) throws IOException {
        writeStoredArchive(path, entries, true, false);
    }

    /** Writes a fixed archive with an unsigned archive comment for negative parser coverage. */
    public static void writeStoredArchiveWithComment(Path path, Map<String, byte[]> entries) throws IOException {
        writeStoredArchive(path, entries, false, true);
    }

    /** Writes the shared STORED layout with optional deliberately unsigned ZIP metadata. */
    private static void writeStoredArchive(
            Path path,
            Map<String, byte[]> entries,
            boolean firstEntryExtra,
            boolean archiveComment
    ) throws IOException {
        try (OutputStream output = Files.newOutputStream(path);
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            if (archiveComment) {
                zip.setComment("unsigned-comment");
            }
            boolean first = true;
            for (Map.Entry<String, byte[]> item : entries.entrySet()) {
                byte[] bytes = item.getValue();
                CRC32 crc = new CRC32();
                crc.update(bytes);
                ZipEntry entry = new ZipEntry(item.getKey());
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(bytes.length);
                entry.setCompressedSize(bytes.length);
                entry.setCrc(crc.getValue());
                entry.setTimeLocal(SignedProofBundleContract.FIXED_ZIP_LOCAL_TIME);
                if (firstEntryExtra && first) {
                    entry.setExtra(new byte[]{(byte) 0xfe, (byte) 0xca, 0, 0});
                }
                zip.putNextEntry(entry);
                zip.write(bytes);
                zip.closeEntry();
                first = false;
            }
            zip.finish();
        }
    }

    /** Writes the same logical entries with DEFLATED metadata for negative archive tests. */
    public static void writeDeflatedArchive(Path path, Map<String, byte[]> entries) throws IOException {
        try (OutputStream output = Files.newOutputStream(path);
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> item : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(item.getKey());
                entry.setMethod(ZipEntry.DEFLATED);
                entry.setTimeLocal(SignedProofBundleContract.FIXED_ZIP_LOCAL_TIME);
                zip.putNextEntry(entry);
                zip.write(item.getValue());
                zip.closeEntry();
            }
        }
    }

    /** Marks the first otherwise valid entry as a Unix symbolic link for negative parser tests. */
    public static void writeSymlinkArchive(Path path, Map<String, byte[]> entries) throws IOException {
        try (OutputStream output = Files.newOutputStream(path);
             ZipArchiveOutputStream zip = new ZipArchiveOutputStream(output)) {
            boolean first = true;
            for (Map.Entry<String, byte[]> item : entries.entrySet()) {
                byte[] bytes = item.getValue();
                CRC32 crc = new CRC32();
                crc.update(bytes);
                ZipArchiveEntry entry = new ZipArchiveEntry(item.getKey());
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(bytes.length);
                entry.setCompressedSize(bytes.length);
                entry.setCrc(crc.getValue());
                entry.setTimeLocal(SignedProofBundleContract.FIXED_ZIP_LOCAL_TIME);
                if (first) {
                    entry.setUnixMode(UnixStat.LINK_FLAG | 0777);
                    first = false;
                }
                zip.putArchiveEntry(entry);
                zip.write(bytes);
                zip.closeArchiveEntry();
            }
            zip.finish();
        }
    }

    /** Writes eight physical entries with one duplicate name and one omitted contract entry. */
    public static void writeDuplicateArchive(Path path, Map<String, byte[]> entries) throws IOException {
        try (OutputStream output = Files.newOutputStream(path);
             ZipArchiveOutputStream zip = new ZipArchiveOutputStream(output)) {
            List<String> names = new ArrayList<>(SignedProofBundleContract.ENTRY_ORDER);
            names.set(2, SignedProofBundleContract.FILE_HASH_ENTRY);
            for (String name : names) {
                byte[] bytes = entries.get(name);
                CRC32 crc = new CRC32();
                crc.update(bytes);
                ZipArchiveEntry entry = new ZipArchiveEntry(name);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(bytes.length);
                entry.setCompressedSize(bytes.length);
                entry.setCrc(crc.getValue());
                entry.setTimeLocal(SignedProofBundleContract.FIXED_ZIP_LOCAL_TIME);
                zip.putArchiveEntry(entry);
                zip.write(bytes);
                zip.closeArchiveEntry();
            }
            zip.finish();
        }
    }

    /** Returns a fully online context whose resolvers use the fixture identities. */
    public static VerificationContext context(Fixture fixture) {
        return new VerificationContext(
                VerificationLimits.defaults(),
                (keyId, keyVersion) -> Resolution.resolved(fixture.key()),
                proofId -> Resolution.resolved(fixture.status()),
                query -> Resolution.resolved(fixture.chain()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Protected JWS header used by the fixture signer. */
    private record JwsHeader(String alg, String kid, Integer keyVersion, String typ) {
    }

    /** Immutable paths, trust evidence, and entry bytes for one generated fixture. */
    public record Fixture(
            Path original,
            Path proof,
            PublicSigningKey key,
            PublicProofStatus status,
            ChainRootEvidence chain,
            Map<String, byte[]> entries,
            byte[] originalBytes
    ) {
        /** Protects fixture collections and source bytes against accidental cross-test mutation. */
        public Fixture {
            entries = Map.copyOf(entries);
            originalBytes = originalBytes.clone();
        }

        /** Returns an independent mutable entry map in frozen contract order. */
        public LinkedHashMap<String, byte[]> mutableEntries() {
            LinkedHashMap<String, byte[]> copy = new LinkedHashMap<>();
            for (String name : SignedProofBundleContract.ENTRY_ORDER) {
                copy.put(name, entries.get(name).clone());
            }
            return copy;
        }

        /** Returns independent original bytes for tamper tests. */
        @Override
        public byte[] originalBytes() {
            return originalBytes.clone();
        }
    }
}
