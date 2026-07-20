package cn.flying.storage.core;

import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.config.NodeConfig;
import jakarta.annotation.PreDestroy;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * S3 客户端管理器
 * 使用单一不可变拓扑快照原子替换客户端、预签名器和节点配置，避免刷新时混读不同代数据。
 *
 * <p>{@code @RefreshScope} 使 Spring Cloud (Nacos) 配置变更时重建此 Bean。
 * 如果将来脱离 Nacos，移除此注解并改用其他配置刷新机制即可。
 */
@Component
@RefreshScope
public class S3ClientManager {

    private static final Logger log = LoggerFactory.getLogger(S3ClientManager.class);

    private final AtomicLong topologyRevision = new AtomicLong();
    private final AtomicReference<TopologyState> topologyRef =
            new AtomicReference<>(TopologyState.empty(0L));
    private final ReentrantReadWriteLock topologyGuard = new ReentrantReadWriteLock(true);
    private final StorageProperties storageProperties;

    /**
     * 注入支持动态刷新的存储配置代理。
     *
     * @param storageProperties 当前存储配置
     */
    public S3ClientManager(StorageProperties storageProperties) {
        this.storageProperties = Objects.requireNonNull(storageProperties, "storageProperties");
    }

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
     * 构建完整新拓扑后，在短写保护内一次性发布同代客户端和配置。
     *
     * <p>旧拓扑若仍有 lease 正在执行 S3 I/O，不会立刻关闭；最后一个 lease 释放后才清理
     * 未被新拓扑复用的资源。拓扑构建和健康检查不持有读写保护。</p>
     */
    public synchronized void reloadClients() {
        log.info("根据当前配置重新加载 S3 客户端...");
        TopologyState previous = topologyRef.get();
        Map<String, NodeConfig> newNodeConfigs = new LinkedHashMap<>();
        Map<String, SharedResource<S3Client>> newClientResources = new LinkedHashMap<>();
        Map<String, SharedResource<S3Presigner>> newPresignerResources = new LinkedHashMap<>();

        List<NodeConfig> configuredNodes = snapshotConfiguredNodes();
        log.info("在 Nacos 配置中找到 {} 个节点", configuredNodes.size());
        for (NodeConfig nodeConfig : configuredNodes) {
            if (nodeConfig.getName() == null || nodeConfig.getName().isBlank()) {
                log.error("发现缺少名称的 Storage 节点配置，跳过...");
                continue;
            }
            String nodeName = nodeConfig.getName();
            if (newNodeConfigs.containsKey(nodeName)) {
                log.error("发现重复的 Storage 节点名称 '{}'，忽略后续重复配置", nodeName);
                continue;
            }
            newNodeConfigs.put(nodeName, nodeConfig);
            S3Client createdClient = null;
            S3Presigner createdPresigner = null;
            try {
                SharedResource<S3Client> existingClient =
                        previous.clientResources().get(nodeName);
                SharedResource<S3Presigner> existingPresigner =
                        previous.presignerResources().get(nodeName);
                NodeConfig oldConfig = previous.nodeConfigs().get(nodeName);
                if (oldConfig != null
                        && oldConfig.equals(nodeConfig)
                        && existingClient != null
                        && existingPresigner != null) {
                    log.debug("为节点复用现有的 S3 客户端: {}", nodeName);
                    newClientResources.put(nodeName, existingClient);
                    newPresignerResources.put(nodeName, existingPresigner);
                    continue;
                }

                log.info("为节点创建/更新 S3 客户端: {}", nodeName);
                createdClient = createS3Client(nodeConfig);
                createdPresigner = createPresigner(nodeConfig, effectivePresignerEndpoint(nodeConfig));
                verifyNodeConnection(nodeConfig, createdClient);
                newClientResources.put(nodeName, resolveSharedResource(
                        createdClient,
                        previous.clientResources(),
                        newClientResources,
                        "S3Client"
                ));
                newPresignerResources.put(nodeName, resolveSharedResource(
                        createdPresigner,
                        previous.presignerResources(),
                        newPresignerResources,
                        "S3Presigner"
                ));
            } catch (Exception e) {
                closeClientIfUnmanaged(
                        createdClient,
                        previous.clientResources(),
                        newClientResources,
                        "创建失败的节点 " + nodeName
                );
                closePresignerIfUnmanaged(
                        createdPresigner,
                        previous.presignerResources(),
                        newPresignerResources,
                        "创建失败的节点 " + nodeName
                );
                log.error("为节点创建/更新 S3 客户端时出错 '{}': {}", nodeName, e.getMessage(), e);
            }
        }

        Set<String> removedNodes = previous.nodeConfigs().keySet().stream()
                .filter(name -> !newNodeConfigs.containsKey(name))
                .collect(Collectors.toSet());
        if (!removedNodes.isEmpty()) {
            log.info("以下节点已从配置中移除: {}", removedNodes);
        }

        TopologyState replacement = new TopologyState(
                topologyRevision.incrementAndGet(),
                newClientResources,
                newPresignerResources,
                newNodeConfigs
        );
        try {
            beforeTopologyPublish(replacement.revision());
        } catch (RuntimeException | Error e) {
            replacement.retire();
            throw e;
        }
        topologyGuard.writeLock().lock();
        try {
            topologyRef.set(replacement);
        } finally {
            topologyGuard.writeLock().unlock();
        }
        previous.retire();
        log.info("已完成重新加载 S3 节点。revision={}, 当前活跃节点数量: {}",
                replacement.revision(), replacement.clients().size());
    }

    /**
     * 应用关闭前原子撤销当前拓扑；仍被 lease 使用的 AWS 资源延迟到 lease 释放后关闭。
     */
    @PreDestroy
    public synchronized void cleanup() {
        TopologyState retired;
        TopologyState empty = TopologyState.empty(topologyRevision.incrementAndGet());
        topologyGuard.writeLock().lock();
        try {
            retired = topologyRef.getAndSet(empty);
        } finally {
            topologyGuard.writeLock().unlock();
        }
        log.info("关闭 S3ClientManager，撤销 topology revision={} 的 {} 个客户端...",
                retired.revision(), retired.clients().size());
        retired.retire();
        log.info("S3ClientManager 资源已清理或等待活跃 lease 释放");
    }

    /**
     * 获取同一 revision 的客户端和配置 lease；调用方必须使用 try-with-resources 释放。
     *
     * @return 当前不可变拓扑 lease
     */
    public TopologyLease acquireTopologyLease() {
        topologyGuard.readLock().lock();
        try {
            TopologyState state = topologyRef.get();
            state.acquire();
            return new ManagedTopologyLease(state);
        } finally {
            topologyGuard.readLock().unlock();
        }
    }

    /**
     * 根据物理节点名称获取 S3Client 实例
     *
     * @param nodeName 物理节点名称
     * @return S3Client 实例，如果找不到则返回 null
     */
    public S3Client getClient(String nodeName) {
        S3Client client = topologyRef.get().clients().get(nodeName);
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
        S3Presigner presigner = topologyRef.get().presigners().get(nodeName);
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
        NodeConfig config = topologyRef.get().nodeConfigs().get(nodeName);
        if (config == null) {
            log.warn("未找到节点 '{}' 的配置信息，请检查 Nacos 配置", nodeName);
        }
        return copyNodeConfig(config);
    }

    /**
     * 获取所有当前有效的 S3 客户端
     *
     * @return 物理节点名称到 S3Client 的映射
     */
    public Map<String, S3Client> getAllClients() {
        return topologyRef.get().clients();
    }

    /**
     * 获取所有当前有效的物理节点配置信息
     *
     * @return 物理节点名称到 NodeConfig 的映射
     */
    public Map<String, NodeConfig> getAllNodeConfigs() {
        Map<String, NodeConfig> configs = topologyRef.get().nodeConfigs();
        log.debug("获取所有节点配置，当前缓存大小: {}, 节点: {}", configs.size(), configs.keySet());
        if (configs.isEmpty()) {
            log.warn("节点配置缓存为空，尝试重新加载配置...");
            reloadClients();
            configs = topologyRef.get().nodeConfigs();
        }
        return copyNodeConfigMap(configs);
    }

    /**
     * 手动刷新配置（用于调试和故障排除）
     */
    public void manualRefresh() {
        log.info("手动触发配置刷新...");
        reloadClients();
    }

    /**
     * 创建内部访问使用的 S3 客户端；protected 可让无网络单元测试提供受控替身。
     *
     * @param nodeConfig 已复制的节点配置
     * @return 新 S3 客户端
     */
    protected S3Client createS3Client(NodeConfig nodeConfig) {
        return S3Client.builder()
                .endpointOverride(URI.create(nodeConfig.getEndpoint()))
                .region(Region.of("us-east-1"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(nodeConfig.getAccessKey(), nodeConfig.getSecretKey())
                ))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .httpClient(ApacheHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(10))
                        .socketTimeout(Duration.ofSeconds(60))
                        .build())
                .build();
    }

    /**
     * 创建预签名客户端；endpoint 已在同一轮 reload 中确定。
     *
     * @param nodeConfig 已复制的节点配置
     * @param endpoint 预签名 URL endpoint
     * @return 新预签名客户端
     */
    protected S3Presigner createPresigner(NodeConfig nodeConfig, String endpoint) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of("us-east-1"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(nodeConfig.getAccessKey(), nodeConfig.getSecretKey())
                ))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    /**
     * 在新 topology 构建完成、进入原子发布前执行扩展钩子；生产默认无操作。
     *
     * @param revision 即将发布的 topology revision
     */
    protected void beforeTopologyPublish(long revision) {
        // 供确定性并发测试观察线性化边界。
    }

    /**
     * 快照当前节点列表，防止随后修改原配置对象污染已发布拓扑。
     *
     * @return 节点配置深复制列表
     */
    private List<NodeConfig> snapshotConfiguredNodes() {
        List<NodeConfig> nodes = storageProperties.getNodes();
        if (nodes == null) {
            log.warn("StorageProperties 中 'nodes' 列表为 null，不会加载任何客户端");
            return List.of();
        }
        return nodes.stream().map(S3ClientManager::copyNodeConfig).toList();
    }

    /**
     * 解析本轮节点的预签名 endpoint。
     */
    private String effectivePresignerEndpoint(NodeConfig nodeConfig) {
        String endpoint = storageProperties.hasExternalEndpoint()
                ? storageProperties.getEffectiveExternalEndpoint()
                : nodeConfig.getEndpoint();
        if (storageProperties.hasExternalEndpoint()) {
            log.debug("节点 '{}' 的预签名 URL 将使用外部端点: {}", nodeConfig.getName(), endpoint);
        }
        return endpoint;
    }

    /**
     * 对新建客户端执行非阻断发布的健康探测；探测失败只记录节点状态。
     */
    private void verifyNodeConnection(NodeConfig nodeConfig, S3Client client) {
        try {
            client.listBuckets();
            log.info("已成功连接到 S3-compatible storage 节点: {}", nodeConfig.getName());
        } catch (S3Exception e) {
            String message = e.awsErrorDetails() == null
                    ? e.getMessage()
                    : e.awsErrorDetails().errorMessage();
            String code = e.awsErrorDetails() == null
                    ? ""
                    : e.awsErrorDetails().errorCode();
            log.warn("无法连接到 S3 节点 '{}': {} (code: {})", nodeConfig.getName(), message, code);
        } catch (Exception e) {
            log.warn("无法验证 S3 节点 '{}': {}", nodeConfig.getName(), e.getMessage());
        }
    }

    /**
     * 深复制可变 NodeConfig，作为拓扑状态内部不可变值。
     */
    private static NodeConfig copyNodeConfig(NodeConfig source) {
        if (source == null) {
            return null;
        }
        NodeConfig copy = new NodeConfig();
        copy.setName(source.getName());
        copy.setEndpoint(source.getEndpoint());
        copy.setPhysicalStorageId(source.getPhysicalStorageId());
        copy.setAccessKey(source.getAccessKey());
        copy.setSecretKey(source.getSecretKey());
        copy.setFaultDomain(source.getFaultDomain());
        copy.setWeight(source.getWeight());
        copy.setEnabled(source.getEnabled());
        copy.setMetricsPath(source.getMetricsPath());
        return copy;
    }

    /**
     * 返回不会暴露内部可变 NodeConfig 的深复制映射。
     */
    private static Map<String, NodeConfig> copyNodeConfigMap(Map<String, NodeConfig> source) {
        Map<String, NodeConfig> copy = new LinkedHashMap<>();
        source.forEach((name, config) -> copy.put(name, copyNodeConfig(config)));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * 按底层对象身份复用跨 topology 的资源 owner，避免同一实例被重复关闭。
     *
     * @param resource 本轮得到的底层资源
     * @param previousResources 当前 topology 的资源 owner
     * @param stagedResources 本轮已暂存的资源 owner
     * @param resourceType 日志中的资源类型
     * @return 已存在或新建的共享资源 owner
     */
    private static <T extends AutoCloseable> SharedResource<T> resolveSharedResource(
            T resource,
            Map<String, SharedResource<T>> previousResources,
            Map<String, SharedResource<T>> stagedResources,
            String resourceType
    ) {
        SharedResource<T> existing = findSharedResource(resource, stagedResources);
        if (existing == null) {
            existing = findSharedResource(resource, previousResources);
        }
        return existing == null ? new SharedResource<>(resource, resourceType) : existing;
    }

    /**
     * 按对象身份查找资源 owner，不调用可能被代理覆盖的 equals。
     */
    private static <T extends AutoCloseable> SharedResource<T> findSharedResource(
            T resource,
            Map<String, SharedResource<T>> resources
    ) {
        if (resource == null) {
            return null;
        }
        for (SharedResource<T> owner : resources.values()) {
            if (owner.resource() == resource) {
                return owner;
            }
        }
        return null;
    }

    /**
     * 创建过程失败时，只关闭尚未由当前或待发布 topology 管理的客户端。
     */
    private static void closeClientIfUnmanaged(
            S3Client client,
            Map<String, SharedResource<S3Client>> previousResources,
            Map<String, SharedResource<S3Client>> stagedResources,
            String context
    ) {
        if (findSharedResource(client, stagedResources) == null
                && findSharedResource(client, previousResources) == null) {
            closeClient(client, context);
        }
    }

    /**
     * 创建过程失败时，只关闭尚未由当前或待发布 topology 管理的预签名器。
     */
    private static void closePresignerIfUnmanaged(
            S3Presigner presigner,
            Map<String, SharedResource<S3Presigner>> previousResources,
            Map<String, SharedResource<S3Presigner>> stagedResources,
            String context
    ) {
        if (findSharedResource(presigner, stagedResources) == null
                && findSharedResource(presigner, previousResources) == null) {
            closePresigner(presigner, context);
        }
    }

    /**
     * 安全关闭一个 S3 客户端。
     */
    private static void closeClient(S3Client client, String context) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (Exception e) {
            log.warn("关闭 {} S3Client 时出错: {}", context, e.getMessage());
        }
    }

    /**
     * 安全关闭一个预签名客户端。
     */
    private static void closePresigner(S3Presigner presigner, String context) {
        if (presigner == null) {
            return;
        }
        try {
            presigner.close();
        } catch (Exception e) {
            log.warn("关闭 {} S3Presigner 时出错: {}", context, e.getMessage());
        }
    }

    /**
     * 供长 S3 I/O 使用的同代客户端/配置视图，以及最终提交使用的短稳定保护。
     */
    public interface TopologyLease extends AutoCloseable {

        /** 返回本 lease 固化的拓扑 revision。 */
        long revision();

        /** 返回本 revision 的节点客户端。 */
        S3Client getClient(String nodeName);

        /** 返回本 revision 的节点预签名器。 */
        S3Presigner getPresigner(String nodeName);

        /** 返回本 revision 的节点配置副本。 */
        NodeConfig getNodeConfig(String nodeName);

        /** 仅校验本 revision 仍是当前 revision。 */
        void verifyCurrent();

        /** 在阻止 reload/cleanup 穿越的短读保护内执行最终提交临界区。 */
        void runIfCurrent(Runnable action);

        /** 释放旧 topology 资源保留。 */
        @Override
        void close();
    }

    /**
     * 将外部 lease 操作绑定到创建它的 manager 实例，避免 RefreshScope 代理换代后误路由。
     */
    private final class ManagedTopologyLease implements TopologyLease {
        private final TopologyState state;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ManagedTopologyLease(TopologyState state) {
            this.state = state;
        }

        @Override
        public long revision() {
            ensureOpen();
            return state.revision();
        }

        @Override
        public S3Client getClient(String nodeName) {
            ensureOpen();
            return state.clients().get(nodeName);
        }

        @Override
        public S3Presigner getPresigner(String nodeName) {
            ensureOpen();
            return state.presigners().get(nodeName);
        }

        @Override
        public NodeConfig getNodeConfig(String nodeName) {
            ensureOpen();
            return copyNodeConfig(state.nodeConfigs().get(nodeName));
        }

        @Override
        public void verifyCurrent() {
            runIfCurrent(() -> {
                // 短读保护本身即为 revision 校验。
            });
        }

        @Override
        public void runIfCurrent(Runnable action) {
            if (action == null) {
                throw new IllegalArgumentException("topology action must not be null");
            }
            ensureOpen();
            topologyGuard.readLock().lock();
            try {
                ensureOpen();
                if (topologyRef.get() != state || state.isRetired()) {
                    throw new IllegalStateException(
                            "S3 topology changed during operation; expected revision " + state.revision());
                }
                action.run();
            } finally {
                topologyGuard.readLock().unlock();
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                state.release();
            }
        }

        /** 校验调用方没有在释放后继续使用 lease。 */
        private void ensureOpen() {
            if (closed.get()) {
                throw new IllegalStateException("S3 topology lease is already closed");
            }
        }
    }

    /**
     * 一代完整 topology 状态；每代只持有共享资源 owner，不直接决定底层实例关闭时机。
     */
    private static final class TopologyState {
        private final long revision;
        private final Map<String, SharedResource<S3Client>> clientResources;
        private final Map<String, SharedResource<S3Presigner>> presignerResources;
        private final Map<String, S3Client> clients;
        private final Map<String, S3Presigner> presigners;
        private final Map<String, NodeConfig> nodeConfigs;
        private final List<SharedResource<?>> ownedResources;
        private int leases;
        private boolean retired;
        private boolean ownershipReleased;

        private TopologyState(
                long revision,
                Map<String, SharedResource<S3Client>> clientResources,
                Map<String, SharedResource<S3Presigner>> presignerResources,
                Map<String, NodeConfig> nodeConfigs
        ) {
            this.revision = revision;
            this.clientResources = Collections.unmodifiableMap(
                    new LinkedHashMap<>(clientResources));
            this.presignerResources = Collections.unmodifiableMap(
                    new LinkedHashMap<>(presignerResources));
            this.clients = unwrapResources(this.clientResources);
            this.presigners = unwrapResources(this.presignerResources);
            this.nodeConfigs = copyNodeConfigMap(nodeConfigs);
            this.ownedResources = retainUniqueResources(
                    this.clientResources.values(),
                    this.presignerResources.values()
            );
        }

        private static TopologyState empty(long revision) {
            return new TopologyState(revision, Map.of(), Map.of(), Map.of());
        }

        private long revision() {
            return revision;
        }

        private Map<String, S3Client> clients() {
            return clients;
        }

        private Map<String, S3Presigner> presigners() {
            return presigners;
        }

        private Map<String, SharedResource<S3Client>> clientResources() {
            return clientResources;
        }

        private Map<String, SharedResource<S3Presigner>> presignerResources() {
            return presignerResources;
        }

        private Map<String, NodeConfig> nodeConfigs() {
            return nodeConfigs;
        }

        /** 增加活跃 lease 引用。 */
        private synchronized void acquire() {
            if (retired) {
                throw new IllegalStateException("S3 topology is already retired");
            }
            leases++;
        }

        /** 标记拓扑退役，并在无 lease 时释放本代对共享资源 owner 的引用。 */
        private void retire() {
            List<SharedResource<?>> resources;
            synchronized (this) {
                if (retired) {
                    return;
                }
                retired = true;
                resources = detachOwnedResourcesIfUnused();
            }
            releaseOwners(resources, revision);
        }

        /** 释放一个 lease，并在最后一个引用退出时释放本代资源 ownership。 */
        private void release() {
            List<SharedResource<?>> resources;
            synchronized (this) {
                if (leases <= 0) {
                    throw new IllegalStateException("S3 topology lease reference underflow");
                }
                leases--;
                resources = detachOwnedResourcesIfUnused();
            }
            releaseOwners(resources, revision);
        }

        /** 返回该状态是否已经退役。 */
        private synchronized boolean isRetired() {
            return retired;
        }

        /** 在退役且无 lease 时一次性移交本代 owner 引用，防止重复释放。 */
        private List<SharedResource<?>> detachOwnedResourcesIfUnused() {
            if (!retired || leases > 0 || ownershipReleased) {
                return List.of();
            }
            ownershipReleased = true;
            return ownedResources;
        }

        /** 将资源 owner 映射解包为调用方只读的底层实例映射。 */
        private static <T extends AutoCloseable> Map<String, T> unwrapResources(
                Map<String, SharedResource<T>> resources
        ) {
            Map<String, T> unwrapped = new LinkedHashMap<>();
            resources.forEach((name, owner) -> unwrapped.put(name, owner.resource()));
            return Collections.unmodifiableMap(unwrapped);
        }

        /**
         * 按 owner 身份去重后为本 topology 增加一次 ownership，构造失败时回滚已增加引用。
         */
        private static List<SharedResource<?>> retainUniqueResources(
                Iterable<? extends SharedResource<?>> clients,
                Iterable<? extends SharedResource<?>> presigners
        ) {
            Set<SharedResource<?>> identities =
                    Collections.newSetFromMap(new IdentityHashMap<>());
            List<SharedResource<?>> uniqueResources = new ArrayList<>();
            clients.forEach(owner -> addIdentityOnce(owner, identities, uniqueResources));
            presigners.forEach(owner -> addIdentityOnce(owner, identities, uniqueResources));

            List<SharedResource<?>> retained = new ArrayList<>();
            try {
                for (SharedResource<?> resource : uniqueResources) {
                    resource.retainOwner();
                    retained.add(resource);
                }
                return List.copyOf(uniqueResources);
            } catch (RuntimeException | Error e) {
                releaseOwners(retained, -1L);
                throw e;
            }
        }

        /** 将一个 owner 按身份至多加入一次。 */
        private static void addIdentityOnce(
                SharedResource<?> owner,
                Set<SharedResource<?>> identities,
                List<SharedResource<?>> resources
        ) {
            if (identities.add(owner)) {
                resources.add(owner);
            }
        }

        /** 释放一组 topology ownership，底层资源仅在最后一个 owner 退出时关闭。 */
        private static void releaseOwners(
                Iterable<? extends SharedResource<?>> resources,
                long revision
        ) {
            for (SharedResource<?> resource : resources) {
                resource.releaseOwner(revision);
            }
        }
    }

    /**
     * 一个底层客户端或预签名器的共享 owner；owner 计数跨 topology 代际延续。
     */
    private static final class SharedResource<T extends AutoCloseable> {
        private final T resource;
        private final String resourceType;
        private int owners;
        private boolean closed;

        private SharedResource(T resource, String resourceType) {
            this.resource = Objects.requireNonNull(resource, "resource");
            this.resourceType = resourceType;
        }

        /** 返回 owner 管理的底层资源实例。 */
        private T resource() {
            return resource;
        }

        /** 为一个已构造 topology 增加 ownership。 */
        private synchronized void retainOwner() {
            if (closed) {
                throw new IllegalStateException(resourceType + " resource is already closed");
            }
            owners++;
        }

        /** 释放一个 topology ownership，并且仅由最后一个 owner 关闭底层资源。 */
        private void releaseOwner(long revision) {
            boolean closeNow;
            synchronized (this) {
                if (owners <= 0) {
                    throw new IllegalStateException(resourceType + " owner reference underflow");
                }
                owners--;
                closeNow = owners == 0;
                if (closeNow) {
                    closed = true;
                }
            }
            if (!closeNow) {
                return;
            }
            try {
                resource.close();
            } catch (Exception e) {
                log.warn("关闭 topology revision {} 的 {} 时出错: {}",
                        revision, resourceType, e.getMessage());
            }
        }
    }
}
