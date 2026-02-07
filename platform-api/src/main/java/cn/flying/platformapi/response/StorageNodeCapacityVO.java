package cn.flying.platformapi.response;

import java.io.Serial;
import java.io.Serializable;

/**
 * 存储节点容量统计。
 *
 * @param nodeName 节点名称
 * @param faultDomain 故障域
 * @param online 节点是否在线
 * @param totalCapacityBytes 节点总容量（字节）
 * @param usedCapacityBytes 节点已用容量（字节）
 * @param usagePercent 节点使用率（0-100）
 */
public record StorageNodeCapacityVO(
        String nodeName,
        String faultDomain,
        boolean online,
        long totalCapacityBytes,
        long usedCapacityBytes,
        double usagePercent
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
