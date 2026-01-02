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

import java.util.List;
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
        if (degradedWriteConfig == null || !degradedWriteConfig.isEnabled() || !degradedWriteConfig.isTrackForSync()) {
            return;
        }

        String recoveredNode = event.getNodeName();
        String recoveredDomain = event.getFaultDomain();

        if (recoveredDomain == null || recoveredDomain.isBlank()) {
            recoveredDomain = faultDomainManager.getNodeDomain(recoveredNode);
        }

        if (recoveredDomain == null) {
            log.debug("Node {} has no domain, skip sync check", recoveredNode);
            return;
        }

        // 检查该域是否有待同步的降级写入
        List<DegradedWriteTracker.DegradedWriteRecord> pendingSyncs =
                degradedWriteTracker.getPendingSyncsForDomain(recoveredDomain);

        if (pendingSyncs.isEmpty()) {
            log.debug("No pending syncs for domain {} after node {} recovery", recoveredDomain, recoveredNode);
            return;
        }

        // 防止并发执行（与定时任务互斥）
        if (!syncInProgress.compareAndSet(false, true)) {
            log.debug("Sync already in progress, event-driven sync for domain {} will be handled by scheduled task", recoveredDomain);
            return;
        }

        try {
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
        if (degradedWriteConfig == null || !degradedWriteConfig.isEnabled() || !degradedWriteConfig.isTrackForSync()) {
            return;
        }

        long pendingCount = degradedWriteTracker.getPendingCount();
        if (pendingCount == 0) {
            return;
        }

        // 防止并发执行
        if (!syncInProgress.compareAndSet(false, true)) {
            log.debug("Sync already in progress, skipping scheduled check");
            return;
        }

        try {
            log.info("定时检查: 发现 {} 个待同步的降级写入", pendingCount);
            List<DegradedWriteTracker.DegradedWriteRecord> pending = degradedWriteTracker.getPendingSyncs();

            for (DegradedWriteTracker.DegradedWriteRecord record : pending) {
                trySync(record);
            }
        } finally {
            syncInProgress.set(false);
        }
    }

    /**
     * 调度域同步任务
     *
     * @param domain       恢复的域
     * @param pendingSyncs 待同步记录列表
     */
    private void scheduleDomainSync(String domain, List<DegradedWriteTracker.DegradedWriteRecord> pendingSyncs) {
        log.info("开始同步域 {} 的 {} 个降级写入", domain, pendingSyncs.size());

        int syncedCount = 0;
        int failedCount = 0;

        for (DegradedWriteTracker.DegradedWriteRecord record : pendingSyncs) {
            try {
                if (trySyncToDomain(record, domain)) {
                    syncedCount++;
                } else {
                    failedCount++;
                }
            } catch (Exception e) {
                log.error("同步记录 {} 到域 {} 失败: {}", record.getObjectHash(), domain, e.getMessage());
                failedCount++;
            }
        }

        log.info("域 {} 同步完成: 成功={}, 失败={}", domain, syncedCount, failedCount);
    }

    /**
     * 尝试同步单条记录到指定域
     *
     * @param record 降级写入记录
     * @param domain 目标域
     * @return 是否成功调度同步
     */
    private boolean trySyncToDomain(DegradedWriteTracker.DegradedWriteRecord record, String domain) {
        if (!record.getMissingDomains().contains(domain)) {
            return false;
        }

        // 找到目标域的健康节点
        String targetNode = faultDomainManager.findHealthyNodeInDomain(domain);
        if (targetNode == null) {
            log.debug("域 {} 仍无健康节点，跳过同步: {}", domain, record.getObjectHash());
            return false;
        }

        // 找到源节点（已成功写入的节点）
        List<String> writtenNodes = record.getWrittenNodes();
        if (writtenNodes == null || writtenNodes.isEmpty()) {
            log.warn("记录 {} 没有成功写入的节点，无法同步", record.getObjectHash());
            degradedWriteTracker.markSynced(record.getObjectHash()); // 清理无效记录
            return false;
        }

        // 选择一个在线的源节点（必须检查节点是否真正在线）
        String sourceNode = null;
        for (String node : writtenNodes) {
            if (s3Monitor.isNodeOnline(node)) {
                sourceNode = node;
                break;
            }
        }

        if (sourceNode == null) {
            log.warn("记录 {} 的源节点都不在线: {}", record.getObjectHash(), writtenNodes);
            return false;
        }

        // 构建对象路径
        String objectPath = buildObjectPath(record);

        // 调度修复任务
        repairService.scheduleImmediateRepairByNodes(objectPath, sourceNode, targetNode);
        log.info("已调度降级写入同步: {} -> {} (object: {})", sourceNode, targetNode, record.getObjectHash());

        // 更新记录中的缺失域
        degradedWriteTracker.updateMissingDomains(record.getObjectHash(), domain);

        return true;
    }

    /**
     * 尝试同步单条记录到所有缺失域
     *
     * @param record 降级写入记录
     */
    private void trySync(DegradedWriteTracker.DegradedWriteRecord record) {
        List<String> missingDomains = record.getMissingDomains();
        if (missingDomains == null || missingDomains.isEmpty()) {
            degradedWriteTracker.markSynced(record.getObjectHash());
            return;
        }

        for (String domain : missingDomains) {
            trySyncToDomain(record, domain);
        }
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

        if (tenantId != null && tenantId > 0) {
            return String.format("tenant/%d/%s", tenantId, objectHash);
        }
        return TenantContextUtil.buildTenantObjectPath(objectHash);
    }
}
