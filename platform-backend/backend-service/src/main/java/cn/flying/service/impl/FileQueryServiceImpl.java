package cn.flying.service.impl;

import cn.flying.api.utils.ResultUtils;
import cn.flying.common.constant.FileKeywordMode;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.constant.ShareType;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.CommonUtils;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.JsonConverter;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.dto.File;
import cn.flying.dao.dto.FileShare;
import cn.flying.dao.entity.FriendFileShare;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.FileShareMapper;
import cn.flying.dao.vo.file.FileDecryptInfoVO;
import cn.flying.dao.vo.file.FileDownloadEncryptionVO;
import cn.flying.dao.vo.file.FileDownloadMetadataVO;
import cn.flying.dao.vo.file.FileDownloadPartVO;
import cn.flying.dao.vo.file.FileShareVO;
import cn.flying.dao.vo.file.FileVersionVO;
import cn.flying.dao.vo.file.ShareFileVO;
import cn.flying.dao.vo.file.UserFileStatsVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.platformapi.response.TransactionVO;
import cn.flying.service.FileQueryService;
import cn.flying.service.FriendFileShareService;
import cn.flying.service.encryption.FramedAeadCrypto;
import cn.flying.service.key.FileKeyEnvelopeService;
import cn.flying.service.manifest.ChunkManifestCanonicalizer;
import cn.flying.service.manifest.ChunkManifestChunk;
import cn.flying.service.manifest.ChunkManifestDraft;
import cn.flying.service.manifest.ChunkManifestEncryption;
import cn.flying.service.manifest.ChunkManifestService;
import cn.flying.service.manifest.ChunkManifestView;
import cn.flying.service.remote.FileRemoteClient;
import cn.flying.service.support.StoredObjectReference;
import cn.flying.service.support.StoredObjectReferenceCodec;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文件查询服务实现类（CQRS Query Side）
 * <p>
 * 专注于文件读操作，与 FileServiceImpl 中的写操作分离。
 * 所有方法均为只读，支持更激进的缓存和并发策略。
 * </p>
 *
 * <h3>Virtual Thread 优化</h3>
 * <p>
 * 异步方法使用 virtualThreadExecutor，适合 I/O 密集型操作：
 * <ul>
 *   <li>数据库查询自动让出底层平台线程</li>
 *   <li>Dubbo 远程调用期间不阻塞平台线程</li>
 *   <li>支持百万级并发查询</li>
 * </ul>
 * </p>
 *
 * <h3>缓存策略</h3>
 * <p>
 * 使用 Caffeine 本地缓存，缓存名称定义在 CacheConfiguration 中：
 * <ul>
 *   <li>transaction - 区块链交易信息（key 格式: transactionHash）</li>
 * </ul>
 * 匿名分享文件列表涉及实时授权、过期和删除状态，明确不使用方法级缓存。
 * </p>
 *
 * @author flyingcoding
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileQueryServiceImpl implements FileQueryService {

    private static final long MAX_IN_MEMORY_TRANSFER_BYTES = 80L * 1024 * 1024;
    private static final long DOWNLOAD_URL_TTL_SECONDS = 24L * 60L * 60L;
    private static final String ENCRYPTION_NONE = "NONE";
    private static final String FRAMED_ENCRYPTION_ALGORITHM = "FRAMED_AEAD_V2";
    private static final Pattern CANONICAL_SHA256_PATTERN = Pattern.compile("sha256:[0-9a-f]{64}");

    private final FileMapper fileMapper;
    private final AccountMapper accountMapper;
    private final FileRemoteClient fileRemoteClient;
    private final FileShareMapper fileShareMapper;
    private final FriendFileShareService friendFileShareService;
    private final ChunkManifestService chunkManifestService;
    private final FileKeyEnvelopeService fileKeyEnvelopeService;
    @Qualifier("virtualThreadExecutor")
    private final TaskExecutor virtualThreadExecutor;

    // ==================== 同步查询方法 ====================

    @Override
    public File getFileById(Long userId, Long fileId) {
        File file = fileMapper.selectById(fileId);
        if (file == null) {
            throw new GeneralException(ResultEnum.FAIL, "文件不存在");
        }
        // 权限校验：用户只能查看自己的文件，管理员可查看所有
        if (!SecurityUtils.isAdmin() && !file.getUid().equals(userId)) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "无权访问此文件");
        }

        // 收集需要查询的用户ID，避免多次独立查询
        Set<Long> userIds = new HashSet<>();
        Long originOwnerId = null;

        if (file.getOrigin() != null) {
            // 使用 selectByIdIncludeDeleted 绕过软删除，因为原始文件可能已被删除
            File originFile = fileMapper.selectByIdIncludeDeleted(file.getOrigin());
            if (originFile != null) {
                originOwnerId = originFile.getUid();
                userIds.add(originOwnerId);
            }
        }

        if (file.getSharedFromUserId() != null) {
            userIds.add(file.getSharedFromUserId());
        }

        // 一次性批量查询所有相关用户
        if (!userIds.isEmpty()) {
            Map<Long, String> userNameMap = accountMapper.selectBatchIds(userIds).stream()
                    .collect(Collectors.toMap(Account::getId, Account::getUsername, (a, b) -> a));

            if (originOwnerId != null) {
                file.setOriginOwnerName(userNameMap.get(originOwnerId));
            }
            if (file.getSharedFromUserId() != null) {
                file.setSharedFromUserName(userNameMap.get(file.getSharedFromUserId()));
            }
        }
        return file;
    }

    /**
     * 根据文件哈希获取文件详情（支持好友分享访问）。
     *
     * <p>该方法用于前端文件详情页按 hash 直接查询文件，不再依赖分页列表过滤。</p>
     *
     * @param userId   当前用户ID（用于权限校验）
     * @param fileHash 文件哈希
     * @return 文件详情（若通过好友分享访问，将填充 sharedFromUserId/sharedFromUserName）
     */
    @Override
    public File getFileByHash(Long userId, String fileHash) {
        if (!StringUtils.hasText(fileHash)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "文件哈希不能为空");
        }

        FileAccessContext accessContext = findAccessibleFile(userId, fileHash, true);
        File file = accessContext != null ? accessContext.file() : null;

        if (file == null) {
            // 安全策略：不泄露文件存在性/归属
            throw new GeneralException(ResultEnum.FILE_NOT_EXIST);
        }

        return file;
    }

    @Override
    public List<File> getUserFilesList(Long userId) {
        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<>();
        // 所有用户（包括管理员）只能查询自己的文件
        // 管理员查看所有文件请使用 FileAdminService.getAllFiles()
        wrapper.eq(File::getUid, userId)
               .eq(File::getIsLatest, 1);
        return fileMapper.selectList(wrapper);
    }

    /**
     * 按用户分页查询文件，并支持 keyword/status/time-range 组合筛选。
     *
     * @param userId    用户ID
     * @param page      分页对象
     * @param keyword   文件名/哈希关键词
     * @param keywordMode 关键词匹配模式（FUZZY/PREFIX/EXACT_HASH/AUTO）
     * @param status    文件状态
     * @param startTime 起始时间（可选）
     * @param endTime   结束时间（可选）
     */
    @Override
    public void getUserFilesPage(Long userId,
                                 Page<File> page,
                                 String keyword,
                                 String keywordMode,
                                 Integer status,
                                 Date startTime,
                                 Date endTime) {
        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<>();
        // 所有用户（包括管理员）只能查询自己的文件
        // 管理员查看所有文件请使用 FileAdminService.getAllFiles()
        wrapper.eq(File::getUid, userId)
               .eq(File::getIsLatest, 1);

        applyKeywordFilter(wrapper, keyword, keywordMode);

        // 状态过滤
        if (status != null) {
            wrapper.eq(File::getStatus, status);
        }

        // 时间范围过滤
        if (startTime != null && endTime != null && startTime.after(endTime)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "startTime 不能晚于 endTime");
        }
        if (startTime != null) {
            wrapper.ge(File::getCreateTime, startTime);
        }
        if (endTime != null) {
            wrapper.le(File::getCreateTime, endTime);
        }

        // 按创建时间倒序排列
        wrapper.orderByDesc(File::getCreateTime);

        fileMapper.selectPage(page, wrapper);
    }

    @Override
    public List<String> getFileAddress(Long userId, String fileHash) {
        // 合并验证和解析，避免重复查询
        Long blockchainUserId = validateAndResolveBlockchainUserId(userId, fileHash);
        String userIdStr = String.valueOf(blockchainUserId);
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

    /**
     * Builds authorized presigned chunk-download metadata from the active chunk manifest.
     */
    @Override
    public FileDownloadMetadataVO getDownloadMetadata(Long userId, String fileHash) {
        FileAccessContext accessContext = findAccessibleFile(userId, fileHash, true);
        if (accessContext == null) {
            throw new GeneralException(ResultEnum.FILE_NOT_EXIST);
        }
        File file = accessContext.file();
        FileDecryptInfoVO decryptInfo = buildFileDecryptInfo(accessContext, fileHash, userId);
        ChunkManifestView manifest = chunkManifestService.findActiveManifest(file.getUid(), file.getId())
                .orElseThrow(() -> new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件缺少分片 manifest"));
        if (CommonUtils.isEmpty(manifest.chunks())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件分片 manifest 为空");
        }

        Long fileSizeValue = resolveDownloadFileSize(file, decryptInfo);
        long responseChunkSize = validateDownloadManifest(
                file, fileHash, decryptInfo, manifest, fileSizeValue);
        ChunkManifestDraft downloadDraft = new ChunkManifestDraft(
                manifest.schemaId(),
                manifest.fileHash(),
                manifest.hashAlgorithm(),
                manifest.chunkSize(),
                manifest.totalSize(),
                manifest.merkleRoot(),
                manifest.encryptionAlgorithm(),
                manifest.storageBackend(),
                manifest.encryption(),
                manifest.chunks());
        String canonicalManifestJson = chunkManifestService.calculateCanonicalJson(downloadDraft);

        List<String> storagePaths = manifest.chunks().stream()
                .map(ChunkManifestChunk::storagePath)
                .toList();
        List<String> cipherHashes = manifest.chunks().stream()
                .map(ChunkManifestChunk::cipherHash)
                .toList();
        List<String> downloadUrls = ResultUtils.getData(
                fileRemoteClient.getFileUrlListByHash(storagePaths, cipherHashes)
        );
        if (downloadUrls == null || downloadUrls.size() != manifest.chunks().size()) {
            throw new GeneralException(ResultEnum.FILE_SERVICE_ERROR, "对象存储返回的下载 URL 数量不一致");
        }

        long expiresAtEpochSeconds = System.currentTimeMillis() / 1000L + DOWNLOAD_URL_TTL_SECONDS;
        List<FileDownloadPartVO> parts = buildDownloadParts(manifest, downloadUrls, expiresAtEpochSeconds);

        return new FileDownloadMetadataVO(
                IdUtils.toExternalId(file.getId()),
                fileHash,
                decryptInfo.fileName(),
                fileSizeValue,
                decryptInfo.contentType(),
                decryptInfo.initialKey(),
                manifest.schemaId(),
                manifest.manifestHash(),
                canonicalManifestJson,
                manifest.hashAlgorithm(),
                manifest.encryptionAlgorithm(),
                manifest.storageBackend(),
                responseChunkSize,
                parts.size(),
                toDownloadEncryption(manifest.encryption()),
                parts
        );
    }

    /**
     * 解析并核对数据库与 fileParam 中的文件明文大小，避免返回相互冲突的下载合同。
     */
    private Long resolveDownloadFileSize(File file, FileDecryptInfoVO decryptInfo) {
        Long persistedSize = file.getFileSize();
        Long parameterSize = decryptInfo.fileSize();
        if (persistedSize != null && parameterSize != null && !persistedSize.equals(parameterSize)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件大小与 fileParam 不一致");
        }
        Long resolved = parameterSize != null ? parameterSize : persistedSize;
        if (resolved == null || resolved <= 0) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件大小缺失或无效");
        }
        return resolved;
    }

    /**
     * 在请求预签名 URL 前校验 manifest 哈希、格式、连续索引和 v2 明密文尺寸合同。
     */
    private long validateDownloadManifest(
            File file,
            String requestedFileHash,
            FileDecryptInfoVO decryptInfo,
            ChunkManifestView manifest,
            long fileSize
    ) {
        List<ChunkManifestChunk> chunks = manifest.chunks();
        if (!Objects.equals(manifest.fileId(), file.getId())
                || !Objects.equals(manifest.fileHash(), requestedFileHash)
                || !Objects.equals(manifest.fileHash(), file.getFileHash())
                || !ChunkManifestCanonicalizer.SCHEMA_ID.equals(manifest.schemaId())
                || !ChunkManifestCanonicalizer.HASH_ALGORITHM.equals(manifest.hashAlgorithm())
                || manifest.chunkSize() <= 0
                || manifest.totalSize() <= 0
                || manifest.chunkCount() == null
                || manifest.chunkCount() != chunks.size()
                || !StringUtils.hasText(manifest.storageBackend())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "下载 manifest 顶层合同无效");
        }
        if (decryptInfo.chunkCount() != null
                && !Objects.equals(decryptInfo.chunkCount(), manifest.chunkCount())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "fileParam 分片数量与 manifest 不一致");
        }

        ChunkManifestEncryption encryption = manifest.encryption();
        boolean framedV2 = encryption != null
                && Objects.equals(encryption.formatVersion(), ChunkManifestEncryption.FORMAT_FRAMED_V2);
        boolean unencrypted = ENCRYPTION_NONE.equalsIgnoreCase(manifest.encryptionAlgorithm());
        validateEncryptionCoherence(manifest, framedV2, unencrypted);

        long aggregateLogicalSize = 0L;
        Set<String> storagePaths = new HashSet<>();
        for (int index = 0; index < chunks.size(); index++) {
            ChunkManifestChunk chunk = chunks.get(index);
            if (chunk == null
                    || chunk.index() != index
                    || chunk.size() <= 0
                    || !StringUtils.hasText(chunk.plainHash())
                    || !StringUtils.hasText(chunk.cipherHash())
                    || !StringUtils.hasText(chunk.storagePath())
                    || !storagePaths.add(chunk.storagePath())) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "下载 manifest 分片顺序或证据无效");
            }
            long logicalSize = framedV2
                    ? validateFramedChunk(manifest, chunk, index)
                    : chunk.size();
            try {
                aggregateLogicalSize = Math.addExact(aggregateLogicalSize, logicalSize);
            } catch (ArithmeticException overflow) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "下载 manifest 分片大小溢出");
            }
        }
        if (aggregateLogicalSize != manifest.totalSize()) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "下载 manifest 分片总量不一致");
        }
        if ((framedV2 || unencrypted) && manifest.totalSize() != fileSize) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "下载 manifest 明文总量与文件大小不一致");
        }

        long responseChunkSize = manifest.chunkSize();
        if (decryptInfo.chunkSize() != null) {
            if (decryptInfo.chunkSize() <= 0
                    || ((framedV2 || unencrypted) && decryptInfo.chunkSize() != manifest.chunkSize())) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "fileParam 分片大小与 manifest 不一致");
            }
            responseChunkSize = decryptInfo.chunkSize();
        }

        validateV2FileParam(file, manifest, framedV2);
        ChunkManifestDraft draft = new ChunkManifestDraft(
                manifest.schemaId(),
                manifest.fileHash(),
                manifest.hashAlgorithm(),
                manifest.chunkSize(),
                manifest.totalSize(),
                manifest.merkleRoot(),
                manifest.encryptionAlgorithm(),
                manifest.storageBackend(),
                manifest.encryption(),
                manifest.chunks());
        String calculatedManifestHash;
        try {
            calculatedManifestHash = chunkManifestService.calculateManifestHash(draft);
        } catch (GeneralException | ArithmeticException invalidManifest) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "下载 manifest canonical 合同无效");
        }
        if (!CANONICAL_SHA256_PATTERN.matcher(Objects.toString(manifest.manifestHash(), "")).matches()
                || !Objects.equals(manifest.manifestHash(), calculatedManifestHash)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "下载 manifest hash 不一致");
        }
        return responseChunkSize;
    }

    /**
     * 校验 encryptionAlgorithm 与版本化 descriptor 的组合，拒绝未知或冲突的格式声明。
     */
    private void validateEncryptionCoherence(
            ChunkManifestView manifest,
            boolean framedV2,
            boolean unencrypted
    ) {
        ChunkManifestEncryption encryption = manifest.encryption();
        if (framedV2 && !FRAMED_ENCRYPTION_ALGORITHM.equalsIgnoreCase(manifest.encryptionAlgorithm())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "v2 manifest 加密算法声明不一致");
        }
        if (FRAMED_ENCRYPTION_ALGORITHM.equalsIgnoreCase(manifest.encryptionAlgorithm()) && !framedV2) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "v2 manifest 缺少加密描述");
        }
        if (encryption == null) {
            return;
        }
        if (Objects.equals(encryption.formatVersion(), ChunkManifestEncryption.FORMAT_NONE) && !unencrypted) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "NONE descriptor 与加密算法冲突");
        }
        if (Objects.equals(encryption.formatVersion(), ChunkManifestEncryption.FORMAT_LEGACY_V1)
                && (unencrypted || framedV2)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "legacy descriptor 与加密算法冲突");
        }
        if (unencrypted && !Objects.equals(
                encryption.formatVersion(), ChunkManifestEncryption.FORMAT_NONE)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "NONE manifest formatVersion 无效");
        }
    }

    /**
     * 校验单个 framed v2 分片的明文大小、frame 数量和密文字节公式。
     */
    private long validateFramedChunk(ChunkManifestView manifest, ChunkManifestChunk chunk, int index) {
        ChunkManifestEncryption encryption = manifest.encryption();
        Long plainSize = chunk.plainSize();
        Integer frameCount = chunk.frameCount();
        if (plainSize == null || plainSize <= 0 || frameCount == null || frameCount <= 0
                || encryption == null
                || encryption.framePlainSize() == null
                || encryption.tagSize() == null
                || !CANONICAL_SHA256_PATTERN.matcher(chunk.plainHash()).matches()
                || !CANONICAL_SHA256_PATTERN.matcher(chunk.cipherHash()).matches()
                || plainSize > manifest.chunkSize()
                || (index < manifest.chunks().size() - 1 && plainSize != manifest.chunkSize())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "v2 manifest 分片明文或 hash 合同无效");
        }
        long expectedFrameCount = (plainSize + encryption.framePlainSize() - 1L)
                / encryption.framePlainSize();
        long expectedCipherSize;
        try {
            expectedCipherSize = Math.addExact(
                    Math.addExact(FramedAeadCrypto.CHUNK_HEADER_SIZE, plainSize),
                    Math.multiplyExact(expectedFrameCount,
                            FramedAeadCrypto.FRAME_HEADER_SIZE + (long) encryption.tagSize()));
        } catch (ArithmeticException overflow) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "v2 manifest 密文大小溢出");
        }
        if (frameCount.longValue() != expectedFrameCount || chunk.size() != expectedCipherSize) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "v2 manifest frame 或密文大小不一致");
        }
        return plainSize;
    }

    /**
     * 对 v2 文件核对持久化 fileParam 与 active manifest descriptor，防止 key/nonce 合同漂移。
     */
    private void validateV2FileParam(File file, ChunkManifestView manifest, boolean framedV2) {
        if (!framedV2) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = JsonConverter.parse(file.getFileParam(), Map.class);
            ChunkManifestEncryption encryption = manifest.encryption();
            if (params == null
                    || !FRAMED_ENCRYPTION_ALGORITHM.equalsIgnoreCase(Objects.toString(
                    params.get("encryptionAlgorithm"), ""))
                    || !Objects.equals(params.get("algorithmSuite"), encryption.algorithmSuite())
                    || !Objects.equals(params.get("fileNonce"), encryption.fileNonce())
                    || !Objects.equals(params.get("keyDerivation"), encryption.keyDerivation())
                    || !Objects.equals(params.get("nonceDerivation"), encryption.nonceDerivation())
                    || !Objects.equals(params.get("aadSchema"), encryption.aadSchema())
                    || !numericEquals(params.get("formatVersion"), encryption.formatVersion())
                    || !numericEquals(params.get("framePlainSize"), encryption.framePlainSize())
                    || !numericEquals(params.get("tagSize"), encryption.tagSize())) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR,
                        "v2 fileParam 与 manifest 加密描述不一致");
            }
        } catch (GeneralException e) {
            throw e;
        } catch (RuntimeException parseError) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "v2 fileParam 格式无效");
        }
    }

    /**
     * 以 long 语义比较 JSON 数值和 descriptor 整数，兼容 Jackson 的 Integer/Long 表示。
     */
    private boolean numericEquals(Object actual, Number expected) {
        return actual instanceof Number actualNumber
                && expected != null
                && actualNumber.longValue() == expected.longValue();
    }

    /**
     * 将内部 manifest descriptor 映射为对外下载合同。
     */
    private FileDownloadEncryptionVO toDownloadEncryption(ChunkManifestEncryption encryption) {
        if (encryption == null) {
            return null;
        }
        return new FileDownloadEncryptionVO(
                encryption.formatVersion(),
                encryption.algorithmSuite(),
                encryption.fileNonce(),
                encryption.framePlainSize(),
                encryption.keyDerivation(),
                encryption.nonceDerivation(),
                encryption.aadSchema(),
                encryption.tagSize());
    }

    /**
     * Combines ordered manifest chunks with storage presigned URLs.
     */
    private List<FileDownloadPartVO> buildDownloadParts(ChunkManifestView manifest,
                                                        List<String> downloadUrls,
                                                        long expiresAtEpochSeconds) {
        List<ChunkManifestChunk> chunks = manifest.chunks();
        List<FileDownloadPartVO> parts = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            ChunkManifestChunk chunk = chunks.get(i);
            String downloadUrl = downloadUrls.get(i);
            if (!StringUtils.hasText(downloadUrl)) {
                throw new GeneralException(ResultEnum.FILE_SERVICE_ERROR, "对象存储返回空下载 URL");
            }
            parts.add(new FileDownloadPartVO(
                    chunk.index(),
                    chunk.size(),
                    downloadUrl,
                    expiresAtEpochSeconds,
                    chunk.storagePath(),
                    chunk.storageBackend(),
                    chunk.etag(),
                    chunk.plainHash(),
                    chunk.cipherHash(),
                    chunk.checksumAlgorithm(),
                    chunk.plainSize(),
                    chunk.frameCount()
            ));
        }
        return parts;
    }

    @Override
    @Cacheable(cacheNames = "transaction", key = "#userId + ':' + #transactionHash", unless = "#result == null")
    public TransactionVO getTransactionByHash(Long userId, String transactionHash) {
        validateTransactionAccess(userId, transactionHash);
        Result<TransactionVO> result = fileRemoteClient.getTransactionByHash(transactionHash);
        return ResultUtils.getData(result);
    }

    /**
     * 校验交易哈希必须绑定到当前用户可访问的本地文件记录。
     */
    private void validateTransactionAccess(Long userId, String transactionHash) {
        if (!StringUtils.hasText(transactionHash)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "交易哈希不能为空");
        }
        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                .eq(File::getTransactionHash, transactionHash);
        if (!SecurityUtils.isAdmin()) {
            wrapper.eq(File::getUid, userId);
        }
        Long count = fileMapper.selectCount(wrapper);
        if (count == null || count == 0) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "无权访问此交易");
        }
    }

    @Override
    public List<byte[]> getFile(Long userId, String fileHash) {
        // 合并验证和解析，避免重复查询
        Long blockchainUserId = validateAndResolveBlockchainUserId(userId, fileHash);
        String userIdStr = String.valueOf(blockchainUserId);
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
     * 根据分享码获取分享文件列表，并按过期时间判定取消/过期状态
     */
    @Override
    public List<ShareFileVO> getShareFile(String sharingCode) {
        if (!StringUtils.hasText(sharingCode)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "分享码不能为空");
        }
        Long shareTenantId = fileShareMapper.selectTenantIdByShareCodeGlobally(sharingCode);
        if (shareTenantId == null) {
            throw new GeneralException(ResultEnum.SHARE_NOT_FOUND);
        }
        return TenantContext.callWithTenantIsolation(shareTenantId, () -> {
            FileShare share = requirePublicActiveShareInCurrentTenant(sharingCode);
            List<String> fileHashList = parseFileHashes(share.getFileHashes());
            if (CommonUtils.isEmpty(fileHashList)) {
                return List.of();
            }

            List<File> files = listShareFilesInShareTenant(share, fileHashList);
            return files.stream().map(ShareFileVO::fromFile).toList();
        });
    }

    /**
     * 校验公开分享元数据访问，并返回处于有效状态的本地分享记录。
     *
     * @param sharingCode 分享码
     * @return 公开且有效的分享记录
     */
    private FileShare requirePublicActiveShareInCurrentTenant(String sharingCode) {
        int expiredCount = fileShareMapper.markAsExpiredIfNecessary(sharingCode);
        FileShare share = fileShareMapper.selectByShareCode(sharingCode);
        if (share != null && expiredCount > 0) {
            share.setStatus(FileShare.STATUS_EXPIRED);
        }

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
        if (!Objects.equals(share.getShareType(), ShareType.PUBLIC.getCode())) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "此分享需要登录后才能访问");
        }
        return share;
    }

    /**
     * 在分享所属租户内读取文件和所有者展示名，避免公开分享列表绕过所有租户过滤。
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
            List<File> files = fileMapper.selectList(wrapper);

            if (CommonUtils.isNotEmpty(files)) {
                Account owner = accountMapper.selectById(share.getUserId());
                String ownerName = (owner != null) ? owner.getUsername() : null;
                files.forEach(file -> file.setOwnerName(ownerName));
            }
            return files;
        });
    }

    @Override
    public FileDecryptInfoVO getFileDecryptInfo(Long userId, String fileHash) {
        FileAccessContext accessContext = findAccessibleFile(userId, fileHash, false);
        File file = accessContext != null ? accessContext.file() : null;

        if (file == null) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "文件不存在或无权限访问");
        }

        return buildFileDecryptInfo(accessContext, fileHash, userId);
    }

    /**
     * Builds decrypt metadata from the stored file parameter JSON.
     */
    private FileDecryptInfoVO buildFileDecryptInfo(FileAccessContext accessContext, String fileHash, Long actorId) {
        File file = accessContext.file();
        String fileParam = file.getFileParam();
        if (CommonUtils.isEmpty(fileParam)) {
            throw new GeneralException(ResultEnum.FAIL, "文件元数据不完整，缺少解密信息");
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = JsonConverter.parse(fileParam, Map.class);

            String initialKey = null;
            if (requiresInitialKey(params)) {
                Optional<String> envelopeInitialKey = resolveInitialKey(accessContext, fileHash, actorId);
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
     * Resolves the decrypt key according to owner/admin or friend-share access mode.
     */
    private Optional<String> resolveInitialKey(FileAccessContext accessContext,
                                               String fileHash,
                                               Long actorId) {
        File file = accessContext.file();
        FriendFileShare friendShare = accessContext.friendShare();
        if (friendShare != null) {
            Optional<String> friendShareKey = fileKeyEnvelopeService.unwrapActiveFriendShareInitialKey(
                    file,
                    fileHash,
                    friendShare,
                    actorId,
                    "FRIEND_SHARE_DECRYPT"
            );
            return friendShareKey != null ? friendShareKey : Optional.empty();
        }

        Optional<String> ownerEnvelope = fileKeyEnvelopeService.unwrapActiveOwnerInitialKey(
                file,
                fileHash,
                file.getUid(),
                actorId,
                "OWNER_DECRYPT"
        );
        return ownerEnvelope != null ? ownerEnvelope : Optional.empty();
    }

    /**
     * Returns whether the file metadata describes encrypted content that needs a data key.
     */
    private boolean requiresInitialKey(Map<String, Object> fileParam) {
        Object encryptionAlgorithm = fileParam.get("encryptionAlgorithm");
        return !(encryptionAlgorithm instanceof String algorithm && "NONE".equalsIgnoreCase(algorithm.trim()));
    }

    @Override
    public IPage<FileShareVO> getUserShares(Long userId, Page<?> page) {
        Long tenantId = TenantContext.getTenantId();

        LambdaQueryWrapper<FileShare> wrapper = new LambdaQueryWrapper<FileShare>()
                .eq(tenantId != null, FileShare::getTenantId, tenantId)
                .eq(FileShare::getUserId, userId)
                .orderByDesc(FileShare::getCreateTime);

        Page<FileShare> sharePage = new Page<>(page.getCurrent(), page.getSize());
        fileShareMapper.selectPage(sharePage, wrapper);

        // 批量收集所有 fileHash，避免 N+1 查询
        Set<String> allFileHashes = new HashSet<>();
        for (FileShare share : sharePage.getRecords()) {
            List<String> fileHashes = parseFileHashes(share.getFileHashes());
            if (CommonUtils.isNotEmpty(fileHashes)) {
                allFileHashes.addAll(fileHashes);
            }
        }

        // 一次性批量查询所有文件
        Map<String, String> hashToFileName = Map.of();
        if (!allFileHashes.isEmpty()) {
            LambdaQueryWrapper<File> fileWrapper = new LambdaQueryWrapper<File>()
                    .eq(File::getUid, userId)
                    .in(File::getFileHash, allFileHashes);
            List<File> files = fileMapper.selectList(fileWrapper);
            hashToFileName = files.stream()
                    .collect(Collectors.toMap(File::getFileHash, File::getFileName, (a, b) -> a));
        }

        // 构建结果
        List<FileShareVO> shareList = new ArrayList<>();
        for (FileShare share : sharePage.getRecords()) {
            FileShareVO vo = convertFileShareToVO(share);
            List<String> fileHashes = parseFileHashes(share.getFileHashes());
            if (CommonUtils.isNotEmpty(fileHashes)) {
                Map<String, String> finalHashToFileName = hashToFileName;
                List<String> fileNames = fileHashes.stream()
                        .map(hash -> finalHashToFileName.getOrDefault(hash, "未知文件"))
                        .toList();
                vo.setFileNames(fileNames);
            } else {
                vo.setFileNames(List.of());
            }
            shareList.add(vo);
        }

        Page<FileShareVO> result = new Page<>(page.getCurrent(), page.getSize());
        result.setRecords(shareList);
        result.setTotal(sharePage.getTotal());
        return result;
    }

    /**
     * 统计用户文件数量、存储用量、分享数量与今日上传数。
     */
    @Override
    public UserFileStatsVO getUserFileStats(Long userId) {
        Long tenantId = TenantContext.getTenantId();

        // 查询文件总数
        Long totalFiles = fileMapper.countByUserId(userId, tenantId);

        // 查询存储用量：使用数据库聚合（避免加载全部文件到内存）
        Long totalStorage = fileMapper.sumStorageByUserId(userId, tenantId);

        // 查询分享数量
        LambdaQueryWrapper<FileShare> shareWrapper = new LambdaQueryWrapper<FileShare>()
                .eq(tenantId != null, FileShare::getTenantId, tenantId)
                .eq(FileShare::getUserId, userId)
                .eq(FileShare::getStatus, FileShare.STATUS_ACTIVE);
        Long sharedFiles = fileShareMapper.selectCount(shareWrapper);

        // 查询今日上传数（今日 00:00:00 开始）
        Date todayStart = Date.from(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant());
        Long todayUploads = fileMapper.countTodayUploadsByUserId(userId, tenantId, todayStart);

        return new UserFileStatsVO(
                totalFiles != null ? totalFiles : 0L,
                totalStorage != null ? totalStorage : 0L,
                sharedFiles != null ? sharedFiles : 0L,
                todayUploads != null ? todayUploads : 0L
        );
    }

    @Override
    public List<FileVersionVO> getFileVersionHistory(Long userId, Long fileId) {
        File file = fileMapper.selectById(fileId);
        if (file == null) {
            throw new GeneralException(ResultEnum.FILE_NOT_EXIST);
        }

        // 权限校验：owner 或 admin
        if (!SecurityUtils.isAdmin() && !file.getUid().equals(userId)) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
        }

        Long versionGroupId = file.getVersionGroupId();
        if (versionGroupId == null) {
            // 遗留文件，无版本链，返回单条
            return List.of(toFileVersionVO(file));
        }

        List<File> chain = fileMapper.selectVersionChain(versionGroupId, file.getTenantId());
        return chain.stream().map(this::toFileVersionVO).toList();
    }

    private FileVersionVO toFileVersionVO(File file) {
        return new FileVersionVO(
                IdUtils.toExternalId(file.getId()),
                file.getVersion() != null ? file.getVersion() : 1,
                file.getFileName(),
                file.getFileHash(),
                file.getFileSize(),
                file.getContentType(),
                file.getIsLatest() != null ? file.getIsLatest() : 1,
                file.getStatus(),
                file.getCreateTime()
        );
    }

    // ==================== 异步查询方法（Virtual Thread）====================

    @Override
    public CompletableFuture<List<File>> getUserFilesListAsync(Long userId) {
        return CompletableFuture.supplyAsync(
                () -> getUserFilesList(userId),
                virtualThreadExecutor
        );
    }

    @Override
    public CompletableFuture<List<String>> getFileAddressAsync(Long userId, String fileHash) {
        return CompletableFuture.supplyAsync(
                () -> getFileAddress(userId, fileHash),
                virtualThreadExecutor
        );
    }

    @Override
    public CompletableFuture<FileDecryptInfoVO> getFileDecryptInfoAsync(Long userId, String fileHash) {
        return CompletableFuture.supplyAsync(
                () -> getFileDecryptInfo(userId, fileHash),
                virtualThreadExecutor
        );
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 根据关键词匹配模式构建查询条件。
     *
     * @param wrapper 查询构造器
     * @param keyword 原始关键词
     * @param keywordMode 关键词匹配模式
     */
    private void applyKeywordFilter(LambdaQueryWrapper<File> wrapper, String keyword, String keywordMode) {
        if (!StringUtils.hasText(keyword)) {
            return;
        }
        String normalizedKeyword = keyword.trim();
        if (normalizedKeyword.isEmpty()) {
            return;
        }

        FileKeywordMode effectiveMode = FileKeywordMode.parseOrDefault(keywordMode)
                .resolveEffectiveMode(normalizedKeyword);
        switch (effectiveMode) {
            case FUZZY -> wrapper.and(w -> w
                    .like(File::getFileName, normalizedKeyword)
                    .or()
                    .like(File::getFileHash, normalizedKeyword));
            case PREFIX -> wrapper.and(w -> w
                    .likeRight(File::getFileName, normalizedKeyword)
                    .or()
                    .eq(File::getFileHash, normalizedKeyword));
            case EXACT_HASH -> wrapper.eq(File::getFileHash, normalizedKeyword);
            default -> throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "未知 keywordMode");
        }
    }

    /**
     * 合并验证与解析：校验文件所有权并解析区块链查询用的userId
     * <p>
     * 对于保存的分享文件使用原始上传者ID，因为区块链上文件是以原始上传者身份存储的。
     * 使用 selectByIdIncludeDeleted 绕过软删除，因为原始文件可能已被删除。
     * </p>
     * <p>
     * 权限检查顺序：
     * 1. 管理员可以访问所有文件
     * 2. 用户可以访问自己的文件
     * 3. 用户可以通过好友分享访问他人的文件
     * </p>
     *
     * @param userId   当前用户ID
     * @param fileHash 文件哈希
     * @return 用于区块链查询的userId
     * @throws GeneralException 如果用户无权访问该文件
     */
    private Long validateAndResolveBlockchainUserId(Long userId, String fileHash) {
        // 管理员可以访问所有文件
        if (SecurityUtils.isAdmin()) {
            LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                    .eq(File::getFileHash, fileHash)
                    .last("LIMIT 1");
            File file = fileMapper.selectOne(wrapper);
            if (file == null) {
                throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "文件不存在或无权访问");
            }
            validateInMemoryTransferLimit(file);
            return resolveBlockchainUserId(file, file.getUid());
        }

        // 首先检查用户自己的文件
        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                .eq(File::getFileHash, fileHash)
                .eq(File::getUid, userId);
        File file = fileMapper.selectOne(wrapper);

        if (file != null) {
            validateInMemoryTransferLimit(file);
            return resolveBlockchainUserId(file, userId);
        }

        // 检查好友分享权限
        FriendFileShare friendShare = friendFileShareService.getActiveShareForFile(userId, fileHash);
        if (friendShare != null) {
            // 用户通过好友分享有权访问，使用分享者的文件
            Long sharerId = friendShare.getSharerId();
            LambdaQueryWrapper<File> sharerWrapper = new LambdaQueryWrapper<File>()
                    .eq(File::getFileHash, fileHash)
                    .eq(File::getUid, sharerId);
            File sharerFile = fileMapper.selectOne(sharerWrapper);
            if (sharerFile != null) {
                validateInMemoryTransferLimit(sharerFile);
                return resolveBlockchainUserId(sharerFile, sharerId);
            }
        }

        throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "文件不存在或无权访问");
    }

    /**
     * Resolves an accessible file and carries friend-share recipient context when applicable.
     */
    private FileAccessContext findAccessibleFile(Long userId, String fileHash, boolean fillSharedFromUser) {
        if (!StringUtils.hasText(fileHash)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "文件哈希不能为空");
        }

        if (SecurityUtils.isAdmin()) {
            LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                    .eq(File::getFileHash, fileHash)
                    .last("LIMIT 1");
            File file = fileMapper.selectOne(wrapper);
            return file != null ? new FileAccessContext(file, null) : null;
        }

        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                .eq(File::getFileHash, fileHash)
                .eq(File::getUid, userId);
        File file = fileMapper.selectOne(wrapper);
        if (file != null) {
            return new FileAccessContext(file, null);
        }

        FriendFileShare friendShare = friendFileShareService.getActiveShareForFile(userId, fileHash);
        if (friendShare == null) {
            return null;
        }

        LambdaQueryWrapper<File> sharerWrapper = new LambdaQueryWrapper<File>()
                .eq(File::getFileHash, fileHash)
                .eq(File::getUid, friendShare.getSharerId());
        File sharerFile = fileMapper.selectOne(sharerWrapper);
        if (sharerFile == null) {
            return null;
        }
        if (fillSharedFromUser) {
            fillFriendShareSource(sharerFile, friendShare.getSharerId());
        }
        return new FileAccessContext(sharerFile, friendShare);
    }

    /**
     * Adds sharer display metadata to a file resolved through friend-share access.
     */
    private void fillFriendShareSource(File file, Long sharerId) {
        Account sharer = accountMapper.selectById(sharerId);
        file.setSharedFromUserId(sharerId);
        file.setSharedFromUserName(sharer != null ? sharer.getUsername() : null);
    }

    /**
     * Carries both the resolved file row and its friend-share recipient context.
     */
    private record FileAccessContext(File file, FriendFileShare friendShare) {
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

    /**
     * 解析区块链查询用的userId
     */
    private Long resolveBlockchainUserId(File file, Long defaultUserId) {
        if (file.getOrigin() != null) {
            File originFile = fileMapper.selectByIdIncludeDeleted(file.getOrigin());
            if (originFile != null) {
                return originFile.getUid();
            }
        }
        return defaultUserId;
    }

    /**
     * 将 FileShare 实体转换为 FileShareVO
     */
    private FileShareVO convertFileShareToVO(FileShare fileShare) {
        FileShareVO vo = new FileShareVO();
        vo.setSharingCode(fileShare.getShareCode());
        vo.setFileHashes(parseFileHashes(fileShare.getFileHashes()));
        vo.setAccessCount(fileShare.getAccessCount() != null ? fileShare.getAccessCount() : 0);
        vo.setExpireTime(fileShare.getExpireTime());
        vo.setCreateTime(fileShare.getCreateTime());

        ShareType shareType = ShareType.fromCode(fileShare.getShareType());
        vo.setShareType(shareType.getCode());
        vo.setShareTypeDesc(shareType.getName());

        int status = fileShare.getStatus() != null ? fileShare.getStatus() : FileShare.STATUS_ACTIVE;
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
}
