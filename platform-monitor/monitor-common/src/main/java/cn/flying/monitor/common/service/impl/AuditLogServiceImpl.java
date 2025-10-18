package cn.flying.monitor.common.service.impl;

import cn.flying.monitor.common.entity.AuditLog;
import cn.flying.monitor.common.mapper.AuditLogMapper;
import cn.flying.monitor.common.service.AuditLogService;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Audit logging service implementation with integrity protection
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {
    
    private final AuditLogMapper auditLogMapper;
    
    @Value("${monitor.audit.signing-key:default-audit-key-change-in-production}")
    private String signingKey;
    
    @Override
    public void logAction(Long userId, String action, String resourceType, String resourceId,
                         Map<String, Object> details, String ipAddress, String userAgent, String correlationId) {
        
        AuditLog auditLog = new AuditLog();
        auditLog.setUserId(userId);
        auditLog.setAction(action);
        auditLog.setResourceType(resourceType);
        auditLog.setResourceId(resourceId);
        auditLog.setDetails(maskSensitiveData(details));
        auditLog.setIpAddress(ipAddress);
        auditLog.setUserAgent(userAgent);
        auditLog.setCorrelationId(correlationId);
        auditLog.setTimestamp(LocalDateTime.now());
        
        // Generate cryptographic signature for tamper protection
        auditLog.setSignature(calculateSignature(auditLog));
        
        try {
            auditLogMapper.insert(auditLog);
            log.debug("Audit log created: {} by user {} on {}", action, userId, resourceType);
        } catch (Exception e) {
            log.error("Failed to create audit log", e);
        }
    }
    
    @Override
    public void logSystemAction(String action, String resourceType, String resourceId,
                               Map<String, Object> details, String correlationId) {
        logAction(null, action, resourceType, resourceId, details, "system", "system", correlationId);
    }
    
    @Override
    public List<AuditLog> getUserAuditLogs(Long userId, int limit) {
        return auditLogMapper.findByUserId(userId, limit);
    }
    
    @Override
    public List<AuditLog> getAuditLogsByAction(String action, int limit) {
        return auditLogMapper.findByAction(action, limit);
    }
    
    @Override
    public List<AuditLog> getAuditLogsByTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        return auditLogMapper.findByTimeRange(startTime, endTime);
    }
    
    @Override
    public List<AuditLog> getAuditLogsByCorrelationId(String correlationId) {
        return auditLogMapper.findByCorrelationId(correlationId);
    }
    
    @Override
    public boolean verifyLogIntegrity(AuditLog auditLog) {
        String expectedSignature = calculateSignature(auditLog);
        return MessageDigest.isEqual(
            expectedSignature.getBytes(StandardCharsets.UTF_8),
            auditLog.getSignature().getBytes(StandardCharsets.UTF_8)
        );
    }
    
    @Override
    public Map<String, Object> getSecurityStatistics(LocalDateTime startTime, LocalDateTime endTime) {
        Map<String, Object> stats = new HashMap<>();
        
        // Count different types of security events
        stats.put("loginAttempts", auditLogMapper.countByActionInTimeRange(AuditLog.Actions.LOGIN, startTime, endTime));
        stats.put("failedLogins", auditLogMapper.countByActionInTimeRange(AuditLog.Actions.LOGIN_FAILED, startTime, endTime));
        stats.put("accountLocks", auditLogMapper.countByActionInTimeRange(AuditLog.Actions.ACCOUNT_LOCKED, startTime, endTime));
        stats.put("permissionDenials", auditLogMapper.countByActionInTimeRange(AuditLog.Actions.PERMISSION_DENIED, startTime, endTime));
        stats.put("mfaSetups", auditLogMapper.countByActionInTimeRange(AuditLog.Actions.MFA_SETUP, startTime, endTime));
        stats.put("passwordChanges", auditLogMapper.countByActionInTimeRange(AuditLog.Actions.PASSWORD_CHANGE, startTime, endTime));
        
        return stats;
    }
    
    @Override
    public List<AuditLog> exportAuditLogs(LocalDateTime startTime, LocalDateTime endTime, String format) {
        List<AuditLog> logs = getAuditLogsByTimeRange(startTime, endTime);
        
        // Verify integrity of exported logs
        long integrityFailures = logs.stream()
            .mapToLong(log -> verifyLogIntegrity(log) ? 0 : 1)
            .sum();
        
        if (integrityFailures > 0) {
            log.warn("Found {} audit logs with integrity failures during export", integrityFailures);
        }
        
        log.info("Exported {} audit logs from {} to {}", logs.size(), startTime, endTime);
        return logs;
    }
    
    /**
     * Calculate HMAC signature for audit log integrity
     */
    private String calculateSignature(AuditLog auditLog) {
        try {
            String payload = buildSignaturePayload(auditLog);
            
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            
            byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature);
            
        } catch (Exception e) {
            log.error("Failed to calculate audit log signature", e);
            return "SIGNATURE_ERROR";
        }
    }
    
    /**
     * Build payload for signature calculation
     */
    private String buildSignaturePayload(AuditLog auditLog) {
        return String.join("|",
            String.valueOf(auditLog.getUserId()),
            auditLog.getAction(),
            auditLog.getResourceType() != null ? auditLog.getResourceType() : "",
            auditLog.getResourceId() != null ? auditLog.getResourceId() : "",
            auditLog.getDetails() != null ? auditLog.getDetails() : "",
            auditLog.getIpAddress() != null ? auditLog.getIpAddress() : "",
            auditLog.getCorrelationId() != null ? auditLog.getCorrelationId() : "",
            auditLog.getTimestamp() != null ? auditLog.getTimestamp().toString() : ""
        );
    }
    
    /**
     * Mask sensitive data in audit log details
     */
    private String maskSensitiveData(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return "{}";
        }
        
        Map<String, Object> maskedDetails = new HashMap<>(details);
        
        // Mask sensitive fields
        maskedDetails.computeIfPresent("password", (k, v) -> "***MASKED***");
        maskedDetails.computeIfPresent("oldPassword", (k, v) -> "***MASKED***");
        maskedDetails.computeIfPresent("newPassword", (k, v) -> "***MASKED***");
        maskedDetails.computeIfPresent("mfaSecret", (k, v) -> "***MASKED***");
        maskedDetails.computeIfPresent("backupCodes", (k, v) -> "***MASKED***");
        maskedDetails.computeIfPresent("token", (k, v) -> "***MASKED***");
        maskedDetails.computeIfPresent("refreshToken", (k, v) -> "***MASKED***");
        
        return JSON.toJSONString(maskedDetails);
    }
}