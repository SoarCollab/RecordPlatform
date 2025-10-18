package cn.flying.monitor.common.health;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.domain.Ready;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * InfluxDB健康检查指示器
 * 检查InfluxDB连接状态和可用性
 */
@Slf4j
@Component
@ConditionalOnClass(InfluxDBClient.class)
public class InfluxDBHealthIndicator implements HealthIndicator {

    private final InfluxDBClient influxDBClient;
    private static final long SLOW_RESPONSE_THRESHOLD_MS = 500; // 500ms

    public InfluxDBHealthIndicator(InfluxDBClient influxDBClient) {
        this.influxDBClient = influxDBClient;
    }

    @Override
    public Health health() {
        try {
            return checkInfluxDBHealth();
        } catch (Exception e) {
            log.error("InfluxDB健康检查失败", e);
            return Health.down()
                .withDetail("error", e.getMessage())
                .withDetail("exception", e.getClass().getSimpleName())
                .build();
        }
    }

    private Health checkInfluxDBHealth() {
        Instant startTime = Instant.now();
        
        try {
            // 检查InfluxDB是否准备就绪
            Ready ready = influxDBClient.ready();
            
            Duration responseTime = Duration.between(startTime, Instant.now());
            long responseTimeMs = responseTime.toMillis();
            
            Health.Builder healthBuilder = Health.up()
                .withDetail("status", ready.getStatus().getValue())
                .withDetail("responseTime", responseTimeMs + "ms")
                .withDetail("started", ready.getStarted())
                .withDetail("up", ready.getUp());
            
            // 检查响应时间
            if (responseTimeMs > SLOW_RESPONSE_THRESHOLD_MS) {
                healthBuilder.withDetail("warning", "InfluxDB响应时间较慢: " + responseTimeMs + "ms");
                log.warn("InfluxDB响应时间较慢: {}ms", responseTimeMs);
            }
            
            // 获取InfluxDB版本信息
            addInfluxDBVersionInfo(healthBuilder);
            
            // 测试基本查询功能
            testBasicQuery(healthBuilder);
            
            log.debug("InfluxDB健康检查成功，响应时间: {}ms", responseTimeMs);
            return healthBuilder.build();
            
        } catch (Exception e) {
            log.error("InfluxDB健康检查异常", e);
            return Health.down()
                .withDetail("error", e.getMessage())
                .withDetail("exception", e.getClass().getSimpleName())
                .build();
        }
    }

    private void addInfluxDBVersionInfo(Health.Builder healthBuilder) {
        try {
            // 获取InfluxDB版本信息
            String version = influxDBClient.version();
            healthBuilder.withDetail("version", version);
        } catch (Exception e) {
            log.debug("无法获取InfluxDB版本信息: {}", e.getMessage());
            healthBuilder.withDetail("version", "未知");
        }
    }

    private void testBasicQuery(Health.Builder healthBuilder) {
        try {
            // 执行简单的查询测试连接
            Instant queryStart = Instant.now();
            
            // 查询系统信息
            String flux = "import \"system\"\nsystem.time()";
            influxDBClient.getQueryApi().query(flux);
            
            Duration queryTime = Duration.between(queryStart, Instant.now());
            healthBuilder
                .withDetail("query.test", "成功")
                .withDetail("query.responseTime", queryTime.toMillis() + "ms");
            
        } catch (Exception e) {
            log.debug("InfluxDB查询测试失败: {}", e.getMessage());
            healthBuilder
                .withDetail("query.test", "失败")
                .withDetail("query.error", e.getMessage());
        }
    }
}