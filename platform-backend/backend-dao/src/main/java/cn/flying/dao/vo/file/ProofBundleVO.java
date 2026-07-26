package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Date;
import java.util.List;

/**
 * Exportable proof bundle contract for third-party file attestation checks.
 */
@Schema(description = "文件证明包")
public record ProofBundleVO(
        @Schema(description = "证明包合同版本", requiredMode = Schema.RequiredMode.REQUIRED)
        String contractVersion,
        @Schema(description = "证明包清单", requiredMode = Schema.RequiredMode.REQUIRED)
        Manifest manifest,
        @Schema(description = "文件公开元数据", requiredMode = Schema.RequiredMode.REQUIRED)
        FileEvidence file,
        @Schema(description = "存储公开元数据", requiredMode = Schema.RequiredMode.REQUIRED)
        StorageEvidence storage,
        @Schema(description = "Merkle 证明", requiredMode = Schema.RequiredMode.REQUIRED)
        MerkleEvidence merkle,
        @Schema(description = "链上回执摘要", requiredMode = Schema.RequiredMode.REQUIRED)
        ChainEvidence chain,
        @Schema(description = "签发方元数据", requiredMode = Schema.RequiredMode.REQUIRED)
        IssuerEvidence issuer,
        @Schema(description = "验证策略", requiredMode = Schema.RequiredMode.REQUIRED)
        VerificationPolicy verificationPolicy,
        @Schema(description = "人工验证说明", requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> verificationGuide
) {

    public static final String CONTRACT_VERSION = "proof-bundle.v1.1";

    /**
     * High-level proof bundle manifest.
     */
    public record Manifest(
            @Schema(description = "证明包类型")
            String type,
            @Schema(description = "证明包版本")
            String version,
            @Schema(description = "外部文件 ID")
            String fileId,
            @Schema(description = "外部叶子 ID")
            String leafId,
            @Schema(description = "批量存证号")
            String batchNo,
            @Schema(description = "Machine-readable manifest lifecycle status")
            String manifestStatus,
            @Schema(description = "Manifest evidence classification")
            String manifestClassification,
            @Schema(description = "Manifest error/reason code; null for active proof evidence")
            String manifestErrorCode,
            @Schema(description = "Whether typed legacy download compatibility is explicitly allowed")
            boolean legacyDownloadAllowed
    ) {

        /**
         * Keeps existing proof construction source-compatible while emitting active manifest fields.
         */
        public Manifest(String type, String version, String fileId, String leafId, String batchNo) {
            this(type, version, fileId, leafId, batchNo,
                    "ACTIVE", "ALREADY_MANIFEST", null, false);
        }
    }

    /**
     * Public file metadata included in the proof bundle.
     */
    public record FileEvidence(
            @Schema(description = "外部文件 ID")
            String fileId,
            @Schema(description = "文件名称")
            String fileName,
            @Schema(description = "文件哈希")
            String fileHash,
            @Schema(description = "文件链上交易哈希")
            String transactionHash,
            @Schema(description = "文件大小")
            Long fileSize,
            @Schema(description = "内容类型")
            String contentType,
            @Schema(description = "分片数量")
            Integer chunkCount,
            @Schema(description = "版本号")
            Integer version,
            @Schema(description = "是否最新版本")
            Integer isLatest,
            @Schema(description = "创建时间")
            Date createTime
    ) {
    }

    /**
     * Storage metadata snapshot used for offline inspection.
     */
    public record StorageEvidence(
            @Schema(description = "存储对象列表")
            List<StorageObjectEvidence> objects
    ) {
    }

    /**
     * One storage object metadata entry.
     */
    public record StorageObjectEvidence(
            @Schema(description = "分片序号")
            Integer index,
            @Schema(description = "逻辑对象路径")
            String objectPath,
            @Schema(description = "明文分片 SHA-256")
            String plainHash,
            @Schema(description = "密文分片哈希")
            String cipherHash,
            @Schema(description = "分片长度")
            Long size,
            @Schema(description = "校验算法")
            String checksumAlgorithm,
            @Schema(description = "对象是否存在")
            boolean exists,
            @Schema(description = "存储节点名")
            String nodeName,
            @Schema(description = "对象长度")
            Long contentLength,
            @Schema(description = "ETag")
            String eTag,
            @Schema(description = "对象元数据文件哈希")
            String metadataHash,
            @Schema(description = "对象元数据哈希是否匹配")
            Boolean metadataHashMatches,
            @Schema(description = "对象路径租户是否匹配")
            Boolean tenantMatches
    ) {
    }

    /**
     * Merkle inclusion proof metadata.
     */
    public record MerkleEvidence(
            @Schema(description = "证明算法")
            String proofAlgorithm,
            @Schema(description = "Merkle 根")
            String merkleRoot,
            @Schema(description = "叶子哈希")
            String leafHash,
            @Schema(description = "叶子索引")
            Integer leafIndex,
            @Schema(description = "证明路径")
            List<ProofNode> proofPath
    ) {
    }

    /**
     * One sibling in a Merkle proof path.
     */
    public record ProofNode(
            @Schema(description = "兄弟节点位置：LEFT/RIGHT")
            String position,
            @Schema(description = "兄弟节点哈希")
            String hash
    ) {
    }

    /**
     * Chain receipt metadata that can be cross-checked outside the platform.
     */
    public record ChainEvidence(
            @Schema(
                    description = "批量根上链交易哈希",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                    nullable = true)
            String batchTransactionHash,
            @Schema(description = "批量根上链文件哈希", requiredMode = Schema.RequiredMode.REQUIRED)
            String batchChainFileHash,
            @Schema(
                    description = "文件上链交易哈希",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                    nullable = true)
            String fileTransactionHash,
            @Schema(
                    description = "批量根确认来源；响应丢失恢复时可为空",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                    nullable = true)
            String batchConfirmationSource,
            @Schema(
                    description = "签发批次绑定的不可变合约注册表快照",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            ContractRegistryEvidence contractRegistry
    ) {

        /**
         * 兼容读取旧测试或旧 Java 调用构造的 v1 结构；验证器会对缺失注册表失败关闭。
         */
        public ChainEvidence(
                String batchTransactionHash,
                String batchChainFileHash,
                String fileTransactionHash,
                String batchConfirmationSource
        ) {
            this(
                    batchTransactionHash,
                    batchChainFileHash,
                    fileTransactionHash,
                    batchConfirmationSource,
                    null);
        }
    }

    /**
     * Contract registry snapshot bound to the batch before the chain write.
     */
    public record ContractRegistryEvidence(
            @Schema(description = "注册表条目 schema", requiredMode = Schema.RequiredMode.REQUIRED)
            String schemaVersion,
            @Schema(description = "注册表关键字段 SHA-256", requiredMode = Schema.RequiredMode.REQUIRED)
            String registryFingerprint,
            @Schema(description = "合约名称", requiredMode = Schema.RequiredMode.REQUIRED)
            String contractName,
            @Schema(description = "合约语义版本", requiredMode = Schema.RequiredMode.REQUIRED)
            String semanticVersion,
            @Schema(description = "链适配器类型", requiredMode = Schema.RequiredMode.REQUIRED)
            String chainType,
            @Schema(description = "节点链 ID", requiredMode = Schema.RequiredMode.REQUIRED)
            String chainId,
            @Schema(
                    description = "FISCO 群组 ID；FISCO 必填，Besu 为空",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                    nullable = true)
            String groupId,
            @Schema(description = "合约地址", requiredMode = Schema.RequiredMode.REQUIRED)
            String contractAddress,
            @Schema(description = "ABI 指纹算法", requiredMode = Schema.RequiredMode.REQUIRED)
            String abiFingerprintAlgorithm,
            @Schema(description = "canonical ABI SHA-256", requiredMode = Schema.RequiredMode.REQUIRED)
            String abiSha256,
            @Schema(description = "creation bytecode SHA-256", requiredMode = Schema.RequiredMode.REQUIRED)
            String artifactBytecodeSha256,
            @Schema(description = "链上 runtime code SHA-256", requiredMode = Schema.RequiredMode.REQUIRED)
            String onChainCodeSha256,
            @Schema(
                    description = "部署交易哈希；旧部署兼容时可与区块号同时为空",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                    nullable = true)
            String deploymentTransactionHash,
            @Schema(
                    description = "部署区块号；旧部署兼容时可与交易哈希同时为空",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                    nullable = true)
            Long deploymentBlockNumber,
            @Schema(description = "生命周期状态", requiredMode = Schema.RequiredMode.REQUIRED)
            String status,
            @Schema(description = "生效时间", requiredMode = Schema.RequiredMode.REQUIRED)
            String effectiveAt,
            @Schema(description = "升级策略", requiredMode = Schema.RequiredMode.REQUIRED)
            String upgradeStrategy
    ) {
    }

    /**
     * Proof issuer metadata.
     */
    public record IssuerEvidence(
            @Schema(description = "签发平台")
            String platform,
            @Schema(description = "签发合同")
            String contract,
            @Schema(description = "批次状态")
            String batchStatus,
            @Schema(description = "签名算法；为空表示当前版本未签名")
            String signatureAlgorithm,
            @Schema(description = "签名；为空表示当前版本未签名")
            String signature
    ) {
    }

    /**
     * Deterministic verification policy for the proof bundle.
     */
    public record VerificationPolicy(
            @Schema(description = "内容加密算法套件")
            String algorithmSuite,
            @Schema(description = "签名算法套件；UNSIGNED-V1 表示当前证明包未签名")
            String signatureSuite,
            @Schema(description = "KEM/接收方密钥协商套件；NONE-V1 表示当前版本未使用 KEM")
            String kemSuite,
            @Schema(description = "证明构造套件")
            String proofSuite,
            @Schema(description = "密钥版本")
            Integer keyVersion,
            @Schema(description = "套件废弃时间；为空表示尚未计划废弃")
            Date deprecatedAfter,
            @Schema(description = "哈希算法")
            String hashAlgorithm,
            @Schema(description = "叶子哈希规则")
            String leafHashRule,
            @Schema(description = "父节点哈希规则")
            String parentHashRule,
            @Schema(description = "叶子排序规则")
            String leafOrdering,
            @Schema(description = "奇数叶子规则")
            String oddLeafRule,
            @Schema(description = "证明路径规则")
            String proofPathRule
    ) {
    }
}
