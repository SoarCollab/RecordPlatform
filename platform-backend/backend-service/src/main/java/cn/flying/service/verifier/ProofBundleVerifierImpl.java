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
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Default offline verifier for proof-bundle.v1 Merkle proof bundles.
 */
@Service
@RequiredArgsConstructor
public class ProofBundleVerifierImpl implements ProofBundleVerifier {

    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final String COMPLETED_STATUS = "COMPLETED";
    private static final String SUPPORTED_ALGORITHM_SUITE = "RP-AES256-GCM-CHUNK-CHAIN-V1";
    private static final String SUPPORTED_SIGNATURE_SUITE = "UNSIGNED-V1";
    private static final String SUPPORTED_KEM_SUITE = "NONE-V1";
    private static final String SUPPORTED_PROOF_SUITE = "RP-MERKLE-SHA256-V1";

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
        validateFileEvidence(file, issues);
        validateOriginalContent(originalFile, bundle.storage(), issues);
        String computedLeafHash = validateMerkleLeaf(file, merkle, issues);
        String computedMerkleRoot = validateMerklePath(merkle, computedLeafHash, issues);
        validateChainEvidence(chain, merkle, issues);
        validateIssuerEvidence(issuer, issues);
        validateStorageEvidence(bundle.storage(), issues);
        validateVerificationPolicy(bundle.verificationPolicy(), issues);

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
     * Validates required file evidence fields used by chain and Merkle checks.
     */
    private void validateFileEvidence(ProofBundleVO.FileEvidence file, List<ProofVerificationIssue> issues) {
        if (file == null) {
            issues.add(missing("file", "缺少文件证明信息"));
            return;
        }
        if (!StringUtils.hasText(file.fileHash())) {
            issues.add(missing("file.fileHash", "缺少文件哈希"));
            return;
        }
    }

    /**
     * Validates original bytes against ordered chunk hashes exported in storage evidence.
     */
    private void validateOriginalContent(byte[] originalFile,
                                         ProofBundleVO.StorageEvidence storage,
                                         List<ProofVerificationIssue> issues) {
        if (originalFile == null) {
            return;
        }
        if (storage == null || CollectionUtils.isEmpty(storage.objects())) {
            issues.add(missing("storage.objects", "缺少存储分片证明信息"));
            return;
        }

        long offset = 0;
        List<ProofBundleVO.StorageObjectEvidence> objects = storage.objects();
        for (int i = 0; i < objects.size(); i++) {
            ProofBundleVO.StorageObjectEvidence object = objects.get(i);
            String fieldPrefix = "storage.objects[" + i + "]";
            if (object == null) {
                issues.add(missing(fieldPrefix, "缺少存储分片证明"));
                return;
            }
            if (!validateChunkIndex(object, i, fieldPrefix, issues)
                    || !validateChunkHashFields(object, fieldPrefix, issues)) {
                return;
            }

            long chunkSize = object.size();
            if (offset + chunkSize > originalFile.length) {
                issues.add(issue(
                        ProofVerificationCode.FILE_HASH_MISMATCH,
                        ProofVerificationSeverity.ERROR,
                        fieldPrefix + ".size",
                        "证明包分片长度超过原始文件长度"
                ));
                return;
            }

            String computedChunkHash = sha256Hex(originalFile, Math.toIntExact(offset), Math.toIntExact(chunkSize));
            if (!computedChunkHash.equalsIgnoreCase(normalizeSha256(object.plainHash()))) {
                issues.add(issue(
                        ProofVerificationCode.FILE_HASH_MISMATCH,
                        ProofVerificationSeverity.ERROR,
                        fieldPrefix + ".plainHash",
                        "原始文件分片 SHA-256 与证明包不一致"
                ));
                return;
            }
            offset += chunkSize;
        }

        if (offset != originalFile.length) {
            issues.add(issue(
                    ProofVerificationCode.FILE_HASH_MISMATCH,
                    ProofVerificationSeverity.ERROR,
                    "storage.objects",
                    "证明包分片总长度与原始文件长度不一致"
            ));
        }
    }

    /**
     * Validates that the chunk order is explicit and contiguous.
     */
    private boolean validateChunkIndex(ProofBundleVO.StorageObjectEvidence object,
                                       int expectedIndex,
                                       String fieldPrefix,
                                       List<ProofVerificationIssue> issues) {
        if (object.index() == null) {
            issues.add(missing(fieldPrefix + ".index", "缺少分片序号"));
            return false;
        }
        if (object.index() != expectedIndex) {
            issues.add(issue(
                    ProofVerificationCode.FILE_HASH_MISMATCH,
                    ProofVerificationSeverity.ERROR,
                    fieldPrefix + ".index",
                    "证明包分片序号必须从 0 连续递增"
            ));
            return false;
        }
        return true;
    }

    /**
     * Validates chunk fields required for local original-file verification.
     */
    private boolean validateChunkHashFields(ProofBundleVO.StorageObjectEvidence object,
                                            String fieldPrefix,
                                            List<ProofVerificationIssue> issues) {
        if (object.size() == null || object.size() < 0) {
            issues.add(missing(fieldPrefix + ".size", "缺少有效分片长度"));
            return false;
        }
        if (!StringUtils.hasText(object.plainHash())) {
            issues.add(missing(fieldPrefix + ".plainHash", "缺少明文分片哈希"));
            return false;
        }
        if (object.size() > Integer.MAX_VALUE) {
            issues.add(issue(
                    ProofVerificationCode.FILE_HASH_MISMATCH,
                    ProofVerificationSeverity.ERROR,
                    fieldPrefix + ".size",
                    "单个证明分片超过本地验证器支持的字节数组长度"
            ));
            return false;
        }
        return true;
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
                        "存储对象元数据哈希与密文分片哈希不一致"
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
            if (object.size() != null
                    && object.contentLength() != null
                    && !Objects.equals(object.size(), object.contentLength())) {
                issues.add(issue(
                        ProofVerificationCode.STORAGE_HASH_MISMATCH,
                        ProofVerificationSeverity.ERROR,
                        fieldPrefix + ".contentLength",
                        "存储对象长度与 manifest 分片长度不一致"
                ));
            }
        }
    }

    /**
     * Validates required crypto agility suite fields declared by the proof bundle.
     */
    private void validateVerificationPolicy(ProofBundleVO.VerificationPolicy policy,
                                            List<ProofVerificationIssue> issues) {
        if (policy == null) {
            issues.add(issue(
                    ProofVerificationCode.UNSUPPORTED_ALGORITHM,
                    ProofVerificationSeverity.ERROR,
                    "verificationPolicy",
                    "证明包缺少验证策略"
            ));
            return;
        }
        validateRequiredSuite("verificationPolicy.algorithmSuite", policy.algorithmSuite(),
                SUPPORTED_ALGORITHM_SUITE, issues);
        validateRequiredSuite("verificationPolicy.signatureSuite", policy.signatureSuite(),
                SUPPORTED_SIGNATURE_SUITE, issues);
        validateRequiredSuite("verificationPolicy.kemSuite", policy.kemSuite(),
                SUPPORTED_KEM_SUITE, issues);
        validateRequiredSuite("verificationPolicy.proofSuite", policy.proofSuite(),
                SUPPORTED_PROOF_SUITE, issues);
        if (policy.deprecatedAfter() != null && !policy.deprecatedAfter().after(new Date())) {
            issues.add(issue(
                    ProofVerificationCode.UNSUPPORTED_ALGORITHM,
                    ProofVerificationSeverity.ERROR,
                    "verificationPolicy.deprecatedAfter",
                    "证明包声明的密码套件已废弃"
            ));
        }
    }

    /**
     * Rejects absent or unsupported suite identifiers.
     */
    private void validateRequiredSuite(String field,
                                       String actual,
                                       String supported,
                                       List<ProofVerificationIssue> issues) {
        if (!StringUtils.hasText(actual)) {
            issues.add(issue(
                    ProofVerificationCode.UNSUPPORTED_ALGORITHM,
                    ProofVerificationSeverity.ERROR,
                    field,
                    "证明包缺少密码套件"
            ));
            return;
        }
        if (!supported.equals(actual)) {
            issues.add(issue(
                    ProofVerificationCode.UNSUPPORTED_ALGORITHM,
                    ProofVerificationSeverity.ERROR,
                    field,
                    "不支持的密码套件"
            ));
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
        return sha256Hex(value, 0, value.length);
    }

    /**
     * Calculates lowercase SHA-256 hex for a byte-array slice.
     */
    private String sha256Hex(byte[] value, int offset, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            digest.update(value, offset, length);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is not available", e);
        }
    }

    /**
     * Normalizes optional algorithm-prefixed SHA-256 strings from upload metadata.
     */
    private String normalizeSha256(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.regionMatches(true, 0, "sha256:", 0, "sha256:".length())) {
            return trimmed.substring("sha256:".length());
        }
        return trimmed;
    }
}
