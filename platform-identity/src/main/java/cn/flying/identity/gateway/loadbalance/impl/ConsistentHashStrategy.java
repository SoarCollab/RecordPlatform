package cn.flying.identity.gateway.loadbalance.impl;

import cn.flying.identity.gateway.loadbalance.LoadBalanceStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Collectors;

/**
 * 一致性哈希负载均衡策略
 * 使用哈希环实现请求的一致性路由
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
@Component("consistentHashStrategy")
public class ConsistentHashStrategy implements LoadBalanceStrategy {

    /**
     * 虚拟节点数量（提高负载均衡的均匀性）
     */
    private static final int VIRTUAL_NODE_COUNT = 150;

    /**
     * 哈希环
     */
    private final NavigableMap<Long, ServiceInstance> hashRing = new ConcurrentSkipListMap<>();

    /**
     * 服务实例缓存（用于快速重建哈希环）
     */
    private List<ServiceInstance> cachedInstances = new ArrayList<>();

    @Override
    public ServiceInstance select(List<ServiceInstance> serviceInstances, String requestKey) {
        if (serviceInstances == null || serviceInstances.isEmpty()) {
            return null;
        }

        // 过滤健康的实例
        List<ServiceInstance> healthyInstances = serviceInstances.stream()
                .filter(ServiceInstance::isHealthy)
                .collect(Collectors.toList());

        if (healthyInstances.isEmpty()) {
            log.warn("没有健康的服务实例可用");
            return null;
        }

        // 如果只有一个实例，直接返回
        if (healthyInstances.size() == 1) {
            return healthyInstances.getFirst();
        }

        // 检查是否需要重建哈希环
        if (!healthyInstances.equals(cachedInstances)) {
            rebuildHashRing(healthyInstances);
        }

        // 计算请求的哈希值
        long hash = hash(requestKey);

        // 在哈希环上找到第一个大于等于该哈希值的节点
        Map.Entry<Long, ServiceInstance> entry = hashRing.ceilingEntry(hash);
        if (entry == null) {
            // 如果没有找到，返回哈希环的第一个节点
            entry = hashRing.firstEntry();
        }

        ServiceInstance selected = entry.getValue();
        log.debug("一致性哈希选择服务实例: {}, requestKey: {}, hash: {}",
                selected, requestKey, hash);

        return selected;
    }

    /**
     * 重建哈希环
     *
     * @param instances 服务实例列表
     */
    private synchronized void rebuildHashRing(List<ServiceInstance> instances) {
        log.debug("重建哈希环，实例数量: {}", instances.size());

        hashRing.clear();

        for (ServiceInstance instance : instances) {
            // 根据权重计算虚拟节点数量
            int virtualNodes = (int) (VIRTUAL_NODE_COUNT * ((double) instance.getWeight() / 100));
            virtualNodes = Math.max(1, virtualNodes);  // 至少一个虚拟节点

            // 添加虚拟节点到哈希环
            for (int i = 0; i < virtualNodes; i++) {
                String virtualNodeName = instance.getInstanceId() + "#" + i;
                long hash = hash(virtualNodeName);
                hashRing.put(hash, instance);
            }
        }

        cachedInstances = new ArrayList<>(instances);
        log.debug("哈希环重建完成，节点总数: {}", hashRing.size());
    }

    /**
     * 计算哈希值（使用MD5）
     *
     * @param key 键值
     * @return 哈希值
     */
    private long hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));

            // 取前8个字节作为long值
            long hash = 0;
            for (int i = 0; i < 8; i++) {
                hash = (hash << 8) | (digest[i] & 0xFF);
            }
            return Math.abs(hash);

        } catch (NoSuchAlgorithmException e) {
            log.error("计算哈希值失败", e);
            // 降级为简单哈希
            return Math.abs(key.hashCode());
        }
    }

    @Override
    public String getStrategyName() {
        return "ConsistentHash";
    }
}