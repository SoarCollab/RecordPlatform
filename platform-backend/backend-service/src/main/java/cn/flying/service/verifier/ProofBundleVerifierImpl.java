package cn.flying.service.verifier;

import cn.flying.common.util.JsonConverter;
import cn.flying.dao.vo.file.ProofBundleVO;
import cn.flying.service.attestation.MerkleProofNode;
import cn.flying.service.attestation.MerkleTreeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Default offline verifier for proof-bundle.v1 Merkle proof bundles.
 */
@Service
@RequiredArgsConstructor
public class ProofBundleVerifierImpl implements ProofBundleVerifier {

    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final String COMPLETED_STATUS = "COMPLETED";

    private final MerkleTreeService merkleTreeService;

    /**
     * Parses proof bundle JSON and verifies it against original file bytes.
     */
    @Override
    public ProofVerificationResult verify(byte[] originalFile, String bundleJson) {
        if (!StringUtils.hasText(bundleJson)) {
            return malformed("证明包 JSON 不能为空");
        }
        try {
            ProofBundleVO bundle = JsonConverter.parse(bundleJson, ProofBundleVO.class);
            return verify(originalFile, bundle);
        } catch (RuntimeException ex) {
            return malformed("证明包 JSON 无法解析");
        }
    }

    /**
     * Verifies a parsed proof bundle without reading backend session state or database rows.
     */
    @Override
    public ProofVerificationResult verify(byte[] originalFile, ProofBundleVO bundle) {
        if (bundle == null) {
            return malformed("证明包不能为空");
        }

        List<ProofVerificationIssue> issues = new ArrayList<>();
        String computedFileHash = calculateFileHash(originalFile, issues);
        ProofBundleVO.FileEvidence file = bundle.file();
        ProofBundleVO.MerkleEvidence merkle = bundle.merkle();
        ProofBundleVO.ChainEvidence chain = bundle.chain();
        ProofBundleVO.IssuerEvidence issuer = bundle.issuer();

        validateContract(bundle, issues);
        validateFileHash(file, computedFileHash, issues);
        String computedLeafHash = validateMerkleLeaf(file, merkle, issues);
        String computedMerkleRoot = validateMerklePath(merkle, computedLeafHash, issues);
        validateChainEvidence(chain, merkle, issues);
        validateIssuerEvidence(issuer, issues);
        validateStorageEvidence(bundle.storage(), issues);

        return buildResult(
                issues,
                bundle,
                computedFileHash,
                computedLeafHash,
                computedMerkleRoot
        );
    }

    /**
     * Builds a malformed-bundle result without relying on parsed bundle fields.
     */
    private ProofVerificationResult malformed(String message) {
        return new ProofVerificationResult(
                false,
                List.of(issue(ProofVerificationCode.MALFORMED_BUNDLE, ProofVerificationSeverity.ERROR, "bundle", message)),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    /**
     * Validates the proof bundle contract version.
     */
    private void validateContract(ProofBundleVO bundle, List<ProofVerificationIssue> issues) {
        if (!ProofBundleVO.CONTRACT_VERSION.equals(bundle.contractVersion())) {
            issues.add(issue(
                    ProofVerificationCode.UNSUPPORTED_CONTRACT_VERSION,
                    ProofVerificationSeverity.ERROR,
                    "contractVersion",
                    "不支持的证明包合同版本"
            ));
        }
    }

    /**
     * Calculates SHA-256 for the original file bytes.
     */
    private String calculateFileHash(byte[] originalFile, List<ProofVerificationIssue> issues) {
        if (originalFile == null) {
            issues.add(issue(
                    ProofVerificationCode.MISSING_ORIGINAL_FILE,
                    ProofVerificationSeverity.ERROR,
                    "originalFile",
                    "原始文件不能为空"
            ));
            return null;
        }
        return sha256Hex(originalFile);
    }

    /**
     * Validates the local file hash against bundle file evidence.
     */
    private void validateFileHash(ProofBundleVO.FileEvidence file, String computedFileHash,
                                  List<ProofVerificationIssue> issues) {
        if (file == null) {
            issues.add(missing("file", "缺少文件证明信息"));
            return;
        }
        if (!StringUtils.hasText(file.fileHash())) {
            issues.add(missing("file.fileHash", "缺少文件哈希"));
            return;
        }
        if (StringUtils.hasText(computedFileHash) && !file.fileHash().equalsIgnoreCase(computedFileHash)) {
            issues.add(issue(
                    ProofVerificationCode.FILE_HASH_MISMATCH,
                    ProofVerificationSeverity.ERROR,
                    "file.fileHash",
                    "原始文件 SHA-256 与证明包文件哈希不一致"
            ));
        }
    }

    /**
     * Validates proof algorithm and recomputes the expected Merkle leaf hash.
     */
    private String validateMerkleLeaf(ProofBundleVO.FileEvidence file, ProofBundleVO.MerkleEvidence merkle,
                                      List<ProofVerificationIssue> issues) {
        if (merkle == null) {
            issues.add(missing("merkle", "缺少 Merkle 证明信息"));
            return null;
        }
        if (!MerkleTreeService.PROOF_ALGORITHM.equals(merkle.proofAlgorithm())) {
            issues.add(issue(
                    ProofVerificationCode.UNSUPPORTED_ALGORITHM,
                    ProofVerificationSeverity.ERROR,
                    "merkle.proofAlgorithm",
                    "不支持的 Merkle 证明算法"
            ));
        }
        if (!StringUtils.hasText(merkle.leafHash())) {
            issues.add(missing("merkle.leafHash", "缺少 Merkle 叶子哈希"));
        }
        if (file == null || !StringUtils.hasText(file.fileHash())) {
            return null;
        }

        String computedLeafHash = merkleTreeService.calculateLeafHash(file.fileHash());
        if (StringUtils.hasText(merkle.leafHash()) && !merkle.leafHash().equalsIgnoreCase(computedLeafHash)) {
            issues.add(issue(
                    ProofVerificationCode.LEAF_HASH_MISMATCH,
                    ProofVerificationSeverity.ERROR,
                    "merkle.leafHash",
                    "文件哈希重算的 Merkle 叶子哈希与证明包不一致"
            ));
        }
        return computedLeafHash;
    }

    /**
     * Recomputes the Merkle root from the expected leaf hash and proof path.
     */
    private String validateMerklePath(ProofBundleVO.MerkleEvidence merkle, String computedLeafHash,
                                      List<ProofVerificationIssue> issues) {
        if (merkle == null) {
            return null;
        }
        if (!StringUtils.hasText(merkle.merkleRoot())) {
            issues.add(missing("merkle.merkleRoot", "缺少 Merkle 根"));
            return null;
        }
        if (merkle.proofPath() == null) {
            issues.add(missing("merkle.proofPath", "缺少 Merkle 证明路径"));
            return null;
        }
        if (!StringUtils.hasText(computedLeafHash)) {
            return null;
        }

        String computedMerkleRoot = merkleTreeService.calculateRootFromProof(
                computedLeafHash,
                toMerkleProofPath(merkle.proofPath(), issues)
        );
        if (!StringUtils.hasText(computedMerkleRoot)
                || !computedMerkleRoot.equalsIgnoreCase(merkle.merkleRoot())) {
            issues.add(issue(
                    ProofVerificationCode.PROOF_PATH_MISMATCH,
                    ProofVerificationSeverity.ERROR,
                    "merkle.proofPath",
                    "Merkle 证明路径无法重算出证明包声明的根"
            ));
        }
        return computedMerkleRoot;
    }

    /**
     * Converts bundle proof nodes into the canonical Merkle proof node type.
     */
    private List<MerkleProofNode> toMerkleProofPath(List<ProofBundleVO.ProofNode> proofPath,
                                                    List<ProofVerificationIssue> issues) {
        if (CollectionUtils.isEmpty(proofPath)) {
            return List.of();
        }
        List<MerkleProofNode> nodes = new ArrayList<>(proofPath.size());
        for (int i = 0; i < proofPath.size(); i++) {
            ProofBundleVO.ProofNode node = proofPath.get(i);
            if (node == null || !StringUtils.hasText(node.position()) || !StringUtils.hasText(node.hash())) {
                issues.add(issue(
                        ProofVerificationCode.PROOF_PATH_MISMATCH,
                        ProofVerificationSeverity.ERROR,
                        "merkle.proofPath[" + i + "]",
                        "Merkle 证明路径节点缺少方向或哈希"
                ));
                nodes.add(new MerkleProofNode(null, null));
                continue;
            }
            nodes.add(new MerkleProofNode(node.position(), node.hash()));
        }
        return List.copyOf(nodes);
    }

    /**
     * Validates chain receipt fields that are self-checkable inside the bundle.
     */
    private void validateChainEvidence(ProofBundleVO.ChainEvidence chain, ProofBundleVO.MerkleEvidence merkle,
                                       List<ProofVerificationIssue> issues) {
        if (chain == null) {
            issues.add(missing("chain", "缺少链上回执摘要"));
            return;
        }
        if (!StringUtils.hasText(chain.batchTransactionHash())) {
            issues.add(issue(
                    ProofVerificationCode.CHAIN_RECEIPT_MISSING,
                    ProofVerificationSeverity.ERROR,
                    "chain.batchTransactionHash",
                    "缺少批量根上链交易哈希"
            ));
        }
        if (!StringUtils.hasText(chain.batchChainFileHash())) {
            issues.add(issue(
                    ProofVerificationCode.CHAIN_RECEIPT_MISSING,
                    ProofVerificationSeverity.ERROR,
                    "chain.batchChainFileHash",
                    "缺少批量根链上文件哈希"
            ));
            return;
        }
        if (merkle != null
                && StringUtils.hasText(merkle.merkleRoot())
                && !chain.batchChainFileHash().equalsIgnoreCase(merkle.merkleRoot())) {
            issues.add(issue(
                    ProofVerificationCode.CHAIN_ROOT_MISMATCH,
                    ProofVerificationSeverity.ERROR,
                    "chain.batchChainFileHash",
                    "链上文件哈希与证明包 Merkle 根不一致"
            ));
        }
    }

    /**
     * Validates issuer status metadata without requiring a platform session.
     */
    private void validateIssuerEvidence(ProofBundleVO.IssuerEvidence issuer, List<ProofVerificationIssue> issues) {
        if (issuer == null) {
            issues.add(missing("issuer", "缺少签发方信息"));
            return;
        }
        if (StringUtils.hasText(issuer.batchStatus()) && !COMPLETED_STATUS.equals(issuer.batchStatus())) {
            issues.add(issue(
                    ProofVerificationCode.BATCH_STATUS_NOT_COMPLETED,
                    ProofVerificationSeverity.ERROR,
                    "issuer.batchStatus",
                    "批量存证状态不是 COMPLETED"
            ));
        }
    }

    /**
     * Reports storage metadata inconsistencies present in the bundle.
     */
    private void validateStorageEvidence(ProofBundleVO.StorageEvidence storage, List<ProofVerificationIssue> issues) {
        if (storage == null || CollectionUtils.isEmpty(storage.objects())) {
            return;
        }
        for (int i = 0; i < storage.objects().size(); i++) {
            ProofBundleVO.StorageObjectEvidence object = storage.objects().get(i);
            if (object == null) {
                continue;
            }
            String fieldPrefix = "storage.objects[" + i + "]";
            if (!object.exists()) {
                issues.add(issue(
                        ProofVerificationCode.STORAGE_OBJECT_MISSING,
                        ProofVerificationSeverity.WARNING,
                        fieldPrefix + ".exists",
                        "证明包记录的存储对象不存在"
                ));
            }
            if (Boolean.FALSE.equals(object.metadataHashMatches())) {
                issues.add(issue(
                        ProofVerificationCode.STORAGE_HASH_MISMATCH,
                        ProofVerificationSeverity.ERROR,
                        fieldPrefix + ".metadataHashMatches",
                        "存储对象元数据哈希与文件哈希不一致"
                ));
            }
            if (Boolean.FALSE.equals(object.tenantMatches())) {
                issues.add(issue(
                        ProofVerificationCode.STORAGE_TENANT_MISMATCH,
                        ProofVerificationSeverity.ERROR,
                        fieldPrefix + ".tenantMatches",
                        "存储对象租户元数据不匹配"
                ));
            }
        }
    }

    /**
     * Builds the final verifier result with a stable evidence summary.
     */
    private ProofVerificationResult buildResult(List<ProofVerificationIssue> issues, ProofBundleVO bundle,
                                                String computedFileHash, String computedLeafHash,
                                                String computedMerkleRoot) {
        ProofBundleVO.FileEvidence file = bundle.file();
        ProofBundleVO.MerkleEvidence merkle = bundle.merkle();
        ProofBundleVO.ChainEvidence chain = bundle.chain();
        ProofBundleVO.IssuerEvidence issuer = bundle.issuer();
        List<ProofVerificationIssue> immutableIssues = List.copyOf(issues);
        boolean valid = immutableIssues.stream()
                .noneMatch(issue -> issue.severity() == ProofVerificationSeverity.ERROR);

        return new ProofVerificationResult(
                valid,
                immutableIssues,
                bundle.contractVersion(),
                merkle == null ? null : merkle.proofAlgorithm(),
                file == null ? null : file.fileHash(),
                computedFileHash,
                merkle == null ? null : merkle.leafHash(),
                computedLeafHash,
                merkle == null ? null : merkle.merkleRoot(),
                computedMerkleRoot,
                chain == null ? null : chain.batchTransactionHash(),
                chain == null ? null : chain.batchChainFileHash(),
                chain == null ? null : chain.fileTransactionHash(),
                issuer == null ? null : issuer.platform(),
                issuer == null ? null : issuer.contract(),
                issuer == null ? null : issuer.batchStatus()
        );
    }

    /**
     * Creates a missing-field error.
     */
    private ProofVerificationIssue missing(String field, String message) {
        return issue(ProofVerificationCode.MISSING_REQUIRED_FIELD, ProofVerificationSeverity.ERROR, field, message);
    }

    /**
     * Creates one verifier issue.
     */
    private ProofVerificationIssue issue(ProofVerificationCode code, ProofVerificationSeverity severity,
                                         String field, String message) {
        return new ProofVerificationIssue(code, severity, field, message);
    }

    /**
     * Calculates lowercase SHA-256 hex for file bytes.
     */
    private String sha256Hex(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is not available", e);
        }
    }
}
