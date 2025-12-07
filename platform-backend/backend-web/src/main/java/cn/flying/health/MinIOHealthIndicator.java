package cn.flying.health;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.external.DistributedStorageService;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@Component("minio")
public class MinIOHealthIndicator implements HealthIndicator {

    @DubboReference(timeout = 3000, retries = 0)
    private DistributedStorageService storageService;

    private static final int TIMEOUT_SECONDS = 3;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public Health health() {
        Future<Health> future = executor.submit(this::checkMinioHealth);
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("MinIO health check timed out");
            return Health.down()
                    .withDetail("reason", "Health check timed out after " + TIMEOUT_SECONDS + "s")
                    .build();
        } catch (Exception e) {
            log.error("MinIO health check failed", e);
            return Health.down()
                    .withException(e)
                    .build();
        }
    }

    private Health checkMinioHealth() {
        try {
            Result<Map<String, Boolean>> result = storageService.getClusterHealth();
            if (result == null || result.getData() == null) {
                return Health.down()
                        .withDetail("reason", "Unable to retrieve MinIO cluster status")
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
                        .withDetail("reason", "No MinIO nodes are online")
                        .build();
            }

            Health.Builder builder = Health.up()
                    .withDetail("onlineCount", onlineCount)
                    .withDetail("totalNodes", nodeStatus.size())
                    .withDetail("nodes", nodeStatus);

            if (onlineCount == 1) {
                return builder.status("DEGRADED")
                        .withDetail("warning", "Only one MinIO node online")
                        .build();
            }

            return builder.build();
        } catch (Exception e) {
            log.error("MinIO health check error", e);
            return Health.down()
                    .withDetail("reason", "Failed to connect to MinIO cluster")
                    .withException(e)
                    .build();
        }
    }
}
