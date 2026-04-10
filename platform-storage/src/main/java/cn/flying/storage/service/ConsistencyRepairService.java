package cn.flying.storage.service;

import cn.flying.storage.core.FaultDomainManager;
import cn.flying.storage.core.S3ClientManager;
import cn.flying.storage.core.S3Monitor;
import cn.flying.storage.core.S3ObjectIterator;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * S3 副本一致性修复服务。
 * 支持多活跃域配置，定期扫描各域节点，检测并修复跨域副本不一致。
 * 当文件仅存在于部分域时，自动从健康副本复制到缺失的域。
 *
 * <p>支持模式:
 * <ul>
 *   <li>单域模式：跳过跨域修复（无需修复）</li>
 *   <li>多域模式：所有活跃域两两比较，确保数据一致</li>
 * </ul>
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
    private FaultDomainManager faultDomainManager;

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

        // 单域模式无需跨域修复
        if (faultDomainManager.isSingleDomainMode()) {
            log.debug("单域模式，跳过跨域副本一致性修复");
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
            RepairStatistics stats = repairAllDomains();
            log.info("副本一致性修复任务完成：检查域数={}, 检查文件数={}, 修复文件数={}, 失败数={}",
                    stats.domainsChecked, stats.filesChecked, stats.filesRepaired, stats.failureCount);

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
     * 修复所有故障域的副本一致性。
     * 以参考域为基准逐页遍历对象，检查其他域是否存在该对象，缺失则修复。
     * 然后反向检查其他域是否有参考域不存在的对象。
     * 避免将全量对象键加载到内存中，使用分页迭代。
     *
     * @return 修复统计信息
     */
    public RepairStatistics repairAllDomains() {
        RepairStatistics stats = new RepairStatistics();

        List<String> activeDomains = faultDomainManager.getActiveDomains();

        // 单域模式或域不足，跳过修复
        if (activeDomains.size() < 2) {
            log.info("活跃域数量不足 ({})，跳过跨域副本一致性修复", activeDomains.size());
            return stats;
        }

        // 收集每个域的健康节点和可用客户端
        Map<String, List<NodeClientPair>> domainHealthyNodes = new LinkedHashMap<>();

        for (String domainName : activeDomains) {
            Set<String> domainNodes = faultDomainManager.getNodesInDomain(domainName);
            List<NodeClientPair> healthyPairs = new ArrayList<>();

            for (String node : domainNodes) {
                if (!s3Monitor.isNodeOnline(node)) {
                    continue;
                }
                S3Client client = clientManager.getClient(node);
                if (client != null) {
                    healthyPairs.add(new NodeClientPair(node, client));
                }
            }

            if (!healthyPairs.isEmpty()) {
                domainHealthyNodes.put(domainName, healthyPairs);
            } else {
                log.warn("域 {} 没有健康节点，跳过该域", domainName);
            }
        }

        List<String> domainsWithNodes = new ArrayList<>(domainHealthyNodes.keySet());
        stats.domainsChecked = domainsWithNodes.size();

        if (domainsWithNodes.size() < 2) {
            log.info("有健康节点的域不足 2 个，跳过跨域修复");
            return stats;
        }

        log.info("开始跨域副本一致性检查：有健康节点的域={}", domainsWithNodes);

        // 两两比较所有域，使用分页遍历
        for (int i = 0; i < domainsWithNodes.size(); i++) {
            for (int j = i + 1; j < domainsWithNodes.size(); j++) {
                String domainA = domainsWithNodes.get(i);
                String domainB = domainsWithNodes.get(j);

                List<NodeClientPair> nodesA = domainHealthyNodes.get(domainA);
                List<NodeClientPair> nodesB = domainHealthyNodes.get(domainB);

                // A -> B: 遍历 A 域的对象，检查 B 域是否存在
                repairDomainPair(nodesA, nodesB, domainA, domainB, stats);

                // B -> A: 遍历 B 域的对象，检查 A 域是否存在
                repairDomainPair(nodesB, nodesA, domainB, domainA, stats);
            }
        }

        return stats;
    }

    /**
     * 以源域为基准，逐页遍历对象并检查目标域是否存在。
     * 缺失的对象从源域复制到目标域。
     *
     * @param sourceNodes 源域的健康节点列表
     * @param targetNodes 目标域的健康节点列表
     * @param sourceDomain 源域名称
     * @param targetDomain 目标域名称
     * @param stats 统计信息
     */
    private void repairDomainPair(List<NodeClientPair> sourceNodes,
                                  List<NodeClientPair> targetNodes,
                                  String sourceDomain,
                                  String targetDomain,
                                  RepairStatistics stats) {
        // 为目标域建立快速查找：收集第一个可用目标节点用于 headObject 检查
        NodeClientPair targetPrimary = targetNodes.getFirst();

        for (NodeClientPair source : sourceNodes) {
            try {
                if (!S3ObjectIterator.bucketExists(source.client, source.nodeName)) {
                    log.debug("节点 {} 的桶不存在，跳过", source.nodeName);
                    continue;
                }

                S3ObjectIterator.forEachPage(source.client, source.nodeName, page -> {
                    for (S3Object s3Object : page) {
                        String key = s3Object.key();
                        stats.filesChecked++;

                        // 检查目标域是否存在该对象
                        boolean existsInTarget = objectExistsInAnyNode(key, targetNodes);
                        if (!existsInTarget) {
                            // 从源节点复制到目标节点
                            boolean success = copyObjectBetweenNodes(key, source.nodeName, targetPrimary.nodeName);
                            if (success) {
                                stats.filesRepaired++;
                                log.debug("已将对象 {} 从 {} ({}) 复制到 {} ({})",
                                        key, source.nodeName, sourceDomain,
                                        targetPrimary.nodeName, targetDomain);
                            } else {
                                stats.failureCount++;
                            }
                        }
                    }
                });
            } catch (Exception e) {
                log.error("遍历节点 {} 对象时发生错误: {}", source.nodeName, e.getMessage());
            }
        }
    }

    /**
     * 检查对象是否存在于任意目标节点
     */
    private boolean objectExistsInAnyNode(String key, List<NodeClientPair> nodes) {
        for (NodeClientPair node : nodes) {
            try {
                node.client.headObject(HeadObjectRequest.builder()
                        .bucket(node.nodeName)
                        .key(key)
                        .build());
                return true;
            } catch (NoSuchKeyException | NoSuchBucketException e) {
                // 该节点不存在此对象，继续检查下一个
            } catch (Exception e) {
                log.debug("检查对象 {} 在节点 {} 时出错: {}", key, node.nodeName, e.getMessage());
            }
        }
        return false;
    }

    /**
     * 在两个节点之间复制对象
     *
     * @param objectName 对象名称
     * @param sourceNode 源节点
     * @param targetNode 目标节点
     * @return 是否成功
     */
    private boolean copyObjectBetweenNodes(String objectName, String sourceNode, String targetNode) {
        try {
            S3Client sourceClient = clientManager.getClient(sourceNode);
            S3Client targetClient = clientManager.getClient(targetNode);

            if (sourceClient == null || targetClient == null) {
                log.error("无法获取 S3 客户端: source={}, target={}", sourceNode, targetNode);
                return false;
            }

            // 确保目标桶存在
            try {
                targetClient.headBucket(HeadBucketRequest.builder().bucket(targetNode).build());
            } catch (NoSuchBucketException e) {
                targetClient.createBucket(CreateBucketRequest.builder().bucket(targetNode).build());
                log.info("在节点 {} 上创建了桶", targetNode);
            }

            // 获取源对象元数据
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(sourceNode)
                    .key(objectName)
                    .build();
            HeadObjectResponse headResponse = sourceClient.headObject(headRequest);

            // 下载并上传对象
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(sourceNode)
                    .key(objectName)
                    .build();

            try (ResponseInputStream<GetObjectResponse> inputStream = sourceClient.getObject(getRequest)) {
                PutObjectRequest putRequest = PutObjectRequest.builder()
                        .bucket(targetNode)
                        .key(objectName)
                        .contentLength(headResponse.contentLength())
                        .contentType(headResponse.contentType())
                        .build();

                targetClient.putObject(putRequest, RequestBody.fromInputStream(inputStream, headResponse.contentLength()));
            }

            return true;

        } catch (Exception e) {
            log.error("复制对象 {} 从 {} 到 {} 失败: {}", objectName, sourceNode, targetNode, e.getMessage());
            return false;
        }
    }

    /**
     * 手动触发副本一致性修复（用于管理和调试）。
     *
     * @return 修复统计信息
     */
    public RepairStatistics triggerManualRepair() {
        log.info("手动触发副本一致性修复...");
        return repairAllDomains();
    }

    /**
     * 调度立即修复任务（故障域模式）
     * 当写入过程中某节点失败时，从成功节点复制到失败节点。
     *
     * @param objectName 对象名称（包含租户路径）
     * @param sourceNode 成功上传的源节点
     * @param targetNode 需要修复的目标节点
     */
    public void scheduleImmediateRepairByNodes(String objectName, String sourceNode, String targetNode) {
        // 使用信号量限制并发修复任务数量
        if (!IMMEDIATE_REPAIR_SEMAPHORE.tryAcquire()) {
            log.warn("立即修复任务队列已满，跳过修复: object={}, source={}, target={}",
                    objectName, sourceNode, targetNode);
            return;
        }

        // 异步执行修复，不阻塞主流程
        Thread.startVirtualThread(() -> {
            try {
                executeRepairByNodesWithRetry(objectName, sourceNode, targetNode);
            } finally {
                IMMEDIATE_REPAIR_SEMAPHORE.release();
            }
        });
    }

    /**
     * 执行带重试的修复操作
     */
    private void executeRepairByNodesWithRetry(String objectName, String sourceNode, String targetNode) {
        for (int attempt = 1; attempt <= IMMEDIATE_REPAIR_MAX_RETRIES; attempt++) {
            try {
                log.info("开始修复对象 {} 从 {} 到 {} (尝试 {}/{})",
                        objectName, sourceNode, targetNode, attempt, IMMEDIATE_REPAIR_MAX_RETRIES);

                if (!s3Monitor.isNodeOnline(sourceNode)) {
                    log.warn("源节点 {} 不在线，无法修复对象 {}", sourceNode, objectName);
                    return;
                }
                if (!s3Monitor.isNodeOnline(targetNode)) {
                    log.warn("目标节点 {} 不在线，等待后重试修复对象 {}", targetNode, objectName);
                    if (attempt < IMMEDIATE_REPAIR_MAX_RETRIES) {
                        long backoffMs = RETRY_BASE_BACKOFF_MS * (1L << (attempt - 1));
                        Thread.sleep(backoffMs);
                        continue;
                    }
                    return;
                }

                // 执行复制
                boolean success = copyObjectBetweenNodes(objectName, sourceNode, targetNode);
                if (success) {
                    log.info("成功修复对象 {} 从 {} 到 {}", objectName, sourceNode, targetNode);
                    return;
                }

                // 复制失败，准备重试
                if (attempt < IMMEDIATE_REPAIR_MAX_RETRIES) {
                    long backoffMs = RETRY_BASE_BACKOFF_MS * (1L << (attempt - 1));
                    log.warn("修复对象 {} 失败，{}ms 后重试", objectName, backoffMs);
                    Thread.sleep(backoffMs);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("修复任务被中断: object={}", objectName);
                return;
            } catch (Exception e) {
                log.error("修复对象 {} 时发生异常: {}", objectName, e.getMessage(), e);
                if (attempt >= IMMEDIATE_REPAIR_MAX_RETRIES) {
                    log.error("修复对象 {} 最终失败，已达最大重试次数", objectName);
                }
            }
        }
    }

    /**
     * 节点与客户端配对，避免重复查找
     */
    private record NodeClientPair(String nodeName, S3Client client) {}

    /**
     * 修复统计信息。
     */
    public static class RepairStatistics {
        public int domainsChecked = 0;
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
            return String.format("RepairStatistics{domains=%d, checked=%d, repaired=%d, failures=%d}",
                    domainsChecked, filesChecked, filesRepaired, failureCount);
        }
    }
}
