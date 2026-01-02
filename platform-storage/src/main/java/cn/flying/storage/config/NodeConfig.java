package cn.flying.storage.config;

import lombok.Data;

/**
 * 单个 S3 兼容存储物理节点配置
 */
@Data
public class NodeConfig {
    /**
     * 物理节点唯一名称
     */
    private String name;
    /**
     * 节点访问地址
     */
    private String endpoint;
    /**
     * 访问密钥
     */
    private String accessKey;
    /**
     * 私有密钥
     */
    private String secretKey;

    // ===== 新增：故障域相关配置 =====

    /**
     * 故障域标识：A, B, 或 STANDBY
     * - A/B: 活跃故障域，每个分片在 A 和 B 各存一份
     * - STANDBY: 备用节点池，当活跃域节点不足时自动提升
     */
    private String faultDomain;

    /**
     * 节点权重，用于一致性哈希的虚拟节点数计算
     * 权重越高，分配的分片越多
     * 默认值: 100
     */
    private Integer weight = 100;

    /**
     * 是否启用该节点
     * false 时节点不参与分片分布，但保留配置
     * 默认值: true
     */
    private Boolean enabled = true;

    /**
     * 指标采集端点路径（相对于 endpoint）
     * 支持不同 S3 兼容存储的指标格式：
     * - MinIO: /minio/v2/metrics/node (默认)
     * - 通用 Prometheus: /metrics
     * - 留空则禁用指标采集
     */
    private String metricsPath = "/minio/v2/metrics/node";
} 