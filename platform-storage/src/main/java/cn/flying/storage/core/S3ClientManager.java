package cn.flying.storage.core;

import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.config.NodeConfig;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * S3 客户端管理器
 * 使用 AtomicReference 实现原子替换，解决配置刷新时的竞态条件问题
 */
@Component
@RefreshScope
public class S3ClientManager {

    private static final Logger log = LoggerFactory.getLogger(S3ClientManager.class);

    // 使用 AtomicReference 包装 Map，实现原子替换，避免 clear+putAll 的竞态条件
    private final AtomicReference<Map<String, S3Client>> clientCacheRef =
            new AtomicReference<>(new ConcurrentHashMap<>());
    private final AtomicReference<Map<String, S3Presigner>> presignerCacheRef =
            new AtomicReference<>(new ConcurrentHashMap<>());
    private final AtomicReference<Map<String, NodeConfig>> nodeConfigCacheRef =
            new AtomicReference<>(new ConcurrentHashMap<>());

    @Resource
    private StorageProperties storageProperties;

    /**
     * 在应用准备就绪后执行初始化 (使用 @EventListener)
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeClientsOnReady(ApplicationReadyEvent ignoredEvent) {
        log.info("应用准备就绪，正在初始化 S3 客户端...");
        reloadClients();
    }

    /**
     * 监听 Nacos 配置刷新事件，重新加载客户端
     */
    @EventListener(EnvironmentChangeEvent.class)
    public void onEnvironmentChangeEvent(EnvironmentChangeEvent event) {
        log.info("检测到配置变更事件，变更的配置键: {}", event.getKeys());
        boolean hasStorageConfigChange = event.getKeys().stream()
                .anyMatch(key -> key.startsWith("storage."));
        if (hasStorageConfigChange) {
            log.info("检测到 Storage 相关配置变更，重新加载客户端...");
            reloadClients();
        } else {
            log.debug("配置变更不涉及 Storage，跳过客户端重新加载");
        }
    }

    /**
     * 重新加载所有 S3 客户端
     * 使用原子替换避免竞态条件：构建完整的新缓存后一次性替换
     */
    public synchronized void reloadClients() {
        log.info("根据当前配置重新加载 S3 客户端...");

        Map<String, NodeConfig> newNodeConfigs = new ConcurrentHashMap<>();
        Map<String, S3Client> newClientCache = new ConcurrentHashMap<>();
        Map<String, S3Presigner> newPresignerCache = new ConcurrentHashMap<>();

        // 获取当前缓存的快照用于复用判断
        Map<String, S3Client> oldClientCache = clientCacheRef.get();
        Map<String, S3Presigner> oldPresignerCache = presignerCacheRef.get();
        Map<String, NodeConfig> oldNodeConfigCache = nodeConfigCacheRef.get();

        if (storageProperties == null) {
            log.error("StorageProperties bean 为 null，无法加载客户端");
            return;
        }

        if (storageProperties.getNodes() == null) {
            log.warn("StorageProperties 中 'nodes' 列表为 null，不会加载任何客户端");
        } else {
            log.info("在 Nacos 配置中找到 {} 个节点", storageProperties.getNodes().size());

            for (NodeConfig nodeConfig : storageProperties.getNodes()) {
                if (nodeConfig.getName() == null || nodeConfig.getName().isBlank()) {
                    log.error("发现缺少名称的 Storage 节点配置，跳过...");
                    continue;
                }

                newNodeConfigs.put(nodeConfig.getName(), nodeConfig);

                try {
                    S3Client existingClient = oldClientCache.get(nodeConfig.getName());
                    S3Presigner existingPresigner = oldPresignerCache.get(nodeConfig.getName());
                    NodeConfig oldConfig = oldNodeConfigCache.get(nodeConfig.getName());

                    // 如果配置没有变化且已有客户端，则复用
                    if (oldConfig != null && oldConfig.equals(nodeConfig) &&
                        existingClient != null && existingPresigner != null) {
                        log.debug("为节点复用现有的 S3 客户端: {}", nodeConfig.getName());
                        newClientCache.put(nodeConfig.getName(), existingClient);
                        newPresignerCache.put(nodeConfig.getName(), existingPresigner);
                    } else {
                        log.info("为节点创建/更新 S3 客户端: {}", nodeConfig.getName());

                        // 创建 S3Client
                        S3Client newClient = S3Client.builder()
                                .endpointOverride(URI.create(nodeConfig.getEndpoint()))
                                .region(Region.of("us-east-1"))  // 固定区域，S3兼容存储通常忽略此设置
                                .credentialsProvider(StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create(nodeConfig.getAccessKey(), nodeConfig.getSecretKey())
                                ))
                                .serviceConfiguration(S3Configuration.builder()
                                        .pathStyleAccessEnabled(true)  // S3 兼容存储需要路径样式访问
                                        .build())
                                .httpClient(ApacheHttpClient.builder()
                                        .connectionTimeout(Duration.ofSeconds(10))
                                        .socketTimeout(Duration.ofSeconds(60))
                                        .build())
                                .build();

                        // 创建 S3Presigner（用于生成预签名 URL）
                        S3Presigner presigner = S3Presigner.builder()
                                .endpointOverride(URI.create(nodeConfig.getEndpoint()))
                                .region(Region.of("us-east-1"))
                                .credentialsProvider(StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create(nodeConfig.getAccessKey(), nodeConfig.getSecretKey())
                                ))
                                .serviceConfiguration(S3Configuration.builder()
                                        .pathStyleAccessEnabled(true)
                                        .build())
                                .build();

                        // 健康检查
                        try {
                            newClient.listBuckets();
                            log.info("已成功连接到 S3-compatible storage 节点: {}", nodeConfig.getName());
                        } catch (S3Exception e) {
                            log.warn("无法连接到 S3 节点 '{}': {} (code: {})",
                                    nodeConfig.getName(),
                                    e.awsErrorDetails().errorMessage(),
                                    e.awsErrorDetails().errorCode());
                        } catch (Exception e) {
                            log.warn("无法验证 S3 节点 '{}': {}", nodeConfig.getName(), e.getMessage());
                        }

                        newClientCache.put(nodeConfig.getName(), newClient);
                        newPresignerCache.put(nodeConfig.getName(), presigner);

                        // 关闭旧客户端（如果存在）
                        if (existingClient != null) {
                            try {
                                existingClient.close();
                            } catch (Exception e) {
                                log.warn("关闭旧 S3Client 时出错: {}", e.getMessage());
                            }
                        }
                        if (existingPresigner != null) {
                            try {
                                existingPresigner.close();
                            } catch (Exception e) {
                                log.warn("关闭旧 S3Presigner 时出错: {}", e.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("为节点创建/更新 S3 客户端时出错 '{}': {}", nodeConfig.getName(), e.getMessage(), e);
                }
            }
        }

        // 记录被移除的节点
        Set<String> nodesToRemove = oldNodeConfigCache.keySet().stream()
                .filter(name -> !newNodeConfigs.containsKey(name))
                .collect(Collectors.toSet());
        if (!nodesToRemove.isEmpty()) {
            log.info("以下节点已从配置中移除: {}", nodesToRemove);
            // 关闭被移除节点的客户端
            nodesToRemove.forEach(name -> {
                S3Client client = oldClientCache.get(name);
                if (client != null) {
                    try {
                        client.close();
                        log.debug("已关闭节点 [{}] 的 S3Client", name);
                    } catch (Exception e) {
                        log.warn("关闭节点 [{}] 的 S3Client 时出错: {}", name, e.getMessage());
                    }
                }
                S3Presigner presigner = oldPresignerCache.get(name);
                if (presigner != null) {
                    try {
                        presigner.close();
                        log.debug("已关闭节点 [{}] 的 S3Presigner", name);
                    } catch (Exception e) {
                        log.warn("关闭节点 [{}] 的 S3Presigner 时出错: {}", name, e.getMessage());
                    }
                }
            });
        }

        // 原子替换：一次性替换整个缓存，避免 clear+putAll 的竞态条件
        clientCacheRef.set(newClientCache);
        presignerCacheRef.set(newPresignerCache);
        nodeConfigCacheRef.set(newNodeConfigs);

        log.info("已完成重新加载 S3 节点。当前活跃节点数量: {}", newClientCache.size());
    }

    /**
     * 应用关闭前清理资源
     * AWS SDK 需要显式关闭客户端以释放资源
     */
    @PreDestroy
    public void cleanup() {
        log.info("关闭 S3ClientManager，清理 {} 个客户端缓存...", clientCacheRef.get().size());

        // 关闭所有 S3Client
        clientCacheRef.get().values().forEach(client -> {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("关闭 S3Client 时出错: {}", e.getMessage());
            }
        });

        // 关闭所有 S3Presigner
        presignerCacheRef.get().values().forEach(presigner -> {
            try {
                presigner.close();
            } catch (Exception e) {
                log.warn("关闭 S3Presigner 时出错: {}", e.getMessage());
            }
        });

        clientCacheRef.set(new ConcurrentHashMap<>());
        presignerCacheRef.set(new ConcurrentHashMap<>());
        nodeConfigCacheRef.set(new ConcurrentHashMap<>());
        log.info("S3ClientManager 资源已清理");
    }

    /**
     * 根据物理节点名称获取 S3Client 实例
     *
     * @param nodeName 物理节点名称
     * @return S3Client 实例，如果找不到则返回 null
     */
    public S3Client getClient(String nodeName) {
        S3Client client = clientCacheRef.get().get(nodeName);
        if (client == null) {
            log.error("未找到节点 '{}' 的 S3 客户端，请检查 Nacos 配置和节点状态", nodeName);
        }
        return client;
    }

    /**
     * 根据物理节点名称获取 S3Presigner 实例
     *
     * @param nodeName 物理节点名称
     * @return S3Presigner 实例，如果找不到则返回 null
     */
    public S3Presigner getPresigner(String nodeName) {
        S3Presigner presigner = presignerCacheRef.get().get(nodeName);
        if (presigner == null) {
            log.error("未找到节点 '{}' 的 S3Presigner，请检查 Nacos 配置和节点状态", nodeName);
        }
        return presigner;
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
     * 获取所有当前有效的 S3 客户端
     *
     * @return 物理节点名称到 S3Client 的映射
     */
    public Map<String, S3Client> getAllClients() {
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
