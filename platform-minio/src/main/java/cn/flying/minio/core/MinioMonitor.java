package cn.flying.minio.core;

import cn.flying.minio.config.NodeConfig;
import cn.flying.minio.config.NodeMetrics;
import io.minio.MinioClient;
import io.minio.errors.MinioException;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import jakarta.annotation.Resource;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 定期监控 MinIO 物理节点的在线状态和负载情况
 */
@Component
public class MinioMonitor {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(MinioMonitor.class);
    // 存储当前在线的物理节点名称
    private final Set<String> onlineNodes = ConcurrentHashMap.newKeySet();
    // 指标缓存
    private final Map<String, NodeMetrics> nodeMetricsCache = new ConcurrentHashMap<>();
    //OkHttpClient 实例
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();
    // 统计节点操作成功和失败的计数器
    private final Counter nodeOperationCounter = Counter.build()
            .name("minio_node_operations_total")
            .help("Total operations attempted on MinIO nodes")
            .labelNames("node", "operation", "result")
            .register();
    // Prometheus Gauge，用于公开节点在线状态
    private final Gauge nodeOnlineStatus = Gauge.build()
            .name("minio_node_online_status")
            .help("Status of MinIO nodes (1=online, 0=offline).")
            .labelNames("node")
            .register();
    private final Gauge nodeLoadScoreGauge = Gauge.build()
            .name("minio_node_load_score")
            .help("Calculated load score for MinIO nodes (lower is better).")
            .labelNames("node")
            .register();
    @Resource
    private MinioClientManager clientManager;

    private static double getScore(double apiInflight, double apiWaiting, double diskUsage) {
        // API Inflight: 使用 tanh 平滑处理，假设 50 个请求是较高负载 (可调)
        double normApiInflight = Math.tanh(apiInflight / 50.0);
        // API Waiting: 使用 tanh 平滑处理，假设 20 个请求是较高负载 (可调)
        double normApiWaiting = Math.tanh(apiWaiting / 20.0);
        // Disk Usage: 已经是百分比，直接除以 100
        double normDiskUsage = Math.max(0.0, Math.min(1.0, diskUsage / 100.0));

        // 定义权重 (可根据实际情况调整)
        double wApiInflight = 0.5; // Inflight 请求数权重最高
        double wApiWaiting = 0.3;  // Waiting 请求数权重次之
        double wDiskUsage = 0.2;   // 磁盘使用率权重


        // 计算加权分数
        double score = wApiInflight * normApiInflight +
                wApiWaiting * normApiWaiting +  // 添加 waiting 分数
                wDiskUsage * normDiskUsage;     // 使用 disk usage 分数

        // 确保分数在 [0, 1.0] 范围内 (理论上加权和可能略大于1，做个限制)
        score = Math.max(0.0, Math.min(1.0, score));
        return score;
    }

    /**
     * 检查节点健康状况
     */
    private void checkNodeHealth(String nodeName, NodeConfig nodeConfig, MinioClient client) {
        if (client == null || nodeConfig == null) {
            markNodeOffline(nodeName, "客户端或配置不可用");
            return;
        }
        try {
            // 1. 基础健康检查 (检查连通性和认证)
            client.listBuckets(); // 简单检查，至少需要一个存储桶（节点自己的）
            markNodeOnline(nodeName);
            nodeOperationCounter.labels(nodeName, "health_check", "success").inc();

            // 2. 尝试获取并解析指标
            try {
                // 获取或创建当前节点的指标对象，并重置瞬时/聚合指标
                NodeMetrics currentMetrics = nodeMetricsCache.computeIfAbsent(nodeName, k -> new NodeMetrics());
                currentMetrics.resetTransientMetrics(); // 重置聚合状态
                fetchAndParseMetrics(nodeName, nodeConfig, currentMetrics); // 传入 NodeMetrics 对象进行填充
            } catch (Exception e) {
                log.warn("节点 '{}'：无法获取或解析指标 {}。Node 保持在线状态，但指标可能已过时。",
                        nodeName, e.getMessage(), e); // Log the exception details
                // 获取指标失败不应标记节点为离线，但分数会受影响
                // 清除该节点的缓存指标，使其获得默认低负载分数
                nodeMetricsCache.remove(nodeName);
                log.warn("节点 '{}'：由于 fetch/parse 错误，已清除指标缓存。", nodeName);
            }
        } catch (MinioException e) {
            markNodeOffline(nodeName, "运行状况检查期间出现 MinIO API 错误: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            nodeOperationCounter.labels(nodeName, "health_check", "failure").inc();
        } catch (InvalidKeyException | IOException | NoSuchAlgorithmException e) {
            markNodeOffline(nodeName, "运行状况检查期间出现连接/凭证错误: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            nodeOperationCounter.labels(nodeName, "health_check", "failure").inc();
        } catch (Exception e) { // 捕获更广泛地异常，包括潜在的 SigV4 或 OkHttp 问题
            markNodeOffline(nodeName, "运行状况检查期间出现意外错误: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            nodeOperationCounter.labels(nodeName, "health_check", "failure").inc();
        }
    }

    /**
     * 从 MinIO 节点获取并解析指标 (使用 OkHttp + Bearer Token)
     */
    private void fetchAndParseMetrics(String nodeName, NodeConfig nodeConfig, NodeMetrics metricsToFill) throws Exception {
        String endpoint = nodeConfig.getEndpoint();
        // 监控端点
        String metricsPath = "/minio/v2/metrics/cluster";

        URI uri;
        try {
            uri = new URI(endpoint.replaceAll("/+$", "") + metricsPath);
        } catch (URISyntaxException e) {
            throw new IOException("无效的端点 URI: " + endpoint + metricsPath, e);
        }

        // --- 构建请求 ---
        Request request = new Request.Builder()
                .url(uri.toURL())
                .get()
                .build();

        // --- 执行请求并处理响应 ---
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = "";
                try (ResponseBody body = response.body()) {
                    if (body != null) errorBody = body.string();
                } catch (Exception ignored) {
                }
                // 更新错误消息
                throw new IOException("Node '" + nodeName + "': 获取监控指标失败 '" + metricsPath + "', HTTP status: " + response.code() + " - " + response.message() + " Body: " + errorBody);
            }

            try (ResponseBody responseBody = response.body()) {
                if (responseBody == null) {
                    // 这里应该认为是失败，因为我们需要指标数据
                    throw new IOException("Node '" + nodeName + "': 监控端点返回数据为空 '" + metricsPath + "'");
                }
                StringBuilder content = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody.byteStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        content.append(line).append("\n");
                    }
                }
                // 解析从单一端点获取的所有指标
                parsePrometheusMetrics(nodeName, content.toString(), metricsToFill);
            }
        } catch (IOException e) {
            // 如果发生 IO 异常，直接向上抛出
            throw new IOException("Node '" + nodeName + "': 获取指标时出现IOException'" + metricsPath + "': " + e.getMessage(), e);
        }

        // 解析完成后，计算聚合指标
        metricsToFill.calculateDiskUsagePercent(); // 计算磁盘使用率
        log.debug("已完成节点 '{}' 的指标获取，最终指标：{}", nodeName, metricsToFill);
    }

    /**
     * 解析 Prometheus 格式的指标，填充到传入的 NodeMetrics 对象
     * **注意：这个方法现在会被多次调用（每个端点一次），需要正确处理累加和覆盖逻辑**
     */
    private void parsePrometheusMetrics(String nodeName, String metricsText, NodeMetrics metricsToFill) {
        // 使用MULTILINE模式处理多行指标
        // 组 1: 指标名
        // 组 2: 完整标签部分 (可选, e.g., {label="value"})
        // 组 3: 标签内容 (可选, e.g., label="value")
        // 组 4: 值
        Pattern metricPattern = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(\\{([^}]*)})?\\s+([+-]?(?:\\d*\\.)?\\d+(?:[eE][+-]?\\d+)?)\\s*$", Pattern.MULTILINE);
        Matcher matcher = metricPattern.matcher(metricsText);

        while (matcher.find()) {
            String metricName = matcher.group(1);
            String labelsString = matcher.group(3);
            String valueString = matcher.group(4);
            double value;
            try {
                value = Double.parseDouble(valueString);
            } catch (NumberFormatException e) {
                log.warn("节点 '{}'：无法解析指标 {} 的值 '{}'，跳过。。。", nodeName, valueString, metricName);
                continue;
            }
            Map<String, String> labels = parseLabels(labelsString);

            switch (metricName) {
                // --- 节点级 S3 指标 ---
                case "minio_s3_requests_inflight_total":
                    metricsToFill.addApiInflightRequests(value);
                    break;
                case "minio_s3_requests_waiting_total":
                    metricsToFill.addApiWaitingRequests(value);
                    break;

                // --- 存储容量指标 (用于计算磁盘使用率) ---
                case "minio_cluster_capacity_usable_free_bytes":
                    metricsToFill.setUsableFreeBytes(value);
                    break;
                case "minio_cluster_capacity_usable_total_bytes":
                    metricsToFill.setUsableTotalBytes(value);
                    break;
            }
        }
    }

    // 辅助方法：解析标签字符串
    private Map<String, String> parseLabels(String labelsString) {
        Map<String, String> labels = new ConcurrentHashMap<>();
        if (labelsString != null && !labelsString.isEmpty()) {
            Pattern labelPattern = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"([^\"]*)\"");
            Matcher labelMatcher = labelPattern.matcher(labelsString);
            while (labelMatcher.find()) {
                labels.put(labelMatcher.group(1), labelMatcher.group(2));
            }
        }
        return labels;
    }

    private void markNodeOnline(String nodeName) {
        boolean newlyOnline = onlineNodes.add(nodeName);
        nodeOnlineStatus.labels(nodeName).set(1); // 更新 Prometheus 指标
        if (newlyOnline) {
            log.info("MinIO node '{}' is now ONLINE.", nodeName);
        }
    }

    private void markNodeOffline(String nodeName, String reason) {
        boolean wasOnline = onlineNodes.remove(nodeName);
        nodeMetricsCache.remove(nodeName); // 节点下线时清除指标缓存
        nodeOnlineStatus.labels(nodeName).set(0); // 更新 Prometheus 指标
        // 注意：移除节点时，对应的 Gauge 也会被设置为一个特殊值： MaxValue
        try {
            nodeLoadScoreGauge.labels(nodeName).set(Double.MAX_VALUE); // 设置最大分数表示离线
        } catch (Exception e) {
            // 如果节点已从外部移除，则处理潜在问题
            log.warn("无法为节点 '{}' 设置离线分数，节点可能已被删除。", nodeName);
        }
        if (wasOnline) {
            log.warn("MinIO 节点 '{}' 现在处于离线状态，原因：{}。已清除指标缓存。。。", nodeName, reason);
        } else {
            log.debug("MinIO 节点 '{}' 仍处于离线状态，原因：{}", nodeName, reason);
        }
    }

    /**
     * 定时任务，检查节点健康
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 15000)
    public void scheduledCheckNodes() {
        log.debug("正在启动计划的 MinIO 节点运行状况检查...");
        Map<String, NodeConfig> nodeConfigs = clientManager.getAllNodeConfigs();
        if (nodeConfigs.isEmpty()) {
            log.debug("没有为运行状况检查配置 MinIO 节点。");
            Set<String> previouslyOnline = Set.copyOf(onlineNodes);
            onlineNodes.clear();
            nodeMetricsCache.clear();
            // 将 Prometheus gauge （Prometheus 仪表） 设置为 0 （对于之前在线的节点）
            previouslyOnline.forEach(node -> {
                nodeOnlineStatus.labels(node).set(0);
                nodeLoadScoreGauge.labels(node).set(Double.MAX_VALUE);
            });
            return;
        }

        for (Map.Entry<String, NodeConfig> entry : nodeConfigs.entrySet()) {
            String nodeName = entry.getKey();
            NodeConfig nodeConfig = entry.getValue();
            MinioClient client = clientManager.getClient(nodeName);
            checkNodeHealth(nodeName, nodeConfig, client);
            // 更新负载分数指标
            // 确保在 getNodeLoadScore 之前 checkNodeHealth 已更新缓存
            if (isNodeOnline(nodeName)) {
                try {
                    nodeLoadScoreGauge.labels(nodeName).set(getNodeLoadScore(nodeName));
                } catch (Exception e) {
                    log.warn("更新节点 {} 的负载分数仪表时出错：{}", nodeName, e.getMessage());
                }
            } else {
                try {
                    nodeLoadScoreGauge.labels(nodeName).set(Double.MAX_VALUE);
                } catch (Exception e) {
                    log.warn("在清理期间无法为节点“{}”设置离线分数。", nodeName);
                }
            }
        }

        // 清理不再配置的节点状态和指标
        Set<String> configuredNodeNames = nodeConfigs.keySet();
        Set<String> nodesToRemove = onlineNodes.stream()
                .filter(node -> !configuredNodeNames.contains(node))
                .collect(Collectors.toSet());
        Set<String> metricsToRemove = nodeMetricsCache.keySet().stream()
                .filter(node -> !configuredNodeNames.contains(node))
                .collect(Collectors.toSet());

        if (!nodesToRemove.isEmpty()) {
            log.info("正在从应用列表中删除已被移除的节点：{}", nodesToRemove);
            onlineNodes.removeAll(nodesToRemove);
            nodesToRemove.forEach(node -> {
                nodeOnlineStatus.labels(node).set(0);
                try {
                    nodeLoadScoreGauge.labels(node).set(Double.MAX_VALUE);
                } catch (Exception e) {
                    log.warn("无法为已删除的节点 '{}' 设置离线分数。", node);
                }
            });
        }
        if (!metricsToRemove.isEmpty()) {
            log.info("正在删除已被移除节点对应的指标缓存：{}", metricsToRemove);
            metricsToRemove.forEach(nodeMetricsCache::remove);
            // Remove gauges for nodes no longer configured
            metricsToRemove.forEach(node -> {
                try {
                    nodeOnlineStatus.remove(node);
                    nodeLoadScoreGauge.remove(node);
                } catch (Exception e) {
                    log.warn("删除节点 {} 的 Prometheus 仪表时出错：{}", node, e.getMessage());
                }
            });
        }

        log.debug("已完成计划的 MinIO 节点运行状况检查。联机节点数：{}，指标缓存结果集大小：{}",
                getOnlineNodes(), nodeMetricsCache.size());
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
     * 获取指定节点的指标数据
     */
    public Optional<NodeMetrics> getNodeMetrics(String nodeName) {
        return Optional.ofNullable(nodeMetricsCache.get(nodeName));
    }

    /**
     * 计算节点的负载分数 (值越低表示负载越轻)
     * 基于 API 进行中请求数、CPU、内存、磁盘利用率。
     */
    public double getNodeLoadScore(String nodeName) {
        if (!isNodeOnline(nodeName)) {
            return Double.MAX_VALUE; // 离线节点分数最高
        }

        Optional<NodeMetrics> metricsOptional = getNodeMetrics(nodeName);

        // 如果没有指标数据，认为负载较低
        if (metricsOptional.isEmpty()) {
            log.debug("节点 '{}'：指标无数据，默认处于低负载状态", nodeName);
            return 0.0; // 默认低负载
        }

        NodeMetrics metrics = metricsOptional.get();

        // 获取指标值，处理 null 的情况
        double apiInflight = metrics.getApiInflightRequests() != null ? metrics.getApiInflightRequests() : 0.0;
        double apiWaiting = metrics.getApiWaitingRequests() != null ? metrics.getApiWaitingRequests() : 0.0;
        double diskUsage = metrics.getDiskUsagePercent() != null ? metrics.getDiskUsagePercent() : 0.0;


        // 归一化指标到 0-1 范围
        double score = getScore(apiInflight, apiWaiting, diskUsage);

        log.trace("节点 '{}'：计算的负载分数：{}(正在进行的：{}，等待中：{}，DiskUsage：{}%)",
                nodeName, score, apiInflight, apiWaiting, diskUsage);
        return score;
    }
} 