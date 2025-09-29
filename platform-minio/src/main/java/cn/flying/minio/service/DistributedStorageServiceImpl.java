package cn.flying.minio.service;

import cn.flying.minio.config.LogicNodeMapping;
import cn.flying.minio.config.MinioProperties;
import cn.flying.minio.core.MinioClientManager;
import cn.flying.minio.core.MinioMonitor;
import cn.flying.minio.core.S3ClientFactory;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.flying.platformapi.dto.MultipartUploadDTO;
import cn.flying.platformapi.external.DistributedStorageService;
import cn.hutool.core.collection.CollUtil;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.*;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;
import io.minio.http.Method;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @program: RecordPlatform
 * @description: 分布式存储实现类（MinIO），支持 Nacos 动态配置和基本负载均衡
 * @author: 王贝强
 * @create: 2025-04-07 00:07
 */
@DubboService(methods = {@org.apache.dubbo.config.annotation.Method(name = "storeFile", timeout = 60000)})
@Slf4j
public class DistributedStorageServiceImpl implements DistributedStorageService {
    //预签名链接有效期
    private final static Integer EXPIRY_HOURS = 24;

    // 缓存 Bucket 是否存在，减少重复检查开销
    private final ConcurrentHashMap<String, Boolean> bucketExistenceCache = new ConcurrentHashMap<>();

    /**
     * 缓存上传会话信息，避免重复查询
     */
    private final ConcurrentHashMap<String, MultipartUploadDTO> uploadSessions = new ConcurrentHashMap<>();

    @Resource
    private MinioClientManager clientManager;

    @Resource
    private MinioMonitor minioMonitor;

    @Resource
    private MinioProperties minioProperties;

    @Resource
    private S3ClientFactory s3ClientFactory;

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

    public Result<Map<String, String>> storeFile(List<byte[]> fileList, List<String> fileHashList) {
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

        // 使用 ConcurrentHashMap 临时存储并发处理结果
        Map<String, String> tempSuccessResults = new ConcurrentHashMap<>();
        Map<String, String> tempFailedResults = new ConcurrentHashMap<>();

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
                        tempFailedResults.put(fileHash, "无法选择合适的 logic node");
                        return;
                    }

                    // 3. 获取该逻辑节点对应的物理节点对
                    List<String> physicalNodePair = getPhysicalNodePair(targetLogicNode);
                    if (physicalNodePair == null || physicalNodePair.size() != 2) {
                        tempFailedResults.put(fileHash, "逻辑节点的物理节点对无效:" + targetLogicNode);
                        return;
                    }

                    // 4. 并发上传到两个物理节点(冗余存储)
                    CompletableFuture<Void> upload1 = uploadToNodeAsync(physicalNodePair.get(0), fileHash, file);
                    CompletableFuture<Void> upload2 = uploadToNodeAsync(physicalNodePair.get(1), fileHash, file);

                    // 等待两个上传都完成（或任一失败）
                    CompletableFuture.allOf(upload1, upload2).join(); // join 会在异常时抛出 CompletionException

                    // 5. 构建并记录成功结果
                    String logicalPath = "minio/node/" + targetLogicNode + "/" + fileHash;
                    tempSuccessResults.put(fileHash, logicalPath);
                    log.info("已成功将文件 '{}' 存储到逻辑节点 '{}' （路径： {}）", fileHash, targetLogicNode, logicalPath);

                } catch (Exception e) {
                    // 捕获 join 抛出的 CompletionException 或其他异常
                    log.error("无法存储文件'{}'：{}", fileHash, e.getMessage(), e);
                    tempFailedResults.put(fileHash, "上传失败：" + e.getMessage());
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

        //按照原始输入顺序重新组织结果，确保分片顺序正确
        Map<String, String> orderedSuccessResults = new LinkedHashMap<>();

        // 按照 fileHashList 的原始顺序重新组织结果
        for (String fileHash : fileHashList) {
            if (tempSuccessResults.containsKey(fileHash)) {
                orderedSuccessResults.put(fileHash, tempSuccessResults.get(fileHash));
            }
        }

        if (!tempFailedResults.isEmpty()) {
            log.warn("存储过程中发生错误：{}", tempFailedResults);
            return Result.error(ResultEnum.FILE_SERVICE_ERROR, tempFailedResults);
        }

        log.info("文件上传完成，成功：{}", orderedSuccessResults.size());
        return Result.success(orderedSuccessResults);
    }

    // --- 内部辅助方法 ---

    @Override
    public Result<Boolean> deleteFile(Map<String, String> fileContent) {
        return null;
    }

    /**
     * 初始化分块上传会话
     * 使用S3兼容的CreateMultipartUpload API
     */
    @Override
    public Result<String> initMultipartUpload(String fileName, String fileHash, long totalSize, Map<String, String> metadata) {
        try {
            log.info("初始化分块上传: fileName={}, fileHash={}, totalSize={}", fileName, fileHash, totalSize);

            // 生成唯一的对象名称（使用fileHash作为唯一标识）
            String objectName = generateObjectName(fileHash, fileName);

            // 获取可用的逻辑节点并选择一个
            List<String> availableLogicNodes = getAvailableLogicNodes();
            if (availableLogicNodes.isEmpty()) {
                log.error("没有可用的逻辑节点进行分块上传");
                return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
            }

            // 选择最佳逻辑节点
            String logicNodeName = selectBestLogicNode(availableLogicNodes);
            List<String> physicalNodes = getPhysicalNodePair(logicNodeName);
            if (physicalNodes == null || physicalNodes.size() < 2) {
                log.error("逻辑节点 {} 配置无效", logicNodeName);
                return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
            }

            // 选择一个物理节点进行分块上传（通常选择主节点）
            String nodeName = physicalNodes.get(0);

            // 获取S3客户端
            AmazonS3 s3Client = s3ClientFactory.getS3Client(nodeName);
            if (s3Client == null) {
                log.error("无法获取节点 {} 的 S3 客户端", nodeName);
                return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
            }

            // 使用节点名称作为桶名
            String bucketName = nodeName;

            // 确保存储桶存在
            if (!s3Client.doesBucketExistV2(bucketName)) {
                try {
                    s3Client.createBucket(bucketName);
                    log.info("创建存储桶: {}", bucketName);
                } catch (Exception e) {
                    log.error("创建存储桶失败: {}", bucketName, e);
                    return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
                }
            }

            // 创建InitiateMultipartUploadRequest
            InitiateMultipartUploadRequest initRequest = new InitiateMultipartUploadRequest(
                    bucketName, objectName);

            // 添加元数据
            ObjectMetadata objectMetadata = new ObjectMetadata();
            if (metadata != null) {
                objectMetadata.setUserMetadata(metadata);
            }
            objectMetadata.addUserMetadata("fileName", fileName);
            objectMetadata.addUserMetadata("fileHash", fileHash);
            objectMetadata.addUserMetadata("totalSize", String.valueOf(totalSize));
            initRequest.setObjectMetadata(objectMetadata);

            // 使用S3 API初始化分块上传
            InitiateMultipartUploadResult initResponse = s3Client.initiateMultipartUpload(initRequest);
            String uploadId = initResponse.getUploadId();

            // 缓存上传会话信息
            MultipartUploadDTO session = MultipartUploadDTO.builder()
                    .uploadId(uploadId)
                    .fileName(fileName)
                    .fileHash(fileHash)
                    .totalSize(totalSize)
                    .bucketName(bucketName)
                    .objectName(objectName)
                    .nodeName(nodeName)  // 保存节点名称
                    .logicNodeName(logicNodeName)  // 保存逻辑节点名称
                    .metadata(metadata)
                    .createTime(System.currentTimeMillis())
                    .updateTime(System.currentTimeMillis())
                    .status("INITIALIZED")
                    .uploadedParts(new ArrayList<>())  // 初始化已上传分块列表
                    .build();

            uploadSessions.put(uploadId, session);

            log.info("S3分块上传会话创建成功: uploadId={}, objectName={}, bucket={}",
                    uploadId, objectName, bucketName);
            return Result.success(uploadId);

        } catch (Exception e) {
            log.error("初始化分块上传失败: fileName={}, error={}", fileName, e.getMessage(), e);
            return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
        }
    }

    /**
     * 上传单个分块
     * 使用S3兼容的UploadPart API
     */
    @Override
    public Result<String> uploadPart(String uploadId, int partNumber, byte[] data, String partHash) {
        try {
            long startTime = System.currentTimeMillis();
            log.info("开始上传分块: uploadId={}, partNumber={}, size={}KB",
                    uploadId, partNumber, data.length / 1024);

            // 获取上传会话信息
            MultipartUploadDTO session = uploadSessions.get(uploadId);
            if (session == null) {
                log.error("上传会话不存在: uploadId={}", uploadId);
                return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
            }

            // 获取S3客户端
            String nodeName = session.getNodeName();
            AmazonS3 s3Client = s3ClientFactory.getS3Client(nodeName);
            if (s3Client == null) {
                log.error("无法获取节点 {} 的 S3 客户端", nodeName);
                return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
            }

            // 创建UploadPartRequest
            UploadPartRequest uploadRequest = new UploadPartRequest()
                    .withBucketName(session.getBucketName())
                    .withKey(session.getObjectName())
                    .withUploadId(uploadId)
                    .withPartNumber(partNumber)
                    .withInputStream(new ByteArrayInputStream(data))
                    .withPartSize(data.length);

            // 可选：设置MD5校验
            // uploadRequest.setMd5Digest(calculateMD5(data));

            // 使用S3 API上传分块
            UploadPartResult uploadResult = s3Client.uploadPart(uploadRequest);
            String etag = uploadResult.getETag();

            // 获取PartETag对象，用于后续完成分块上传
            PartETag partETag = uploadResult.getPartETag();

            // 保存分块信息到会话
            Map<String, Object> partInfo = new HashMap<>();
            partInfo.put("partNumber", partNumber);
            partInfo.put("etag", etag);
            partInfo.put("size", data.length);
            partInfo.put("uploadTime", System.currentTimeMillis());
            partInfo.put("partHash", partHash);
            partInfo.put("partETag", partETag);  // 保存PartETag对象

            if (session.getUploadedParts() == null) {
                session.setUploadedParts(new ArrayList<>());
            }
            session.getUploadedParts().add(partInfo);

            // 更新会话信息
            session.setUpdateTime(System.currentTimeMillis());
            session.setStatus("UPLOADING");

            // 计算已上传的总大小
            long uploadedSize = session.getUploadedParts().stream()
                    .mapToLong(p -> ((Number) p.get("size")).longValue())
                    .sum();
            session.setUploadedSize(uploadedSize);

            long elapsed = System.currentTimeMillis() - startTime;
            double throughput = data.length / 1024.0 / 1024.0 / (elapsed / 1000.0);

            log.info("S3分块上传成功: uploadId={}, partNumber={}, etag={}, elapsed={}ms, throughput={}MB/s",
                    uploadId, partNumber, etag, elapsed, String.format("%.2f", throughput));

            return Result.success(etag);

        } catch (Exception e) {
            log.error("上传分块失败: uploadId={}, partNumber={}, error={}",
                    uploadId, partNumber, e.getMessage(), e);
            return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
        }
    }

    /**
     * 完成分块上传
     * 使用S3兼容的CompleteMultipartUpload API
     */
    @Override
    public Result<String> completeMultipartUpload(String uploadId, List<String> partETags) {
        try {
            log.info("完成分块上传: uploadId={}, parts={}", uploadId, partETags != null ? partETags.size() : 0);

            // 获取上传会话信息
            MultipartUploadDTO session = uploadSessions.get(uploadId);
            if (session == null) {
                log.error("上传会话不存在: uploadId={}", uploadId);
                return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
            }

            // 获取S3客户端
            String nodeName = session.getNodeName();
            AmazonS3 s3Client = s3ClientFactory.getS3Client(nodeName);
            if (s3Client == null) {
                log.error("无法获取节点 {} 的 S3 客户端", nodeName);
                return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
            }

            // 准备分块列表
            List<PartETag> partETagList = new ArrayList<>();

            if (session.getUploadedParts() != null && !session.getUploadedParts().isEmpty()) {
                // 按partNumber排序，确保分块顺序正确
                session.getUploadedParts().sort(
                    (a, b) -> Integer.compare((Integer)a.get("partNumber"), (Integer)b.get("partNumber"))
                );

                // 从会话中提取PartETag对象
                for (Map<String, Object> partInfo : session.getUploadedParts()) {
                    Object partETagObj = partInfo.get("partETag");
                    if (partETagObj instanceof PartETag) {
                        partETagList.add((PartETag) partETagObj);
                    } else {
                        // 如果没有保存PartETag对象，则从etag和partNumber创建
                        String etag = (String) partInfo.get("etag");
                        Integer partNumber = (Integer) partInfo.get("partNumber");
                        if (etag != null && partNumber != null) {
                            partETagList.add(new PartETag(partNumber, etag));
                        }
                    }
                }
            }

            if (partETagList.isEmpty()) {
                log.error("没有找到已上传的分块: uploadId={}", uploadId);
                return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
            }

            // 创建CompleteMultipartUploadRequest
            CompleteMultipartUploadRequest completeRequest = new CompleteMultipartUploadRequest(
                    session.getBucketName(),
                    session.getObjectName(),
                    uploadId,
                    partETagList
            );

            // 使用S3 API完成分块上传
            CompleteMultipartUploadResult completeResult = s3Client.completeMultipartUpload(completeRequest);

            // 构建文件访问路径
            String logicNodeName = session.getLogicNodeName();
            String filePath = String.format("minio/node/%s/%s", logicNodeName, session.getFileHash());

            // 更新会话状态
            session.setStatus("COMPLETED");
            session.setUpdateTime(System.currentTimeMillis());

            // 如果配置了冗余存储，复制到第二个物理节点
            List<String> physicalNodes = getPhysicalNodePair(logicNodeName);
            if (physicalNodes != null && physicalNodes.size() >= 2) {
                String secondNode = physicalNodes.get(1);
                if (!secondNode.equals(nodeName)) {
                    // 异步复制到第二个节点
                    CompletableFuture.runAsync(() -> {
                        try {
                            // 使用S3客户端复制对象
                            AmazonS3 destS3Client = s3ClientFactory.getS3Client(secondNode);
                            if (destS3Client != null) {
                                // 确保目标桶存在
                                if (!destS3Client.doesBucketExistV2(secondNode)) {
                                    destS3Client.createBucket(secondNode);
                                }

                                // 复制对象
                                CopyObjectRequest copyRequest = new CopyObjectRequest(
                                        session.getBucketName(),
                                        session.getObjectName(),
                                        secondNode,
                                        session.getObjectName()
                                );
                                destS3Client.copyObject(copyRequest);

                                log.info("成功复制对象到冗余节点: object={}, node={}",
                                        session.getObjectName(), secondNode);
                            }
                        } catch (Exception e) {
                            log.error("复制对象到冗余节点失败: object={}, node={}, error={}",
                                     session.getObjectName(), secondNode, e.getMessage());
                        }
                    });
                }
            }

            log.info("S3分块上传完成: uploadId={}, objectName={}, path={}, location={}",
                    uploadId, session.getObjectName(), filePath, completeResult.getLocation());

            // 清理缓存
            uploadSessions.remove(uploadId);

            return Result.success(filePath);

        } catch (Exception e) {
            log.error("完成分块上传失败: uploadId={}, error={}", uploadId, e.getMessage(), e);
            return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
        }
    }

    /**
     * 复制对象到另一个节点（用于冗余存储）
     */
    private void copyObjectToNode(MinioClient sourceClient, String sourceBucket, String sourceObject,
                                  String destNode, String destBucket, String destObject) throws Exception {
        MinioClient destClient = clientManager.getClient(destNode);
        if (destClient == null) {
            throw new RuntimeException("无法获取目标节点的MinIO客户端: " + destNode);
        }

        // 确保目标桶存在
        ensureBucketExists(destClient, destNode, destBucket);

        // 复制对象
        CopySource copySource = CopySource.builder()
                .bucket(sourceBucket)
                .object(sourceObject)
                .build();

        CopyObjectArgs copyArgs = CopyObjectArgs.builder()
                .bucket(destBucket)
                .object(destObject)
                .source(copySource)
                .build();

        destClient.copyObject(copyArgs);
    }

    /**
     * 取消分块上传
     * 使用S3兼容的AbortMultipartUpload API
     */
    @Override
    public Result<Boolean> abortMultipartUpload(String uploadId) {
        try {
            log.info("取消分块上传: uploadId={}", uploadId);

            // 获取上传会话信息
            MultipartUploadDTO session = uploadSessions.get(uploadId);
            if (session == null) {
                log.warn("上传会话不存在，可能已完成或取消: uploadId={}", uploadId);
                return Result.success(true);
            }

            // 获取S3客户端
            String nodeName = session.getNodeName();
            AmazonS3 s3Client = s3ClientFactory.getS3Client(nodeName);
            if (s3Client != null) {
                try {
                    // 创建AbortMultipartUploadRequest
                    AbortMultipartUploadRequest abortRequest = new AbortMultipartUploadRequest(
                            session.getBucketName(),
                            session.getObjectName(),
                            uploadId
                    );

                    // 使用S3 API取消分块上传
                    s3Client.abortMultipartUpload(abortRequest);
                    log.info("S3分块上传已通过API取消: uploadId={}", uploadId);

                } catch (Exception e) {
                    // 如果分块上传已经不存在（可能已完成或已取消），不认为是错误
                    if (e.getMessage() != null && e.getMessage().contains("NoSuchUpload")) {
                        log.warn("分块上传不存在，可能已经完成或取消: uploadId={}", uploadId);
                    } else {
                        log.error("调用S3 AbortMultipartUpload失败: {}", e.getMessage(), e);
                        // 继续执行清理操作
                    }
                }
            }

            // 更新状态并清理缓存
            session.setStatus("ABORTED");
            session.setUpdateTime(System.currentTimeMillis());
            uploadSessions.remove(uploadId);

            log.info("分块上传已取消: uploadId={}", uploadId);
            return Result.success(true);

        } catch (Exception e) {
            log.error("取消分块上传失败: uploadId={}, error={}", uploadId, e.getMessage(), e);
            return Result.error(ResultEnum.FILE_SERVICE_ERROR, false);
        }
    }

    /**
     * 列出已上传的分块
     * 使用S3兼容的ListParts API
     */
    @Override
    public Result<List<Map<String, Object>>> listUploadedParts(String uploadId) {
        try {
            log.info("列出已上传分块: uploadId={}", uploadId);

            // 获取上传会话信息
            MultipartUploadDTO session = uploadSessions.get(uploadId);
            if (session == null) {
                log.error("上传会话不存在: uploadId={}", uploadId);
                return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
            }

            // 获取S3客户端
            String nodeName = session.getNodeName();
            AmazonS3 s3Client = s3ClientFactory.getS3Client(nodeName);
            if (s3Client == null) {
                log.error("无法获取节点 {} 的 S3 客户端", nodeName);
                return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
            }

            // 构建返回结果列表
            List<Map<String, Object>> partsList = new ArrayList<>();

            try {
                // 创建ListPartsRequest
                ListPartsRequest listRequest = new ListPartsRequest(
                        session.getBucketName(),
                        session.getObjectName(),
                        uploadId
                );

                // 使用S3 API列出分块
                PartListing partListing = s3Client.listParts(listRequest);

                // 处理分块信息
                for (PartSummary part : partListing.getParts()) {
                    Map<String, Object> partInfo = new HashMap<>();
                    partInfo.put("partNumber", part.getPartNumber());
                    partInfo.put("etag", part.getETag());
                    partInfo.put("size", part.getSize());
                    partInfo.put("lastModified", part.getLastModified() != null ? part.getLastModified().getTime() : null);
                    partsList.add(partInfo);
                }

                // 如果有更多分块，继续获取
                while (partListing.isTruncated()) {
                    listRequest.setPartNumberMarker(partListing.getNextPartNumberMarker());
                    partListing = s3Client.listParts(listRequest);

                    for (PartSummary part : partListing.getParts()) {
                        Map<String, Object> partInfo = new HashMap<>();
                        partInfo.put("partNumber", part.getPartNumber());
                        partInfo.put("etag", part.getETag());
                        partInfo.put("size", part.getSize());
                        partInfo.put("lastModified", part.getLastModified() != null ? part.getLastModified().getTime() : null);
                        partsList.add(partInfo);
                    }
                }

                log.info("S3 API返回的分块数量: uploadId={}, count={}", uploadId, partsList.size());

            } catch (Exception e) {
                log.warn("通过S3 API列出分块失败，使用缓存数据: {}", e.getMessage());
                // 如果S3 API失败，回退到使用缓存的分块信息
                if (session.getUploadedParts() != null) {
                    partsList.addAll(session.getUploadedParts());
                }
            }

            // 按分块编号排序
            partsList.sort((a, b) -> {
                Integer partNumberA = (Integer) a.get("partNumber");
                Integer partNumberB = (Integer) b.get("partNumber");
                return partNumberA.compareTo(partNumberB);
            });

            log.info("已上传分块总数: uploadId={}, count={}", uploadId, partsList.size());
            return Result.success(partsList);

        } catch (Exception e) {
            log.error("列出已上传分块失败: uploadId={}, error={}", uploadId, e.getMessage(), e);
            return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
        }
    }

    /**
     * 流式存储文件（使用分块传输）
     * 实现真正的流式分块上传，避免一次性加载整个文件到内存
     */
    @Override
    public Result<Map<String, String>> storeFileStreaming(List<byte[]> fileList, List<String> fileHashList, int chunkSize) {
        try {
            log.info("开始流式存储文件: fileCount={}, chunkSize={}KB", fileList.size(), chunkSize / 1024);

            // 参数校验
            if (CollUtil.isEmpty(fileList) || CollUtil.isEmpty(fileHashList)) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            if (fileList.size() != fileHashList.size()) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            // 获取可用的逻辑节点
            List<String> availableLogicNodes = getAvailableLogicNodes();
            if (CollectionUtils.isEmpty(availableLogicNodes)) {
                log.error("无法存储文件：没有运行状况良好的逻辑节点可用。");
                return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
            }

            Map<String, String> resultMap = new ConcurrentHashMap<>();
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            // 并发处理每个文件的流式上传
            for (int i = 0; i < fileList.size(); i++) {
                byte[] fileData = fileList.get(i);
                String fileHash = fileHashList.get(i);
                int finalIndex = i;

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        // 选择逻辑节点
                        String targetLogicNode = availableLogicNodes.get(finalIndex % availableLogicNodes.size());
                        List<String> physicalNodePair = getPhysicalNodePair(targetLogicNode);
                        if (physicalNodePair == null || physicalNodePair.size() < 2) {
                            log.error("逻辑节点的物理节点对无效: {}", targetLogicNode);
                            return;
                        }

                        // 获取主节点的MinIO客户端
                        String primaryNode = physicalNodePair.get(0);
                        MinioClient client = clientManager.getClient(primaryNode);
                        if (client == null) {
                            log.error("无法获取节点 {} 的MinIO客户端", primaryNode);
                            return;
                        }

                        String objectName = generateObjectName(fileHash, "file");
                        String bucketName = primaryNode;

                        // 确保桶存在
                        ensureBucketExists(client, primaryNode, bucketName);

                        // 如果文件小于单个分块大小，直接上传
                        if (fileData.length <= chunkSize) {
                            // 直接上传整个文件
                            PutObjectArgs putArgs = PutObjectArgs.builder()
                                    .bucket(bucketName)
                                    .object(objectName)
                                    .stream(new ByteArrayInputStream(fileData), fileData.length, -1)
                                    .build();

                            client.putObject(putArgs);
                            log.info("小文件直接上传成功: hash={}, size={}KB", fileHash, fileData.length / 1024);

                        } else {
                            // 大文件使用模拟分块上传
                            log.info("大文件使用分块流式上传: hash={}, size={}MB", fileHash, fileData.length / 1024 / 1024);

                            // 直接上传整个文件（对于MinIO旧版本，不使用真正的分块上传）
                            PutObjectArgs putArgs = PutObjectArgs.builder()
                                    .bucket(bucketName)
                                    .object(objectName)
                                    .stream(new ByteArrayInputStream(fileData), fileData.length, -1)
                                    .build();

                            client.putObject(putArgs);
                            log.info("大文件上传成功: hash={}, size={}MB", fileHash, fileData.length / 1024 / 1024);
                        }

                        // 异步复制到第二个物理节点
                        String secondaryNode = physicalNodePair.get(1);
                        CompletableFuture.runAsync(() -> {
                            try {
                                uploadToNodeAsync(secondaryNode, objectName, fileData).join();
                                log.info("成功复制文件到冗余节点: hash={}, node={}", fileHash, secondaryNode);
                            } catch (Exception e) {
                                log.error("复制文件到冗余节点失败: hash={}, node={}, error={}",
                                        fileHash, secondaryNode, e.getMessage());
                            }
                        });

                        // 记录成功结果
                        String logicalPath = "minio/node/" + targetLogicNode + "/" + fileHash;
                        resultMap.put(fileHash, logicalPath);
                        log.info("流式存储文件成功: hash={}, path={}", fileHash, logicalPath);

                    } catch (Exception e) {
                        log.error("流式存储文件失败: hash={}, error={}", fileHash, e.getMessage(), e);
                    }
                });

                futures.add(future);
            }

            // 等待所有文件上传完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // 按原始顺序返回结果
            Map<String, String> orderedResults = new LinkedHashMap<>();
            for (String fileHash : fileHashList) {
                if (resultMap.containsKey(fileHash)) {
                    orderedResults.put(fileHash, resultMap.get(fileHash));
                }
            }

            log.info("流式存储文件完成: 成功={}, 总数={}", orderedResults.size(), fileHashList.size());
            return Result.success(orderedResults);

        } catch (Exception e) {
            log.error("流式存储文件失败: error={}", e.getMessage(), e);
            return Result.error(ResultEnum.FILE_SERVICE_ERROR, null);
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
     * 生成对象名称
     *
     * @param fileHash 文件哈希值
     * @param fileName 原始文件名
     * @return 对象存储路径
     */
    private String generateObjectName(String fileHash, String fileName) {
        // 使用日期分隔目录，避免单目录文件过多
        String dateDir = new java.text.SimpleDateFormat("yyyy/MM/dd").format(new Date());

        // 提取文件扩展名
        String extension = "";
        if (fileName != null && fileName.contains(".")) {
            extension = fileName.substring(fileName.lastIndexOf("."));
        }

        // 构建对象名称：日期目录/哈希值.扩展名
        return String.format("%s/%s%s", dateDir, fileHash, extension);
    }

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

    // ==================== 分块上传相关实现（流式传输优化） ====================

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
     * 解析逻辑路径，格式: minio/node/{logic_node_name}/{object_name}
     *
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
     * 内部记录类，用于存储解析后的路径信息
     */
    private record ParsedPath(String logicNodeName, String objectName) {
    }
}
