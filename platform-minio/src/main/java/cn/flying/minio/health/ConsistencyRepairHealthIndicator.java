package cn.flying.minio.health;

import cn.flying.minio.config.LogicNodeMapping;
import cn.flying.minio.config.MinioProperties;
import cn.flying.minio.core.MinioClientManager;
import cn.flying.minio.core.MinioMonitor;
import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MinIO 副本一致性健康指标。
 * 检查各逻辑节点的物理节点对之间的数据一致性。
 */
@Slf4j
@Component("minioConsistency")
public class ConsistencyRepairHealthIndicator implements HealthIndicator {

    @Resource
    private MinioClientManager clientManager;

    @Resource
    private MinioMonitor minioMonitor;

    @Resource
    private MinioProperties minioProperties;

    @Override
    public Health health() {
        try {
            List<LogicNodeMapping> mappings = minioProperties.getLogicalMapping();
            if (CollectionUtils.isEmpty(mappings)) {
                return Health.unknown()
                        .withDetail("reason", "未配置逻辑节点映射")
                        .build();
            }

            Map<String, Object> details = new HashMap<>();
            int totalInconsistencies = 0;
            int logicNodesChecked = 0;
            int logicNodesWithIssues = 0;

            for (LogicNodeMapping mapping : mappings) {
                String logicNodeName = mapping.getLogicNodeName();
                List<String> physicalPair = mapping.getPhysicalNodePair();

                if (physicalPair == null || physicalPair.size() != 2) {
                    details.put(logicNodeName, "配置无效");
                    logicNodesWithIssues++;
                    continue;
                }

                String node1 = physicalPair.get(0);
                String node2 = physicalPair.get(1);

                // 检查节点在线状态
                boolean node1Online = minioMonitor.isNodeOnline(node1);
                boolean node2Online = minioMonitor.isNodeOnline(node2);

                if (!node1Online || !node2Online) {
                    Map<String, Object> nodeDetails = new HashMap<>();
                    nodeDetails.put(node1, node1Online ? "online" : "offline");
                    nodeDetails.put(node2, node2Online ? "online" : "offline");
                    nodeDetails.put("status", "部分离线");
                    details.put(logicNodeName, nodeDetails);
                    logicNodesWithIssues++;
                    continue;
                }

                // 快速一致性检查（仅比较对象数量）
                try {
                    int count1 = countObjects(clientManager.getClient(node1), node1);
                    int count2 = countObjects(clientManager.getClient(node2), node2);

                    Map<String, Object> nodeDetails = new HashMap<>();
                    nodeDetails.put(node1 + "_count", count1);
                    nodeDetails.put(node2 + "_count", count2);

                    if (count1 != count2) {
                        int diff = Math.abs(count1 - count2);
                        nodeDetails.put("inconsistency", diff);
                        totalInconsistencies += diff;
                        logicNodesWithIssues++;
                    } else {
                        nodeDetails.put("status", "consistent");
                    }
                    details.put(logicNodeName, nodeDetails);
                    logicNodesChecked++;

                } catch (Exception e) {
                    Map<String, Object> nodeDetails = new HashMap<>();
                    nodeDetails.put("error", e.getMessage());
                    details.put(logicNodeName, nodeDetails);
                    logicNodesWithIssues++;
                }
            }

            details.put("totalLogicNodes", mappings.size());
            details.put("logicNodesChecked", logicNodesChecked);
            details.put("totalInconsistencies", totalInconsistencies);

            Health.Builder builder;
            if (logicNodesWithIssues > 0) {
                if (totalInconsistencies > 0) {
                    builder = Health.status("DEGRADED")
                            .withDetail("reason", "存在副本不一致");
                } else {
                    builder = Health.status("DEGRADED")
                            .withDetail("reason", "部分节点离线或配置无效");
                }
            } else {
                builder = Health.up();
            }

            return builder.withDetails(details).build();

        } catch (Exception e) {
            log.error("MinIO 一致性健康检查失败", e);
            return Health.down()
                    .withDetail("reason", "健康检查失败")
                    .withException(e)
                    .build();
        }
    }

    /**
     * 快速统计桶中的对象数量。
     */
    private int countObjects(MinioClient client, String bucketName) throws Exception {
        if (client == null) {
            return 0;
        }

        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
        if (!exists) {
            return 0;
        }

        int count = 0;
        Iterable<Result<Item>> results = client.listObjects(
                ListObjectsArgs.builder()
                        .bucket(bucketName)
                        .recursive(true)
                        .build()
        );

        for (Result<Item> result : results) {
            Item item = result.get();
            if (!item.isDir()) {
                count++;
            }
        }
        return count;
    }
}
