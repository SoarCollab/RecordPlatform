package cn.flying.storage.config;

import lombok.Data;

/**
 * 故障域配置
 * 定义每个故障域的行为和约束
 */
@Data
public class FaultDomainConfig {

    /**
     * 故障域名称: A, B, 或 STANDBY
     */
    private String name;

    /**
     * 该域最少需要的健康节点数
     * 当健康节点低于此值时，触发备用节点提升
     */
    private Integer minNodes = 1;

    /**
     * 每个分片在该域中存储的副本数
     * - 活跃域 (A/B): 通常为 1
     * - 备用域 (STANDBY): 通常为 0
     */
    private Integer replicaCount = 1;

    /**
     * 是否接受写入
     * - 活跃域: true
     * - 备用域: false (仅在提升后接受写入)
     */
    private Boolean acceptsWrites = true;
}
