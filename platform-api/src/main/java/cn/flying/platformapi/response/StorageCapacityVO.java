package cn.flying.platformapi.response;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 集群存储容量统计。
 *
 * @param totalCapacityBytes 集群总容量（字节）
 * @param usedCapacityBytes 集群已用容量（字节）
 * @param availableCapacityBytes 集群可用容量（字节）
 * @param degraded 是否处于降级数据源（部分节点缺失指标）
 * @param source 数据来源标识
 * @param nodes 节点容量明细
 * @param domains 故障域容量汇总
 */
public record StorageCapacityVO(
        long totalCapacityBytes,
        long usedCapacityBytes,
        long availableCapacityBytes,
        boolean degraded,
        String source,
        List<StorageNodeCapacityVO> nodes,
        List<StorageDomainCapacityVO> domains
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
