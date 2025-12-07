package cn.flying.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

@Slf4j
@Component("database")
@RequiredArgsConstructor
public class DatabaseHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    @Override
    public Health health() {
        long startTime = System.currentTimeMillis();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {

            statement.setQueryTimeout(3);
            try (ResultSet rs = statement.executeQuery("SELECT 1")) {
                if (rs.next()) {
                    long latency = System.currentTimeMillis() - startTime;
                    Health.Builder builder = Health.up()
                            .withDetail("latencyMs", latency)
                            .withDetail("database", connection.getMetaData().getDatabaseProductName())
                            .withDetail("version", connection.getMetaData().getDatabaseProductVersion());

                    if (latency > 500) {
                        return builder.status("DEGRADED")
                                .withDetail("warning", "High latency detected: " + latency + "ms")
                                .build();
                    }

                    return builder.build();
                }
            }

            return Health.down()
                    .withDetail("reason", "Query returned no result")
                    .build();

        } catch (Exception e) {
            log.error("Database health check failed", e);
            return Health.down()
                    .withDetail("reason", "Failed to execute health check query")
                    .withException(e)
                    .build();
        }
    }
}
