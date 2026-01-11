package cn.flying.health;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.external.DistributedStorageService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.*;

/**
 * S3 兼容对象存储健康检查指示器
 */
@Slf4j
@Component("s3Storage")
public class S3StorageHealthIndicator implements HealthIndicator {

    @DubboReference(id = "storageServiceHealth", version = DistributedStorageService.VERSION, timeout = 3000, retries = 0, providedBy = "RecordPlatform_storage")
    private DistributedStorageService storageService;

    private static final int TIMEOUT_SECONDS = 3;

    @Resource(name = "healthIndicatorExecutor")
    private ExecutorService executor;

    @Override
    public Health health() {
        Future<Health> future = executor.submit(this::checkS3Health);
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("S3 storage health check timed out");
            return Health.down()
                    .withDetail("reason", "Health check timed out after " + TIMEOUT_SECONDS + "s")
                    .build();
        } catch (Exception e) {
            log.error("S3 storage health check failed", e);
            return Health.down()
                    .withException(e)
                    .build();
        }
    }

    private Health checkS3Health() {
        try {
            Result<Map<String, Boolean>> result = storageService.getClusterHealth();
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
                    .withException(e)
                    .build();
        }
    }
}
