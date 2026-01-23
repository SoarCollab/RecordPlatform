package cn.flying.service.job;

import cn.flying.common.lock.DistributedLock;
import cn.flying.common.util.JsonConverter;
import cn.flying.service.SysAuditService;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.service.sse.SseEvent;
import cn.flying.service.sse.SseEventType;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 审计系统定时任务
 * <p>
 * 包含两个主要任务：
 * 1. 异常检测：每5分钟执行一次，检测高频操作、失败登录、错误率异常
 * 2. 日志备份：每天凌晨3:30执行，备份过期日志并可选删除原数据
 */
@Component
@Slf4j
public class AuditTask {

    @Resource
    private SysAuditService sysAuditService;

    @Resource
    private SseEmitterManager sseEmitterManager;

    /**
     * 日志保留天数，默认180天
     */
    @Value("${audit.backup.retention-days:180}")
    private int logRetentionDays;

    /**
     * 备份后是否删除原数据，默认true
     */
    @Value("${audit.backup.delete-after-backup:true}")
    private boolean deleteAfterBackup;

    /**
     * 是否启用异常检测定时任务，默认true
     */
    @Value("${audit.anomaly-check.enabled:true}")
    private boolean anomalyCheckEnabled;

    /**
     * 是否启用日志备份定时任务，默认true
     */
    @Value("${audit.backup.enabled:true}")
    private boolean backupEnabled;

    /**
     * 异常检测定时任务
     * 每5分钟执行一次，检测系统中的异常操作
     * 检测到异常时通过SSE向所有在线管理员发送告警
     */
    @Scheduled(cron = "${audit.anomaly-check.cron:0 */5 * * * ?}")
    @DistributedLock(key = "audit:anomaly-check", leaseTime = 300)
    public void checkAnomaliesScheduled() {
        if (!anomalyCheckEnabled) {
            log.debug("审计异常检测任务已禁用，跳过执行");
            return;
        }

        log.info("开始执行审计异常检测任务...");

        try {
            // 调用异常检测服务
            Map<String, Object> result = sysAuditService.checkAnomalies();

            Boolean hasAnomalies = (Boolean) result.get("hasAnomalies");
            String anomalyDetails = (String) result.get("anomalyDetails");

            if (Boolean.TRUE.equals(hasAnomalies)) {
                log.warn("检测到审计异常: {}", anomalyDetails);

                // 构建告警事件
                Map<String, Object> alertPayload = new HashMap<>();
                alertPayload.put("type", "anomaly_detected");
                alertPayload.put("message", "系统检测到异常操作");
                alertPayload.put("details", parseAnomalyDetails(anomalyDetails));
                alertPayload.put("checkTime", LocalDateTime.now().toString());
                alertPayload.put("severity", determineSeverity(anomalyDetails));

                SseEvent alertEvent = SseEvent.of(SseEventType.AUDIT_ALERT, alertPayload);

                // 向所有租户的在线管理员发送告警（跨租户全局广播）
                sseEmitterManager.broadcastToAllAdmins(alertEvent);

                log.info("审计异常检测完成，已向管理员发送告警");
            } else {
                log.debug("审计异常检测完成，未发现异常");
            }
        } catch (Exception e) {
            log.error("审计异常检测任务执行失败", e);
        }
    }

    /**
     * 日志备份定时任务
     * 每天凌晨3:30执行，备份超过保留期的日志
     */
    @Scheduled(cron = "${audit.backup.cron:0 30 3 * * ?}")
    @DistributedLock(key = "audit:log-backup", leaseTime = 3600)
    public void backupLogsScheduled() {
        if (!backupEnabled) {
            log.debug("审计日志备份任务已禁用，跳过执行");
            return;
        }

        log.info("开始执行审计日志备份任务，保留天数={}，备份后删除={}", logRetentionDays, deleteAfterBackup);

        try {
            String result = sysAuditService.backupLogs(logRetentionDays, deleteAfterBackup);
            log.info("审计日志备份任务完成: {}", result);
        } catch (Exception e) {
            log.error("审计日志备份任务执行失败", e);
        }
    }

    /**
     * 解析异常详情JSON
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseAnomalyDetails(String detailsJson) {
        if (detailsJson == null || detailsJson.isEmpty()) {
            return new HashMap<>();
        }
        try {
            return JsonConverter.parse(detailsJson, Map.class);
        } catch (Exception e) {
            log.warn("解析异常详情失败: {}", e.getMessage());
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("raw", detailsJson);
            return fallback;
        }
    }

    /**
     * 根据异常详情确定告警级别
     * <p>
     * 从存储过程返回的 thresholds 中获取配置阈值，使用阈值倍数来确定严重级别：
     * - critical: 超过阈值 2 倍以上
     * - warning: 超过阈值
     * - info: 未超过阈值
     *
     * @param detailsJson 异常详情JSON
     * @return 告警级别：critical, warning, info
     */
    @SuppressWarnings("unchecked")
    private String determineSeverity(String detailsJson) {
        try {
            Map<String, Object> details = parseAnomalyDetails(detailsJson);

            int highFreqUsers = getIntValue(details, "highFrequencyUsers");
            int failedLogins = getIntValue(details, "failedLoginUsers");
            double errorRate = getDoubleValue(details, "errorRatePercent");

            // 从返回的 thresholds 中获取配置值
            Map<String, Object> thresholds = (Map<String, Object>) details.getOrDefault("thresholds", Map.of());
            int highFreqThreshold = getIntValue(thresholds, "highFrequency");
            int failedLoginThreshold = getIntValue(thresholds, "failedLogin");
            int errorRateThreshold = getIntValue(thresholds, "errorRate");

            // 使用默认阈值（当存储过程未返回阈值时）
            if (highFreqThreshold <= 0) highFreqThreshold = 3;
            if (failedLoginThreshold <= 0) failedLoginThreshold = 5;
            if (errorRateThreshold <= 0) errorRateThreshold = 10;

            // 判断是否超过阈值的 2 倍（critical 级别）
            boolean isCritical = highFreqUsers > highFreqThreshold * 2
                    || failedLogins > failedLoginThreshold * 2
                    || errorRate > errorRateThreshold * 2;

            if (isCritical) {
                return "critical";
            }

            // 判断是否超过阈值（warning 级别）
            boolean isWarning = highFreqUsers > 0 || failedLogins > 0 || errorRate > errorRateThreshold;

            if (isWarning) {
                return "warning";
            }

            return "info";
        } catch (Exception e) {
            return "warning";
        }
    }

    private int getIntValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    private double getDoubleValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.0;
    }
}
