package cn.flying.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import java.util.Properties;

@Slf4j
@Component("redis")
@RequiredArgsConstructor
public class RedisHealthIndicator implements HealthIndicator {

    private final RedisConnectionFactory connectionFactory;

    @Override
    public Health health() {
        long startTime = System.currentTimeMillis();

        try (RedisConnection connection = connectionFactory.getConnection()) {
            String pong = connection.ping();
            long latency = System.currentTimeMillis() - startTime;

            if (!"PONG".equals(pong)) {
                return Health.down()
                        .withDetail("reason", "Unexpected PING response: " + pong)
                        .build();
            }

            Health.Builder builder = Health.up()
                    .withDetail("latencyMs", latency);

            try {
                Properties info = connection.serverCommands().info("server");
                if (info != null) {
                    builder.withDetail("redisVersion", info.getProperty("redis_version", "unknown"));
                    builder.withDetail("mode", info.getProperty("redis_mode", "unknown"));
                }

                Properties replication = connection.serverCommands().info("replication");
                if (replication != null) {
                    String role = replication.getProperty("role", "unknown");
                    builder.withDetail("role", role);

                    if ("slave".equals(role)) {
                        String lag = replication.getProperty("master_repl_offset", "0");
                        builder.withDetail("replicationLag", lag);
                    }
                }
            } catch (Exception e) {
                log.debug("Could not retrieve Redis server info", e);
            }

            if (latency > 100) {
                return builder.status("DEGRADED")
                        .withDetail("warning", "High latency detected: " + latency + "ms")
                        .build();
            }

            return builder.build();

        } catch (Exception e) {
            log.error("Redis health check failed", e);
            return Health.down()
                    .withDetail("reason", "Failed to connect to Redis")
                    .withException(e)
                    .build();
        }
    }
}
