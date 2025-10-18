package cn.flying.monitor.common.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

/**
 * 数据库健康检查指示器
 * 检查数据库连接状态和响应时间
 */
@Slf4j
@Component
public class DatabaseHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;
    private static final String HEALTH_CHECK_SQL = "SELECT 1";
    private static final long SLOW_QUERY_THRESHOLD_MS = 1000; // 1秒

    public DatabaseHealthIndicator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        try {
            return checkDatabaseHealth();
        } catch (Exception e) {
            log.error("数据库健康检查失败", e);
            return Health.down()
                .withDetail("error", e.getMessage())
                .withDetail("exception", e.getClass().getSimpleName())
                .build();
        }
    }

    private Health checkDatabaseHealth() throws SQLException {
        Instant startTime = Instant.now();
        
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(HEALTH_CHECK_SQL);
             ResultSet resultSet = statement.executeQuery()) {
            
            Duration responseTime = Duration.between(startTime, Instant.now());
            long responseTimeMs = responseTime.toMillis();
            
            if (resultSet.next() && resultSet.getInt(1) == 1) {
                Health.Builder healthBuilder = Health.up()
                    .withDetail("database", "MySQL")
                    .withDetail("responseTime", responseTimeMs + "ms")
                    .withDetail("url", getMaskedUrl())
                    .withDetail("validationQuery", HEALTH_CHECK_SQL);
                
                // 检查响应时间
                if (responseTimeMs > SLOW_QUERY_THRESHOLD_MS) {
                    healthBuilder.withDetail("warning", "数据库响应时间较慢: " + responseTimeMs + "ms");
                    log.warn("数据库响应时间较慢: {}ms", responseTimeMs);
                }
                
                // 获取连接池信息
                addConnectionPoolInfo(healthBuilder);
                
                log.debug("数据库健康检查成功，响应时间: {}ms", responseTimeMs);
                return healthBuilder.build();
            } else {
                return Health.down()
                    .withDetail("error", "数据库查询返回异常结果")
                    .withDetail("responseTime", responseTimeMs + "ms")
                    .build();
            }
        }
    }

    private String getMaskedUrl() {
        try {
            String url = dataSource.getConnection().getMetaData().getURL();
            // 隐藏敏感信息，只显示主机和数据库名
            return url.replaceAll("password=[^&]*", "password=***");
        } catch (SQLException e) {
            return "无法获取数据库URL";
        }
    }

    private void addConnectionPoolInfo(Health.Builder healthBuilder) {
        try {
            // 尝试获取连接池信息（适用于HikariCP）
            if (dataSource.getClass().getName().contains("HikariDataSource")) {
                addHikariPoolInfo(healthBuilder);
            } else if (dataSource.getClass().getName().contains("DruidDataSource")) {
                addDruidPoolInfo(healthBuilder);
            }
        } catch (Exception e) {
            log.debug("无法获取连接池信息: {}", e.getMessage());
        }
    }

    private void addHikariPoolInfo(Health.Builder healthBuilder) {
        try {
            // 使用反射获取HikariCP连接池信息
            Object hikariPool = dataSource.getClass().getMethod("getHikariPoolMXBean").invoke(dataSource);
            if (hikariPool != null) {
                int activeConnections = (Integer) hikariPool.getClass().getMethod("getActiveConnections").invoke(hikariPool);
                int idleConnections = (Integer) hikariPool.getClass().getMethod("getIdleConnections").invoke(hikariPool);
                int totalConnections = (Integer) hikariPool.getClass().getMethod("getTotalConnections").invoke(hikariPool);
                
                healthBuilder
                    .withDetail("pool.active", activeConnections)
                    .withDetail("pool.idle", idleConnections)
                    .withDetail("pool.total", totalConnections)
                    .withDetail("pool.type", "HikariCP");
            }
        } catch (Exception e) {
            log.debug("无法获取HikariCP连接池信息: {}", e.getMessage());
        }
    }

    private void addDruidPoolInfo(Health.Builder healthBuilder) {
        try {
            // 使用反射获取Druid连接池信息
            int activeCount = (Integer) dataSource.getClass().getMethod("getActiveCount").invoke(dataSource);
            int poolingCount = (Integer) dataSource.getClass().getMethod("getPoolingCount").invoke(dataSource);
            int maxActive = (Integer) dataSource.getClass().getMethod("getMaxActive").invoke(dataSource);
            
            healthBuilder
                .withDetail("pool.active", activeCount)
                .withDetail("pool.idle", poolingCount)
                .withDetail("pool.max", maxActive)
                .withDetail("pool.type", "Druid");
        } catch (Exception e) {
            log.debug("无法获取Druid连接池信息: {}", e.getMessage());
        }
    }
}