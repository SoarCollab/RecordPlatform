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
 *   <li>多域模式：高可用场景，按 replication.factor 配置副本数</li>
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
     * 对象存储直传完成链路配置。
     */
    @Data
    public static class DirectUploadConfig {
        private static final long DEFAULT_MAX_FILE_SIZE_BYTES = 4L * 1024 * 1024 * 1024;
        private static final long DEFAULT_MAX_PART_SIZE_BYTES = 100L * 1024 * 1024;
        private static final long MAX_FILE_SIZE_BYTES = 4L * 1024 * 1024 * 1024;
        private static final long MAX_PART_SIZE_BYTES = 100L * 1024 * 1024;
        private static final int DEFAULT_STREAM_BUFFER_BYTES = 64 * 1024;
        private static final int MIN_STREAM_BUFFER_BYTES = 8 * 1024;
        private static final int MAX_STREAM_BUFFER_BYTES = 1024 * 1024;
        private static final int DEFAULT_TRANSFER_TIMEOUT_SECONDS = 300;
        private static final int MAX_TRANSFER_TIMEOUT_SECONDS = 1_800;
        private static final int DEFAULT_LOCK_WAIT_SECONDS = 5;
        private static final int MAX_LOCK_WAIT_SECONDS = 60;
        private static final long DEFAULT_STAGING_RETENTION_HOURS = 48;
        private static final long MIN_STAGING_RETENTION_HOURS = 48;
        private static final long MAX_STAGING_RETENTION_HOURS = 8_760;
        private static final long DEFAULT_CLEANUP_INTERVAL_MILLIS = 3_600_000L;
        private static final long MIN_CLEANUP_INTERVAL_MILLIS = 60_000L;
        private static final long MAX_CLEANUP_INTERVAL_MILLIS = 86_400_000L;
        private static final long DEFAULT_CLEANUP_INITIAL_DELAY_MILLIS = 300_000L;
        private static final long MAX_CLEANUP_INITIAL_DELAY_MILLIS = 86_400_000L;
        private static final int DEFAULT_CLEANUP_BATCH_SIZE = 200;
        private static final int DEFAULT_CLEANUP_CLAIM_LEASE_SECONDS = 600;
        private static final int MIN_CLEANUP_CLAIM_LEASE_SECONDS = 180;
        private static final int MAX_CLEANUP_CLAIM_LEASE_SECONDS = 3600;

        /**
         * 单个直传文件的最大字节数。
         */
        private long maxFileSizeBytes = DEFAULT_MAX_FILE_SIZE_BYTES;

        /**
         * 单个直传分片的最大字节数。
         */
        private long maxPartSizeBytes = DEFAULT_MAX_PART_SIZE_BYTES;

        /**
         * 流式校验和跨端点转发的显式输入缓冲区大小。
         */
        private int streamBufferBytes = DEFAULT_STREAM_BUFFER_BYTES;

        /**
         * 等待一组最终副本提升任务结束的超时时间。
         */
        private int transferTimeoutSeconds = DEFAULT_TRANSFER_TIMEOUT_SECONDS;

        /**
         * complete、abort 或清理任务等待同一分片锁的时间。
         */
        private int lockWaitSeconds = DEFAULT_LOCK_WAIT_SECONDS;

        /**
         * staging 对象进入生命周期清理队列前的保留时间。
         */
        private long stagingRetentionHours = DEFAULT_STAGING_RETENTION_HOURS;

        /**
         * 是否启用过期 staging 对象定时清理。
         */
        private boolean cleanupEnabled = true;

        /**
         * 两轮 staging 清理之间的固定延迟毫秒数。
         */
        private long cleanupIntervalMillis = DEFAULT_CLEANUP_INTERVAL_MILLIS;

        /**
         * 应用启动后首次执行 staging 清理的延迟毫秒数。
         */
        private long cleanupInitialDelayMillis = DEFAULT_CLEANUP_INITIAL_DELAY_MILLIS;

        /**
         * 每轮最多处理的到期 staging 记录数。
         */
        private int cleanupBatchSize = DEFAULT_CLEANUP_BATCH_SIZE;

        /**
         * 集群领取一个 staging 清理批次的 fencing 租约秒数。
         */
        private int cleanupClaimLeaseSeconds = DEFAULT_CLEANUP_CLAIM_LEASE_SECONDS;

        /**
         * 将最大文件大小限制在公开直传合同的 4 GiB 内，避免极端配置进入后续大小运算。
         *
         * @return 最大文件字节数
         */
        public long getEffectiveMaxFileSizeBytes() {
            long configured = maxFileSizeBytes > 0 ? maxFileSizeBytes : DEFAULT_MAX_FILE_SIZE_BYTES;
            return Math.min(configured, MAX_FILE_SIZE_BYTES);
        }

        /**
         * 将分片大小限制在公开直传合同的 100 MiB 内，并确保不超过有效文件上限。
         *
         * @return 最大分片字节数
         */
        public long getEffectiveMaxPartSizeBytes() {
            long configured = maxPartSizeBytes > 0 ? maxPartSizeBytes : DEFAULT_MAX_PART_SIZE_BYTES;
            return Math.min(
                    Math.min(configured, MAX_PART_SIZE_BYTES),
                    getEffectiveMaxFileSizeBytes()
            );
        }

        /**
         * 将流缓冲限制在 8 KiB 到 1 MiB，避免错误配置放大堆占用。
         *
         * @return 有效流缓冲字节数
         */
        public int getEffectiveStreamBufferBytes() {
            return Math.max(MIN_STREAM_BUFFER_BYTES,
                    Math.min(streamBufferBytes, MAX_STREAM_BUFFER_BYTES));
        }

        /**
         * 返回不超过 30 分钟的正数传输超时，避免截止时间运算溢出或 RPC 长时间占锁。
         *
         * @return 有效传输超时秒数
         */
        public int getEffectiveTransferTimeoutSeconds() {
            int configured = transferTimeoutSeconds > 0
                    ? transferTimeoutSeconds
                    : DEFAULT_TRANSFER_TIMEOUT_SECONDS;
            return Math.min(configured, MAX_TRANSFER_TIMEOUT_SECONDS);
        }

        /**
         * 将锁等待限制在 0 到 60 秒，避免错误配置让同步 RPC 长时间阻塞。
         *
         * @return 有效锁等待秒数
         */
        public int getEffectiveLockWaitSeconds() {
            return Math.min(Math.max(0, lockWaitSeconds), MAX_LOCK_WAIT_SECONDS);
        }

        /**
         * 保证 staging tombstone 至少保留 48 小时，并限制异常配置造成的时间溢出。
         *
         * @return 有效保留小时数
         */
        public long getEffectiveStagingRetentionHours() {
            long configured = stagingRetentionHours > 0
                    ? stagingRetentionHours
                    : DEFAULT_STAGING_RETENTION_HOURS;
            return Math.min(
                    Math.max(configured, MIN_STAGING_RETENTION_HOURS),
                    MAX_STAGING_RETENTION_HOURS
            );
        }

        /**
         * 返回一分钟到一天之间的清理间隔，非正配置回退到一小时默认值。
         *
         * @return 有效清理间隔毫秒数
         */
        public long getEffectiveCleanupIntervalMillis() {
            long configured = cleanupIntervalMillis > 0
                    ? cleanupIntervalMillis
                    : DEFAULT_CLEANUP_INTERVAL_MILLIS;
            return Math.min(
                    Math.max(configured, MIN_CLEANUP_INTERVAL_MILLIS),
                    MAX_CLEANUP_INTERVAL_MILLIS
            );
        }

        /**
         * 返回零到一天之间的首次清理延迟，负数配置回退到五分钟默认值。
         *
         * @return 有效首次清理延迟毫秒数
         */
        public long getEffectiveCleanupInitialDelayMillis() {
            long configured = cleanupInitialDelayMillis >= 0
                    ? cleanupInitialDelayMillis
                    : DEFAULT_CLEANUP_INITIAL_DELAY_MILLIS;
            return Math.min(configured, MAX_CLEANUP_INITIAL_DELAY_MILLIS);
        }

        /**
         * 将单轮清理批量限制在安全范围，避免错误配置形成突发删除压力。
         *
         * @return 有效清理批量
         */
        public int getEffectiveCleanupBatchSize() {
            int configured = cleanupBatchSize > 0 ? cleanupBatchSize : DEFAULT_CLEANUP_BATCH_SIZE;
            return Math.min(configured, 10_000);
        }

        /**
         * 返回覆盖单次有界 DELETE 的 claim 租约，并限制异常配置的恢复时间。
         *
         * @return 180 到 3600 秒之间的 claim 租约
         */
        public int getEffectiveCleanupClaimLeaseSeconds() {
            int configured = cleanupClaimLeaseSeconds > 0
                    ? cleanupClaimLeaseSeconds
                    : DEFAULT_CLEANUP_CLAIM_LEASE_SECONDS;
            return Math.min(
                    Math.max(configured, MIN_CLEANUP_CLAIM_LEASE_SECONDS),
                    MAX_CLEANUP_CLAIM_LEASE_SECONDS
            );
        }
    }

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
        private static final int DEFAULT_MAX_SYNC_FAILURES = 12;
        private static final int MAX_SYNC_FAILURES_LIMIT = 10_000;
        private static final int DEFAULT_SYNC_BATCH_SIZE = 100;
        private static final int MAX_SYNC_BATCH_SIZE = 1_000;
        private static final int DEFAULT_CLAIM_LEASE_SECONDS = 600;
        private static final int MIN_CLAIM_LEASE_SECONDS = 30;
        private static final int MAX_CLAIM_LEASE_SECONDS = 3_600;
        private static final int DEFAULT_REPAIR_TIMEOUT_SECONDS = 120;
        private static final int MIN_REPAIR_TIMEOUT_SECONDS = 1;
        private static final int MAX_REPAIR_TIMEOUT_SECONDS = 1_800;
        private static final int CLAIM_LEASE_SAFETY_SECONDS = 60;
        private static final int DEFAULT_RETRY_BACKOFF_SECONDS = 60;
        private static final int MAX_RETRY_BACKOFF_SECONDS_LIMIT = 86_400;
        private static final int DEFAULT_MAX_RETRY_BACKOFF_SECONDS = 3_600;

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

        /**
         * 单个租户、对象和缺失域连续修复失败多少次后进入显式死信。
         */
        private int maxSyncFailures = DEFAULT_MAX_SYNC_FAILURES;

        /**
         * 每个实例每轮最多领取的待恢复记录数。
         */
        private int syncBatchSize = DEFAULT_SYNC_BATCH_SIZE;

        /**
         * Redis due claim 的租约秒数，覆盖一次副本修复的正常执行窗口。
         */
        private int claimLeaseSeconds = DEFAULT_CLAIM_LEASE_SECONDS;

        /**
         * 单次立即副本修复（含重试、流传输和最终校验）的整体超时秒数。
         */
        private int repairTimeoutSeconds = DEFAULT_REPAIR_TIMEOUT_SECONDS;

        /**
         * 首次修复失败或无效记录重排的基础退避秒数。
         */
        private int retryBackoffSeconds = DEFAULT_RETRY_BACKOFF_SECONDS;

        /**
         * 指数退避的最大秒数。
         */
        private int maxRetryBackoffSeconds = DEFAULT_MAX_RETRY_BACKOFF_SECONDS;

        /**
         * 返回安全范围内的修复失败阈值，避免无效配置禁用或无限放大重试。
         *
         * @return 有效失败阈值
         */
        public int getEffectiveMaxSyncFailures() {
            int configured = maxSyncFailures > 0 ? maxSyncFailures : DEFAULT_MAX_SYNC_FAILURES;
            return Math.min(configured, MAX_SYNC_FAILURES_LIMIT);
        }

        /**
         * 返回带硬上限的单轮恢复批次，避免配置错误重新引入全量加载。
         *
         * @return 1 到 1000 之间的领取数量
         */
        public int getEffectiveSyncBatchSize() {
            int configured = syncBatchSize > 0 ? syncBatchSize : DEFAULT_SYNC_BATCH_SIZE;
            return Math.min(configured, MAX_SYNC_BATCH_SIZE);
        }

        /**
         * 返回安全范围内的 claim 租约秒数。
         *
         * @return 30 到 3600 秒之间的租约
         */
        public int getEffectiveClaimLeaseSeconds() {
            int configured = claimLeaseSeconds > 0
                    ? claimLeaseSeconds
                    : DEFAULT_CLAIM_LEASE_SECONDS;
            int bounded = Math.min(
                    Math.max(configured, MIN_CLAIM_LEASE_SECONDS),
                    MAX_CLAIM_LEASE_SECONDS
            );
            int repairFloor = Math.min(
                    MAX_CLAIM_LEASE_SECONDS,
                    getEffectiveRepairTimeoutSeconds() + CLAIM_LEASE_SAFETY_SECONDS
            );
            return Math.max(bounded, repairFloor);
        }

        /**
         * 返回带安全上下限的立即修复整体超时。
         *
         * @return 1 到 1800 秒之间的修复超时
         */
        public int getEffectiveRepairTimeoutSeconds() {
            int configured = repairTimeoutSeconds > 0
                    ? repairTimeoutSeconds
                    : DEFAULT_REPAIR_TIMEOUT_SECONDS;
            return Math.min(
                    Math.max(configured, MIN_REPAIR_TIMEOUT_SECONDS),
                    MAX_REPAIR_TIMEOUT_SECONDS
            );
        }

        /**
         * 返回大于零且受最大退避约束的基础退避秒数。
         *
         * @return 基础退避秒数
         */
        public int getEffectiveRetryBackoffSeconds() {
            int configured = retryBackoffSeconds > 0
                    ? retryBackoffSeconds
                    : DEFAULT_RETRY_BACKOFF_SECONDS;
            return Math.min(configured, getEffectiveMaxRetryBackoffSeconds());
        }

        /**
         * 返回有界最大退避秒数，并保证不小于有效基础退避。
         *
         * @return 最大退避秒数
         */
        public int getEffectiveMaxRetryBackoffSeconds() {
            int configured = maxRetryBackoffSeconds > 0
                    ? maxRetryBackoffSeconds
                    : DEFAULT_MAX_RETRY_BACKOFF_SECONDS;
            int bounded = Math.min(configured, MAX_RETRY_BACKOFF_SECONDS_LIMIT);
            int base = retryBackoffSeconds > 0
                    ? Math.min(retryBackoffSeconds, MAX_RETRY_BACKOFF_SECONDS_LIMIT)
                    : DEFAULT_RETRY_BACKOFF_SECONDS;
            return Math.max(bounded, base);
        }
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
     * 当 replication.factor < activeDomains.size() 时，按此列表顺序选择前 N 个域写入。
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
     * 副本策略配置（v3.1.0 新增）
     */
    private ReplicationConfig replication = new ReplicationConfig();

    /**
     * 降级写入配置（v3.1.0 新增）
     */
    private DegradedWriteConfig degradedWrite = new DegradedWriteConfig();

    /**
     * 对象存储直传完成、锁和 staging 生命周期配置。
     */
    private DirectUploadConfig directUpload = new DirectUploadConfig();

    /**
     * 返回非空的直传配置，兼容动态配置把整个配置段置空的场景。
     *
     * @return 直传配置
     */
    public DirectUploadConfig getDirectUpload() {
        if (directUpload == null) {
            directUpload = new DirectUploadConfig();
        }
        return directUpload;
    }

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
     *   <li>使用 replication.factor 配置</li>
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

        Integer configuredFactor = replication != null ? replication.getFactor() : null;

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
        validateDegradedWriteTracking();
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

        // v3.1.0 新增配置日志
        int effectiveQuorum = getEffectiveQuorum();
        log.info("Storage configuration validated: activeDomains={}, replicationFactor={}, quorum={}, standbyDomain={}, degradedWrite={}, externalEndpoint={}, directUploadBufferBytes={}, directUploadMaxPartBytes={}",
                activeDomains, effectiveReplicationFactor, effectiveQuorum,
                isStandbyEnabled() ? standbyDomain : "disabled",
                degradedWrite != null && degradedWrite.isEnabled()
                        ? "enabled(min=" + degradedWrite.getMinReplicas()
                        + ",maxSyncFailures=" + degradedWrite.getEffectiveMaxSyncFailures()
                        + ",syncBatchSize=" + degradedWrite.getEffectiveSyncBatchSize()
                        + ",repairTimeoutSeconds=" + degradedWrite.getEffectiveRepairTimeoutSeconds()
                        + ",claimLeaseSeconds=" + degradedWrite.getEffectiveClaimLeaseSeconds() + ")"
                        : "disabled",
                hasExternalEndpoint() ? getEffectiveExternalEndpoint() : "disabled",
                getDirectUpload().getEffectiveStreamBufferBytes(),
                getDirectUpload().getEffectiveMaxPartSizeBytes());
    }

    /**
     * 降级写开启时必须同时持久化同步证据，避免成功响应后永久遗失副本缺口。
     */
    public void validateDegradedWriteTracking() {
        DegradedWriteConfig config = getDegradedWrite();
        if (config != null
                && config.isEnabled()
                && !config.isTrackForSync()) {
            throw new IllegalStateException(
                    "storage.degraded-write.track-for-sync must be enabled when degraded writes are enabled.");
        }
    }
}
