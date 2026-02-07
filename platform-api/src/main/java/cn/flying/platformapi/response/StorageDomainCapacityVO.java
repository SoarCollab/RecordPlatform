package cn.flying.platformapi.response;

import java.io.Serial;
import java.io.Serializable;

/**
 * 故障域容量统计。
 *
 * @param domainName 故障域名称
 * @param nodeCount 节点数
 * @param onlineNodeCount 在线节点数
 * @param totalCapacityBytes 故障域总容量（字节）
 * @param usedCapacityBytes 故障域已用容量（字节）
 * @param usagePercent 故障域使用率（0-100）
 */
public record StorageDomainCapacityVO(
        String domainName,
        int nodeCount,
        int onlineNodeCount,
        long totalCapacityBytes,
        long usedCapacityBytes,
        double usagePercent
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
