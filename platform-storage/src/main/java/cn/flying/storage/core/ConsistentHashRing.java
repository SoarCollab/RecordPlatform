package cn.flying.storage.core;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 一致性哈希环实现
 * 用于在故障域内均匀分布分片到物理节点
 *
 * <p>特性:
 * <ul>
 *   <li>基于 MD5 的哈希函数，保证分布均匀性</li>
 *   <li>虚拟节点机制，解决节点分布不均问题</li>
 *   <li>支持节点权重，权重越高分配越多虚拟节点</li>
 *   <li>使用读写锁保证线程安全</li>
 * </ul>
 */
@Slf4j
public class ConsistentHashRing {

    /**
     * 哈希环：哈希值 -> 物理节点名称
     * 使用 ConcurrentSkipListMap 保证有序性
     */
    private final ConcurrentSkipListMap<Long, String> ring = new ConcurrentSkipListMap<>();

    /**
     * 节点权重映射：节点名称 -> 权重
     */
    private final Map<String, Integer> nodeWeights = new ConcurrentHashMap<>();

    /**
     * 节点的虚拟节点哈希值集合：节点名称 -> 该节点的所有虚拟节点哈希值
     */
    private final Map<String, Set<Long>> nodeVirtualHashes = new ConcurrentHashMap<>();

    /**
     * 每单位权重（100）对应的虚拟节点数
     */
    private final int virtualNodesPerUnit;

    /**
     * 读写锁，保证读写操作的线程安全
     */
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    /**
     * 构造一致性哈希环
     *
     * @param virtualNodesPerUnit 每单位权重的虚拟节点数（推荐 100-200）
     */
    public ConsistentHashRing(int virtualNodesPerUnit) {
        this.virtualNodesPerUnit = virtualNodesPerUnit;
    }

    /**
     * 添加节点到哈希环
     *
     * @param nodeName 节点名称
     * @param weight   节点权重（100 为标准权重）
     */
    public void addNode(String nodeName, int weight) {
        if (nodeName == null || nodeName.isBlank()) {
            log.warn("尝试添加空节点名称，跳过");
            return;
        }

        rwLock.writeLock().lock();
        try {
            // 如果节点已存在，先移除旧的虚拟节点
            if (nodeWeights.containsKey(nodeName)) {
                removeNodeInternal(nodeName);
            }

            // 计算虚拟节点数：权重 / 100 * virtualNodesPerUnit
            int virtualNodes = Math.max(1, (int) ((weight / 100.0) * virtualNodesPerUnit));
            Set<Long> hashes = ConcurrentHashMap.newKeySet();

            for (int i = 0; i < virtualNodes; i++) {
                // 使用 "nodeName#i" 格式生成虚拟节点键
                String virtualKey = nodeName + "#" + i;
                long hash = hash(virtualKey);
                ring.put(hash, nodeName);
                hashes.add(hash);
            }

            nodeWeights.put(nodeName, weight);
            nodeVirtualHashes.put(nodeName, hashes);

            log.debug("添加节点 {} (权重={}, 虚拟节点数={})", nodeName, weight, virtualNodes);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 从哈希环移除节点
     *
     * @param nodeName 要移除的节点名称
     */
    public void removeNode(String nodeName) {
        if (nodeName == null) {
            return;
        }

        rwLock.writeLock().lock();
        try {
            removeNodeInternal(nodeName);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 内部移除节点方法（调用者需持有写锁）
     */
    private void removeNodeInternal(String nodeName) {
        if (!nodeWeights.containsKey(nodeName)) {
            return;
        }

        // 移除所有虚拟节点
        Set<Long> hashes = nodeVirtualHashes.remove(nodeName);
        if (hashes != null) {
            for (Long hash : hashes) {
                ring.remove(hash);
            }
        }

        nodeWeights.remove(nodeName);
        log.debug("移除节点 {}", nodeName);
    }

    /**
     * 获取给定键应该映射到的节点
     *
     * @param key 用于定位的键（通常是分片哈希）
     * @return 目标节点名称，如果环为空则返回 null
     */
    public String getNode(String key) {
        rwLock.readLock().lock();
        try {
            if (ring.isEmpty()) {
                return null;
            }

            long hash = hash(key);

            // 查找大于等于 hash 的第一个节点
            Map.Entry<Long, String> entry = ring.ceilingEntry(hash);

            // 如果没找到，环绕到第一个节点
            if (entry == null) {
                entry = ring.firstEntry();
            }

            return entry != null ? entry.getValue() : null;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 获取给定键应该映射到的多个不同节点
     * 用于读取时的故障转移
     *
     * @param key   用于定位的键
     * @param count 需要的节点数量
     * @return 节点名称列表（去重）
     */
    public List<String> getNodes(String key, int count) {
        rwLock.readLock().lock();
        try {
            if (ring.isEmpty() || count <= 0) {
                return Collections.emptyList();
            }

            List<String> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();

            long hash = hash(key);
            NavigableMap<Long, String> tailMap = ring.tailMap(hash, true);

            // 先遍历 tailMap
            for (String node : tailMap.values()) {
                if (seen.add(node)) {
                    result.add(node);
                    if (result.size() >= count) {
                        return result;
                    }
                }
            }

            // 然后从头开始遍历
            for (String node : ring.values()) {
                if (seen.add(node)) {
                    result.add(node);
                    if (result.size() >= count) {
                        return result;
                    }
                }
            }

            return result;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 获取哈希环中的所有节点
     *
     * @return 节点名称集合
     */
    public Set<String> getAllNodes() {
        rwLock.readLock().lock();
        try {
            return new HashSet<>(nodeWeights.keySet());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 获取节点数量
     *
     * @return 物理节点数量
     */
    public int size() {
        rwLock.readLock().lock();
        try {
            return nodeWeights.size();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 检查环是否为空
     *
     * @return true 如果没有节点
     */
    public boolean isEmpty() {
        rwLock.readLock().lock();
        try {
            return nodeWeights.isEmpty();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 清空哈希环
     */
    public void clear() {
        rwLock.writeLock().lock();
        try {
            ring.clear();
            nodeWeights.clear();
            nodeVirtualHashes.clear();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 计算字符串的 MD5 哈希值（返回 64 位长整型）
     *
     * @param key 输入字符串
     * @return 64 位哈希值
     */
    private long hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));

            // 取前 8 字节组成 long
            return ((long) (digest[0] & 0xFF) << 56)
                    | ((long) (digest[1] & 0xFF) << 48)
                    | ((long) (digest[2] & 0xFF) << 40)
                    | ((long) (digest[3] & 0xFF) << 32)
                    | ((long) (digest[4] & 0xFF) << 24)
                    | ((long) (digest[5] & 0xFF) << 16)
                    | ((long) (digest[6] & 0xFF) << 8)
                    | ((long) (digest[7] & 0xFF));

        } catch (NoSuchAlgorithmException e) {
            // MD5 必定存在，不应发生
            throw new RuntimeException("MD5 算法不可用", e);
        }
    }
}
