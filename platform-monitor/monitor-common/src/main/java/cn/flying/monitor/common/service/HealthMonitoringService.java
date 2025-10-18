package cn.flying.monitor.common.service;

import cn.flying.monitor.common.util.ErrorHandlingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 健康监控服务
 * 定期检查系统健康状态并提供聚合信息
 */
@Slf4j
@Service
public class HealthMonitoringService {

    private final HealthEndpoint healthEndpoint;
    private final CustomMetricsService customMetricsService;
    private final Map<String, HealthStatus> healthHistory = new ConcurrentHashMap<>();
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    public HealthMonitoringService(HealthEndpoint healthEndpoint, 
                                 CustomMetricsService customMetricsService) {
        this.healthEndpoint = healthEndpoint;
        this.customMetricsService = customMetricsService;
    }

    /**
     * 定期健康检查（每30秒执行一次）
     */
    @Scheduled(fixedRate = 30000)
    public void performHealthCheck() {
        try {
            log.debug("开始执行定期健康检查");
            
            HealthComponent healthResult = healthEndpoint.health();
            Status overallStatus = healthResult.getStatus();
            
            // 记录整体健康状态指标
            recordOverallHealthMetrics(overallStatus);
            
            // 检查各个组件的健康状态
            if (healthResult instanceof Health) {
                Health health = (Health) healthResult;
                Map<String, Object> details = health.getDetails();
                if (details != null) {
                    for (Map.Entry<String, Object> entry : details.entrySet()) {
                        String componentName = entry.getKey();
                        Object componentHealth = entry.getValue();
                        
                        processComponentHealth(componentName, componentHealth);
                    }
                }
            }
            
            // 清理过期的健康历史记录
            cleanupHealthHistory();
            
            log.debug("定期健康检查完成，整体状态: {}", overallStatus);
            
        } catch (Exception e) {
            ErrorHandlingUtils.logHealthCheckError("定期健康检查", e);
            customMetricsService.createCounter(
                "monitor.health.check.errors",
                "健康检查错误统计"
            ).increment();
        }
    }

    /**
     * 处理组件健康状态
     */
    private void processComponentHealth(String componentName, Object componentHealth) {
        try {
            if (componentHealth instanceof HealthComponent) {
                HealthComponent healthComponent = (HealthComponent) componentHealth;
                Status status = healthComponent.getStatus();
                
                // 记录组件健康状态
                recordComponentHealthMetrics(componentName, status);
                
                // 更新健康历史
                updateHealthHistory(componentName, status);
                
                // 检查连续失败
                checkConsecutiveFailures(componentName, status);
                
                // 记录详细信息
                if (healthComponent instanceof Health) {
                    Health health = (Health) healthComponent;
                    Map<String, Object> details = health.getDetails();
                    if (details != null) {
                        processHealthDetails(componentName, details);
                    }
                }
            } else if (componentHealth instanceof Map<?, ?> healthMap) {
                // 兼容旧格式
                Object statusObj = healthMap.get("status");
                if (statusObj instanceof String statusStr) {
                    Status status = new Status(statusStr);
                    
                    // 记录组件健康状态
                    recordComponentHealthMetrics(componentName, status);
                    
                    // 更新健康历史
                    updateHealthHistory(componentName, status);
                    
                    // 检查连续失败
                    checkConsecutiveFailures(componentName, status);
                    
                    // 记录详细信息
                    Object details = healthMap.get("details");
                    if (details instanceof Map<?, ?> detailsMap) {
                        processHealthDetails(componentName, detailsMap);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("处理组件健康状态异常: {}", componentName, e);
        }
    }

    /**
     * 记录整体健康状态指标
     */
    private void recordOverallHealthMetrics(Status status) {
        customMetricsService.createCounter(
            "monitor.health.overall.status",
            "整体健康状态统计",
            "status", status.getCode()
        ).increment();
        
        // 记录健康状态为数值指标（便于监控和告警）
        double healthValue = switch (status.getCode()) {
            case "UP" -> 1.0;
            case "DOWN" -> 0.0;
            case "OUT_OF_SERVICE" -> -1.0;
            default -> 0.5; // UNKNOWN等状态
        };
        
        customMetricsService.createGauge(
            "monitor.health.overall.value",
            "整体健康状态数值",
            this,
            () -> healthValue
        );
    }

    /**
     * 记录组件健康状态指标
     */
    private void recordComponentHealthMetrics(String componentName, Status status) {
        customMetricsService.createCounter(
            "monitor.health.component.status",
            "组件健康状态统计",
            "component", componentName,
            "status", status.getCode()
        ).increment();
        
        // 记录组件健康状态为数值指标
        double healthValue = switch (status.getCode()) {
            case "UP" -> 1.0;
            case "DOWN" -> 0.0;
            case "OUT_OF_SERVICE" -> -1.0;
            default -> 0.5;
        };
        
        customMetricsService.createGauge(
            "monitor.health.component.value",
            "组件健康状态数值",
            this,
            () -> healthValue,
            "component", componentName
        );
    }

    /**
     * 更新健康历史记录（增强版）
     */
    private void updateHealthHistory(String componentName, Status status) {
        HealthStatus previousStatus = healthHistory.get(componentName);
        HealthStatus newStatus = new HealthStatus(status, Instant.now());
        healthHistory.put(componentName, newStatus);
        
        // 检查状态变化并触发告警
        if (previousStatus != null) {
            checkAndTriggerAlerts(componentName, status, previousStatus.status);
        }
    }

    /**
     * 检查连续失败次数
     */
    private void checkConsecutiveFailures(String componentName, Status status) {
        if (status == Status.DOWN || status == Status.OUT_OF_SERVICE) {
            int failures = consecutiveFailures.getOrDefault(componentName, 0) + 1;
            consecutiveFailures.put(componentName, failures);
            
            if (failures >= MAX_CONSECUTIVE_FAILURES) {
                log.error("组件 {} 连续失败 {} 次，需要关注", componentName, failures);
                
                customMetricsService.createCounter(
                    "monitor.health.component.consecutive.failures",
                    "组件连续失败统计",
                    "component", componentName,
                    "failure_count", String.valueOf(failures)
                ).increment();
            }
        } else {
            // 健康状态恢复，重置失败计数
            consecutiveFailures.remove(componentName);
        }
    }

    /**
     * 处理健康检查详细信息
     */
    private void processHealthDetails(String componentName, Map<?, ?> details) {
        try {
            // 处理响应时间信息
            Object responseTimeObj = details.get("responseTime");
            if (responseTimeObj instanceof String responseTimeStr) {
                try {
                    // 解析响应时间（格式如 "123ms"）
                    String timeValue = responseTimeStr.replace("ms", "");
                    double responseTime = Double.parseDouble(timeValue);
                    
                    customMetricsService.createGauge(
                        "monitor.health.component.response.time",
                        "组件响应时间",
                        this,
                        () -> responseTime,
                        "component", componentName
                    );
                } catch (NumberFormatException e) {
                    log.debug("无法解析响应时间: {}", responseTimeStr);
                }
            }
            
            // 处理警告信息
            Object warningObj = details.get("warning");
            if (warningObj instanceof String warning) {
                log.warn("组件 {} 健康检查警告: {}", componentName, warning);
                
                customMetricsService.createCounter(
                    "monitor.health.component.warnings",
                    "组件健康检查警告统计",
                    "component", componentName
                ).increment();
            }
            
        } catch (Exception e) {
            log.debug("处理健康检查详细信息异常: {}", componentName, e);
        }
    }

    /**
     * 清理过期的健康历史记录
     */
    private void cleanupHealthHistory() {
        Instant cutoff = Instant.now().minusSeconds(3600); // 保留1小时内的记录
        
        healthHistory.entrySet().removeIf(entry -> 
            entry.getValue().timestamp.isBefore(cutoff));
    }

    /**
     * 获取健康状态摘要
     */
    public Map<String, Object> getHealthSummary() {
        Map<String, Object> summary = new HashMap<>();
        
        try {
            HealthComponent healthResult = healthEndpoint.health();
            summary.put("overallStatus", healthResult.getStatus().getCode());
            summary.put("timestamp", Instant.now());
            
            Map<String, Object> componentSummary = new HashMap<>();
            
            if (healthResult instanceof Health) {
                Health health = (Health) healthResult;
                Map<String, Object> details = health.getDetails();
                
                if (details != null) {
                    for (Map.Entry<String, Object> entry : details.entrySet()) {
                        String componentName = entry.getKey();
                        Object componentHealth = entry.getValue();
                        
                        Map<String, Object> componentInfo = new HashMap<>();
                        
                        if (componentHealth instanceof HealthComponent) {
                            HealthComponent component = (HealthComponent) componentHealth;
                            componentInfo.put("status", component.getStatus().getCode());
                        } else if (componentHealth instanceof Map<?, ?> healthMap) {
                            Object statusObj = healthMap.get("status");
                            if (statusObj instanceof String statusStr) {
                                componentInfo.put("status", statusStr);
                            }
                        }
                        
                        componentInfo.put("consecutiveFailures", 
                            consecutiveFailures.getOrDefault(componentName, 0));
                        
                        HealthStatus lastStatus = healthHistory.get(componentName);
                        if (lastStatus != null) {
                            componentInfo.put("lastCheck", lastStatus.timestamp);
                        }
                        
                        componentSummary.put(componentName, componentInfo);
                    }
                }
            }
            
            summary.put("components", componentSummary);
            
        } catch (Exception e) {
            log.error("获取健康状态摘要异常", e);
            summary.put("error", e.getMessage());
        }
        
        return summary;
    }

    /**
     * 获取系统性能指标
     */
    public Map<String, Object> getSystemPerformanceMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        
        try {
            // JVM内存使用情况
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            long maxMemory = runtime.maxMemory();
            
            Map<String, Object> jvmMetrics = new HashMap<>();
            jvmMetrics.put("totalMemory", totalMemory);
            jvmMetrics.put("freeMemory", freeMemory);
            jvmMetrics.put("usedMemory", usedMemory);
            jvmMetrics.put("maxMemory", maxMemory);
            jvmMetrics.put("memoryUsagePercent", (double) usedMemory / maxMemory * 100);
            
            metrics.put("jvm", jvmMetrics);
            
            // 线程信息
            ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
            while (rootGroup.getParent() != null) {
                rootGroup = rootGroup.getParent();
            }
            
            Map<String, Object> threadMetrics = new HashMap<>();
            threadMetrics.put("activeThreads", rootGroup.activeCount());
            threadMetrics.put("activeGroups", rootGroup.activeGroupCount());
            
            metrics.put("threads", threadMetrics);
            
            // 系统负载（如果可用）
            try {
                java.lang.management.OperatingSystemMXBean osBean = 
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                
                Map<String, Object> systemMetrics = new HashMap<>();
                systemMetrics.put("availableProcessors", osBean.getAvailableProcessors());
                systemMetrics.put("systemLoadAverage", osBean.getSystemLoadAverage());
                
                // 如果是com.sun.management.OperatingSystemMXBean，获取更多信息
                if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                    com.sun.management.OperatingSystemMXBean sunOsBean = 
                        (com.sun.management.OperatingSystemMXBean) osBean;
                    
                    systemMetrics.put("processCpuLoad", sunOsBean.getProcessCpuLoad());
                    systemMetrics.put("totalMemory", sunOsBean.getTotalMemorySize());
                    systemMetrics.put("freeMemory", sunOsBean.getFreeMemorySize());
                }
                
                metrics.put("system", systemMetrics);
                
            } catch (Exception e) {
                log.debug("无法获取系统负载信息: {}", e.getMessage());
            }
            
            metrics.put("timestamp", Instant.now());
            
        } catch (Exception e) {
            log.error("获取系统性能指标异常", e);
            metrics.put("error", e.getMessage());
        }
        
        return metrics;
    }

    /**
     * 检查并触发健康状态变化告警
     */
    private void checkAndTriggerAlerts(String componentName, Status currentStatus, Status previousStatus) {
        try {
            if (previousStatus != null && !currentStatus.equals(previousStatus)) {
                // 状态发生变化，触发告警
                String alertMessage = String.format("组件 %s 健康状态从 %s 变更为 %s", 
                    componentName, previousStatus.getCode(), currentStatus.getCode());
                
                log.warn("健康状态变化告警: {}", alertMessage);
                
                // 记录状态变化指标
                customMetricsService.createCounter(
                    "monitor.health.status.changes",
                    "健康状态变化统计",
                    "component", componentName,
                    "from_status", previousStatus.getCode(),
                    "to_status", currentStatus.getCode()
                ).increment();
                
                // 如果状态变为DOWN或OUT_OF_SERVICE，发送紧急告警
                if (currentStatus == Status.DOWN || currentStatus == Status.OUT_OF_SERVICE) {
                    sendCriticalAlert(componentName, currentStatus, alertMessage);
                }
                
                // 如果状态从DOWN恢复到UP，发送恢复通知
                if (previousStatus == Status.DOWN && currentStatus == Status.UP) {
                    sendRecoveryNotification(componentName, alertMessage);
                }
            }
        } catch (Exception e) {
            log.error("检查健康状态告警异常", e);
        }
    }

    /**
     * 发送关键告警
     */
    private void sendCriticalAlert(String componentName, Status status, String message) {
        try {
            log.error("关键健康告警: 组件 {} 状态为 {}", componentName, status.getCode());
            
            // 记录关键告警指标
            customMetricsService.createCounter(
                "monitor.health.critical.alerts",
                "关键健康告警统计",
                "component", componentName,
                "status", status.getCode()
            ).increment();
            
            // 这里可以集成实际的告警系统，如邮件、短信、Slack等
            // 暂时记录日志
            log.error("需要立即关注的健康问题: {}", message);
            
        } catch (Exception e) {
            log.error("发送关键告警异常", e);
        }
    }

    /**
     * 发送恢复通知
     */
    private void sendRecoveryNotification(String componentName, String message) {
        try {
            log.info("健康状态恢复通知: 组件 {} 已恢复正常", componentName);
            
            // 记录恢复通知指标
            customMetricsService.createCounter(
                "monitor.health.recovery.notifications",
                "健康恢复通知统计",
                "component", componentName
            ).increment();
            
            // 这里可以发送恢复通知
            log.info("健康状态已恢复: {}", message);
            
        } catch (Exception e) {
            log.error("发送恢复通知异常", e);
        }
    }

    /**
     * 获取健康趋势分析
     */
    public Map<String, Object> getHealthTrends() {
        Map<String, Object> trends = new HashMap<>();
        
        try {
            Map<String, Object> componentTrends = new HashMap<>();
            
            for (Map.Entry<String, HealthStatus> entry : healthHistory.entrySet()) {
                String componentName = entry.getKey();
                HealthStatus currentStatus = entry.getValue();
                
                Map<String, Object> componentTrend = new HashMap<>();
                componentTrend.put("currentStatus", currentStatus.status.getCode());
                componentTrend.put("lastCheck", currentStatus.timestamp);
                componentTrend.put("consecutiveFailures", consecutiveFailures.getOrDefault(componentName, 0));
                
                // 计算稳定性评分（基于连续失败次数）
                int failures = consecutiveFailures.getOrDefault(componentName, 0);
                double stabilityScore = Math.max(0, 100 - (failures * 20)); // 每次失败扣20分
                componentTrend.put("stabilityScore", stabilityScore);
                
                // 健康状态持续时间
                long durationMinutes = java.time.Duration.between(currentStatus.timestamp, Instant.now()).toMinutes();
                componentTrend.put("statusDurationMinutes", durationMinutes);
                
                componentTrends.put(componentName, componentTrend);
            }
            
            trends.put("components", componentTrends);
            trends.put("timestamp", Instant.now());
            
            // 计算整体系统健康评分
            double overallScore = componentTrends.values().stream()
                .mapToDouble(trend -> {
                    if (trend instanceof Map<?, ?> trendMap) {
                        Object score = trendMap.get("stabilityScore");
                        return score instanceof Number ? ((Number) score).doubleValue() : 0;
                    }
                    return 0;
                })
                .average()
                .orElse(0);
            
            trends.put("overallHealthScore", overallScore);
            
        } catch (Exception e) {
            log.error("获取健康趋势分析异常", e);
            trends.put("error", e.getMessage());
        }
        
        return trends;
    }



    /**
     * 健康状态记录
     */
    private record HealthStatus(Status status, Instant timestamp) {}
}