package cn.flying.health;

import cn.flying.platformapi.constant.Result;
import cn.flying.service.remote.FileRemoteClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * S3 兼容对象存储健康检查指示器
 */
@Slf4j
@Component("s3Storage")
public class S3StorageHealthIndicator implements HealthIndicator {

    @Resource
    private FileRemoteClient fileRemoteClient;

    @Override
    public Health health() {
        try {
            Result<Map<String, Boolean>> result = fileRemoteClient.getClusterHealth();
            if (result == null || result.getData() == null) {
                return Health.down()
                        .withDetail("reason", "Unable to retrieve S3 storage cluster status")
                        .build();
            }

            Map<String, Boolean> nodeStatus = result.getData();
            long onlineCount = nodeStatus.values().stream()
                    .filter(Boolean.TRUE::equals)
                    .count();

            if (onlineCount == 0) {
                return Health.down()
                        .withDetail("onlineCount", onlineCount)
                        .withDetail("totalNodes", nodeStatus.size())
                        .withDetail("nodes", nodeStatus)
                        .withDetail("reason", "No S3 storage nodes are online")
                        .build();
            }

            Health.Builder builder = Health.up()
                    .withDetail("onlineCount", onlineCount)
                    .withDetail("totalNodes", nodeStatus.size())
                    .withDetail("nodes", nodeStatus);

            if (onlineCount == 1) {
                return builder.status("DEGRADED")
                        .withDetail("warning", "Only one S3 storage node online")
                        .build();
            }

            return builder.build();
        } catch (Exception e) {
            log.error("S3 storage health check error", e);
            return Health.down()
                    .withDetail("reason", "Failed to connect to S3 storage cluster")
                    .build();
        }
    }
}
