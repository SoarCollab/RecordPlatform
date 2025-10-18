package cn.flying.monitor.common.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * RabbitMQ健康检查指示器
 * 检查RabbitMQ连接状态和可用性
 */
@Slf4j
@Component
@ConditionalOnClass(ConnectionFactory.class)
public class RabbitMQHealthIndicator implements HealthIndicator {

    private final ConnectionFactory connectionFactory;
    private static final long SLOW_RESPONSE_THRESHOLD_MS = 200; // 200ms

    public RabbitMQHealthIndicator(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public Health health() {
        try {
            return checkRabbitMQHealth();
        } catch (Exception e) {
            log.error("RabbitMQ健康检查失败", e);
            return Health.down()
                .withDetail("error", e.getMessage())
                .withDetail("exception", e.getClass().getSimpleName())
                .build();
        }
    }

    private Health checkRabbitMQHealth() {
        Instant startTime = Instant.now();
        
        try (Connection connection = connectionFactory.createConnection()) {
            Duration responseTime = Duration.between(startTime, Instant.now());
            long responseTimeMs = responseTime.toMillis();
            
            Health.Builder healthBuilder = Health.up()
                .withDetail("responseTime", responseTimeMs + "ms")
                .withDetail("connection", "成功");
            
            // 检查响应时间
            if (responseTimeMs > SLOW_RESPONSE_THRESHOLD_MS) {
                healthBuilder.withDetail("warning", "RabbitMQ响应时间较慢: " + responseTimeMs + "ms");
                log.warn("RabbitMQ响应时间较慢: {}ms", responseTimeMs);
            }
            
            // 获取连接信息
            addConnectionInfo(healthBuilder, connection);
            
            // 获取服务器属性
            addServerProperties(healthBuilder, connection);
            
            log.debug("RabbitMQ健康检查成功，响应时间: {}ms", responseTimeMs);
            return healthBuilder.build();
            
        } catch (Exception e) {
            log.error("RabbitMQ健康检查异常", e);
            return Health.down()
                .withDetail("error", e.getMessage())
                .withDetail("exception", e.getClass().getSimpleName())
                .build();
        }
    }

    private void addConnectionInfo(Health.Builder healthBuilder, Connection connection) {
        try {
            if (connection.isOpen()) {
                healthBuilder.withDetail("status", "已连接");
                
                // 获取底层连接信息
                var delegate = connection.getDelegate();
                if (delegate != null) {
                    String address = String.valueOf(delegate.getAddress());
                    int port = delegate.getPort();
                    healthBuilder
                        .withDetail("host", address)
                        .withDetail("port", port);
                }
                
            } else {
                healthBuilder.withDetail("status", "连接已关闭");
            }
        } catch (Exception e) {
            log.debug("无法获取RabbitMQ连接信息: {}", e.getMessage());
        }
    }

    private void addServerProperties(Health.Builder healthBuilder, Connection connection) {
        try {
            // 获取服务器属性
            var delegate = connection.getDelegate();
            if (delegate != null) {
                var serverProperties = delegate.getServerProperties();
                if (serverProperties != null) {
                    Object version = serverProperties.get("version");
                    Object product = serverProperties.get("product");
                    Object platform = serverProperties.get("platform");
                    
                    if (version != null) {
                        healthBuilder.withDetail("version", version.toString());
                    }
                    if (product != null) {
                        healthBuilder.withDetail("product", product.toString());
                    }
                    if (platform != null) {
                        healthBuilder.withDetail("platform", platform.toString());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("无法获取RabbitMQ服务器属性: {}", e.getMessage());
        }
    }
}