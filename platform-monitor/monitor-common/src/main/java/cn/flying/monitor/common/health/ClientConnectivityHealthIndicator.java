package cn.flying.monitor.common.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 客户端连接健康检查指示器
 * 检查客户端连接状态和活跃度
 */
@Slf4j
@Component
public class ClientConnectivityHealthIndicator implements HealthIndicator {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final String CLIENT_HEARTBEAT_PREFIX = "client:heartbeat:";
    private static final String CLIENT_STATUS_PREFIX = "client:status:";
    private static final long HEARTBEAT_TIMEOUT_MINUTES = 5; // 5分钟无心跳视为离线

    public ClientConnectivityHealthIndicator(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Health health() {
        try {
            return checkClientConnectivity();
        } catch (Exception e) {
            log.error("客户端连接健康检查失败", e);
            return Health.down()
                .withDetail("error", e.getMessage())
                .withDetail("exception", e.getClass().getSimpleName())
                .build();
        }
    }

    private Health checkClientConnectivity() {
        Instant startTime = Instant.now();
        
        try {
            // 获取所有客户端心跳键
            Set<String> heartbeatKeys = redisTemplate.keys(CLIENT_HEARTBEAT_PREFIX + "*");
            
            int totalClients = 0;
            int activeClients = 0;
            int inactiveClients = 0;
            long oldestHeartbeat = System.currentTimeMillis();
            long newestHeartbeat = 0;
            
            if (heartbeatKeys != null) {
                totalClients = heartbeatKeys.size();
                long currentTime = System.currentTimeMillis();
                long timeoutThreshold = currentTime - TimeUnit.MINUTES.toMillis(HEARTBEAT_TIMEOUT_MINUTES);
                
                for (String key : heartbeatKeys) {
                    try {
                        Object heartbeatObj = redisTemplate.opsForValue().get(key);
                        if (heartbeatObj instanceof Long heartbeatTime) {
                            if (heartbeatTime > timeoutThreshold) {
                                activeClients++;
                            } else {
                                inactiveClients++;
                            }
                            
                            // 更新最新和最旧心跳时间
                            if (heartbeatTime > newestHeartbeat) {
                                newestHeartbeat = heartbeatTime;
                            }
                            if (heartbeatTime < oldestHeartbeat) {
                                oldestHeartbeat = heartbeatTime;
                            }
                        }
                    } catch (Exception e) {
                        log.debug("处理客户端心跳键异常: {}", key, e);
                        inactiveClients++;
                    }
                }
            }
            
            Duration responseTime = Duration.between(startTime, Instant.now());
            
            Health.Builder healthBuilder = Health.up()
                .withDetail("responseTime", responseTime.toMillis() + "ms")
                .withDetail("clients.total", totalClients)
                .withDetail("clients.active", activeClients)
                .withDetail("clients.inactive", inactiveClients);
            
            // 计算客户端活跃率
            if (totalClients > 0) {
                double activeRate = (double) activeClients / totalClients * 100;
                healthBuilder.withDetail("clients.activeRate", String.format("%.2f%%", activeRate));
                
                // 活跃率警告
                if (activeRate < 50) {
                    healthBuilder.withDetail("warning", "客户端活跃率较低: " + String.format("%.2f%%", activeRate));
                    log.warn("客户端活跃率较低: {:.2f}%", activeRate);
                }
            }
            
            // 添加心跳时间信息
            if (newestHeartbeat > 0) {
                long timeSinceNewest = System.currentTimeMillis() - newestHeartbeat;
                healthBuilder.withDetail("heartbeat.newest", formatDuration(timeSinceNewest) + " ago");
            }
            
            if (oldestHeartbeat < System.currentTimeMillis()) {
                long timeSinceOldest = System.currentTimeMillis() - oldestHeartbeat;
                healthBuilder.withDetail("heartbeat.oldest", formatDuration(timeSinceOldest) + " ago");
            }
            
            // 获取客户端状态统计
            addClientStatusInfo(healthBuilder);
            
            log.debug("客户端连接健康检查成功，总数: {}, 活跃: {}, 非活跃: {}", 
                     totalClients, activeClients, inactiveClients);
            
            return healthBuilder.build();
            
        } catch (Exception e) {
            log.error("客户端连接健康检查异常", e);
            return Health.down()
                .withDetail("error", e.getMessage())
                .withDetail("exception", e.getClass().getSimpleName())
                .build();
        }
    }

    private void addClientStatusInfo(Health.Builder healthBuilder) {
        try {
            Set<String> statusKeys = redisTemplate.keys(CLIENT_STATUS_PREFIX + "*");
            
            int onlineCount = 0;
            int offlineCount = 0;
            int maintenanceCount = 0;
            
            if (statusKeys != null) {
                for (String key : statusKeys) {
                    try {
                        Object statusObj = redisTemplate.opsForValue().get(key);
                        if (statusObj instanceof String status) {
                            switch (status.toLowerCase()) {
                                case "online" -> onlineCount++;
                                case "offline" -> offlineCount++;
                                case "maintenance" -> maintenanceCount++;
                            }
                        }
                    } catch (Exception e) {
                        log.debug("处理客户端状态键异常: {}", key, e);
                    }
                }
            }
            
            healthBuilder
                .withDetail("status.online", onlineCount)
                .withDetail("status.offline", offlineCount)
                .withDetail("status.maintenance", maintenanceCount);
            
        } catch (Exception e) {
            log.debug("无法获取客户端状态信息: {}", e.getMessage());
        }
    }

    private String formatDuration(long milliseconds) {
        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        
        if (days > 0) {
            return days + "d " + (hours % 24) + "h";
        } else if (hours > 0) {
            return hours + "h " + (minutes % 60) + "m";
        } else if (minutes > 0) {
            return minutes + "m " + (seconds % 60) + "s";
        } else {
            return seconds + "s";
        }
    }
}