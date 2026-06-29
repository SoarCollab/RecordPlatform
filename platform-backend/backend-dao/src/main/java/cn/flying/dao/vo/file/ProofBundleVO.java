package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Date;
import java.util.List;

/**
 * Exportable proof bundle contract for third-party file attestation checks.
 */
@Schema(description = "文件证明包")
public record ProofBundleVO(
        @Schema(description = "证明包合同版本")
        String contractVersion,
        @Schema(description = "证明包清单")
        Manifest manifest,
        @Schema(description = "文件公开元数据")
        FileEvidence file,
        @Schema(description = "存储公开元数据")
        StorageEvidence storage,
        @Schema(description = "Merkle 证明")
        MerkleEvidence merkle,
        @Schema(description = "链上回执摘要")
        ChainEvidence chain,
        @Schema(description = "签发方元数据")
        IssuerEvidence issuer,
        @Schema(description = "验证策略")
        VerificationPolicy verificationPolicy,
        @Schema(description = "人工验证说明")
        List<String> verificationGuide
) {

    public static final String CONTRACT_VERSION = "proof-bundle.v1";

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
            String batchNo
    ) {
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
            @Schema(description = "逻辑对象路径")
            String objectPath,
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
            @Schema(description = "批量根上链交易哈希")
            String batchTransactionHash,
            @Schema(description = "批量根上链文件哈希")
            String batchChainFileHash,
            @Schema(description = "文件上链交易哈希")
            String fileTransactionHash
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
