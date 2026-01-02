package cn.flying.storage.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Storage 相关配置属性 - 从 Nacos 加载并支持动态刷新
 * <p>
 * 支持多活跃域配置：
 * <ul>
 *   <li>单域模式：开发环境，无副本</li>
 *   <li>双域模式：标准生产配置</li>
 *   <li>多域模式：高可用场景，按 replicationFactor 配置副本数</li>
 * </ul>
 * <p>
 * v3.1.0 新增：
 * <ul>
 *   <li>写入仲裁 (Write Quorum) 配置</li>
 *   <li>降级写入配置</li>
 * </ul>
 */
@Slf4j
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    /**
     * 副本策略配置
     */
    @Data
    public static class ReplicationConfig {
        /**
         * 副本数量，默认=活跃域数量
         */
        private Integer factor;

        /**
         * 仲裁策略: auto | majority | all | 具体数字
         * <ul>
         *   <li>auto: 根据 factor 自动计算 (2副本=2, 3+副本=majority)</li>
         *   <li>majority: 多数派 (factor/2 + 1)</li>
         *   <li>all: 全部成功</li>
         *   <li>数字: 手动指定仲裁数</li>
         * </ul>
         */
        private String quorum = "auto";

        /**
         * 根据配置计算有效仲裁数
         *
         * @param effectiveFactor 有效副本因子
         * @return 仲裁所需的最小成功数
         */
        public int getEffectiveQuorum(int effectiveFactor) {
            if (quorum == null || quorum.isBlank()) {
                quorum = "auto";
            }
            return switch (quorum.toLowerCase().trim()) {
                case "auto" -> effectiveFactor <= 2 ? effectiveFactor : (effectiveFactor / 2 + 1);
                case "majority" -> effectiveFactor / 2 + 1;
                case "all" -> effectiveFactor;
                default -> {
                    try {
                        int parsed = Integer.parseInt(quorum);
                        yield Math.min(Math.max(1, parsed), effectiveFactor);
                    } catch (NumberFormatException e) {
                        log.warn("Invalid quorum config '{}', using 'auto'", quorum);
                        yield effectiveFactor <= 2 ? effectiveFactor : (effectiveFactor / 2 + 1);
                    }
                }
            };
        }
    }

    /**
     * 降级写入配置
     */
    @Data
    public static class DegradedWriteConfig {
        /**
         * 是否允许降级写入
         * <p>
         * 当某个故障域完全不可用时，允许仅写入剩余健康域
         */
        private boolean enabled = false;

        /**
         * 降级模式下的最小副本数
         * <p>
         * 当可用节点数低于此值时拒绝写入
         */
        private int minReplicas = 1;

        /**
         * 是否记录降级写入以便后续同步
         */
        private boolean trackForSync = true;
    }

    /**
     * 配置的所有物理节点列表
     * 注意: 每个节点的name字段将直接用作该节点的S3桶名
     */
    private List<NodeConfig> nodes;

    /**
     * 故障域配置列表
     * 定义每个域的行为（minNodes, replicaCount, acceptsWrites）
     */
    private List<FaultDomainConfig> domains;

    /**
     * 活跃域名称列表（按优先级排序，必须配置）
     * <p>
     * 当 replicationFactor < activeDomains.size() 时，按此列表顺序选择前 N 个域写入。
     * 单域模式只需配置一个域名。
     */
    private List<String> activeDomains;

    /**
     * 备用域名称
     * <p>
     * 设置为 null 或空字符串表示禁用备用池功能。
     * 开发环境通常不需要备用池。
     */
    private String standbyDomain;

    /**
     * 每个物理节点的虚拟节点数（用于一致性哈希）
     * 值越大，分布越均匀，但内存占用和计算开销也越大
     * 推荐值: 100-200
     * 默认值: 150
     */
    private Integer virtualNodesPerNode = 150;

    /**
     * 每个分片的总副本数
     * <p>
     * 规则：
     * <ul>
     *   <li>默认值：等于活跃域数量（全副本）</li>
     *   <li>最小值：1</li>
     *   <li>最大值：活跃域数量（超出时自动调整）</li>
     * </ul>
     *
     * @deprecated 请使用 {@link #replication} 配置块中的 factor
     */
    @Deprecated
    private Integer replicationFactor;

    /**
     * 副本策略配置（v3.1.0 新增）
     */
    private ReplicationConfig replication = new ReplicationConfig();

    /**
     * 降级写入配置（v3.1.0 新增）
     */
    private DegradedWriteConfig degradedWrite = new DegradedWriteConfig();

    /**
     * 外部访问端点（v3.2.0 新增）
     * <p>
     * 用于生成预签名 URL 时替换内部端点地址，解决跨网段访问问题。
     * 例如：内部存储地址为 192.168.5.100:9000，但客户端通过 VPN 访问时需要使用 10.1.0.2:9000
     * <p>
     * 格式：http://host:port（不带尾部斜杠）
     * 如果未配置或为空，则使用各节点的 endpoint 配置
     */
    private String externalEndpoint;

    /**
     * 获取活跃域列表
     *
     * @return 活跃域名称列表
     */
    public List<String> getActiveDomains() {
        return activeDomains != null ? activeDomains : List.of();
    }

    /**
     * 判断是否为单域模式
     * <p>
     * 单域模式适用于开发环境，不进行跨域副本复制。
     *
     * @return true 如果只有一个活跃域
     */
    public boolean isSingleDomainMode() {
        return getActiveDomains().size() <= 1;
    }

    /**
     * 获取有效的副本因子
     * <p>
     * 规则：
     * <ul>
     *   <li>优先使用 replication.factor 配置</li>
     *   <li>兼容旧的 replicationFactor 配置</li>
     *   <li>如果未配置或超出活跃域数量，返回活跃域数量</li>
     *   <li>最小返回 1</li>
     * </ul>
     *
     * @return 有效副本数
     */
    public int getEffectiveReplicationFactor() {
        int activeDomainCount = getActiveDomains().size();
        if (activeDomainCount == 0) {
            return 1;
        }

        // 优先使用新配置，兼容旧配置
        Integer configuredFactor = replication != null && replication.getFactor() != null
                ? replication.getFactor()
                : replicationFactor;

        if (configuredFactor == null || configuredFactor > activeDomainCount) {
            return activeDomainCount;
        }
        return Math.max(1, configuredFactor);
    }

    /**
     * 获取有效仲裁数
     * <p>
     * 仲裁数决定了写入成功所需的最小副本数
     *
     * @return 仲裁所需的最小成功数
     */
    public int getEffectiveQuorum() {
        int effectiveFactor = getEffectiveReplicationFactor();
        if (replication == null) {
            // 无配置时，默认要求全部成功
            return effectiveFactor;
        }
        return replication.getEffectiveQuorum(effectiveFactor);
    }

    /**
     * 检查备用域是否已配置并启用
     *
     * @return true 如果备用域已配置
     */
    public boolean isStandbyEnabled() {
        return standbyDomain != null && !standbyDomain.isBlank();
    }

    /**
     * 检查是否配置了外部访问端点
     *
     * @return true 如果配置了有效的外部端点
     */
    public boolean hasExternalEndpoint() {
        return externalEndpoint != null && !externalEndpoint.isBlank();
    }

    /**
     * 获取有效的外部端点（去除尾部斜杠）
     *
     * @return 外部端点地址，未配置返回 null
     */
    public String getEffectiveExternalEndpoint() {
        if (!hasExternalEndpoint()) {
            return null;
        }
        return externalEndpoint.replaceAll("/$", "");
    }

    /**
     * 配置校验
     * <p>
     * 注意：本服务默认通过 Nacos 动态加载 storage 拓扑配置（见 bootstrap.yml 的 optional:nacos 导入）。
     * 当本地/开发环境未接入 Nacos 且未在 application.yml 中显式配置拓扑时，允许应用启动，但会处于“未初始化拓扑”状态。
     */
    @PostConstruct
    public void validate() {
        if (activeDomains == null || activeDomains.isEmpty()) {
            boolean hasNodes = nodes != null && !nodes.isEmpty();
            boolean hasDomains = domains != null && !domains.isEmpty();
            if (hasNodes || hasDomains) {
                throw new IllegalStateException(
                        "storage.active-domains must be configured with at least one domain when storage.nodes/domains are provided.");
            }

            log.warn("storage.active-domains is not configured; storage topology is not initialized. " +
                    "This is expected for local/dev environments when Nacos config is absent.");
            return;
        }

        int effectiveReplicationFactor = getEffectiveReplicationFactor();

        // 单域模式警告
        if (isSingleDomainMode()) {
            log.warn("Storage running in SINGLE-DOMAIN mode (domain: {}). " +
                            "Data will NOT be replicated across domains. " +
                            "This is suitable for development only.",
                    activeDomains.getFirst());
        }

        // 副本因子调整警告
        if (replicationFactor != null && replicationFactor > activeDomains.size()) {
            log.warn("replicationFactor ({}) exceeds active domain count ({}), will use {}",
                    replicationFactor, activeDomains.size(), effectiveReplicationFactor);
        }

        // v3.1.0 新增配置日志
        int effectiveQuorum = getEffectiveQuorum();
        log.info("Storage configuration validated: activeDomains={}, replicationFactor={}, quorum={}, standbyDomain={}, degradedWrite={}, externalEndpoint={}",
                activeDomains, effectiveReplicationFactor, effectiveQuorum,
                isStandbyEnabled() ? standbyDomain : "disabled",
                degradedWrite != null && degradedWrite.isEnabled() ? "enabled(min=" + degradedWrite.getMinReplicas() + ")" : "disabled",
                hasExternalEndpoint() ? getEffectiveExternalEndpoint() : "disabled");
    }
}
