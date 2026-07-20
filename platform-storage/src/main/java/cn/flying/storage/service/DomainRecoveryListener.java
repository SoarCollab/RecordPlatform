package cn.flying.storage.service;

import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.FaultDomainManager;
import cn.flying.storage.core.S3Monitor;
import cn.flying.storage.event.NodeTopologyChangeEvent;
import cn.flying.storage.tenant.TenantContextUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 域恢复监听器
 * <p>
 * 监听节点恢复事件，自动触发降级写入的副本同步。
 * 包含两种触发机制：
 * <ul>
 *   <li>事件驱动：节点上线时立即检查并同步</li>
 *   <li>定时轮询：每5分钟检查待同步记录（兜底机制）</li>
 * </ul>
 *
 * @since v3.1.0
 */
@Slf4j
@Component
public class DomainRecoveryListener {

    @Resource
    private DegradedWriteTracker degradedWriteTracker;

    @Resource
    private FaultDomainManager faultDomainManager;

    @Resource
    private ConsistencyRepairService repairService;

    @Resource
    private StorageProperties storageProperties;

    @Resource
    private S3Monitor s3Monitor;

    /**
     * 同步进行中标记，防止并发执行
     */
    private final AtomicBoolean syncInProgress = new AtomicBoolean(false);

    /**
     * 监听节点拓扑变更事件
     *
     * @param event 节点拓扑变更事件
     */
    @EventListener
    public void onNodeTopologyChange(NodeTopologyChangeEvent event) {
        // 只处理节点上线事件
        if (event.getChangeType() != NodeTopologyChangeEvent.TopologyChangeType.NODE_ONLINE) {
            return;
        }

        // 检查降级写入功能是否启用
        var degradedWriteConfig = storageProperties.getDegradedWrite();
        if (degradedWriteConfig == null || !degradedWriteConfig.isTrackForSync()) {
            return;
        }

        String recoveredNode = event.getNodeName();
        String recoveredDomain = event.getFaultDomain();

        if (recoveredDomain == null || recoveredDomain.isBlank()) {
            recoveredDomain = faultDomainManager.getNodeDomain(recoveredNode);
        }

        if (recoveredDomain == null || recoveredDomain.isBlank()) {
            log.debug("Node {} has no domain, skip sync check", recoveredNode);
            return;
        }

        // 防止并发执行（与定时任务互斥）
        if (!syncInProgress.compareAndSet(false, true)) {
            log.debug("Sync already in progress, event-driven sync for domain {} will be handled by scheduled task", recoveredDomain);
            return;
        }

        try {
            List<DegradedWriteTracker.ClaimedDegradedWrite> claimed =
                    degradedWriteTracker.claimPendingSyncs();
            List<DegradedWriteTracker.ClaimedDegradedWrite> pendingSyncs = new ArrayList<>();
            for (DegradedWriteTracker.ClaimedDegradedWrite claim : claimed) {
                List<String> missingDomains = normalizeCurrentPlacement(claim);
                if (missingDomains == null || missingDomains.isEmpty()) {
                    continue;
                }
                if (missingDomains.contains(recoveredDomain)) {
                    pendingSyncs.add(claim);
                } else {
                    // 事件只处理恢复域；非匹配 claim 必须立即释放，不能占用完整租约。
                    degradedWriteTracker.releaseClaim(claim.storageField());
                }
            }

            if (pendingSyncs.isEmpty()) {
                log.debug("No pending syncs for domain {} after node {} recovery", recoveredDomain, recoveredNode);
                return;
            }
            log.info("检测到域 {} 节点 {} 恢复，发现 {} 个待同步的降级写入",
                    recoveredDomain, recoveredNode, pendingSyncs.size());
            scheduleDomainSync(recoveredDomain, pendingSyncs);
        } finally {
            syncInProgress.set(false);
        }
    }

    /**
     * 定时检查待同步的降级写入（兜底机制）
     * <p>
     * 每5分钟执行一次，确保即使错过事件也能完成同步
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void checkPendingSyncs() {
        // 检查降级写入功能是否启用
        var degradedWriteConfig = storageProperties.getDegradedWrite();
        if (degradedWriteConfig == null || !degradedWriteConfig.isTrackForSync()) {
            return;
        }

        // 防止并发执行
        if (!syncInProgress.compareAndSet(false, true)) {
            log.debug("Sync already in progress, skipping scheduled check");
            return;
        }

        try {
            List<DegradedWriteTracker.ClaimedDegradedWrite> pending =
                    degradedWriteTracker.claimPendingSyncs();
            if (pending.isEmpty()) {
                return;
            }
            log.info("定时检查: 原子领取 {} 个待同步的降级写入", pending.size());

            for (DegradedWriteTracker.ClaimedDegradedWrite claim : pending) {
                trySync(claim);
            }
        } finally {
            syncInProgress.set(false);
        }
    }

    /**
     * 调度域同步任务
     *
     * @param domain       恢复的域
     * @param pendingSyncs 已领取的待同步记录列表
     */
    private void scheduleDomainSync(
            String domain,
            List<DegradedWriteTracker.ClaimedDegradedWrite> pendingSyncs
    ) {
        log.info("开始同步域 {} 的 {} 个降级写入", domain, pendingSyncs.size());

        int syncedCount = 0;
        int failedCount = 0;

        for (DegradedWriteTracker.ClaimedDegradedWrite claim : pendingSyncs) {
            DegradedWriteTracker.DegradedWriteRecord record = claim.record();
            try {
                if (trySyncToDomain(claim, domain)) {
                    syncedCount++;
                } else {
                    failedCount++;
                }
            } catch (Exception e) {
                log.error("同步记录 {} 到域 {} 失败: {}", record.getObjectHash(), domain, e.getMessage());
                degradedWriteTracker.rescheduleClaim(claim.storageField());
                failedCount++;
            }
        }

        log.info("域 {} 同步完成: 成功={}, 失败={}", domain, syncedCount, failedCount);
    }

    /**
     * 尝试同步单条记录到指定域
     *
     * @param claim 已领取的降级写入记录
     * @param domain 目标域
     * @return 是否真实完成副本修复
     */
    private boolean trySyncToDomain(
            DegradedWriteTracker.ClaimedDegradedWrite claim,
            String domain
    ) {
        DegradedWriteTracker.DegradedWriteRecord record = claim.record();
        if (record.getMissingDomains() == null || !record.getMissingDomains().contains(domain)) {
            return false;
        }

        // 只能写入该 hash 在目标域的一致性哈希节点，不能用任意健康节点冒充 placement。
        String targetNode = faultDomainManager.getTargetNodeInDomain(record.getObjectHash(), domain);
        if (targetNode == null) {
            log.debug("域 {} 的 hash 目标节点不可用，延后同步: {}", domain, record.getObjectHash());
            degradedWriteTracker.rescheduleClaim(claim.storageField());
            return false;
        }

        // 找到源节点（已成功写入的节点）
        List<String> writtenNodes = record.getWrittenNodes();
        if (writtenNodes == null || writtenNodes.isEmpty()) {
            log.warn("记录 {} 没有成功写入的节点，无法同步", record.getObjectHash());
            degradedWriteTracker.rescheduleClaim(claim.storageField());
            return false;
        }

        // 遍历所有不属于目标域且在线的源副本；单个坏副本不能遮蔽后续健康源。
        LinkedHashSet<String> sourceCandidates = new LinkedHashSet<>();
        for (String node : writtenNodes) {
            String sourceDomain = faultDomainManager.getNodeDomain(node);
            if (sourceDomain == null || sourceDomain.equals(domain)) {
                continue;
            }
            if (!faultDomainManager.areNodesOnIndependentPhysicalStorage(node, targetNode)) {
                log.error("拒绝把恢复源节点 {} 与目标节点 {} 计为独立物理副本: object={}",
                        node, targetNode, record.getObjectHash());
                continue;
            }
            if (s3Monitor.isNodeOnline(node)) {
                sourceCandidates.add(node);
            }
        }

        if (sourceCandidates.isEmpty()) {
            log.warn("记录 {} 的源节点都不在线: {}", record.getObjectHash(), writtenNodes);
            degradedWriteTracker.rescheduleClaim(claim.storageField());
            return false;
        }

        // 构建对象路径
        String objectPath = buildObjectPath(record);
        int copyFailures = 0;
        boolean indeterminateDeferred = false;
        StorageProperties.DegradedWriteConfig config = storageProperties.getDegradedWrite();
        int timeoutSeconds = config != null ? config.getEffectiveRepairTimeoutSeconds() : 120;

        for (String sourceNode : sourceCandidates) {
            CompletableFuture<ConsistencyRepairService.ImmediateRepairResult> future =
                    repairService.scheduleImmediateRepairByNodesDetailedAsync(
                            objectPath,
                            sourceNode,
                            targetNode
                    );
            ConsistencyRepairService.ImmediateRepairResult repairResult;
            try {
                repairResult = future.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.warn("降级写入同步等待超时，按 claim 租约延后: {} -> {} (object: {})",
                        sourceNode, targetNode, record.getObjectHash());
                degradedWriteTracker.rescheduleClaimAfterRepairTimeout(claim.storageField());
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("降级写入同步等待被中断，按 claim 租约延后: object={}", record.getObjectHash());
                degradedWriteTracker.rescheduleClaimAfterRepairTimeout(claim.storageField());
                return false;
            } catch (ExecutionException | RuntimeException e) {
                log.warn("降级写入同步未返回可判定结果，保留待同步: {} -> {} (object: {}): {}",
                        sourceNode, targetNode, record.getObjectHash(), e.getClass().getSimpleName());
                indeterminateDeferred = true;
                continue;
            }

            if (repairResult.status() == ConsistencyRepairService.ImmediateRepairStatus.SUCCEEDED) {
                String currentTarget = faultDomainManager.getTargetNodeInDomain(
                        record.getObjectHash(),
                        domain
                );
                if (!targetNode.equals(currentTarget)
                        || !faultDomainManager.areNodesOnIndependentPhysicalStorage(
                                sourceNode,
                                targetNode
                        )) {
                    log.error("修复完成后物理拓扑已漂移，保留降级证据: source={}, target={}, "
                                    + "currentTarget={}, object={}",
                            sourceNode, targetNode, currentTarget, record.getObjectHash());
                    degradedWriteTracker.rescheduleClaim(claim.storageField());
                    return false;
                }
                log.info("降级写入同步完成: {} -> {} (object: {})",
                        sourceNode, targetNode, record.getObjectHash());
                degradedWriteTracker.updateMissingDomains(
                        record.getObjectHash(),
                        record.getTenantId(),
                        domain,
                        targetNode
                );
                return true;
            }
            if (repairResult.status() == ConsistencyRepairService.ImmediateRepairStatus.COPY_FAILED) {
                copyFailures++;
                log.warn("源副本修复失败，继续尝试下一在线源: source={}, target={}, object={}",
                        sourceNode, targetNode, record.getObjectHash());
            } else if (repairResult.status()
                    == ConsistencyRepairService.ImmediateRepairStatus.RETRYABLE_DEFERRED) {
                indeterminateDeferred = true;
                log.debug("源副本修复被调度资源或 deadline 延后: source={}, object={}",
                        sourceNode, record.getObjectHash());
            } else {
                log.debug("源副本当前不可用于确定性复制，继续候选: source={}, status={}, object={}",
                        sourceNode, repairResult.status(), record.getObjectHash());
            }
        }

        // 前置不可用的陈旧源不应永久遮蔽其他实际可尝试源已经确定的复制失败；
        // 但队列、deadline 或异常结果仍属不确定状态，不消耗 DLQ 次数。
        if (copyFailures > 0 && !indeterminateDeferred) {
            return recordRepairFailure(
                    record,
                    domain,
                    targetNode,
                    "all actual source copy or verification attempts failed"
            );
        }

        // 队列满、启动失败、前置不可用等未覆盖全部源时不消耗 DLQ 次数。
        degradedWriteTracker.rescheduleClaim(claim.storageField());
        return false;
    }

    /**
     * 持久化域级修复失败；达到阈值后由 tracker 把该域转入 tenant-scoped 死信。
     *
     * @return 始终为 false，便于调用方保留失败计数语义
     */
    private boolean recordRepairFailure(
            DegradedWriteTracker.DegradedWriteRecord record,
            String domain,
            String attemptedTargetNode,
            String reason
    ) {
        StorageProperties.DegradedWriteConfig config = storageProperties.getDegradedWrite();
        int threshold = config != null ? config.getEffectiveMaxSyncFailures() : 12;
        DegradedWriteTracker.RepairFailureDisposition disposition = degradedWriteTracker.recordRepairFailure(
                record.getObjectHash(),
                record.getTenantId(),
                domain,
                attemptedTargetNode,
                reason,
                threshold
        );
        if (disposition == DegradedWriteTracker.RepairFailureDisposition.DEAD_LETTERED) {
            log.error("降级写入修复达到阈值并进入死信: hash={}, tenant={}, domain={}",
                    record.getObjectHash(), record.getTenantId(), domain);
        }
        return false;
    }

    /**
     * 尝试同步单条记录到所有缺失域
     *
     * @param claim 已领取的降级写入记录
     */
    private void trySync(DegradedWriteTracker.ClaimedDegradedWrite claim) {
        DegradedWriteTracker.DegradedWriteRecord record = claim.record();
        List<String> missingDomains = normalizeCurrentPlacement(claim);
        if (missingDomains == null || missingDomains.isEmpty()) {
            return;
        }

        // 任何域级结果都会重排 due score；每个 claim 每轮只处理一个域，避免其他实例重新领取后并发修改。
        String domain = missingDomains.getFirst();
        try {
            trySyncToDomain(claim, domain);
        } catch (RuntimeException e) {
            log.error("定时同步记录 {} 到域 {} 失败: {}", record.getObjectHash(), domain, e.getMessage());
            degradedWriteTracker.rescheduleClaim(claim.storageField());
        }
    }

    /**
     * 按当前合法 placement 过滤缺失域，并在复制前安全回收已过时的域状态。
     *
     * @return 当前仍需恢复的合法域；null 表示记录状态未知或规划查询失败
     */
    private List<String> normalizeCurrentPlacement(
            DegradedWriteTracker.ClaimedDegradedWrite claim
    ) {
        DegradedWriteTracker.DegradedWriteRecord record = claim.record();
        List<String> missingDomains = record.getMissingDomains();
        if (missingDomains == null) {
            log.error("Degraded write has unknown missing domains, keep pending: field={}, hash={}, tenant={}",
                    claim.storageField(), record.getObjectHash(), record.getTenantId());
            degradedWriteTracker.rescheduleClaim(claim.storageField());
            return null;
        }
        if (missingDomains.isEmpty()) {
            degradedWriteTracker.markSynced(record.getObjectHash(), record.getTenantId());
            return Collections.emptyList();
        }

        Map<String, String> plannedTargets;
        try {
            plannedTargets = faultDomainManager.getPlannedTargetsSnapshot(record.getObjectHash());
        } catch (RuntimeException e) {
            log.error("无法解析当前合法 placement，保留待同步记录: hash={}, tenant={}",
                    record.getObjectHash(), record.getTenantId(), e);
            degradedWriteTracker.rescheduleClaim(claim.storageField());
            return null;
        }
        if (plannedTargets == null) {
            log.error("当前合法 placement 返回未知状态，保留待同步记录: hash={}, tenant={}",
                    record.getObjectHash(), record.getTenantId());
            degradedWriteTracker.rescheduleClaim(claim.storageField());
            return null;
        }
        boolean unresolvedPhysicalTopology = plannedTargets.values().stream()
                .anyMatch(targetNode -> targetNode == null || targetNode.isBlank());
        if (unresolvedPhysicalTopology) {
            log.error("当前 placement 无法证明物理副本唯一性，保留全部降级证据: hash={}, tenant={}",
                    record.getObjectHash(), record.getTenantId());
            degradedWriteTracker.rescheduleClaim(claim.storageField());
            return null;
        }
        Set<String> planned = plannedTargets.keySet();
        for (String obsoleteDomain : missingDomains) {
            if (!planned.contains(obsoleteDomain)) {
                log.info("移除已不属于当前 placement 的降级缺失域: hash={}, tenant={}, domain={}",
                        record.getObjectHash(), record.getTenantId(), obsoleteDomain);
                degradedWriteTracker.updateMissingDomains(
                        record.getObjectHash(),
                        record.getTenantId(),
                        obsoleteDomain,
                        null
                );
                // updateMissingDomains 已重新安排 due score；下一状态必须重新 claim 并读取最新 durable truth。
                return null;
            }
        }
        List<String> validMissingDomains = missingDomains.stream()
                .filter(planned::contains)
                .distinct()
                .toList();
        record.setMissingDomains(new ArrayList<>(validMissingDomains));
        return validMissingDomains;
    }

    /**
     * 构建对象路径
     *
     * @param record 降级写入记录
     * @return 对象路径
     */
    private String buildObjectPath(DegradedWriteTracker.DegradedWriteRecord record) {
        Long tenantId = record.getTenantId();
        String objectHash = record.getObjectHash();
        long effectiveTenantId = tenantId != null && tenantId >= 0 ? tenantId : 0L;
        return TenantContextUtil.buildTenantObjectPath(effectiveTenantId, objectHash);
    }
}
