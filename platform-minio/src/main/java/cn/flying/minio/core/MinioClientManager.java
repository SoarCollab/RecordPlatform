package cn.flying.minio.core;

import cn.flying.minio.config.MinioProperties;
import cn.flying.minio.config.NodeConfig;
import io.minio.MinioClient;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * MinIO 客户端管理器
 * 使用 AtomicReference 实现原子替换，解决配置刷新时的竞态条件问题
 */
@Component
@RefreshScope
public class MinioClientManager {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(MinioClientManager.class);

    // 使用 AtomicReference 包装 Map，实现原子替换，避免 clear+putAll 的竞态条件
    private final AtomicReference<Map<String, MinioClient>> clientCacheRef =
            new AtomicReference<>(new ConcurrentHashMap<>());
    private final AtomicReference<Map<String, NodeConfig>> nodeConfigCacheRef =
            new AtomicReference<>(new ConcurrentHashMap<>());

    @Resource
    private MinioProperties minioProperties;

    /**
     * 在应用准备就绪后执行初始化 (使用 @EventListener)
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeClientsOnReady(ApplicationReadyEvent ignoredEvent) {
        log.info("应用准备就绪，正在初始化 MinIO 客户端...");
        reloadClients();
    }

    /**
     * 监听 Nacos 配置刷新事件，重新加载客户端
     */
    @EventListener(EnvironmentChangeEvent.class)
    public void onEnvironmentChangeEvent(EnvironmentChangeEvent event) {
        log.info("检测到配置变更事件，变更的配置键: {}", event.getKeys());
        boolean hasMinioConfigChange = event.getKeys().stream()
                .anyMatch(key -> key.startsWith("minio."));
        if (hasMinioConfigChange) {
            log.info("检测到 MinIO 相关配置变更，重新加载客户端...");
            reloadClients();
        } else {
            log.debug("配置变更不涉及 MinIO，跳过客户端重新加载");
        }
    }

    /**
     * 重新加载所有 MinIO 客户端
     * 使用原子替换避免竞态条件：构建完整的新缓存后一次性替换
     */
    public synchronized void reloadClients() {
        log.info("根据当前配置重新加载 MinIO 客户端...");

        Map<String, NodeConfig> newNodeConfigs = new ConcurrentHashMap<>();
        Map<String, MinioClient> newClientCache = new ConcurrentHashMap<>();

        // 获取当前缓存的快照用于复用判断
        Map<String, MinioClient> oldClientCache = clientCacheRef.get();
        Map<String, NodeConfig> oldNodeConfigCache = nodeConfigCacheRef.get();

        if (minioProperties == null) {
            log.error("MinioProperties bean 为 null，无法加载客户端");
            return;
        }

        if (minioProperties.getNodes() == null) {
            log.warn("MinioProperties 中 'nodes' 列表为 null，不会加载任何客户端");
        } else {
            log.info("在 Nacos 配置中找到 {} 个节点", minioProperties.getNodes().size());

            for (NodeConfig nodeConfig : minioProperties.getNodes()) {
                if (nodeConfig.getName() == null || nodeConfig.getName().isBlank()) {
                    log.error("发现缺少名称的 MinIO 节点配置，跳过...");
                    continue;
                }

                newNodeConfigs.put(nodeConfig.getName(), nodeConfig);

                try {
                    MinioClient existingClient = oldClientCache.get(nodeConfig.getName());
                    NodeConfig oldConfig = oldNodeConfigCache.get(nodeConfig.getName());

                    // 如果配置没有变化且已有客户端，则复用
                    if (oldConfig != null && oldConfig.equals(nodeConfig) && existingClient != null) {
                        log.debug("为节点复用现有的 MinIO 客户端: {}", nodeConfig.getName());
                        newClientCache.put(nodeConfig.getName(), existingClient);
                    } else {
                        log.info("为节点创建/更新 MinIO 客户端: {}", nodeConfig.getName());
                        MinioClient newClient = MinioClient.builder()
                                .endpoint(nodeConfig.getEndpoint())
                                .credentials(nodeConfig.getAccessKey(), nodeConfig.getSecretKey())
                                .build();

                        // 健康检查
                        try {
                            newClient.listBuckets();
                            log.info("已成功连接到 MinIO 节点: {}", nodeConfig.getName());
                        } catch (Exception e) {
                            log.warn("无法连接或验证 MinIO 节点 '{}': {}", nodeConfig.getName(), e.getMessage());
                        }

                        newClientCache.put(nodeConfig.getName(), newClient);
                    }
                } catch (Exception e) {
                    log.error("为节点创建/更新 MinIO 客户端时出错 '{}': {}", nodeConfig.getName(), e.getMessage(), e);
                }
            }
        }

        // 记录被移除的节点
        Set<String> nodesToRemove = oldNodeConfigCache.keySet().stream()
                .filter(name -> !newNodeConfigs.containsKey(name))
                .collect(Collectors.toSet());
        if (!nodesToRemove.isEmpty()) {
            log.info("以下节点已从配置中移除: {}", nodesToRemove);
        }

        // 原子替换：一次性替换整个缓存，避免 clear+putAll 的竞态条件
        clientCacheRef.set(newClientCache);
        nodeConfigCacheRef.set(newNodeConfigs);

        log.info("已完成重新加载 MinIO 节点。当前活跃节点数量: {}", newClientCache.size());
    }

    /**
     * 应用关闭前清理资源
     */
    @PreDestroy
    public void cleanup() {
        log.info("关闭 MinioClientManager...");
        clientCacheRef.set(new ConcurrentHashMap<>());
        nodeConfigCacheRef.set(new ConcurrentHashMap<>());
    }

    /**
     * 根据物理节点名称获取 MinioClient 实例
     *
     * @param nodeName 物理节点名称
     * @return MinioClient 实例，如果找不到则返回 null
     */
    public MinioClient getClient(String nodeName) {
        MinioClient client = clientCacheRef.get().get(nodeName);
        if (client == null) {
            log.error("未找到节点 '{}' 的 MinIO 客户端，请检查 Nacos 配置和节点状态", nodeName);
        }
        return client;
    }

    /**
     * 根据物理节点名称获取节点配置信息
     *
     * @param nodeName 物理节点名称
     * @return 节点配置信息，如果找不到则返回 null
     */
    public NodeConfig getNodeConfig(String nodeName) {
        NodeConfig config = nodeConfigCacheRef.get().get(nodeName);
        if (config == null) {
            log.warn("未找到节点 '{}' 的配置信息，请检查 Nacos 配置", nodeName);
        }
        return config;
    }

    /**
     * 获取所有当前有效的 MinIO 客户端
     *
     * @return 物理节点名称到 MinioClient 的映射
     */
    public Map<String, MinioClient> getAllClients() {
        return clientCacheRef.get();
    }

    /**
     * 获取所有当前有效的物理节点配置信息
     *
     * @return 物理节点名称到 NodeConfig 的映射
     */
    public Map<String, NodeConfig> getAllNodeConfigs() {
        Map<String, NodeConfig> configs = nodeConfigCacheRef.get();
        log.debug("获取所有节点配置，当前缓存大小: {}, 节点: {}", configs.size(), configs.keySet());
        if (configs.isEmpty()) {
            log.warn("节点配置缓存为空，尝试重新加载配置...");
            reloadClients();
            configs = nodeConfigCacheRef.get();
        }
        return configs;
    }

    /**
     * 手动刷新配置（用于调试和故障排除）
     */
    public void manualRefresh() {
        log.info("手动触发配置刷新...");
        reloadClients();
    }
}
