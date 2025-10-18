package cn.flying.monitor.common.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Properties;

/**
 * Redis健康检查指示器
 * 检查Redis连接状态和性能
 */
@Slf4j
@Component
public class RedisHealthIndicator implements HealthIndicator {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisConnectionFactory connectionFactory;
    private static final String HEALTH_CHECK_KEY = "health:check";
    private static final String HEALTH_CHECK_VALUE = "ok";
    private static final long SLOW_RESPONSE_THRESHOLD_MS = 100; // 100ms

    public RedisHealthIndicator(RedisTemplate<String, Object> redisTemplate,
                               RedisConnectionFactory connectionFactory) {
        this.redisTemplate = redisTemplate;
        this.connectionFactory = connectionFactory;
    }

    @Override
    public Health health() {
        try {
            return checkRedisHealth();
        } catch (Exception e) {
            log.error("Redis健康检查失败", e);
            return Health.down()
                .withDetail("error", e.getMessage())
                .withDetail("exception", e.getClass().getSimpleName())
                .build();
        }
    }

    private Health checkRedisHealth() {
        Instant startTime = Instant.now();
        
        try (RedisConnection connection = connectionFactory.getConnection()) {
            // 执行PING命令
            String pongResponse = connection.ping();
            
            // 测试读写操作
            redisTemplate.opsForValue().set(HEALTH_CHECK_KEY, HEALTH_CHECK_VALUE, Duration.ofSeconds(10));
            String readValue = (String) redisTemplate.opsForValue().get(HEALTH_CHECK_KEY);
            
            Duration responseTime = Duration.between(startTime, Instant.now());
            long responseTimeMs = responseTime.toMillis();
            
            Health.Builder healthBuilder = Health.up()
                .withDetail("ping", pongResponse)
                .withDetail("responseTime", responseTimeMs + "ms")
                .withDetail("readWrite", HEALTH_CHECK_VALUE.equals(readValue) ? "OK" : "FAILED");
            
            // 检查响应时间
            if (responseTimeMs > SLOW_RESPONSE_THRESHOLD_MS) {
                healthBuilder.withDetail("warning", "Redis响应时间较慢: " + responseTimeMs + "ms");
                log.warn("Redis响应时间较慢: {}ms", responseTimeMs);
            }
            
            // 获取Redis服务器信息
            addRedisServerInfo(healthBuilder, connection);
            
            // 获取内存使用情况
            addRedisMemoryInfo(healthBuilder, connection);
            
            log.debug("Redis健康检查成功，响应时间: {}ms", responseTimeMs);
            return healthBuilder.build();
            
        } catch (Exception e) {
            log.error("Redis健康检查异常", e);
            return Health.down()
                .withDetail("error", e.getMessage())
                .withDetail("exception", e.getClass().getSimpleName())
                .build();
        }
    }

    private void addRedisServerInfo(Health.Builder healthBuilder, RedisConnection connection) {
        try {
            Properties serverInfo = connection.info("server");
            if (serverInfo != null) {
                healthBuilder
                    .withDetail("version", serverInfo.getProperty("redis_version"))
                    .withDetail("mode", serverInfo.getProperty("redis_mode"))
                    .withDetail("os", serverInfo.getProperty("os"))
                    .withDetail("uptime", serverInfo.getProperty("uptime_in_seconds") + "s");
            }
        } catch (Exception e) {
            log.debug("无法获取Redis服务器信息: {}", e.getMessage());
        }
    }

    private void addRedisMemoryInfo(Health.Builder healthBuilder, RedisConnection connection) {
        try {
            Properties memoryInfo = connection.info("memory");
            if (memoryInfo != null) {
                String usedMemory = memoryInfo.getProperty("used_memory_human");
                String maxMemory = memoryInfo.getProperty("maxmemory_human");
                String memoryUsage = memoryInfo.getProperty("used_memory_rss_human");
                
                healthBuilder
                    .withDetail("memory.used", usedMemory != null ? usedMemory : "N/A")
                    .withDetail("memory.max", maxMemory != null ? maxMemory : "N/A")
                    .withDetail("memory.rss", memoryUsage != null ? memoryUsage : "N/A");
                
                // 计算内存使用率
                String usedMemoryBytes = memoryInfo.getProperty("used_memory");
                String maxMemoryBytes = memoryInfo.getProperty("maxmemory");
                if (usedMemoryBytes != null && maxMemoryBytes != null && !maxMemoryBytes.equals("0")) {
                    try {
                        long used = Long.parseLong(usedMemoryBytes);
                        long max = Long.parseLong(maxMemoryBytes);
                        double usagePercent = (double) used / max * 100;
                        healthBuilder.withDetail("memory.usage_percent", String.format("%.2f%%", usagePercent));
                        
                        // 内存使用率警告
                        if (usagePercent > 80) {
                            healthBuilder.withDetail("warning", "Redis内存使用率较高: " + String.format("%.2f%%", usagePercent));
                            log.warn("Redis内存使用率较高: {:.2f}%", usagePercent);
                        }
                    } catch (NumberFormatException e) {
                        log.debug("无法解析Redis内存使用数据");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("无法获取Redis内存信息: {}", e.getMessage());
        }
    }
}