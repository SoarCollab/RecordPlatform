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
import java.util.regex.Pattern;
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
 *   <li>多域模式：生产环境，按 replication.factor 配置副本数</li>
 * </ul>
 */
@Slf4j
@Component
@RefreshScope
public class FaultDomainManager {

    private static final Pattern PHYSICAL_STORAGE_ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");

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
     * 已发布拓扑中逻辑节点到物理存储集群身份的不可变映射。
     */
    private final AtomicReference<Map<String, String>> nodePhysicalStorageIdsRef =
            new AtomicReference<>(Collections.emptyMap());

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
        Map<String, String> newNodePhysicalStorageIds = new HashMap<>();
        Set<String> ambiguousPhysicalIdentityNodes = new HashSet<>();

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

                String physicalStorageId = normalizePhysicalStorageId(node.getPhysicalStorageId());
                if (physicalStorageId == null) {
                    log.error("节点 {} 缺少合法 physicalStorageId，多副本拓扑将失败关闭", node.getName());
                } else if (!ambiguousPhysicalIdentityNodes.contains(node.getName())) {
                    String previousIdentity = newNodePhysicalStorageIds.putIfAbsent(
                            node.getName(),
                            physicalStorageId
                    );
                    if (previousIdentity != null && !previousIdentity.equals(physicalStorageId)) {
                        newNodePhysicalStorageIds.remove(node.getName());
                        ambiguousPhysicalIdentityNodes.add(node.getName());
                        log.error("节点 {} 配置了冲突的 physicalStorageId，多副本拓扑将失败关闭", node.getName());
                    }
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
        nodePhysicalStorageIdsRef.set(Collections.unmodifiableMap(
                new HashMap<>(newNodePhysicalStorageIds)
        ));

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
     * @return 目标节点列表（数量由 replication.factor 决定）
     */
    public List<String> getTargetNodes(String chunkHash) {
        List<String> targets = new ArrayList<>();
        Map<String, ConsistentHashRing> rings = domainRingsRef.get();

        // 目标域计划只取决于配置，不受当前在线状态影响。
        for (String domainName : getPlannedTargetDomains(chunkHash)) {
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
     * 返回对象在当前配置下确定性的合法目标域计划。
     *
     * <p>计划严格按照 active-domains 顺序、acceptsWrites 和有效副本因子裁剪，
     * 不读取节点健康状态，因此域故障期间仍能稳定识别真正缺失的 placement。</p>
     *
     * @param chunkHash 对象哈希；当前域级策略按配置顺序确定，保留参数供未来分片域策略扩展
     * @return 不超过有效副本因子的可写目标域
     */
    public List<String> getPlannedTargetDomains(String chunkHash) {
        int replicationFactor = storageProperties.getEffectiveReplicationFactor();
        return getWritableDomains().stream()
                .map(FaultDomainConfig::getName)
                .filter(Objects::nonNull)
                .filter(domain -> !domain.isBlank())
                .limit(Math.max(0, replicationFactor))
                .toList();
    }

    /**
     * 在一次拓扑线性化读取中返回对象的精确计划目标。
     *
     * <p>该方法与拓扑重建、节点迁移共用同一 monitor，返回值仅包含不可变字符串，
     * 因此调用方可以在一次状态更新内安全复用，且不会把两个 ring 版本拼成混合拓扑。
     * 多副本计划中的逻辑节点名或 physicalStorageId 只要缺失、非法或重复，整份快照
     * 都按未解析处理，避免拓扑漂移期间任一别名副本清理 durable degraded evidence。</p>
     *
     * @param chunkHash 分片哈希值
     * @return 按计划域顺序排列的不可变 domain-to-target 快照；无法解析的域对应 null
     */
    public synchronized Map<String, String> getPlannedTargetsSnapshot(String chunkHash) {
        Map<String, ConsistentHashRing> rings = domainRingsRef.get();
        Map<String, String> plannedTargets = new LinkedHashMap<>();
        Set<String> assignedNodes = new HashSet<>();
        Map<String, String> physicalIdentityOwners = new HashMap<>();
        List<String> plannedDomains = getPlannedTargetDomains(chunkHash);
        boolean requiresIndependentPhysicalTargets = plannedDomains.size() > 1;
        boolean physicalTopologyValid = true;

        for (String domain : plannedDomains) {
            ConsistentHashRing ring = rings.get(domain);
            String targetNode = ring == null || ring.isEmpty() ? null : ring.getNode(chunkHash);
            if (targetNode != null && !assignedNodes.add(targetNode)) {
                log.error("节点 {} 同时成为对象 {} 的多个故障域目标，整份多副本计划失败关闭",
                        targetNode, chunkHash, domain);
                physicalTopologyValid = false;
            }
            if (requiresIndependentPhysicalTargets && targetNode != null) {
                String physicalStorageId = nodePhysicalStorageIdsRef.get().get(targetNode);
                if (physicalStorageId == null) {
                    log.error("对象 {} 的计划目标节点 {} 缺少合法 physicalStorageId，整份多副本计划失败关闭",
                            chunkHash, targetNode);
                    physicalTopologyValid = false;
                } else {
                    String existingNode = physicalIdentityOwners.putIfAbsent(
                            physicalStorageId,
                            targetNode
                    );
                    if (existingNode != null && !existingNode.equals(targetNode)) {
                        log.error("对象 {} 的计划目标节点 {} 与 {} 共享 physicalStorageId={}，整份多副本计划失败关闭",
                                chunkHash, existingNode, targetNode, physicalStorageId);
                        physicalTopologyValid = false;
                    }
                }
            }
            plannedTargets.put(domain, targetNode);
        }

        if (!physicalTopologyValid) {
            plannedTargets.replaceAll((domain, targetNode) -> null);
        }

        return Collections.unmodifiableMap(plannedTargets);
    }

    /**
     * 返回已发布拓扑中的合法物理存储身份。
     *
     * @param nodeName 逻辑节点名称
     * @return 规范化 physicalStorageId；缺失或非法时返回 null
     */
    public String getPhysicalStorageId(String nodeName) {
        if (nodeName == null || nodeName.isBlank()) {
            return null;
        }
        return nodePhysicalStorageIdsRef.get().get(nodeName);
    }

    /**
     * 判断两个逻辑节点是否能证明位于不同的物理存储集群。
     *
     * <p>任一身份缺失、非法，或两个身份相同都返回 false，调用方必须失败关闭。</p>
     *
     * @param firstNode 第一个逻辑节点
     * @param secondNode 第二个逻辑节点
     * @return true 表示两者具有非空且互异的物理存储身份
     */
    public synchronized boolean areNodesOnIndependentPhysicalStorage(
            String firstNode,
            String secondNode
    ) {
        if (firstNode == null || secondNode == null
                || firstNode.isBlank() || secondNode.isBlank()
                || firstNode.equals(secondNode)) {
            return false;
        }
        Map<String, String> physicalStorageIds = nodePhysicalStorageIdsRef.get();
        String firstIdentity = physicalStorageIds.get(firstNode);
        String secondIdentity = physicalStorageIds.get(secondNode);
        return firstIdentity != null
                && secondIdentity != null
                && !firstIdentity.equals(secondIdentity);
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
     * 获取指定域内不受健康状态影响的一致性哈希计划节点。
     *
     * <p>该结果用于判断 durable placement 是否真实满足；即使节点当前离线也必须返回，
     * 避免同域旧节点或临时 fallback 节点冒充当前 hash ring 的精确目标。</p>
     *
     * @param chunkHash 分片哈希值
     * @param domainName 故障域名称
     * @return 当前 hash ring 的精确计划节点，域为空时返回 null
     */
    public String getPlannedTargetNodeInDomain(String chunkHash, String domainName) {
        ConsistentHashRing ring = domainRingsRef.get().get(domainName);
        if (ring == null || ring.isEmpty()) {
            return null;
        }
        return ring.getNode(chunkHash);
    }

    /**
     * 获取指定域内的目标节点
     * 用于再平衡时确定对象应该存储在哪个节点
     *
     * @param chunkHash  分片哈希值
     * @param domainName 故障域名称
     * @return 一致性哈希精确目标节点；精确目标离线或域为空时返回 null
     */
    public synchronized String getTargetNodeInDomain(String chunkHash, String domainName) {
        Map<String, String> plannedTargets = getPlannedTargetsSnapshot(chunkHash);
        String targetNode = plannedTargets.containsKey(domainName)
                ? plannedTargets.get(domainName)
                : getPlannedTargetNodeInDomain(chunkHash, domainName);
        if (targetNode != null && s3Monitor.isNodeOnline(targetNode)) {
            return targetNode;
        }
        // 修复和再平衡必须写入 hash placement 节点，不能以同域任意节点冒充完成。
        return null;
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

        // 3. 从新域映射构建全新哈希环，禁止原地修改仍被 reader 使用的已发布 ring。
        Map<String, ConsistentHashRing> newRings = buildRingsFromDomainNodes(newDomainNodes);

        // 4. 原子替换
        domainNodesRef.set(newDomainNodes);
        domainRingsRef.set(newRings);

        // 5. 更新节点配置中的域信息（内存级别）
        nodeConfig.setFaultDomain(newDomain);

        log.info("节点 {} 已从域 {} 变更到域 {}", nodeName, oldDomain, newDomain);
        return true;
    }

    /**
     * 根据域节点快照构建彼此独立的全新哈希环。
     *
     * <p>节点迁移不得浅复制 ring map 后修改共享 ring，否则未加 manager 锁的 reader
     * 会在 AtomicReference 替换前观察到半完成迁移。</p>
     *
     * @param domainNodes 域到节点集合的新拓扑
     * @return 不复用任何已发布 ring 实例的新哈希环映射
     */
    private Map<String, ConsistentHashRing> buildRingsFromDomainNodes(
            Map<String, Set<String>> domainNodes
    ) {
        Map<String, Integer> nodeWeights = new HashMap<>();
        List<NodeConfig> configuredNodes = storageProperties.getNodes();
        if (!CollectionUtils.isEmpty(configuredNodes)) {
            for (NodeConfig configuredNode : configuredNodes) {
                int weight = configuredNode.getWeight() != null ? configuredNode.getWeight() : 100;
                nodeWeights.put(configuredNode.getName(), weight);
            }
        }

        int virtualNodesPerNode = storageProperties.getVirtualNodesPerNode();
        Map<String, ConsistentHashRing> rebuiltRings = new ConcurrentHashMap<>();
        for (Map.Entry<String, Set<String>> entry : domainNodes.entrySet()) {
            ConsistentHashRing ring = new ConsistentHashRing(virtualNodesPerNode);
            for (String member : new TreeSet<>(entry.getValue())) {
                ring.addNode(member, nodeWeights.getOrDefault(member, 100));
            }
            rebuiltRings.put(entry.getKey(), ring);
        }
        return rebuiltRings;
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

    /**
     * 规范化并校验配置中的物理存储身份。
     *
     * @param physicalStorageId 原始配置值
     * @return 合法的去空白身份；无效时返回 null
     */
    private String normalizePhysicalStorageId(String physicalStorageId) {
        String normalized = physicalStorageId == null ? "" : physicalStorageId.trim();
        return PHYSICAL_STORAGE_ID_PATTERN.matcher(normalized).matches()
                ? normalized
                : null;
    }
}
