package cn.flying.minio.service;

import cn.flying.minio.config.LogicNodeMapping;
import cn.flying.minio.config.MinioProperties;
import cn.flying.minio.core.MinioClientManager;
import cn.flying.minio.core.MinioMonitor;
import io.minio.*;
import io.minio.messages.Item;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * MinIO 副本一致性修复服务。
 * 定期扫描逻辑节点下的物理节点对，检测并修复单副本风险。
 * 当文件仅存在于一个物理节点时，自动复制到另一个节点。
 */
@Slf4j
@Service
public class ConsistencyRepairService {

    private static final String LOCK_KEY = "minio:consistency:repair";

    @Resource
    private MinioClientManager clientManager;

    @Resource
    private MinioMonitor minioMonitor;

    @Resource
    private MinioProperties minioProperties;

    @Resource
    private RedissonClient redissonClient;

    @Value("${minio.consistency.repair.batch-size:100}")
    private int batchSize;

    @Value("${minio.consistency.repair.lock-timeout-seconds:600}")
    private long lockTimeoutSeconds;

    @Value("${minio.consistency.repair.enabled:true}")
    private boolean repairEnabled;

    /**
     * 定时执行副本一致性修复任务。
     * 每小时执行一次（可通过配置调整）。
     */
    @Scheduled(cron = "${minio.consistency.repair.cron:0 0 * * * ?}")
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

            log.info("开始执行 MinIO 副本一致性修复任务...");
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

        List<LogicNodeMapping> mappings = minioProperties.getLogicalMapping();
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
            boolean node1Online = minioMonitor.isNodeOnline(node1);
            boolean node2Online = minioMonitor.isNodeOnline(node2);

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

        MinioClient client1 = clientManager.getClient(node1);
        MinioClient client2 = clientManager.getClient(node2);

        if (client1 == null || client2 == null) {
            log.error("无法获取物理节点的 MinIO 客户端：{} 或 {}", node1, node2);
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
     * @param client   MinIO 客户端
     * @param nodeName 节点名称（同时也是桶名）
     * @return 对象名称集合
     */
    private Set<String> listAllObjects(MinioClient client, String nodeName) throws Exception {
        Set<String> objects = new HashSet<>();

        // 检查桶是否存在
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(nodeName).build());
        if (!exists) {
            log.debug("节点 {} 的桶不存在，返回空集合", nodeName);
            return objects;
        }

        Iterable<io.minio.Result<Item>> results = client.listObjects(
                ListObjectsArgs.builder()
                        .bucket(nodeName)
                        .recursive(true)
                        .build()
        );

        for (io.minio.Result<Item> result : results) {
            Item item = result.get();
            if (!item.isDir()) {
                objects.add(item.objectName());
            }
        }

        log.debug("节点 {} 中共有 {} 个对象", nodeName, objects.size());
        return objects;
    }

    /**
     * 将对象从源节点复制到目标节点。
     *
     * @param sourceClient 源 MinIO 客户端
     * @param sourceBucket 源桶名（节点名）
     * @param targetClient 目标 MinIO 客户端
     * @param targetBucket 目标桶名（节点名）
     * @param objectName   对象名称
     */
    private void copyObject(MinioClient sourceClient, String sourceBucket,
                            MinioClient targetClient, String targetBucket,
                            String objectName) throws Exception {
        // 确保目标桶存在
        boolean targetExists = targetClient.bucketExists(
                BucketExistsArgs.builder().bucket(targetBucket).build());
        if (!targetExists) {
            targetClient.makeBucket(MakeBucketArgs.builder().bucket(targetBucket).build());
            log.info("在节点 {} 上创建了桶 {}", targetBucket, targetBucket);
        }

        // 获取源对象
        try (InputStream inputStream = sourceClient.getObject(
                GetObjectArgs.builder()
                        .bucket(sourceBucket)
                        .object(objectName)
                        .build())) {

            // 获取源对象的大小
            StatObjectResponse stat = sourceClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(sourceBucket)
                            .object(objectName)
                            .build());

            // 上传到目标节点
            targetClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(targetBucket)
                            .object(objectName)
                            .stream(inputStream, stat.size(), -1)
                            .contentType(stat.contentType())
                            .build());
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
