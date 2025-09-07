package cn.flying.identity.event;

import cn.flying.identity.config.AuditMonitorConfig;
import cn.flying.identity.dto.AuditLog;
import cn.flying.identity.dto.TokenMonitor;
import cn.flying.identity.mapper.AuditLogMapper;
import cn.flying.identity.mapper.TokenMonitorMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

/**
 * 审计事件处理器
 * 用于异步处理审计日志和Token监控事件
 *
 * @author flying
 * @date 2024
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEventHandler {

    private final AuditLogMapper auditLogMapper;
    private final TokenMonitorMapper tokenMonitorMapper;
    private final AuditMonitorConfig auditMonitorConfig;

    /**
     * 异步处理审计日志事件
     *
     * @param event 审计日志事件
     * @return 处理结果
     */
    @Async
    @EventListener
    public CompletableFuture<Void> handleAuditLogEvent(AuditLogEvent event) {
        try {
            if (!auditMonitorConfig.getAuditLog().isEnabled()) {
                return CompletableFuture.completedFuture(null);
            }

            AuditLog auditLog = event.getAuditLog();
            auditLog.setCreateTime(LocalDateTime.now());

            // 设置风险等级
            auditLog.setRiskLevel(calculateRiskLevel(auditLog));

            // 保存审计日志
            auditLogMapper.insert(auditLog);

            log.debug("审计日志记录成功: {}", auditLog.getId());

            // 检测异常模式
            if (auditMonitorConfig.getAlert().isEnabled()) {
                detectAbnormalPattern(auditLog);
            }

        } catch (Exception e) {
            log.error("处理审计日志事件失败", e);
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * 异步处理Token监控事件
     *
     * @param event Token监控事件
     * @return 处理结果
     */
    @Async
    @EventListener
    public CompletableFuture<Void> handleTokenMonitorEvent(TokenMonitorEvent event) {
        try {
            if (!auditMonitorConfig.getTokenMonitor().isEnabled()) {
                return CompletableFuture.completedFuture(null);
            }

            TokenMonitor tokenMonitor = event.getTokenMonitor();
            tokenMonitor.setCreateTime(LocalDateTime.now());

            // 计算风险评分
            tokenMonitor.setRiskScore(calculateTokenRiskScore(tokenMonitor));

            // 设置风险原因
            tokenMonitor.setRiskReason(generateRiskReason(tokenMonitor));

            // 检测是否异常
            boolean isAbnormal = detectTokenAbnormal(tokenMonitor);
            tokenMonitor.setIsAbnormal(isAbnormal);

            if (isAbnormal) {
                tokenMonitor.setAbnormalType(detectAbnormalType(tokenMonitor));
                tokenMonitor.setHandleStatus(AuditMonitorConfig.Constants.PROCESS_STATUS_PENDING);
            }

            // 保存Token监控记录
            tokenMonitorMapper.insert(tokenMonitor);

            log.debug("Token监控记录成功: {}", tokenMonitor.getId());

            // 实时告警
            if (isAbnormal && auditMonitorConfig.getAlert().isEnabled()) {
                triggerTokenAlert(tokenMonitor);
            }

        } catch (Exception e) {
            log.error("处理Token监控事件失败", e);
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * 计算操作风险等级
     *
     * @param auditLog 审计日志
     * @return 风险等级
     */
    private String calculateRiskLevel(AuditLog auditLog) {
        int riskScore = 0;

        // 根据操作类型评分
        String operationType = auditLog.getOperationType();
        if (AuditMonitorConfig.Constants.OPERATION_DELETE.equals(operationType)) {
            riskScore += 30;
        } else if (AuditMonitorConfig.Constants.OPERATION_UPDATE.equals(operationType)) {
            riskScore += 20;
        } else if (AuditMonitorConfig.Constants.OPERATION_EXPORT.equals(operationType)) {
            riskScore += 25;
        }

        // 根据模块评分
        String module = auditLog.getModule();
        String[] highRiskModules = auditMonitorConfig.getAuditLog().getHighRiskModules();
        for (String highRiskModule : highRiskModules) {
            if (highRiskModule.equals(module)) {
                riskScore += 20;
                break;
            }
        }

        // 根据操作结果评分
        if (!auditLog.getIsSuccess()) {
            riskScore += 15;
        }

        // 根据时间评分（非工作时间）
        if (isNonWorkingHours()) {
            riskScore += 10;
        }

        // 返回风险等级
        if (riskScore >= 60) {
            return AuditMonitorConfig.Constants.RISK_LEVEL_CRITICAL;
        } else if (riskScore >= 40) {
            return AuditMonitorConfig.Constants.RISK_LEVEL_HIGH;
        } else if (riskScore >= 20) {
            return AuditMonitorConfig.Constants.RISK_LEVEL_MEDIUM;
        } else {
            return AuditMonitorConfig.Constants.RISK_LEVEL_LOW;
        }
    }

    /**
     * 计算Token风险评分
     *
     * @param tokenMonitor Token监控记录
     * @return 风险评分
     */
    private Integer calculateTokenRiskScore(TokenMonitor tokenMonitor) {
        int riskScore = 0;

        // 根据事件类型评分
        String eventType = tokenMonitor.getEventType();
        if (AuditMonitorConfig.Constants.TOKEN_EVENT_ABNORMAL.equals(eventType)) {
            riskScore += 40;
        } else if (AuditMonitorConfig.Constants.TOKEN_EVENT_REVOKED.equals(eventType)) {
            riskScore += 20;
        }

        // 根据地理位置评分
        if (isUnusualLocation(tokenMonitor.getLocation())) {
            riskScore += 25;
        }

        // 根据设备指纹评分
        if (isUnusualDevice(tokenMonitor.getDeviceFingerprint())) {
            riskScore += 20;
        }

        // 根据时间评分
        if (isNonWorkingHours()) {
            riskScore += 15;
        }

        // 根据IP评分
        if (isSuspiciousIp(tokenMonitor.getClientIp())) {
            riskScore += 30;
        }

        return Math.min(riskScore, 100); // 最大100分
    }

    /**
     * 生成风险原因
     *
     * @param tokenMonitor Token监控记录
     * @return 风险原因
     */
    private String generateRiskReason(TokenMonitor tokenMonitor) {
        StringBuilder reasons = new StringBuilder();

        if (AuditMonitorConfig.Constants.TOKEN_EVENT_ABNORMAL.equals(tokenMonitor.getEventType())) {
            reasons.append("异常事件类型;");
        }

        if (isUnusualLocation(tokenMonitor.getLocation())) {
            reasons.append("异常地理位置;");
        }

        if (isUnusualDevice(tokenMonitor.getDeviceFingerprint())) {
            reasons.append("异常设备指纹;");
        }

        if (isNonWorkingHours()) {
            reasons.append("非工作时间;");
        }

        if (isSuspiciousIp(tokenMonitor.getClientIp())) {
            reasons.append("可疑IP地址;");
        }

        return !reasons.isEmpty() ? reasons.toString() : "正常使用";
    }

    /**
     * 检测Token是否异常
     *
     * @param tokenMonitor Token监控记录
     * @return 是否异常
     */
    private boolean detectTokenAbnormal(TokenMonitor tokenMonitor) {
        return tokenMonitor.getRiskScore() >= auditMonitorConfig.getTokenMonitor().getRiskScoreThreshold();
    }

    /**
     * 检测异常类型
     *
     * @param tokenMonitor Token监控记录
     * @return 异常类型
     */
    private String detectAbnormalType(TokenMonitor tokenMonitor) {
        if (isSuspiciousIp(tokenMonitor.getClientIp())) {
            return AuditMonitorConfig.Constants.ABNORMAL_TYPE_SUSPICIOUS_IP;
        }

        if (isUnusualLocation(tokenMonitor.getLocation())) {
            return AuditMonitorConfig.Constants.ABNORMAL_TYPE_UNUSUAL_LOCATION;
        }

        if (isUnusualDevice(tokenMonitor.getDeviceFingerprint())) {
            return AuditMonitorConfig.Constants.ABNORMAL_TYPE_DEVICE_MISMATCH;
        }

        if (isNonWorkingHours()) {
            return AuditMonitorConfig.Constants.ABNORMAL_TYPE_TIME_ANOMALY;
        }

        return AuditMonitorConfig.Constants.ABNORMAL_TYPE_HIGH_FREQUENCY;
    }

    /**
     * 检测异常模式
     *
     * @param auditLog 审计日志
     */
    private void detectAbnormalPattern(AuditLog auditLog) {
        // 检测同一IP的失败次数
        LocalDateTime startTime = LocalDateTime.now().minusMinutes(auditMonitorConfig.getAuditLog().getAbnormalDetection().getTimeWindow());
        LocalDateTime endTime = LocalDateTime.now();
        Long failureCount = auditLogMapper.countFailuresByIp(
                auditLog.getClientIp(),
                startTime,
                endTime
        );

        if (failureCount >= auditMonitorConfig.getAuditLog().getAbnormalDetection().getMaxFailuresPerIp()) {
            log.warn("检测到异常IP: {}, 失败次数: {}", auditLog.getClientIp(), failureCount);
            // 触发告警
            triggerAuditAlert(auditLog, "IP异常", "IP " + auditLog.getClientIp() + " 在短时间内失败次数过多");
        }
    }

    /**
     * 触发审计告警
     *
     * @param auditLog     审计日志
     * @param alertType    告警类型
     * @param alertMessage 告警消息
     */
    private void triggerAuditAlert(AuditLog auditLog, String alertType, String alertMessage) {
        log.warn("审计告警 - 类型: {}, 消息: {}, 日志ID: {}", alertType, alertMessage, auditLog.getId());
        // 这里可以集成邮件、短信等告警通知
    }

    /**
     * 触发Token告警
     *
     * @param tokenMonitor Token监控记录
     */
    private void triggerTokenAlert(TokenMonitor tokenMonitor) {
        log.warn("Token异常告警 - Token: {}, 风险评分: {}, 异常类型: {}",
                tokenMonitor.getTokenId(), tokenMonitor.getRiskScore(), tokenMonitor.getAbnormalType());
        // 这里可以集成邮件、短信等告警通知
    }

    /**
     * 判断是否为非工作时间
     *
     * @return 是否为非工作时间
     */
    private boolean isNonWorkingHours() {
        // 简单实现，实际可以根据配置的工作时间判断
        LocalDateTime now = LocalDateTime.now();
        int hour = now.getHour();
        return hour < 9 || hour > 18;
    }

    /**
     * 判断是否为异常地理位置
     *
     * @param location 地理位置
     * @return 是否异常
     */
    private boolean isUnusualLocation(String location) {
        // 简单实现，实际可以根据用户历史位置判断
        return location != null && (location.contains("海外") || location.contains("境外"));
    }

    /**
     * 判断是否为异常设备
     *
     * @param deviceFingerprint 设备指纹
     * @return 是否异常
     */
    private boolean isUnusualDevice(String deviceFingerprint) {
        // 简单实现，实际可以根据用户历史设备判断
        return deviceFingerprint != null && deviceFingerprint.contains("unknown");
    }

    /**
     * 判断是否为可疑IP
     *
     * @param ip IP地址
     * @return 是否可疑
     */
    private boolean isSuspiciousIp(String ip) {
        // 简单实现，实际可以集成IP黑名单库
        return ip != null && (!ip.startsWith("192.168.") && !ip.startsWith("10."));
    }
}