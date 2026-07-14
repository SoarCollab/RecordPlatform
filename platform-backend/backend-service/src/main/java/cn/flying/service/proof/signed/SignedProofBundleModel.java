package cn.flying.service.proof.signed;

import java.util.List;

/**
 * Signed proof ZIP 中各 canonical JSON 条目的稳定业务合同。
 */
public final class SignedProofBundleModel {

    private SignedProofBundleModel() {
    }

    /** 顶层签名 manifest。 */
    public record Manifest(
            String schemaVersion,
            String proofId,
            String fileId,
            Integer fileVersion,
            String leafId,
            String batchNo,
            String issuedAt,
            String issuedStatus,
            String statusLocation,
            SignatureMetadata signature,
            List<EntryDigest> entries
    ) {
    }

    /** 顶层 manifest 中的签名 key 元数据。 */
    public record SignatureMetadata(
            String algorithm,
            String keyId,
            Integer keyVersion,
            String publicKeyFingerprint,
            String verificationKeyLocation
    ) {
    }

    /** 一个被 manifest 摘要绑定的 ZIP 证据条目。 */
    public record EntryDigest(
            String name,
            String mediaType,
            String sha256,
            long size,
            boolean required,
            boolean present
    ) {
    }

    /** manifest 构建所需、签发后不可变的上下文。 */
    public record ManifestSeed(
            String proofId,
            String fileId,
            Integer fileVersion,
            String leafId,
            String batchNo,
            String issuedAt,
            String issuedStatus,
            String statusLocation
    ) {
    }

    /** 除顶层 manifest/JWS 外的证据 payload。 */
    public record EvidencePayloads(
            String contentHash,
            ChunkManifestEvidence chunkManifest,
            MerkleProofEvidence merkleProof,
            BlockchainReceiptEvidence blockchainReceipt,
            VerificationPolicyEvidence verificationPolicy,
            String readme
    ) {
    }

    /** 明确拆分内容、链记录和 manifest 摘要的分片证据。 */
    public record ChunkManifestEvidence(
            String schemaVersion,
            String fileId,
            Integer fileVersion,
            String contentHash,
            String chainRecordId,
            String manifestHash,
            String sourceSchema,
            String hashAlgorithm,
            long chunkSize,
            Integer chunkCount,
            long totalSize,
            String encryptionAlgorithm,
            String storageBackend,
            List<ChunkEvidence> chunks
    ) {
    }

    /** 一个按索引排序的分片证据。 */
    public record ChunkEvidence(
            int index,
            String plainHash,
            String cipherHash,
            long size,
            String objectPath,
            String checksumAlgorithm
    ) {
    }

    /** 明确绑定 evidenceHash 的 Merkle inclusion proof。 */
    public record MerkleProofEvidence(
            String schemaVersion,
            String evidenceType,
            String evidenceHash,
            String proofAlgorithm,
            String merkleRoot,
            String leafHash,
            Integer leafIndex,
            List<ProofNode> proofPath
    ) {
    }

    /** 一个 Merkle sibling 节点。 */
    public record ProofNode(
            String position,
            String hash
    ) {
    }

    /** 单文件链记录、批次根回执和合约注册表快照。 */
    public record BlockchainReceiptEvidence(
            String schemaVersion,
            String chainRecordId,
            String fileTransactionHash,
            String batchTransactionHash,
            String batchChainRoot,
            String confirmationSource,
            ContractRegistryEvidence contractRegistry
    ) {
    }

    /** 签发批次绑定的不可变 contract registry entry。 */
    public record ContractRegistryEvidence(
            String schemaVersion,
            String registryFingerprint,
            String contractName,
            String semanticVersion,
            String chainType,
            String chainId,
            String groupId,
            String contractAddress,
            String abiFingerprintAlgorithm,
            String abiFingerprint,
            String artifactBytecodeSha256,
            String onChainCodeSha256,
            String deploymentTransactionHash,
            Long deploymentBlockNumber,
            String status,
            String effectiveAt,
            String upgradeStrategy
    ) {
    }

    /** 外部 verifier 必须执行的确定性规则。 */
    public record VerificationPolicyEvidence(
            String schemaVersion,
            List<String> evidenceSchemas,
            String contentHashAlgorithm,
            String fileHashRule,
            String manifestHashRule,
            String merkleLeafRule,
            String merkleParentRule,
            String proofPathRule,
            ChainReceiptPolicy chainReceiptPolicy,
            ContractRegistryPolicy contractRegistryPolicy,
            String signatureFormat,
            String statusPolicy,
            String zipPolicy,
            String textEntryPolicy
    ) {
    }

    /** 链回执来源、交易哈希和批次根的精确组合规则。 */
    public record ChainReceiptPolicy(
            String rootPattern,
            String transactionHashPattern,
            String writeSource,
            List<String> querySources,
            String writeTransactionRule,
            String queryTransactionRule
    ) {
    }

    /** 签发时 contract registry 快照必须满足的结构与指纹规则。 */
    public record ContractRegistryPolicy(
            String schemaVersion,
            String contractName,
            String semanticVersionPattern,
            List<String> allowedChainTypes,
            List<String> fiscoChainTypes,
            String groupIdRule,
            String addressPattern,
            String abiFingerprintAlgorithm,
            String sha256Pattern,
            String deploymentEvidenceRule,
            List<String> issuableStatuses,
            String effectiveAtRule,
            String upgradeStrategy,
            String registryFingerprintRule,
            List<String> registryFingerprintFields
    ) {
    }
}
