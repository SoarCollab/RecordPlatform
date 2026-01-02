package cn.flying.storage.core;

import cn.flying.storage.config.NodeConfig;
import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.event.NodeTopologyChangeEvent;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.Data;
import org.slf4j.Logger;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 定期监控 S3 兼容存储节点的在线状态和负载指标
 */
@Component
public class S3Monitor {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(S3Monitor.class);

    // ===== 负载评分权重配置 =====
    private static final double WEIGHT_DISK_USAGE = 0.4;
    private static final double WEIGHT_INFLIGHT_REQUESTS = 0.35;
    private static final double WEIGHT_WAITING_REQUESTS = 0.25;

    // 默认负载分数（当无法获取指标时使用）
    private static final double DEFAULT_LOAD_SCORE = 0.5;
    // 指标缓存过期时间（秒）
    private static final long METRICS_CACHE_TTL_SECONDS = 60;

    // 存储当前在线的物理节点名称
    private final Set<String> onlineNodes = ConcurrentHashMap.newKeySet();

    // 节点指标缓存
    private final Map<String, NodeMetrics> nodeMetricsCache = new ConcurrentHashMap<>();

    // HTTP 客户端用于获取指标
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

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

    // 节点负载评分 Gauge
    private final Gauge nodeLoadScoreGauge = Gauge.build()
            .name("s3_node_load_score")
            .help("Load score of S3 nodes (lower is better)")
            .labelNames("node")
            .register();

    @Resource
    private S3ClientManager clientManager;

    @Resource
    private ApplicationEventPublisher eventPublisher;

    @Lazy
    @Resource
    private StorageProperties storageProperties;

    /**
     * 节点指标数据
     */
    @Data
    public static class NodeMetrics {
        private Double diskUsagePercent;      // 磁盘使用率 (0-100)
        private Integer apiInflightRequests;  // 正在处理的请求数
        private Integer apiWaitingRequests;   // 等待处理的请求数
        private Instant fetchTime;            // 指标获取时间

        public boolean isExpired() {
            return fetchTime == null ||
                    Duration.between(fetchTime, Instant.now()).getSeconds() > METRICS_CACHE_TTL_SECONDS;
        }
    }

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
            // 发布节点上线事件
            String faultDomain = getNodeFaultDomain(nodeName);
            eventPublisher.publishEvent(new NodeTopologyChangeEvent(
                    this, nodeName,
                    NodeTopologyChangeEvent.TopologyChangeType.NODE_ONLINE,
                    faultDomain
            ));
        }
    }

    private void markNodeOffline(String nodeName, String reason) {
        boolean wasOnline = onlineNodes.remove(nodeName);
        nodeOnlineStatus.labels(nodeName).set(0); // 更新 Prometheus 指标
        if (wasOnline) {
            log.warn("S3 节点 '{}' 现在处于离线状态，原因：{}", nodeName, reason);
            // 发布节点离线事件
            String faultDomain = getNodeFaultDomain(nodeName);
            eventPublisher.publishEvent(new NodeTopologyChangeEvent(
                    this, nodeName,
                    NodeTopologyChangeEvent.TopologyChangeType.NODE_OFFLINE,
                    faultDomain
            ));
        } else {
            log.debug("S3 节点 '{}' 仍处于离线状态，原因：{}", nodeName, reason);
        }
    }

    /**
     * 定时任务，检查节点健康并收集指标
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 15000)
    public void scheduledCheckNodes() {
        log.debug("正在启动计划的 S3 节点运行状况检查...");
        Map<String, NodeConfig> nodeConfigs = clientManager.getAllNodeConfigs();
        if (nodeConfigs.isEmpty()) {
            log.debug("没有为运行状况检查配置 S3 节点。");
            Set<String> previouslyOnline = Set.copyOf(onlineNodes);
            onlineNodes.clear();
            nodeMetricsCache.clear();
            // 将 Prometheus gauge （Prometheus 仪表） 设置为 0 （对于之前在线的节点）
            previouslyOnline.forEach(node -> nodeOnlineStatus.labels(node).set(0));
            return;
        }

        for (Map.Entry<String, NodeConfig> entry : nodeConfigs.entrySet()) {
            String nodeName = entry.getKey();
            NodeConfig nodeConfig = entry.getValue();
            S3Client client = clientManager.getClient(nodeName);
            checkNodeHealth(nodeName, nodeConfig, client);

            // 为在线节点收集指标
            if (isNodeOnline(nodeName)) {
                fetchAndCacheNodeMetrics(nodeName, nodeConfig);
            }
        }

        // 清理不再配置的节点状态和指标
        Set<String> configuredNodeNames = nodeConfigs.keySet();
        Set<String> nodesToRemove = onlineNodes.stream()
                .filter(node -> !configuredNodeNames.contains(node))
                .collect(Collectors.toSet());

        if (!nodesToRemove.isEmpty()) {
            log.info("正在从应用列表中删除已被移除的节点：{}", nodesToRemove);
            onlineNodes.removeAll(nodesToRemove);
            nodesToRemove.forEach(node -> {
                nodeOnlineStatus.labels(node).set(0);
                nodeMetricsCache.remove(node);
            });
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
     *
     * <p>评分公式:
     * score = (diskUsage% / 100 * 0.4) + (inflightReqs / 100 * 0.35) + (waitingReqs / 50 * 0.25)
     *
     * @param nodeName 节点名称
     * @return 负载分数 (0.0 - 1.0+)，离线节点返回 Double.MAX_VALUE
     */
    public double getNodeLoadScore(String nodeName) {
        if (!isNodeOnline(nodeName)) {
            return Double.MAX_VALUE;
        }

        NodeMetrics metrics = nodeMetricsCache.get(nodeName);
        if (metrics == null || metrics.isExpired()) {
            // 无指标或已过期，返回默认中等分数
            return DEFAULT_LOAD_SCORE;
        }

        // 计算各维度的归一化分数
        double diskScore = 0.0;
        if (metrics.getDiskUsagePercent() != null) {
            diskScore = Math.min(1.0, metrics.getDiskUsagePercent() / 100.0);
        }

        double inflightScore = 0.0;
        if (metrics.getApiInflightRequests() != null) {
            inflightScore = Math.min(1.0, metrics.getApiInflightRequests() / 100.0);
        }

        double waitingScore = 0.0;
        if (metrics.getApiWaitingRequests() != null) {
            waitingScore = Math.min(1.0, metrics.getApiWaitingRequests() / 50.0);
        }

        // 加权求和
        return (diskScore * WEIGHT_DISK_USAGE)
                + (inflightScore * WEIGHT_INFLIGHT_REQUESTS)
                + (waitingScore * WEIGHT_WAITING_REQUESTS);
    }

    /**
     * 计算并更新节点负载分数到 Prometheus 指标
     * 仅在定时任务中调用，避免读取方法的副作用
     */
    private void updateNodeLoadScoreGauge(String nodeName) {
        double loadScore = getNodeLoadScore(nodeName);
        if (loadScore != Double.MAX_VALUE) {
            nodeLoadScoreGauge.labels(nodeName).set(loadScore);
        }
    }

    /**
     * 获取并缓存节点指标
     *
     * @param nodeName   节点名称
     * @param nodeConfig 节点配置
     */
    private void fetchAndCacheNodeMetrics(String nodeName, NodeConfig nodeConfig) {
        try {
            NodeMetrics metrics = fetchNodeMetrics(nodeConfig);
            if (metrics != null) {
                nodeMetricsCache.put(nodeName, metrics);
                log.debug("已更新节点 {} 的指标: disk={}%, inflight={}, waiting={}",
                        nodeName, metrics.getDiskUsagePercent(),
                        metrics.getApiInflightRequests(), metrics.getApiWaitingRequests());
                // 在定时任务中更新 Prometheus 指标
                updateNodeLoadScoreGauge(nodeName);
            }
        } catch (Exception e) {
            log.debug("获取节点 {} 指标失败: {}", nodeName, e.getMessage());
        }
    }

    /**
     * 从节点的 Prometheus 端点获取指标
     *
     * <p>支持的指标端点:
     * <ul>
     *   <li>MinIO: /minio/v2/metrics/node (默认)</li>
     *   <li>通用 S3: /metrics (需要配置)</li>
     * </ul>
     *
     * @param nodeConfig 节点配置
     * @return 节点指标，获取失败返回 null
     */
    private NodeMetrics fetchNodeMetrics(NodeConfig nodeConfig) {
        String endpoint = nodeConfig.getEndpoint();
        String metricsPath = nodeConfig.getMetricsPath();

        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }

        // 如果 metricsPath 为空或 null，则禁用指标采集
        if (metricsPath == null || metricsPath.isBlank()) {
            log.debug("节点 {} 未配置指标端点，跳过指标采集", nodeConfig.getName());
            return null;
        }

        // 构建指标 URL
        String baseUrl = endpoint.replaceAll("/$", "");
        String metricsUrl = baseUrl + (metricsPath.startsWith("/") ? metricsPath : "/" + metricsPath);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(metricsUrl))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parsePrometheusMetrics(response.body());
            } else {
                log.debug("指标端点返回非 200 状态码: {} for {}", response.statusCode(), metricsUrl);
            }
        } catch (Exception e) {
            log.debug("无法从 {} 获取指标: {}", metricsUrl, e.getMessage());
        }

        return null;
    }

    /**
     * 解析 Prometheus 格式的指标文本
     *
     * @param metricsText Prometheus 格式的指标文本
     * @return 解析后的节点指标
     */
    private NodeMetrics parsePrometheusMetrics(String metricsText) {
        NodeMetrics metrics = new NodeMetrics();
        metrics.setFetchTime(Instant.now());

        // 匹配 MinIO 风格的磁盘使用率指标
        // minio_node_drive_used_bytes / minio_node_drive_total_bytes (MinIO 2024+ 版本)
        // 兼容旧版: minio_node_disk_used_bytes / minio_node_disk_total_bytes
        Pattern diskUsedPattern = Pattern.compile("minio_node_dr(?:ive|isk)_used_bytes\\{[^}]*\\}\\s+(\\d+(?:\\.\\d+)?(?:e[+-]?\\d+)?)");
        Pattern diskTotalPattern = Pattern.compile("minio_node_dr(?:ive|isk)_total_bytes\\{[^}]*\\}\\s+(\\d+(?:\\.\\d+)?(?:e[+-]?\\d+)?)");

        Matcher usedMatcher = diskUsedPattern.matcher(metricsText);
        Matcher totalMatcher = diskTotalPattern.matcher(metricsText);

        if (usedMatcher.find() && totalMatcher.find()) {
            double used = Double.parseDouble(usedMatcher.group(1));
            double total = Double.parseDouble(totalMatcher.group(1));
            if (total > 0) {
                metrics.setDiskUsagePercent((used / total) * 100);
            }
        }

        // 匹配 API 请求指标 (MinIO 2024+ 版本使用 _total 后缀)
        Pattern inflightPattern = Pattern.compile("minio_s3_requests_inflight(?:_total)?\\{[^}]*\\}\\s+(\\d+)");
        Pattern waitingPattern = Pattern.compile("minio_s3_requests_waiting(?:_total)?\\{[^}]*\\}\\s+(\\d+)");

        Matcher inflightMatcher = inflightPattern.matcher(metricsText);
        Matcher waitingMatcher = waitingPattern.matcher(metricsText);

        if (inflightMatcher.find()) {
            metrics.setApiInflightRequests(Integer.parseInt(inflightMatcher.group(1)));
        }

        if (waitingMatcher.find()) {
            metrics.setApiWaitingRequests(Integer.parseInt(waitingMatcher.group(1)));
        }

        return metrics;
    }

    /**
     * 获取节点的缓存指标（用于调试/监控）
     *
     * @param nodeName 节点名称
     * @return 节点指标，如果不存在返回 null
     */
    public NodeMetrics getNodeMetrics(String nodeName) {
        return nodeMetricsCache.get(nodeName);
    }

    /**
     * 根据节点名称获取其所属的故障域
     *
     * @param nodeName 节点名称
     * @return 故障域名称，如果未找到返回 null
     */
    private String getNodeFaultDomain(String nodeName) {
        if (storageProperties == null || storageProperties.getNodes() == null) {
            return null;
        }
        return storageProperties.getNodes().stream()
                .filter(node -> nodeName.equals(node.getName()))
                .findFirst()
                .map(NodeConfig::getFaultDomain)
                .orElse(null);
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
