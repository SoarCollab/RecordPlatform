package cn.flying.service.attestation;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.AttestationBatch;
import cn.flying.dao.entity.AttestationBatchCandidate;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.request.GetAttestationBatchRequest;
import cn.flying.platformapi.request.StoreAttestationBatchRequest;
import cn.flying.platformapi.response.GetAttestationBatchResponse;
import cn.flying.platformapi.response.ContractRegistryEntryResponse;
import cn.flying.platformapi.response.StoreAttestationBatchResponse;
import cn.flying.service.remote.FileRemoteClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/**
 * 编排可恢复的 Merkle 批量存证创建和链上确认，所有链 RPC 均位于本地事务之外。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttestationBatchServiceImpl implements AttestationBatchService {

    static final int MAX_ATTEMPTS = 5;
    private static final long LEASE_SECONDS = 120L;
    private static final long[] BACKOFF_SECONDS = {5L, 30L, 120L, 600L, 3_600L};
    private static final String SOURCE_CHAIN_QUERY_BEFORE_WRITE = "CHAIN_QUERY_BEFORE_WRITE";
    private static final String SOURCE_CHAIN_WRITE = "CHAIN_WRITE";
    private static final String SOURCE_CHAIN_QUERY_AFTER_WRITE = "CHAIN_QUERY_AFTER_WRITE";
    private static final Pattern CHAIN_RECEIPT_TRANSACTION_HASH_PATTERN =
            Pattern.compile("(?i)(?:0x)?[0-9a-f]{64}");

    private final FileMapper fileMapper;
    private final MerkleTreeService merkleTreeService;
    private final FileRemoteClient fileRemoteClient;
    private final AttestationBatchPersistenceService persistenceService;
    private final AttestationBatchIdempotencyKey idempotencyKeyGenerator;

    /**
     * 创建或复用租户内幂等 batch，并在数据库事务提交后同步尝试一次链确认。
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AttestationBatch createBatch(Long userId, List<Long> fileIds) {
        Long tenantId = TenantContext.requireTenantId();
        List<Long> normalizedFileIds = normalizeFileIds(fileIds);
        List<File> files = loadAndValidateFiles(userId, tenantId, normalizedFileIds);
        MerkleTreeResult tree = merkleTreeService.buildTree(toMerkleInputs(files));
        String idempotencyKey = idempotencyKeyGenerator.generate(tenantId, tree);
        ContractRegistryEntryResponse contractRegistry = contractRegistryForCreation();

        AttestationBatch batch;
        try {
            batch = persistenceService.createOrGet(
                    tenantId,
                    idempotencyKey,
                    tree,
                    toLegacyEvidence(files),
                    contractRegistry);
        } catch (DuplicateKeyException duplicate) {
            batch = persistenceService.findByIdempotencyKey(tenantId, idempotencyKey)
                    .orElseThrow(() -> duplicate);
        }

        if (isTerminal(batch)) {
            return batch;
        }
        submitBatch(batch.getId());
        return persistenceService.findById(tenantId, batch.getId()).orElse(batch);
    }

    /**
     * 使用数据库租约保护的 manifest candidate 创建生产 batch；链提交由调用方在事务完成后单独触发。
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AttestationBatch createProductionBatch(AttestationCandidateClaim claim) {
        Long tenantId = TenantContext.requireTenantId();
        validateProductionClaim(tenantId, claim);
        MerkleTreeResult tree = merkleTreeService.buildTree(claim.candidates().stream()
                .map(candidate -> new MerkleLeafInput(candidate.getFileId(), candidate.getEvidenceHash()))
                .toList());
        String idempotencyKey = idempotencyKeyGenerator.generate(tenantId, tree);
        ContractRegistryEntryResponse contractRegistry = contractRegistryForCreation();
        AttestationBatch batch = persistenceService.createFromClaimedCandidates(
                tenantId, idempotencyKey, tree, claim, contractRegistry);
        return persistenceService.findById(tenantId, batch.getId()).orElse(batch);
    }

    /**
     * 领取并提交一个待处理 batch；该方法可由后续生产调度安全重复调用。
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AttestationBatch submitBatch(Long batchId) {
        Long tenantId = TenantContext.requireTenantId();
        if (batchId == null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "batchId 不能为空");
        }

        Instant now = Instant.now();
        String claimToken = UUID.randomUUID().toString();
        AttestationBatch claimed = persistenceService.claim(
                        tenantId,
                        batchId,
                        claimToken,
                        Date.from(now),
                        Date.from(now.plus(LEASE_SECONDS, ChronoUnit.SECONDS)),
                        MAX_ATTEMPTS)
                .orElse(null);
        if (claimed == null) {
            return loadCurrentBatch(tenantId, batchId);
        }

        ContractRegistryEntryResponse activeRegistry;
        try {
            activeRegistry = loadActiveSharingRegistry();
        } catch (ContractRegistryLookupException e) {
            return e.retryable()
                    ? handleRetryableFailure(claimed, e.getMessage())
                    : markManualReview(claimed, e.getMessage(), null, null);
        }
        try {
            AttestationBatch verifiedClaim = persistenceService.verifyContractRegistryClaim(
                            tenantId,
                            batchId,
                            claimed.getClaimToken(),
                            activeRegistry)
                    .orElse(null);
            if (verifiedClaim == null) {
                return markManualReview(
                        claimed,
                        "批次缺少注册表快照，或持久化快照已不是 provider 当前 ACTIVE 合约",
                        null,
                        null);
            }
            claimed = verifiedClaim;
        } catch (RuntimeException e) {
            return markManualReview(
                    claimed,
                    "批次合约注册表快照校验失败: " + safeMessage(e),
                    null,
                    null);
        }

        ChainQuery queryBeforeWrite = queryChainBatch(claimed);
        if (queryBeforeWrite.status() == ChainQueryStatus.ERROR) {
            return handleRetryableFailure(claimed, queryBeforeWrite.detail());
        }
        if (queryBeforeWrite.status() == ChainQueryStatus.FOUND) {
            return reconcileFoundBatch(
                    claimed, queryBeforeWrite.response(), SOURCE_CHAIN_QUERY_BEFORE_WRITE, null);
        }
        if (claimed.getAttemptCount() != null && claimed.getAttemptCount() > MAX_ATTEMPTS) {
            return markManualReview(
                    claimed,
                    "过期租约恢复仅完成链上对账，自动链写尝试已耗尽",
                    null,
                    null);
        }

        Result<StoreAttestationBatchResponse> writeResult;
        try {
            writeResult = fileRemoteClient.storeAttestationBatch(toStoreRequest(claimed));
        } catch (RuntimeException e) {
            return reconcileAfterUncertainWrite(claimed, "链写调用异常: " + safeMessage(e));
        }

        if (writeResult != null && writeResult.isSuccess()) {
            StoreAttestationBatchResponse response = writeResult.getData();
            if (isValidWriteResponse(claimed, response)) {
                persistenceService.confirm(
                        tenantId,
                        claimed.getId(),
                        claimed.getClaimToken(),
                        response.transactionHash(),
                        normalizeHash(response.batchRootHash()),
                        SOURCE_CHAIN_WRITE);
                return loadCurrentBatch(tenantId, claimed.getId());
            }
        }

        String writeError = describeWriteFailure(writeResult);
        if (writeResult != null
                && Objects.equals(writeResult.getCode(), cn.flying.platformapi.constant.ResultEnum.PARAM_IS_INVALID.getCode())) {
            return markManualReview(claimed, writeError, SOURCE_CHAIN_WRITE, null);
        }
        return reconcileAfterUncertainWrite(claimed, writeError);
    }

    /**
     * 写结果未知时再次查询链上业务记录，避免盲目进行第二次链写。
     */
    private AttestationBatch reconcileAfterUncertainWrite(AttestationBatch claimed, String writeError) {
        ChainQuery queryAfterWrite = queryChainBatch(claimed);
        if (queryAfterWrite.status() == ChainQueryStatus.FOUND) {
            return reconcileFoundBatch(
                    claimed, queryAfterWrite.response(), SOURCE_CHAIN_QUERY_AFTER_WRITE, null);
        }
        String detail = queryAfterWrite.status() == ChainQueryStatus.ERROR
                ? writeError + "; 写后对账失败: " + queryAfterWrite.detail()
                : writeError + "; 写后对账未发现链上记录";
        return handleRetryableFailure(claimed, detail);
    }

    /**
     * 校验查询到的链上记录与本地不可变批次内容完全一致，再决定确认或人工处理。
     */
    private AttestationBatch reconcileFoundBatch(
            AttestationBatch claimed,
            GetAttestationBatchResponse chainBatch,
            String confirmationSource,
            String transactionHash
    ) {
        if (!matchesLocalBatch(claimed, chainBatch)) {
            String detail = "链上批次与本地数据不一致: localRoot=" + claimed.getMerkleRoot()
                    + ", chainRoot=" + (chainBatch != null ? chainBatch.merkleRoot() : null);
            return markManualReview(
                    claimed,
                    detail,
                    confirmationSource,
                    chainBatch != null ? normalizeHash(chainBatch.merkleRoot()) : null);
        }
        persistenceService.confirm(
                claimed.getTenantId(),
                claimed.getId(),
                claimed.getClaimToken(),
                transactionHash,
                normalizeHash(chainBatch.merkleRoot()),
                confirmationSource);
        return loadCurrentBatch(claimed.getTenantId(), claimed.getId());
    }

    /**
     * 根据尝试次数进入有上限的退避重试或人工处理终态。
     */
    private AttestationBatch handleRetryableFailure(AttestationBatch claimed, String errorMessage) {
        int attemptCount = claimed.getAttemptCount() != null ? claimed.getAttemptCount() : 0;
        if (attemptCount >= MAX_ATTEMPTS) {
            return markManualReview(claimed, "自动重试已耗尽: " + errorMessage, null, null);
        }
        long backoff = BACKOFF_SECONDS[Math.max(0, Math.min(attemptCount - 1, BACKOFF_SECONDS.length - 1))];
        persistenceService.retry(
                claimed.getTenantId(),
                claimed.getId(),
                claimed.getClaimToken(),
                errorMessage,
                Date.from(Instant.now().plus(backoff, ChronoUnit.SECONDS)));
        log.warn("Merkle batch chain submission scheduled for retry: tenantId={}, batchId={}, attempt={}",
                claimed.getTenantId(), claimed.getId(), attemptCount);
        return loadCurrentBatch(claimed.getTenantId(), claimed.getId());
    }

    /**
     * 将数据冲突或重试耗尽的当前 claim 安全迁移到人工处理终态。
     */
    private AttestationBatch markManualReview(
            AttestationBatch claimed,
            String errorMessage,
            String confirmationSource,
            String chainRoot
    ) {
        persistenceService.manualReview(
                claimed.getTenantId(),
                claimed.getId(),
                claimed.getClaimToken(),
                errorMessage,
                confirmationSource,
                chainRoot);
        log.error("Merkle batch requires manual review: tenantId={}, batchId={}, detail={}",
                claimed.getTenantId(), claimed.getId(), errorMessage);
        return loadCurrentBatch(claimed.getTenantId(), claimed.getId());
    }

    /**
     * 通过只读 RPC 查询链上的稳定业务记录，并显式区分不存在和查询失败。
     */
    private ChainQuery queryChainBatch(AttestationBatch batch) {
        try {
            Result<GetAttestationBatchResponse> result = fileRemoteClient.getAttestationBatch(
                    new GetAttestationBatchRequest(
                            batch.getTenantId(),
                            batch.getId(),
                            persistenceService.requireContractRegistry(batch)));
            if (result == null) {
                return ChainQuery.error("链查询返回为空");
            }
            if (!result.isSuccess()) {
                return ChainQuery.error("链查询失败: code=" + result.getCode() + ", message=" + result.getMessage());
            }
            GetAttestationBatchResponse response = result.getData();
            if (response == null || response.exists() == null) {
                return ChainQuery.error("链查询缺少 exists 结果");
            }
            return Boolean.TRUE.equals(response.exists())
                    ? ChainQuery.found(response)
                    : ChainQuery.notFound();
        } catch (RuntimeException e) {
            return ChainQuery.error("链查询异常: " + safeMessage(e));
        }
    }

    /**
     * 判断链上业务记录是否与本地不可变 batch 字段完全一致。
     */
    private boolean matchesLocalBatch(AttestationBatch local, GetAttestationBatchResponse chain) {
        return chain != null
                && Boolean.TRUE.equals(chain.exists())
                && Objects.equals(local.getTenantId(), chain.tenantId())
                && Objects.equals(local.getId(), chain.batchId())
                && Objects.equals(local.getBatchNo(), chain.batchNo())
                && Objects.equals(local.getProofAlgorithm(), chain.proofAlgorithm())
                && Objects.equals(local.getLeafCount(), chain.leafCount())
                && normalizeHash(local.getMerkleRoot()).equalsIgnoreCase(normalizeHash(chain.merkleRoot()));
    }

    /**
     * 将本地不可变 batch 内容映射为专用链写请求。
     */
    private StoreAttestationBatchRequest toStoreRequest(AttestationBatch batch) {
        return new StoreAttestationBatchRequest(
                batch.getTenantId(),
                batch.getId(),
                batch.getBatchNo(),
                batch.getProofAlgorithm(),
                batch.getMerkleRoot(),
                batch.getLeafCount(),
                persistenceService.requireContractRegistry(batch));
    }

    /**
     * 在创建新 batch 前获取合约注册表；失败时禁止创建无法外部验真的批次。
     */
    private ContractRegistryEntryResponse contractRegistryForCreation() {
        try {
            return loadActiveSharingRegistry();
        } catch (ContractRegistryLookupException e) {
            throw new GeneralException(ResultEnum.BLOCKCHAIN_ERROR, e.getMessage());
        }
    }

    /**
     * 读取并严格验证 provider 返回的唯一 ACTIVE Sharing 注册表条目。
     */
    private ContractRegistryEntryResponse loadActiveSharingRegistry() {
        Result<List<ContractRegistryEntryResponse>> result;
        try {
            result = fileRemoteClient.getContractRegistry();
        } catch (RuntimeException e) {
            throw new ContractRegistryLookupException(
                    "合约注册表查询异常: " + safeMessage(e), true);
        }
        if (result == null || !result.isSuccess() || result.getData() == null) {
            throw new ContractRegistryLookupException(
                    "合约注册表查询失败: code=" + (result != null ? result.getCode() : null),
                    true);
        }
        List<ContractRegistryEntryResponse> sharingEntries = result.getData().stream()
                .filter(Objects::nonNull)
                .filter(entry -> "Sharing".equals(entry.contractName()))
                .toList();
        if (sharingEntries.size() != 1) {
            throw new ContractRegistryLookupException(
                    "合约注册表必须且只能包含一个 Sharing 条目", false);
        }
        ContractRegistryEntryResponse registry = sharingEntries.getFirst();
        validateSharingRegistry(registry);
        return registry;
    }

    /**
     * 校验注册表字段完整性，禁止使用不支持的 schema、算法、状态或链身份。
     */
    private void validateSharingRegistry(ContractRegistryEntryResponse registry) {
        if (!ContractRegistryEntryValidator.isValidActiveSharingRegistry(registry)) {
            throw new ContractRegistryLookupException(
                    "provider 返回的 Sharing 合约注册表字段无效", false);
        }
    }

    /**
     * 校验写响应必须同时包含交易哈希和与本地一致的链上根。
     */
    private boolean isValidWriteResponse(AttestationBatch batch, StoreAttestationBatchResponse response) {
        return response != null
                && StringUtils.hasText(response.transactionHash())
                && CHAIN_RECEIPT_TRANSACTION_HASH_PATTERN.matcher(response.transactionHash()).matches()
                && StringUtils.hasText(response.batchRootHash())
                && normalizeHash(batch.getMerkleRoot())
                .equalsIgnoreCase(normalizeHash(response.batchRootHash()));
    }

    /**
     * 生成不会泄露内部对象的链写失败摘要。
     */
    private String describeWriteFailure(Result<StoreAttestationBatchResponse> result) {
        if (result == null) {
            return "链写返回为空";
        }
        if (!result.isSuccess()) {
            return "链写失败: code=" + result.getCode() + ", message=" + result.getMessage();
        }
        return "链写成功响应缺少交易哈希或返回根不一致";
    }

    /**
     * 按租户加载当前 batch；跨租户或不存在统一返回业务错误。
     */
    private AttestationBatch loadCurrentBatch(Long tenantId, Long batchId) {
        return persistenceService.findById(tenantId, batchId)
                .orElseThrow(() -> new GeneralException(ResultEnum.FILE_RECORD_ERROR, "批量存证记录不存在"));
    }

    /**
     * 判断 batch 是否已经处于不可自动迁移的终态。
     */
    private boolean isTerminal(AttestationBatch batch) {
        return AttestationBatchStatus.COMPLETED.value().equals(batch.getStatus())
                || AttestationBatchStatus.MANUAL_REVIEW.value().equals(batch.getStatus());
    }

    /**
     * 去除空 ID 和重复 ID，同时保持调用方顺序供文件加载校验使用。
     */
    private List<Long> normalizeFileIds(List<Long> fileIds) {
        if (CollectionUtils.isEmpty(fileIds)) {
            throw new GeneralException(ResultEnum.PARAM_IS_BLANK, "fileIds 不能为空");
        }
        LinkedHashSet<Long> distinctIds = new LinkedHashSet<>();
        for (Long fileId : fileIds) {
            if (fileId == null) {
                throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "fileId 不能为空");
            }
            distinctIds.add(fileId);
        }
        return List.copyOf(distinctIds);
    }

    /**
     * 加载文件并校验租户、用户、上传状态和链上哈希前置条件。
     */
    private List<File> loadAndValidateFiles(Long userId, Long tenantId, List<Long> fileIds) {
        List<File> files = fileMapper.selectBatchIds(fileIds);
        if (files == null || files.size() != fileIds.size()) {
            throw new GeneralException(ResultEnum.FILE_NOT_EXIST, "部分文件不存在或不属于当前租户");
        }

        Map<Long, File> filesById = files.stream()
                .collect(Collectors.toMap(File::getId, Function.identity()));
        List<File> orderedFiles = new ArrayList<>(fileIds.size());
        for (Long fileId : fileIds) {
            File file = filesById.get(fileId);
            validateFile(userId, tenantId, fileId, file);
            orderedFiles.add(file);
        }
        return orderedFiles;
    }

    /**
     * 校验一个文件可以进入当前租户和用户发起的 Merkle batch。
     */
    private void validateFile(Long userId, Long tenantId, Long fileId, File file) {
        if (file == null) {
            throw new GeneralException(ResultEnum.FILE_NOT_EXIST, "文件不存在: " + fileId);
        }
        if (!tenantId.equals(file.getTenantId())) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "文件不属于当前租户: " + fileId);
        }
        if (userId != null && !userId.equals(file.getUid())) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "文件不属于当前用户: " + fileId);
        }
        if (!Integer.valueOf(FileUploadStatus.SUCCESS.getCode()).equals(file.getStatus())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "仅上传成功的文件可创建批量存证: " + fileId);
        }
        if (!StringUtils.hasText(file.getFileHash())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件缺少链上哈希: " + fileId);
        }
    }

    /**
     * 将文件记录转换为 Merkle 服务的最小输入。
     */
    private List<MerkleLeafInput> toMerkleInputs(List<File> files) {
        return files.stream()
                .map(file -> new MerkleLeafInput(file.getId(), file.getFileHash()))
                .toList();
    }

    /**
     * 为旧的显式创建路径补齐版本和链记录证据元数据。
     */
    private List<AttestationLeafEvidence> toLegacyEvidence(List<File> files) {
        return files.stream()
                .map(file -> new AttestationLeafEvidence(
                        file.getId(),
                        file.getVersion() != null ? file.getVersion() : 1,
                        null,
                        AttestationBatchPersistenceService.EVIDENCE_TYPE_LEGACY_CHAIN_RECORD_ID,
                        file.getFileHash(),
                        file.getFileHash()))
                .toList();
    }

    /**
     * 校验 production claim 完整属于当前租户且只包含 manifest 证据。
     */
    private void validateProductionClaim(Long tenantId, AttestationCandidateClaim claim) {
        if (claim == null || claim.size() == 0 || !StringUtils.hasText(claim.claimToken())) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "生产批次 candidate claim 不能为空");
        }
        if (!Objects.equals(tenantId, claim.tenantId())) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "candidate claim 不属于当前租户");
        }
        for (AttestationBatchCandidate candidate : claim.candidates()) {
            if (candidate == null
                    || !Objects.equals(tenantId, candidate.getTenantId())
                    || !Objects.equals(claim.claimToken(), candidate.getClaimToken())
                    || !AttestationBatchCandidateStatus.CLAIMED.value().equals(candidate.getStatus())
                    || !AttestationBatchCandidatePersistenceService.EVIDENCE_TYPE_MANIFEST_HASH
                        .equals(candidate.getEvidenceType())
                    || candidate.getFileId() == null
                    || candidate.getFileVersion() == null
                    || candidate.getFileVersion() <= 0
                    || candidate.getManifestId() == null
                    || !AttestationBatchCandidatePersistenceService
                        .isCanonicalManifestHash(candidate.getEvidenceHash())
                    || !StringUtils.hasText(candidate.getChainRecordId())) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "生产批次 candidate 证据无效");
            }
        }
    }

    /**
     * 去除可选 0x 前缀，统一链适配器返回哈希的比较格式。
     */
    private String normalizeHash(String hash) {
        if (hash == null) {
            return "";
        }
        String trimmed = hash.trim();
        return trimmed.startsWith("0x") || trimmed.startsWith("0X")
                ? trimmed.substring(2)
                : trimmed;
    }

    /**
     * 提取异常的安全摘要，避免空消息破坏审计字段。
     */
    private String safeMessage(Throwable throwable) {
        return throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
    }

    /**
     * 区分可重试的注册表连接故障与必须人工处理的注册表合同错误。
     */
    private static final class ContractRegistryLookupException extends RuntimeException {

        private final boolean retryable;

        /**
         * 创建带重试分类的注册表异常。
         */
        private ContractRegistryLookupException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        /**
         * 返回该故障是否可通过后续只读重试恢复。
         */
        private boolean retryable() {
            return retryable;
        }
    }

    private enum ChainQueryStatus {
        FOUND,
        NOT_FOUND,
        ERROR
    }

    /**
     * 显式表达链查询的发现、不存在和失败三态，避免把故障误判为不存在。
     */
    private record ChainQuery(
            ChainQueryStatus status,
            GetAttestationBatchResponse response,
            String detail
    ) {

        private static ChainQuery found(GetAttestationBatchResponse response) {
            return new ChainQuery(ChainQueryStatus.FOUND, response, null);
        }

        private static ChainQuery notFound() {
            return new ChainQuery(ChainQueryStatus.NOT_FOUND, null, null);
        }

        private static ChainQuery error(String detail) {
            return new ChainQuery(ChainQueryStatus.ERROR, null, detail);
        }
    }
}
