package cn.flying.storage.health;

import cn.flying.storage.config.LogicNodeMapping;
import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.S3ClientManager;
import cn.flying.storage.core.S3Monitor;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * S3 副本一致性健康指标。
 * 检查各逻辑节点的物理节点对之间的数据一致性。
 */
@Slf4j
@Component("storageConsistency")
public class ConsistencyRepairHealthIndicator implements HealthIndicator {

    @Resource
    private S3ClientManager clientManager;

    @Resource
    private S3Monitor s3Monitor;

    @Resource
    private StorageProperties storageProperties;

    @Override
    public Health health() {
        try {
            List<LogicNodeMapping> mappings = storageProperties.getLogicalMapping();
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
                boolean node1Online = s3Monitor.isNodeOnline(node1);
                boolean node2Online = s3Monitor.isNodeOnline(node2);

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
            log.error("S3 一致性健康检查失败", e);
            return Health.down()
                    .withDetail("reason", "健康检查失败")
                    .withException(e)
                    .build();
        }
    }

    /**
     * 快速统计桶中的对象数量。
     */
    private int countObjects(S3Client client, String bucketName) throws Exception {
        if (client == null) {
            return 0;
        }

        // Check if bucket exists using headBucket
        try {
            client.headBucket(request -> request.bucket(bucketName));
        } catch (NoSuchBucketException e) {
            return 0;
        }

        int count = 0;
        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .build();

        ListObjectsV2Response response = client.listObjectsV2(request);

        if (response.contents() != null) {
            // AWS SDK v2 returns S3Object which don't have isDir() method
            // Count all objects as files (AWS S3 doesn't have directories, only key prefixes)
            count = (int) response.contents().stream()
                    .filter(obj -> !obj.key().endsWith("/"))
                    .count();
        }

        return count;
    }
}
