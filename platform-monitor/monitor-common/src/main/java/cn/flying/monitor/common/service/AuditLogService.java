package cn.flying.monitor.common.service;

import cn.flying.monitor.common.entity.AuditLog;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Audit logging service interface
 */
public interface AuditLogService {
    
    /**
     * Log user action with automatic signature generation
     */
    void logAction(Long userId, String action, String resourceType, String resourceId, 
                   Map<String, Object> details, String ipAddress, String userAgent, String correlationId);
    
    /**
     * Log action without user (system actions)
     */
    void logSystemAction(String action, String resourceType, String resourceId, 
                        Map<String, Object> details, String correlationId);
    
    /**
     * Get audit logs for user
     */
    List<AuditLog> getUserAuditLogs(Long userId, int limit);
    
    /**
     * Get audit logs by action
     */
    List<AuditLog> getAuditLogsByAction(String action, int limit);
    
    /**
     * Get audit logs by time range
     */
    List<AuditLog> getAuditLogsByTimeRange(LocalDateTime startTime, LocalDateTime endTime);
    
    /**
     * Get audit logs by correlation ID (for request tracing)
     */
    List<AuditLog> getAuditLogsByCorrelationId(String correlationId);
    
    /**
     * Verify audit log integrity
     */
    boolean verifyLogIntegrity(AuditLog auditLog);
    
    /**
     * Get security statistics
     */
    Map<String, Object> getSecurityStatistics(LocalDateTime startTime, LocalDateTime endTime);
    
    /**
     * Export audit logs for compliance
     */
    List<AuditLog> exportAuditLogs(LocalDateTime startTime, LocalDateTime endTime, String format);
}