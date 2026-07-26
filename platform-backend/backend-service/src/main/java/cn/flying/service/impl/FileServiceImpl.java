package cn.flying.service.impl;

import cn.flying.api.utils.ResultUtils;
import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.constant.ShareType;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.exception.RetryableException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.CommonUtils;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.JsonConverter;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.dto.File;
import cn.flying.dao.dto.FileShare;
import cn.flying.dao.dto.FileSource;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.ProofBundleIssuanceMapper;
import cn.flying.dao.mapper.FileShareMapper;
import cn.flying.dao.mapper.FileSourceMapper;
import cn.flying.dao.vo.file.FileDecryptInfoVO;
import cn.flying.dao.vo.file.FileShareVO;
import cn.flying.dao.vo.file.ShareInfoVO;
import cn.flying.dao.vo.file.ShareFileVO;
import cn.flying.dao.vo.file.UpdateShareVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.request.CancelShareRequest;
import cn.flying.platformapi.request.ShareFilesRequest;
import cn.flying.platformapi.request.StoreFileRequest;
import cn.flying.platformapi.request.StoreFileResponse;
import cn.flying.platformapi.response.DirectMultipartCompletedPartVO;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.service.remote.FileRemoteClient;
import cn.flying.platformapi.response.SharingVO;
import cn.flying.service.FileService;
import cn.flying.service.QuotaService;
import cn.flying.service.ShareAuditService;
import cn.flying.service.key.FileKeyEnvelopeService;
import cn.flying.service.key.FileParamEnvelopeResult;
import cn.flying.service.saga.FileSagaOrchestrator;
import cn.flying.service.saga.FileUploadCommand;
import cn.flying.service.saga.FileUploadResult;
import cn.flying.service.support.StoredObjectReference;
import cn.flying.service.support.StoredObjectReferenceCodec;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.MDC;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * @program: RecordPlatform
 * @description: 文件服务实现类
 * @author flyingcoding
 * @create: 2025-03-12 21:22
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl extends ServiceImpl<FileMapper, File> implements FileService {

    private static final long MAX_IN_MEMORY_TRANSFER_BYTES = 80L * 1024 * 1024;
    private static final Pattern CONTENT_HASH_PATTERN = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final String FINALIZATION_CLAIM_KEY = "_finalizationClaim";
    private static final int FINALIZATION_CLAIM_VERSION = 1;
    private static final String FINALIZATION_MODE_DIRECT = "DIRECT";
    private static final String FINALIZATION_MODE_LEGACY = "LEGACY";
    private static final String FINALIZATION_PHASE_CLAIMED = "CLAIMED";
    private static final String FINALIZATION_PHASE_CHAIN_ATTESTING = "CHAIN_ATTESTING";
    private static final String FINALIZATION_PHASE_CHAIN_ATTESTED = "CHAIN_ATTESTED";

    private final FileRemoteClient fileRemoteClient;
    private final FileSagaOrchestrator sagaOrchestrator;
    private final FileShareMapper fileShareMapper;
    private final FileSourceMapper fileSourceMapper;
    private final ShareAuditService shareAuditService;
    private final CacheManager cacheManager;
    private final RedissonClient redissonClient;
    private final TransactionTemplate transactionTemplate;
    private final QuotaService quotaService;
    private final FileKeyEnvelopeService fileKeyEnvelopeService;
    private final ProofBundleIssuanceMapper proofBundleIssuanceMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void prepareStoreFile(Long userId, String OriginFileName, long fileSize) {
        prepareStoreFile(userId, null, OriginFileName, fileSize);
    }

    /**
     * 预存储文件元数据，支持复用既有 PREPARE 记录。
     *
     * @param userId 用户ID
     * @param targetFileId 目标文件ID（为空时创建新 PREPARE）
     * @param originFileName 原始文件名
     * @param fileSize 文件大小
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void prepareStoreFile(Long userId, Long targetFileId, String originFileName, long fileSize) {
        Long preparedFileId = targetFileId != null ? targetFileId : IdUtils.nextEntityId();
        prepareStoreFileWithStableId(userId, targetFileId, preparedFileId, originFileName, fileSize);
    }

    /**
     * 使用会话预先保存的稳定主键创建或幂等复用 PREPARE 记录。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public File prepareStoreFileWithStableId(Long userId, Long targetFileId, Long preparedFileId,
                                             String originFileName, long fileSize) {
        if (preparedFileId == null || preparedFileId <= 0) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "PREPARE 文件ID无效");
        }
        if (targetFileId != null && !Objects.equals(targetFileId, preparedFileId)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "目标版本与 PREPARE 文件ID不一致");
        }

        File existing = this.getById(preparedFileId);
        if (existing != null) {
            validatePreparedFileSnapshot(existing, userId, originFileName, fileSize);
            return existing;
        }
        if (targetFileId != null) {
            throw new GeneralException(ResultEnum.FILE_NOT_EXIST);
        }

        File file = new File()
                .setId(preparedFileId)
                .setUid(userId)
                .setFileName(originFileName)
                .setFileParam(buildPrepareFileParam(fileSize))
                .setStatus(FileUploadStatus.PREPARE.getCode())
                .setVersion(1)
                .setIsLatest(1)
                .setVersionGroupId(preparedFileId);
        if (!this.save(file)) {
            throw new GeneralException(ResultEnum.FAIL, "PREPARE 文件创建失败");
        }
        return file;
    }

    /**
     * 校验稳定 PREPARE 快照的所有权、状态、文件名和预占大小。
     */
    private void validatePreparedFileSnapshot(File targetFile, Long userId, String originFileName, long fileSize) {
        if (!Objects.equals(targetFile.getUid(), userId)) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
        }
        if (!Objects.equals(targetFile.getStatus(), FileUploadStatus.PREPARE.getCode())) {
            throw new GeneralException(ResultEnum.VERSION_SOURCE_INVALID, "目标版本状态不允许上传");
        }
        if (CommonUtils.isNotEmpty(originFileName) && !Objects.equals(targetFile.getFileName(), originFileName)) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "上传文件名与目标版本不一致");
        }

        Long targetFileSize = targetFile.getFileSize();
        long resolvedSize = Math.max(0L, fileSize);
        if (targetFileSize != null && targetFileSize > 0 && targetFileSize.longValue() != resolvedSize) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "上传文件大小与目标版本不一致");
        }
    }

    /**
     * 构造 PREPARE 阶段的最小 file_param，提前写入 fileSize 以参与配额统计。
     *
     * @param fileSize 文件大小（字节）
     * @return file_param JSON 字符串
     */
    private String buildPrepareFileParam(long fileSize) {
        long resolvedFileSize = Math.max(0L, fileSize);
        return JsonConverter.toJson(Map.of("fileSize", resolvedFileSize));
    }

    /**
     * 存储文件：执行 Saga 流程（S3 存储 + 区块链）
     * 注意：此方法不使用类级别事务，Saga 编排器内部管理自己的事务
     */
    @Override
    public File storeFile(Long userId, String OriginFileName, List<java.io.File> fileList, List<String> fileHashList, String fileParam) {
        return storeFile(userId, null, OriginFileName, fileList, fileHashList, fileParam);
    }

    /**
     * 执行文件分片存储与上链，并将结果回写到目标 PREPARE 记录。
     *
     * @param userId 用户ID
     * @param targetFileId 目标文件ID（为空时按 fileName 查找 PREPARE）
     * @param originFileName 原始文件名
     * @param fileList 分片文件列表
     * @param fileHashList 分片哈希列表
     * @param fileParam 文件参数
     * @return 存储成功后的文件记录
     */
    @Override
    public File storeFile(Long userId, Long targetFileId, String originFileName,
                          List<java.io.File> fileList, List<String> fileHashList, String fileParam) {
        return storeFile(userId, targetFileId, originFileName, fileList, fileHashList, fileParam, null);
    }

    /**
     * 在进入普通上传 Saga 前持久化 PREPARE 文件级 claim，阻断其他会话或直传模式并发执行外部调用。
     */
    @Override
    public File storeFile(Long userId, Long targetFileId, String originFileName,
                          List<java.io.File> fileList, List<String> fileHashList,
                          String fileParam, String ownerToken) {
        if (CommonUtils.isEmpty(fileList)) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "File list cannot be empty");
        }

        File existingFile = resolvePrepareFileForStore(userId, targetFileId, originFileName);
        FileParamEnvelopeResult envelopeResult = prepareFileParamEnvelope(fileParam);
        String sanitizedFileParam = envelopeResult.sanitizedFileParam();
        String contentHash = requireContentHash(sanitizedFileParam);

        String resolvedOwnerToken = normalizeFinalizationOwnerToken(
                ownerToken, FINALIZATION_MODE_LEGACY, existingFile.getId());
        String fingerprint = calculateLegacyFinalizationFingerprint(
                sanitizedFileParam, fileList, fileHashList);
        FinalizationClaimSnapshot claimSnapshot = claimFinalization(
                existingFile,
                userId,
                resolvedOwnerToken,
                FINALIZATION_MODE_LEGACY,
                fingerprint,
                sanitizedFileParam);

        StoreFileResponse chainResult;
        if (FINALIZATION_PHASE_CHAIN_ATTESTED.equals(claimSnapshot.claim().phase())) {
            chainResult = claimSnapshot.claim().toChainResult();
        } else {
            if (FINALIZATION_PHASE_CHAIN_ATTESTING.equals(claimSnapshot.claim().phase())) {
                throw unresolvedFinalizationException(existingFile.getId());
            }

            String requestId = UUID.randomUUID().toString();
            FileUploadCommand cmd = FileUploadCommand.builder()
                    .requestId(requestId)
                    .fileId(existingFile.getId())
                    .userId(userId)
                    .tenantId(existingFile.getTenantId())
                    .fileName(existingFile.getFileName())
                    .fileParam(sanitizedFileParam)
                    .fileList(fileList)
                    .fileHashList(fileHashList)
                    .build();

            AtomicReference<FinalizationClaimSnapshot> durableClaim =
                    new AtomicReference<>(claimSnapshot);
            FileUploadResult result = sagaOrchestrator.executeUpload(
                    cmd,
                    () -> durableClaim.set(transitionFinalizationClaim(
                            existingFile,
                            userId,
                            durableClaim.get(),
                            FINALIZATION_PHASE_CHAIN_ATTESTING,
                            "",
                            "")),
                    attestedResult -> durableClaim.set(transitionFinalizationClaim(
                            existingFile,
                            userId,
                            durableClaim.get(),
                            FINALIZATION_PHASE_CHAIN_ATTESTED,
                            attestedResult.transactionHash(),
                            attestedResult.fileHash())));

            if (!result.isSuccess()) {
                String errorMsg = result.getErrorMessage();
                throw new GeneralException(ResultEnum.FAIL, errorMsg != null ? errorMsg : "File upload failed");
            }
            chainResult = new StoreFileResponse(result.getTransactionHash(), result.getFileHash());
            if (!isValidDirectChainResult(chainResult)) {
                throw new GeneralException(ResultEnum.BLOCKCHAIN_ERROR, "区块链存储返回无效结果");
            }
            claimSnapshot = durableClaim.get();
            if (!FINALIZATION_PHASE_CHAIN_ATTESTED.equals(claimSnapshot.claim().phase())
                    || !Objects.equals(chainResult.transactionHash(), claimSnapshot.claim().txHash())
                    || !Objects.equals(chainResult.fileHash(), claimSnapshot.claim().fileHash())) {
                throw new GeneralException(
                        ResultEnum.FILE_RECORD_ERROR,
                        "Saga 链结果未持久化到 ATTESTED claim");
            }
        }

        return persistFinalizationSuccess(
                existingFile,
                userId,
                null,
                sanitizedFileParam,
                contentHash,
                chainResult,
                envelopeResult,
                claimSnapshot);
    }

    /**
     * Registers chunks that were uploaded directly to object storage without proxying bytes through backend.
     */
    @Override
    public File storeDirectUploadedFile(Long userId, Long preparedFileId, String originFileName, long fileSize,
                                        List<DirectMultipartCompletedPartVO> completedParts, String fileParam) {
        StoreFileResponse chainResult = attestDirectUploadedFile(
                userId, preparedFileId, originFileName, completedParts, fileParam);
        return persistDirectUploadedFile(
                userId, preparedFileId, originFileName, fileSize, fileParam, chainResult);
    }

    /**
     * 仅执行直传文件的链上登记，使调用方能先把链结果写入可恢复会话检查点。
     */
    @Override
    public StoreFileResponse attestDirectUploadedFile(Long userId, Long preparedFileId,
                                                      String originFileName,
                                                      List<DirectMultipartCompletedPartVO> completedParts,
                                                      String fileParam) {
        return attestDirectUploadedFile(
                userId, preparedFileId, originFileName, completedParts, fileParam, null);
    }

    /**
     * 使用稳定会话 owner 在 DB 中 claim 直传目标，并在链 RPC 前推进为不可自动重放阶段。
     */
    @Override
    public StoreFileResponse attestDirectUploadedFile(Long userId, Long preparedFileId,
                                                      String originFileName,
                                                      List<DirectMultipartCompletedPartVO> completedParts,
                                                      String fileParam,
                                                      String ownerToken) {
        if (CommonUtils.isEmpty(completedParts)) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "Stored parts cannot be empty");
        }
        if (preparedFileId == null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "PREPARE 文件ID不能为空");
        }

        File existingFile = resolvePrepareFileForStore(userId, preparedFileId, originFileName);
        FileParamEnvelopeResult envelopeResult = prepareFileParamEnvelope(fileParam);
        String sanitizedFileParam = envelopeResult.sanitizedFileParam();
        requireContentHash(sanitizedFileParam);
        String resolvedOwnerToken = normalizeFinalizationOwnerToken(
                ownerToken, FINALIZATION_MODE_DIRECT, existingFile.getId());
        String fingerprint = calculateDirectFinalizationFingerprint(sanitizedFileParam, completedParts);
        FinalizationClaimSnapshot claimSnapshot = claimFinalization(
                existingFile,
                userId,
                resolvedOwnerToken,
                FINALIZATION_MODE_DIRECT,
                fingerprint,
                sanitizedFileParam);
        if (FINALIZATION_PHASE_CHAIN_ATTESTED.equals(claimSnapshot.claim().phase())) {
            return claimSnapshot.claim().toChainResult();
        }
        if (FINALIZATION_PHASE_CHAIN_ATTESTING.equals(claimSnapshot.claim().phase())) {
            throw unresolvedFinalizationException(existingFile.getId());
        }
        claimSnapshot = transitionFinalizationClaim(
                existingFile,
                userId,
                claimSnapshot,
                FINALIZATION_PHASE_CHAIN_ATTESTING,
                "",
                "");

        String fileContent = StoredObjectReferenceCodec.toChainContent(completedParts);
        Result<StoreFileResponse> result = fileRemoteClient.storeFileOnChainOnce(new StoreFileRequest(
                String.valueOf(userId),
                existingFile.getFileName(),
                sanitizedFileParam,
                fileContent
        ));
        StoreFileResponse chainResult = ResultUtils.getData(result);
        if (!isValidDirectChainResult(chainResult)) {
            throw new GeneralException(ResultEnum.BLOCKCHAIN_ERROR, "区块链存储返回无效结果");
        }
        FinalizationClaimSnapshot attestedSnapshot = transitionFinalizationClaim(
                existingFile,
                userId,
                claimSnapshot,
                FINALIZATION_PHASE_CHAIN_ATTESTED,
                chainResult.transactionHash(),
                chainResult.fileHash());
        return attestedSnapshot.claim().toChainResult();
    }

    /**
     * 使用 Redis 已保存的链结果幂等推进稳定 PREPARE 记录，重试时不再重复上链。
     */
    @Override
    public File persistDirectUploadedFile(Long userId, Long preparedFileId, String originFileName,
                                          long fileSize, String fileParam, StoreFileResponse chainResult) {
        return persistDirectUploadedFile(
                userId, preparedFileId, originFileName, fileSize, fileParam, chainResult, null);
    }

    /**
     * 仅使用同 owner 的 ATTESTED claim 完成 DB SUCCESS 精确 CAS，禁止调用方注入链结果。
     */
    @Override
    public File persistDirectUploadedFile(Long userId, Long preparedFileId, String originFileName,
                                          long fileSize, String fileParam, StoreFileResponse chainResult,
                                          String ownerToken) {
        if (preparedFileId == null || !isValidDirectChainResult(chainResult)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "直传文件检查点无效");
        }

        File existingFile = loadDirectFileByStableId(userId, preparedFileId, originFileName);
        FileParamEnvelopeResult envelopeResult = prepareFileParamEnvelope(fileParam);
        String sanitizedFileParam = envelopeResult.sanitizedFileParam();
        String contentHash = requireContentHash(sanitizedFileParam);
        if (Objects.equals(existingFile.getStatus(), FileUploadStatus.SUCCESS.getCode())) {
            validatePersistedDirectFile(existingFile, chainResult, contentHash, fileSize);
            return existingFile.setFileSize(fileSize);
        }
        if (!Objects.equals(existingFile.getStatus(), FileUploadStatus.PREPARE.getCode())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "直传目标文件状态不可恢复");
        }
        String resolvedOwnerToken = normalizeFinalizationOwnerToken(
                ownerToken, FINALIZATION_MODE_DIRECT, existingFile.getId());
        FinalizationClaimSnapshot claimSnapshot = requireOwnedAttestedClaim(
                existingFile,
                resolvedOwnerToken,
                FINALIZATION_MODE_DIRECT,
                chainResult);
        return persistFinalizationSuccess(
                existingFile,
                userId,
                fileSize,
                sanitizedFileParam,
                contentHash,
                chainResult,
                envelopeResult,
                claimSnapshot);
    }

    /**
     * 按稳定主键加载直传文件，并校验租户拦截器范围内的所有权和文件名。
     */
    private File loadDirectFileByStableId(Long userId, Long preparedFileId, String originFileName) {
        File file = this.getOne(new LambdaQueryWrapper<File>()
                .eq(File::getId, preparedFileId)
                .eq(File::getUid, userId)
                .eq(CommonUtils.isNotEmpty(originFileName), File::getFileName, originFileName));
        if (file == null) {
            throw new GeneralException(ResultEnum.FAIL, "File metadata not initialized for upload");
        }
        return file;
    }

    /**
     * 校验链结果具备恢复本地 SUCCESS 所需的两个稳定标识。
     */
    private boolean isValidDirectChainResult(StoreFileResponse chainResult) {
        return chainResult != null
                && CommonUtils.isNotEmpty(chainResult.fileHash())
                && CommonUtils.isNotEmpty(chainResult.transactionHash());
    }

    /**
     * 校验已存在的 SUCCESS 记录与会话链检查点完全一致，禁止错误会话借用终态。
     */
    private void validatePersistedDirectFile(File existingFile, StoreFileResponse chainResult,
                                             String contentHash, long fileSize) {
        Long persistedSize = existingFile.getFileSize();
        if (!Objects.equals(existingFile.getFileHash(), chainResult.fileHash())
                || !Objects.equals(existingFile.getTransactionHash(), chainResult.transactionHash())
                || !Objects.equals(existingFile.getContentHash(), contentHash)
                || fileSize <= 0
                || persistedSize == null
                || persistedSize <= 0
                || persistedSize.longValue() != fileSize) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "直传 SUCCESS 记录与会话检查点不一致");
        }
    }

    /**
     * 判断目标文件是否已经进入链结果不允许自动重放的持久化阶段。
     */
    @Override
    public boolean requiresManualFinalizationReconciliation(Long userId, Long preparedFileId) {
        return getFinalizationRecoveryPhase(userId, preparedFileId)
                == FinalizationRecoveryPhase.CHAIN_ATTESTING;
    }

    /**
     * 只读解析稳定文件记录的最终化阶段，ATTESTED 与 ATTESTING 采用不同恢复策略。
     */
    @Override
    public FinalizationRecoveryPhase getFinalizationRecoveryPhase(
            Long userId,
            Long preparedFileId
    ) {
        if (preparedFileId == null) {
            return FinalizationRecoveryPhase.NONE;
        }
        File file = this.getById(preparedFileId);
        if (file == null || !Objects.equals(file.getUid(), userId)) {
            return FinalizationRecoveryPhase.NONE;
        }
        if (Objects.equals(file.getStatus(), FileUploadStatus.SUCCESS.getCode())) {
            return FinalizationRecoveryPhase.SUCCESS;
        }
        if (!Objects.equals(file.getStatus(), FileUploadStatus.PREPARE.getCode())) {
            return FinalizationRecoveryPhase.NONE;
        }
        FinalizationClaim claim;
        try {
            claim = readFinalizationClaim(file.getFileParam());
        } catch (RuntimeException malformedClaim) {
            return FinalizationRecoveryPhase.UNKNOWN;
        }
        if (claim == null) {
            return FinalizationRecoveryPhase.NONE;
        }
        if (!isValidFinalizationClaim(claim)) {
            return FinalizationRecoveryPhase.UNKNOWN;
        }
        return switch (claim.phase()) {
            case FINALIZATION_PHASE_CLAIMED -> FinalizationRecoveryPhase.CLAIMED;
            case FINALIZATION_PHASE_CHAIN_ATTESTING ->
                    FinalizationRecoveryPhase.CHAIN_ATTESTING;
            case FINALIZATION_PHASE_CHAIN_ATTESTED ->
                    FinalizationRecoveryPhase.CHAIN_ATTESTED;
            default -> FinalizationRecoveryPhase.UNKNOWN;
        };
    }

    /**
     * 创建或幂等复用文件级最终化 claim，首次写入使用 file_param 精确快照 CAS。
     */
    private FinalizationClaimSnapshot claimFinalization(
            File file,
            Long userId,
            String ownerToken,
            String mode,
            String fingerprint,
            String sanitizedFileParam
    ) {
        FinalizationClaim currentClaim = readFinalizationClaim(file.getFileParam());
        if (currentClaim != null) {
            validateOwnedClaim(file.getId(), currentClaim, ownerToken, mode, fingerprint);
            validateClaimCleanFileParam(file.getFileParam(), sanitizedFileParam);
            return new FinalizationClaimSnapshot(file.getFileParam(), currentClaim);
        }

        FinalizationClaim claimed = new FinalizationClaim(
                FINALIZATION_CLAIM_VERSION,
                ownerToken,
                mode,
                fingerprint,
                FINALIZATION_PHASE_CLAIMED,
                "",
                "");
        String oldFileParam = file.getFileParam();
        String claimedFileParam = writeFinalizationClaim(sanitizedFileParam, claimed);
        try {
            if (!casPreparedFileParam(file, userId, oldFileParam, claimedFileParam)
                    && !hasExactPreparedFileParam(
                        file.getId(), userId, claimedFileParam)) {
                throw finalizationCasConflict(file.getId());
            }
        } catch (RuntimeException claimWriteError) {
            if (!hasExactPreparedFileParam(file.getId(), userId, claimedFileParam)) {
                throw claimWriteError;
            }
            log.warn("CLAIMED 检查点写响应异常但精确回读确认已提交: fileId={}",
                    file.getId(), claimWriteError);
        }
        file.setFileParam(claimedFileParam);
        return new FinalizationClaimSnapshot(claimedFileParam, claimed);
    }

    /**
     * 以 exact old file_param 快照推进 claim 阶段，确保并发 owner 无法覆盖彼此状态。
     */
    private FinalizationClaimSnapshot transitionFinalizationClaim(
            File file,
            Long userId,
            FinalizationClaimSnapshot expected,
            String nextPhase,
            String transactionHash,
            String fileHash
    ) {
        if (!isValidFinalizationClaim(expected.claim())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "最终化 claim 状态不变量无效");
        }
        validateFinalizationTransition(expected.claim().phase(), nextPhase);
        FinalizationClaim nextClaim = new FinalizationClaim(
                expected.claim().v(),
                expected.claim().ownerToken(),
                expected.claim().mode(),
                expected.claim().fingerprint(),
                nextPhase,
                Objects.requireNonNullElse(transactionHash, ""),
                Objects.requireNonNullElse(fileHash, ""));
        String nextFileParam = writeFinalizationClaim(expected.fileParamSnapshot(), nextClaim);
        try {
            if (!casPreparedFileParam(
                    file, userId, expected.fileParamSnapshot(), nextFileParam)
                    && !hasExactPreparedFileParam(file.getId(), userId, nextFileParam)) {
                throw finalizationCasConflict(file.getId());
            }
        } catch (RuntimeException transitionError) {
            if (!hasExactPreparedFileParam(file.getId(), userId, nextFileParam)) {
                throw transitionError;
            }
            log.warn("最终化阶段写响应异常但精确回读确认已提交: fileId={}, phase={}",
                    file.getId(), nextPhase, transitionError);
        }
        file.setFileParam(nextFileParam);
        return new FinalizationClaimSnapshot(nextFileParam, nextClaim);
    }

    /**
     * 校验直接完成阶段只能消费当前 owner 的 ATTESTED claim 和其中的链结果。
     */
    private FinalizationClaimSnapshot requireOwnedAttestedClaim(
            File file,
            String ownerToken,
            String mode,
            StoreFileResponse chainResult
    ) {
        FinalizationClaim claim = readFinalizationClaim(file.getFileParam());
        if (claim == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "最终化 claim 不存在");
        }
        validateOwnedClaim(file.getId(), claim, ownerToken, mode, claim.fingerprint());
        if (FINALIZATION_PHASE_CHAIN_ATTESTING.equals(claim.phase())) {
            throw unresolvedFinalizationException(file.getId());
        }
        if (!FINALIZATION_PHASE_CHAIN_ATTESTED.equals(claim.phase())
                || !Objects.equals(claim.txHash(), chainResult.transactionHash())
                || !Objects.equals(claim.fileHash(), chainResult.fileHash())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "链检查点与最终化 claim 不一致");
        }
        return new FinalizationClaimSnapshot(file.getFileParam(), claim);
    }

    /**
     * 使用 ATTESTED 快照精确 CAS 到 SUCCESS，并以无内部 claim 的干净 file_param 覆盖 PREPARE。
     */
    private File persistFinalizationSuccess(
            File existingFile,
            Long userId,
            Long fileSize,
            String sanitizedFileParam,
            String contentHash,
            StoreFileResponse chainResult,
            FileParamEnvelopeResult envelopeResult,
            FinalizationClaimSnapshot claimSnapshot
    ) {
        FinalizationClaim claim = claimSnapshot.claim();
        if (!isValidFinalizationClaim(claim)
                || !FINALIZATION_PHASE_CHAIN_ATTESTED.equals(claim.phase())
                || !Objects.equals(claim.txHash(), chainResult.transactionHash())
                || !Objects.equals(claim.fileHash(), chainResult.fileHash())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "最终化 claim 尚未完成链确认");
        }
        Map<String, Object> claimedCleanParams = parseFileParamMap(claimSnapshot.fileParamSnapshot());
        claimedCleanParams.remove(FINALIZATION_CLAIM_KEY);
        Map<String, Object> suppliedCleanParams = parseFileParamMap(sanitizedFileParam);
        if (!Objects.equals(claimedCleanParams, suppliedCleanParams)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件参数与最终化 claim 不一致");
        }
        String cleanFileParam = JsonConverter.toJson(claimedCleanParams);
        if (CommonUtils.isEmpty(cleanFileParam)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "无法清理最终化 claim");
        }

        LambdaUpdateWrapper<File> wrapper = preparedFileParamCasWrapper(
                existingFile.getId(), userId, claimSnapshot.fileParamSnapshot());
        File update = new File()
                .setUid(userId)
                .setFileName(existingFile.getFileName())
                .setFileHash(chainResult.fileHash())
                .setContentHash(contentHash)
                .setTransactionHash(chainResult.transactionHash())
                .setFileParam(cleanFileParam)
                .setStatus(FileUploadStatus.SUCCESS.getCode());

        transactionTemplate.executeWithoutResult(status -> {
            lockProofLifecycleVersionGroupBeforeFileMutation(existingFile);
            if (!this.update(update, wrapper)) {
                throw finalizationCasConflict(existingFile.getId());
            }
            existingFile.setFileParam(cleanFileParam);
            fileKeyEnvelopeService.saveOwnerEnvelope(
                    existingFile, chainResult.fileHash(), userId, envelopeResult);
            markOlderProofIssuancesSuperseded(existingFile);
        });

        if (fileSize != null) {
            existingFile.setFileSize(fileSize);
        }
        return existingFile
                .setFileHash(chainResult.fileHash())
                .setContentHash(contentHash)
                .setTransactionHash(chainResult.transactionHash())
                .setFileParam(cleanFileParam)
                .setStatus(FileUploadStatus.SUCCESS.getCode());
    }

    /**
     * 校验重入请求的干净参数与既有 claim 绑定的业务参数完全一致。
     */
    private void validateClaimCleanFileParam(String claimedFileParam, String sanitizedFileParam) {
        Map<String, Object> claimedCleanParams = parseFileParamMap(claimedFileParam);
        claimedCleanParams.remove(FINALIZATION_CLAIM_KEY);
        if (!Objects.equals(claimedCleanParams, parseFileParamMap(sanitizedFileParam))) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件参数与最终化 claim 不一致");
        }
    }

    /**
     * 以文件主键、用户、PREPARE 状态及 exact old file_param 构造 CAS 条件。
     */
    private LambdaUpdateWrapper<File> preparedFileParamCasWrapper(
            Long fileId,
            Long userId,
            String oldFileParam
    ) {
        LambdaUpdateWrapper<File> wrapper = new LambdaUpdateWrapper<File>()
                .eq(File::getId, fileId)
                .eq(File::getUid, userId)
                .eq(File::getStatus, FileUploadStatus.PREPARE.getCode());
        if (oldFileParam == null) {
            wrapper.isNull(File::getFileParam);
        } else {
            wrapper.apply("HEX(file_param) = HEX({0})", oldFileParam);
        }
        return wrapper;
    }

    /**
     * 先锁定版本组锚点，再原子替换 PREPARE 的 file_param，不改变文件业务终态。
     */
    private boolean casPreparedFileParam(
            Long fileId,
            Long userId,
            String oldFileParam,
            String nextFileParam
    ) {
        return casPreparedFileParam(
                this.getById(fileId), userId, oldFileParam, nextFileParam);
    }

    /**
     * 使用已校验的 PREPARE 快照锁定版本组锚点，并在同一短事务中执行 file_param CAS。
     */
    private boolean casPreparedFileParam(
            File targetFile,
            Long userId,
            String oldFileParam,
            String nextFileParam
    ) {
        if (targetFile == null || !Objects.equals(targetFile.getUid(), userId)) {
            return false;
        }
        AtomicBoolean updated = new AtomicBoolean(false);
        transactionTemplate.executeWithoutResult(status -> {
            lockProofLifecycleVersionGroupBeforeFileMutation(targetFile);
            updated.set(this.update(
                    new File().setFileParam(nextFileParam),
                    preparedFileParamCasWrapper(
                            targetFile.getId(), userId, oldFileParam)));
        });
        return updated.get();
    }

    /**
     * 精确回读 PREPARE 的 file_param 字节快照，用于收敛数据库写响应未知。
     */
    private boolean hasExactPreparedFileParam(
            Long fileId,
            Long userId,
            String expectedFileParam
    ) {
        File persisted = this.getById(fileId);
        return persisted != null
                && Objects.equals(persisted.getUid(), userId)
                && Objects.equals(
                    persisted.getStatus(), FileUploadStatus.PREPARE.getCode())
                && Objects.equals(persisted.getFileParam(), expectedFileParam);
    }

    /**
     * 校验 claim 只能按 CLAIMED -> CHAIN_ATTESTING -> CHAIN_ATTESTED 单向推进。
     */
    private void validateFinalizationTransition(String currentPhase, String nextPhase) {
        boolean valid = FINALIZATION_PHASE_CLAIMED.equals(currentPhase)
                && FINALIZATION_PHASE_CHAIN_ATTESTING.equals(nextPhase)
                || FINALIZATION_PHASE_CHAIN_ATTESTING.equals(currentPhase)
                && FINALIZATION_PHASE_CHAIN_ATTESTED.equals(nextPhase);
        if (!valid) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "最终化 claim 阶段转换非法");
        }
    }

    /**
     * 校验既有 claim 属于同一 owner、模式和输入指纹；不同 owner 在外部调用前失败关闭。
     */
    private void validateOwnedClaim(
            Long fileId,
            FinalizationClaim claim,
            String ownerToken,
            String mode,
            String fingerprint
    ) {
        if (!isValidFinalizationClaim(claim)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "最终化 claim 状态不变量无效");
        }
        if (!Objects.equals(claim.ownerToken(), ownerToken)
                || !Objects.equals(claim.mode(), mode)
                || !Objects.equals(claim.fingerprint(), fingerprint)) {
            if (FINALIZATION_PHASE_CLAIMED.equals(claim.phase())) {
                throw new RetryableException(
                        ResultEnum.SERVICE_UNAVAILABLE,
                        Map.of("reason", "prepared file finalization is owned by another upload"));
            }
            throw unresolvedFinalizationException(fileId);
        }
    }

    /**
     * 校验最终化 claim 的版本、身份、模式与阶段链结果不变量，未知或半提交组合一律视为不可信。
     */
    private boolean isValidFinalizationClaim(FinalizationClaim claim) {
        if (claim == null
                || claim.v() != FINALIZATION_CLAIM_VERSION
                || claim.ownerToken() == null || claim.ownerToken().isBlank()
                || claim.fingerprint() == null || claim.fingerprint().isBlank()
                || !Set.of(FINALIZATION_MODE_LEGACY, FINALIZATION_MODE_DIRECT)
                    .contains(claim.mode())) {
            return false;
        }
        boolean hasTransactionHash = claim.txHash() != null && !claim.txHash().isBlank();
        boolean hasFileHash = claim.fileHash() != null && !claim.fileHash().isBlank();
        if (FINALIZATION_PHASE_CLAIMED.equals(claim.phase())
                || FINALIZATION_PHASE_CHAIN_ATTESTING.equals(claim.phase())) {
            return !hasTransactionHash && !hasFileHash;
        }
        if (FINALIZATION_PHASE_CHAIN_ATTESTED.equals(claim.phase())) {
            return hasTransactionHash && hasFileHash;
        }
        return false;
    }

    /**
     * 将 claim 写入保留业务字段的 file_param 顶层命名空间。
     */
    private String writeFinalizationClaim(String fileParam, FinalizationClaim claim) {
        Map<String, Object> params = parseFileParamMap(fileParam);
        Map<String, Object> claimMap = new LinkedHashMap<>();
        claimMap.put("v", claim.v());
        claimMap.put("ownerToken", claim.ownerToken());
        claimMap.put("mode", claim.mode());
        claimMap.put("fingerprint", claim.fingerprint());
        claimMap.put("phase", claim.phase());
        claimMap.put("txHash", claim.txHash());
        claimMap.put("fileHash", claim.fileHash());
        params.put(FINALIZATION_CLAIM_KEY, claimMap);
        String serialized = JsonConverter.toJson(params);
        if (CommonUtils.isEmpty(serialized)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "最终化 claim 序列化失败");
        }
        return serialized;
    }

    /**
     * 从 file_param 读取并严格校验内部 claim 字段。
     */
    private FinalizationClaim readFinalizationClaim(String fileParam) {
        Map<String, Object> params = parseFileParamMap(fileParam);
        Object rawClaim = params.get(FINALIZATION_CLAIM_KEY);
        if (rawClaim == null) {
            return null;
        }
        if (!(rawClaim instanceof Map<?, ?> claimMap)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "最终化 claim 格式无效");
        }
        Object version = claimMap.get("v");
        if (!(version instanceof Number number)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "最终化 claim 版本无效");
        }
        int parsedVersion = number.intValue();
        if (Double.compare(number.doubleValue(), parsedVersion) != 0) {
            parsedVersion = Integer.MIN_VALUE;
        }
        return new FinalizationClaim(
                parsedVersion,
                requireClaimText(claimMap, "ownerToken"),
                requireClaimText(claimMap, "mode"),
                requireClaimText(claimMap, "fingerprint"),
                requireClaimText(claimMap, "phase"),
                requireClaimText(claimMap, "txHash"),
                requireClaimText(claimMap, "fileHash"));
    }

    /**
     * 将 file_param 解析为可保序修改的 JSON 对象；空 PREPARE 参数按空对象处理。
     */
    private Map<String, Object> parseFileParamMap(String fileParam) {
        if (fileParam == null || fileParam.isBlank()) {
            return new LinkedHashMap<>();
        }
        Map<?, ?> parsed = JsonConverter.parse(fileParam, Map.class);
        if (parsed == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件参数格式无效");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : parsed.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件参数字段无效");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    /**
     * 读取 claim 必需字符串字段，允许未产生链结果的阶段以空字符串占位。
     */
    private String requireClaimText(Map<?, ?> claimMap, String field) {
        Object value = claimMap.get(field);
        if (!(value instanceof String text)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "最终化 claim 字段无效: " + field);
        }
        return text;
    }

    /**
     * 生成跨重试稳定的 owner；生产事件会显式传 clientId，旧 API 使用文件级兼容 owner。
     */
    private String normalizeFinalizationOwnerToken(String ownerToken, String mode, Long fileId) {
        if (ownerToken != null && !ownerToken.isBlank()) {
            return ownerToken;
        }
        return mode.toLowerCase(java.util.Locale.ROOT) + "-compat:" + fileId;
    }

    /**
     * 计算普通上传输入的稳定 SHA-256 指纹，覆盖干净参数、分片顺序、名称、大小和哈希。
     */
    private String calculateLegacyFinalizationFingerprint(
            String sanitizedFileParam,
            List<java.io.File> fileList,
            List<String> fileHashList
    ) {
        if (fileHashList == null || fileList.size() != fileHashList.size()) {
            throw new GeneralException(ResultEnum.FILE_UPLOAD_ERROR, "文件分片与哈希数量不一致");
        }
        List<String> components = new ArrayList<>();
        components.add(FINALIZATION_MODE_LEGACY);
        components.add(sanitizedFileParam);
        for (int index = 0; index < fileList.size(); index++) {
            java.io.File part = fileList.get(index);
            if (part == null) {
                throw new GeneralException(ResultEnum.FILE_UPLOAD_ERROR, "文件分片不能为空");
            }
            components.add(String.valueOf(index));
            components.add(part.getName());
            components.add(String.valueOf(part.length()));
            components.add(Objects.requireNonNullElse(fileHashList.get(index), ""));
        }
        return sha256Fingerprint(components);
    }

    /**
     * 计算直传输入拓扑的稳定 SHA-256 指纹，确保同内容但不同分片布局不会共享 claim。
     */
    private String calculateDirectFinalizationFingerprint(
            String sanitizedFileParam,
            List<DirectMultipartCompletedPartVO> completedParts
    ) {
        List<String> components = new ArrayList<>();
        components.add(FINALIZATION_MODE_DIRECT);
        components.add(sanitizedFileParam);
        for (DirectMultipartCompletedPartVO part : completedParts) {
            if (part == null) {
                throw new GeneralException(ResultEnum.PARAM_ERROR, "Stored part cannot be null");
            }
            components.add(String.valueOf(part.partIndex()));
            components.add(part.storagePath());
            components.add(String.valueOf(part.size()));
            components.add(part.eTag());
            components.add(part.plainHash());
            components.add(part.cipherHash());
            components.add(part.checksumAlgorithm());
        }
        return sha256Fingerprint(components);
    }

    /**
     * 使用长度前缀编码计算稳定 SHA-256，避免字段拼接歧义。
     */
    private String sha256Fingerprint(List<String> components) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String component : components) {
                byte[] value = Objects.requireNonNullElse(component, "")
                        .getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(value.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(value);
                digest.update((byte) ';');
            }
            return "sha256:" + java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 构造 CAS 冲突可安全重试异常；调用方重试后会重新读取 DB claim。
     */
    private RetryableException finalizationCasConflict(Long fileId) {
        return new RetryableException(
                ResultEnum.SERVICE_UNAVAILABLE,
                Map.of("reason", "prepared file finalization CAS conflict", "fileId", fileId));
    }

    /**
     * 构造链结果未知或已确认但尚未收敛到 SUCCESS 的人工对账错误。
     */
    private GeneralException unresolvedFinalizationException(Long fileId) {
        return new GeneralException(
                ResultEnum.BLOCKCHAIN_ERROR,
                "文件最终化链结果不可自动重放，请先完成链上对账: fileId=" + fileId);
    }

    /**
     * file_param 中持久化的最终化 claim。
     */
    private record FinalizationClaim(
            int v,
            String ownerToken,
            String mode,
            String fingerprint,
            String phase,
            String txHash,
            String fileHash
    ) {
        /**
         * 将已确认 claim 恢复为链结果。
         */
        private StoreFileResponse toChainResult() {
            if (txHash == null || txHash.isBlank() || fileHash == null || fileHash.isBlank()) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "最终化 claim 缺少链结果");
            }
            return new StoreFileResponse(txHash, fileHash);
        }
    }

    /**
     * claim 与其 exact file_param CAS 快照。
     */
    private record FinalizationClaimSnapshot(
            String fileParamSnapshot,
            FinalizationClaim claim
    ) {
    }

    /**
     * Sanitizes file metadata and protects raw initial keys before persistence.
     */
    private FileParamEnvelopeResult prepareFileParamEnvelope(String fileParam) {
        return fileKeyEnvelopeService.prepareFileParam(fileParam);
    }

    /**
     * 从已脱敏文件参数中读取并校验原文件整体 SHA-256，阻断链记录 ID 作为内容摘要写入。
     *
     * @param fileParam 已脱敏文件参数 JSON
     * @return 小写规范内容摘要
     */
    private String requireContentHash(String fileParam) {
        Map<?, ?> params;
        try {
            params = JsonConverter.parse(fileParam, Map.class);
        } catch (RuntimeException e) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件参数缺少可信内容哈希");
        }
        Object value = params == null ? null : params.get("contentHash");
        String normalized = value instanceof String text
                ? text.trim().toLowerCase(java.util.Locale.ROOT)
                : "";
        if (!CONTENT_HASH_PATTERN.matcher(normalized).matches()) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件参数缺少可信内容哈希");
        }
        return normalized;
    }

    /**
     * 新版本真正上传成功后推进旧版本 proof 在线状态；PREPARE/FAIL 不触发不可逆 superseded。
     *
     * @param completedFile 已完成上传的新版本 PREPARE 记录
     */
    private void markOlderProofIssuancesSuperseded(File completedFile) {
        if (completedFile.getVersionGroupId() == null
                || completedFile.getVersion() == null
                || completedFile.getVersion() <= 1) {
            return;
        }
        lockProofLifecycleVersionGroup(
                completedFile.getTenantId(),
                completedFile.getVersionGroupId(),
                "文件版本链不存在，无法推进 proof 状态");
        List<Long> olderFileIds = baseMapper.selectList(new LambdaQueryWrapper<File>()
                        .select(File::getId)
                        .eq(File::getTenantId, completedFile.getTenantId())
                        .eq(File::getVersionGroupId, completedFile.getVersionGroupId())
                        .lt(File::getVersion, completedFile.getVersion())
                        .eq(File::getStatus, FileUploadStatus.SUCCESS.getCode())
                        .eq(File::getDeleted, 0))
                .stream()
                .map(File::getId)
                .filter(Objects::nonNull)
                .toList();
        if (olderFileIds.isEmpty()) {
            return;
        }
        proofBundleIssuanceMapper.update(null, new LambdaUpdateWrapper<cn.flying.dao.entity.ProofBundleIssuance>()
                .eq(cn.flying.dao.entity.ProofBundleIssuance::getTenantId, completedFile.getTenantId())
                .in(cn.flying.dao.entity.ProofBundleIssuance::getFileId, olderFileIds)
                .eq(cn.flying.dao.entity.ProofBundleIssuance::getStatus, "ACTIVE")
                .set(cn.flying.dao.entity.ProofBundleIssuance::getStatus, "SUPERSEDED")
                .set(cn.flying.dao.entity.ProofBundleIssuance::getStatusReason, "newer_file_version")
                .setSql("status_version = status_version + 1"));
    }

    /**
     * 对版本 2+ 的上传完成事务先锁版本链锚点，再修改目标文件行，统一全局锁顺序。
     *
     * @param file 待从 PREPARE 推进为 SUCCESS 的文件
     */
    private void lockProofLifecycleVersionGroupBeforeFileMutation(File file) {
        if (file.getVersion() == null || file.getVersion() <= 1) {
            return;
        }
        lockProofLifecycleVersionGroup(
                file.getTenantId(),
                file.getVersionGroupId(),
                "文件版本链不存在，无法完成版本上传");
    }

    /**
     * 锁定版本链稳定锚点并对缺失链失败关闭。
     *
     * @param tenantId 租户ID
     * @param versionGroupId 版本组ID
     * @param failureMessage 锁定失败提示
     */
    private void lockProofLifecycleVersionGroup(
            Long tenantId,
            Long versionGroupId,
            String failureMessage
    ) {
        if (tenantId == null || versionGroupId == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, failureMessage);
        }
        Long anchorFileId = baseMapper.lockVersionGroupForProofLifecycle(tenantId, versionGroupId);
        if (anchorFileId == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, failureMessage);
        }
    }

    /**
     * 解析并校验待回写的 PREPARE 记录。
     *
     * @param userId 用户ID
     * @param targetFileId 指定目标文件ID（可为空）
     * @param originFileName 文件名
     * @return 匹配到的 PREPARE 记录
     */
    private File resolvePrepareFileForStore(Long userId, Long targetFileId, String originFileName) {
        LambdaQueryWrapper<File> fileQuery = new LambdaQueryWrapper<File>()
                .eq(File::getUid, userId)
                .eq(File::getStatus, FileUploadStatus.PREPARE.getCode());

        if (targetFileId != null) {
            fileQuery.eq(File::getId, targetFileId);
            if (CommonUtils.isNotEmpty(originFileName)) {
                fileQuery.eq(File::getFileName, originFileName);
            }
        } else {
            fileQuery.eq(File::getFileName, originFileName)
                    .orderByDesc(File::getCreateTime)
                    .orderByDesc(File::getId)
                    .last("LIMIT 1");
        }

        File existingFile = this.getOne(fileQuery);
        if (existingFile == null) {
            throw new GeneralException(ResultEnum.FAIL, "File metadata not initialized for upload");
        }
        return existingFile;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeFileStatusByHash(Long userId, String fileHash, Integer fileStatus) {
        LambdaUpdateWrapper<File> wrapper = new LambdaUpdateWrapper<File>()
                .eq(File::getFileHash, fileHash)
                .eq(File::getUid, userId);
        File file = new File()
                .setStatus(fileStatus);
        this.update(file,wrapper);
    }

    /**
     * 根据文件ID更新文件状态。
     *
     * @param userId 用户ID
     * @param fileId 文件ID
     * @param fileStatus 目标状态
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeFileStatusById(Long userId, Long fileId, Integer fileStatus) {
        LambdaUpdateWrapper<File> wrapper = new LambdaUpdateWrapper<File>()
                .eq(File::getId, fileId)
                .eq(File::getUid, userId);
        File file = new File().setStatus(fileStatus);
        this.update(file, wrapper);
    }

    /**
     * 将 PREPARE 文件以 CAS 方式标记为失败，并返回关联上传会话是否可以安全清理。
     *
     * @param userId 用户ID
     * @param fileId 目标文件ID
     * @return 已安全进入或原本已处于 FAIL 时返回 true；SUCCESS、其他状态或 CAS 冲突返回 false
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markFileUploadFailed(Long userId, Long fileId) {
        if (fileId == null) {
            return false;
        }

        File targetFile = this.getById(fileId);
        if (targetFile == null || !Objects.equals(targetFile.getUid(), userId)) {
            return false;
        }
        if (Objects.equals(targetFile.getStatus(), FileUploadStatus.FAIL.getCode())) {
            return true;
        }
        if (!Objects.equals(targetFile.getStatus(), FileUploadStatus.PREPARE.getCode())) {
            log.info("忽略非 PREPARE 文件的迟到失败回写: userId={}, fileId={}, status={}",
                    userId, fileId, targetFile.getStatus());
            return false;
        }
        // 与完成、新建版本统一使用版本组锚点锁；锁后必须重读，禁止基于过期 isLatest 恢复父版本。
        lockProofLifecycleVersionGroupBeforeFileMutation(targetFile);
        targetFile = this.getById(fileId);
        if (targetFile == null
                || !Objects.equals(targetFile.getUid(), userId)
                || !Objects.equals(targetFile.getStatus(), FileUploadStatus.PREPARE.getCode())) {
            return false;
        }
        FinalizationClaim latestClaim;
        try {
            latestClaim = readFinalizationClaim(targetFile.getFileParam());
        } catch (RuntimeException malformedClaim) {
            log.warn("文件最终化 claim 无法解析，拒绝失败回写并保留现场: userId={}, fileId={}",
                    userId, fileId, malformedClaim);
            return false;
        }
        if (latestClaim != null && (!isValidFinalizationClaim(latestClaim)
                || !FINALIZATION_PHASE_CLAIMED.equals(latestClaim.phase()))) {
            log.warn("文件最终化 claim 已进入不可逆阶段，拒绝失败回写: userId={}, fileId={}, phase={}",
                    userId, fileId, latestClaim.phase());
            return false;
        }

        boolean shouldRestoreParentLatest = targetFile.getParentVersionId() != null
                && Objects.equals(targetFile.getIsLatest(), 1);

        File failUpdate = new File().setStatus(FileUploadStatus.FAIL.getCode());
        if (shouldRestoreParentLatest) {
            failUpdate.setIsLatest(0);
        }
        LambdaUpdateWrapper<File> failWrapper = preparedFileParamCasWrapper(
                targetFile.getId(), userId, targetFile.getFileParam());
        if (targetFile.getIsLatest() == null) {
            failWrapper.isNull(File::getIsLatest);
        } else {
            failWrapper.eq(File::getIsLatest, targetFile.getIsLatest());
        }
        if (!this.update(failUpdate, failWrapper)) {
            log.info("文件状态已并发离开 PREPARE，跳过迟到失败回写: userId={}, fileId={}",
                    userId, fileId);
            return false;
        }

        if (shouldRestoreParentLatest) {
            restoreParentVersionAsLatest(targetFile);
        }
        return true;
    }

    /**
     * 将失败版本的父版本恢复为 latest，确保版本链仍有可用的最新成功版本。
     *
     * @param failedVersion 失败版本记录
     */
    private void restoreParentVersionAsLatest(File failedVersion) {
        Long parentVersionId = failedVersion.getParentVersionId();
        if (parentVersionId == null) {
            return;
        }

        File currentParent = this.getById(parentVersionId);
        if (currentParent == null
                || !Objects.equals(currentParent.getUid(), failedVersion.getUid())
                || !Objects.equals(currentParent.getStatus(), FileUploadStatus.SUCCESS.getCode())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "父版本不存在或不是可恢复的 SUCCESS");
        }
        if (Objects.equals(currentParent.getIsLatest(), 1)) {
            return;
        }

        LambdaUpdateWrapper<File> parentWrapper = new LambdaUpdateWrapper<File>()
                .eq(File::getId, parentVersionId)
                .eq(File::getUid, failedVersion.getUid())
                .eq(File::getStatus, FileUploadStatus.SUCCESS.getCode())
                .eq(File::getIsLatest, currentParent.getIsLatest());
        File parentUpdate = new File().setIsLatest(1);
        if (this.update(parentUpdate, parentWrapper)) {
            return;
        }

        File latestParent = this.getById(parentVersionId);
        if (latestParent == null
                || !Objects.equals(latestParent.getUid(), failedVersion.getUid())
                || !Objects.equals(latestParent.getStatus(), FileUploadStatus.SUCCESS.getCode())
                || !Objects.equals(latestParent.getIsLatest(), 1)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "父版本 latest 恢复失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteFiles(Long userId, List<String> identifiers) {
        if (CommonUtils.isEmpty(identifiers)) {
            return;
        }

        List<String> fileHashes = new ArrayList<>();
        List<Long> fileIds = new ArrayList<>();
        for (String identifier : identifiers) {
            if (CommonUtils.isEmpty(identifier)) {
                continue;
            }
            String normalized = identifier.trim();
            if (normalized.isEmpty()) {
                continue;
            }

            boolean allDigits = normalized.chars().allMatch(Character::isDigit);
            if (allDigits) {
                try {
                    fileIds.add(Long.parseLong(normalized));
                } catch (NumberFormatException ignored) {
                    fileHashes.add(normalized);
                }
            } else {
                fileHashes.add(normalized);
            }
        }

        if (fileHashes.isEmpty() && fileIds.isEmpty()) {
            return;
        }

        // 先查询要删除的文件，用于后续清除缓存
        LambdaQueryWrapper<File> queryWrapper = new LambdaQueryWrapper<File>()
                .eq(File::getUid, userId)
                .and(w -> {
                    boolean hasCondition = false;
                    if (!fileHashes.isEmpty()) {
                        w.in(File::getFileHash, fileHashes);
                        hasCondition = true;
                    }
                    if (!fileIds.isEmpty()) {
                        if (hasCondition) {
                            w.or();
                        }
                        w.in(File::getId, fileIds);
                    }
                })
                .select(File::getFileHash);
        List<File> filesToDelete = this.list(queryWrapper);

        // 支持同时按 fileHash 或 ID 匹配删除
        // 这样前端传入 file.id 时，对于 fileHash 为 null 的失败文件也能正确删除
        LambdaUpdateWrapper<File> wrapper = new LambdaUpdateWrapper<File>()
                .eq(File::getUid, userId)
                .and(w -> {
                    boolean hasCondition = false;
                    if (!fileHashes.isEmpty()) {
                        w.in(File::getFileHash, fileHashes);
                        hasCondition = true;
                    }
                    if (!fileIds.isEmpty()) {
                        if (hasCondition) {
                            w.or();
                        }
                        w.in(File::getId, fileIds);
                    }
                });
        // Logical delete only - physical cleanup is handled by FileCleanupTask scheduled job
        this.remove(wrapper);

        // 清除 fileDecryptInfo 缓存
        evictFileDecryptInfoCache(userId, filesToDelete);
    }

    /**
     * 清除文件解密信息缓存
     * <p>
     * 缓存 key 格式为 userId:fileHash，仅清除当前用户的缓存条目。
     * 如果其他用户保存了相同 hash 的分享文件，他们的缓存不会被清除，
     * 这是正确的行为，因为分享文件有独立的元数据副本，原始文件删除不影响分享文件。
     * </p>
     */
    private void evictFileDecryptInfoCache(Long userId, List<File> files) {
        Cache cache = cacheManager.getCache("fileDecryptInfo");
        if (cache != null && CommonUtils.isNotEmpty(files)) {
            for (File file : files) {
                if (file != null && file.getFileHash() != null) {
                    cache.evict(userId + ":" + file.getFileHash());
                }
            }
        }
    }

    @Override
    public List<File> getUserFilesList(Long userId) {
        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<>();
        // 所有用户（包括管理员）只能查询自己的文件
        // 管理员查看所有文件请使用 FileAdminService.getAllFiles()
        wrapper.eq(File::getUid, userId)
               .eq(File::getIsLatest, 1);
        return this.list(wrapper);
    }

    @Override
    public void getUserFilesPage(Long userId, Page<File> page) {
        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<>();
        // 所有用户（包括管理员）只能查询自己的文件
        // 管理员查看所有文件请使用 FileAdminService.getAllFiles()
        wrapper.eq(File::getUid, userId)
               .eq(File::getIsLatest, 1);
        this.page(page, wrapper);
    }

    @Override
    public List<String> getFileAddress(Long userId, String fileHash) {
        // 校验文件所有权：用户只能获取自己的文件地址，管理员可获取所有
        if (!SecurityUtils.isAdmin()) {
            LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                    .eq(File::getUid, userId)
                    .eq(File::getFileHash, fileHash);
            if (this.count(wrapper) == 0) {
                throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
            }
        }

        String userIdStr = String.valueOf(userId);
        Result<FileDetailVO> filePointer = fileRemoteClient.getFile(userIdStr, fileHash);
        FileDetailVO detailVO = ResultUtils.getData(filePointer);
        if (detailVO == null) {
            throw new GeneralException(ResultEnum.FAIL, "无法获取文件详情，文件可能不存在");
        }
        String fileContent = detailVO.content();
        if (CommonUtils.isEmpty(fileContent)) {
            throw new GeneralException(ResultEnum.FAIL, "文件内容为空");
        }
        List<StoredObjectReference> references = StoredObjectReferenceCodec.parseChainContent(fileContent);
        Result<List<String>> urlListResult = fileRemoteClient.getFileUrlListByHash(
                references.stream().map(StoredObjectReference::storagePath).toList(),
                references.stream().map(StoredObjectReference::cipherHash).toList()
        );
        return ResultUtils.getData(urlListResult);
    }

    @Override
    public List<byte[]> getFile(Long userId, String fileHash) {
        validateAccessibleFileForInMemoryTransfer(userId, fileHash);

        String userIdStr = String.valueOf(userId);
        Result<FileDetailVO> filePointer = fileRemoteClient.getFile(userIdStr, fileHash);
        FileDetailVO detailVO = ResultUtils.getData(filePointer);
        if (detailVO == null) {
            throw new GeneralException(ResultEnum.FAIL, "无法获取文件详情，文件可能不存在");
        }
        String fileContent = detailVO.content();
        if (CommonUtils.isEmpty(fileContent)) {
            throw new GeneralException(ResultEnum.FAIL, "文件内容为空");
        }
        List<StoredObjectReference> references = StoredObjectReferenceCodec.parseChainContent(fileContent);
        Result<List<byte[]>> fileListResult = fileRemoteClient.getFileListByHash(
                references.stream().map(StoredObjectReference::storagePath).toList(),
                references.stream().map(StoredObjectReference::cipherHash).toList()
        );
        return ResultUtils.getData(fileListResult);
    }

    /**
     * 校验当前用户可访问的文件是否适合通过内存聚合接口下载。
     *
     * @param userId 用户ID
     * @param fileHash 文件哈希
     */
    private void validateAccessibleFileForInMemoryTransfer(Long userId, String fileHash) {
        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                .eq(File::getFileHash, fileHash)
                .last("LIMIT 1");
        if (!SecurityUtils.isAdmin()) {
            wrapper.eq(File::getUid, userId);
        }
        File file = baseMapper.selectOne(wrapper);
        if (file == null) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
        }
        validateInMemoryTransferLimit(file);
    }

    /**
     * 校验分享所有者文件是否适合通过内存聚合接口下载。
     *
     * @param ownerId 分享所有者ID
     * @param fileHash 文件哈希
     */
    private void validateOwnerFileForInMemoryTransfer(Long ownerId, String fileHash) {
        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                .eq(File::getUid, ownerId)
                .eq(File::getFileHash, fileHash)
                .last("LIMIT 1");
        File file = baseMapper.selectOne(wrapper);
        if (file == null) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "文件不存在或无权访问");
        }
        validateInMemoryTransferLimit(file);
    }

    /**
     * 校验文件是否适合通过当前内存聚合下载接口返回。
     *
     * @param file 文件元数据
     */
    private void validateInMemoryTransferLimit(File file) {
        Long fileSize = file.getFileSize();
        if (fileSize != null && fileSize > MAX_IN_MEMORY_TRANSFER_BYTES) {
            throw new GeneralException(ResultEnum.PARAM_ERROR,
                    "文件超过当前下载上限 (" + (MAX_IN_MEMORY_TRANSFER_BYTES / 1024 / 1024) + "MB)");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String generateSharingCode(Long userId, List<String> fileHash, Integer expireMinutes, Integer shareType) {
        // 验证参数
        if (CommonUtils.isEmpty(fileHash)) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "文件列表不能为空");
        }
        if (expireMinutes == null || expireMinutes <= 0) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "过期时间必须为正数");
        }
        int validatedShareType = shareType != null ? shareType : ShareType.PUBLIC.getCode();
        requireSupportedShareType(validatedShareType, ResultEnum.PARAM_IS_INVALID);

        // 去重后验证用户拥有所有要分享的文件（避免重复 hash 导致误报）
        List<String> distinctHashes = fileHash.stream().distinct().toList();
        LambdaQueryWrapper<File> ownershipWrapper = new LambdaQueryWrapper<File>()
                .eq(File::getUid, userId)
                .in(File::getFileHash, distinctHashes);
        long ownedCount = this.count(ownershipWrapper);
        if (ownedCount != distinctHashes.size()) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "无权分享部分文件");
        }

        // 调用区块链生成分享码（核心能力：失败则直接返回错误，不做降级）
        Result<String> result = fileRemoteClient.shareFiles(new ShareFilesRequest(
                String.valueOf(userId),
                fileHash,
                expireMinutes
        ));
        String sharingCode = ResultUtils.getData(result);
        if (CommonUtils.isEmpty(sharingCode)) {
            throw new GeneralException(ResultEnum.BLOCKCHAIN_ERROR, "区块链返回的分享码为空");
        }

        // 同步写入数据库
        Long tenantId = TenantContext.getTenantId();
        Date expireTime = new Date(System.currentTimeMillis() + (long) expireMinutes * 60 * 1000L);

        // 分享码全局唯一（跨租户），提前检测避免唯一索引冲突导致 500
        boolean exists = findShareTenantIdGlobally(sharingCode) != null;
        if (exists) {
            throw new GeneralException(ResultEnum.BLOCKCHAIN_ERROR, "分享码冲突，请重试");
        }

        FileShare fileShare = new FileShare()
                .setTenantId(tenantId != null ? tenantId : 0L)
                .setUserId(userId)
                .setShareCode(sharingCode)
                .setShareType(validatedShareType)
                .setFileHashes(JsonConverter.toJson(fileHash))
                .setExpireTime(expireTime)
                .setAccessCount(0)
                .setStatus(FileShare.STATUS_ACTIVE)
                .setCreateTime(new Date());

        fileShareMapper.insert(fileShare);
        List<File> ownedFiles = this.list(ownershipWrapper);
        fileKeyEnvelopeService.saveShareEnvelopes(fileShare, ownedFiles, userId, "SHARE_CREATE");
        log.info("分享已生成: userId={}, shareId={}, shareType={}, fileCount={}",
                userId, fileShare.getId(), ShareType.fromCode(fileShare.getShareType()).getName(), fileHash.size());

        return sharingCode;
    }

    /**
     * 根据分享码获取分享文件列表，并按过期时间判定取消/过期状态
     */
    @Override
    public List<ShareFileVO> getShareFile(String sharingCode) {
        Long shareTenantId = findShareTenantIdGlobally(sharingCode);
        if (shareTenantId == null) {
            throw new GeneralException(ResultEnum.SHARE_NOT_FOUND);
        }
        return TenantContext.callWithTenantIsolation(shareTenantId, () -> {
            FileShare share = requirePublicActiveShare(sharingCode);
            List<String> fileHashList = parseFileHashes(share.getFileHashes());
            if (CommonUtils.isEmpty(fileHashList)) {
                return List.of();
            }

            List<File> files = listShareFilesInShareTenant(share, fileHashList);
            return files.stream().map(ShareFileVO::fromFile).toList();
        });
    }

    /**
     * 校验公开分享元数据访问，并返回处于有效状态的分享记录。
     *
     * @param shareCode 分享码
     * @return 公开且有效的分享记录
     */
    private FileShare requirePublicActiveShare(String shareCode) {
        if (CommonUtils.isBlank(shareCode)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "分享码不能为空");
        }

        int expiredCount = fileShareMapper.markAsExpiredIfNecessary(shareCode);
        FileShare share = fileShareMapper.selectByShareCode(shareCode);
        if (share == null) {
            throw new GeneralException(ResultEnum.SHARE_NOT_FOUND);
        }
        if (expiredCount > 0) {
            share.setStatus(FileShare.STATUS_EXPIRED);
        }
        if (Objects.equals(share.getStatus(), FileShare.STATUS_CANCELLED)) {
            throw new GeneralException(ResultEnum.SHARE_CANCELLED);
        }
        if (Objects.equals(share.getStatus(), FileShare.STATUS_EXPIRED)
                || (share.getExpireTime() != null && share.getExpireTime().before(new Date()))) {
            throw new GeneralException(ResultEnum.SHARE_EXPIRED);
        }
        if (!Objects.equals(share.getStatus(), FileShare.STATUS_ACTIVE)) {
            throw new GeneralException(ResultEnum.FAIL, "分享状态无效");
        }
        if (!Objects.equals(share.getShareType(), ShareType.PUBLIC.getCode())) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "此分享需要登录后才能访问");
        }
        fileShareMapper.incrementAccessCountIfActive(shareCode);
        return share;
    }

    /**
     * 在分享所属租户内读取分享文件，避免为了公开分享元数据绕过所有租户过滤。
     *
     * @param share 分享记录
     * @param fileHashList 分享授权的文件哈希
     * @return 分享文件实体列表
     */
    private List<File> listShareFilesInShareTenant(FileShare share, List<String> fileHashList) {
        Long shareTenantId = share.getTenantId() != null ? share.getTenantId() : 0L;
        return TenantContext.callWithTenantIsolation(shareTenantId, () -> {
            LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                    .eq(File::getUid, share.getUserId())
                    .in(File::getFileHash, fileHashList);
            return this.list(wrapper);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveShareFile(List<String> sharingFileIdList, String shareCode, String clientIp) {
        if (CommonUtils.isEmpty(sharingFileIdList)) {
            return;
        }
        FileShare fileShare = requireActiveShareForSave(shareCode);
        List<String> authorizedFileHashes = parseFileHashes(fileShare.getFileHashes());
        if (CommonUtils.isEmpty(authorizedFileHashes)) {
            throw new GeneralException(ResultEnum.FAIL, "分享文件为空");
        }

        // 获取当前登录用户ID，未登录时抛出异常
        String userIdStr = MDC.get(Const.ATTR_USER_ID);
        if (CommonUtils.isEmpty(userIdStr)) {
            throw new GeneralException(ResultEnum.USER_NOT_LOGGED_IN, "用户未登录，无法保存分享文件");
        }

        Long userId;
        try {
            userId = Long.valueOf(userIdStr);
        } catch (NumberFormatException e) {
            log.error("MDC 中的用户ID格式非法: {}", userIdStr);
            throw new GeneralException(ResultEnum.PARAM_ERROR, "用户ID格式非法");
        }

        List<File> fileList = listRequestedShareFiles(fileShare, sharingFileIdList);
        validateRequestedShareFiles(fileShare, authorizedFileHashes, sharingFileIdList, fileList);

        if (CommonUtils.isEmpty(fileList)) {
            log.warn("未找到指定的分享文件: ids={}", sharingFileIdList);
            return;
        }

        // 过滤掉自己的文件，只保存其他用户分享的文件
        final Long currentUserId = userId;
        fileList = fileList.stream()
                .filter(file -> !currentUserId.equals(file.getUid()))
                .toList();

        if (CommonUtils.isEmpty(fileList)) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "不能保存自己的文件");
        }

        // 构建需要插入的 FileSource 记录列表
        List<FileSource> fileSources = new ArrayList<>();

        // 拷贝其它用户分享文件对应的信息，修改文件所有人并增加文件来源
        for (File file : fileList) {
            Long sourceFileId = file.getId();
            Long sourceUserId = file.getUid();
            Long originFileId = file.getOrigin();

            // 如果源文件已经有来源，则保留最初的文件所有人；否则源文件就是原始文件
            if (originFileId == null) {
                originFileId = sourceFileId;
            }

            // 计算链路深度：查询源文件的深度并加1
            int depth = 1;
            Long sourceTenantId = file.getTenantId() != null ? file.getTenantId() : TenantContext.getTenantIdOrDefault();
            FileSource sourceFileSource = TenantContext.callWithTenantIsolation(
                    sourceTenantId,
                    () -> fileSourceMapper.selectByFileId(sourceFileId, sourceTenantId));
            if (sourceFileSource != null) {
                depth = sourceFileSource.getDepth() + 1;
            }

            Long copiedFileId = IdUtils.nextEntityId();
            File copiedFile = copyShareFileForUser(file, userId, originFileId, copiedFileId);

            // 先保存文件以获取新ID
            this.save(copiedFile);
            fileKeyEnvelopeService.saveCopiedOwnerEnvelope(
                    file,
                    copiedFile,
                    fileShare,
                    userId,
                    userId,
                    "SHARE_SAVE_OWNER_ENVELOPE"
            );

            // 创建 FileSource 记录
            FileSource fileSource = new FileSource()
                    .setTenantId(TenantContext.getTenantIdOrDefault())
                    .setFileId(copiedFile.getId())
                    .setOriginFileId(originFileId)
                    .setSourceFileId(sourceFileId)
                    .setSourceUserId(sourceUserId)
                    .setShareCode(shareCode)
                    .setDepth(depth)
                    .setDeleted(0);
            fileSources.add(fileSource);

            // 记录审计日志
            if (CommonUtils.isNotEmpty(shareCode)) {
                shareAuditService.logShareSave(shareCode, userId, copiedFile.getFileHash(), copiedFile.getFileName(), clientIp);
            }
        }

        // 批量保存 FileSource 记录
        if (CommonUtils.isNotEmpty(fileSources)) {
            for (FileSource fs : fileSources) {
                fileSourceMapper.insert(fs);
            }
        }

        log.info("成功保存分享文件: userId={}, 文件数量={}", userId, fileList.size());
    }

    /**
     * 校验保存分享文件所需的分享记录状态。
     *
     * @param shareCode 分享码
     * @return 有效分享记录
     */
    private FileShare requireActiveShareForSave(String shareCode) {
        if (CommonUtils.isBlank(shareCode)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "分享码不能为空");
        }

        Long shareTenantId = findShareTenantIdGlobally(shareCode);
        if (shareTenantId == null) {
            throw new GeneralException(ResultEnum.SHARE_NOT_FOUND);
        }
        FileShare share = TenantContext.callWithTenantIsolation(shareTenantId, () -> {
            int expiredCount = fileShareMapper.markAsExpiredIfNecessary(shareCode);
            FileShare current = fileShareMapper.selectByShareCode(shareCode);
            if (current != null && expiredCount > 0) {
                current.setStatus(FileShare.STATUS_EXPIRED);
            }
            return current;
        });

        if (share == null) {
            throw new GeneralException(ResultEnum.SHARE_NOT_FOUND);
        }
        if (Objects.equals(share.getStatus(), FileShare.STATUS_CANCELLED)) {
            throw new GeneralException(ResultEnum.SHARE_CANCELLED);
        }
        if (Objects.equals(share.getStatus(), FileShare.STATUS_EXPIRED)
                || (share.getExpireTime() != null && share.getExpireTime().before(new Date()))) {
            throw new GeneralException(ResultEnum.SHARE_EXPIRED);
        }
        if (!Objects.equals(share.getStatus(), FileShare.STATUS_ACTIVE)) {
            throw new GeneralException(ResultEnum.FAIL, "分享状态无效");
        }
        requireSupportedShareType(share.getShareType(), ResultEnum.FAIL);
        return share;
    }

    /**
     * 在分享所属租户内按请求 ID 查询待保存的源文件。
     *
     * @param share 分享记录
     * @param sharingFileIdList 请求保存的文件 ID 列表
     * @return 源文件列表
     */
    private List<File> listRequestedShareFiles(FileShare share, List<String> sharingFileIdList) {
        Long shareTenantId = share.getTenantId() != null ? share.getTenantId() : 0L;
        return TenantContext.callWithTenantIsolation(shareTenantId, () -> {
            LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                    .in(File::getId, sharingFileIdList);
            List<File> files = this.list(wrapper);
            return files != null ? files : List.of();
        });
    }

    /**
     * 校验请求的文件 ID 全部属于指定分享授权的文件集合。
     *
     * @param share 分享记录
     * @param authorizedFileHashes 分享授权的文件哈希
     * @param requestedFileIds 请求保存的文件 ID
     * @param files 查询到的源文件
     */
    private void validateRequestedShareFiles(FileShare share,
                                             List<String> authorizedFileHashes,
                                             List<String> requestedFileIds,
                                             List<File> files) {
        Set<String> requestedIds = new HashSet<>(requestedFileIds);
        Set<String> matchedIds = new HashSet<>();
        for (File file : files) {
            matchedIds.add(String.valueOf(file.getId()));
            if (!Objects.equals(file.getUid(), share.getUserId())
                    || !authorizedFileHashes.contains(file.getFileHash())) {
                throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "分享码未授权所选文件");
            }
        }
        if (!matchedIds.containsAll(requestedIds)) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "分享码未授权所选文件");
        }
    }

    /**
     * 复制分享源文件为当前用户的新文件记录。
     *
     * @param sourceFile 分享源文件
     * @param userId 当前保存用户 ID
     * @param originFileId 原始文件 ID
     * @param copiedFileId 新副本内部 ID，同时作为独立版本链 ID
     * @return 新文件记录
     */
    private File copyShareFileForUser(
            File sourceFile,
            Long userId,
            Long originFileId,
            Long copiedFileId
    ) {
        return new File()
                .setId(copiedFileId)
                .setTenantId(TenantContext.getTenantIdOrDefault())
                .setUid(userId)
                .setOrigin(originFileId)
                .setSharedFromUserId(sourceFile.getUid())
                .setFileName(sourceFile.getFileName())
                .setClassification(sourceFile.getClassification())
                .setFileParam(sourceFile.getFileParam())
                .setFileHash(sourceFile.getFileHash())
                .setContentHash(sourceFile.getContentHash())
                .setTransactionHash(sourceFile.getTransactionHash())
                .setStatus(sourceFile.getStatus())
                .setDeleted(0)
                .setVersion(1)
                .setParentVersionId(null)
                .setIsLatest(1)
                .setVersionGroupId(copiedFileId);
    }

    @Override
    @OperationLog(module = "FILE_SECURITY", operationType = "KEY_ACCESS", description = "访问文件解密密钥")
    @io.github.resilience4j.ratelimiter.annotation.RateLimiter(name = "fileKeyAccessRateLimiter", fallbackMethod = "fileKeyAccessRateLimitFallback")
    public FileDecryptInfoVO getFileDecryptInfo(Long userId, String fileHash) {
        // 校验文件所有权：用户只能获取自己的文件解密信息，管理员可获取所有
        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                .eq(File::getFileHash, fileHash);

        if (!SecurityUtils.isAdmin()) {
            wrapper.eq(File::getUid, userId);
        }

        File file = this.getOne(wrapper);
        if (file == null) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "文件不存在或无权限访问");
        }

        // 记录密钥访问审计日志
        Long tenantId = TenantContext.getTenantId();
        log.info("访问文件解密密钥: userId={}, fileId={}, fileName={}, fileHash={}, tenantId={}, accessTime={}",
                userId, file.getId(), file.getFileName(), fileHash, tenantId, new Date());

        return buildFileDecryptInfo(file, fileHash, file.getUid(), userId);
    }

    /**
     * Rate limiter fallback for getFileDecryptInfo
     */
    private FileDecryptInfoVO fileKeyAccessRateLimitFallback(Long userId, String fileHash, Throwable t) {
        log.warn("文件解密密钥访问限流触发: userId={}, fileHash={}, error={}", userId, fileHash, t.getMessage());
        throw new GeneralException(ResultEnum.RATE_LIMIT_EXCEEDED);
    }

    @Override
    public IPage<FileShareVO> getUserShares(Long userId, Page<?> page) {
        Long tenantId = TenantContext.getTenantId();

        // 从数据库查询用户分享记录
        LambdaQueryWrapper<FileShare> wrapper = new LambdaQueryWrapper<FileShare>()
                .eq(tenantId != null, FileShare::getTenantId, tenantId)
                .eq(FileShare::getUserId, userId)
                .orderByDesc(FileShare::getCreateTime);

        Page<FileShare> sharePage = new Page<>(page.getCurrent(), page.getSize());
        fileShareMapper.selectPage(sharePage, wrapper);

        // 转换为 FileShareVO
        List<FileShareVO> shareVOList = new ArrayList<>();
        for (FileShare share : sharePage.getRecords()) {
            FileShareVO vo = convertFileShareToVO(share);

            // 查询文件名列表
            List<String> fileHashes = parseFileHashes(share.getFileHashes());
            if (CommonUtils.isNotEmpty(fileHashes)) {
                LambdaQueryWrapper<File> fileWrapper = new LambdaQueryWrapper<File>()
                        .eq(File::getUid, userId)
                        .in(File::getFileHash, fileHashes);
                List<File> files = this.list(fileWrapper);
                vo.setFileNames(files.stream().map(File::getFileName).toList());
            }

            shareVOList.add(vo);
        }

        Page<FileShareVO> result = new Page<>(page.getCurrent(), page.getSize());
        result.setRecords(shareVOList);
        result.setTotal(sharePage.getTotal());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelShare(Long userId, String shareCode) {
        // 先验证分享是否属于该用户（从数据库查询）
        FileShare fileShare = fileShareMapper.selectByShareCode(shareCode);
        if (fileShare == null) {
            throw new GeneralException(ResultEnum.FAIL, "分享记录不存在");
        }

        // 权限校验
        if (!userId.equals(fileShare.getUserId())) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "无权操作此分享");
        }

        // 检查是否已取消
        if (fileShare.getStatus() == FileShare.STATUS_CANCELLED) {
            throw new GeneralException(ResultEnum.FAIL, "分享已被取消");
        }

        // 调用区块链取消分享（核心能力：失败则直接返回错误，不做降级）
        Result<Boolean> result = fileRemoteClient.cancelShare(
                new CancelShareRequest(
                        shareCode,
                        String.valueOf(userId),
                        String.valueOf(userId)
                ));
        if (!ResultUtils.isSuccess(result) || !Boolean.TRUE.equals(ResultUtils.getData(result))) {
            throw new GeneralException(ResultEnum.BLOCKCHAIN_ERROR, "取消分享失败");
        }

        // 同步更新数据库状态
        LambdaUpdateWrapper<FileShare> wrapper = new LambdaUpdateWrapper<FileShare>()
                .eq(FileShare::getShareCode, shareCode)
                .set(FileShare::getStatus, FileShare.STATUS_CANCELLED)
                .set(FileShare::getUpdateTime, new Date());
        fileShareMapper.update(null, wrapper);
        fileKeyEnvelopeService.revokeShareEnvelopes(fileShare, userId, "USER_CANCEL_SHARE");

        log.info("分享已取消: userId={}, shareId={}", userId, fileShare.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateShare(Long userId, UpdateShareVO updateVO) {
        if (updateVO.getShareType() != null) {
            requireSupportedShareType(updateVO.getShareType(), ResultEnum.PARAM_IS_INVALID);
        }
        if (updateVO.getShareType() == null && updateVO.getExtendMinutes() == null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "至少需要更新一项分享设置");
        }
        FileShare fileShare = fileShareMapper.selectByShareCode(updateVO.getShareCode());
        if (fileShare == null) {
            throw new GeneralException(ResultEnum.FAIL, "分享记录不存在");
        }

        // 权限校验
        if (!userId.equals(fileShare.getUserId())) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "无权操作此分享");
        }

        // 检查是否已取消
        if (Objects.equals(fileShare.getStatus(), FileShare.STATUS_CANCELLED)) {
            throw new GeneralException(ResultEnum.FAIL, "分享已被取消，无法修改");
        }
        if (!Objects.equals(fileShare.getStatus(), FileShare.STATUS_ACTIVE)
                && !Objects.equals(fileShare.getStatus(), FileShare.STATUS_EXPIRED)) {
            throw new GeneralException(ResultEnum.FAIL, "分享状态无效");
        }

        // 条件更新保证并发取消一旦提交，延期或类型更新不能覆盖取消状态
        LambdaUpdateWrapper<FileShare> wrapper = new LambdaUpdateWrapper<FileShare>()
                .eq(FileShare::getShareCode, updateVO.getShareCode())
                .eq(FileShare::getUserId, userId)
                .ne(FileShare::getStatus, FileShare.STATUS_CANCELLED);

        // 更新分享类型
        if (updateVO.getShareType() != null) {
            wrapper.set(FileShare::getShareType, updateVO.getShareType());
        }

        // 延长有效期（从当前时间开始计算）
        if (updateVO.getExtendMinutes() != null && updateVO.getExtendMinutes() > 0) {
            Date newExpireTime = new Date(System.currentTimeMillis() + (long) updateVO.getExtendMinutes() * 60 * 1000L);
            wrapper.set(FileShare::getExpireTime, newExpireTime)
                    .set(FileShare::getStatus, FileShare.STATUS_ACTIVE);
        }

        int updated = fileShareMapper.update(null, wrapper);
        if (updated != 1) {
            throw new GeneralException(ResultEnum.FAIL, "分享状态已变化，请刷新后重试");
        }
        log.info("分享设置已更新: userId={}, shareId={}", userId, fileShare.getId());
    }

    @Override
    public FileShare getShareByCode(String shareCode) {
        if (TenantContext.isSet()) {
            return TenantContext.callWithTenantIsolation(
                    TenantContext.requireTenantId(),
                    () -> getShareByCodeInternal(shareCode));
        }
        Long shareTenantId = findShareTenantIdGlobally(shareCode);
        if (shareTenantId == null) {
            return null;
        }
        return TenantContext.callWithTenantIsolation(shareTenantId, () -> getShareByCodeInternal(shareCode));
    }

    /**
     * 根据分享码查询分享记录，并在查询前做过期标记与访问计数更新。
     * <p>
     * 注意：公开分享入口必须先通过 mapper 级窄查询恢复 owner tenant，再在 owner tenant
     * 调用本方法；无租户上下文的跨租户兼容分支仅保留给既有服务接口，不得用于公开入口。
     * </p>
     *
     * @param shareCode 分享码
     * @return 分享记录，不存在返回 null
     */
    private FileShare getShareByCodeInternal(String shareCode) {
        // 原子操作：先尝试将过期分享标记为过期状态，返回更新条数
        int expiredCount = fileShareMapper.markAsExpiredIfNecessary(shareCode);

        FileShare fileShare = fileShareMapper.selectByShareCode(shareCode);
        if (fileShare != null) {
            // 如果刚刚被标记为过期，同步更新内存对象状态
            if (expiredCount > 0) {
                fileShare.setStatus(FileShare.STATUS_EXPIRED);
            }
            // 原子操作：仅当分享处于活跃状态时增加访问计数
            // 避免 TOCTOU 竞态条件
            if (Objects.equals(fileShare.getStatus(), FileShare.STATUS_ACTIVE)) {
                fileShareMapper.incrementAccessCountIfActive(shareCode);
            }
        }
        return fileShare;
    }

    @Override
    public ShareInfoVO getShareInfo(String shareCode) {
        Long shareTenantId = findShareTenantIdGlobally(shareCode);
        if (shareTenantId == null) {
            return null;
        }
        return TenantContext.callWithTenantIsolation(shareTenantId, () -> getShareInfoInCurrentTenant(shareCode));
    }

    /**
     * 在已经恢复的分享 owner 租户内读取分享详情及安全文件视图。
     *
     * @param shareCode 分享码
     * @return 分享详情，不存在返回 null
     */
    private ShareInfoVO getShareInfoInCurrentTenant(String shareCode) {
        int expiredCount = fileShareMapper.markAsExpiredIfNecessary(shareCode);
        FileShare fileShare = fileShareMapper.selectByShareCode(shareCode);
        if (fileShare == null) {
            return null;
        }
        if (expiredCount > 0) {
            fileShare.setStatus(FileShare.STATUS_EXPIRED);
        }

        // 分享状态校验
        if (Objects.equals(fileShare.getStatus(), FileShare.STATUS_CANCELLED)) {
            ShareInfoVO cancelled = new ShareInfoVO();
            cancelled.setShareCode(shareCode);
            cancelled.setStatus(FileShare.STATUS_CANCELLED);
            return cancelled;
        }
        if (Objects.equals(fileShare.getStatus(), FileShare.STATUS_EXPIRED)) {
            ShareInfoVO expired = new ShareInfoVO();
            expired.setShareCode(shareCode);
            expired.setStatus(FileShare.STATUS_EXPIRED);
            return expired;
        }
        if (!Objects.equals(fileShare.getStatus(), FileShare.STATUS_ACTIVE)) {
            throw new GeneralException(ResultEnum.FAIL, "分享状态无效");
        }

        // 过期时间校验
        if (fileShare.getExpireTime() != null && fileShare.getExpireTime().before(new Date())) {
            ShareInfoVO expired = new ShareInfoVO();
            expired.setShareCode(shareCode);
            expired.setStatus(FileShare.STATUS_EXPIRED);
            return expired;
        }

        if (!Objects.equals(fileShare.getShareType(), ShareType.PUBLIC.getCode())) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "此分享需要登录后才能访问");
        }
        fileShareMapper.incrementAccessCountIfActive(shareCode);

        // 解析文件哈希
        List<String> fileHashes = parseFileHashes(fileShare.getFileHashes());
        if (CommonUtils.isEmpty(fileHashes)) {
            ShareInfoVO empty = new ShareInfoVO();
            empty.setShareCode(shareCode);
            empty.setStatus(ShareInfoVO.STATUS_EMPTY_FILES);
            return empty;
        }

        List<File> files = listShareFilesInShareTenant(fileShare, fileHashes);

        ShareInfoVO info = new ShareInfoVO();
        info.setShareCode(fileShare.getShareCode());
        info.setShareType(fileShare.getShareType());
        info.setExpireTime(fileShare.getExpireTime());
        info.setFiles(files != null ? files.stream().map(ShareFileVO::fromFile).toList() : List.of());
        return info;
    }

    @Override
    public List<byte[]> getPublicFile(String shareCode, String fileHash) {
        return callWithPublicShareAccess(shareCode, fileHash, accessContext -> {
            validateOwnerFileForInMemoryTransfer(accessContext.ownerId(), fileHash);

            // 使用 owner 的身份获取文件
            Result<FileDetailVO> filePointer = fileRemoteClient.getFile(
                    String.valueOf(accessContext.ownerId()), fileHash);
            FileDetailVO detailVO = ResultUtils.getData(filePointer);
            if (detailVO == null) {
                throw new GeneralException(ResultEnum.FAIL, "无法获取文件详情");
            }
            String fileContent = detailVO.content();
            if (CommonUtils.isEmpty(fileContent)) {
                throw new GeneralException(ResultEnum.FAIL, "文件内容为空");
            }
            List<StoredObjectReference> references = StoredObjectReferenceCodec.parseChainContent(fileContent);
            Result<List<byte[]>> fileListResult = fileRemoteClient.getFileListByHash(
                    references.stream().map(StoredObjectReference::storagePath).toList(),
                    references.stream().map(StoredObjectReference::cipherHash).toList());
            List<byte[]> files = ResultUtils.getData(fileListResult);
            incrementShareAccessCount(accessContext);
            return files;
        });
    }

    @Override
    public FileDecryptInfoVO getPublicFileDecryptInfo(String shareCode, String fileHash) {
        return callWithPublicShareAccess(shareCode, fileHash, accessContext -> {
            // 查询文件元数据
            LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                    .eq(File::getUid, accessContext.ownerId())
                    .eq(File::getFileHash, fileHash);
            File file = this.getOne(wrapper);
            if (file == null) {
                throw new GeneralException(ResultEnum.FAIL, "文件不存在");
            }

            return buildShareFileDecryptInfo(file, fileHash, accessContext, null);
        });
    }

    /**
     * 通过全局唯一分享码只解析 owner 租户，再在该租户内完成公开文件授权与业务操作。
     *
     * @param shareCode 分享码
     * @param fileHash 文件哈希
     * @param action owner 租户内执行的业务动作
     * @param <T> 返回类型
     * @return 业务结果
     */
    private <T> T callWithPublicShareAccess(String shareCode,
                                            String fileHash,
                                            Function<ShareAccessContext, T> action) {
        Long shareTenantId = findShareTenantIdGlobally(shareCode);
        if (shareTenantId == null) {
            throw new GeneralException(ResultEnum.FAIL, "分享不存在");
        }
        return TenantContext.callWithTenantIsolation(shareTenantId, () -> {
            ShareAccessContext accessContext = resolveShareAccess(shareCode, fileHash, ShareType.PUBLIC);
            return action.apply(accessContext);
        });
    }

    /**
     * 使用 mapper 级单条 SQL 例外只读取全局分享所属租户，避免打开线程级跨租户模式。
     *
     * @param shareCode 分享码
     * @return 分享所属租户，不存在返回 null
     */
    private Long findShareTenantIdGlobally(String shareCode) {
        return fileShareMapper.selectTenantIdByShareCodeGlobally(shareCode);
    }

    /**
     * 登录用户通过分享码下载文件（支持私密/公开分享）
     */
    @Override
    public List<byte[]> getSharedFileContent(Long userId, String shareCode, String fileHash) {
        // 验证分享有效性（允许公开/私密）
        ShareAccessContext accessContext = resolveShareAccess(shareCode, fileHash, null);
        validateOwnerFileForInMemoryTransfer(accessContext.ownerId(), fileHash);

        Result<FileDetailVO> filePointer = fileRemoteClient.getFile(String.valueOf(accessContext.ownerId()), fileHash);
        FileDetailVO detailVO = ResultUtils.getData(filePointer);
        if (detailVO == null) {
            throw new GeneralException(ResultEnum.FAIL, "无法获取文件详情");
        }
        String fileContent = detailVO.content();
        if (CommonUtils.isEmpty(fileContent)) {
            throw new GeneralException(ResultEnum.FAIL, "文件内容为空");
        }
        List<StoredObjectReference> references = StoredObjectReferenceCodec.parseChainContent(fileContent);
        Result<List<byte[]>> fileListResult = fileRemoteClient.getFileListByHash(
                references.stream().map(StoredObjectReference::storagePath).toList(),
                references.stream().map(StoredObjectReference::cipherHash).toList());
        List<byte[]> files = ResultUtils.getData(fileListResult);
        incrementShareAccessCount(accessContext);
        return files;
    }

    /**
     * 登录用户通过分享码获取解密信息（支持私密/公开分享）
     */
    @Override
    public FileDecryptInfoVO getSharedFileDecryptInfo(Long userId, String shareCode, String fileHash) {
        // 验证分享有效性（允许公开/私密）
        ShareAccessContext accessContext = resolveShareAccess(shareCode, fileHash, null);

        // 查询文件元数据
        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                .eq(File::getUid, accessContext.ownerId())
                .eq(File::getFileHash, fileHash);
        File file = this.getOne(wrapper);
        if (file == null) {
            throw new GeneralException(ResultEnum.FAIL, "文件不存在");
        }

        return buildShareFileDecryptInfo(file, fileHash, accessContext, userId);
    }

    /**
     * Builds decrypt metadata by resolving key envelopes for encrypted files.
     */
    private FileDecryptInfoVO buildFileDecryptInfo(File file, String fileHash, Long envelopeOwnerId) {
        return buildFileDecryptInfo(file, fileHash, envelopeOwnerId, envelopeOwnerId);
    }

    /**
     * Builds decrypt metadata for owner/admin access by auditing the requesting actor.
     */
    private FileDecryptInfoVO buildFileDecryptInfo(File file, String fileHash, Long envelopeOwnerId, Long actorId) {
        String fileParam = file.getFileParam();
        if (CommonUtils.isEmpty(fileParam)) {
            throw new GeneralException(ResultEnum.FAIL, "文件元数据不完整，缺少解密信息");
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = JsonConverter.parse(fileParam, Map.class);

            String initialKey = null;
            if (requiresInitialKey(params)) {
                Optional<String> envelopeInitialKey = fileKeyEnvelopeService.unwrapActiveOwnerInitialKey(
                        file,
                        fileHash,
                        envelopeOwnerId,
                        actorId,
                        "OWNER_DECRYPT"
                );
                initialKey = (envelopeInitialKey != null ? envelopeInitialKey : Optional.<String>empty())
                        .orElse(null);
                if (CommonUtils.isEmpty(initialKey)) {
                    throw new GeneralException(ResultEnum.FAIL, "文件解密密钥不存在");
                }
            }

            String fileName = (String) params.get("fileName");
            Long fileSize = params.get("fileSize") instanceof Number
                    ? ((Number) params.get("fileSize")).longValue() : null;
            String contentType = (String) params.get("contentType");
            Integer chunkCount = params.get("chunkCount") instanceof Number
                    ? ((Number) params.get("chunkCount")).intValue() : null;
            Long chunkSize = params.get("chunkSize") instanceof Number
                    ? ((Number) params.get("chunkSize")).longValue() : null;

            return new FileDecryptInfoVO(
                    initialKey,
                    fileName != null ? fileName : file.getFileName(),
                    fileSize,
                    contentType,
                    chunkCount,
                    fileHash,
                    chunkSize
            );

        } catch (GeneralException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析文件参数失败: fileHash={}, error={}", fileHash, e.getMessage());
            throw new GeneralException(ResultEnum.FAIL, "解析文件元数据失败");
        }
    }

    /**
     * Builds decrypt metadata for share-code access using recipient envelopes for encrypted files.
     */
    private FileDecryptInfoVO buildShareFileDecryptInfo(File file,
                                                        String fileHash,
                                                        ShareAccessContext accessContext,
                                                        Long actorId) {
        String fileParam = file.getFileParam();
        if (CommonUtils.isEmpty(fileParam)) {
            throw new GeneralException(ResultEnum.FAIL, "文件元数据不完整，缺少解密信息");
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = JsonConverter.parse(fileParam, Map.class);

            String initialKey = null;
            if (requiresInitialKey(params)) {
                Optional<String> shareEnvelopeInitialKey = fileKeyEnvelopeService.unwrapActiveShareInitialKey(
                        file,
                        fileHash,
                        accessContext.fileShare(),
                        actorId,
                        "SHARE_DECRYPT"
                );
                initialKey = (shareEnvelopeInitialKey != null ? shareEnvelopeInitialKey : Optional.<String>empty())
                        .orElse(null);
                if (CommonUtils.isEmpty(initialKey)) {
                    throw new GeneralException(ResultEnum.FAIL, "文件解密密钥不存在");
                }
            }

            String fileName = (String) params.get("fileName");
            Long fileSize = params.get("fileSize") instanceof Number
                    ? ((Number) params.get("fileSize")).longValue() : null;
            String contentType = (String) params.get("contentType");
            Integer chunkCount = params.get("chunkCount") instanceof Number
                    ? ((Number) params.get("chunkCount")).intValue() : null;
            Long chunkSize = params.get("chunkSize") instanceof Number
                    ? ((Number) params.get("chunkSize")).longValue() : null;

            return new FileDecryptInfoVO(
                    initialKey,
                    fileName != null ? fileName : file.getFileName(),
                    fileSize,
                    contentType,
                    chunkCount,
                    fileHash,
                    chunkSize
            );

        } catch (GeneralException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析分享文件参数失败: fileId={}, shareId={}, errorType={}",
                    file.getId(), accessContext.fileShare().getId(), e.getClass().getSimpleName());
            throw new GeneralException(ResultEnum.FAIL, "解析文件元数据失败");
        }
    }

    /**
     * Returns whether the file metadata describes encrypted content that needs a data key.
     */
    private boolean requiresInitialKey(Map<String, Object> fileParam) {
        Object encryptionAlgorithm = fileParam.get("encryptionAlgorithm");
        return !(encryptionAlgorithm instanceof String algorithm && "NONE".equalsIgnoreCase(algorithm.trim()));
    }

    /**
     * 验证分享访问权限并解析分享上下文
     */
    private ShareAccessContext resolveShareAccess(String shareCode, String fileHash, ShareType requiredType) {
        int expiredCount = fileShareMapper.markAsExpiredIfNecessary(shareCode);
        FileShare fileShare = fileShareMapper.selectByShareCode(shareCode);
        if (fileShare != null) {
            if (expiredCount > 0) {
                fileShare.setStatus(FileShare.STATUS_EXPIRED);
            }
            requireSupportedShareType(fileShare.getShareType(), ResultEnum.FAIL);
            if (requiredType != null && !Objects.equals(fileShare.getShareType(), requiredType.getCode())) {
                throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "此分享需要登录后才能访问");
            }

            if (Objects.equals(fileShare.getStatus(), FileShare.STATUS_CANCELLED)) {
                throw new GeneralException(ResultEnum.FAIL, "分享已被取消");
            }

            if (Objects.equals(fileShare.getStatus(), FileShare.STATUS_EXPIRED)) {
                throw new GeneralException(ResultEnum.SHARE_EXPIRED);
            }

            if (!Objects.equals(fileShare.getStatus(), FileShare.STATUS_ACTIVE)) {
                throw new GeneralException(ResultEnum.FAIL, "分享状态无效");
            }

            Date now = new Date();
            if (fileShare.getExpireTime() != null && fileShare.getExpireTime().before(now)) {
                throw new GeneralException(ResultEnum.SHARE_EXPIRED);
            }

            List<String> fileHashes = parseFileHashes(fileShare.getFileHashes());
            if (!fileHashes.contains(fileHash)) {
                throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "该文件不在此分享中");
            }

            return new ShareAccessContext(fileShare.getUserId(), fileShare);
        }

        throw new GeneralException(ResultEnum.FAIL, "分享不存在");
    }

    /**
     * 校验持久化或调用方提供的分享类型，未知值不得降级为公开分享。
     *
     * @param shareType 分享类型代码
     * @param errorType 非法类型对应的错误分类
     */
    private void requireSupportedShareType(Integer shareType, ResultEnum errorType) {
        if (!Objects.equals(shareType, ShareType.PUBLIC.getCode())
                && !Objects.equals(shareType, ShareType.PRIVATE.getCode())) {
            throw new GeneralException(errorType, "分享类型必须是 0 或 1");
        }
    }

    /**
     * 增加分享访问次数（仅数据库记录存在时）
     */
    private void incrementShareAccessCount(ShareAccessContext accessContext) {
        if (accessContext.fileShare() != null) {
            fileShareMapper.incrementAccessCount(accessContext.fileShare().getShareCode());
        }
    }

    /**
     * 将 FileShare 实体转换为 FileShareVO
     */
    private FileShareVO convertFileShareToVO(FileShare fileShare) {
        FileShareVO vo = new FileShareVO();
        vo.setSharingCode(fileShare.getShareCode());
        vo.setFileHashes(parseFileHashes(fileShare.getFileHashes()));
        vo.setAccessCount(fileShare.getAccessCount());
        vo.setExpireTime(fileShare.getExpireTime());
        vo.setCreateTime(fileShare.getCreateTime());
        vo.setShareType(fileShare.getShareType());
        vo.setShareTypeDesc(ShareType.fromCode(fileShare.getShareType()).getName());

        // 设置状态
        int status = fileShare.getStatus();
        // 检查是否已过期
        if (status == FileShare.STATUS_ACTIVE
                && fileShare.getExpireTime() != null
                && fileShare.getExpireTime().before(new Date())) {
            status = FileShare.STATUS_EXPIRED;
        }

        vo.setStatus(status);
        vo.setIsValid(status == FileShare.STATUS_ACTIVE);

        String statusDesc = switch (status) {
            case FileShare.STATUS_CANCELLED -> "已取消";
            case FileShare.STATUS_ACTIVE -> "有效";
            case FileShare.STATUS_EXPIRED -> "已过期";
            default -> "未知";
        };
        vo.setStatusDesc(statusDesc);

        return vo;
    }

    /**
     * 解析文件哈希 JSON 数组
     */
    private List<String> parseFileHashes(String fileHashesJson) {
        if (CommonUtils.isEmpty(fileHashesJson)) {
            return List.of();
        }
        try {
            String[] hashes = JsonConverter.parse(fileHashesJson, String[].class);
            return hashes != null ? Arrays.asList(hashes) : List.of();
        } catch (Exception e) {
            log.warn("解析文件哈希列表失败: {}", fileHashesJson);
            return List.of();
        }
    }

    /**
     * 分享访问上下文（包含分享拥有者与可选的数据库记录）
     */
    private record ShareAccessContext(Long ownerId, FileShare fileShare) {
    }

    @Override
    public File createNewVersion(Long userId, Long parentFileId, String fileName, long fileSize, String contentType) {
        File parentFile = validateVersionSourceFile(userId, parentFileId);

        Long versionGroupId = parentFile.getVersionGroupId() != null ? parentFile.getVersionGroupId() : parentFile.getId();
        String lockKey = "file:version:" + versionGroupId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new GeneralException(ResultEnum.VERSION_CONFLICT);
            }
            try {
                // 加锁后再次校验，防止并发下基于过期父版本创建新版本。
                File latestParentFile = validateVersionSourceFile(userId, parentFileId);
                quotaService.checkUploadQuota(latestParentFile.getTenantId(), userId, fileSize);
                File createdVersion = transactionTemplate.execute(
                        status -> doCreateNewVersion(userId, latestParentFile, versionGroupId, fileName, fileSize, contentType)
                );
                if (createdVersion == null) {
                    throw new GeneralException(ResultEnum.FAIL, "创建新版本失败");
                }
                return createdVersion;
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeneralException(ResultEnum.VERSION_CONFLICT);
        }
    }

    /**
     * 校验父版本文件是否允许创建新版本。
     * 仅允许文件所有者基于 SUCCESS 且 is_latest=1 的记录创建新版本。
     *
     * @param userId 用户ID
     * @param parentFileId 父版本文件ID
     * @return 校验通过的父版本文件
     */
    private File validateVersionSourceFile(Long userId, Long parentFileId) {
        File parentFile = this.getById(parentFileId);
        if (parentFile == null) {
            throw new GeneralException(ResultEnum.FILE_NOT_EXIST);
        }
        if (!parentFile.getUid().equals(userId)) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
        }
        if (parentFile.getStatus() != FileUploadStatus.SUCCESS.getCode()) {
            throw new GeneralException(ResultEnum.VERSION_SOURCE_INVALID);
        }
        if (parentFile.getIsLatest() != null && parentFile.getIsLatest() != 1) {
            throw new GeneralException(ResultEnum.VERSION_SOURCE_INVALID, "只能基于最新版本创建新版本");
        }
        return parentFile;
    }

    /**
     * 在事务内写入新版本记录，并将版本链中旧记录的 is_latest 置为 0。
     *
     * @param userId 用户ID
     * @param parentFile 父版本文件
     * @param versionGroupId 版本链分组ID
     * @param fileName 新版本文件名
     * @param fileSize 新版本文件大小
     * @param contentType 新版本文件类型
     * @return 新创建的版本记录
     */
    protected File doCreateNewVersion(Long userId, File parentFile, Long versionGroupId,
                                       String fileName, long fileSize, String contentType) {
        lockProofLifecycleVersionGroup(
                parentFile.getTenantId(),
                versionGroupId,
                "文件版本链不存在，无法创建新版本");
        // 清除版本链中所有文件的 isLatest
        baseMapper.clearLatestInChain(versionGroupId, parentFile.getTenantId());

        // 创建新版本
        Long newId = IdUtils.nextEntityId();
        String fileParam = JsonConverter.toJson(Map.of("fileSize", Math.max(0L, fileSize), "contentType", contentType));
        int parentVersion = parentFile.getVersion() != null ? parentFile.getVersion() : 1;
        File newVersion = new File()
                .setId(newId)
                .setUid(userId)
                .setTenantId(parentFile.getTenantId())
                .setFileName(fileName)
                .setFileParam(fileParam)
                .setStatus(FileUploadStatus.PREPARE.getCode())
                .setVersion(parentVersion + 1)
                .setParentVersionId(parentFile.getId())
                .setIsLatest(1)
                .setVersionGroupId(versionGroupId);
        this.save(newVersion);

        return newVersion;
    }
}
