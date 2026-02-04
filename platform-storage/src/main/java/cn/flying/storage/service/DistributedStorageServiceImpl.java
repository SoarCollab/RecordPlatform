package cn.flying.storage.service;

import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.FaultDomainManager;
import cn.flying.storage.core.S3ClientManager;
import cn.flying.storage.core.S3Monitor;
import cn.flying.storage.tenant.TenantContextUtil;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.flying.platformapi.external.DistributedStorageService;
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

    //预签名链接有效期
    private final static Integer EXPIRY_HOURS = 24;

    // 最大允许直接加载到内存的文件大小（100MB）
    private static final long MAX_IN_MEMORY_FILE_SIZE = 100 * 1024 * 1024L;

    // 文件操作超时时间（秒）
    private static final int FILE_OPERATION_TIMEOUT_SECONDS = 300;

    // 分块读取缓冲区大小（8KB）
    private static final int BUFFER_SIZE = 8192;

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
            String tenantObjectPath = TenantContextUtil.buildTenantObjectPath(fileHash);

            // 创建所有节点的上传任务
            List<CompletableFuture<String>> uploadFutures = new ArrayList<>();
            for (String node : targetNodes) {
                uploadFutures.add(uploadToNodeAsyncWithResult(node, tenantObjectPath, fileData));
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

            // 异步检查其他上传任务的状态，失败则触发修复
            scheduleRepairIfNeededForDomains(uploadFutures, targetNodes, tenantObjectPath);

            // 如果是降级写入，记录以便后续同步
            boolean isDegraded = targetNodes.size() < requiredReplicas;
            if (isDegraded && degradedWriteConfig != null && degradedWriteConfig.isTrackForSync()) {
                Long tenantId = TenantContextUtil.getTenantIdOrDefault();
                degradedWriteTracker.recordDegradedWrite(fileHash, quorumResult.getSuccessNodes(), tenantId);
                return Result.success(logicalPath); // 降级成功仍返回 SUCCESS
            }

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
            return resultFuture.get(FILE_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.error("存储文件块仲裁超时（>{}s）: hash={}", FILE_OPERATION_TIMEOUT_SECONDS, fileHash);
            return new QuorumResult(false, successCount.get(),
                    new ArrayList<>(successNodes), new ArrayList<>(failedNodes));
        } catch (ExecutionException e) {
            log.error("存储文件块仲裁异常: hash={}", fileHash, e);
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
                                                   List<String> nodes, String objectPath) {
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
                        consistencyRepairService.scheduleImmediateRepairByNodes(objectPath, sourceNode, failedNode);
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
     * 异步上传并返回成功的节点名称
     */
    private CompletableFuture<String> uploadToNodeAsyncWithResult(String nodeName, String objectName, byte[] file) {
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
                    errors.add(fileHash + ": invalid chunk path format");
                    continue;
                }

                // 校验 fileHash 与路径中的 objectName 匹配
                if (!fileHash.equals(parsedPath.objectName())) {
                    errors.add(fileHash + ": hash mismatch with path");
                    continue;
                }

                // 获取分片存储的所有目标节点（基于一致性哈希）
                List<String> targetNodes = faultDomainManager.getCandidateNodes(fileHash);
                if (targetNodes.isEmpty()) {
                    errors.add(fileHash + ": no candidate nodes found");
                    continue;
                }

                // 构建对象路径
                String objectPath = String.format("tenant/%d/%s", parsedPath.tenantId(), parsedPath.objectName());

                // 从所有目标节点删除
                for (String nodeName : targetNodes) {
                    try {
                        deleteFromNode(nodeName, objectPath);
                    } catch (Exception e) {
                        log.warn("Failed to delete {} from node {}: {}", objectPath, nodeName, e.getMessage());
                    }
                }
                log.info("Deleted file chunk {} from nodes {}", fileHash, targetNodes);
            } catch (Exception e) {
                log.error("Failed to delete file {}: {}", fileHash, e.getMessage());
                errors.add(fileHash + ": " + e.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            log.warn("Some files failed to delete: {}", errors);
            return Result.error(ResultEnum.FILE_SERVICE_ERROR, false);
        }
        return Result.success(true);
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
        List<String> candidateNodes = faultDomainManager.getCandidateNodes(fileHash);
        if (candidateNodes.isEmpty()) {
            log.error("无法找到文件 '{}' 的候选存储节点", fileHash);
            return Optional.empty();
        }

        // 构建对象路径
        String objectPath = String.format("tenant/%d/%s", parsedPath.tenantId(), parsedPath.objectName());

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
        List<String> candidateNodes = faultDomainManager.getCandidateNodes(fileHash);
        if (candidateNodes.isEmpty()) {
            log.error("无法找到文件 '{}' 的候选存储节点", fileHash);
            return Optional.empty();
        }

        // 构建对象路径
        String objectPath = String.format("tenant/%d/%s", parsedPath.tenantId(), parsedPath.objectName());

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
     * 尝试从指定节点获取对象并写入临时文件
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
        String cacheKey = nodeName + ":" + bucketName;
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
