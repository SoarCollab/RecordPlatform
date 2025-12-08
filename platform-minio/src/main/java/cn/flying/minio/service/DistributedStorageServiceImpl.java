package cn.flying.minio.service;

import cn.flying.minio.config.LogicNodeMapping;
import cn.flying.minio.config.MinioProperties;
import cn.flying.minio.core.MinioClientManager;
import cn.flying.minio.core.MinioMonitor;
import cn.flying.minio.tenant.TenantContextUtil;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.flying.platformapi.external.DistributedStorageService;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;
import io.minio.http.Method;
import jakarta.annotation.Resource;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * 分布式存储实现类（MinIO）v2.3.0
 * 支持 Nacos 动态配置、负载均衡和租户隔离。
 * 使用 firstSuccessOf 容错模式，任一副本写入成功即返回，异步修复失败副本。
 */
@DubboService(version = DistributedStorageService.VERSION)
public class DistributedStorageServiceImpl implements DistributedStorageService {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(DistributedStorageServiceImpl.class);
    @Resource
    private MinioClientManager clientManager;

    @Resource
    private MinioMonitor minioMonitor;

    @Resource
    private MinioProperties minioProperties;

    @Resource
    private ConsistencyRepairService consistencyRepairService;

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

    // 专用 I/O 线程池，用于文件上传操作（避免阻塞 ForkJoinPool.commonPool）
    private static final ExecutorService UPLOAD_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

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

        List<String> availableLogicNodes = getAvailableLogicNodes();
        if (CollectionUtils.isEmpty(availableLogicNodes)) {
            log.error("无法存储文件块：没有运行状况良好的逻辑节点可用。");
            return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
        }

        String targetLogicNode = selectBestLogicNode(availableLogicNodes);
        if (targetLogicNode == null) {
            log.error("无法选择合适的逻辑节点存储文件块: {}", fileHash);
            return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
        }

        List<String> physicalNodePair = getPhysicalNodePair(targetLogicNode);
        if (physicalNodePair == null || physicalNodePair.size() != 2) {
            log.error("逻辑节点的物理节点对无效: {}", targetLogicNode);
            return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
        }

        try {
            // 使用带租户隔离的对象路径
            String tenantObjectPath = TenantContextUtil.buildTenantObjectPath(fileHash);
            String node1 = physicalNodePair.get(0);
            String node2 = physicalNodePair.get(1);

            CompletableFuture<String> upload1 = uploadToNodeAsyncWithResult(node1, tenantObjectPath, fileData);
            CompletableFuture<String> upload2 = uploadToNodeAsyncWithResult(node2, tenantObjectPath, fileData);

            // 使用 firstSuccessOf 模式：任一成功即返回，两个都失败才返回错误
            CompletableFuture<String> firstSuccess = firstSuccessOf(upload1, upload2);

            try {
                // 等待任一上传成功
                String successNode = firstSuccess.get(FILE_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                // 使用租户隔离的逻辑路径
                String logicalPath = TenantContextUtil.buildTenantPath(targetLogicNode, fileHash);
                log.info("已成功将文件块 '{}' 存储到节点 '{}' (路径: {})", fileHash, successNode, logicalPath);

                // 异步检查另一个上传任务的状态，失败则触发修复
                scheduleRepairIfNeeded(upload1, upload2, successNode, node1, node2, targetLogicNode, tenantObjectPath);

                return Result.success(logicalPath);

            } catch (TimeoutException e) {
                // 超时时取消未完成的上传任务，避免资源浪费和数据不一致
                cancelIfNotDone(upload1, "upload1");
                cancelIfNotDone(upload2, "upload2");
                log.error("存储文件块超时（>{}s）: hash={}, logicNode={}", FILE_OPERATION_TIMEOUT_SECONDS, fileHash, targetLogicNode, e);
                return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("存储文件块被中断: hash={}, logicNode={}", fileHash, targetLogicNode, e);
            return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
        } catch (ExecutionException e) {
            // 两个副本都失败了
            log.error("存储文件块失败（两个副本都失败）: hash={}, logicNode={}, cause={}", fileHash, targetLogicNode, e.getCause() != null ? e.getCause().getMessage() : e.getMessage(), e);
            return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
        } catch (Exception e) {
            log.error("存储文件块 '{}' 失败: {}", fileHash, e.getMessage(), e);
            return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
        }
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
     * 检查另一个上传任务的状态，如果失败则触发修复。
     * 使用 whenComplete 回调，等两个任务都完成后再判断。
     */
    private void scheduleRepairIfNeeded(CompletableFuture<String> upload1, CompletableFuture<String> upload2,
                                         String successNode, String node1, String node2,
                                         String logicNodeName, String objectPath) {
        // 等待两个任务都完成后再检查（无论成功或失败）
        CompletableFuture.allOf(upload1, upload2).whenComplete((v, ex) -> {
            // 使用信号量限制并发修复检查数量
            if (!REPAIR_CHECK_SEMAPHORE.tryAcquire()) {
                log.warn("修复检查队列已满，跳过本次修复检查: object={}", objectPath);
                return;
            }

            try {
                boolean upload1Failed = upload1.isCompletedExceptionally();
                boolean upload2Failed = upload2.isCompletedExceptionally();

                if (upload1Failed && !upload2Failed) {
                    log.warn("节点 {} 上传失败，触发修复任务", node1);
                    consistencyRepairService.scheduleImmediateRepair(logicNodeName, objectPath, node2, node1);
                } else if (upload2Failed && !upload1Failed) {
                    log.warn("节点 {} 上传失败，触发修复任务", node2);
                    consistencyRepairService.scheduleImmediateRepair(logicNodeName, objectPath, node1, node2);
                } else if (!upload1Failed && !upload2Failed) {
                    log.debug("两个副本都成功写入，无需修复");
                }
                // 如果两个都失败，不会走到这里（firstSuccessOf 会抛出异常）
            } finally {
                REPAIR_CHECK_SEMAPHORE.release();
            }
        });
    }

    /**
     * 异步上传并返回成功的节点名称
     */
    private CompletableFuture<String> uploadToNodeAsyncWithResult(String nodeName, String objectName, byte[] file) {
        return CompletableFuture.supplyAsync(() -> {
            if (!minioMonitor.isNodeOnline(nodeName)) {
                throw new RuntimeException("Node '" + nodeName + "' is offline, cannot upload file '" + objectName + "'.");
            }
            MinioClient client = clientManager.getClient(nodeName);
            if (client == null) {
                throw new RuntimeException("Cannot get MinioClient for online node: " + nodeName);
            }

            try {
                // 确保 Bucket 存在
                ensureBucketExists(client, nodeName, nodeName);

                // 使用 ByteArrayInputStream 读取文件内容
                try (InputStream inputStream = new ByteArrayInputStream(file)) {
                    PutObjectArgs args = PutObjectArgs.builder()
                        .bucket(nodeName)
                        .object(objectName)
                        .stream(inputStream, file.length, -1)
                        .build();
                    client.putObject(args);
                    log.debug("已成功将'{}'上传到节点'{}'", objectName, nodeName);
                    return nodeName; // 返回成功的节点名称
                }

            } catch (Exception e) {
                log.error("将'{}'上传到节点'{}'时出错：{}", objectName, nodeName, e.getMessage());
                throw new RuntimeException("Upload of '" + objectName + "' to node '" + nodeName + "' failed: " + e.getMessage(), e);
            }
        }, UPLOAD_EXECUTOR);
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
                ParsedPath parsedPath = parseLogicalPath(filePath, fileHash);
                if (parsedPath == null) {
                    errors.add(fileHash + ": invalid path format");
                    continue;
                }

                List<String> physicalNodes = getPhysicalNodePair(parsedPath.logicNodeName);
                if (physicalNodes == null || physicalNodes.size() != 2) {
                    errors.add(fileHash + ": invalid physical node pair");
                    continue;
                }

                // Delete from both physical nodes
                for (String nodeName : physicalNodes) {
                    try {
                        deleteFromNode(nodeName, parsedPath.objectName);
                    } catch (Exception e) {
                        log.warn("Failed to delete {} from node {}: {}", parsedPath.objectName, nodeName, e.getMessage());
                    }
                }
                log.info("Deleted file {} from logical node {}", fileHash, parsedPath.logicNodeName);
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
        if (!minioMonitor.isNodeOnline(nodeName)) {
            log.warn("Node '{}' is offline, skipping delete for '{}'", nodeName, objectName);
            return;
        }
        MinioClient client = clientManager.getClient(nodeName);
        if (client == null) {
            log.warn("Cannot get MinioClient for node {}", nodeName);
            return;
        }

        RemoveObjectArgs args = RemoveObjectArgs.builder()
            .bucket(nodeName)
            .object(objectName)
            .build();
        client.removeObject(args);
        log.debug("Deleted object '{}' from node '{}'", objectName, nodeName);
    }

    // --- 内部辅助方法 ---

    /**
     * 获取当前所有配置的、且对应物理节点对都健康的逻辑节点名称列表
     */
    private List<String> getAvailableLogicNodes() {
        List<LogicNodeMapping> mappings = minioProperties.getLogicalMapping();
        if (CollectionUtils.isEmpty(mappings)) {
            log.warn("未在 MinioProperties 中配置逻辑节点映射");
            return List.of();
        }

        return mappings.stream()
            .filter(mapping -> {
                List<String> pair = mapping.getPhysicalNodePair();
                // 必须配置了两个物理节点，且这两个节点都必须在线
                return pair != null && pair.size() == 2 &&
                    minioMonitor.isNodeOnline(pair.get(0)) &&
                    minioMonitor.isNodeOnline(pair.get(1));
            })
            .map(LogicNodeMapping::getLogicNodeName)
            .collect(Collectors.toList());
    }

    /**
     * 根据逻辑节点名称从配置中获取物理节点对
     */
    private List<String> getPhysicalNodePair(String logicNodeName) {
        List<LogicNodeMapping> mappings = minioProperties.getLogicalMapping();
        if (CollectionUtils.isEmpty(mappings)) {
            return null;
        }
        return mappings.stream()
            .filter(m -> logicNodeName.equals(m.getLogicNodeName()))
            .findFirst()
            .map(LogicNodeMapping::getPhysicalNodePair)
            .orElse(null);
    }

    /**
     * 从可用逻辑节点列表中选择负载最低的一个
     */
    private String selectBestLogicNode(List<String> availableLogicNodes) {
        if (CollectionUtils.isEmpty(availableLogicNodes)) {
            return null;
        }

        String bestNode = null;
        double minScore = Double.MAX_VALUE;

        for (String logicNode : availableLogicNodes) {
            List<String> physicalPair = getPhysicalNodePair(logicNode);
            if (physicalPair == null || physicalPair.size() != 2) continue;

            // 计算逻辑节点的平均负载（或取最大值，取决于策略）
            double score1 = minioMonitor.getNodeLoadScore(physicalPair.get(0));
            double score2 = minioMonitor.getNodeLoadScore(physicalPair.get(1));
            // 如果任一物理节点分数无效，则跳过此逻辑节点
            if (score1 == Double.MAX_VALUE || score2 == Double.MAX_VALUE) continue;

            double averageScore = (score1 + score2) / 2.0;

            if (averageScore < minScore) {
                minScore = averageScore;
                bestNode = logicNode;
            }
        }

        if (bestNode == null) {
            log.warn("无法从 available：{} 中确定最佳逻辑节点,回退到随机选择", availableLogicNodes);
            //回退策略:随机选择一个
            if (!availableLogicNodes.isEmpty()) {
                return availableLogicNodes.get(new Random().nextInt(availableLogicNodes.size()));
            }
        }

        log.debug("选择得分为 {} 的最佳逻辑节点 '{}'", bestNode, minScore);
        return bestNode;
    }

    /**
     * 内部实现：根据文件路径和哈希获取文件
     *
     * @return Optional<File> 如果成功获取文件；Optional.empty() 如果尝试后未找到或节点不可用；
     * @throws RuntimeException 如果发生不可恢复的存储错误
     */
    private Optional<byte[]> getFileByHashInternal(String filePath, String fileHash) throws RuntimeException {
        ParsedPath parsedPath = parseLogicalPath(filePath, fileHash);
        if (parsedPath == null) {
            return Optional.empty(); // 解析失败，已记录日志
        }

        List<String> physicalNodes = getPhysicalNodePair(parsedPath.logicNodeName);
        if (physicalNodes == null || physicalNodes.size() != 2) {
            log.error("逻辑节点 {} 的物理节点对配置无效", parsedPath.logicNodeName);
            return Optional.empty();
        }

        // 优先选择负载低的节点
        String primaryNode = selectPrimaryNodeForRead(physicalNodes.get(0), physicalNodes.get(1));
        String secondaryNode = physicalNodes.get(0).equals(primaryNode) ? physicalNodes.get(1) : physicalNodes.get(0);

        // 尝试从主节点获取
        Optional<byte[]> fileOpt = tryGetObjectFromNode(primaryNode, parsedPath.objectName);
        if (fileOpt.isPresent()) {
            return fileOpt;
        }

        // 主节点失败，尝试从备用节点获取
        log.warn("无法从主节点 '{}' 获取文件 '{}'。正在尝试辅助节点 '{}'...",
            primaryNode, parsedPath.objectName, secondaryNode);
        fileOpt = tryGetObjectFromNode(secondaryNode, parsedPath.objectName);
        if (fileOpt.isPresent()) {
            return fileOpt;
        }

        // 两个节点都失败
        log.error("无法从逻辑节点 '{}' 的两个物理节点（{}、{}）获取文件 '{}'",
            parsedPath.logicNodeName, primaryNode, secondaryNode, parsedPath.objectName);
        //返回空
        return Optional.empty();
    }

    /**
     * 内部实现：获取文件的预签名下载 URL
     *
     * @return Optional<String> 如果成功；Optional.empty() 如果失败
     */
    private Optional<String> getPresignedUrlInternal(String filePath, String fileHash) {
        ParsedPath parsedPath = parseLogicalPath(filePath, fileHash);
        if (parsedPath == null) {
            return Optional.empty();
        }

        List<String> physicalNodes = getPhysicalNodePair(parsedPath.logicNodeName);
        if (physicalNodes == null || physicalNodes.size() != 2) {
            log.error("逻辑节点 {} 的物理节点对配置无效", parsedPath.logicNodeName);
            return Optional.empty();
        }

        // 优先选择负载低的节点
        String primaryNode = selectPrimaryNodeForRead(physicalNodes.get(0), physicalNodes.get(1));
        String secondaryNode = physicalNodes.get(0).equals(primaryNode) ? physicalNodes.get(1) : physicalNodes.get(0);

        // 尝试从主节点获取 URL
        Optional<String> urlOpt = tryGetResignedUrlFromNode(primaryNode, parsedPath.objectName);
        if (urlOpt.isPresent()) {
            return urlOpt;
        }

        // 主节点失败，尝试从备用节点获取
        log.warn("无法从主节点 '{}' 获取 '{}' 的预签名 URL。正在尝试辅助节点 '{}'...",
            primaryNode, parsedPath.objectName, secondaryNode);
        urlOpt = tryGetResignedUrlFromNode(secondaryNode, parsedPath.objectName);
        if (urlOpt.isPresent()) {
            return urlOpt;
        }

        log.error("无法从逻辑节点 '{}' 的两个物理节点（{}、{}）获取 '{}' 的预签名 URL",
            parsedPath.logicNodeName, primaryNode, secondaryNode, parsedPath.objectName);
        return Optional.empty();
    }

    /**
     * 解析逻辑路径，支持新旧两种格式：
     * 新格式: minio/tenant/{tenantId}/node/{logic_node_name}/{object_name}
     * 旧格式: minio/node/{logic_node_name}/{object_name}
     *
     * @return ParsedPath 对象，如果解析失败则返回 null
     */
    private ParsedPath parseLogicalPath(String filePath, String fileHash) {
        if (!StringUtils.hasText(filePath) || !StringUtils.hasText(fileHash)) {
            log.error("无法解析 null 或空 filePath/fileHash。");
            return null;
        }

        // 使用 TenantContextUtil 解析（支持新旧格式）
        TenantContextUtil.ParsedTenantPath parsed = TenantContextUtil.parseTenantPath(filePath);
        if (parsed == null) {
            log.error("无效的逻辑路径格式 {}，预期格式：minio/tenant/{tenantId}/node/{logicNode}/{objectName} 或 minio/node/{logicNode}/{objectName}", filePath);
            return null;
        }

        // 校验 objectName 是否与传入的 fileHash 匹配
        if (!fileHash.equals(parsed.objectName())) {
            log.error("路径[{}]中的fileHash'{}'和objectName'{}'不匹配", filePath, fileHash, parsed.objectName());
            return null;
        }

        // 构建带租户隔离的对象路径（用于在物理节点中查找）
        String tenantObjectPath = String.format("tenant/%d/%s", parsed.tenantId(), parsed.objectName());
        return new ParsedPath(parsed.logicNodeName(), tenantObjectPath);
    }

    /**
     * 内部记录类，用于存储解析后的路径信息
     */
    private record ParsedPath(String logicNodeName, String objectName) {
    }

    /**
     * 根据负载选择读取操作的主节点
     */
    private String selectPrimaryNodeForRead(String node1, String node2) {
        double score1 = minioMonitor.getNodeLoadScore(node1);
        double score2 = minioMonitor.getNodeLoadScore(node2);

        // 优先选择分数低的 (负载低)。如果分数相同或都无效，随机选一个
        if (score1 != Double.MAX_VALUE && (score1 < score2 || score2 == Double.MAX_VALUE)) {
            return node1;
        } else if (score2 != Double.MAX_VALUE) {
            return node2;
        } else {
            // 如果两个节点分数都无效（可能都不在线或监控异常），随机选一个尝试
            log.warn("两个节点（{}，{}）的负载分数都无效，随机选择一个尝试", node1, node2);
            return new Random().nextBoolean() ? node1 : node2;
        }
    }

    /**
     * 尝试从指定节点获取对象并写入临时文件
     */
    private Optional<byte[]> tryGetObjectFromNode(String nodeName, String objectName) {
        if (!minioMonitor.isNodeOnline(nodeName)) {
            log.warn("节点'{}'处于离线状态，无法获取对象'{}'", nodeName, objectName);
            return Optional.empty();
        }
        MinioClient client = clientManager.getClient(nodeName);
        if (client == null) {
            log.error("无法获取在线节点 {} 的 MinioClient", nodeName);
            return Optional.empty();
        }

        try {
            // 先获取对象大小，检查是否超过内存限制
            StatObjectArgs statArgs = StatObjectArgs.builder()
                .bucket(nodeName)
                .object(objectName)
                .build();
            StatObjectResponse stat = client.statObject(statArgs);
            long objectSize = stat.size();

            if (objectSize > MAX_IN_MEMORY_FILE_SIZE) {
                log.error("对象 '{}' 大小 ({} bytes) 超过内存限制 ({} bytes)，拒绝加载",
                    objectName, objectSize, MAX_IN_MEMORY_FILE_SIZE);
                throw new RuntimeException("文件过大，无法直接加载到内存");
            }

            GetObjectArgs args = GetObjectArgs.builder()
                .bucket(nodeName)
                .object(objectName)
                .build();

            // 使用分块读取避免一次性加载大文件
            try (InputStream inputStream = client.getObject(args);
                 ByteArrayOutputStream outputStream = new ByteArrayOutputStream((int) objectSize)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                long totalRead = 0;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
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
        } catch (MinioException e) {
            // 特别处理对象不存在的错误
            if (e instanceof ErrorResponseException && ((ErrorResponseException) e).errorResponse().code().equals("NoSuchKey")) {
                log.warn("在节点'{}'上找不到对象'{}'（NoSuchKey）", nodeName, objectName);
                return Optional.empty();
            } else {
                log.error("从节点'{}'获取对象'{}'时出现MinIO错误：{}", nodeName, objectName, e.getMessage(), e);
            }
        } catch (Exception e) {
            log.error("从节点'{}'获取对象'{}'时出现意外错误：{}", nodeName, objectName, e.getMessage(), e);
        }
        return Optional.empty();
    }

    /**
     * 尝试从指定节点获取预签名 URL
     */
    private Optional<String> tryGetResignedUrlFromNode(String nodeName, String objectName) {
        if (!minioMonitor.isNodeOnline(nodeName)) {
            log.warn("节点 '{}' 处于离线状态，无法获取 '{}' 的预签名URL", nodeName, objectName);
            return Optional.empty();
        }
        MinioClient client = clientManager.getClient(nodeName);
        if (client == null) {
            log.error("无法获取在线节点 '{}' 的MinioClient", nodeName);
            return Optional.empty();
        }

        try {
            // 使用节点名称作为桶名
            // 检查对象是否存在（可选，但可以避免为不存在的对象生成URL）
            try {
                StatObjectArgs statArgs = StatObjectArgs.builder()
                    .bucket(nodeName)
                    .object(objectName).build();
                client.statObject(statArgs);
            } catch (ErrorResponseException e) {
                if (e.errorResponse().code().equals("NoSuchKey")) {
                    log.warn("在节点 '{}' 上找不到对象 '{}'，无法生成预签名URL", nodeName, objectName);
                    return Optional.empty();
                } else {
                    throw e; // 重新抛出其他 MinIO 错误
                }
            }

            GetPresignedObjectUrlArgs args = GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(nodeName)
                .object(objectName)
                .expiry(DistributedStorageServiceImpl.EXPIRY_HOURS, TimeUnit.HOURS)
                .build();
            String url = client.getPresignedObjectUrl(args);
            log.info("从节点 '{}' 为对象 '{}' 成功生成预签名 URL", nodeName, objectName);
            return Optional.of(url);
        } catch (Exception e) {
            log.error("无法从节点 '{}' 为对象 '{}' 生成预签名 URL：{}", nodeName, objectName, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * 异步上传文件到单个 MinIO 节点 (从 File 对象读取)
     */
    private CompletableFuture<Void> uploadToNodeAsync(String nodeName, String objectName, byte[] file) {
        return CompletableFuture.runAsync(() -> {
            if (!minioMonitor.isNodeOnline(nodeName)) {
                throw new RuntimeException("Node '" + nodeName + "' is offline, cannot upload file '" + objectName + "'.");
            }
            MinioClient client = clientManager.getClient(nodeName);
            if (client == null) {
                throw new RuntimeException("Cannot get MinioClient for online node: " + nodeName);
            }

            try {
                // 使用节点名称作为桶名
                // 确保 Bucket 存在
                ensureBucketExists(client, nodeName, nodeName);

                // 使用 FileInputStream 读取文件内容
                try (InputStream inputStream = new ByteArrayInputStream(file)) {
                    PutObjectArgs args = PutObjectArgs.builder()
                        .bucket(nodeName)
                        .object(objectName)
                        .stream(inputStream, file.length, -1) // 使用文件长度
                        .build();
                    client.putObject(args);
                    log.debug("已成功将'{}'上传到节点'{}'", objectName, nodeName);
                } // try-with-resources 会自动关闭 inputStream

            } catch (Exception e) {
                log.error("将{}'上传到节点{}时出错：{}", objectName, nodeName, e.getMessage());
                // 包装成自定义异常，携带更多上下文信息
                throw new RuntimeException("Upload of '" + objectName + "' to node '" + nodeName + "' failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 确保指定的 Bucket 在给定的 MinIO 节点上存在
     */
    private void ensureBucketExists(MinioClient client, String nodeName, String bucketName) throws RuntimeException {
        String cacheKey = nodeName + ":" + bucketName;
        Boolean cached = bucketExistenceCache.getIfPresent(cacheKey);
        if (Boolean.TRUE.equals(cached)) {
            return;
        }

        try {
            BucketExistsArgs existsArgs = BucketExistsArgs.builder().bucket(bucketName).build();
            boolean exists = client.bucketExists(existsArgs);

            if (!exists) {
                log.warn("存储桶'{}'在节点'{}'上不存在,正在尝试创建...", bucketName, nodeName);
                try {
                    MakeBucketArgs makeArgs = MakeBucketArgs.builder().bucket(bucketName).build();
                    client.makeBucket(makeArgs);
                    log.info("在节点'{}'上成功创建存储桶'{}'", bucketName, nodeName);
                    bucketExistenceCache.put(cacheKey, true);
                } catch (Exception createError) {
                    log.error("无法在节点'{}'上创建存储桶'{}'：{}", bucketName, nodeName, createError.getMessage());
                    bucketExistenceCache.put(cacheKey, false);
                    throw new RuntimeException("Failed to create bucket '" + bucketName + "' on node '" + nodeName + "': " + createError.getMessage(), createError);
                }
            } else {
                bucketExistenceCache.put(cacheKey, true);
            }
        } catch (Exception checkError) {
            log.error("检查节点'{}'上的存储桶'{}'是否存在时出错：{}", nodeName, bucketName, checkError.getMessage());
            // 无法检查或创建 Bucket 是严重问题
            throw new RuntimeException("Failed to check/ensure bucket '" + bucketName + "' on node '" + nodeName + "': " + checkError.getMessage(), checkError);
        }
    }

    @Override
    public Result<Map<String, Boolean>> getClusterHealth() {
        Set<String> onlineNodes = minioMonitor.getOnlineNodes();
        Map<String, Boolean> nodeStatus = new LinkedHashMap<>();

        List<LogicNodeMapping> mappings = minioProperties.getLogicalMapping();
        if (!CollectionUtils.isEmpty(mappings)) {
            for (LogicNodeMapping mapping : mappings) {
                List<String> pair = mapping.getPhysicalNodePair();
                if (pair != null) {
                    for (String nodeName : pair) {
                        nodeStatus.put(nodeName, onlineNodes.contains(nodeName));
                    }
                }
            }
        }

        return Result.success(nodeStatus);
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
