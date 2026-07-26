package cn.flying.storage.service;

import cn.flying.storage.config.NodeConfig;
import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.FaultDomainManager;
import cn.flying.storage.core.S3ClientManager;
import cn.flying.storage.core.S3Monitor;
import cn.flying.storage.tenant.TenantContextUtil;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.flying.platformapi.external.DistributedStorageService;
import cn.flying.platformapi.request.AbortDirectMultipartUploadRequest;
import cn.flying.platformapi.request.CompleteDirectMultipartUploadRequest;
import cn.flying.platformapi.request.CreateDirectMultipartUploadRequest;
import cn.flying.platformapi.request.DirectMultipartCompletedPart;
import cn.flying.platformapi.request.DirectMultipartUploadPartRequest;
import cn.flying.platformapi.response.CompleteDirectMultipartUploadResponse;
import cn.flying.platformapi.response.CreateDirectMultipartUploadResponse;
import cn.flying.platformapi.response.DirectMultipartCompletedPartVO;
import cn.flying.platformapi.response.DirectMultipartUploadPartUrl;
import cn.flying.platformapi.response.StorageCapacityVO;
import cn.flying.platformapi.response.StorageDomainCapacityVO;
import cn.flying.platformapi.response.StorageNodeCapacityVO;
import cn.flying.platformapi.response.StorageObjectHeadVO;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.Resource;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.springframework.util.CollectionUtils;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * 分布式存储实现类（S3 兼容）v3.1.0
 * 基于故障域机制实现 50% 节点容错和负载均衡读取。
 *
 * <p>核心特性：
 * <ul>
 *   <li>双故障域副本策略（A + B 域各一份）</li>
 *   <li>一致性哈希实现均匀分布</li>
 *   <li>负载感知的读取节点选择</li>
 *   <li>写入仲裁 (Write Quorum) 保证数据持久性</li>
 *   <li>降级写入支持及域恢复后自动同步</li>
 *   <li>Nacos 动态配置刷新</li>
 * </ul>
 */
@DubboService(version = DistributedStorageService.VERSION)
public class DistributedStorageServiceImpl implements DistributedStorageService {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(DistributedStorageServiceImpl.class);
    @Resource
    private S3ClientManager clientManager;

    @Resource
    private S3Monitor s3Monitor;

    @Resource
    private StorageProperties storageProperties;

    @Resource
    private ConsistencyRepairService consistencyRepairService;

    @Resource
    private FaultDomainManager faultDomainManager;

    @Resource
    private RebalanceService rebalanceService;

    @Resource
    private DegradedWriteTracker degradedWriteTracker;

    @Resource
    private DirectUploadPromotionService directUploadPromotionService;

    @Resource
    private DirectUploadStagingTracker directUploadStagingTracker;

    //预签名链接有效期
    private final static Integer EXPIRY_HOURS = 24;

    // 最大允许直接加载到内存的文件大小（100MB）
    private static final long MAX_IN_MEMORY_FILE_SIZE = 100 * 1024 * 1024L;

    // 文件操作超时时间（秒）
    private static final int FILE_OPERATION_TIMEOUT_SECONDS = 300;

    // 单个直传会话的分片数量上限，限制 RPC 反序列化后的排序和编排开销。
    private static final int MAX_DIRECT_UPLOAD_PARTS = 10_000;

    // manifest 持久化列上限，同时用于约束条件请求中的 ETag。
    private static final int MAX_DIRECT_UPLOAD_ETAG_LENGTH = 255;

    // 分块读取缓冲区大小（8KB）
    private static final int BUFFER_SIZE = 8192;

    private static final String METADATA_FILE_HASH = "file-hash";
    private static final String METADATA_TENANT_ID = "tenant-id";
    private static final String CHECKSUM_ALGORITHM_SHA256 = "SHA-256";
    private static final String HASH_PREFIX_SHA256 = "sha256:";
    private static final String STAGING_PREFIX = "staging/direct-upload";

    // 修复检查并发限制信号量
    private static final Semaphore REPAIR_CHECK_SEMAPHORE = new Semaphore(10);

    // 专用 I/O 执行器，用于文件上传操作（避免阻塞 ForkJoinPool.commonPool），由 Spring 管理生命周期
    @Resource(name = "storageUploadExecutor")
    private ExecutorService uploadExecutor;

    // 缓存 Bucket 是否存在，减少重复检查开销（带TTL自动过期）
    private final Cache<String, Boolean> bucketExistenceCache = Caffeine.newBuilder()
        .expireAfterWrite(30, TimeUnit.MINUTES)
        .maximumSize(256)
        .build();

    private enum HeadLookupStatus {
        FOUND,
        MISSING,
        UNAVAILABLE
    }

    private record HeadLookupResult(HeadLookupStatus status, StorageObjectHeadVO head) {}


    @Override
    public Result<List<byte[]>> getFileListByHash(List<String> filePathList, List<String> fileHashList) {
        if (CollectionUtils.isEmpty(filePathList) || CollectionUtils.isEmpty(fileHashList)) {
            log.warn("获取文件列表时传入Hash列表为空");
            return Result.success(null);
        }
        if (filePathList.size() != fileHashList.size()) {
            log.error("filePathList 和 fileHashList 必须具有相同的大小。");
            return Result.error(ResultEnum.PARAM_IS_INVALID, null);
        }

        List<byte[]> result = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < filePathList.size(); i++) {
            String filePath = filePathList.get(i);
            String fileHash = fileHashList.get(i);
            try {
                Optional<byte[]> fileOpt = getFileByHashInternal(filePath, fileHash);
                fileOpt.ifPresent(result::add);
                if (fileOpt.isEmpty()) {
                    // 如果内部方法返回 empty，表示尝试过但失败了，但没有抛出异常（按需调整）
                    log.warn("未找到路径:[{}],哈希[{}]的文件", filePath, fileHash);
                    // 可以选择在这里添加错误标记或继续
                }
            } catch (RuntimeException e) {
                // 捕获特定异常，记录错误信息
                log.error("无法检索路径:[{}],哈希[{}]的文件,原因: {}", filePath, fileHash, e.getMessage());
                errors.add(filePath + ": " + e.getMessage());
            } catch (Exception e) {
                // 捕获其他意外异常
                log.error("获取路径:[{}],哈希[{}]的文件时出现意外错误", filePath, fileHash, e);
                errors.add(filePath + ": Unexpected error - " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            //在部分成功时抛出异常并记录日志
            log.warn("getFileListByHash获取文件时仅部分成功，出现 {} 错误：{}", errors.size(), errors);
            //返回失败消息
            return Result.error(ResultEnum.FILE_SERVICE_ERROR, result);
        }
        return Result.success(result);
    }

    @Override
    public Result<List<String>> getFileUrlListByHash(List<String> filePathList, List<String> fileHashList) {
        if (CollectionUtils.isEmpty(filePathList) || CollectionUtils.isEmpty(fileHashList)) {
            log.warn("获取文件列表时传入Hash列表为空");
            return Result.error(ResultEnum.PARAM_IS_INVALID, null);
        }
        if (filePathList.size() != fileHashList.size()) {
            log.error("filePathList 和 fileHashList 必须具有相同的大小。");
            return Result.error(ResultEnum.PARAM_IS_INVALID, null);
        }

        List<String> result = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < filePathList.size(); i++) {
            String filePath = filePathList.get(i);
            String fileHash = fileHashList.get(i);
            try {
                Optional<String> urlOpt = getPresignedUrlInternal(filePath, fileHash);
                urlOpt.ifPresent(result::add);
                if (urlOpt.isEmpty()) {
                    log.warn("未找到路径:[{}],哈希[{}]的文件", filePath, fileHash);
                    errors.add(filePath + ": URL 生成失败");
                }
            } catch (Exception e) {
                log.error("获取路径:[{}],哈希[{}]的文件时出现意外错误", filePath, fileHash, e);
                errors.add(filePath + ": Unexpected error - " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            log.warn("getFileUrlListByHash获取文件时仅部分成功，出现 {} 错误：{}", errors.size(), errors);
            //返回失败消息
            return Result.error(ResultEnum.FILE_SERVICE_ERROR, result);
        }
        return Result.success(result);
    }

    @Override
    public Result<StorageObjectHeadVO> headObject(String filePath, String fileHash) {
        if (filePath == null || filePath.isBlank() || fileHash == null || fileHash.isBlank()) {
            log.warn("headObject参数无效: hasPath={}, hasHash={}",
                    filePath != null && !filePath.isBlank(), fileHash != null && !fileHash.isBlank());
            return Result.error(ResultEnum.PARAM_IS_INVALID, null);
        }

        TenantContextUtil.ParsedChunkPath parsedPath = TenantContextUtil.parseChunkPath(filePath);
        if (parsedPath == null) {
            log.error("headObject收到无效的分片路径格式");
            return Result.error(ResultEnum.PARAM_IS_INVALID, null);
        }

        if (!fileHash.equals(parsedPath.objectName())) {
            log.error("headObject请求的哈希与路径对象身份不匹配");
            return Result.error(ResultEnum.PARAM_IS_INVALID, null);
        }

        List<String> candidateNodes = getReadCandidateNodes(fileHash, parsedPath);
        if (candidateNodes.isEmpty()) {
            log.error("headObject无法找到候选存储节点");
            return Result.error(ResultEnum.FILE_SERVICE_ERROR,
                    StorageObjectHeadVO.missing(filePath, fileHash, parsedPath.tenantId()));
        }

        String objectPath = parsedPath.objectPath();
        boolean unavailableObserved = false;
        String primaryNode = faultDomainManager.selectBestNodeForRead(candidateNodes);
        if (primaryNode != null) {
            HeadLookupResult lookup = tryHeadObjectFromNode(
                    primaryNode, objectPath, filePath, fileHash, parsedPath.tenantId());
            if (lookup.status() == HeadLookupStatus.FOUND) {
                return Result.success(lookup.head());
            }
            unavailableObserved = lookup.status() == HeadLookupStatus.UNAVAILABLE;
            log.warn("无法从主节点 '{}' 获取对象元数据", primaryNode);
        }

        for (String node : candidateNodes) {
            if (node.equals(primaryNode)) {
                continue;
            }
            HeadLookupResult lookup = tryHeadObjectFromNode(
                    node, objectPath, filePath, fileHash, parsedPath.tenantId());
            if (lookup.status() == HeadLookupStatus.FOUND) {
                return Result.success(lookup.head());
            }
            if (lookup.status() == HeadLookupStatus.UNAVAILABLE) {
                unavailableObserved = true;
            }
        }

        if (unavailableObserved) {
            log.warn("{} 个候选节点无法完整确认对象元数据", candidateNodes.size());
            return Result.error(ResultEnum.FILE_SERVICE_ERROR,
                    StorageObjectHeadVO.missing(filePath, fileHash, parsedPath.tenantId()));
        }

        log.warn("无法从 {} 个候选节点获取对象元数据", candidateNodes.size());
        return Result.success(StorageObjectHeadVO.missing(filePath, fileHash, parsedPath.tenantId()));
    }

    /**
     * Exposes only the bounded degraded-write count required by reference-aware cleanup.
     */
    @Override
    public Result<Long> getDegradedWriteCount() {
        return Result.success(degradedWriteTracker.getPendingCount());
    }

    @Override
    public Result<CreateDirectMultipartUploadResponse> createDirectMultipartUpload(
            CreateDirectMultipartUploadRequest request) {
        if (!isValidDirectUploadCreateRequest(request)) {
            return Result.error(ResultEnum.PARAM_IS_INVALID, null);
        }

        Long tenantId = requireDirectUploadTenantId();
        if (tenantId == null) {
            return Result.error(ResultEnum.PARAM_IS_INVALID, null);
        }
        List<DirectMultipartUploadPartUrl> urls = new ArrayList<>(request.parts().size());
        List<DirectMultipartUploadPartRequest> orderedParts = request.parts().stream()
                .sorted(Comparator.comparingInt(DirectMultipartUploadPartRequest::partIndex))
                .toList();
        for (DirectMultipartUploadPartRequest part : orderedParts) {
            try {
                urls.add(createPresignedPartUrl(request, part, tenantId));
            } catch (Exception e) {
                log.error("创建直传分片预签名 URL 失败: sessionId={}, partIndex={}",
                        request.sessionId(), part != null ? part.partIndex() : null, e);
                return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
            }
        }
        return Result.success(new CreateDirectMultipartUploadResponse(request.sessionId(), urls));
    }

    @Override
    public Result<CompleteDirectMultipartUploadResponse> completeDirectMultipartUpload(
            CompleteDirectMultipartUploadRequest request) {
        if (request == null || !isValidDirectUploadSessionId(request.sessionId())
                || CollectionUtils.isEmpty(request.parts())
                || request.parts().size() > MAX_DIRECT_UPLOAD_PARTS) {
            return Result.error(ResultEnum.PARAM_IS_INVALID, null);
        }

        if (request.parts().stream().anyMatch(Objects::isNull)) {
            return Result.error(ResultEnum.PARAM_IS_INVALID, null);
        }
        List<DirectMultipartCompletedPart> orderedParts = request.parts().stream()
                .sorted(Comparator.comparingInt(DirectMultipartCompletedPart::partIndex))
                .toList();
        if (!hasContiguousDirectUploadParts(orderedParts)
                || !hasValidDirectUploadTotalSize(orderedParts)) {
            return Result.error(ResultEnum.PARAM_IS_INVALID, null);
        }

        Long tenantId = requireDirectUploadTenantId();
        if (tenantId == null) {
            return Result.error(ResultEnum.PARAM_IS_INVALID, null);
        }
        List<TrustedDirectUploadPart> trustedParts = new ArrayList<>(orderedParts.size());
        for (DirectMultipartCompletedPart part : orderedParts) {
            try {
                trustedParts.add(toTrustedDirectUploadPart(request.sessionId(), part, tenantId));
            } catch (IllegalArgumentException e) {
                log.warn("直传分片完成请求参数无效: sessionId={}, partIndex={}, reason={}",
                        request.sessionId(), part != null ? part.partIndex() : null, e.getMessage());
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            } catch (IllegalStateException e) {
                log.warn("直传分片目标副本不足: sessionId={}, partIndex={}, reason={}",
                        request.sessionId(), part != null ? part.partIndex() : null, e.getMessage());
                return Result.error(ResultEnum.STORAGE_INSUFFICIENT_REPLICAS, null);
            }
        }

        List<DirectMultipartCompletedPartVO> completedParts = new ArrayList<>(trustedParts.size());
        DirectUploadDigestAccumulator contentDigest = DirectUploadDigestAccumulator.sha256();
        for (TrustedDirectUploadPart trustedPart : trustedParts) {
            try {
                DirectUploadPromotionResult promotedPart = directUploadPromotionService.promote(
                        trustedPart.toPromotionDescriptor(),
                        contentDigest
                );
                completedParts.add(toDirectMultipartCompletedPartVO(trustedPart, promotedPart));
            } catch (Exception e) {
                log.error("直传分片校验或晋级失败: sessionId={}, partIndex={}",
                        request.sessionId(), trustedPart.partIndex(), e);
                return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
            }
        }

        String contentHash = contentDigest.finishHash();
        return Result.success(new CompleteDirectMultipartUploadResponse(
                request.sessionId(),
                contentHash,
                completedParts));
    }

    /**
     * 校验直传完成分片按索引从零连续，避免重排、重复或缺片生成错误的整体内容哈希。
     */
    private boolean hasContiguousDirectUploadParts(List<DirectMultipartCompletedPart> parts) {
        for (int index = 0; index < parts.size(); index++) {
            DirectMultipartCompletedPart part = parts.get(index);
            if (part == null || part.partIndex() != index) {
                return false;
            }
        }
        return true;
    }

    /**
     * 校验完成请求的分片总大小未溢出且不超过直传文件上限。
     *
     * @param parts 已按索引排序的完成分片
     * @return 总大小是否在安全范围内
     */
    private boolean hasValidDirectUploadTotalSize(List<DirectMultipartCompletedPart> parts) {
        long totalSize = 0;
        try {
            for (DirectMultipartCompletedPart part : parts) {
                totalSize = Math.addExact(totalSize, part.size());
            }
        } catch (ArithmeticException e) {
            return false;
        }
        return totalSize > 0
                && totalSize <= storageProperties.getDirectUpload().getEffectiveMaxFileSizeBytes();
    }

    @Override
    public Result<Boolean> abortDirectMultipartUpload(AbortDirectMultipartUploadRequest request) {
        if (request == null || CollectionUtils.isEmpty(request.parts())) {
            return Result.success(true);
        }
        if (!isValidDirectUploadSessionId(request.sessionId())
                || request.parts().size() > MAX_DIRECT_UPLOAD_PARTS) {
            return Result.error(ResultEnum.PARAM_IS_INVALID, false);
        }

        Long tenantId = requireDirectUploadTenantId();
        if (tenantId == null) {
            return Result.error(ResultEnum.PARAM_IS_INVALID, false);
        }
        List<DirectUploadStagingDescriptor> stagingDescriptors = new ArrayList<>(request.parts().size());
        for (DirectMultipartCompletedPart part : request.parts()) {
            try {
                stagingDescriptors.add(toTrustedDirectUploadStagingDescriptor(
                        request.sessionId(),
                        part,
                        tenantId
                ));
            } catch (IllegalArgumentException e) {
                log.warn("直传分片终止请求参数无效: sessionId={}, partIndex={}, reason={}",
                        request.sessionId(), part != null ? part.partIndex() : null, e.getMessage());
                return Result.error(ResultEnum.PARAM_IS_INVALID, false);
            }
        }

        boolean allCleaned = true;
        for (DirectUploadStagingDescriptor stagingDescriptor : stagingDescriptors) {
            try {
                directUploadPromotionService.abort(stagingDescriptor);
            } catch (Exception e) {
                allCleaned = false;
                log.warn("清理直传 staging 分片失败: sessionId={}, partIndex={}, node={}",
                        request.sessionId(), stagingDescriptor.partIndex(), stagingDescriptor.nodeName(), e);
            }
        }
        if (!allCleaned) {
            return Result.error(ResultEnum.FILE_SERVICE_ERROR, false);
        }
        return Result.success(true);
    }

    /**
     * 解析直传写边界的显式租户，缺失或畸形 attachment 时失败关闭而不回退 tenant 0。
     *
     * @return 显式租户 ID；上下文不可用时返回 null
     */
    private Long requireDirectUploadTenantId() {
        try {
            return TenantContextUtil.requireTenantId();
        } catch (IllegalStateException e) {
            log.warn("拒绝缺少有效租户上下文的直传存储请求: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Generates a presigned PUT URL for a direct-upload staging object.
     */
    private DirectMultipartUploadPartUrl createPresignedPartUrl(
            CreateDirectMultipartUploadRequest request,
            DirectMultipartUploadPartRequest part,
            Long tenantId) {
        String nodeName = selectWritableNode(part.objectName());
        try (S3ClientManager.TopologyLease topology = clientManager.acquireTopologyLease()) {
            S3Client client = topology.getClient(nodeName);
            S3Presigner presigner = topology.getPresigner(nodeName);
            if (client == null || presigner == null) {
                throw new IllegalStateException("S3 client or presigner is unavailable for node " + nodeName);
            }

            ensureBucketExists(client, nodeName, nodeName, topology.revision());

            String directObjectName = normalizeHash(part.cipherHash());
            String stagingObjectName = buildDirectUploadStagingObjectName(
                    tenantId,
                    request.sessionId(),
                    part.partIndex()
            );
            String finalObjectName = buildDirectUploadFinalObjectName(tenantId, directObjectName);
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(nodeName)
                    .key(stagingObjectName)
                    .contentLength(part.size())
                    .build();
            PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofHours(EXPIRY_HOURS))
                    .putObjectRequest(putObjectRequest)
                    .build();
            PresignedPutObjectRequest presignedRequest = presigner.presignPutObject(presignRequest);

            directUploadStagingTracker.record(new DirectUploadStagingDescriptor(
                    tenantId,
                    request.sessionId(),
                    part.partIndex(),
                    nodeName,
                    stagingObjectName
            ));
            topology.verifyCurrent();

            long expiresAt = System.currentTimeMillis() / 1000L
                    + TimeUnit.HOURS.toSeconds(EXPIRY_HOURS);
            return new DirectMultipartUploadPartUrl(
                    part.partIndex(),
                    presignedRequest.url().toString(),
                    expiresAt,
                    buildDirectUploadStoragePath(tenantId, directObjectName),
                    stagingObjectName,
                    finalObjectName,
                    nodeName,
                    part.size()
            );
        }
    }

    /**
     * Resolves target replica nodes and enforces the same degraded-write policy used by normal chunk uploads.
     */
    private List<String> resolveDirectUploadTargetNodes(String cipherHash) {
        storageProperties.validateDegradedWriteTracking();
        List<String> targetNodes = new ArrayList<>(new LinkedHashSet<>(
                faultDomainManager.getTargetNodes(cipherHash)));
        int requiredReplicas = storageProperties.getEffectiveReplicationFactor();
        StorageProperties.DegradedWriteConfig degradedWriteConfig = storageProperties.getDegradedWrite();
        if (targetNodes.size() < requiredReplicas) {
            if (degradedWriteConfig != null && degradedWriteConfig.isEnabled()
                    && targetNodes.size() >= degradedWriteConfig.getMinReplicas()) {
                log.warn("直传降级写入模式: hash={}, 目标副本={}, 实际可用={}",
                        cipherHash, requiredReplicas, targetNodes.size());
            } else {
                throw new IllegalStateException("direct-upload target replicas are insufficient");
            }
        }
        if (targetNodes.isEmpty()) {
            throw new IllegalStateException("No online storage node available for " + cipherHash);
        }
        return targetNodes;
    }

    /**
     * Calculates the write quorum for direct-upload final replica promotion.
     */
    private int resolveDirectUploadQuorum(List<String> targetNodes) {
        int requiredReplicas = storageProperties.getEffectiveReplicationFactor();
        if (targetNodes.size() < requiredReplicas) {
            return targetNodes.size();
        }
        return storageProperties.getEffectiveQuorum();
    }

    /**
     * Selects one online target node for the direct-upload chunk object.
     */
    private String selectWritableNode(String objectName) {
        List<String> targetNodes = faultDomainManager.getTargetNodes(objectName);
        for (String node : targetNodes) {
            if (s3Monitor.isNodeOnline(node)) {
                return node;
            }
        }
        throw new IllegalStateException("No online storage node available for " + objectName);
    }

    /**
     * Builds the physical staging object key for a direct-upload chunk.
     */
    private String buildDirectUploadStagingObjectName(Long tenantId, String sessionId, int partIndex) {
        return "tenant/" + tenantId + "/" + STAGING_PREFIX + "/" + sessionId + "/part-" + partIndex;
    }

    /**
     * Builds the tenant-scoped final object key for a verified direct-upload chunk.
     */
    private String buildDirectUploadFinalObjectName(Long tenantId, String cipherHash) {
        return TenantContextUtil.buildTenantObjectPath(tenantId, normalizeHash(cipherHash));
    }

    /**
     * Builds the logical chunk path returned to backend manifests for a direct-upload chunk.
     */
    private String buildDirectUploadStoragePath(Long tenantId, String cipherHash) {
        return "storage/tenant/" + tenantId + "/chunk/" + normalizeHash(cipherHash);
    }

    /**
     * 校验 storage RPC 层的完整直传创建计划，避免绕过 backend 直接提交不连续或越界分片。
     */
    private boolean isValidDirectUploadCreateRequest(CreateDirectMultipartUploadRequest request) {
        if (request == null
                || !isValidDirectUploadSessionId(request.sessionId())
                || request.fileName() == null
                || request.fileName().isBlank()
                || request.fileName().length() > 255
                || request.contentType() == null
                || request.contentType().isBlank()
                || request.contentType().length() > 255
                || CollectionUtils.isEmpty(request.parts())) {
            return false;
        }

        StorageProperties.DirectUploadConfig config = storageProperties.getDirectUpload();
        if (request.totalSize() <= 0
                || request.totalSize() > config.getEffectiveMaxFileSizeBytes()
                || request.chunkSize() <= 0
                || request.chunkSize() > config.getEffectiveMaxPartSizeBytes()) {
            return false;
        }
        long expectedPartCount = (request.totalSize() + request.chunkSize() - 1L)
                / request.chunkSize();
        if (expectedPartCount <= 0
                || expectedPartCount > MAX_DIRECT_UPLOAD_PARTS
                || request.parts().size() != expectedPartCount
                || request.parts().stream().anyMatch(Objects::isNull)) {
            return false;
        }

        List<DirectMultipartUploadPartRequest> orderedParts = request.parts().stream()
                .sorted(Comparator.comparingInt(DirectMultipartUploadPartRequest::partIndex))
                .toList();
        long sizeSum = 0;
        for (int index = 0; index < orderedParts.size(); index++) {
            DirectMultipartUploadPartRequest part = orderedParts.get(index);
            long expectedSize = Math.min(
                    (long) request.chunkSize(),
                    request.totalSize() - (long) index * request.chunkSize()
            );
            if (part.partIndex() != index
                    || part.size() != expectedSize
                    || !isValidDirectUploadPart(part)) {
                return false;
            }
            try {
                sizeSum = Math.addExact(sizeSum, part.size());
            } catch (ArithmeticException e) {
                return false;
            }
        }
        return sizeSum == request.totalSize();
    }

    /**
     * Validates one requested direct-upload part.
     */
    private boolean isValidDirectUploadPart(DirectMultipartUploadPartRequest part) {
        return part != null
                && part.partIndex() >= 0
                && part.size() > 0
                && part.size() <= storageProperties.getDirectUpload().getEffectiveMaxPartSizeBytes()
                && part.objectName() != null
                && !part.objectName().isBlank()
                && isSha256Hash(part.objectName())
                && isSha256Hash(part.plainHash())
                && isSha256Hash(part.cipherHash())
                && CHECKSUM_ALGORITHM_SHA256.equals(part.checksumAlgorithm())
                && normalizeHash(part.objectName()).equals(normalizeHash(part.cipherHash()))
                && normalizeHash(part.plainHash()).equals(normalizeHash(part.cipherHash()));
    }

    /**
     * Validates one completed direct-upload part request.
     */
    private boolean isValidCompletedDirectPart(
            DirectMultipartCompletedPart part,
            boolean requireEtag
    ) {
        return part != null
                && part.partIndex() >= 0
                && part.size() > 0
                && isValidDirectUploadNodeName(part.nodeName())
                && part.stagingObjectName() != null
                && !part.stagingObjectName().isBlank()
                && part.finalObjectName() != null
                && !part.finalObjectName().isBlank()
                && part.storagePath() != null
                && !part.storagePath().isBlank()
                && isSha256Hash(part.plainHash())
                && isSha256Hash(part.cipherHash())
                && CHECKSUM_ALGORITHM_SHA256.equals(part.checksumAlgorithm())
                && normalizeHash(part.plainHash()).equals(normalizeHash(part.cipherHash()))
                && part.size() <= storageProperties.getDirectUpload().getEffectiveMaxPartSizeBytes()
                && (!requireEtag || isSafeDirectUploadEtag(part.eTag()));
    }

    /**
     * 校验 ETag 只包含有界可见 ASCII，避免控制字符进入条件请求、日志或持久化证据。
     *
     * @param eTag provider 返回并由客户端回传的 ETag
     * @return 长度和字符集均安全时返回 true
     */
    private boolean isSafeDirectUploadEtag(String eTag) {
        if (eTag == null || eTag.length() < 1 || eTag.length() > MAX_DIRECT_UPLOAD_ETAG_LENGTH) {
            return false;
        }
        for (int index = 0; index < eTag.length(); index++) {
            char value = eTag.charAt(index);
            if (value < 0x21 || value > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /**
     * Rebuilds tenant/session-owned storage metadata and rejects client-supplied object keys that do not match it.
     */
    private TrustedDirectUploadPart toTrustedDirectUploadPart(
            String sessionId,
            DirectMultipartCompletedPart part,
            Long tenantId
    ) {
        if (!isValidDirectUploadSessionId(sessionId)
                || !isValidCompletedDirectPart(part, true)) {
            throw new IllegalArgumentException("invalid direct-upload part metadata");
        }
        String cipherHash = normalizeHash(part.cipherHash());
        String expectedStagingObjectName = buildDirectUploadStagingObjectName(tenantId, sessionId, part.partIndex());
        String expectedFinalObjectName = buildDirectUploadFinalObjectName(tenantId, cipherHash);
        String expectedStoragePath = buildDirectUploadStoragePath(tenantId, cipherHash);
        if (!expectedStagingObjectName.equals(part.stagingObjectName())
                || !expectedFinalObjectName.equals(part.finalObjectName())
                || !expectedStoragePath.equals(part.storagePath())) {
            throw new IllegalArgumentException("direct-upload object paths do not match the session");
        }

        List<String> targetNodes = resolveDirectUploadTargetNodes(cipherHash);
        return new TrustedDirectUploadPart(
                part,
                tenantId,
                sessionId,
                expectedStagingObjectName,
                expectedFinalObjectName,
                expectedStoragePath,
                targetNodes,
                resolveDirectUploadQuorum(targetNodes)
        );
    }

    /**
     * 为 abort 重建规范 staging 身份，不依赖完成时可能已变化的目标拓扑。
     */
    private DirectUploadStagingDescriptor toTrustedDirectUploadStagingDescriptor(
            String sessionId,
            DirectMultipartCompletedPart part,
            Long tenantId
    ) {
        if (!isValidDirectUploadSessionId(sessionId)
                || !isValidCompletedDirectPart(part, false)) {
            throw new IllegalArgumentException("invalid direct-upload staging metadata");
        }
        String cipherHash = normalizeHash(part.cipherHash());
        String expectedStagingObjectName = buildDirectUploadStagingObjectName(
                tenantId,
                sessionId,
                part.partIndex()
        );
        String expectedFinalObjectName = buildDirectUploadFinalObjectName(tenantId, cipherHash);
        String expectedStoragePath = buildDirectUploadStoragePath(tenantId, cipherHash);
        if (!expectedStagingObjectName.equals(part.stagingObjectName())
                || !expectedFinalObjectName.equals(part.finalObjectName())
                || !expectedStoragePath.equals(part.storagePath())) {
            throw new IllegalArgumentException("direct-upload object paths do not match the session");
        }
        return new DirectUploadStagingDescriptor(
                tenantId,
                sessionId,
                part.partIndex(),
                part.nodeName(),
                expectedStagingObjectName
        );
    }

    /**
     * 将 promotion service 的可信结果转换为现有 RPC 响应合同。
     */
    private DirectMultipartCompletedPartVO toDirectMultipartCompletedPartVO(
            TrustedDirectUploadPart part,
            DirectUploadPromotionResult result
    ) {
        return new DirectMultipartCompletedPartVO(
                part.partIndex(),
                part.storagePath(),
                result.size(),
                result.eTag(),
                part.plainHash(),
                part.cipherHash(),
                part.checksumAlgorithm()
        );
    }

    /**
     * 校验 direct-upload session 与公开 REST 合同使用相同的安全字符集。
     */
    private boolean isValidDirectUploadSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 64) {
            return false;
        }
        for (int index = 0; index < sessionId.length(); index++) {
            char value = sessionId.charAt(index);
            if (!((value >= 'A' && value <= 'Z')
                    || (value >= 'a' && value <= 'z')
                    || (value >= '0' && value <= '9')
                    || value == '-')) {
                return false;
            }
        }
        return true;
    }

    /**
     * 校验持久化节点名只包含 S3 bucket/node 配置允许的低风险字符。
     */
    private boolean isValidDirectUploadNodeName(String nodeName) {
        if (nodeName == null || nodeName.isBlank() || nodeName.length() > 128) {
            return false;
        }
        for (int index = 0; index < nodeName.length(); index++) {
            char value = nodeName.charAt(index);
            if (!((value >= 'A' && value <= 'Z')
                    || (value >= 'a' && value <= 'z')
                    || (value >= '0' && value <= '9')
                    || value == '-'
                    || value == '_'
                    || value == '.')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates the canonical sha256-prefixed hash format used by direct-upload completion.
     */
    private boolean isSha256Hash(String value) {
        String normalized = normalizeHash(value);
        if (!normalized.equals(value) || !normalized.startsWith(HASH_PREFIX_SHA256)) {
            return false;
        }
        String hex = normalized.substring(HASH_PREFIX_SHA256.length());
        if (hex.length() != 64) {
            return false;
        }
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Carries direct-upload metadata after it has been rebound to the current tenant/session.
     */
    private record TrustedDirectUploadPart(
            DirectMultipartCompletedPart source,
            Long tenantId,
            String sessionId,
            String stagingObjectName,
            String finalObjectName,
            String storagePath,
            List<String> targetNodes,
            int requiredQuorum
    ) {
        private TrustedDirectUploadPart {
            targetNodes = List.copyOf(targetNodes);
        }

        private int partIndex() {
            return source.partIndex();
        }

        private String nodeName() {
            return source.nodeName();
        }

        private long size() {
            return source.size();
        }

        private String eTag() {
            return source.eTag();
        }

        private String plainHash() {
            return source.plainHash();
        }

        private String cipherHash() {
            return source.cipherHash();
        }

        private String checksumAlgorithm() {
            return source.checksumAlgorithm();
        }

        /**
         * 构建不含完整对象字节的内部 promotion 合同。
         */
        private DirectUploadPartDescriptor toPromotionDescriptor() {
            return new DirectUploadPartDescriptor(
                    tenantId,
                    sessionId,
                    partIndex(),
                    nodeName(),
                    stagingObjectName,
                    finalObjectName,
                    size(),
                    eTag(),
                    plainHash(),
                    cipherHash(),
                    checksumAlgorithm(),
                    targetNodes,
                    requiredQuorum
            );
        }
    }

    /**
     * Normalizes checksum algorithm text for direct-upload validation.
     */
    private String normalizeChecksumAlgorithm(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Normalizes prefixed hash values for deterministic comparison.
     */
    private String normalizeHash(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Detects S3-compatible missing-object responses across providers.
     */
    private boolean isMissingObject(S3Exception e) {
        String errorCode = e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : "";
        return e.statusCode() == 404 || "NoSuchKey".equalsIgnoreCase(errorCode);
    }

    @Override
    public Result<String> storeFileChunk(byte[] fileData, String fileHash) {
        if (fileData == null || fileData.length == 0 || fileHash == null || fileHash.isEmpty()) {
            log.warn("storeFileChunk参数无效: fileData={}, fileHash={}",
                fileData == null ? "null" : fileData.length, fileHash);
            return Result.error(ResultEnum.PARAM_IS_INVALID, null);
        }

        // 获取目标节点（每个活跃域一个）
        List<String> targetNodes = faultDomainManager.getTargetNodes(fileHash);
        int requiredReplicas = storageProperties.getEffectiveReplicationFactor();
        int quorumSize = storageProperties.getEffectiveQuorum();
        var degradedWriteConfig = storageProperties.getDegradedWrite();

        // 检查可用节点是否满足要求
        if (targetNodes.size() < requiredReplicas) {
            // 检查是否可以降级写入
            if (degradedWriteConfig != null && degradedWriteConfig.isEnabled()
                    && targetNodes.size() >= degradedWriteConfig.getMinReplicas()) {
                log.warn("降级写入模式: hash={}, 目标副本={}, 实际可用={}",
                        fileHash, requiredReplicas, targetNodes.size());
                // 降级模式下，仲裁数调整为可用节点数
                quorumSize = targetNodes.size();
            } else {
                log.error("无法存储文件块：跨故障域的健康节点不足。可用节点: {}, 需要: {}, 最小降级: {}",
                        targetNodes, requiredReplicas,
                        degradedWriteConfig != null ? degradedWriteConfig.getMinReplicas() : "disabled");
                return Result.error(ResultEnum.STORAGE_INSUFFICIENT_REPLICAS, null);
            }
        }

        try {
            Long tenantId = TenantContextUtil.getTenantIdOrDefault();
            String tenantObjectPath = TenantContextUtil.buildTenantObjectPath(tenantId, fileHash);

            // 创建所有节点的上传任务
            List<CompletableFuture<String>> uploadFutures = new ArrayList<>();
            for (String node : targetNodes) {
                uploadFutures.add(uploadToNodeAsyncWithResult(node, tenantObjectPath, fileData, fileHash, tenantId));
            }

            // 使用仲裁模式等待写入结果
            QuorumResult quorumResult = storeWithQuorum(uploadFutures, targetNodes, quorumSize, fileHash);

            if (!quorumResult.isSuccess()) {
                uploadFutures.forEach(f -> cancelIfNotDone(f, "upload"));
                log.error("存储文件块仲裁失败: hash={}, 成功={}, 需要={}", fileHash,
                        quorumResult.getSuccessCount(), quorumSize);
                return Result.error(ResultEnum.STORAGE_QUORUM_NOT_REACHED, null);
            }

            // 使用新的路径格式（不包含逻辑节点名）
            String logicalPath = TenantContextUtil.buildChunkPath(fileHash);
            log.info("已成功将文件块 '{}' 存储到 {} 个节点 (仲裁: {}/{}, 路径: {})",
                    fileHash, quorumResult.getSuccessCount(), quorumResult.getSuccessCount(),
                    targetNodes.size(), logicalPath);

            // 先持久化本次写入的权威成功节点，保证进程内修复失败时仍有可恢复入口。
            boolean trackForSync = degradedWriteConfig != null && degradedWriteConfig.isTrackForSync();
            if (trackForSync) {
                degradedWriteTracker.recordAuthoritativeDegradedWrite(
                        fileHash,
                        quorumResult.getSuccessNodes(),
                        tenantId
                );
            }

            // 异步检查其他上传任务的状态，修复真实成功后再清除对应降级记录。
            scheduleRepairIfNeededForDomains(
                    uploadFutures,
                    targetNodes,
                    tenantObjectPath,
                    fileHash,
                    tenantId,
                    trackForSync
            );

            return Result.success(logicalPath);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("存储文件块被中断: hash={}", fileHash, e);
            return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
        } catch (Exception e) {
            log.error("存储文件块 '{}' 失败: {}", fileHash, e.getMessage(), e);
            return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
        }
    }

    /**
     * 仲裁写入结果
     */
    private static class QuorumResult {
        private final boolean success;
        private final int successCount;
        private final List<String> successNodes;
        private final List<String> failedNodes;

        QuorumResult(boolean success, int successCount, List<String> successNodes, List<String> failedNodes) {
            this.success = success;
            this.successCount = successCount;
            this.successNodes = successNodes;
            this.failedNodes = failedNodes;
        }

        boolean isSuccess() { return success; }
        int getSuccessCount() { return successCount; }
        List<String> getSuccessNodes() { return successNodes; }
        List<String> getFailedNodes() { return failedNodes; }
    }

    /**
     * 使用仲裁模式写入，等待达到仲裁数后返回
     *
     * @param futures     上传任务列表
     * @param nodes       目标节点列表
     * @param quorumSize  仲裁所需的最小成功数
     * @param fileHash    文件哈希（用于日志）
     * @return 仲裁结果
     * @throws InterruptedException 如果等待被中断
     */
    private QuorumResult storeWithQuorum(List<CompletableFuture<String>> futures,
                                          List<String> nodes,
                                          int quorumSize,
                                          String fileHash) throws InterruptedException {
        CompletableFuture<QuorumResult> resultFuture = new CompletableFuture<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<String> successNodes = new CopyOnWriteArrayList<>();
        List<String> failedNodes = new CopyOnWriteArrayList<>();

        for (int i = 0; i < futures.size(); i++) {
            final String nodeName = nodes.get(i);

            futures.get(i).whenComplete((result, error) -> {
                if (error == null && result != null) {
                    successNodes.add(nodeName);
                    int currentSuccess = successCount.incrementAndGet();
                    log.debug("节点 {} 写入成功 ({}/{}), hash={}", nodeName, currentSuccess, quorumSize, fileHash);

                    if (currentSuccess >= quorumSize && !resultFuture.isDone()) {
                        // 达到仲裁数，立即返回成功（创建快照避免后续修改）
                        resultFuture.complete(new QuorumResult(true, currentSuccess,
                                new ArrayList<>(successNodes), new ArrayList<>(failedNodes)));
                    }
                } else {
                    failedNodes.add(nodeName);
                    int currentFailure = failureCount.incrementAndGet();
                    log.warn("节点 {} 写入失败 ({}/{}), hash={}, error={}",
                            nodeName, currentFailure, futures.size() - quorumSize + 1, fileHash,
                            error != null ? error.getMessage() : "null result");

                    // 检查是否已无法达到仲裁
                    if (currentFailure > futures.size() - quorumSize && !resultFuture.isDone()) {
                        resultFuture.complete(new QuorumResult(false, successCount.get(),
                                new ArrayList<>(successNodes), new ArrayList<>(failedNodes)));
                    }
                }

                // 所有任务都完成了
                if (successCount.get() + failureCount.get() >= futures.size() && !resultFuture.isDone()) {
                    boolean success = successCount.get() >= quorumSize;
                    resultFuture.complete(new QuorumResult(success, successCount.get(),
                            new ArrayList<>(successNodes), new ArrayList<>(failedNodes)));
                }
            });
        }

        try {
            QuorumResult result = resultFuture.get(FILE_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (result.isSuccess()) {
                cancelPendingFutures(futures, "upload-after-quorum");
                // 取消完成后重新快照，纳入仲裁返回与取消之间已经成功的节点。
                return new QuorumResult(
                        true,
                        successCount.get(),
                        new ArrayList<>(successNodes),
                        new ArrayList<>(failedNodes)
                );
            }
            return result;
        } catch (TimeoutException e) {
            log.error("存储文件块仲裁超时（>{}s）: hash={}", FILE_OPERATION_TIMEOUT_SECONDS, fileHash);
            cancelPendingFutures(futures, "upload-timeout");
            return new QuorumResult(false, successCount.get(),
                    new ArrayList<>(successNodes), new ArrayList<>(failedNodes));
        } catch (ExecutionException e) {
            log.error("存储文件块仲裁异常: hash={}", fileHash, e);
            cancelPendingFutures(futures, "upload-exception");
            return new QuorumResult(false, successCount.get(),
                    new ArrayList<>(successNodes), new ArrayList<>(failedNodes));
        }
    }

    /**
     * firstSuccessOf 的多节点版本
     */
    private CompletableFuture<String> firstSuccessOf(List<CompletableFuture<String>> futures) {
        if (futures.size() == 2) {
            return firstSuccessOf(futures.get(0), futures.get(1));
        }

        CompletableFuture<String> result = new CompletableFuture<>();
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicReference<Throwable> lastError = new AtomicReference<>();

        for (CompletableFuture<String> future : futures) {
            future.whenComplete((value, error) -> {
                if (error == null && value != null) {
                    result.complete(value);
                } else {
                    lastError.set(error);
                    if (failureCount.incrementAndGet() == futures.size()) {
                        result.completeExceptionally(lastError.get() != null ?
                                lastError.get() : new RuntimeException("All uploads failed"));
                    }
                }
            });
        }

        return result;
    }

    /**
     * 检查故障域模式下其他上传任务的状态，失败则触发修复
     */
    private void scheduleRepairIfNeededForDomains(List<CompletableFuture<String>> futures,
                                                   List<String> nodes,
                                                   String objectPath,
                                                   String objectHash,
                                                   Long tenantId,
                                                   boolean trackForSync) {
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((v, ex) -> {
            if (!REPAIR_CHECK_SEMAPHORE.tryAcquire()) {
                log.warn("修复检查队列已满，跳过本次修复检查: object={}", objectPath);
                return;
            }

            try {
                // 找出成功和失败的节点
                List<String> successNodes = new ArrayList<>();
                List<String> failedNodes = new ArrayList<>();

                for (int i = 0; i < futures.size(); i++) {
                    if (futures.get(i).isCompletedExceptionally()) {
                        failedNodes.add(nodes.get(i));
                    } else {
                        successNodes.add(nodes.get(i));
                    }
                }

                if (!failedNodes.isEmpty() && !successNodes.isEmpty()) {
                    log.warn("部分节点上传失败: 失败={}, 成功={}，触发修复任务", failedNodes, successNodes);
                    // 从成功节点复制到失败节点
                    String sourceNode = successNodes.getFirst();
                    for (String failedNode : failedNodes) {
                        CompletableFuture<Boolean> repairFuture;
                        try {
                            repairFuture = consistencyRepairService.scheduleImmediateRepairByNodesAsync(
                                    objectPath,
                                    sourceNode,
                                    failedNode
                            );
                        } catch (RuntimeException repairScheduleError) {
                            log.warn("调度立即修复失败，保留降级记录: object={}, source={}, target={}",
                                    objectPath, sourceNode, failedNode, repairScheduleError);
                            continue;
                        }
                        if (repairFuture == null) {
                            log.warn("立即修复未返回完成结果，保留降级记录: object={}, source={}, target={}",
                                    objectPath, sourceNode, failedNode);
                            continue;
                        }
                        repairFuture.whenComplete((repaired, repairError) -> {
                            if (repairError != null || !Boolean.TRUE.equals(repaired)) {
                                log.warn("立即修复未成功，保留降级记录: object={}, source={}, target={}",
                                        objectPath, sourceNode, failedNode, repairError);
                                return;
                            }
                            if (!trackForSync) {
                                return;
                            }
                            try {
                                degradedWriteTracker.markNodeRepaired(objectHash, tenantId, failedNode);
                            } catch (RuntimeException trackingError) {
                                log.warn("立即修复成功但更新降级记录失败，将由后续同步重试: object={}, target={}",
                                        objectPath, failedNode, trackingError);
                            }
                        });
                    }
                } else if (failedNodes.isEmpty()) {
                    log.debug("所有副本都成功写入，无需修复");
                }
            } finally {
                REPAIR_CHECK_SEMAPHORE.release();
            }
        });
    }

    /**
     * 取消未完成的 Future，避免超时后任务继续执行
     */
    private void cancelIfNotDone(CompletableFuture<?> future, String name) {
        if (!future.isDone()) {
            boolean cancelled = future.cancel(true);
            log.debug("取消未完成任务 {}: cancelled={}", name, cancelled);
        }
    }

    /**
     * 批量取消仍未完成的异步任务，避免达到仲裁或超时后后台上传继续占用资源。
     */
    private void cancelPendingFutures(List<? extends CompletableFuture<?>> futures, String name) {
        for (CompletableFuture<?> future : futures) {
            cancelIfNotDone(future, name);
        }
    }

    /**
     * 异步上传并返回成功的节点名称
     */
    private CompletableFuture<String> uploadToNodeAsyncWithResult(String nodeName, String objectName,
                                                                  byte[] file, String fileHash, Long tenantId) {
        return CompletableFuture.supplyAsync(() -> {
            if (!s3Monitor.isNodeOnline(nodeName)) {
                throw new RuntimeException("Node '" + nodeName + "' is offline, cannot upload file '" + objectName + "'.");
            }
            S3Client client = clientManager.getClient(nodeName);
            if (client == null) {
                throw new RuntimeException("Cannot get S3Client for online node: " + nodeName);
            }

            try {
                // 确保 Bucket 存在
                ensureBucketExists(client, nodeName, nodeName);

                // 使用 AWS SDK v2 的 PutObjectRequest 和 RequestBody
                PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(nodeName)
                    .key(objectName)
                    .contentLength((long) file.length)
                    .metadata(buildObjectMetadata(fileHash, tenantId))
                    .build();
                client.putObject(request, RequestBody.fromBytes(file));
                log.debug("已成功将'{}'上传到节点'{}'", objectName, nodeName);
                return nodeName; // 返回成功的节点名称

            } catch (Exception e) {
                log.error("将'{}'上传到节点'{}'时出错：{}", objectName, nodeName, e.getMessage());
                throw new RuntimeException("Upload of '" + objectName + "' to node '" + nodeName + "' failed: " + e.getMessage(), e);
            }
        }, uploadExecutor);
    }

    /**
     * 构造对象写入时用于后续轻量完整性校验的 S3 用户元数据。
     */
    private Map<String, String> buildObjectMetadata(String fileHash, Long tenantId) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put(METADATA_FILE_HASH, fileHash);
        metadata.put(METADATA_TENANT_ID, String.valueOf(tenantId));
        return metadata;
    }

    @Override
    public Result<Boolean> deleteFile(Map<String, String> fileContent) {
        if (CollectionUtils.isEmpty(fileContent)) {
            log.warn("deleteFile called with empty fileContent");
            return Result.success(true);
        }

        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, String> entry : fileContent.entrySet()) {
            String fileHash = entry.getKey();
            String filePath = entry.getValue();

            try {
                // 解析分片路径
                TenantContextUtil.ParsedChunkPath parsedPath = TenantContextUtil.parseChunkPath(filePath);
                if (parsedPath == null) {
                    errors.add("invalid chunk path format");
                    continue;
                }

                // 校验 fileHash 与路径中的 objectName 匹配
                if (!fileHash.equals(parsedPath.objectName())) {
                    errors.add("hash mismatch with path");
                    continue;
                }

                // 删除覆盖当前候选节点和所有活跃域节点，避免遗漏 fallback/rebalance 残留副本。
                List<String> targetNodes = getDeleteCandidateNodes(fileHash, parsedPath);
                if (targetNodes.isEmpty()) {
                    errors.add("no candidate nodes found");
                    continue;
                }

                String objectPath = parsedPath.objectPath();

                // 从所有目标节点删除
                for (String nodeName : targetNodes) {
                    try {
                        deleteFromNode(nodeName, objectPath);
                    } catch (Exception e) {
                        log.warn("Object delete failed on node {}, errorClass={}",
                                nodeName, e.getClass().getSimpleName());
                    }
                }
                log.info("Deleted one file chunk from {} candidate nodes", targetNodes.size());
            } catch (Exception e) {
                log.error("Failed to delete one file chunk, errorClass={}",
                        e.getClass().getSimpleName());
                errors.add(e.getClass().getSimpleName());
            }
        }

        if (!errors.isEmpty()) {
            log.warn("Some file chunks failed to delete: failureCount={}", errors.size());
            return Result.error(ResultEnum.FILE_SERVICE_ERROR, false);
        }
        return Result.success(true);
    }

    /**
     * 汇总删除时需要尝试清理的节点。
     *
     * @param fileHash 文件分片哈希
     * @return 去重后的候选节点列表
     */
    private List<String> getDeleteCandidateNodes(String fileHash, TenantContextUtil.ParsedChunkPath parsedPath) {
        LinkedHashSet<String> nodes = new LinkedHashSet<>();
        addLegacyNodeFirst(nodes, parsedPath);
        nodes.addAll(faultDomainManager.getCandidateNodes(fileHash));
        List<String> activeDomains = faultDomainManager.getActiveDomains();
        if (!CollectionUtils.isEmpty(activeDomains)) {
            for (String domainName : activeDomains) {
                nodes.addAll(faultDomainManager.getNodesInDomain(domainName));
            }
        }
        return new ArrayList<>(nodes);
    }

    private void deleteFromNode(String nodeName, String objectName) throws Exception {
        if (!s3Monitor.isNodeOnline(nodeName)) {
            log.warn("Node '{}' is offline, skipping delete for '{}'", nodeName, objectName);
            return;
        }
        S3Client client = clientManager.getClient(nodeName);
        if (client == null) {
            log.warn("Cannot get S3Client for node {}", nodeName);
            return;
        }

        DeleteObjectRequest request = DeleteObjectRequest.builder()
            .bucket(nodeName)
            .key(objectName)
            .build();
        client.deleteObject(request);
        log.debug("Deleted object '{}' from node '{}'", objectName, nodeName);
    }

    // --- 内部辅助方法 ---

    /**
     * 内部实现：根据文件路径和哈希获取文件
     * 使用故障域机制，从候选节点中选择负载最低的进行读取
     *
     * @return Optional<File> 如果成功获取文件；Optional.empty() 如果尝试后未找到或节点不可用；
     * @throws RuntimeException 如果发生不可恢复的存储错误
     */
    private Optional<byte[]> getFileByHashInternal(String filePath, String fileHash) throws RuntimeException {
        // 解析分片路径
        TenantContextUtil.ParsedChunkPath parsedPath = TenantContextUtil.parseChunkPath(filePath);
        if (parsedPath == null) {
            log.error("无效的分片路径格式: {}", filePath);
            return Optional.empty();
        }

        // 校验 fileHash 与路径中的 objectName 匹配
        if (!fileHash.equals(parsedPath.objectName())) {
            log.error("路径[{}]中的 fileHash '{}' 和 objectName '{}' 不匹配",
                    filePath, fileHash, parsedPath.objectName());
            return Optional.empty();
        }

        // 获取候选节点
        List<String> candidateNodes = getReadCandidateNodes(fileHash, parsedPath);
        if (candidateNodes.isEmpty()) {
            log.error("无法找到文件 '{}' 的候选存储节点", fileHash);
            return Optional.empty();
        }

        String objectPath = parsedPath.objectPath();

        // 选择负载最低的节点作为主节点
        String primaryNode = faultDomainManager.selectBestNodeForRead(candidateNodes);
        if (primaryNode != null) {
            Optional<byte[]> fileOpt = tryGetObjectFromNode(primaryNode, objectPath);
            if (fileOpt.isPresent()) {
                return fileOpt;
            }
            log.warn("无法从主节点 '{}' 获取文件 '{}'", primaryNode, objectPath);
        }

        // 主节点失败，尝试其他候选节点
        for (String node : candidateNodes) {
            if (node.equals(primaryNode)) {
                continue; // 已经尝试过
            }
            if (!s3Monitor.isNodeOnline(node)) {
                continue;
            }

            log.info("正在尝试从备选节点 '{}' 获取文件 '{}'...", node, objectPath);
            Optional<byte[]> fileOpt = tryGetObjectFromNode(node, objectPath);
            if (fileOpt.isPresent()) {
                return fileOpt;
            }
        }

        // 所有节点都失败
        log.error("无法从任何候选节点 {} 获取文件 '{}'", candidateNodes, objectPath);
        return Optional.empty();
    }

    /**
     * 内部实现：获取文件的预签名下载 URL
     * 使用故障域机制，从候选节点中选择负载最低的生成 URL
     *
     * @return Optional<String> 如果成功；Optional.empty() 如果失败
     */
    private Optional<String> getPresignedUrlInternal(String filePath, String fileHash) {
        // 解析分片路径
        TenantContextUtil.ParsedChunkPath parsedPath = TenantContextUtil.parseChunkPath(filePath);
        if (parsedPath == null) {
            log.error("无效的分片路径格式: {}", filePath);
            return Optional.empty();
        }

        // 校验 fileHash 与路径中的 objectName 匹配
        if (!fileHash.equals(parsedPath.objectName())) {
            log.error("路径[{}]中的 fileHash '{}' 和 objectName '{}' 不匹配",
                    filePath, fileHash, parsedPath.objectName());
            return Optional.empty();
        }

        // 获取候选节点
        List<String> candidateNodes = getReadCandidateNodes(fileHash, parsedPath);
        if (candidateNodes.isEmpty()) {
            log.error("无法找到文件 '{}' 的候选存储节点", fileHash);
            return Optional.empty();
        }

        String objectPath = parsedPath.objectPath();

        // 选择负载最低的节点作为主节点
        String primaryNode = faultDomainManager.selectBestNodeForRead(candidateNodes);
        if (primaryNode != null) {
            Optional<String> urlOpt = tryGetResignedUrlFromNode(primaryNode, objectPath);
            if (urlOpt.isPresent()) {
                return urlOpt;
            }
            log.warn("无法从主节点 '{}' 获取 '{}' 的预签名 URL", primaryNode, objectPath);
        }

        // 主节点失败，尝试其他候选节点
        for (String node : candidateNodes) {
            if (node.equals(primaryNode)) {
                continue; // 已经尝试过
            }
            if (!s3Monitor.isNodeOnline(node)) {
                continue;
            }

            log.info("正在尝试从备选节点 '{}' 获取 '{}' 的预签名 URL...", node, objectPath);
            Optional<String> urlOpt = tryGetResignedUrlFromNode(node, objectPath);
            if (urlOpt.isPresent()) {
                return urlOpt;
            }
        }

        log.error("无法从任何候选节点 {} 获取 '{}' 的预签名 URL", candidateNodes, objectPath);
        return Optional.empty();
    }

    /**
     * 汇总读取时需要尝试的候选节点，并优先保留旧路径中记录的逻辑节点。
     *
     * @param fileHash 文件哈希
     * @param parsedPath 已解析的分片路径
     * @return 去重后的读取候选节点
     */
    private List<String> getReadCandidateNodes(String fileHash, TenantContextUtil.ParsedChunkPath parsedPath) {
        LinkedHashSet<String> nodes = new LinkedHashSet<>();
        addLegacyNodeFirst(nodes, parsedPath);
        nodes.addAll(faultDomainManager.getCandidateNodes(fileHash));
        return new ArrayList<>(nodes);
    }

    /**
     * 将旧逻辑路径中记录的节点放到候选集合首位。
     *
     * @param nodes 候选节点集合
     * @param parsedPath 已解析的分片路径
     */
    private void addLegacyNodeFirst(LinkedHashSet<String> nodes, TenantContextUtil.ParsedChunkPath parsedPath) {
        if (parsedPath != null
                && parsedPath.legacyNodeName() != null
                && !parsedPath.legacyNodeName().isBlank()) {
            nodes.add(parsedPath.legacyNodeName());
        }
    }

    /**
     * 从存储节点读取文件对象到内存。
     *
     * <p><b>当前实现约束</b>:
     * 使用 ByteArrayOutputStream 将完整对象加载到堆内存，受 JVM 堆大小限制。
     * 当前硬编码限制为 100MB（MAX_IN_MEMORY_FILE_SIZE），超过此大小直接拒绝加载。
     * 高并发下载场景需要注意总内存占用：并发下载数 × 平均文件大小。
     * </p>
     *
     * <p><b>安全机制</b>:
     * - 下载前检查对象大小（HeadObject）
     * - 分块读取避免单次读取过大（8KB buffer）
     * - 读取过程中二次检查防止超限
     * </p>
     *
     * <p><b>v2.0 改进计划</b>（参见 ROADMAP.md P2 任务）:
     * 返回 S3 预签名 URL 给前端，由前端直接从对象存储流式下载并解密，
     * 后端完全不参与数据流传输，彻底消除内存聚合问题。
     * </p>
     *
     * @param nodeName 存储节点名称
     * @param objectName S3 对象键
     * @return 文件字节数组，如果对象不存在或读取失败则返回 empty
     */
    private Optional<byte[]> tryGetObjectFromNode(String nodeName, String objectName) {
        if (!s3Monitor.isNodeOnline(nodeName)) {
            log.warn("节点'{}'处于离线状态，无法获取对象'{}'", nodeName, objectName);
            return Optional.empty();
        }
        S3Client client = clientManager.getClient(nodeName);
        if (client == null) {
            log.error("无法获取在线节点 {} 的 S3Client", nodeName);
            return Optional.empty();
        }

        try {
            // 先获取对象大小，检查是否超过内存限制
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                .bucket(nodeName)
                .key(objectName)
                .build();
            HeadObjectResponse headResponse = client.headObject(headRequest);
            long objectSize = headResponse.contentLength();

            if (objectSize > MAX_IN_MEMORY_FILE_SIZE) {
                log.error("对象 '{}' 大小 ({} bytes) 超过内存限制 ({} bytes)，拒绝加载",
                    objectName, objectSize, MAX_IN_MEMORY_FILE_SIZE);
                throw new RuntimeException("文件过大，无法直接加载到内存");
            }

            GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(nodeName)
                .key(objectName)
                .build();

            // 使用分块读取避免一次性加载大文件
            try (ResponseInputStream<GetObjectResponse> responseStream = client.getObject(getRequest);
                 ByteArrayOutputStream outputStream = new ByteArrayOutputStream((int) objectSize)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                long totalRead = 0;
                while ((bytesRead = responseStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                    // 防止读取超过预期大小（安全检查）
                    if (totalRead > MAX_IN_MEMORY_FILE_SIZE) {
                        log.error("读取对象 '{}' 时超过内存限制，已读取 {} bytes", objectName, totalRead);
                        throw new RuntimeException("文件读取过程中超过内存限制");
                    }
                }
                byte[] fileBytes = outputStream.toByteArray();
                log.info("已成功将对象 '{}' ({} bytes) 从节点 '{}' 读取到服务器",
                    objectName, fileBytes.length, nodeName);
                return Optional.of(fileBytes);
            }
        } catch (NoSuchKeyException e) {
            // 特别处理对象不存在的错误
            log.warn("在节点'{}'上找不到对象'{}'（NoSuchKey）", nodeName, objectName);
            return Optional.empty();
        } catch (S3Exception e) {
            log.error("从节点'{}'获取对象'{}'时出现S3错误：{} (errorCode: {})",
                nodeName, objectName, e.awsErrorDetails().errorMessage(),
                e.awsErrorDetails().errorCode(), e);
            return Optional.empty();
        } catch (Exception e) {
            log.error("从节点'{}'获取对象'{}'时出现意外错误：{}", nodeName, objectName, e.getMessage(), e);
        }
        return Optional.empty();
    }

    /**
     * 尝试从指定节点获取预签名 URL
     */
    private Optional<String> tryGetResignedUrlFromNode(String nodeName, String objectName) {
        if (!s3Monitor.isNodeOnline(nodeName)) {
            log.warn("节点 '{}' 处于离线状态，无法获取 '{}' 的预签名URL", nodeName, objectName);
            return Optional.empty();
        }
        S3Client client = clientManager.getClient(nodeName);
        if (client == null) {
            log.error("无法获取在线节点 '{}' 的S3Client", nodeName);
            return Optional.empty();
        }

        try {
            // 使用节点名称作为桶名
            // 检查对象是否存在（可选，但可以避免为不存在的对象生成URL）
            try {
                HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(nodeName)
                    .key(objectName)
                    .build();
                client.headObject(headRequest);
            } catch (NoSuchKeyException e) {
                log.warn("在节点 '{}' 上找不到对象 '{}'，无法生成预签名URL", nodeName, objectName);
                return Optional.empty();
            }

            // 获取 S3Presigner 来生成预签名 URL
            S3Presigner presigner = clientManager.getPresigner(nodeName);
            if (presigner == null) {
                log.error("无法获取节点 '{}' 的S3Presigner", nodeName);
                return Optional.empty();
            }

            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(nodeName)
                .key(objectName)
                .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofHours(EXPIRY_HOURS))
                .getObjectRequest(getObjectRequest)
                .build();

            PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
            String url = presignedRequest.url().toString();

            log.info("从节点 '{}' 为对象 '{}' 成功生成预签名 URL", nodeName, objectName);
            return Optional.of(url);
        } catch (Exception e) {
            log.error("无法从节点 '{}' 为对象 '{}' 生成预签名 URL：{}", nodeName, objectName, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * 从指定存储节点读取对象 HEAD 元数据，不下载对象内容。
     */
    private HeadLookupResult tryHeadObjectFromNode(String nodeName, String objectName,
                                                  String filePath, String fileHash, Long tenantId) {
        if (!s3Monitor.isNodeOnline(nodeName)) {
            log.warn("节点 '{}' 处于离线状态，无法获取 '{}' 的对象元数据", nodeName, objectName);
            return new HeadLookupResult(HeadLookupStatus.UNAVAILABLE, null);
        }
        S3Client client = clientManager.getClient(nodeName);
        if (client == null) {
            log.error("无法获取在线节点 '{}' 的S3Client", nodeName);
            return new HeadLookupResult(HeadLookupStatus.UNAVAILABLE, null);
        }

        try {
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(nodeName)
                    .key(objectName)
                    .build();
            HeadObjectResponse headResponse = client.headObject(headRequest);
            Map<String, String> metadata = headResponse.metadata() != null ? headResponse.metadata() : Map.of();
            StorageObjectHeadVO head = new StorageObjectHeadVO(
                    true,
                    filePath,
                    fileHash,
                    tenantId,
                    parseLongMetadata(metadata.get(METADATA_TENANT_ID)),
                    nodeName,
                    headResponse.contentLength(),
                    headResponse.eTag(),
                    firstPresentMetadata(metadata, METADATA_FILE_HASH, "fileHash", "filehash", "sha256", "hash")
            );
            return new HeadLookupResult(HeadLookupStatus.FOUND, head);
        } catch (NoSuchKeyException e) {
            log.warn("在节点 '{}' 上找不到对象 '{}'，无法读取对象元数据", nodeName, objectName);
            return new HeadLookupResult(HeadLookupStatus.MISSING, null);
        } catch (S3Exception e) {
            if (isMissingObject(e)) {
                log.warn("在节点 '{}' 上找不到对象 '{}'，无法读取对象元数据", nodeName, objectName);
                return new HeadLookupResult(HeadLookupStatus.MISSING, null);
            }
            var details = e.awsErrorDetails();
            log.error("从节点 '{}' 获取对象 '{}' 元数据时出现S3错误：{} (errorCode: {})",
                    nodeName, objectName,
                    details != null ? details.errorMessage() : e.getMessage(),
                    details != null ? details.errorCode() : e.statusCode(), e);
            return new HeadLookupResult(HeadLookupStatus.UNAVAILABLE, null);
        } catch (Exception e) {
            log.error("从节点 '{}' 获取对象 '{}' 元数据时出现意外错误：{}", nodeName, objectName, e.getMessage(), e);
            return new HeadLookupResult(HeadLookupStatus.UNAVAILABLE, null);
        }
    }

    /**
     * 从对象用户元数据中按候选 key 取第一个非空值。
     */
    private String firstPresentMetadata(Map<String, String> metadata, String... keys) {
        for (String key : keys) {
            String value = metadata.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * 将对象用户元数据中的长整数字段解析为 Long。
     */
    private Long parseLongMetadata(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("对象元数据中的租户 ID 无法解析: {}", value);
            return null;
        }
    }

    /**
     * 异步上传文件到单个 S3 兼容存储节点 (byte[] 数据)
     */
    private CompletableFuture<Void> uploadToNodeAsync(String nodeName, String objectName, byte[] file) {
        return CompletableFuture.runAsync(() -> {
            if (!s3Monitor.isNodeOnline(nodeName)) {
                throw new RuntimeException("Node '" + nodeName + "' is offline, cannot upload file '" + objectName + "'.");
            }
            S3Client client = clientManager.getClient(nodeName);
            if (client == null) {
                throw new RuntimeException("Cannot get S3Client for online node: " + nodeName);
            }

            try {
                // 使用节点名称作为桶名
                // 确保 Bucket 存在
                ensureBucketExists(client, nodeName, nodeName);

                // 使用 AWS SDK v2 的 PutObjectRequest 和 RequestBody
                PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(nodeName)
                    .key(objectName)
                    .contentLength((long) file.length)
                    .build();
                client.putObject(request, RequestBody.fromBytes(file));
                log.debug("已成功将'{}'上传到节点'{}'", objectName, nodeName);

            } catch (Exception e) {
                log.error("将 '{}' 上传到节点 '{}' 时出错：{}", objectName, nodeName, e.getMessage());
                // 包装成自定义异常，携带更多上下文信息
                throw new RuntimeException("Upload of '" + objectName + "' to node '" + nodeName + "' failed: " + e.getMessage(), e);
            }
        }, uploadExecutor);
    }

    /**
     * 确保指定的 Bucket 在给定的 S3 兼容存储节点上存在
     */
    private void ensureBucketExists(S3Client client, String nodeName, String bucketName) throws RuntimeException {
        ensureBucketExists(client, nodeName, bucketName, null);
    }

    /**
     * 按 topology revision 隔离直传桶缓存，避免把旧 endpoint 的存在性结论复用于新拓扑。
     */
    private void ensureBucketExists(
            S3Client client,
            String nodeName,
            String bucketName,
            Long topologyRevision
    ) throws RuntimeException {
        String topologyNamespace = topologyRevision == null
                ? "legacy"
                : "topology-" + topologyRevision;
        String cacheKey = topologyNamespace + ":" + nodeName + ":" + bucketName;
        Boolean cached = bucketExistenceCache.getIfPresent(cacheKey);
        if (Boolean.TRUE.equals(cached)) {
            return;
        }

        try {
            HeadBucketRequest headRequest = HeadBucketRequest.builder()
                .bucket(bucketName)
                .build();
            client.headBucket(headRequest);
            // Bucket exists
            bucketExistenceCache.put(cacheKey, true);
        } catch (NoSuchBucketException e) {
            log.warn("存储桶'{}'在节点'{}'上不存在,正在尝试创建...", bucketName, nodeName);
            try {
                CreateBucketRequest createRequest = CreateBucketRequest.builder()
                    .bucket(bucketName)
                    .build();
                client.createBucket(createRequest);
                log.info("在节点'{}'上成功创建存储桶'{}'", bucketName, nodeName);
                bucketExistenceCache.put(cacheKey, true);
            } catch (Exception createError) {
                log.error("无法在节点'{}'上创建存储桶'{}'：{}", bucketName, nodeName, createError.getMessage());
                bucketExistenceCache.put(cacheKey, false);
                throw new RuntimeException("Failed to create bucket '" + bucketName + "' on node '" + nodeName + "': " + createError.getMessage(), createError);
            }
        } catch (Exception checkError) {
            log.error("检查节点'{}'上的存储桶'{}'是否存在时出错：{}", nodeName, bucketName, checkError.getMessage());
            // 无法检查或创建 Bucket 是严重问题
            throw new RuntimeException("Failed to check/ensure bucket '" + bucketName + "' on node '" + nodeName + "': " + checkError.getMessage(), checkError);
        }
    }

    @Override
    public Result<Map<String, Boolean>> getClusterHealth() {
        Set<String> onlineNodes = s3Monitor.getOnlineNodes();
        Map<String, Boolean> nodeStatus = new LinkedHashMap<>();

        // 从配置中获取所有节点
        var nodes = storageProperties.getNodes();
        if (!CollectionUtils.isEmpty(nodes)) {
            for (var node : nodes) {
                if (Boolean.TRUE.equals(node.getEnabled())) {
                    nodeStatus.put(node.getName(), onlineNodes.contains(node.getName()));
                }
            }
        }

        return Result.success(nodeStatus);
    }

    /**
     * 聚合集群存储容量统计信息。
     * <p>
     * 该方法优先基于 S3Monitor 的 Prometheus 指标缓存构建容量结果；
     * 当部分节点缺失容量指标时返回 degraded=true，并保留可计算的部分结果。
     *
     * @return 容量聚合结果
     */
    @Override
    public Result<StorageCapacityVO> getStorageCapacity() {
        try {
            List<NodeConfig> configuredNodes = storageProperties.getNodes();
            if (CollectionUtils.isEmpty(configuredNodes)) {
                return Result.success(emptyStorageCapacitySnapshot());
            }

            List<NodeConfig> enabledNodes = configuredNodes.stream()
                    .filter(node -> Boolean.TRUE.equals(node.getEnabled()))
                    .toList();
            if (enabledNodes.isEmpty()) {
                return Result.success(emptyStorageCapacitySnapshot());
            }

            long totalCapacityBytes = 0L;
            long usedCapacityBytes = 0L;
            boolean degraded = false;
            List<StorageNodeCapacityVO> nodeSummaries = new ArrayList<>();
            Map<String, DomainCapacityAccumulator> domainAccumulator = new LinkedHashMap<>();

            for (NodeConfig node : enabledNodes) {
                String nodeName = node.getName();
                String faultDomain = (node.getFaultDomain() == null || node.getFaultDomain().isBlank())
                        ? "UNKNOWN"
                        : node.getFaultDomain();
                boolean online = s3Monitor.isNodeOnline(nodeName);
                S3Monitor.NodeMetrics nodeMetrics = s3Monitor.getNodeMetrics(nodeName);

                long nodeTotalBytes = 0L;
                long nodeUsedBytes = 0L;
                if (nodeMetrics != null
                        && nodeMetrics.getDiskTotalBytes() != null
                        && nodeMetrics.getDiskUsedBytes() != null
                        && nodeMetrics.getDiskTotalBytes() > 0) {
                    nodeTotalBytes = nodeMetrics.getDiskTotalBytes();
                    nodeUsedBytes = Math.max(0L, Math.min(nodeTotalBytes, nodeMetrics.getDiskUsedBytes()));
                } else {
                    degraded = true;
                }

                totalCapacityBytes += nodeTotalBytes;
                usedCapacityBytes += nodeUsedBytes;

                nodeSummaries.add(new StorageNodeCapacityVO(
                        nodeName,
                        faultDomain,
                        online,
                        nodeTotalBytes,
                        nodeUsedBytes,
                        calculateUsagePercent(nodeUsedBytes, nodeTotalBytes)
                ));

                DomainCapacityAccumulator accumulator = domainAccumulator.computeIfAbsent(
                        faultDomain,
                        key -> new DomainCapacityAccumulator()
                );
                accumulator.nodeCount++;
                if (online) {
                    accumulator.onlineNodeCount++;
                }
                accumulator.totalCapacityBytes += nodeTotalBytes;
                accumulator.usedCapacityBytes += nodeUsedBytes;
            }

            nodeSummaries.sort(Comparator.comparing(StorageNodeCapacityVO::nodeName));
            List<StorageDomainCapacityVO> domainSummaries = new ArrayList<>();
            for (Map.Entry<String, DomainCapacityAccumulator> entry : domainAccumulator.entrySet()) {
                DomainCapacityAccumulator aggregate = entry.getValue();
                domainSummaries.add(new StorageDomainCapacityVO(
                        entry.getKey(),
                        aggregate.nodeCount,
                        aggregate.onlineNodeCount,
                        aggregate.totalCapacityBytes,
                        aggregate.usedCapacityBytes,
                        calculateUsagePercent(aggregate.usedCapacityBytes, aggregate.totalCapacityBytes)
                ));
            }
            domainSummaries.sort(Comparator.comparing(StorageDomainCapacityVO::domainName));

            long availableCapacityBytes = Math.max(0L, totalCapacityBytes - usedCapacityBytes);
            String source = degraded ? "prometheus-partial" : "prometheus";

            return Result.success(new StorageCapacityVO(
                    totalCapacityBytes,
                    usedCapacityBytes,
                    availableCapacityBytes,
                    degraded,
                    source,
                    nodeSummaries,
                    domainSummaries
            ));
        } catch (Exception e) {
            log.error("聚合存储容量信息失败", e);
            return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
        }
    }

    /**
     * 构造“当前没有受管存储节点”的容量快照，供 backend-web 清理桥接指标。
     *
     * @return 无节点容量快照
     */
    private StorageCapacityVO emptyStorageCapacitySnapshot() {
        return new StorageCapacityVO(
                0L,
                0L,
                0L,
                true,
                "prometheus-no-nodes",
                List.of(),
                List.of()
        );
    }

    /**
     * 计算容量使用率百分比。
     *
     * @param usedBytes 已用容量
     * @param totalBytes 总容量
     * @return 使用率百分比（0-100）
     */
    private double calculateUsagePercent(long usedBytes, long totalBytes) {
        if (totalBytes <= 0) {
            return 0D;
        }
        return Math.min(100D, (usedBytes * 100D) / totalBytes);
    }

    /**
     * 故障域聚合累加器。
     */
    private static final class DomainCapacityAccumulator {
        private int nodeCount;
        private int onlineNodeCount;
        private long totalCapacityBytes;
        private long usedCapacityBytes;
    }

    // ===== v3.0.0 新增：故障域管理 API 实现 =====

    @Override
    public Result<Map<String, Map<String, Object>>> getDomainHealth() {
        Map<String, Map<String, Object>> domainHealth = new LinkedHashMap<>();

        // 动态获取所有故障域（活跃域 + 备用域）
        List<String> allDomains = new ArrayList<>(faultDomainManager.getActiveDomains());
        if (storageProperties.isStandbyEnabled()) {
            allDomains.add(storageProperties.getStandbyDomain());
        }

        for (String domainName : allDomains) {
            Map<String, Object> domainInfo = new LinkedHashMap<>();

            Set<String> nodesInDomain = faultDomainManager.getNodesInDomain(domainName);
            int totalNodes = nodesInDomain.size();
            int healthyNodes = faultDomainManager.countHealthyNodesInDomain(domainName);

            domainInfo.put("totalNodes", totalNodes);
            domainInfo.put("healthyNodes", healthyNodes);

            // 计算状态
            String status;
            if (totalNodes == 0) {
                status = "empty";
            } else if (healthyNodes == totalNodes) {
                status = "healthy";
            } else if (healthyNodes > 0) {
                status = "degraded";
            } else {
                status = "down";
            }
            domainInfo.put("status", status);

            // 添加节点详情
            Map<String, Boolean> nodeStatus = new LinkedHashMap<>();
            for (String nodeName : nodesInDomain) {
                nodeStatus.put(nodeName, s3Monitor.isNodeOnline(nodeName));
            }
            domainInfo.put("nodes", nodeStatus);

            domainHealth.put(domainName, domainInfo);
        }

        return Result.success(domainHealth);
    }

    @Override
    public Result<List<String>> getChunkLocations(String chunkHash) {
        if (chunkHash == null || chunkHash.isEmpty()) {
            return Result.error(ResultEnum.PARAM_IS_INVALID, null);
        }

        // 获取候选节点
        List<String> candidateNodes = faultDomainManager.getCandidateNodes(chunkHash);
        if (candidateNodes.isEmpty()) {
            return Result.success(Collections.emptyList());
        }

        // 验证对象实际存在于哪些节点
        List<String> actualLocations = new ArrayList<>();
        String objectPath = TenantContextUtil.buildTenantObjectPath(chunkHash);

        for (String nodeName : candidateNodes) {
            if (!s3Monitor.isNodeOnline(nodeName)) {
                continue;
            }

            S3Client client = clientManager.getClient(nodeName);
            if (client == null) {
                continue;
            }

            try {
                HeadObjectRequest headRequest = HeadObjectRequest.builder()
                        .bucket(nodeName)
                        .key(objectPath)
                        .build();
                client.headObject(headRequest);
                actualLocations.add(nodeName);
            } catch (NoSuchKeyException e) {
                // 对象不存在于此节点
                log.debug("分片 {} 不存在于节点 {}", chunkHash, nodeName);
            } catch (Exception e) {
                log.warn("检查分片 {} 在节点 {} 的位置时出错: {}", chunkHash, nodeName, e.getMessage());
            }
        }

        return Result.success(actualLocations);
    }

    @Override
    public Result<String> triggerRebalance(String targetDomain) {
        try {
            String taskId = rebalanceService.triggerManualRebalance(targetDomain);
            if (taskId == null) {
                return Result.error(ResultEnum.FILE_SERVICE_ERROR, "再平衡功能已禁用");
            }
            return Result.success(taskId);
        } catch (Exception e) {
            log.error("触发再平衡失败: {}", e.getMessage(), e);
            return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getRebalanceStatus() {
        try {
            RebalanceService.RebalanceStatus status = rebalanceService.getStatus();
            Map<String, Object> statusMap = new LinkedHashMap<>();

            statusMap.put("running", status.isRunning());
            statusMap.put("success", status.isSuccess());
            statusMap.put("type", status.getType() != null ? status.getType().name() : null);
            statusMap.put("triggerNode", status.getTriggerNode());
            statusMap.put("startTime", status.getStartTime() != null ? status.getStartTime().toString() : null);
            statusMap.put("endTime", status.getEndTime() != null ? status.getEndTime().toString() : null);
            statusMap.put("migratedCount", status.getMigratedCount().get());
            statusMap.put("failedCount", status.getFailedCount().get());
            statusMap.put("error", status.getError());

            return Result.success(statusMap);
        } catch (Exception e) {
            log.error("获取再平衡状态失败: {}", e.getMessage(), e);
            return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
        }
    }

    /**
     * 实现真正的"任一成功即返回"语义。
     * 与 CompletableFuture.anyOf 不同，此方法只有在两个都失败时才返回失败。
     * 当两个都失败时，使用 addSuppressed 保留两个异常信息。
     *
     * @param f1 第一个 Future
     * @param f2 第二个 Future
     * @return 第一个成功的结果，或者两个都失败时抛出异常（包含所有失败原因）
     */
    private CompletableFuture<String> firstSuccessOf(CompletableFuture<String> f1, CompletableFuture<String> f2) {
        CompletableFuture<String> result = new CompletableFuture<>();
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();

        BiConsumer<String, Throwable> handler = (success, failure) -> {
            if (failure == null && success != null) {
                // 任一成功立即返回（使用 complete 而非 completeExceptionally 确保幂等）
                result.complete(success);
            } else if (failure != null) {
                // 保存第一个失败的异常
                Throwable previous = firstFailure.getAndSet(failure);
                if (failureCount.incrementAndGet() == 2) {
                    // 两个都失败，合并异常信息
                    RuntimeException combined = new RuntimeException(
                            "Both upload tasks failed: [1] " + (previous != null ? previous.getMessage() : "unknown")
                            + " [2] " + failure.getMessage());
                    if (previous != null) {
                        combined.addSuppressed(previous);
                    }
                    combined.addSuppressed(failure);
                    result.completeExceptionally(combined);
                }
            }
            // 如果只有一个失败，等待另一个的结果
        };

        f1.whenComplete(handler);
        f2.whenComplete(handler);

        return result;
    }
}
