package cn.flying.service.proof.signed;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.verifier.contract.SignedProofBundleModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 固定 proof ZIP 合同、确定性与资源上限测试。
 */
class DeterministicProofArchiveBuilderTest {

    private static final String CONTENT_HASH = "sha256:" + "1".repeat(64);
    private static final String MANIFEST_HASH = "sha256:" + "2".repeat(64);
    private static final String PLAIN_HASH = "sha256:" + "3".repeat(64);
    private static final String CIPHER_HASH = "sha256:" + "4".repeat(64);
    private static final String BASELINE_SOURCE_SCHEMA = "record-platform-chunk-manifest.v1";
    private static final String BASELINE_MANIFEST_HASH =
            "sha256:07b3bbb08704fb125f3865e381578d8eb10846f9c387cdc419a6bb18393f42e7";
    private static final String BASELINE_ARCHIVE_SHA256 =
            "5ee5cf098c27176aa77db07e001d75c34921931b40201ca1f54d8403a45a30d0";
    private static final String FIXTURE_PRIVATE_KEY =
            "MC4CAQAwBQYDK2VwBCIEIM9Y9vKvau3QSKkiO6r/QAxztslFWL8357aPnWdPcgh6";
    private static final String FIXTURE_PUBLIC_KEY =
            "MCowBQYDK2VwAyEA8wy43NzHvQljIDAPCeh24QcbLmygEvh3wrNBf9fkWNI=";

    private ProofCanonicalizer canonicalizer;
    private ProofSigningProvider signingProvider;
    private DeterministicProofArchiveBuilder builder;

    /**
     * 初始化真实 Ed25519 signer 和 canonical builder。
     */
    @BeforeEach
    void setUp() throws Exception {
        canonicalizer = new ProofCanonicalizer();
        ProofSigningProperties properties = activeProperties(generateKeyPair());
        signingProvider = new LocalEd25519ProofSigningProvider(properties, canonicalizer);
        builder = new DeterministicProofArchiveBuilder(canonicalizer, signingProvider);
    }

    /**
     * 验证固定输入的 manifest、JWS 和 ZIP bytes 完全一致，且八个 STORED entry 顺序固定。
     */
    @Test
    void shouldBuildByteForByteDeterministicStoredArchive() throws Exception {
        SignedProofBundleModel.ManifestSeed seed = seed();
        SignedProofBundleModel.EvidencePayloads payloads = payloads(CONTENT_HASH);

        ProofArchive first = builder.buildNew("record-proof-fileA-1.zip", seed, payloads);
        ProofArchive second = builder.buildNew("record-proof-fileA-1.zip", seed, payloads);

        assertThat(second.manifestHash()).isEqualTo(first.manifestHash());
        assertThat(second.compactJws()).isEqualTo(first.compactJws());
        assertThat(second.toByteArray()).isEqualTo(first.toByteArray());
        assertThat(first.manifestHash()).isEqualTo(BASELINE_MANIFEST_HASH);
        assertThat(rawSha256(first.toByteArray())).isEqualTo(BASELINE_ARCHIVE_SHA256);
        assertThat(first.entries()).extracting(ProofArchive.ArchiveEntry::name)
                .containsExactlyElementsOf(ProofArchive.ENTRY_ORDER);

        List<String> zipNames = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(first.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                zipNames.add(entry.getName());
                assertThat(entry.getMethod()).isEqualTo(ZipEntry.STORED);
                assertThat(entry.getTimeLocal()).isEqualTo(LocalDateTime.of(1980, 1, 2, 0, 0));
                assertThat(entry.getName()).doesNotContain("/", "\\", "..");
            }
        }
        assertThat(zipNames).containsExactlyElementsOf(ProofArchive.ENTRY_ORDER);
    }

    /**
     * 验证 ZIP DOS 时间使用固定本地值，构建字节不受 JVM 默认时区影响。
     */
    @Test
    void shouldBuildIdenticalBytesAcrossDefaultTimeZones() {
        ProofArchive archive = builder.buildNew("record-proof-fileA-1.zip", seed(), payloads(CONTENT_HASH));
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            byte[] utc = archive.toByteArray();
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            byte[] shanghai = archive.toByteArray();

            assertThat(shanghai).isEqualTo(utc);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    /**
     * 验证 ZIP writer 完成并关闭自身资源后，调用方输出流仍保持打开。
     */
    @Test
    void shouldReleaseZipResourcesWithoutClosingCallerStream() throws Exception {
        ProofArchive archive = builder.buildNew("record-proof-fileA-1.zip", seed(), payloads(CONTENT_HASH));
        TrackingOutputStream output = new TrackingOutputStream();

        archive.writeTo(output);

        assertThat(output.closed).isFalse();
        assertThat(output.size()).isPositive();
        output.write(1);
        assertThat(output.size()).isGreaterThan(1);
    }

    /**
     * 验证 manifest 摘要六个证据条目，并明确 contentHash/chainRecordId/manifestHash 语义。
     */
    @Test
    void shouldBindSeparatedHashSemanticsAndEveryEvidenceEntry() throws Exception {
        ProofArchive archive = builder.buildNew("record-proof-fileA-1.zip", seed(), payloads(CONTENT_HASH));
        JsonNode manifest = new ObjectMapper().readTree(archive.entries().getFirst().bytes());
        JsonNode chunkManifest = new ObjectMapper().readTree(archive.entries().get(2).bytes());
        JsonNode policy = new ObjectMapper().readTree(archive.entries().get(6).bytes());
        List<String> manifestFields = new ArrayList<>();
        manifest.fieldNames().forEachRemaining(manifestFields::add);

        assertThat(manifestFields).containsExactly(
                "batchNo",
                "entries",
                "fileId",
                "fileVersion",
                "issuedAt",
                "issuedStatus",
                "leafId",
                "proofId",
                "schemaVersion",
                "signature",
                "statusLocation");
        assertThat(manifest.path("entries")).hasSize(6);
        assertThat(manifest.path("entries").findValuesAsText("name"))
                .containsExactly(
                        ProofArchive.FILE_HASH_ENTRY,
                        ProofArchive.CHUNK_MANIFEST_ENTRY,
                        ProofArchive.MERKLE_PROOF_ENTRY,
                        ProofArchive.BLOCKCHAIN_RECEIPT_ENTRY,
                        ProofArchive.VERIFICATION_POLICY_ENTRY,
                        ProofArchive.README_ENTRY);
        assertThat(new String(archive.entries().get(1).bytes(), StandardCharsets.UTF_8))
                .isEqualTo(CONTENT_HASH + "\n");
        assertThat(chunkManifest.path("contentHash").asText()).isEqualTo(CONTENT_HASH);
        assertThat(chunkManifest.path("chainRecordId").asText()).isEqualTo("chain-record-1");
        assertThat(chunkManifest.path("manifestHash").asText()).isEqualTo(MANIFEST_HASH);
        assertThat(chunkManifest.path("chunks").get(0).path("cipherHash").asText()).isEqualTo(CIPHER_HASH);
        assertThat(policy.path("chainReceiptPolicy").path("writeSource").asText())
                .isEqualTo("CHAIN_WRITE");
        assertThat(policy.path("chainReceiptPolicy").path("querySources")).hasSize(2);
        assertThat(policy.path("contractRegistryPolicy").path("schemaVersion").asText())
                .isEqualTo("record-platform-contract-registry-entry.v1");
        assertThat(policy.path("contractRegistryPolicy").path("issuableStatuses")).hasSize(2);
        assertThat(policy.path("contractRegistryPolicy").path("issuableStatuses").get(0).asText())
                .isEqualTo("ACTIVE");
        assertThat(policy.path("contractRegistryPolicy").path("issuableStatuses").get(1).asText())
                .isEqualTo("DEPRECATED");
        assertThat(new String(archive.entries().get(5).bytes(), StandardCharsets.US_ASCII))
                .endsWith("\n");
    }

    /**
     * 验证历史 JWS 只能重建完全相同 manifest，证据或签名篡改均失败。
     */
    @Test
    void shouldRejectEvidenceAndSignatureTamperingDuringRebuild() {
        ProofArchive issued = builder.buildNew("record-proof-fileA-1.zip", seed(), payloads(CONTENT_HASH));
        ProofSigningKeyMetadata key = signingProvider.currentKey();

        assertThatThrownBy(() -> builder.rebuild(
                "record-proof-fileA-1.zip",
                seed(),
                payloads("sha256:" + "9".repeat(64)),
                key,
                issued.compactJws()))
                .isInstanceOf(GeneralException.class);

        assertThatThrownBy(() -> builder.rebuild(
                "record-proof-fileA-1.zip",
                seed(),
                payloads(CONTENT_HASH),
                key,
                issued.compactJws() + "A"))
                .isInstanceOf(GeneralException.class);
    }

    /**
     * 验证任何必需结构化证据或 README 缺失时都不能生成标记为 present 的签名 manifest。
     */
    @Test
    void shouldRejectMissingRequiredEvidenceBeforeSigning() {
        SignedProofBundleModel.EvidencePayloads valid = payloads(CONTENT_HASH);
        SignedProofBundleModel.EvidencePayloads missingMerkle = new SignedProofBundleModel.EvidencePayloads(
                valid.contentHash(),
                valid.chunkManifest(),
                null,
                valid.blockchainReceipt(),
                valid.verificationPolicy(),
                valid.readme());
        SignedProofBundleModel.EvidencePayloads blankReadme = new SignedProofBundleModel.EvidencePayloads(
                valid.contentHash(),
                valid.chunkManifest(),
                valid.merkleProof(),
                valid.blockchainReceipt(),
                valid.verificationPolicy(),
                " ");

        assertThatThrownBy(() -> builder.buildNew("record-proof-fileA-1.zip", seed(), missingMerkle))
                .isInstanceOf(GeneralException.class)
                .satisfies(error -> assertThat(((GeneralException) error).getResultEnum())
                        .isEqualTo(ResultEnum.FILE_RECORD_ERROR));
        assertThatThrownBy(() -> builder.buildNew("record-proof-fileA-1.zip", seed(), blankReadme))
                .isInstanceOf(GeneralException.class)
                .satisfies(error -> assertThat(((GeneralException) error).getResultEnum())
                        .isEqualTo(ResultEnum.FILE_RECORD_ERROR));
    }

    /**
     * 验证非法文件名、错误顺序和单条目超限都在 ZIP 写出前失败关闭。
     */
    @Test
    void shouldRejectUnsafeNameWrongOrderAndOversizedEntry() {
        ProofArchive valid = builder.buildNew("record-proof-fileA-1.zip", seed(), payloads(CONTENT_HASH));

        assertThatThrownBy(() -> new ProofArchive(
                "../proof.zip", valid.manifestHash(), valid.compactJws(), valid.entries()))
                .isInstanceOf(GeneralException.class);

        List<ProofArchive.ArchiveEntry> wrongOrder = new ArrayList<>(valid.entries());
        ProofArchive.ArchiveEntry first = wrongOrder.get(0);
        wrongOrder.set(0, wrongOrder.get(1));
        wrongOrder.set(1, first);
        assertThatThrownBy(() -> new ProofArchive(
                valid.fileName(), valid.manifestHash(), valid.compactJws(), wrongOrder))
                .isInstanceOf(GeneralException.class);

        List<ProofArchive.ArchiveEntry> oversized = new ArrayList<>(valid.entries());
        oversized.set(7, new ProofArchive.ArchiveEntry(
                ProofArchive.README_ENTRY,
                "text/markdown",
                new byte[1024 * 1024 + 1]));
        assertThatThrownBy(() -> new ProofArchive(
                valid.fileName(), valid.manifestHash(), valid.compactJws(), oversized))
                .isInstanceOf(GeneralException.class);
    }

    /**
     * 验证 archive 对空字段、未知条目、总大小与输出流故障全部失败关闭。
     */
    @Test
    void shouldRejectEveryArchiveBoundaryAndTranslateWriteFailure() {
        ProofArchive valid = builder.buildNew("record-proof-fileA-1.zip", seed(), payloads(CONTENT_HASH));

        assertThatThrownBy(() -> new ProofArchive(
                null, valid.manifestHash(), valid.compactJws(), valid.entries()))
                .isInstanceOf(GeneralException.class);
        assertThatThrownBy(() -> new ProofArchive(
                valid.fileName(), valid.manifestHash(), valid.compactJws(), null))
                .isInstanceOf(GeneralException.class);
        assertThatThrownBy(() -> new ProofArchive(
                valid.fileName(), valid.manifestHash(), valid.compactJws(), valid.entries().subList(0, 7)))
                .isInstanceOf(GeneralException.class);

        List<ProofArchive.ArchiveEntry> nullEntry = new ArrayList<>(valid.entries());
        nullEntry.set(0, null);
        assertThatThrownBy(() -> new ProofArchive(
                valid.fileName(), valid.manifestHash(), valid.compactJws(), nullEntry))
                .isInstanceOf(GeneralException.class);

        List<ProofArchive.ArchiveEntry> unknownEntry = new ArrayList<>(valid.entries());
        unknownEntry.set(0, new ProofArchive.ArchiveEntry("unknown", "application/octet-stream", new byte[0]));
        assertThatThrownBy(() -> new ProofArchive(
                valid.fileName(), valid.manifestHash(), valid.compactJws(), unknownEntry))
                .isInstanceOf(GeneralException.class);

        List<ProofArchive.ArchiveEntry> totalOversized = new ArrayList<>(valid.entries());
        for (int index = 0; index < 5; index++) {
            ProofArchive.ArchiveEntry source = totalOversized.get(index);
            totalOversized.set(index, new ProofArchive.ArchiveEntry(
                    source.name(), source.mediaType(), new byte[1024 * 1024]));
        }
        assertThatThrownBy(() -> new ProofArchive(
                valid.fileName(), valid.manifestHash(), valid.compactJws(), totalOversized))
                .isInstanceOf(GeneralException.class);

        assertThat(new ProofArchive.ArchiveEntry("empty", "text/plain", null).bytes()).isEmpty();
        assertThatThrownBy(() -> valid.writeTo(null)).isInstanceOf(GeneralException.class);
        assertThatThrownBy(() -> valid.writeTo(new FailingOutputStream()))
                .isInstanceOf(GeneralException.class)
                .satisfies(error -> assertThat(((GeneralException) error).getResultEnum())
                        .isEqualTo(ResultEnum.FILE_DOWNLOAD_ERROR));
    }

    /**
     * 验证 canonical JSON 序列化和历史 manifest 解析错误使用稳定业务错误。
     */
    @Test
    void shouldTranslateCanonicalJsonFailures() {
        assertThatThrownBy(() -> canonicalizer.canonicalBytes(new SelfReferencingValue()))
                .isInstanceOf(GeneralException.class)
                .satisfies(error -> assertThat(((GeneralException) error).getResultEnum())
                        .isEqualTo(ResultEnum.JSON_PARSE_ERROR));
        assertThatThrownBy(() -> canonicalizer.parseManifest("{} trailing"))
                .isInstanceOf(GeneralException.class)
                .satisfies(error -> assertThat(((GeneralException) error).getResultEnum())
                        .isEqualTo(ResultEnum.FILE_RECORD_ERROR));
        assertThatThrownBy(() -> canonicalizer.parseManifest(null))
                .isInstanceOf(GeneralException.class)
                .satisfies(error -> assertThat(((GeneralException) error).getResultEnum())
                        .isEqualTo(ResultEnum.FILE_RECORD_ERROR));
    }

    /**
     * 构造稳定签发上下文。
     */
    private SignedProofBundleModel.ManifestSeed seed() {
        return new SignedProofBundleModel.ManifestSeed(
                "rp-proof-" + "a".repeat(64),
                "fileA",
                1,
                "leafA",
                "MB-1",
                "2026-07-14T00:00:00Z",
                "ACTIVE",
                "/api/v1/public/proofs/rp-proof-" + "a".repeat(64) + "/status");
    }

    /**
     * 构造包含全部六类受摘要保护证据的 payload。
     */
    private SignedProofBundleModel.EvidencePayloads payloads(String contentHash) {
        SignedProofBundleModel.ChunkManifestEvidence chunkManifest =
                new SignedProofBundleModel.ChunkManifestEvidence(
                        "record-platform-proof-chunk-manifest.v2",
                        "fileA",
                        1,
                        contentHash,
                        "chain-record-1",
                        MANIFEST_HASH,
                        BASELINE_SOURCE_SCHEMA,
                        "SHA-256",
                        1024,
                        1,
                        3,
                        "NONE",
                        "S3",
                        List.of(new SignedProofBundleModel.ChunkEvidence(
                                0, PLAIN_HASH, CIPHER_HASH, 3, "tenant/1/chunk/0", "SHA-256")));
        SignedProofBundleModel.MerkleProofEvidence merkleProof =
                new SignedProofBundleModel.MerkleProofEvidence(
                        "record-platform-proof-merkle.v2",
                        "MANIFEST_HASH",
                        MANIFEST_HASH,
                        "SHA-256-MERKLE-V1",
                        "root",
                        "leaf",
                        0,
                        List.of());
        SignedProofBundleModel.ContractRegistryEvidence registry =
                new SignedProofBundleModel.ContractRegistryEvidence(
                        "record-platform-contract-registry-entry.v1",
                        "sha256:" + "5".repeat(64),
                        "Sharing",
                        "1.0.0",
                        "LOCAL_FISCO",
                        "chain0",
                        "group0",
                        "0x1111111111111111111111111111111111111111",
                        "ABI-CANONICAL-JSON-SHA256-V1",
                        "sha256:" + "6".repeat(64),
                        "sha256:" + "7".repeat(64),
                        "sha256:" + "8".repeat(64),
                        "0x" + "9".repeat(64),
                        100L,
                        "ACTIVE",
                        "2026-07-01T00:00:00Z",
                        "REDEPLOY_ADDRESS");
        SignedProofBundleModel.BlockchainReceiptEvidence receipt =
                new SignedProofBundleModel.BlockchainReceiptEvidence(
                        "record-platform-proof-chain-receipt.v2",
                        "chain-record-1",
                        "0xfiletx",
                        "0xbatchtx",
                        "root",
                        "CHAIN_WRITE",
                        registry);
        SignedProofBundleModel.VerificationPolicyEvidence policy =
                new SignedProofBundleModel.VerificationPolicyEvidence(
                        "record-platform-proof-verification-policy.v2",
                        List.of(
                                "record-platform-proof-chunk-manifest.v2",
                                "record-platform-proof-merkle.v2",
                                "record-platform-proof-chain-receipt.v2",
                                "record-platform-proof-verification-policy.v2"),
                        "SHA-256",
                        "file hash rule",
                        "manifest rule",
                        "leaf rule",
                        "parent rule",
                        "path rule",
                        new SignedProofBundleModel.ChainReceiptPolicy(
                                "root pattern",
                                "transaction pattern",
                                "CHAIN_WRITE",
                                List.of("CHAIN_QUERY_BEFORE_WRITE", "CHAIN_QUERY_AFTER_WRITE"),
                                "write transaction rule",
                                "query transaction rule"),
                        new SignedProofBundleModel.ContractRegistryPolicy(
                                "record-platform-contract-registry-entry.v1",
                                "Sharing",
                                "semantic version pattern",
                                List.of("LOCAL_FISCO", "BSN_FISCO", "BSN_BESU"),
                                List.of("LOCAL_FISCO", "BSN_FISCO"),
                                "group rule",
                                "address pattern",
                                "ABI-CANONICAL-JSON-SHA256-V1",
                                "sha256 pattern",
                                "deployment evidence rule",
                                List.of("ACTIVE", "DEPRECATED"),
                                "effective time rule",
                                "REDEPLOY_ADDRESS",
                                "registry fingerprint rule",
                                List.of("schemaVersion", "contractName")),
                        "JWS EdDSA",
                        "online status",
                        "fixed zip",
                        "one trailing LF");
        return new SignedProofBundleModel.EvidencePayloads(
                contentHash,
                chunkManifest,
                merkleProof,
                receipt,
                policy,
                "# Verify\n");
    }

    /**
     * 构造真实 ACTIVE 本地 signer 配置。
     */
    private ProofSigningProperties activeProperties(KeyPair keyPair) {
        ProofSigningProperties properties = new ProofSigningProperties();
        properties.setEnabled(true);
        properties.setAlgorithm("Ed25519");
        properties.setKeyId("proof-key-main");
        properties.setKeyVersion(1);
        properties.setKeyStatus("ACTIVE");
        properties.setPrivateKeyPkcs8(Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
        properties.setPublicKeySpki(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        return properties;
    }

    /**
     * 加载固定测试密钥，确保可与 P1-2 基线逐字节比较签名证明包。
     */
    private KeyPair generateKeyPair() throws Exception {
        Base64.Decoder decoder = Base64.getDecoder();
        KeyFactory factory = KeyFactory.getInstance("Ed25519");
        return new KeyPair(
                factory.generatePublic(new X509EncodedKeySpec(decoder.decode(FIXTURE_PUBLIC_KEY))),
                factory.generatePrivate(new PKCS8EncodedKeySpec(decoder.decode(FIXTURE_PRIVATE_KEY))));
    }

    /**
     * 计算不带算法前缀的小写 SHA-256，固定 ZIP 字节兼容性基线。
     */
    private String rawSha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    /**
     * 记录底层输出流是否被 proof ZIP writer 关闭。
     */
    private static final class TrackingOutputStream extends ByteArrayOutputStream {

        private boolean closed;

        /**
         * 标记测试流关闭事件。
         */
        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    /**
     * 在首次写入时模拟 HTTP 输出流故障。
     */
    private static final class FailingOutputStream extends OutputStream {

        /**
         * 始终抛出 I/O 异常，验证 ZIP writer 的错误映射。
         */
        @Override
        public void write(int value) throws IOException {
            throw new IOException("simulated output failure");
        }
    }

    /**
     * 构造 Jackson 可检测的直接自引用对象。
     */
    private static final class SelfReferencingValue {

        /**
         * 返回自身以触发 canonical JSON 自引用检测。
         */
        public SelfReferencingValue getSelf() {
            return this;
        }
    }
}
