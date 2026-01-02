package cn.flying.storage.service;

import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.FaultDomainManager;
import cn.flying.storage.core.S3ClientManager;
import cn.flying.storage.core.S3Monitor;
import cn.flying.storage.event.NodeTopologyChangeEvent;
import com.google.common.util.concurrent.RateLimiter;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 数据再平衡服务
 * 当节点拓扑发生变化时，负责将数据迁移到正确的位置
 *
 * <p>支持多活跃域配置，动态处理任意数量的故障域
 */
@Slf4j
@Service
public class RebalanceService {

    private static final String REBALANCE_LOCK_KEY = "storage:rebalance:lock";
    private static final int BUFFER_SIZE = 8192;
    // 最大允许直接加载到内存的文件大小（100MB）
    private static final long MAX_IN_MEMORY_FILE_SIZE = 100 * 1024 * 1024L;

    @Resource
    private FaultDomainManager faultDomainManager;

    @Resource
    private S3ClientManager clientManager;

    @Resource
    private S3Monitor s3Monitor;

    @Resource
    private RedissonClient redissonClient;

    @Value("${storage.rebalance.rate-limit-per-second:10}")
    private int rateLimitPerSecond;

    @Value("${storage.rebalance.enabled:true}")
    private boolean rebalanceEnabled;

    private volatile RebalanceStatus currentStatus = new RebalanceStatus();

    /**
     * 监听节点拓扑变更事件
     */
    @EventListener
    @Async
    public void onTopologyChange(NodeTopologyChangeEvent event) {
        if (!rebalanceEnabled) {
            log.info("再平衡功能已禁用，忽略拓扑变更事件: {}", event);
            return;
        }

        log.info("收到节点拓扑变更事件: {}", event);

        switch (event.getChangeType()) {
            case NODE_ADDED, NODE_ONLINE -> {
                // 新节点上线，可能需要将数据迁移到新节点
                log.info("节点 {} 上线，将在下次调度时考虑数据再平衡", event.getNodeName());
            }
            case NODE_REMOVED, NODE_OFFLINE -> {
                // 节点下线，需要将数据从该节点迁移到其他节点
                log.warn("节点 {} 下线，将触发数据迁移", event.getNodeName());
                scheduleRebalance(event.getNodeName(), RebalanceType.MIGRATE_FROM_FAILED);
            }
            case NODE_DOMAIN_CHANGED -> {
                // 节点域变更（备用提升），需要复制数据到新节点
                log.info("节点 {} 域变更到 {}，将触发数据复制", event.getNodeName(), event.getFaultDomain());
                scheduleRebalance(event.getNodeName(), RebalanceType.COPY_TO_PROMOTED);
            }
        }
    }

    /**
     * 调度再平衡任务
     */
    public void scheduleRebalance(String triggerNode, RebalanceType type) {
        // 使用分布式锁确保只有一个实例执行再平衡
        RLock lock = redissonClient.getLock(REBALANCE_LOCK_KEY);

        try {
            if (!lock.tryLock(0, 3600, TimeUnit.SECONDS)) {
                log.info("再平衡任务已在执行中，跳过本次调度");
                return;
            }

            log.info("开始执行再平衡任务: 触发节点={}, 类型={}", triggerNode, type);
            executeRebalance(triggerNode, type);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("再平衡任务被中断", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 执行再平衡
     */
    private void executeRebalance(String triggerNode, RebalanceType type) {
        currentStatus = new RebalanceStatus();
        currentStatus.setStartTime(Instant.now());
        currentStatus.setType(type);
        currentStatus.setTriggerNode(triggerNode);
        currentStatus.setRunning(true);

        RateLimiter rateLimiter = RateLimiter.create(rateLimitPerSecond);

        try {
            switch (type) {
                case MIGRATE_FROM_FAILED -> migrateFromFailedNode(triggerNode, rateLimiter);
                case COPY_TO_PROMOTED -> copyToPromotedNode(triggerNode, rateLimiter);
                case REBALANCE_TO_NEW -> rebalanceToNewNode(triggerNode, rateLimiter);
            }

            currentStatus.setSuccess(true);
            log.info("再平衡任务完成: 迁移={}, 失败={}", currentStatus.getMigratedCount(), currentStatus.getFailedCount());

        } catch (Exception e) {
            currentStatus.setSuccess(false);
            currentStatus.setError(e.getMessage());
            log.error("再平衡任务失败", e);
        } finally {
            currentStatus.setEndTime(Instant.now());
            currentStatus.setRunning(false);
        }
    }

    /**
     * 从失败节点迁移数据
     * 遍历该节点上的对象，从其他副本复制到新的目标节点
     */
    private void migrateFromFailedNode(String failedNode, RateLimiter rateLimiter) {
        log.info("开始从失败节点 {} 迁移数据", failedNode);

        // 1. 确定失败节点所在的故障域
        String failedDomain = faultDomainManager.getNodeDomain(failedNode);
        if (failedDomain == null) {
            log.warn("无法确定失败节点 {} 的故障域，跳过迁移", failedNode);
            return;
        }

        // 2. 获取所有其他活跃域（不仅仅是 A/B 切换）
        List<String> otherDomains = faultDomainManager.getActiveDomains().stream()
                .filter(d -> !d.equals(failedDomain))
                .toList();

        if (otherDomains.isEmpty()) {
            log.error("没有其他活跃域可用于数据恢复");
            return;
        }

        // 3. 从所有其他域收集健康节点
        List<String> sourceNodes = new ArrayList<>();
        for (String domain : otherDomains) {
            sourceNodes.addAll(faultDomainManager.getHealthyNodesInDomainList(domain));
        }

        if (sourceNodes.isEmpty()) {
            log.error("其他域没有健康节点，无法执行迁移");
            return;
        }

        // 4. 遍历源节点的对象，迁移到失败域的新目标节点
        for (String sourceNode : sourceNodes) {
            try {
                Set<String> objects = listAllObjects(sourceNode);
                log.info("从源节点 {} 获取到 {} 个对象", sourceNode, objects.size());

                for (String objectPath : objects) {
                    rateLimiter.acquire();

                    // 使用一致性哈希确定该对象在失败域的新目标节点
                    String chunkHash = extractHashFromPath(objectPath);
                    if (chunkHash == null) {
                        continue;
                    }

                    String newTargetNode = faultDomainManager.getTargetNodeInDomain(chunkHash, failedDomain);

                    // 如果新目标节点健康且不是失败节点，执行复制
                    if (newTargetNode != null && !newTargetNode.equals(failedNode)
                            && s3Monitor.isNodeOnline(newTargetNode)) {
                        boolean success = copyObject(sourceNode, newTargetNode, objectPath);
                        if (success) {
                            currentStatus.getMigratedCount().incrementAndGet();
                        } else {
                            currentStatus.getFailedCount().incrementAndGet();
                        }
                    }
                }
            } catch (Exception e) {
                log.error("从源节点 {} 迁移数据时发生错误: {}", sourceNode, e.getMessage(), e);
                currentStatus.getFailedCount().incrementAndGet();
            }
        }

        log.info("从失败节点 {} 迁移数据完成", failedNode);
    }

    /**
     * 复制数据到提升后的节点
     */
    private void copyToPromotedNode(String promotedNode, RateLimiter rateLimiter) {
        log.info("开始复制数据到提升后的节点 {}", promotedNode);

        // 1. 获取提升节点的新故障域
        String targetDomain = faultDomainManager.getNodeDomain(promotedNode);
        if (targetDomain == null) {
            log.warn("无法确定提升节点 {} 的故障域，跳过复制", promotedNode);
            return;
        }

        // 2. 获取所有其他活跃域作为数据源
        List<String> sourceDomains = faultDomainManager.getActiveDomains().stream()
                .filter(d -> !d.equals(targetDomain))
                .toList();

        if (sourceDomains.isEmpty()) {
            log.warn("没有其他活跃域作为数据源");
            return;
        }

        // 3. 从所有源域获取健康节点
        List<String> sourceNodes = new ArrayList<>();
        for (String domain : sourceDomains) {
            sourceNodes.addAll(faultDomainManager.getHealthyNodesInDomainList(domain));
        }

        if (sourceNodes.isEmpty()) {
            log.error("源域没有健康节点，无法执行复制");
            return;
        }

        // 4. 遍历对象，确定哪些应该复制到提升节点
        for (String sourceNode : sourceNodes) {
            try {
                Set<String> objects = listAllObjects(sourceNode);
                log.info("从源节点 {} 获取到 {} 个对象", sourceNode, objects.size());

                for (String objectPath : objects) {
                    rateLimiter.acquire();

                    String chunkHash = extractHashFromPath(objectPath);
                    if (chunkHash == null) {
                        continue;
                    }

                    String targetNode = faultDomainManager.getTargetNodeInDomain(chunkHash, targetDomain);

                    // 如果该对象应该存储在提升节点上
                    if (promotedNode.equals(targetNode)) {
                        boolean success = copyObject(sourceNode, promotedNode, objectPath);
                        if (success) {
                            currentStatus.getMigratedCount().incrementAndGet();
                        } else {
                            currentStatus.getFailedCount().incrementAndGet();
                        }
                    }
                }
            } catch (Exception e) {
                log.error("从源节点 {} 复制数据时发生错误: {}", sourceNode, e.getMessage(), e);
                currentStatus.getFailedCount().incrementAndGet();
            }
        }

        log.info("复制数据到提升后的节点 {} 完成", promotedNode);
    }

    /**
     * 再平衡数据到新节点
     */
    private void rebalanceToNewNode(String newNode, RateLimiter rateLimiter) {
        log.info("开始再平衡数据到新节点 {}", newNode);

        // 1. 获取新节点所在域
        String domain = faultDomainManager.getNodeDomain(newNode);
        if (domain == null) {
            log.warn("无法确定新节点 {} 的故障域，跳过再平衡", newNode);
            return;
        }

        // 2. 获取该域的其他节点
        List<String> otherNodesInDomain = faultDomainManager.getHealthyNodesInDomainList(domain)
                .stream().filter(n -> !n.equals(newNode)).toList();

        if (otherNodesInDomain.isEmpty()) {
            log.info("域 {} 中没有其他节点，无需再平衡", domain);
            return;
        }

        // 3. 遍历其他节点的对象，检查是否需要迁移到新节点
        for (String sourceNode : otherNodesInDomain) {
            try {
                Set<String> objects = listAllObjects(sourceNode);
                log.info("从源节点 {} 获取到 {} 个对象", sourceNode, objects.size());

                for (String objectPath : objects) {
                    rateLimiter.acquire();

                    String chunkHash = extractHashFromPath(objectPath);
                    if (chunkHash == null) {
                        continue;
                    }

                    String targetNode = faultDomainManager.getTargetNodeInDomain(chunkHash, domain);

                    // 如果一致性哈希指向新节点，执行迁移
                    if (newNode.equals(targetNode)) {
                        boolean success = copyObject(sourceNode, newNode, objectPath);
                        if (success) {
                            currentStatus.getMigratedCount().incrementAndGet();
                            // 可选：从源节点删除（取决于策略，这里暂不删除以保证数据安全）
                            // deleteObject(sourceNode, objectPath);
                        } else {
                            currentStatus.getFailedCount().incrementAndGet();
                        }
                    }
                }
            } catch (Exception e) {
                log.error("从源节点 {} 再平衡数据时发生错误: {}", sourceNode, e.getMessage(), e);
                currentStatus.getFailedCount().incrementAndGet();
            }
        }

        log.info("再平衡数据到新节点 {} 完成", newNode);
    }

    /**
     * 从对象路径中提取哈希值
     * 路径格式: tenant/{tenantId}/{objectName}
     */
    private String extractHashFromPath(String objectPath) {
        if (objectPath == null || objectPath.isEmpty()) {
            return null;
        }
        // 获取路径的最后一部分作为哈希值
        int lastSlash = objectPath.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < objectPath.length() - 1) {
            return objectPath.substring(lastSlash + 1);
        }
        return objectPath;
    }

    /**
     * 列出节点（桶）中的所有对象
     */
    private Set<String> listAllObjects(String nodeName) throws Exception {
        Set<String> objects = new HashSet<>();

        S3Client client = clientManager.getClient(nodeName);
        if (client == null) {
            log.warn("无法获取节点 {} 的 S3 客户端", nodeName);
            return objects;
        }

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

        return objects;
    }

    /**
     * 复制单个对象
     */
    public boolean copyObject(String sourceNode, String targetNode, String objectPath) {
        try {
            S3Client sourceClient = clientManager.getClient(sourceNode);
            S3Client targetClient = clientManager.getClient(targetNode);

            if (sourceClient == null || targetClient == null) {
                log.error("无法获取客户端: source={}, target={}", sourceNode, targetNode);
                return false;
            }

            // 确保目标桶存在
            ensureBucketExists(targetClient, targetNode);

            // 先检查对象大小
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(sourceNode)
                    .key(objectPath)
                    .build();
            HeadObjectResponse headResponse = sourceClient.headObject(headRequest);
            long objectSize = headResponse.contentLength();

            if (objectSize > MAX_IN_MEMORY_FILE_SIZE) {
                log.warn("对象 {} 大小 ({} bytes) 超过内存限制 ({} bytes)，跳过复制",
                        objectPath, objectSize, MAX_IN_MEMORY_FILE_SIZE);
                return false;
            }

            // 从源节点读取
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(sourceNode)
                    .key(objectPath)
                    .build();

            try (ResponseInputStream<GetObjectResponse> response = sourceClient.getObject(getRequest)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream((int) objectSize);
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                long totalRead = 0;
                while ((bytesRead = response.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                    // 安全检查：防止读取超过预期大小
                    if (totalRead > MAX_IN_MEMORY_FILE_SIZE) {
                        log.error("读取对象 {} 时超过内存限制，已读取 {} bytes", objectPath, totalRead);
                        return false;
                    }
                }

                byte[] data = baos.toByteArray();

                // 写入目标节点
                PutObjectRequest putRequest = PutObjectRequest.builder()
                        .bucket(targetNode)
                        .key(objectPath)
                        .contentLength((long) data.length)
                        .build();

                targetClient.putObject(putRequest, RequestBody.fromBytes(data));

                log.debug("成功复制对象 {} 从 {} 到 {}", objectPath, sourceNode, targetNode);
                return true;
            }

        } catch (NoSuchKeyException e) {
            log.warn("源对象不存在: {} 在节点 {}", objectPath, sourceNode);
            return false;
        } catch (Exception e) {
            log.error("复制对象失败: {} 从 {} 到 {}", objectPath, sourceNode, targetNode, e);
            return false;
        }
    }

    /**
     * 确保桶存在
     */
    private void ensureBucketExists(S3Client client, String bucketName) {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
        } catch (NoSuchBucketException e) {
            log.info("桶 {} 不存在，正在创建...", bucketName);
            client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
        }
    }

    /**
     * 手动触发再平衡（用于 API 调用）
     *
     * @param targetDomain 目标域（可选，null 表示全部域）
     * @return 任务 ID（当前使用触发时间戳）
     */
    public String triggerManualRebalance(String targetDomain) {
        if (!rebalanceEnabled) {
            log.warn("再平衡功能已禁用");
            return null;
        }

        String taskId = "rebalance-" + System.currentTimeMillis();
        log.info("手动触发再平衡任务: taskId={}, targetDomain={}", taskId, targetDomain);

        // 异步执行再平衡
        Thread.startVirtualThread(() -> {
            if (targetDomain != null) {
                // 针对特定域的再平衡
                List<String> nodesInDomain = faultDomainManager.getHealthyNodesInDomainList(targetDomain);
                for (String node : nodesInDomain) {
                    scheduleRebalance(node, RebalanceType.REBALANCE_TO_NEW);
                }
            } else {
                // 全域再平衡 - 动态获取所有活跃域
                for (String domain : faultDomainManager.getActiveDomains()) {
                    List<String> nodesInDomain = faultDomainManager.getHealthyNodesInDomainList(domain);
                    for (String node : nodesInDomain) {
                        scheduleRebalance(node, RebalanceType.REBALANCE_TO_NEW);
                    }
                }
            }
        });

        return taskId;
    }

    /**
     * 获取当前再平衡状态
     */
    public RebalanceStatus getStatus() {
        return currentStatus;
    }

    /**
     * 再平衡类型
     */
    public enum RebalanceType {
        MIGRATE_FROM_FAILED,   // 从失败节点迁移
        COPY_TO_PROMOTED,      // 复制到提升后的节点
        REBALANCE_TO_NEW       // 再平衡到新节点
    }

    /**
     * 再平衡状态
     */
    @Data
    public static class RebalanceStatus {
        private boolean running;
        private boolean success;
        private RebalanceType type;
        private String triggerNode;
        private Instant startTime;
        private Instant endTime;
        private AtomicInteger migratedCount = new AtomicInteger(0);
        private AtomicInteger failedCount = new AtomicInteger(0);
        private String error;
    }
}
