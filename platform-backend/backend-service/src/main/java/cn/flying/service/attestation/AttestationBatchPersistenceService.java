package cn.flying.service.attestation;

import cn.flying.common.util.JsonConverter;
import cn.flying.common.util.SnowflakeIdGenerator;
import cn.flying.dao.entity.AttestationBatch;
import cn.flying.dao.entity.AttestationBatchAttempt;
import cn.flying.dao.entity.AttestationBatchCandidate;
import cn.flying.dao.entity.AttestationLeaf;
import cn.flying.dao.mapper.AttestationBatchAttemptMapper;
import cn.flying.dao.mapper.AttestationBatchCandidateMapper;
import cn.flying.dao.mapper.AttestationBatchMapper;
import cn.flying.dao.mapper.AttestationLeafMapper;
import cn.flying.platformapi.response.ContractRegistryEntryResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 使用独立短事务持久化 Merkle 批次创建和链提交状态迁移。
 */
@Service
@RequiredArgsConstructor
public class AttestationBatchPersistenceService {

    public static final String EVIDENCE_TYPE_LEGACY_CHAIN_RECORD_ID = "LEGACY_CHAIN_RECORD_ID";
    private static final String ATTEMPT_CLAIMED = "CLAIMED";
    private static final String ATTEMPT_COMPLETED = "COMPLETED";
    private static final String ATTEMPT_RETRY = "RETRY_SCHEDULED";
    private static final String ATTEMPT_MANUAL = "MANUAL_REVIEW";
    private static final String ATTEMPT_STALE = "STALE_IGNORED";

    private final AttestationBatchMapper batchMapper;
    private final AttestationLeafMapper leafMapper;
    private final AttestationBatchAttemptMapper attemptMapper;
    private final AttestationBatchCandidateMapper candidateMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 在一个独立事务中创建 batch 和全部 leaf；同一幂等键已存在时直接复用。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public AttestationBatch createOrGet(Long tenantId, String idempotencyKey, MerkleTreeResult tree) {
        List<AttestationLeafEvidence> evidence = tree.leaves().stream()
                .map(leaf -> new AttestationLeafEvidence(
                        leaf.fileId(),
                        1,
                        null,
                        EVIDENCE_TYPE_LEGACY_CHAIN_RECORD_ID,
                        leaf.fileHash(),
                        leaf.fileHash()))
                .toList();
        return createOrGet(tenantId, idempotencyKey, tree, evidence, null);
    }

    /**
     * 在一个独立事务中按显式证据元数据创建或复用 batch 和全部 leaf。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public AttestationBatch createOrGet(Long tenantId,
                                        String idempotencyKey,
                                        MerkleTreeResult tree,
                                        List<AttestationLeafEvidence> evidence) {
        return createOrGet(tenantId, idempotencyKey, tree, evidence, null);
    }

    /**
     * 在一个独立事务中创建或复用绑定了不可变合约注册表的 batch。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public AttestationBatch createOrGet(
            Long tenantId,
            String idempotencyKey,
            MerkleTreeResult tree,
            List<AttestationLeafEvidence> evidence,
            ContractRegistryEntryResponse contractRegistry
    ) {
        AttestationBatch existing = selectByIdempotencyKey(tenantId, idempotencyKey);
        if (existing != null) {
            return existing;
        }

        return insertBatchAndLeaves(
                tenantId, idempotencyKey, tree, evidence, contractRegistry);
    }

    /**
     * 把当前 candidate claim、batch 和全部 leaf 在同一事务中原子绑定。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public AttestationBatch createFromClaimedCandidates(Long tenantId,
                                                        String idempotencyKey,
                                                        MerkleTreeResult tree,
                                                        AttestationCandidateClaim claim) {
        return createFromClaimedCandidates(
                tenantId, idempotencyKey, tree, claim, null);
    }

    /**
     * 在同一事务中把 candidate claim、batch、leaf 和合约注册表快照原子绑定。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public AttestationBatch createFromClaimedCandidates(
            Long tenantId,
            String idempotencyKey,
            MerkleTreeResult tree,
            AttestationCandidateClaim claim,
            ContractRegistryEntryResponse contractRegistry
    ) {
        if (claim == null || claim.size() == 0
                || !Objects.equals(tenantId, claim.tenantId())) {
            throw new IllegalArgumentException("A non-empty tenant-scoped candidate claim is required");
        }
        List<AttestationLeafEvidence> evidence = claim.candidates().stream()
                .map(this::toLeafEvidence)
                .toList();

        AttestationBatch batch = selectByIdempotencyKey(tenantId, idempotencyKey);
        if (batch == null) {
            batch = insertBatchAndLeaves(
                    tenantId, idempotencyKey, tree, evidence, contractRegistry);
        }

        int candidatesUpdated = candidateMapper.markBatched(
                tenantId, claim.claimToken(), batch.getId());
        if (candidatesUpdated != claim.size()) {
            throw new IllegalStateException("Candidate claim no longer owns every batch member");
        }
        return batch;
    }

    /**
     * 插入一个 batch 和与 Merkle 输出严格对应的 leaf 行。
     */
    private AttestationBatch insertBatchAndLeaves(Long tenantId,
                                                  String idempotencyKey,
                                                  MerkleTreeResult tree,
                                                  List<AttestationLeafEvidence> evidence,
                                                  ContractRegistryEntryResponse contractRegistry) {
        Map<Long, AttestationLeafEvidence> evidenceByFileId = indexEvidence(tree, evidence);

        long batchId = snowflakeIdGenerator.nextId();
        AttestationBatch batch = new AttestationBatch()
                .setId(batchId)
                .setTenantId(tenantId)
                .setBatchNo("MB-" + batchId)
                .setIdempotencyKey(idempotencyKey)
                .setMerkleRoot(tree.merkleRoot())
                .setProofAlgorithm(tree.proofAlgorithm())
                .setLeafCount(tree.leaves().size())
                .setStatus(AttestationBatchStatus.CHAIN_PENDING.value())
                .setAttemptCount(0)
                .setStateVersion(0L)
                .setDeleted(0);
        applyContractRegistry(batch, contractRegistry);
        batchMapper.insert(batch);

        for (MerkleLeafProof proof : tree.leaves()) {
            AttestationLeafEvidence leafEvidence = evidenceByFileId.get(proof.fileId());
            AttestationLeaf leaf = new AttestationLeaf()
                    .setId(snowflakeIdGenerator.nextId())
                    .setTenantId(tenantId)
                    .setBatchId(batchId)
                    .setFileId(proof.fileId())
                    .setFileVersion(leafEvidence.fileVersion())
                    .setManifestId(leafEvidence.manifestId())
                    .setFileHash(proof.fileHash())
                    .setEvidenceType(leafEvidence.evidenceType())
                    .setEvidenceHash(leafEvidence.evidenceHash())
                    .setChainRecordId(leafEvidence.chainRecordId())
                    .setLeafHash(proof.leafHash())
                    .setLeafIndex(proof.leafIndex())
                    .setProofPathJson(JsonConverter.toJson(proof.proofPath()))
                    .setProofAlgorithm(tree.proofAlgorithm())
                    .setDeleted(0);
            leafMapper.insert(leaf);
        }
        return batch;
    }

    /**
     * 把完整注册表 JSON 与常用查询字段同时写入新 batch。
     */
    private void applyContractRegistry(
            AttestationBatch batch,
            ContractRegistryEntryResponse registry
    ) {
        if (registry == null) {
            return;
        }
        if (!registry.hasValidRegistryFingerprint()) {
            throw new IllegalArgumentException("Contract registry fingerprint is invalid");
        }
        String registryJson = JsonConverter.toJson(registry);
        if (registryJson == null || registryJson.isBlank()) {
            throw new IllegalStateException("Contract registry snapshot cannot be serialized");
        }
        batch.setContractRegistryFingerprint(registry.registryFingerprint())
                .setContractRegistryJson(registryJson)
                .setChainType(registry.chainType())
                .setChainId(registry.chainId())
                .setChainGroupId(registry.groupId())
                .setContractName(registry.contractName())
                .setContractVersion(registry.semanticVersion())
                .setContractAddress(registry.contractAddress())
                .setContractAbiSha256(registry.abiSha256())
                .setContractArtifactBytecodeSha256(registry.artifactBytecodeSha256())
                .setContractCodeSha256(registry.onChainCodeSha256())
                .setContractStatus(registry.status());
    }

    /**
     * 校验每个 Merkle 叶子都有且只有一个完全匹配的证据绑定。
     */
    private Map<Long, AttestationLeafEvidence> indexEvidence(
            MerkleTreeResult tree,
            List<AttestationLeafEvidence> evidence
    ) {
        if (tree == null || tree.leaves() == null || evidence == null
                || tree.leaves().size() != evidence.size()) {
            throw new IllegalArgumentException("Merkle leaves and evidence metadata must have equal size");
        }
        Map<Long, AttestationLeafEvidence> evidenceByFileId;
        try {
            evidenceByFileId = evidence.stream().collect(Collectors.toMap(
                    AttestationLeafEvidence::fileId,
                    Function.identity()));
        } catch (RuntimeException duplicateOrNull) {
            throw new IllegalArgumentException("Leaf evidence contains a duplicate or null file ID", duplicateOrNull);
        }
        for (MerkleLeafProof proof : tree.leaves()) {
            AttestationLeafEvidence leafEvidence = evidenceByFileId.get(proof.fileId());
            if (leafEvidence == null
                    || leafEvidence.fileVersion() == null
                    || leafEvidence.fileVersion() <= 0
                    || !Objects.equals(proof.fileHash(), leafEvidence.evidenceHash())) {
                throw new IllegalArgumentException("Merkle leaf does not match its evidence metadata");
            }
        }
        return evidenceByFileId;
    }

    /**
     * 把持久化候选快照转换为 leaf 证据元数据。
     */
    private AttestationLeafEvidence toLeafEvidence(AttestationBatchCandidate candidate) {
        return new AttestationLeafEvidence(
                candidate.getFileId(),
                candidate.getFileVersion(),
                candidate.getManifestId(),
                candidate.getEvidenceType(),
                candidate.getEvidenceHash(),
                candidate.getChainRecordId());
    }

    /**
     * 在并发唯一键竞争回滚后，通过稳定幂等键读取已创建的批次。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<AttestationBatch> findByIdempotencyKey(Long tenantId, String idempotencyKey) {
        return Optional.ofNullable(selectByIdempotencyKey(tenantId, idempotencyKey));
    }

    /**
     * 按租户读取批次当前状态，防止跨租户 ID 猜测。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<AttestationBatch> findById(Long tenantId, Long batchId) {
        return Optional.ofNullable(selectByTenantAndId(tenantId, batchId));
    }

    /**
     * 原子领取批次并在同一事务中写入 attempt 审计记录。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Optional<AttestationBatch> claim(
            Long tenantId,
            Long batchId,
            String claimToken,
            Date now,
            Date leaseExpiresAt,
            int maxAttempts
    ) {
        int updated = batchMapper.claimForSubmission(
                tenantId, batchId, claimToken, now, leaseExpiresAt, maxAttempts);
        if (updated != 1) {
            return Optional.empty();
        }

        AttestationBatch claimed = selectByTenantAndId(tenantId, batchId);
        if (claimed == null || !Objects.equals(claimToken, claimed.getClaimToken())) {
            throw new IllegalStateException("Claimed attestation batch cannot be reloaded");
        }
        AttestationBatchAttempt attempt = new AttestationBatchAttempt()
                .setId(snowflakeIdGenerator.nextId())
                .setTenantId(tenantId)
                .setBatchId(batchId)
                .setAttemptNo(claimed.getAttemptCount())
                .setClaimToken(claimToken)
                .setStatus(ATTEMPT_CLAIMED);
        attemptMapper.insert(attempt);
        return Optional.of(claimed);
    }

    /**
     * 在有效 claim 内验证持久化快照与 provider 当前 ACTIVE 条目完全相同。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Optional<AttestationBatch> verifyContractRegistryClaim(
            Long tenantId,
            Long batchId,
            String claimToken,
            ContractRegistryEntryResponse activeRegistry
    ) {
        if (activeRegistry == null
                || activeRegistry.registryFingerprint() == null
                || !activeRegistry.hasValidRegistryFingerprint()) {
            throw new IllegalArgumentException("Active contract registry is required");
        }
        int updated = batchMapper.verifyContractRegistryClaim(
                tenantId,
                batchId,
                claimToken,
                activeRegistry.registryFingerprint());
        if (updated != 1) {
            return Optional.empty();
        }
        AttestationBatch claimed = selectByTenantAndId(tenantId, batchId);
        if (claimed == null || !Objects.equals(claimToken, claimed.getClaimToken())) {
            throw new IllegalStateException("Registry-verified claim cannot be reloaded");
        }
        ContractRegistryEntryResponse persisted = requireContractRegistry(claimed);
        if (!persisted.equals(activeRegistry)) {
            throw new IllegalStateException(
                    "Persisted contract registry fields do not match the active registry");
        }
        return Optional.of(claimed);
    }

    /**
     * 读取并交叉校验 batch 中完整 JSON 和反规范化的合约身份字段。
     */
    public ContractRegistryEntryResponse requireContractRegistry(AttestationBatch batch) {
        if (batch == null
                || batch.getContractRegistryJson() == null
                || batch.getContractRegistryJson().isBlank()) {
            throw new IllegalStateException("Attestation batch has no contract registry snapshot");
        }
        ContractRegistryEntryResponse registry = JsonConverter.parse(
                batch.getContractRegistryJson(), ContractRegistryEntryResponse.class);
        if (registry == null
                || !ContractRegistryEntryValidator.isValidPersistedSharingRegistry(registry)
                || !Objects.equals(batch.getContractRegistryFingerprint(), registry.registryFingerprint())
                || !Objects.equals(batch.getChainType(), registry.chainType())
                || !Objects.equals(batch.getChainId(), registry.chainId())
                || !Objects.equals(batch.getChainGroupId(), registry.groupId())
                || !Objects.equals(batch.getContractName(), registry.contractName())
                || !Objects.equals(batch.getContractVersion(), registry.semanticVersion())
                || !Objects.equals(batch.getContractAddress(), registry.contractAddress())
                || !Objects.equals(batch.getContractAbiSha256(), registry.abiSha256())
                || !Objects.equals(
                        batch.getContractArtifactBytecodeSha256(),
                        registry.artifactBytecodeSha256())
                || !Objects.equals(batch.getContractCodeSha256(), registry.onChainCodeSha256())
                || !Objects.equals(batch.getContractStatus(), registry.status())) {
            throw new IllegalStateException("Attestation batch contract registry snapshot is inconsistent");
        }
        return registry;
    }

    /**
     * 使用 claim token 保护地确认链写完成，并同步完成 attempt 审计。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean confirm(
            Long tenantId,
            Long batchId,
            String claimToken,
            String transactionHash,
            String chainRoot,
            String confirmationSource
    ) {
        AttestationConfirmationReceiptValidator.requireValid(
                confirmationSource, transactionHash, chainRoot);
        int updated = batchMapper.confirmSubmission(
                tenantId, batchId, claimToken, transactionHash, chainRoot, confirmationSource);
        if (updated == 1) {
            int attemptUpdated = attemptMapper.updateResult(
                    tenantId, batchId, claimToken, ATTEMPT_COMPLETED, confirmationSource,
                    transactionHash, chainRoot, null);
            requireAttemptUpdated(attemptUpdated);
            return true;
        }
        recordStaleAttempt(tenantId, batchId, claimToken);
        return false;
    }

    /**
     * 使用 claim token 保护地安排下一次退避重试。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean retry(
            Long tenantId,
            Long batchId,
            String claimToken,
            String errorMessage,
            Date nextAttemptAt
    ) {
        String normalizedError = truncate(errorMessage, 512);
        int updated = batchMapper.scheduleRetry(
                tenantId, batchId, claimToken, normalizedError, nextAttemptAt);
        if (updated == 1) {
            int attemptUpdated = attemptMapper.updateResult(
                    tenantId, batchId, claimToken, ATTEMPT_RETRY, null,
                    null, null, truncate(errorMessage, 1024));
            requireAttemptUpdated(attemptUpdated);
            return true;
        }
        recordStaleAttempt(tenantId, batchId, claimToken);
        return false;
    }

    /**
     * 使用 claim token 保护地进入人工处理终态。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean manualReview(
            Long tenantId,
            Long batchId,
            String claimToken,
            String errorMessage,
            String confirmationSource,
            String chainRoot
    ) {
        String normalizedError = truncate(errorMessage, 512);
        int updated = batchMapper.markManualReview(
                tenantId, batchId, claimToken, normalizedError, confirmationSource);
        if (updated == 1) {
            int attemptUpdated = attemptMapper.updateResult(
                    tenantId, batchId, claimToken, ATTEMPT_MANUAL, confirmationSource,
                    null, chainRoot, truncate(errorMessage, 1024));
            requireAttemptUpdated(attemptUpdated);
            return true;
        }
        recordStaleAttempt(tenantId, batchId, claimToken);
        return false;
    }

    /**
     * 查询租户内未删除的幂等 batch。
     */
    private AttestationBatch selectByIdempotencyKey(Long tenantId, String idempotencyKey) {
        return batchMapper.selectOne(new LambdaQueryWrapper<AttestationBatch>()
                .eq(AttestationBatch::getTenantId, tenantId)
                .eq(AttestationBatch::getIdempotencyKey, idempotencyKey)
                .eq(AttestationBatch::getDeleted, 0)
                .last("LIMIT 1"));
    }

    /**
     * 查询租户内指定 ID 的未删除 batch。
     */
    private AttestationBatch selectByTenantAndId(Long tenantId, Long batchId) {
        return batchMapper.selectOne(new LambdaQueryWrapper<AttestationBatch>()
                .eq(AttestationBatch::getTenantId, tenantId)
                .eq(AttestationBatch::getId, batchId)
                .eq(AttestationBatch::getDeleted, 0)
                .last("LIMIT 1"));
    }

    /**
     * 标记已失效 worker 的结果被安全忽略。
     */
    private void recordStaleAttempt(Long tenantId, Long batchId, String claimToken) {
        attemptMapper.updateResult(
                tenantId, batchId, claimToken, ATTEMPT_STALE, null,
                null, null, "Claim token no longer owns the batch state");
    }

    /**
     * 保证 batch 状态迁移和 attempt 审计结果在同一事务中同时成功。
     */
    private void requireAttemptUpdated(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("Attestation batch attempt audit row is missing or already finalized");
        }
    }

    /**
     * 将数据库错误字段限制在迁移定义的最大长度内。
     */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
