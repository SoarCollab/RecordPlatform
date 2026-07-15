package cn.flying.verifier.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stable business models shared by the signed proof producer and every public verifier entrypoint.
 */
public final class SignedProofBundleModel {

    private SignedProofBundleModel() {
    }

    /** Defensively copies a nullable contract list while preserving malformed null elements for validation. */
    private static <T> List<T> immutableList(List<T> value) {
        return value == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(value));
    }

    /** Top-level signed manifest. */
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
        public Manifest {
            entries = immutableList(entries);
        }
    }

    /** Signing-key metadata bound by the top-level manifest. */
    public record SignatureMetadata(
            String algorithm,
            String keyId,
            Integer keyVersion,
            String publicKeyFingerprint,
            String verificationKeyLocation
    ) {
    }

    /** Digest binding for one evidence entry. */
    public record EntryDigest(
            String name,
            String mediaType,
            String sha256,
            long size,
            boolean required,
            boolean present
    ) {
    }

    /** Immutable manifest context supplied by the producer. */
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

    /** Evidence payloads other than the manifest and compact JWS. */
    public record EvidencePayloads(
            String contentHash,
            ChunkManifestEvidence chunkManifest,
            MerkleProofEvidence merkleProof,
            BlockchainReceiptEvidence blockchainReceipt,
            VerificationPolicyEvidence verificationPolicy,
            String readme
    ) {
    }

    /** Chunk-manifest evidence with separated content, chain-record, and source-manifest hashes. */
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
        public ChunkManifestEvidence {
            chunks = immutableList(chunks);
        }
    }

    /** One ordered chunk evidence item. */
    public record ChunkEvidence(
            int index,
            String plainHash,
            String cipherHash,
            long size,
            String objectPath,
            String checksumAlgorithm
    ) {
    }

    /** Merkle inclusion proof bound to the source-manifest evidence hash. */
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
        public MerkleProofEvidence {
            proofPath = immutableList(proofPath);
        }

        /** Returns a defensive immutable copy so callers cannot retain or mutate record state. */
        public List<ProofNode> proofPath() {
            return immutableList(proofPath);
        }
    }

    /** One ordered Merkle sibling node. */
    public record ProofNode(
            String position,
            String hash
    ) {
    }

    /** Single-file receipt, batch-root receipt, and immutable contract-registry snapshot. */
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

    /** Immutable contract-registry entry used at issuance time. */
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

    /** Deterministic verification rules signed into the archive. */
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
        public VerificationPolicyEvidence {
            evidenceSchemas = immutableList(evidenceSchemas);
        }
    }

    /** Exact chain receipt source, transaction, and root rules. */
    public record ChainReceiptPolicy(
            String rootPattern,
            String transactionHashPattern,
            String writeSource,
            List<String> querySources,
            String writeTransactionRule,
            String queryTransactionRule
    ) {
        public ChainReceiptPolicy {
            querySources = immutableList(querySources);
        }
    }

    /** Exact structure and fingerprint rules for the immutable registry snapshot. */
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
        public ContractRegistryPolicy {
            allowedChainTypes = immutableList(allowedChainTypes);
            fiscoChainTypes = immutableList(fiscoChainTypes);
            issuableStatuses = immutableList(issuableStatuses);
            registryFingerprintFields = immutableList(registryFingerprintFields);
        }
    }
}
