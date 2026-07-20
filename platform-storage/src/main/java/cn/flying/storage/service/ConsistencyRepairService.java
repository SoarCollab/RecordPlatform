package cn.flying.storage.service;

import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.FaultDomainManager;
import cn.flying.storage.core.S3ClientManager;
import cn.flying.storage.core.S3ClientManager.TopologyLease;
import cn.flying.storage.core.S3Monitor;
import cn.flying.storage.core.S3ObjectIterator;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * direct-upload staging 由专用晋级与过期清理流程独占；全局副本修复不得跨域复制该命名空间。
     * 匹配范围故意覆盖规范键及此前缀下的畸形后缀，避免绕过生命周期管理。
     */
    private static final String TENANT_OBJECT_PREFIX = "tenant/";
    private static final String DIRECT_UPLOAD_STAGING_NAMESPACE = "/staging/direct-upload";
    private static final Pattern CANONICAL_CONTENT_OBJECT_PATTERN = Pattern.compile(
            "^tenant/[0-9]+/sha256:([0-9a-fA-F]{64})$"
    );

    // 立即修复任务的并发限制
    private static final Semaphore IMMEDIATE_REPAIR_SEMAPHORE = new Semaphore(10);

    // 立即修复任务的最大重试次数
    private static final int IMMEDIATE_REPAIR_MAX_RETRIES = 3;

    // 重试基础退避时间（毫秒）
    private static final long RETRY_BASE_BACKOFF_MS = 1000;

    // 单次 provider attempt 最长 30 秒，同时受整个修复绝对 deadline 约束。
    private static final long MAX_API_ATTEMPT_TIMEOUT_MILLIS = 30_000;

    // 到达整体 deadline 时主动 abort 已返回 headers 但仍阻塞 body 的 streaming GET。
    private static final ScheduledThreadPoolExecutor STREAM_ABORT_EXECUTOR = createStreamAbortExecutor();

    /**
     * 创建守护型流中止调度器，并在正常关闭流时立即清除已取消的延迟任务。
     */
    private static ScheduledThreadPoolExecutor createStreamAbortExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "consistency-repair-stream-deadline");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /**
     * 立即修复的详细生命周期结果，区分真实复制失败和可重试前置条件。
     */
    public enum ImmediateRepairStatus {
        SUCCEEDED,
        COPY_FAILED,
        RETRYABLE_DEFERRED,
        PREREQUISITE_UNAVAILABLE
    }

    /**
     * 立即修复详细结果，供恢复调度器决定是否增加域级失败次数。
     *
     * @param status 修复状态
     */
    public record ImmediateRepairResult(ImmediateRepairStatus status) {

        /**
         * 返回本次修复是否已完成并通过最终校验。
         */
        public boolean succeeded() {
            return status == ImmediateRepairStatus.SUCCEEDED;
        }

        /**
         * 返回是否至少启动过一次真实复制或复制后校验。
         */
        public boolean copyAttempted() {
            return status == ImmediateRepairStatus.SUCCEEDED
                    || status == ImmediateRepairStatus.COPY_FAILED;
        }
    }

    @Resource
    private S3ClientManager clientManager;

    @Resource
    private S3Monitor s3Monitor;

    @Resource
    private FaultDomainManager faultDomainManager;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StorageProperties storageProperties;

    /**
     * 默认复用全局有界调度器；保留实例引用便于隔离验证拒绝调度时的资源回收。
     */
    private ScheduledExecutorService streamAbortExecutor = STREAM_ABORT_EXECUTOR;

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
        for (NodeClientPair source : sourceNodes) {
            List<NodeClientPair> independentTargets = resolveIndependentPhysicalTargets(
                    source,
                    targetNodes
            );
            if (independentTargets.isEmpty()) {
                log.error("源域 {} 的节点 {} 与目标域 {} 无可证明独立的物理存储，跳过跨域修复",
                        sourceDomain, source.nodeName, targetDomain);
                continue;
            }
            NodeClientPair targetPrimary = independentTargets.getFirst();
            try {
                if (!bucketExists(source.client, source.nodeName, newRepairDeadline())) {
                    log.debug("节点 {} 的桶不存在，跳过", source.nodeName);
                    continue;
                }

                S3ObjectIterator.forEachPage(source.client, source.nodeName, page -> {
                    for (S3Object s3Object : page) {
                        String key = s3Object.key();
                        if (isDirectUploadStagingObject(key)) {
                            log.debug("跳过 direct-upload staging 对象的一致性修复: object={}", key);
                            continue;
                        }
                        stats.filesChecked++;

                        // 检查目标域是否存在该对象
                        boolean existsInTarget = objectExistsInAnyNode(
                                key,
                                source.nodeName,
                                independentTargets
                        );
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
     * 选择与源节点物理隔离、且目标之间 physicalStorageId 互异的候选节点。
     *
     * <p>无法证明身份独立的节点直接排除，避免别名 endpoint 被当成跨域副本。</p>
     *
     * @param source 当前源节点
     * @param targetNodes 原始目标候选
     * @return 保持原顺序的物理唯一目标列表
     */
    private List<NodeClientPair> resolveIndependentPhysicalTargets(
            NodeClientPair source,
            List<NodeClientPair> targetNodes
    ) {
        List<NodeClientPair> independentTargets = new ArrayList<>();
        for (NodeClientPair target : targetNodes) {
            if (!faultDomainManager.areNodesOnIndependentPhysicalStorage(
                    source.nodeName,
                    target.nodeName
            )) {
                log.error("拒绝把源节点 {} 与目标节点 {} 计为独立物理副本",
                        source.nodeName, target.nodeName);
                continue;
            }
            boolean duplicatesExistingTarget = independentTargets.stream()
                    .anyMatch(existing -> !faultDomainManager.areNodesOnIndependentPhysicalStorage(
                            existing.nodeName,
                            target.nodeName
                    ));
            if (duplicatesExistingTarget) {
                log.error("目标节点 {} 与已有目标共享物理存储身份，不重复计入修复候选", target.nodeName);
                continue;
            }
            independentTargets.add(target);
        }
        return List.copyOf(independentTargets);
    }

    /**
     * 检查对象是否存在于任意目标节点
     */
    private boolean objectExistsInAnyNode(
            String key,
            String sourceNode,
            List<NodeClientPair> nodes
    ) {
        if (isDirectUploadStagingObject(key)) {
            return true;
        }
        long deadline = newRepairDeadline();
        for (NodeClientPair node : nodes) {
            if (!faultDomainManager.areNodesOnIndependentPhysicalStorage(
                    sourceNode,
                    node.nodeName
            )) {
                log.error("检查目标对象前物理拓扑已漂移，跳过别名目标: object={}, source={}, target={}",
                        key, sourceNode, node.nodeName);
                continue;
            }
            try {
                node.client.headObject(HeadObjectRequest.builder()
                        .bucket(node.nodeName)
                        .key(key)
                        .overrideConfiguration(requestOverride(deadline))
                        .build());
                if (faultDomainManager.areNodesOnIndependentPhysicalStorage(
                        sourceNode,
                        node.nodeName
                )) {
                    return true;
                }
                log.error("目标对象检查完成后物理拓扑已漂移，拒绝把别名端点计为独立副本: "
                                + "object={}, source={}, target={}",
                        key, sourceNode, node.nodeName);
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
        return copyObjectBetweenNodesDetailed(
                objectName,
                sourceNode,
                targetNode,
                newRepairDeadline()
        ).succeeded();
    }

    /**
     * 在共享绝对 deadline 内复制并校验对象，并区分前置不可用、超时和真实复制失败。
     */
    private ImmediateRepairResult copyObjectBetweenNodesDetailed(
            String objectName,
            String sourceNode,
            String targetNode,
            long deadline
    ) {
        if (isDirectUploadStagingObject(objectName)) {
            log.debug("拒绝由全局一致性修复复制 direct-upload staging 对象: object={}", objectName);
            return result(ImmediateRepairStatus.PREREQUISITE_UNAVAILABLE);
        }
        if (!faultDomainManager.areNodesOnIndependentPhysicalStorage(sourceNode, targetNode)) {
            log.error("拒绝在非独立物理存储节点之间修复对象: object={}, source={}, target={}",
                    objectName, sourceNode, targetNode);
            return result(ImmediateRepairStatus.PREREQUISITE_UNAVAILABLE);
        }
        boolean copyStarted = false;
        try (TopologyLease topology = clientManager.acquireTopologyLease()) {
            S3Client sourceClient = topology.getClient(sourceNode);
            S3Client targetClient = topology.getClient(targetNode);

            if (sourceClient == null || targetClient == null) {
                log.error("无法获取 S3 客户端: source={}, target={}", sourceNode, targetNode);
                return result(ImmediateRepairStatus.PREREQUISITE_UNAVAILABLE);
            }

            boolean canonicalContentObject = CANONICAL_CONTENT_OBJECT_PATTERN.matcher(objectName).matches();
            if (!canonicalContentObject) {
                ensureTargetBucketExists(targetClient, targetNode, deadline);
            }

            // 获取源对象元数据
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(sourceNode)
                    .key(objectName)
                    .overrideConfiguration(requestOverride(deadline))
                    .build();
            HeadObjectResponse headResponse = sourceClient.headObject(headRequest);

            if (headResponse.contentLength() == null || headResponse.contentLength() < 0
                    || headResponse.eTag() == null || headResponse.eTag().isBlank()) {
                log.error("源对象元数据不完整，拒绝无条件修复: object={}, source={}", objectName, sourceNode);
                return result(ImmediateRepairStatus.PREREQUISITE_UNAVAILABLE);
            }

            int bufferSize = storageProperties.getDirectUpload().getEffectiveStreamBufferBytes();
            if (!verifyCanonicalObjectContent(
                    objectName,
                    sourceClient,
                    sourceNode,
                    headResponse,
                    bufferSize,
                    deadline
            )) {
                log.error("源对象内容地址校验失败，拒绝污染目标副本: object={}, source={}, target={}",
                        objectName, sourceNode, targetNode);
                return result(ImmediateRepairStatus.COPY_FAILED);
            }

            if (canonicalContentObject) {
                // 源内容通过确定性校验后才允许访问或创建目标桶，确保坏源不会产生任何目标端副作用。
                ensureTargetBucketExists(targetClient, targetNode, deadline);
            }

            // 使用同一个源 ETag 重开输入流，避免校验后源对象被替换并保持 SDK 重试能力。
            ContentStreamProvider provider = ContentStreamProvider.fromInputStreamSupplier(
                    () -> openDeadlineBoundStream(
                            sourceClient,
                            sourceNode,
                            objectName,
                            headResponse.eTag(),
                            bufferSize,
                            deadline
                    ));
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(targetNode)
                    .key(objectName)
                    .contentLength(headResponse.contentLength())
                    .contentType(headResponse.contentType())
                    .contentEncoding(headResponse.contentEncoding())
                    .contentDisposition(headResponse.contentDisposition())
                    .cacheControl(headResponse.cacheControl())
                    .contentLanguage(headResponse.contentLanguage())
                    .metadata(headResponse.metadata())
                    .overrideConfiguration(requestOverride(deadline))
                    .build();

            copyStarted = true;
            PutObjectResponse putResponse = targetClient.putObject(putRequest, RequestBody.fromContentProvider(
                    provider,
                    headResponse.contentLength(),
                    headResponse.contentType() != null
                            ? headResponse.contentType()
                            : "application/octet-stream"));
            HeadObjectResponse targetHead = targetClient.headObject(HeadObjectRequest.builder()
                    .bucket(targetNode)
                    .key(objectName)
                    .overrideConfiguration(requestOverride(deadline))
                    .build());
            if (targetHead.contentLength() == null
                    || !targetHead.contentLength().equals(headResponse.contentLength())
                    || targetHead.eTag() == null
                    || targetHead.eTag().isBlank()
                    || !Objects.equals(targetHead.metadata(), headResponse.metadata())) {
                log.error("目标对象修复后校验失败: object={}, source={}, target={}",
                        objectName, sourceNode, targetNode);
                return result(ImmediateRepairStatus.COPY_FAILED);
            }
            if (putResponse == null || putResponse.eTag() == null || putResponse.eTag().isBlank()) {
                log.error("目标 provider 未返回修复对象 ETag: object={}, target={}", objectName, targetNode);
                return result(ImmediateRepairStatus.COPY_FAILED);
            }
            if (!verifyCanonicalObjectContent(
                    objectName,
                    targetClient,
                    targetNode,
                    targetHead,
                    bufferSize,
                    deadline
            )) {
                log.error("目标对象内容地址校验失败: object={}, source={}, target={}",
                        objectName, sourceNode, targetNode);
                return result(ImmediateRepairStatus.COPY_FAILED);
            }

            AtomicBoolean physicallyIndependent = new AtomicBoolean();
            topology.runIfCurrent(() -> physicallyIndependent.set(
                    faultDomainManager.areNodesOnIndependentPhysicalStorage(sourceNode, targetNode)));
            if (!physicallyIndependent.get()) {
                log.error("对象修复完成后物理拓扑已漂移，拒绝计为独立副本: object={}, source={}, target={}",
                        objectName, sourceNode, targetNode);
                return result(ImmediateRepairStatus.PREREQUISITE_UNAVAILABLE);
            }

            return result(ImmediateRepairStatus.SUCCEEDED);

        } catch (RepairDeadlineExceededException | RepairStreamSchedulingException e) {
            log.warn("复制对象因 deadline 或流调度资源暂不可用而延后: object={}, source={}, target={}",
                    objectName, sourceNode, targetNode);
            return result(ImmediateRepairStatus.RETRYABLE_DEFERRED);
        } catch (RuntimeException e) {
            log.error("复制对象 {} 从 {} 到 {} 失败: {}", objectName, sourceNode, targetNode, e.getMessage());
            if (isTimeoutFailure(e) || deadlineExpired(deadline)) {
                return result(ImmediateRepairStatus.RETRYABLE_DEFERRED);
            }
            return result(copyStarted
                    ? ImmediateRepairStatus.COPY_FAILED
                    : ImmediateRepairStatus.PREREQUISITE_UNAVAILABLE);
        }
    }

    /**
     * 确保修复目标桶存在，并让 canonical 与历史对象路径按各自既有顺序复用同一实现。
     */
    private void ensureTargetBucketExists(S3Client targetClient, String targetNode, long deadline) {
        try {
            targetClient.headBucket(HeadBucketRequest.builder()
                    .bucket(targetNode)
                    .overrideConfiguration(requestOverride(deadline))
                    .build());
        } catch (NoSuchBucketException e) {
            targetClient.createBucket(CreateBucketRequest.builder()
                    .bucket(targetNode)
                    .overrideConfiguration(requestOverride(deadline))
                    .build());
            log.info("在节点 {} 上创建了桶", targetNode);
        }
    }

    /**
     * 对 canonical content-addressed final 执行条件 GET 与有界 SHA-256 复核。
     * 非 canonical 对象继续沿用 metadata/size 校验，避免改变历史对象命名合同。
     *
     * @return true 表示无需内容地址校验或对象字节与 key 中哈希一致
     */
    private boolean verifyCanonicalObjectContent(
            String objectName,
            S3Client objectClient,
            String objectNode,
            HeadObjectResponse objectHead,
            int bufferSize,
            long deadline
    ) {
        Matcher matcher = CANONICAL_CONTENT_OBJECT_PATTERN.matcher(objectName);
        if (!matcher.matches()) {
            return true;
        }

        MessageDigest digest = sha256Digest();
        long totalRead = 0;
        try (InputStream input = openDeadlineBoundStream(
                objectClient,
                objectNode,
                objectName,
                objectHead.eTag(),
                bufferSize,
                deadline
        )) {
            byte[] buffer = new byte[bufferSize];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (deadlineExpired(deadline)) {
                    throw new RepairDeadlineExceededException();
                }
                totalRead += read;
                if (totalRead > objectHead.contentLength()) {
                    return false;
                }
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            if (deadlineExpired(deadline)) {
                throw new RepairDeadlineExceededException();
            }
            throw new IllegalStateException("failed to verify repaired target content", e);
        }

        if (totalRead != objectHead.contentLength()) {
            return false;
        }
        String actualHash = HexFormat.of().formatHex(digest.digest());
        return actualHash.equalsIgnoreCase(matcher.group(1));
    }

    /**
     * 创建目标内容复核使用的 SHA-256 实例。
     */
    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * 使用 request-level timeout 检查桶是否存在。
     */
    private boolean bucketExists(S3Client client, String bucket, long deadline) {
        try {
            client.headBucket(HeadBucketRequest.builder()
                    .bucket(bucket)
                    .overrideConfiguration(requestOverride(deadline))
                    .build());
            return true;
        } catch (NoSuchBucketException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    /**
     * 创建覆盖整次修复及其全部重试的绝对单调时钟 deadline。
     */
    private long newRepairDeadline() {
        StorageProperties.DegradedWriteConfig config = storageProperties.getDegradedWrite();
        int timeoutSeconds = config != null
                ? config.getEffectiveRepairTimeoutSeconds()
                : new StorageProperties.DegradedWriteConfig().getEffectiveRepairTimeoutSeconds();
        long timeoutNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds);
        long now = System.nanoTime();
        return timeoutNanos > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + timeoutNanos;
    }

    /**
     * 根据绝对 deadline 为单次 AWS 请求配置 API call 与 attempt timeout。
     */
    private AwsRequestOverrideConfiguration requestOverride(long deadline) {
        long remainingNanos = remainingNanos(deadline);
        if (remainingNanos <= 0) {
            throw new RepairDeadlineExceededException();
        }
        long callTimeoutMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        long attemptTimeoutMillis = Math.max(
                1L,
                Math.min(callTimeoutMillis, MAX_API_ATTEMPT_TIMEOUT_MILLIS)
        );
        return AwsRequestOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofMillis(callTimeoutMillis))
                .apiCallAttemptTimeout(Duration.ofMillis(attemptTimeoutMillis))
                .build();
    }

    /**
     * 打开受整体 deadline 约束的条件 GET；headers 返回后由定时器主动 abort 阻塞 body。
     */
    private InputStream openDeadlineBoundStream(
            S3Client sourceClient,
            String sourceNode,
            String objectName,
            String sourceEtag,
            int bufferSize,
            long deadline
    ) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(sourceNode)
                .key(objectName)
                .ifMatch(sourceEtag)
                .overrideConfiguration(requestOverride(deadline))
                .build();
        ResponseInputStream<GetObjectResponse> response = sourceClient.getObject(request);
        long remainingNanos = remainingNanos(deadline);
        if (remainingNanos <= 0) {
            abortAndCloseResponse(response, objectName, sourceNode);
            throw new RepairDeadlineExceededException();
        }
        ScheduledFuture<?> abortTask;
        try {
            abortTask = streamAbortExecutor.schedule(
                    () -> abortResponse(response, objectName, sourceNode),
                    remainingNanos,
                    TimeUnit.NANOSECONDS
            );
        } catch (RejectedExecutionException e) {
            abortAndCloseResponse(response, objectName, sourceNode);
            throw new RepairStreamSchedulingException(e);
        }
        return new DeadlineBoundInputStream(
                new BufferedInputStream(response, bufferSize),
                abortTask
        );
    }

    /**
     * 到达整体 deadline 时主动中止 streaming response，解除阻塞读取。
     */
    private void abortResponse(
            ResponseInputStream<GetObjectResponse> response,
            String objectName,
            String sourceNode
    ) {
        try {
            response.abort();
            log.warn("已在整体 deadline 主动中止修复流: object={}, source={}", objectName, sourceNode);
        } catch (RuntimeException e) {
            log.debug("中止修复流时 provider 已结束: object={}, source={}", objectName, sourceNode, e);
        }
    }

    /**
     * 在流无法进入受控生命周期时同时中止并关闭响应，避免连接和输入流泄漏。
     */
    private void abortAndCloseResponse(
            ResponseInputStream<GetObjectResponse> response,
            String objectName,
            String sourceNode
    ) {
        try {
            abortResponse(response, objectName, sourceNode);
        } finally {
            try {
                response.close();
            } catch (IOException | RuntimeException e) {
                log.debug("关闭已中止修复流时 provider 已结束: object={}, source={}",
                        objectName, sourceNode, e);
            }
        }
    }

    /**
     * 返回绝对 deadline 的剩余纳秒数。
     */
    private long remainingNanos(long deadline) {
        return deadline - System.nanoTime();
    }

    /**
     * 判断整个修复绝对 deadline 是否已经到期。
     */
    private boolean deadlineExpired(long deadline) {
        return remainingNanos(deadline) <= 0;
    }

    /**
     * 识别 AWS API call/attempt timeout 及其包装异常。
     */
    private boolean isTimeoutFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ApiCallTimeoutException
                    || current instanceof ApiCallAttemptTimeoutException
                    || current instanceof TimeoutException
                    || current instanceof RepairDeadlineExceededException
                    || current instanceof RepairStreamSchedulingException
                    || current.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 创建非空详细结果。
     */
    private ImmediateRepairResult result(ImmediateRepairStatus status) {
        return new ImmediateRepairResult(status);
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
        scheduleImmediateRepairByNodesAsync(objectName, sourceNode, targetNode);
    }

    /**
     * 调度立即修复并返回真实完成结果，供降级追踪在复制成功后再清除缺失域。
     *
     * @param objectName 对象名称（包含租户路径）
     * @param sourceNode 成功上传的源节点
     * @param targetNode 需要修复的目标节点
     * @return true 表示复制最终成功；队列满、节点离线或重试耗尽均为 false
     */
    public CompletableFuture<Boolean> scheduleImmediateRepairByNodesAsync(
            String objectName,
            String sourceNode,
            String targetNode
    ) {
        return scheduleImmediateRepairByNodesDetailedAsync(objectName, sourceNode, targetNode)
                .thenApply(ImmediateRepairResult::succeeded);
    }

    /**
     * 调度立即修复并返回可区分真实复制失败和可重试前置条件的详细结果。
     *
     * @param objectName 对象名称（包含租户路径）
     * @param sourceNode 候选源节点
     * @param targetNode hash placement 精确目标节点
     * @return 详细异步结果
     */
    public CompletableFuture<ImmediateRepairResult> scheduleImmediateRepairByNodesDetailedAsync(
            String objectName,
            String sourceNode,
            String targetNode
    ) {
        if (isDirectUploadStagingObject(objectName)) {
            log.debug("跳过 direct-upload staging 对象的立即修复: object={}", objectName);
            return CompletableFuture.completedFuture(result(
                    ImmediateRepairStatus.PREREQUISITE_UNAVAILABLE));
        }
        if (!faultDomainManager.areNodesOnIndependentPhysicalStorage(sourceNode, targetNode)) {
            log.error("跳过无法证明物理隔离的立即修复: object={}, source={}, target={}",
                    objectName, sourceNode, targetNode);
            return CompletableFuture.completedFuture(result(
                    ImmediateRepairStatus.PREREQUISITE_UNAVAILABLE));
        }

        // 使用信号量限制并发修复任务数量
        if (!IMMEDIATE_REPAIR_SEMAPHORE.tryAcquire()) {
            log.warn("立即修复任务队列已满，跳过修复: object={}, source={}, target={}",
                    objectName, sourceNode, targetNode);
            return CompletableFuture.completedFuture(result(
                    ImmediateRepairStatus.RETRYABLE_DEFERRED));
        }

        CompletableFuture<ImmediateRepairResult> future = new CompletableFuture<>();
        // 异步执行修复，不阻塞主流程
        try {
            Thread.startVirtualThread(() -> {
                try {
                    future.complete(executeRepairByNodesWithRetry(objectName, sourceNode, targetNode));
                } catch (RuntimeException e) {
                    log.error("立即修复任务异常结束: object={}, source={}, target={}",
                            objectName, sourceNode, targetNode, e);
                    future.complete(result(ImmediateRepairStatus.RETRYABLE_DEFERRED));
                } finally {
                    IMMEDIATE_REPAIR_SEMAPHORE.release();
                }
            });
        } catch (RuntimeException e) {
            IMMEDIATE_REPAIR_SEMAPHORE.release();
            log.error("启动立即修复任务失败: object={}, source={}, target={}",
                    objectName, sourceNode, targetNode, e);
            future.complete(result(ImmediateRepairStatus.RETRYABLE_DEFERRED));
        }
        return future;
    }

    /**
     * 在单个绝对 deadline 内执行带退避重试的修复操作。
     */
    private ImmediateRepairResult executeRepairByNodesWithRetry(
            String objectName,
            String sourceNode,
            String targetNode
    ) {
        long deadline = newRepairDeadline();
        for (int attempt = 1; attempt <= IMMEDIATE_REPAIR_MAX_RETRIES; attempt++) {
            try {
                log.info("开始修复对象 {} 从 {} 到 {} (尝试 {}/{})",
                        objectName, sourceNode, targetNode, attempt, IMMEDIATE_REPAIR_MAX_RETRIES);

                if (!s3Monitor.isNodeOnline(sourceNode)) {
                    log.warn("源节点 {} 不在线，无法修复对象 {}", sourceNode, objectName);
                    return result(ImmediateRepairStatus.PREREQUISITE_UNAVAILABLE);
                }
                if (!s3Monitor.isNodeOnline(targetNode)) {
                    log.warn("目标节点 {} 不在线，延后修复对象 {}", targetNode, objectName);
                    return result(ImmediateRepairStatus.PREREQUISITE_UNAVAILABLE);
                }

                // 执行复制
                ImmediateRepairResult repairResult = copyObjectBetweenNodesDetailed(
                        objectName,
                        sourceNode,
                        targetNode,
                        deadline
                );
                if (repairResult.succeeded()) {
                    log.info("成功修复对象 {} 从 {} 到 {}", objectName, sourceNode, targetNode);
                    return repairResult;
                }
                if (repairResult.status() != ImmediateRepairStatus.COPY_FAILED) {
                    return repairResult;
                }

                // 复制失败，准备重试
                if (attempt < IMMEDIATE_REPAIR_MAX_RETRIES) {
                    long backoffMs = RETRY_BASE_BACKOFF_MS * (1L << (attempt - 1));
                    long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos(deadline));
                    if (remainingMillis <= backoffMs) {
                        return result(ImmediateRepairStatus.RETRYABLE_DEFERRED);
                    }
                    log.warn("修复对象 {} 失败，{}ms 后重试", objectName, backoffMs);
                    Thread.sleep(backoffMs);
                } else {
                    return repairResult;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("修复任务被中断: object={}", objectName);
                return result(ImmediateRepairStatus.RETRYABLE_DEFERRED);
            } catch (RuntimeException e) {
                log.error("修复对象 {} 时发生异常: {}", objectName, e.getMessage(), e);
                return result(isTimeoutFailure(e) || deadlineExpired(deadline)
                        ? ImmediateRepairStatus.RETRYABLE_DEFERRED
                        : ImmediateRepairStatus.PREREQUISITE_UNAVAILABLE);
            }
        }
        return result(ImmediateRepairStatus.COPY_FAILED);
    }

    /**
     * 判断对象是否属于 direct-upload staging 生命周期命名空间。
     * 除规范 session/part 键外，同一前缀下的残缺或非法后缀也保守跳过。
     *
     * @param objectName S3 对象键
     * @return true 表示该对象只能由 direct-upload 专用流程处理
     */
    private boolean isDirectUploadStagingObject(String objectName) {
        if (objectName == null || !objectName.startsWith(TENANT_OBJECT_PREFIX)) {
            return false;
        }
        int tenantSegmentEnd = objectName.indexOf('/', TENANT_OBJECT_PREFIX.length());
        if (tenantSegmentEnd < TENANT_OBJECT_PREFIX.length()
                || !objectName.startsWith(DIRECT_UPLOAD_STAGING_NAMESPACE, tenantSegmentEnd)) {
            return false;
        }
        int namespaceEnd = tenantSegmentEnd + DIRECT_UPLOAD_STAGING_NAMESPACE.length();
        return objectName.length() == namespaceEnd || objectName.charAt(namespaceEnd) == '/';
    }

    /**
     * 在正常关闭时取消 deadline abort，在阻塞读取时允许定时器中止底层响应。
     */
    private static final class DeadlineBoundInputStream extends FilterInputStream {
        private final ScheduledFuture<?> abortTask;

        private DeadlineBoundInputStream(InputStream input, ScheduledFuture<?> abortTask) {
            super(input);
            this.abortTask = abortTask;
        }

        /**
         * 关闭流并取消尚未触发的 abort 任务。
         */
        @Override
        public void close() throws IOException {
            abortTask.cancel(false);
            super.close();
        }
    }

    /**
     * 表示在发起下一个 provider 请求前整体修复 deadline 已耗尽。
     */
    private static final class RepairDeadlineExceededException extends RuntimeException {
        private RepairDeadlineExceededException() {
            super("consistency repair deadline exceeded");
        }
    }

    /**
     * 表示流中止任务无法进入调度器，应延后整次修复而不是计为复制失败。
     */
    private static final class RepairStreamSchedulingException extends RuntimeException {
        private RepairStreamSchedulingException(Throwable cause) {
            super("consistency repair stream deadline scheduling rejected", cause);
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
