package cn.flying.service.verifier;

import cn.flying.common.util.JsonConverter;
import cn.flying.dao.vo.file.ProofBundleVO;
import cn.flying.service.attestation.MerkleLeafInput;
import cn.flying.service.attestation.MerkleLeafProof;
import cn.flying.service.attestation.MerkleTreeResult;
import cn.flying.service.attestation.MerkleTreeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProofBundleVerifierImpl")
class ProofBundleVerifierImplTest {

    private final MerkleTreeService merkleTreeService = new MerkleTreeService();
    private final ProofBundleVerifierImpl verifier = new ProofBundleVerifierImpl(merkleTreeService);

    /**
     * 验证有效证明包可以在无平台会话和无数据库访问的情况下通过校验。
     */
    @Test
    void verify_shouldAcceptValidProofBundle() {
        byte[] originalFile = bytes("hello proof");
        ProofBundleVO bundle = validBundle(originalFile);

        ProofVerificationResult result = verifier.verify(originalFile, bundle);

        assertThat(result.valid()).isTrue();
        assertThat(result.issues()).isEmpty();
        assertThat(result.computedFileHash()).isEqualTo(bundle.file().fileHash());
        assertThat(result.computedLeafHash()).isEqualTo(bundle.merkle().leafHash());
        assertThat(result.computedMerkleRoot()).isEqualTo(bundle.merkle().merkleRoot());
        assertThat(result.batchTransactionHash()).isEqualTo("tx-batch");
        assertThat(result.issuerPlatform()).isEqualTo("RecordPlatform");
    }

    /**
     * 验证 JSON 字符串入口会解析证明包并复用同一套离线校验逻辑。
     */
    @Test
    void verify_shouldAcceptValidProofBundleJson() {
        byte[] originalFile = bytes("hello proof");
        ProofBundleVO bundle = validBundle(originalFile);

        ProofVerificationResult result = verifier.verify(originalFile, JsonConverter.toJson(bundle));

        assertThat(result.valid()).isTrue();
        assertThat(result.contractVersion()).isEqualTo(ProofBundleVO.CONTRACT_VERSION);
    }

    /**
     * 验证原始文件内容与证明包文件哈希不一致时返回机器可读错误码。
     */
    @Test
    void verify_shouldRejectFileHashMismatch() {
        ProofBundleVO bundle = validBundle(bytes("hello proof"));

        ProofVerificationResult result = verifier.verify(bytes("tampered"), bundle);

        assertThat(result.valid()).isFalse();
        assertThat(result.issues())
                .extracting(ProofVerificationIssue::code)
                .contains(ProofVerificationCode.FILE_HASH_MISMATCH);
    }

    /**
     * 验证证明路径被篡改时会拒绝证明包。
     */
    @Test
    void verify_shouldRejectProofPathMismatch() {
        byte[] originalFile = bytes("hello proof");
        ProofBundleVO bundle = validBundle(originalFile);
        ProofBundleVO tampered = withMerkle(
                bundle,
                new ProofBundleVO.MerkleEvidence(
                        bundle.merkle().proofAlgorithm(),
                        bundle.merkle().merkleRoot(),
                        bundle.merkle().leafHash(),
                        bundle.merkle().leafIndex(),
                        List.of(new ProofBundleVO.ProofNode("RIGHT", "tampered"))
                )
        );

        ProofVerificationResult result = verifier.verify(originalFile, tampered);

        assertThat(result.valid()).isFalse();
        assertThat(result.issues())
                .extracting(ProofVerificationIssue::code)
                .contains(ProofVerificationCode.PROOF_PATH_MISMATCH);
    }

    /**
     * 验证不支持的 Merkle 算法会显式失败。
     */
    @Test
    void verify_shouldRejectUnsupportedAlgorithm() {
        byte[] originalFile = bytes("hello proof");
        ProofBundleVO bundle = validBundle(originalFile);
        ProofBundleVO unsupported = withMerkle(
                bundle,
                new ProofBundleVO.MerkleEvidence(
                        "SHA-512-MERKLE-V9",
                        bundle.merkle().merkleRoot(),
                        bundle.merkle().leafHash(),
                        bundle.merkle().leafIndex(),
                        bundle.merkle().proofPath()
                )
        );

        ProofVerificationResult result = verifier.verify(originalFile, unsupported);

        assertThat(result.valid()).isFalse();
        assertThat(result.issues())
                .extracting(ProofVerificationIssue::code)
                .contains(ProofVerificationCode.UNSUPPORTED_ALGORITHM);
    }

    /**
     * 验证链上根字段与 Merkle 根不一致时会显式失败。
     */
    @Test
    void verify_shouldRejectChainRootMismatch() {
        byte[] originalFile = bytes("hello proof");
        ProofBundleVO bundle = validBundle(originalFile);
        ProofBundleVO tampered = withChain(
                bundle,
                new ProofBundleVO.ChainEvidence("tx-batch", "different-root", "tx-file")
        );

        ProofVerificationResult result = verifier.verify(originalFile, tampered);

        assertThat(result.valid()).isFalse();
        assertThat(result.issues())
                .extracting(ProofVerificationIssue::code)
                .contains(ProofVerificationCode.CHAIN_ROOT_MISMATCH);
    }

    /**
     * 验证无法解析的证明包 JSON 返回格式错误码。
     */
    @Test
    void verify_shouldRejectMalformedBundleJson() {
        ProofVerificationResult result = verifier.verify(bytes("hello proof"), "{");

        assertThat(result.valid()).isFalse();
        assertThat(result.issues())
                .extracting(ProofVerificationIssue::code)
                .containsExactly(ProofVerificationCode.MALFORMED_BUNDLE);
    }

    /**
     * 构造与原始文件内容匹配的证明包。
     */
    private ProofBundleVO validBundle(byte[] originalFile) {
        String fileHash = sha256Hex(originalFile);
        String siblingHash = sha256Hex(bytes("sibling"));
        MerkleTreeResult tree = merkleTreeService.buildTree(List.of(
                new MerkleLeafInput(1L, fileHash),
                new MerkleLeafInput(2L, siblingHash)
        ));
        MerkleLeafProof leaf = tree.leaves().stream()
                .filter(candidate -> candidate.fileHash().equals(fileHash))
                .findFirst()
                .orElseThrow();

        return new ProofBundleVO(
                ProofBundleVO.CONTRACT_VERSION,
                new ProofBundleVO.Manifest(
                        "RECORD_PLATFORM_MERKLE_FILE_PROOF",
                        "1.0",
                        "file-ext",
                        "leaf-ext",
                        "MB-1"
                ),
                new ProofBundleVO.FileEvidence(
                        "file-ext",
                        "report.txt",
                        fileHash,
                        "tx-file",
                        (long) originalFile.length,
                        "text/plain",
                        1,
                        1,
                        1,
                        new Date(1710000000000L)
                ),
                new ProofBundleVO.StorageEvidence(List.of(new ProofBundleVO.StorageObjectEvidence(
                        "storage/tenant/7/chunk/" + fileHash,
                        true,
                        "node-a",
                        (long) originalFile.length,
                        "etag-a",
                        fileHash,
                        true,
                        true
                ))),
                new ProofBundleVO.MerkleEvidence(
                        tree.proofAlgorithm(),
                        tree.merkleRoot(),
                        leaf.leafHash(),
                        leaf.leafIndex(),
                        leaf.proofPath().stream()
                                .map(node -> new ProofBundleVO.ProofNode(node.position(), node.hash()))
                                .toList()
                ),
                new ProofBundleVO.ChainEvidence("tx-batch", tree.merkleRoot(), "tx-file"),
                new ProofBundleVO.IssuerEvidence("RecordPlatform", "P1.2-proof-bundle", "COMPLETED", null, null),
                new ProofBundleVO.VerificationPolicy(
                        "SHA-256",
                        "hex(sha256('leaf\\n' + fileHash.trim()))",
                        "hex(sha256('node\\n' + leftHash.trim() + '\\n' + rightHash.trim()))",
                        "fileHash ASC, internal fileId ASC at issuance time",
                        "duplicate the last leaf hash when a tree level has an odd leaf count",
                        "apply each proofPath node from leaf to root; LEFT prepends sibling, RIGHT appends sibling"
                ),
                List.of("verify")
        );
    }

    /**
     * 替换证明包里的 Merkle 证明段。
     */
    private ProofBundleVO withMerkle(ProofBundleVO bundle, ProofBundleVO.MerkleEvidence merkle) {
        return new ProofBundleVO(
                bundle.contractVersion(),
                bundle.manifest(),
                bundle.file(),
                bundle.storage(),
                merkle,
                bundle.chain(),
                bundle.issuer(),
                bundle.verificationPolicy(),
                bundle.verificationGuide()
        );
    }

    /**
     * 替换证明包里的链上回执段。
     */
    private ProofBundleVO withChain(ProofBundleVO bundle, ProofBundleVO.ChainEvidence chain) {
        return new ProofBundleVO(
                bundle.contractVersion(),
                bundle.manifest(),
                bundle.file(),
                bundle.storage(),
                bundle.merkle(),
                chain,
                bundle.issuer(),
                bundle.verificationPolicy(),
                bundle.verificationGuide()
        );
    }

    /**
     * 将文本转成 UTF-8 字节数组。
     */
    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 计算测试输入的 SHA-256 小写十六进制。
     */
    private String sha256Hex(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is not available", e);
        }
    }
}
