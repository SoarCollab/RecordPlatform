package cn.flying.storage.core;

import cn.flying.storage.config.FaultDomainConfig;
import cn.flying.storage.config.NodeConfig;
import cn.flying.storage.config.StorageProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 故障域管理器
 * 负责管理各故障域的一致性哈希环，提供分片分布和读取负载均衡功能
 *
 * <p>核心职责:
 * <ul>
 *   <li>维护每个故障域的一致性哈希环</li>
 *   <li>根据分片哈希确定目标节点（按配置的活跃域和副本因子）</li>
 *   <li>根据负载评分选择最优读取节点</li>
 *   <li>响应配置刷新事件，重建哈希环</li>
 * </ul>
 *
 * <p>支持模式:
 * <ul>
 *   <li>单域模式：开发环境，无跨域副本</li>
 *   <li>多域模式：生产环境，按 replicationFactor 配置副本数</li>
 * </ul>
 */
@Slf4j
@Component
@RefreshScope
public class FaultDomainManager {

    @Resource
    private StorageProperties storageProperties;

    @Resource
    private S3Monitor s3Monitor;

    /**
     * 每个故障域的一致性哈希环
     * 使用 AtomicReference 实现原子替换
     */
    private final AtomicReference<Map<String, ConsistentHashRing>> domainRingsRef =
            new AtomicReference<>(new ConcurrentHashMap<>());

    /**
     * 每个故障域包含的节点集合
     */
    private final AtomicReference<Map<String, Set<String>>> domainNodesRef =
            new AtomicReference<>(new ConcurrentHashMap<>());

    /**
     * 故障域配置缓存
     */
    private final AtomicReference<Map<String, FaultDomainConfig>> domainConfigsRef =
            new AtomicReference<>(new ConcurrentHashMap<>());

    /**
     * 初始化哈希环
     */
    @PostConstruct
    public void init() {
        rebuildRings();
    }

    /**
     * 监听配置刷新事件，重建哈希环
     */
    @EventListener({RefreshScopeRefreshedEvent.class, ContextRefreshedEvent.class})
    public void onRefresh() {
        log.info("检测到配置变更，重建故障域哈希环");
        rebuildRings();
    }

    /**
     * 重建所有故障域的哈希环
     */
    public synchronized void rebuildRings() {
        Map<String, ConsistentHashRing> newRings = new ConcurrentHashMap<>();
        Map<String, Set<String>> newDomainNodes = new ConcurrentHashMap<>();
        Map<String, FaultDomainConfig> newDomainConfigs = new ConcurrentHashMap<>();

        List<NodeConfig> nodes = storageProperties.getNodes();
        List<FaultDomainConfig> domains = storageProperties.getDomains();
        int virtualNodesPerNode = storageProperties.getVirtualNodesPerNode();

        // 缓存域配置
        if (!CollectionUtils.isEmpty(domains)) {
            for (FaultDomainConfig domain : domains) {
                newDomainConfigs.put(domain.getName(), domain);
            }
        }

        // 构建每个域的哈希环
        if (!CollectionUtils.isEmpty(nodes)) {
            for (NodeConfig node : nodes) {
                // 跳过未启用的节点
                if (!Boolean.TRUE.equals(node.getEnabled())) {
                    continue;
                }

                String domain = node.getFaultDomain();
                if (domain == null || domain.isBlank()) {
                    log.warn("节点 {} 未配置故障域，跳过", node.getName());
                    continue;
                }

                // 添加到域节点集合
                newDomainNodes.computeIfAbsent(domain, k -> ConcurrentHashMap.newKeySet())
                        .add(node.getName());

                // 添加到域哈希环
                newRings.computeIfAbsent(domain, k -> new ConsistentHashRing(virtualNodesPerNode))
                        .addNode(node.getName(), node.getWeight() != null ? node.getWeight() : 100);
            }
        }

        // 原子替换
        domainRingsRef.set(newRings);
        domainNodesRef.set(newDomainNodes);
        domainConfigsRef.set(newDomainConfigs);

        log.info("故障域哈希环重建完成: {}", newDomainNodes.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue().size() + "节点")
                .collect(Collectors.joining(", ")));
    }

    // ==================== 活跃域访问方法 ====================

    /**
     * 获取配置的活跃域列表
     *
     * @return 活跃域名称列表
     */
    public List<String> getActiveDomains() {
        return storageProperties.getActiveDomains();
    }

    /**
     * 判断是否为单域模式
     *
     * @return true 如果只有一个活跃域
     */
    public boolean isSingleDomainMode() {
        return storageProperties.isSingleDomainMode();
    }

    /**
     * 获取备用域名称
     *
     * @return 备用域名称，如果未配置返回 null
     */
    public String getStandbyDomainName() {
        return storageProperties.getStandbyDomain();
    }

    /**
     * 检查备用域是否启用
     *
     * @return true 如果备用域已配置
     */
    public boolean isStandbyEnabled() {
        return storageProperties.isStandbyEnabled();
    }

    // ==================== 写入相关方法 ====================

    /**
     * 获取分片应该存储的目标节点列表
     * 按配置的活跃域顺序和副本因子选择目标节点
     *
     * @param chunkHash 分片哈希值
     * @return 目标节点列表（数量由 replicationFactor 决定）
     */
    public List<String> getTargetNodes(String chunkHash) {
        List<String> targets = new ArrayList<>();
        Map<String, ConsistentHashRing> rings = domainRingsRef.get();

        List<FaultDomainConfig> writableDomains = getWritableDomains();
        int replicationFactor = storageProperties.getEffectiveReplicationFactor();
        int replicasNeeded = Math.min(replicationFactor, writableDomains.size());

        // 按配置顺序遍历可写入的活跃域
        for (int i = 0; i < replicasNeeded && i < writableDomains.size(); i++) {
            FaultDomainConfig domainConfig = writableDomains.get(i);
            String domainName = domainConfig.getName();
            ConsistentHashRing ring = rings.get(domainName);

            if (ring == null || ring.isEmpty()) {
                log.warn("域 {} 没有可用节点", domainName);
                continue;
            }

            // 使用一致性哈希获取目标节点
            String targetNode = ring.getNode(chunkHash);
            if (targetNode != null && s3Monitor.isNodeOnline(targetNode)) {
                targets.add(targetNode);
            } else {
                // 回退：在该域内寻找任意健康节点
                String fallback = findHealthyNodeInDomain(domainName);
                if (fallback != null) {
                    targets.add(fallback);
                    log.debug("域 {} 目标节点 {} 不可用，使用回退节点 {}", domainName, targetNode, fallback);
                } else {
                    log.warn("域 {} 没有健康节点可用", domainName);
                }
            }
        }

        return targets;
    }

    /**
     * 获取所有可写入的活跃故障域
     * 按 active-domains 配置顺序返回
     *
     * @return 可写入的域配置列表
     */
    public List<FaultDomainConfig> getWritableDomains() {
        Map<String, FaultDomainConfig> configs = domainConfigsRef.get();
        List<FaultDomainConfig> writable = new ArrayList<>();

        // 按配置的活跃域顺序处理
        for (String domainName : getActiveDomains()) {
            FaultDomainConfig config = configs.get(domainName);
            if (config != null && Boolean.TRUE.equals(config.getAcceptsWrites())) {
                writable.add(config);
            } else if (config == null) {
                // 如果没有显式域配置，创建默认配置
                FaultDomainConfig defaultConfig = new FaultDomainConfig();
                defaultConfig.setName(domainName);
                defaultConfig.setAcceptsWrites(true);
                defaultConfig.setReplicaCount(1);
                writable.add(defaultConfig);
            }
            // 如果 config.acceptsWrites == false，则跳过该域
        }

        return writable;
    }

    // ==================== 读取相关方法 ====================

    /**
     * 获取分片可能存在的所有候选节点
     * 用于读取时的故障转移
     *
     * @param chunkHash 分片哈希值
     * @return 候选节点列表
     */
    public List<String> getCandidateNodes(String chunkHash) {
        List<String> candidates = new ArrayList<>();
        Map<String, ConsistentHashRing> rings = domainRingsRef.get();

        // 从所有活跃域获取候选节点
        for (String domainName : getActiveDomains()) {
            ConsistentHashRing ring = rings.get(domainName);
            if (ring != null) {
                String node = ring.getNode(chunkHash);
                if (node != null) {
                    candidates.add(node);
                }
            }
        }

        return candidates;
    }

    /**
     * 从候选节点中选择负载最低的节点进行读取
     *
     * @param nodes 候选节点列表
     * @return 最优节点，如果都不可用则返回 null
     */
    public String selectBestNodeForRead(List<String> nodes) {
        if (CollectionUtils.isEmpty(nodes)) {
            return null;
        }

        return nodes.stream()
                .filter(s3Monitor::isNodeOnline)
                .min(Comparator.comparingDouble(s3Monitor::getNodeLoadScore))
                .orElse(null);
    }

    // ==================== 备用节点相关方法 ====================

    /**
     * 获取所有备用节点
     *
     * @return 备用节点名称列表，如果备用域未配置则返回空列表
     */
    public List<String> getStandbyNodes() {
        String standbyDomain = getStandbyDomainName();
        if (standbyDomain == null || standbyDomain.isBlank()) {
            return Collections.emptyList();
        }
        Set<String> standby = domainNodesRef.get().get(standbyDomain);
        return standby != null ? new ArrayList<>(standby) : Collections.emptyList();
    }

    /**
     * 获取所有健康的备用节点
     *
     * @return 健康备用节点列表
     */
    public List<String> getHealthyStandbyNodes() {
        return getStandbyNodes().stream()
                .filter(s3Monitor::isNodeOnline)
                .toList();
    }

    // ==================== 域查询方法 ====================

    /**
     * 在指定域内寻找任意健康节点
     *
     * @param domainName 故障域名称
     * @return 健康节点名称，如果没有则返回 null
     */
    public String findHealthyNodeInDomain(String domainName) {
        Set<String> nodes = domainNodesRef.get().get(domainName);
        if (CollectionUtils.isEmpty(nodes)) {
            return null;
        }

        return nodes.stream()
                .filter(s3Monitor::isNodeOnline)
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取指定域内的健康节点数量
     *
     * @param domainName 故障域名称
     * @return 健康节点数量
     */
    public int countHealthyNodesInDomain(String domainName) {
        Set<String> nodes = domainNodesRef.get().get(domainName);
        if (CollectionUtils.isEmpty(nodes)) {
            return 0;
        }

        return (int) nodes.stream()
                .filter(s3Monitor::isNodeOnline)
                .count();
    }

    /**
     * 获取故障域配置
     *
     * @param domainName 域名称
     * @return 域配置，如果不存在返回 null
     */
    public FaultDomainConfig getDomainConfig(String domainName) {
        return domainConfigsRef.get().get(domainName);
    }

    /**
     * 获取域中的所有节点
     *
     * @param domainName 域名称
     * @return 节点名称集合
     */
    public Set<String> getNodesInDomain(String domainName) {
        Set<String> nodes = domainNodesRef.get().get(domainName);
        return nodes != null ? new HashSet<>(nodes) : Collections.emptySet();
    }

    /**
     * 获取指定域内的所有健康节点
     *
     * @param domainName 故障域名称
     * @return 健康节点列表
     */
    public List<String> getHealthyNodesInDomainList(String domainName) {
        Set<String> nodes = domainNodesRef.get().get(domainName);
        if (CollectionUtils.isEmpty(nodes)) {
            return Collections.emptyList();
        }
        return nodes.stream()
                .filter(s3Monitor::isNodeOnline)
                .toList();
    }

    /**
     * 检查是否启用了故障域机制
     *
     * @return true 如果至少有一个活跃域配置了节点
     */
    public boolean isUsingFaultDomains() {
        Map<String, Set<String>> domainNodes = domainNodesRef.get();
        if (domainNodes.isEmpty()) {
            return false;
        }
        // 检查是否有任意活跃域配置了节点
        return getActiveDomains().stream()
                .anyMatch(domainNodes::containsKey);
    }

    /**
     * 获取指定域内的目标节点
     * 用于再平衡时确定对象应该存储在哪个节点
     *
     * @param chunkHash  分片哈希值
     * @param domainName 故障域名称
     * @return 目标节点名称，如果域为空或无健康节点则返回 null
     */
    public String getTargetNodeInDomain(String chunkHash, String domainName) {
        ConsistentHashRing ring = domainRingsRef.get().get(domainName);
        if (ring == null || ring.isEmpty()) {
            return null;
        }
        String targetNode = ring.getNode(chunkHash);
        if (targetNode != null && s3Monitor.isNodeOnline(targetNode)) {
            return targetNode;
        }
        // 目标节点不健康，回退到域内任意健康节点
        return findHealthyNodeInDomain(domainName);
    }

    /**
     * 获取节点所属的故障域
     *
     * @param nodeName 节点名称
     * @return 故障域名称，如果节点不存在则返回 null
     */
    public String getNodeDomain(String nodeName) {
        for (Map.Entry<String, Set<String>> entry : domainNodesRef.get().entrySet()) {
            if (entry.getValue().contains(nodeName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    // ==================== 节点管理方法 ====================

    /**
     * 变更节点所属故障域（用于备用节点提升）
     * 此方法会更新内存中的域映射和哈希环，但不会持久化到 Nacos
     * 使用原子替换模式确保线程安全
     *
     * @param nodeName  节点名称
     * @param newDomain 新的故障域名称
     * @return true 如果变更成功
     */
    public synchronized boolean changeNodeDomain(String nodeName, String newDomain) {
        // 1. 找到节点配置
        NodeConfig nodeConfig = findNodeConfig(nodeName);
        if (nodeConfig == null) {
            log.warn("节点 {} 不存在，无法变更域", nodeName);
            return false;
        }

        String oldDomain = nodeConfig.getFaultDomain();
        if (oldDomain != null && oldDomain.equals(newDomain)) {
            log.debug("节点 {} 已在域 {} 中，无需变更", nodeName, newDomain);
            return true;
        }

        // 2. 创建新的域映射（原子替换模式）
        Map<String, Set<String>> oldDomainNodes = domainNodesRef.get();
        Map<String, Set<String>> newDomainNodes = new ConcurrentHashMap<>();

        // 复制现有映射
        for (Map.Entry<String, Set<String>> entry : oldDomainNodes.entrySet()) {
            newDomainNodes.put(entry.getKey(), ConcurrentHashMap.newKeySet());
            newDomainNodes.get(entry.getKey()).addAll(entry.getValue());
        }

        // 从旧域移除
        if (oldDomain != null) {
            Set<String> oldDomainNodeSet = newDomainNodes.get(oldDomain);
            if (oldDomainNodeSet != null) {
                oldDomainNodeSet.remove(nodeName);
            }
        }

        // 添加到新域
        newDomainNodes.computeIfAbsent(newDomain, k -> ConcurrentHashMap.newKeySet())
                .add(nodeName);

        // 3. 创建新的哈希环映射（原子替换模式）
        Map<String, ConsistentHashRing> oldRings = domainRingsRef.get();

        // 复制现有哈希环
        Map<String, ConsistentHashRing> newRings = new ConcurrentHashMap<>(oldRings);

        // 从旧域哈希环移除
        if (oldDomain != null) {
            ConsistentHashRing oldRing = newRings.get(oldDomain);
            if (oldRing != null) {
                oldRing.removeNode(nodeName);
            }
        }

        // 添加到新域哈希环
        int weight = nodeConfig.getWeight() != null ? nodeConfig.getWeight() : 100;
        newRings.computeIfAbsent(newDomain, k -> new ConsistentHashRing(storageProperties.getVirtualNodesPerNode()))
                .addNode(nodeName, weight);

        // 4. 原子替换
        domainNodesRef.set(newDomainNodes);
        domainRingsRef.set(newRings);

        // 5. 更新节点配置中的域信息（内存级别）
        nodeConfig.setFaultDomain(newDomain);

        log.info("节点 {} 已从域 {} 变更到域 {}", nodeName, oldDomain, newDomain);
        return true;
    }

    /**
     * 根据节点名称查找节点配置
     *
     * @param nodeName 节点名称
     * @return 节点配置，如果不存在返回 null
     */
    public NodeConfig findNodeConfig(String nodeName) {
        List<NodeConfig> nodes = storageProperties.getNodes();
        if (CollectionUtils.isEmpty(nodes)) {
            return null;
        }
        return nodes.stream()
                .filter(n -> nodeName.equals(n.getName()))
                .findFirst()
                .orElse(null);
    }
}
