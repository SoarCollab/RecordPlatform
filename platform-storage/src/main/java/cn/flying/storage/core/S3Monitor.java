package cn.flying.storage.core;

import cn.flying.storage.config.NodeConfig;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 定期监控 S3 兼容存储节点的在线状态
 */
@Component
public class S3Monitor {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(S3Monitor.class);
    // 存储当前在线的物理节点名称
    private final Set<String> onlineNodes = ConcurrentHashMap.newKeySet();
    // 统计节点操作成功和失败的计数器
    private final Counter nodeOperationCounter = Counter.build()
            .name("s3_node_operations_total")
            .help("Total operations attempted on S3 nodes")
            .labelNames("node", "operation", "result")
            .register();
    // Prometheus Gauge，用于公开节点在线状态
    private final Gauge nodeOnlineStatus = Gauge.build()
            .name("s3_node_online_status")
            .help("Status of S3 nodes (1=online, 0=offline).")
            .labelNames("node")
            .register();
    @Resource
    private S3ClientManager clientManager;

    /**
     * 检查节点健康状况
     */
    private void checkNodeHealth(String nodeName, NodeConfig nodeConfig, S3Client client) {
        if (client == null || nodeConfig == null) {
            markNodeOffline(nodeName, "Client or config unavailable");
            return;
        }

        try {
            client.listBuckets();
            markNodeOnline(nodeName);
            nodeOperationCounter.labels(nodeName, "health_check", "success").inc();
        } catch (S3Exception e) {
            markNodeOffline(nodeName, "S3 error: " + e.awsErrorDetails().errorCode());
            nodeOperationCounter.labels(nodeName, "health_check", "failure").inc();
        } catch (Exception e) {
            markNodeOffline(nodeName, "Connection error: " + e.getMessage());
            nodeOperationCounter.labels(nodeName, "health_check", "failure").inc();
        }
    }

    private void markNodeOnline(String nodeName) {
        boolean newlyOnline = onlineNodes.add(nodeName);
        nodeOnlineStatus.labels(nodeName).set(1); // 更新 Prometheus 指标
        if (newlyOnline) {
            log.info("S3 node '{}' is now ONLINE.", nodeName);
        }
    }

    private void markNodeOffline(String nodeName, String reason) {
        boolean wasOnline = onlineNodes.remove(nodeName);
        nodeOnlineStatus.labels(nodeName).set(0); // 更新 Prometheus 指标
        if (wasOnline) {
            log.warn("S3 节点 '{}' 现在处于离线状态，原因：{}", nodeName, reason);
        } else {
            log.debug("S3 节点 '{}' 仍处于离线状态，原因：{}", nodeName, reason);
        }
    }

    /**
     * 定时任务，检查节点健康
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 15000)
    public void scheduledCheckNodes() {
        log.debug("正在启动计划的 S3 节点运行状况检查...");
        Map<String, NodeConfig> nodeConfigs = clientManager.getAllNodeConfigs();
        if (nodeConfigs.isEmpty()) {
            log.debug("没有为运行状况检查配置 S3 节点。");
            Set<String> previouslyOnline = Set.copyOf(onlineNodes);
            onlineNodes.clear();
            // 将 Prometheus gauge （Prometheus 仪表） 设置为 0 （对于之前在线的节点）
            previouslyOnline.forEach(node -> nodeOnlineStatus.labels(node).set(0));
            return;
        }

        for (Map.Entry<String, NodeConfig> entry : nodeConfigs.entrySet()) {
            String nodeName = entry.getKey();
            NodeConfig nodeConfig = entry.getValue();
            S3Client client = clientManager.getClient(nodeName);
            checkNodeHealth(nodeName, nodeConfig, client);
        }

        // 清理不再配置的节点状态和指标
        Set<String> configuredNodeNames = nodeConfigs.keySet();
        Set<String> nodesToRemove = onlineNodes.stream()
                .filter(node -> !configuredNodeNames.contains(node))
                .collect(Collectors.toSet());

        if (!nodesToRemove.isEmpty()) {
            log.info("正在从应用列表中删除已被移除的节点：{}", nodesToRemove);
            onlineNodes.removeAll(nodesToRemove);
            nodesToRemove.forEach(node -> nodeOnlineStatus.labels(node).set(0));
        }

        log.debug("已完成计划的 S3 节点运行状况检查。联机节点数：{}", getOnlineNodes());
    }

    /**
     * 检查节点是否在线
     */
    public boolean isNodeOnline(String nodeName) {
        return onlineNodes.contains(nodeName);
    }

    /**
     * 获取在线节点名称集合
     */
    public Set<String> getOnlineNodes() {
        return Set.copyOf(onlineNodes);
    }

    /**
     * 计算节点的负载分数 (值越低表示负载越轻)
     * 简化版本：所有在线节点返回固定分数 0.0，离线节点返回 Double.MAX_VALUE
     */
    public double getNodeLoadScore(String nodeName) {
        if (!isNodeOnline(nodeName)) {
            return Double.MAX_VALUE;
        }
        return 0.0;  // Fixed score for all online nodes
    }

    /**
     * 应用关闭时释放资源
     */
    @PreDestroy
    public void cleanup() {
        log.info("正在关闭 S3Monitor 资源...");
        try {
            onlineNodes.clear();
            log.info("S3Monitor 指标缓存已清理");
        } catch (Exception e) {
            log.warn("清理指标缓存时出错: {}", e.getMessage());
        }
    }
}
