package cn.flying.storage.service;

import cn.flying.storage.config.LogicNodeMapping;
import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.S3ClientManager;
import cn.flying.storage.core.S3Monitor;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * S3 副本一致性修复服务。
 * 定期扫描逻辑节点下的物理节点对，检测并修复单副本风险。
 * 当文件仅存在于一个物理节点时，自动复制到另一个节点。
 */
@Slf4j
@Service
public class ConsistencyRepairService {

    private static final String LOCK_KEY = "storage:consistency:repair";

    // 立即修复任务的并发限制
    private static final Semaphore IMMEDIATE_REPAIR_SEMAPHORE = new Semaphore(10);

    // 立即修复任务的最大重试次数
    private static final int IMMEDIATE_REPAIR_MAX_RETRIES = 3;

    // 重试基础退避时间（毫秒）
    private static final long RETRY_BASE_BACKOFF_MS = 1000;

    @Resource
    private S3ClientManager clientManager;

    @Resource
    private S3Monitor s3Monitor;

    @Resource
    private StorageProperties storageProperties;

    @Resource
    private RedissonClient redissonClient;

    @Value("${storage.consistency.repair.batch-size:100}")
    private int batchSize;

    @Value("${storage.consistency.repair.lock-timeout-seconds:600}")
    private long lockTimeoutSeconds;

    @Value("${storage.consistency.repair.enabled:true}")
    private boolean repairEnabled;

    /**
     * 定时执行副本一致性修复任务。
     * 每小时执行一次（可通过配置调整）。
     */
    @Scheduled(cron = "${storage.consistency.repair.cron:0 0 * * * ?}")
    public void scheduledRepair() {
        if (!repairEnabled) {
            log.debug("副本一致性修复任务已禁用");
            return;
        }

        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;

        try {
            // 尝试获取分布式锁，等待 0 秒，持有 lockTimeoutSeconds 秒
            acquired = lock.tryLock(0, lockTimeoutSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                log.info("副本一致性修复任务：其他实例正在执行，跳过本次执行");
                return;
            }

            log.info("开始执行存储副本一致性修复任务...");
            RepairStatistics stats = repairAllLogicNodes();
            log.info("副本一致性修复任务完成：检查逻辑节点数={}, 检查文件数={}, 修复文件数={}, 失败数={}",
                    stats.logicNodesChecked, stats.filesChecked, stats.filesRepaired, stats.failureCount);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("副本一致性修复任务被中断");
        } catch (Exception e) {
            log.error("副本一致性修复任务执行失败", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("副本一致性修复任务：已释放分布式锁");
            }
        }
    }

    /**
     * 修复所有逻辑节点的副本一致性。
     *
     * @return 修复统计信息
     */
    public RepairStatistics repairAllLogicNodes() {
        RepairStatistics stats = new RepairStatistics();

        List<LogicNodeMapping> mappings = storageProperties.getLogicalMapping();
        if (CollectionUtils.isEmpty(mappings)) {
            log.warn("未配置逻辑节点映射，跳过副本一致性修复");
            return stats;
        }

        for (LogicNodeMapping mapping : mappings) {
            String logicNodeName = mapping.getLogicNodeName();
            List<String> physicalPair = mapping.getPhysicalNodePair();

            if (physicalPair == null || physicalPair.size() != 2) {
                log.warn("逻辑节点 {} 的物理节点对配置无效，跳过", logicNodeName);
                continue;
            }

            String node1 = physicalPair.get(0);
            String node2 = physicalPair.get(1);

            // 检查两个物理节点是否都在线
            boolean node1Online = s3Monitor.isNodeOnline(node1);
            boolean node2Online = s3Monitor.isNodeOnline(node2);

            if (!node1Online || !node2Online) {
                log.info("逻辑节点 {} 的物理节点不全在线（{}={}, {}={}），跳过修复",
                        logicNodeName, node1, node1Online, node2, node2Online);
                continue;
            }

            try {
                RepairStatistics nodeStats = repairLogicNode(logicNodeName, node1, node2);
                stats.merge(nodeStats);
                stats.logicNodesChecked++;
            } catch (Exception e) {
                log.error("修复逻辑节点 {} 时发生错误: {}", logicNodeName, e.getMessage(), e);
                stats.failureCount++;
            }
        }

        return stats;
    }

    /**
     * 修复单个逻辑节点的副本一致性。
     *
     * @param logicNodeName 逻辑节点名称
     * @param node1         物理节点1名称
     * @param node2         物理节点2名称
     * @return 修复统计信息
     */
    private RepairStatistics repairLogicNode(String logicNodeName, String node1, String node2) {
        RepairStatistics stats = new RepairStatistics();
        log.debug("开始检查逻辑节点 {} 的副本一致性（物理节点：{}, {}）", logicNodeName, node1, node2);

        S3Client client1 = clientManager.getClient(node1);
        S3Client client2 = clientManager.getClient(node2);

        if (client1 == null || client2 == null) {
            log.error("无法获取物理节点的 S3 客户端：{} 或 {}", node1, node2);
            stats.failureCount++;
            return stats;
        }

        try {
            // 获取两个节点的对象列表
            Set<String> objects1 = listAllObjects(client1, node1);
            Set<String> objects2 = listAllObjects(client2, node2);

            stats.filesChecked = objects1.size() + objects2.size();

            // 找出仅存在于 node1 的对象
            Set<String> onlyInNode1 = new HashSet<>(objects1);
            onlyInNode1.removeAll(objects2);

            // 找出仅存在于 node2 的对象
            Set<String> onlyInNode2 = new HashSet<>(objects2);
            onlyInNode2.removeAll(objects1);

            if (onlyInNode1.isEmpty() && onlyInNode2.isEmpty()) {
                log.debug("逻辑节点 {} 的两个物理节点数据一致，无需修复", logicNodeName);
                return stats;
            }

            log.info("逻辑节点 {} 发现不一致：仅在 {} 存在 {} 个对象，仅在 {} 存在 {} 个对象",
                    logicNodeName, node1, onlyInNode1.size(), node2, onlyInNode2.size());

            // 从 node1 复制到 node2
            for (String objectName : onlyInNode1) {
                try {
                    copyObject(client1, node1, client2, node2, objectName);
                    stats.filesRepaired++;
                    log.debug("已将对象 {} 从 {} 复制到 {}", objectName, node1, node2);
                } catch (Exception e) {
                    log.error("复制对象 {} 从 {} 到 {} 失败: {}", objectName, node1, node2, e.getMessage());
                    stats.failureCount++;
                }
            }

            // 从 node2 复制到 node1
            for (String objectName : onlyInNode2) {
                try {
                    copyObject(client2, node2, client1, node1, objectName);
                    stats.filesRepaired++;
                    log.debug("已将对象 {} 从 {} 复制到 {}", objectName, node2, node1);
                } catch (Exception e) {
                    log.error("复制对象 {} 从 {} 到 {} 失败: {}", objectName, node2, node1, e.getMessage());
                    stats.failureCount++;
                }
            }

        } catch (Exception e) {
            log.error("检查逻辑节点 {} 的副本一致性时发生错误: {}", logicNodeName, e.getMessage(), e);
            stats.failureCount++;
        }

        return stats;
    }

    /**
     * 列出节点（桶）中的所有对象。
     *
     * @param client   S3 客户端
     * @param nodeName 节点名称（同时也是桶名）
     * @return 对象名称集合
     */
    private Set<String> listAllObjects(S3Client client, String nodeName) throws Exception {
        Set<String> objects = new HashSet<>();

        // 检查桶是否存在
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(nodeName).build());
        } catch (NoSuchBucketException e) {
            log.debug("节点 {} 的桶不存在，返回空集合", nodeName);
            return objects;
        }

        ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                .bucket(nodeName)
                .build();

        ListObjectsV2Response listResponse;
        do {
            listResponse = client.listObjectsV2(listRequest);
            for (S3Object s3Object : listResponse.contents()) {
                if (!s3Object.key().endsWith("/")) {
                    objects.add(s3Object.key());
                }
            }

            listRequest = listRequest.toBuilder()
                    .continuationToken(listResponse.nextContinuationToken())
                    .build();
        } while (listResponse.isTruncated());

        log.debug("节点 {} 中共有 {} 个对象", nodeName, objects.size());
        return objects;
    }

    /**
     * 将对象从源节点复制到目标节点。
     *
     * @param sourceClient 源 S3 客户端
     * @param sourceBucket 源桶名（节点名）
     * @param targetClient 目标 S3 客户端
     * @param targetBucket 目标桶名（节点名）
     * @param objectName   对象名称
     */
    private void copyObject(S3Client sourceClient, String sourceBucket,
                            S3Client targetClient, String targetBucket,
                            String objectName) throws Exception {
        // 确保目标桶存在
        try {
            targetClient.headBucket(HeadBucketRequest.builder().bucket(targetBucket).build());
        } catch (NoSuchBucketException e) {
            targetClient.createBucket(CreateBucketRequest.builder().bucket(targetBucket).build());
            log.info("在节点 {} 上创建了桶 {}", targetBucket, targetBucket);
        }

        // 获取源对象元数据
        HeadObjectRequest headRequest = HeadObjectRequest.builder()
                .bucket(sourceBucket)
                .key(objectName)
                .build();
        HeadObjectResponse headResponse = sourceClient.headObject(headRequest);

        // 下载并上传对象
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(sourceBucket)
                .key(objectName)
                .build();

        try (ResponseInputStream<GetObjectResponse> inputStream = sourceClient.getObject(getRequest)) {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(targetBucket)
                    .key(objectName)
                    .contentLength(headResponse.contentLength())
                    .contentType(headResponse.contentType())
                    .build();

            targetClient.putObject(putRequest, RequestBody.fromInputStream(inputStream, headResponse.contentLength()));
        }
    }

    /**
     * 手动触发副本一致性修复（用于管理和调试）。
     *
     * @return 修复统计信息
     */
    public RepairStatistics triggerManualRepair() {
        log.info("手动触发副本一致性修复...");
        return repairAllLogicNodes();
    }

    /**
     * 立即修复指定逻辑节点的单个对象。
     * 当 firstSuccessOf 上传策略中有一个节点失败时调用此方法。
     * 具备重试机制和并发限制。
     *
     * @param logicNodeName 逻辑节点名称
     * @param objectName    对象名称
     * @param sourceNode    成功上传的源节点
     * @param targetNode    需要修复的目标节点
     */
    public void scheduleImmediateRepair(String logicNodeName, String objectName,
                                         String sourceNode, String targetNode) {
        // 使用信号量限制并发修复任务数量
        if (!IMMEDIATE_REPAIR_SEMAPHORE.tryAcquire()) {
            log.warn("立即修复任务队列已满，跳过修复: object={}, source={}, target={}",
                    objectName, sourceNode, targetNode);
            return;
        }

        // 异步执行修复，不阻塞主流程
        Thread.startVirtualThread(() -> {
            try {
                executeRepairWithRetry(logicNodeName, objectName, sourceNode, targetNode);
            } finally {
                IMMEDIATE_REPAIR_SEMAPHORE.release();
            }
        });
    }

    /**
     * 执行带重试的修复操作
     */
    private void executeRepairWithRetry(String logicNodeName, String objectName,
                                          String sourceNode, String targetNode) {
        for (int attempt = 1; attempt <= IMMEDIATE_REPAIR_MAX_RETRIES; attempt++) {
            try {
                log.info("开始修复对象 {} 从 {} 到 {} (逻辑节点: {}, 尝试 {}/{})",
                        objectName, sourceNode, targetNode, logicNodeName, attempt, IMMEDIATE_REPAIR_MAX_RETRIES);

                // 检查节点在线状态
                if (!s3Monitor.isNodeOnline(sourceNode)) {
                    log.warn("源节点 {} 不在线，无法修复逻辑节点 {} 的对象 {}", sourceNode, logicNodeName, objectName);
                    return; // 源节点不在线，无法修复，不再重试
                }
                if (!s3Monitor.isNodeOnline(targetNode)) {
                    log.warn("目标节点 {} 不在线，等待后重试修复逻辑节点 {} 的对象 {}", targetNode, logicNodeName, objectName);
                    // 目标节点不在线，可以等待后重试（使用指数退避）
                    if (attempt < IMMEDIATE_REPAIR_MAX_RETRIES) {
                        long backoffMs = RETRY_BASE_BACKOFF_MS * (1L << (attempt - 1));
                        Thread.sleep(backoffMs);
                        continue;
                    }
                    return;
                }

                S3Client sourceClient = clientManager.getClient(sourceNode);
                S3Client targetClient = clientManager.getClient(targetNode);

                if (sourceClient == null || targetClient == null) {
                    log.error("无法获取 S3 客户端进行修复：逻辑节点={}, source={}, target={}",
                            logicNodeName, sourceNode, targetNode);
                    return; // 客户端获取失败，不再重试
                }

                copyObject(sourceClient, sourceNode, targetClient, targetNode, objectName);
                log.info("成功修复对象 {} 从 {} 到 {} (逻辑节点: {}, 尝试 {}/{})",
                        objectName, sourceNode, targetNode, logicNodeName, attempt, IMMEDIATE_REPAIR_MAX_RETRIES);
                return; // 成功，退出

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("修复任务被中断: 逻辑节点={}, object={}", logicNodeName, objectName);
                return;
            } catch (Exception e) {
                log.warn("修复对象 {} 从 {} 到 {} 失败 (逻辑节点: {}, 尝试 {}/{}): {}",
                        objectName, sourceNode, targetNode, logicNodeName, attempt, IMMEDIATE_REPAIR_MAX_RETRIES, e.getMessage());

                if (attempt < IMMEDIATE_REPAIR_MAX_RETRIES) {
                    try {
                        // 指数退避
                        long backoffMs = RETRY_BASE_BACKOFF_MS * (1L << (attempt - 1));
                        log.debug("等待 {}ms 后重试", backoffMs);
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } else {
                    log.error("修复对象 {} 从 {} 到 {} 最终失败（逻辑节点: {}），已达最大重试次数 {}",
                            objectName, sourceNode, targetNode, logicNodeName, IMMEDIATE_REPAIR_MAX_RETRIES);
                    // 可选：此处可以将失败任务记录到数据库，供后续人工或定时任务处理
                }
            }
        }
    }

    /**
     * 修复统计信息。
     */
    public static class RepairStatistics {
        public int logicNodesChecked = 0;
        public int filesChecked = 0;
        public int filesRepaired = 0;
        public int failureCount = 0;

        public void merge(RepairStatistics other) {
            this.filesChecked += other.filesChecked;
            this.filesRepaired += other.filesRepaired;
            this.failureCount += other.failureCount;
        }

        @Override
        public String toString() {
            return String.format("RepairStatistics{logicNodes=%d, checked=%d, repaired=%d, failures=%d}",
                    logicNodesChecked, filesChecked, filesRepaired, failureCount);
        }
    }
}
