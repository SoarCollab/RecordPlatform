package cn.flying.storage.health;

import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.FaultDomainManager;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * S3 副本一致性健康指标。
 * 检查各故障域之间的数据一致性。
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

    @Resource
    private FaultDomainManager faultDomainManager;

    @Override
    public Health health() {
        try {
            // 动态获取所有活跃域
            List<String> activeDomains = faultDomainManager.getActiveDomains();

            // 单域模式跳过跨域检查
            if (storageProperties.isSingleDomainMode()) {
                return Health.up()
                        .withDetail("mode", "single-domain")
                        .withDetail("reason", "单域模式，跳过跨域一致性检查")
                        .build();
            }

            // 检查是否所有活跃域都有节点
            boolean allDomainsHaveNodes = activeDomains.stream()
                    .allMatch(d -> !CollectionUtils.isEmpty(faultDomainManager.getNodesInDomain(d)));

            if (!allDomainsHaveNodes) {
                Map<String, Integer> domainNodeCounts = new HashMap<>();
                for (String domain : activeDomains) {
                    domainNodeCounts.put(domain, faultDomainManager.getNodesInDomain(domain).size());
                }
                return Health.unknown()
                        .withDetail("reason", "部分故障域节点配置不完整")
                        .withDetail("domainNodeCounts", domainNodeCounts)
                        .build();
            }

            Map<String, Object> details = new HashMap<>();
            int totalInconsistencies = 0;
            int domainsWithIssues = 0;

            // 动态检查每个活跃域
            Map<String, Integer> domainObjectCounts = new HashMap<>();
            for (String domainName : activeDomains) {
                Set<String> nodesInDomain = faultDomainManager.getNodesInDomain(domainName);
                Map<String, Object> domainDetails = checkDomainHealth(domainName, nodesInDomain);
                details.put("domain_" + domainName, domainDetails);
                if (domainDetails.containsKey("issues")) {
                    domainsWithIssues++;
                }
                // 统计对象数
                int objectCount = getTotalObjectCount(nodesInDomain);
                domainObjectCounts.put(domainName, objectCount);
                details.put("totalObjectsIn_" + domainName, objectCount);
            }

            // 跨域一致性检查（N域两两比较，取最大差异）
            if (domainObjectCounts.size() >= 2) {
                int maxDiff = 0;
                String[] domains = domainObjectCounts.keySet().toArray(new String[0]);
                for (int i = 0; i < domains.length; i++) {
                    for (int j = i + 1; j < domains.length; j++) {
                        int diff = Math.abs(domainObjectCounts.get(domains[i]) - domainObjectCounts.get(domains[j]));
                        maxDiff = Math.max(maxDiff, diff);
                    }
                }
                if (maxDiff > 0) {
                    totalInconsistencies = maxDiff;
                    details.put("crossDomainInconsistency", totalInconsistencies);
                }
            }

            // 备用节点状态
            if (storageProperties.isStandbyEnabled()) {
                String standbyDomain = storageProperties.getStandbyDomain();
                Set<String> standbyNodes = faultDomainManager.getNodesInDomain(standbyDomain);
                if (!standbyNodes.isEmpty()) {
                    long healthyStandby = standbyNodes.stream()
                            .filter(s3Monitor::isNodeOnline)
                            .count();
                    details.put("standbyDomain", standbyDomain);
                    details.put("standbyNodes", standbyNodes.size());
                    details.put("healthyStandbyNodes", healthyStandby);
                }
            }

            Health.Builder builder;
            if (domainsWithIssues > 0 || totalInconsistencies > 0) {
                builder = Health.status("DEGRADED")
                        .withDetail("reason", totalInconsistencies > 0 ?
                                "存在跨域副本不一致" : "部分节点离线");
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
     * 检查单个故障域的健康状态
     */
    private Map<String, Object> checkDomainHealth(String domainName, Set<String> nodes) {
        Map<String, Object> details = new HashMap<>();
        int onlineCount = 0;
        int offlineCount = 0;

        for (String node : nodes) {
            boolean online = s3Monitor.isNodeOnline(node);
            details.put(node, online ? "online" : "offline");
            if (online) {
                onlineCount++;
            } else {
                offlineCount++;
            }
        }

        details.put("totalNodes", nodes.size());
        details.put("onlineNodes", onlineCount);

        if (offlineCount > 0) {
            details.put("issues", offlineCount + " node(s) offline");
        }

        return details;
    }

    /**
     * 获取域内所有节点的总对象数
     */
    private int getTotalObjectCount(Set<String> nodes) {
        int total = 0;
        for (String node : nodes) {
            if (s3Monitor.isNodeOnline(node)) {
                try {
                    total += countObjects(clientManager.getClient(node), node);
                } catch (Exception e) {
                    log.warn("统计节点 {} 对象数失败: {}", node, e.getMessage());
                }
            }
        }
        return total;
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
            count = (int) response.contents().stream()
                    .filter(obj -> !obj.key().endsWith("/"))
                    .count();
        }

        return count;
    }
}
