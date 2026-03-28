package cn.flying.service.monitor;

import cn.flying.platformapi.response.StorageCapacityVO;
import cn.flying.platformapi.response.StorageNodeCapacityVO;
import cn.flying.service.SystemMonitorService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 将存储容量快照桥接为可由 backend-web `/actuator/prometheus` 抓取的节点级指标。
 */
@Slf4j
@Component
public class StorageCapacityMetricsBinder {

    private static final String ONLINE_STATUS_METRIC = "s3.node.online.status";
    private static final String USAGE_PERCENT_METRIC = "s3.node.usage.percent";
    private static final String UNKNOWN_FAULT_DOMAIN = "UNKNOWN";
    private static final String NO_NODES_SOURCE = "prometheus-no-nodes";
    private static final String FALLBACK_ESTIMATE_SOURCE = "fallback-estimate";

    private final MeterRegistry meterRegistry;
    private final SystemMonitorService systemMonitorService;
    private final Map<String, NodeGaugeHandle> nodeGaugeHandles = new ConcurrentHashMap<>();

    public StorageCapacityMetricsBinder(MeterRegistry meterRegistry, SystemMonitorService systemMonitorService) {
        this.meterRegistry = meterRegistry;
        this.systemMonitorService = systemMonitorService;
    }

    /**
     * 定时刷新存储节点指标，仅在拿到按节点拆分的 Prometheus 快照时更新对外暴露的 gauge。
     */
    @Scheduled(fixedDelayString = "${monitor.storage.metrics.refresh-interval-ms:30000}")
    public void refreshStorageNodeMetrics() {
        StorageCapacityVO storageCapacity = systemMonitorService.getStorageCapacity();
        if (storageCapacity == null || storageCapacity.nodes() == null) {
            log.debug("跳过存储指标刷新：未拿到存储容量快照。");
            return;
        }

        List<StorageNodeCapacityVO> nodes = storageCapacity.nodes();
        if (nodes.isEmpty()) {
            handleEmptySnapshot(storageCapacity);
            return;
        }

        if (!isPrometheusSnapshot(storageCapacity.source())) {
            log.debug("跳过存储指标刷新：当前快照来源不是 Prometheus，可用 source={}", storageCapacity.source());
            return;
        }

        Set<String> activeNodeNames = new HashSet<>();
        for (StorageNodeCapacityVO node : nodes) {
            if (node == null || node.nodeName() == null || node.nodeName().isBlank()) {
                continue;
            }
            activeNodeNames.add(node.nodeName());
            upsertNodeGauge(storageCapacity, node);
        }

        removeStaleNodeGauges(activeNodeNames);
    }

    /**
     * 处理没有节点明细的快照，避免把短暂回退结果误写成“全节点离线”。
     */
    private void handleEmptySnapshot(StorageCapacityVO storageCapacity) {
        if (shouldClearForEmptyPrometheusSnapshot(storageCapacity)) {
            clearAllNodeGauges();
            return;
        }
        log.debug("跳过存储指标刷新：快照缺少节点明细，source={}", storageCapacity.source());
    }

    /**
     * 判断空节点快照是否代表“当前没有受管节点”，此时需要清空 bridge gauge。
     *
     * @param storageCapacity 存储容量快照
     * @return 若应清空全部 bridge gauge 则返回 true
     */
    private boolean shouldClearForEmptyPrometheusSnapshot(StorageCapacityVO storageCapacity) {
        String source = storageCapacity.source();
        if (NO_NODES_SOURCE.equals(source) || FALLBACK_ESTIMATE_SOURCE.equals(source)) {
            return true;
        }
        return "prometheus".equals(source) && !storageCapacity.degraded();
    }

    /**
     * 按节点名更新或重建对应 gauge，并同步最新在线状态与容量使用率。
     */
    private void upsertNodeGauge(StorageCapacityVO storageCapacity, StorageNodeCapacityVO node) {
        String nodeName = node.nodeName();
        String faultDomain = normalizeFaultDomain(node.faultDomain());
        NodeGaugeHandle currentHandle = nodeGaugeHandles.get(nodeName);
        if (currentHandle == null || !currentHandle.faultDomain().equals(faultDomain)) {
            if (currentHandle != null) {
                deregisterNodeGauges(nodeName, currentHandle);
            }
            currentHandle = registerNodeGauges(nodeName, faultDomain);
            nodeGaugeHandles.put(nodeName, currentHandle);
        }

        currentHandle.onlineStatus().set(node.online() ? 1 : 0);
        currentHandle.usagePercent().set(resolvePublishedUsagePercent(storageCapacity, node));
    }

    /**
     * 为指定节点注册在线状态与容量使用率 gauge。
     */
    private NodeGaugeHandle registerNodeGauges(String nodeName, String faultDomain) {
        AtomicInteger onlineStatus = new AtomicInteger(0);
        AtomicReference<Double> usagePercent = new AtomicReference<>(Double.NaN);
        Gauge onlineGauge = Gauge.builder(ONLINE_STATUS_METRIC, onlineStatus, AtomicInteger::doubleValue)
                .description("Current online status of configured S3 nodes bridged via backend-web monitor snapshot")
                .tag("node", nodeName)
                .tag("fault_domain", faultDomain)
                .register(meterRegistry);
        Gauge usageGauge = Gauge.builder(USAGE_PERCENT_METRIC, usagePercent, ref -> ref.get() == null ? Double.NaN : ref.get())
                .description("Current disk usage percent of configured S3 nodes bridged via backend-web monitor snapshot")
                .baseUnit("percent")
                .tag("node", nodeName)
                .tag("fault_domain", faultDomain)
                .register(meterRegistry);
        return new NodeGaugeHandle(faultDomain, onlineStatus, usagePercent, onlineGauge, usageGauge);
    }

    /**
     * 删除本次快照中已经不存在的节点 gauge，避免旧节点标签长期残留。
     */
    private void removeStaleNodeGauges(Set<String> activeNodeNames) {
        for (Map.Entry<String, NodeGaugeHandle> entry : new ArrayList<>(nodeGaugeHandles.entrySet())) {
            if (!activeNodeNames.contains(entry.getKey())) {
                deregisterNodeGauges(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * 清空全部节点 gauge，用于“当前没有配置任何节点”的场景。
     */
    private void clearAllNodeGauges() {
        for (Map.Entry<String, NodeGaugeHandle> entry : new ArrayList<>(nodeGaugeHandles.entrySet())) {
            deregisterNodeGauges(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 从注册表中移除节点相关 gauge，并清理本地句柄缓存。
     */
    private void deregisterNodeGauges(String nodeName, NodeGaugeHandle gaugeHandle) {
        meterRegistry.remove(gaugeHandle.onlineGauge());
        meterRegistry.remove(gaugeHandle.usagePercentGauge());
        nodeGaugeHandles.remove(nodeName, gaugeHandle);
    }

    /**
     * 规范化容量使用率，保证导出的值始终落在 0-100 区间内。
     */
    private double sanitizeUsagePercent(double usagePercent) {
        if (!Double.isFinite(usagePercent)) {
            return Double.NaN;
        }
        return Math.max(0D, Math.min(100D, usagePercent));
    }

    /**
     * 仅在节点具备有效容量样本时发布磁盘使用率；缺样本场景导出 NaN，避免把“未知”误写成 0%。
     */
    private double resolvePublishedUsagePercent(StorageCapacityVO storageCapacity, StorageNodeCapacityVO node) {
        if (storageCapacity.degraded() && !hasCapacitySample(node)) {
            return Double.NaN;
        }
        return sanitizeUsagePercent(node.usagePercent());
    }

    /**
     * 判断节点是否携带了可用的容量样本。
     */
    private boolean hasCapacitySample(StorageNodeCapacityVO node) {
        return node.totalCapacityBytes() > 0;
    }

    /**
     * 统一故障域标签，避免空值导致 Prometheus 维度不稳定。
     */
    private String normalizeFaultDomain(String faultDomain) {
        if (faultDomain == null || faultDomain.isBlank()) {
            return UNKNOWN_FAULT_DOMAIN;
        }
        return faultDomain;
    }

    /**
     * 判断当前快照是否来自存储服务的 Prometheus 聚合路径。
     */
    private boolean isPrometheusSnapshot(String source) {
        return source != null && source.startsWith("prometheus");
    }

    private record NodeGaugeHandle(
            String faultDomain,
            AtomicInteger onlineStatus,
            AtomicReference<Double> usagePercent,
            Gauge onlineGauge,
            Gauge usagePercentGauge
    ) {
    }
}
