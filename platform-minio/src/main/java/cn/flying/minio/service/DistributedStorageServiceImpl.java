package cn.flying.minio.service;

import cn.flying.minio.config.LogicNodeMapping;
import cn.flying.minio.config.MinioProperties;
import cn.flying.minio.core.MinioClientManager;
import cn.flying.minio.core.MinioMonitor;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.flying.platformapi.external.DistributedStorageService;
import io.minio.*;
import io.minio.errors.MinioException;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @program: RecordPlatform
 * @description: 分布式存储实现类（MinIO），支持 Nacos 动态配置和基本负载均衡
 * @author: flyingcoding
 * @create: 2025-04-07 00:07
 */
@DubboService(methods = {@org.apache.dubbo.config.annotation.Method(name = "storeFile", timeout = 60000)})
@Slf4j
public class DistributedStorageServiceImpl implements DistributedStorageService {
    @Resource
    private MinioClientManager clientManager;

    @Resource
    private MinioMonitor minioMonitor;

    @Resource
    private MinioProperties minioProperties;

    //预签名链接有效期
    private final static Integer EXPIRY_HOURS = 24;

    // 缓存 Bucket 是否存在，减少重复检查开销
    private final ConcurrentHashMap<String, Boolean> bucketExistenceCache = new ConcurrentHashMap<>();


    public Result<List<byte[]>> getFileListByHash(List<String> filePathList, List<String> fileHashList) {
        if (CollectionUtils.isEmpty(filePathList) || CollectionUtils.isEmpty(fileHashList)) {
            log.warn("获取文件列表时传入Hash列表为空");
            return Result.success(null);
        }
        if (filePathList.size() != fileHashList.size()) {
            log.error("filePathList 和 fileHashList 必须具有相同的大小。");
            return Result.error(ResultEnum.PARAM_IS_INVALID,null);
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
            return Result.error(ResultEnum.FILE_SERVICE_ERROR,result);
        }
        return Result.success(result);
    }

    @Override
    public Result<List<String>> getFileUrlListByHash(List<String> filePathList, List<String> fileHashList) {
        if (CollectionUtils.isEmpty(filePathList) || CollectionUtils.isEmpty(fileHashList)) {
            log.warn("获取文件列表时传入Hash列表为空");
            return Result.error(ResultEnum.PARAM_IS_INVALID,null);
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

    public Result<Map<String,String>> storeFile(List<byte[]> fileList, List<String> fileHashList) {
        if (CollectionUtils.isEmpty(fileList) || CollectionUtils.isEmpty(fileHashList)) {
            log.warn("storeFile调用时列表参数为空");
            return Result.error(ResultEnum.PARAM_IS_INVALID, null);
        }
        if (fileList.size() != fileHashList.size()) {
            log.error("fileList 和 fileHashList 必须具有相同的大小。");
            return Result.error(ResultEnum.PARAM_IS_INVALID, null);
        }

        // 1. 获取当前有效且健康的逻辑节点列表
        List<String> availableLogicNodes = getAvailableLogicNodes();
        if (CollectionUtils.isEmpty(availableLogicNodes)) {
            log.error("无法存储文件：没有运行状况良好的逻辑节点可用。");
            // 返回空 Map 表示全部失败
            return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
        }

        Map<String, String> successResults = new ConcurrentHashMap<>();
        Map<String, String> failedResults = new ConcurrentHashMap<>();

        // 使用 CompletableFuture 并发处理每个文件
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < fileList.size(); i++) {
            byte[] file = fileList.get(i);
            String fileHash = fileHashList.get(i);

            int finalI = i;
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    // 2. 按顺序选择逻辑节点
                    String targetLogicNode = availableLogicNodes.get(finalI % availableLogicNodes.size());
                    if (targetLogicNode == null) {
                        failedResults.put(fileHash, "无法选择合适的 logic node");
                        return;
                    }

                    // 3. 获取该逻辑节点对应的物理节点对
                    List<String> physicalNodePair = getPhysicalNodePair(targetLogicNode);
                    if (physicalNodePair == null || physicalNodePair.size() != 2) {
                        failedResults.put(fileHash, "逻辑节点的物理节点对无效:" + targetLogicNode);
                        return;
                    }

                    // 4. 并发上传到两个物理节点(冗余存储)
                    CompletableFuture<Void> upload1 = uploadToNodeAsync(physicalNodePair.get(0), fileHash, file);
                    CompletableFuture<Void> upload2 = uploadToNodeAsync(physicalNodePair.get(1), fileHash, file);

                    // 等待两个上传都完成（或任一失败）
                    CompletableFuture.allOf(upload1, upload2).join(); // join 会在异常时抛出 CompletionException

                    // 5. 构建并记录成功结果
                    String logicalPath = "minio/node/" + targetLogicNode + "/" + fileHash;
                    successResults.put(fileHash, logicalPath);
                    log.info("已成功将文件 '{}' 存储到逻辑节点 '{}' （路径： {}）", fileHash, targetLogicNode, logicalPath);

                } catch (Exception e) {
                    // 捕获 join 抛出的 CompletionException 或其他异常
                    log.error("无法存储文件'{}'：{}", fileHash, e.getMessage(), e);
                    failedResults.put(fileHash, "上传失败：" + e.getMessage());
                }
            }));
        }

        // 等待所有文件的处理完成
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            // 通常这里的异常已被内部捕获，但以防万一
            log.warn("某些文件存储任务可能意外失败：{}", e.getMessage());
        }

        if (!failedResults.isEmpty()) {
            log.warn("存储部分完成，失败消息如下：{}", failedResults);
            //返回失败消息
            return Result.error(ResultEnum.FILE_SERVICE_ERROR, successResults);
        }

        log.info("文件上传完成，成功：{}", successResults.size());
        return Result.success(successResults);
    }

    @Override
    public Result<Boolean> deleteFile(Map<String, String> fileContent) {
        return null;
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
             if (!availableLogicNodes.isEmpty()){
                 return availableLogicNodes.get(new Random().nextInt(availableLogicNodes.size()));
             }
        }

        log.debug("选择得分为 {} 的最佳逻辑节点 '{}'", bestNode, minScore);
        return bestNode;
    }

    /**
     * 内部实现：根据文件路径和哈希获取文件
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
        log.warn("无法从主节点“{}”获取文件“{}”。正在尝试辅助节点 '{}'...",
                 parsedPath.objectName, primaryNode, secondaryNode);
        fileOpt = tryGetObjectFromNode(secondaryNode, parsedPath.objectName);
        if (fileOpt.isPresent()) {
            return fileOpt;
        }

        // 两个节点都失败
        log.error("无法从逻辑节点“{}”的两个物理节点（{}、{}）获取文件“{}”",
                  parsedPath.objectName, primaryNode, secondaryNode, parsedPath.logicNodeName);
        //返回空
        return Optional.empty();
    }

    /**
     * 内部实现：获取文件的预签名下载 URL
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
        log.warn("无法从主节点“{}”获取“{}”的预签名 URL。正在尝试辅助节点'{}'...",
                 parsedPath.objectName, primaryNode, secondaryNode);
        urlOpt = tryGetResignedUrlFromNode(secondaryNode, parsedPath.objectName);
        if (urlOpt.isPresent()) {
            return urlOpt;
        }

        log.error("无法从逻辑节点“{}”的两个物理节点（{}、{}）获取“{}”的预签名 URL",
                  parsedPath.objectName, primaryNode, secondaryNode, parsedPath.logicNodeName);
        return Optional.empty();
    }

    /**
     * 解析逻辑路径，格式: minio/node/{logic_node_name}/{object_name}
     * @return ParsedPath 对象，如果解析失败则返回 null
     */
    private ParsedPath parseLogicalPath(String filePath, String fileHash) {
        if (!StringUtils.hasText(filePath) || !StringUtils.hasText(fileHash)) {
            log.error("无法解析 null 或空 filePath/fileHash。");
            return null;
        }
        String prefix = "minio/node/";
        if (!filePath.startsWith(prefix)) {
            log.error("无效的逻辑路径格式 {}，预期格式：minio/node/logic_node_name/object_name", filePath);
            return null;
        }
        String remainingPath = filePath.substring(prefix.length());
        int lastSlashIndex = remainingPath.lastIndexOf('/');
        if (lastSlashIndex <= 0 || lastSlashIndex == remainingPath.length() - 1) {
            log.error("无效的逻辑路径格式 {}，无法提取逻辑节点名称和文件名称", filePath);
            return null;
        }
        String logicNodeName = remainingPath.substring(0, lastSlashIndex);
        String objectName = remainingPath.substring(lastSlashIndex + 1);

        // 校验 objectName 是否与传入的 fileHash 匹配
        if (!fileHash.equals(objectName)) {
            log.error("路径[{}]中的fileHash'{}'和objectName'{}'不匹配", fileHash, objectName, filePath);
            return null;
        }

        return new ParsedPath(logicNodeName, objectName);
    }

    /**
     * 内部记录类，用于存储解析后的路径信息
     */
    private record ParsedPath(String logicNodeName, String objectName) {}

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

        File tempFile = null;
        try {
            // 使用节点名称作为桶名

            GetObjectArgs args = GetObjectArgs.builder()
                    .bucket(nodeName)
                    .object(objectName)
                    .build();
            try (InputStream inputStream = client.getObject(args)) {
                byte[] fileBytes = IOUtils.toByteArray(inputStream);
                log.info("已成功将对象“{}”从节点“{}”读取到服务器", objectName, nodeName);
                return Optional.of(fileBytes);
            } // try-with-resources 会自动关闭 inputStream
        } catch (MinioException e) {
            // 特别处理对象不存在的错误
            if (e instanceof ErrorResponseException && ((ErrorResponseException) e).errorResponse().code().equals("NoSuchKey")) {
                 log.warn("在节点'{}'上找不到对象'{}'（NoSuchKey）", objectName, nodeName);
                 // 不认为是严重错误，返回 empty
                 return Optional.empty();
            } else {
                log.error("从节点'{}'获取对象'{}'时出现MinIO错误：{}", objectName, nodeName, e.getMessage(), e);
            }
        } catch (Exception e) {
            log.error("从节点'{}'获取对象'{}'时出现意外错误：{}", objectName, nodeName, e.getMessage(), e);
        }
        return Optional.empty(); // 获取失败
    }

    /**
     * 尝试从指定节点获取预签名 URL
     */
    private Optional<String> tryGetResignedUrlFromNode(String nodeName, String objectName) {
        if (!minioMonitor.isNodeOnline(nodeName)) {
            log.warn("节点“{}”处于离线状态，无法获取“{}”的预签名URL", nodeName, objectName);
            return Optional.empty();
        }
        MinioClient client = clientManager.getClient(nodeName);
        if (client == null) {
            log.error("无法获取在线节点‘{}’的MinioClient", nodeName);
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
                    log.warn("在节点“{}”上找不到对象“{}”，无法生成预签名URL", objectName, nodeName);
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
            log.info("从节点“{}”为对象“{}”成功生成预签名 URL", nodeName, objectName);
            return Optional.of(url);
        } catch (Exception e) {
            log.error("无法从节点“{}”为对象“{}”生成预签名 URL：{}", nodeName, objectName, e.getMessage(), e);
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
        if (Boolean.TRUE.equals(bucketExistenceCache.get(cacheKey))) {
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
            log.error("检查节点“{}”上“{}”的存储桶是否存在时出错：{}", bucketName, nodeName, checkError.getMessage());
            // 无法检查或创建 Bucket 是严重问题
            throw new RuntimeException("Failed to check/ensure bucket '" + bucketName + "' on node '" + nodeName + "': " + checkError.getMessage(), checkError);
        }
    }
}
