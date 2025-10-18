package cn.flying.monitor.common.security;

import cn.flying.monitor.common.entity.SecurityAuditLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * 安全审计服务
 */
@Slf4j
@Service
public class SecurityAuditService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final SecurityAuditLogMapper auditLogMapper;
    private static final String AUDIT_QUEUE_KEY = "security:audit:queue";

    public SecurityAuditService(RedisTemplate<String, Object> redisTemplate, 
                               SecurityAuditLogMapper auditLogMapper) {
        this.redisTemplate = redisTemplate;
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 记录登录事件
     */
    public void logLoginAttempt(String userId, String username, String clientIp, 
                               String userAgent, boolean success, String failureReason) {
        SecurityAuditLog auditLog = SecurityAuditLog.builder()
                .eventType(SecurityAuditLog.EventType.LOGIN_ATTEMPT)
                .userId(userId)
                .username(username)
                .clientIp(clientIp)
                .userAgent(userAgent)
                .success(success)
                .details(success ? "登录成功" : "登录失败: " + failureReason)
                .timestamp(Instant.now())
                .build();

        logAuditEvent(auditLog);
    }

    /**
     * 记录MFA验证事件
     */
    public void logMfaVerification(String userId, String username, String clientIp, 
                                  String method, boolean success) {
        SecurityAuditLog auditLog = SecurityAuditLog.builder()
                .eventType(SecurityAuditLog.EventType.MFA_VERIFICATION)
                .userId(userId)
                .username(username)
                .clientIp(clientIp)
                .success(success)
                .details(String.format("MFA验证 - 方法: %s, 结果: %s", method, success ? "成功" : "失败"))
                .timestamp(Instant.now())
                .build();

        logAuditEvent(auditLog);
    }

    /**
     * 记录权限检查事件
     */
    public void logPermissionCheck(String userId, String username, String resource, 
                                  String action, boolean granted) {
        SecurityAuditLog auditLog = SecurityAuditLog.builder()
                .eventType(SecurityAuditLog.EventType.PERMISSION_CHECK)
                .userId(userId)
                .username(username)
                .resource(resource)
                .action(action)
                .success(granted)
                .details(String.format("权限检查 - 资源: %s, 操作: %s, 结果: %s", 
                        resource, action, granted ? "允许" : "拒绝"))
                .timestamp(Instant.now())
                .build();

        logAuditEvent(auditLog);
    }

    /**
     * 记录证书认证事件
     */
    public void logCertificateAuthentication(String clientId, String certificateSerial, 
                                           String clientIp, boolean success, String reason) {
        SecurityAuditLog auditLog = SecurityAuditLog.builder()
                .eventType(SecurityAuditLog.EventType.CERTIFICATE_AUTH)
                .userId(clientId)
                .clientIp(clientIp)
                .success(success)
                .details(String.format("证书认证 - 序列号: %s, 结果: %s, 原因: %s", 
                        certificateSerial, success ? "成功" : "失败", reason))
                .timestamp(Instant.now())
                .build();

        logAuditEvent(auditLog);
    }

    /**
     * 记录角色变更事件
     */
    public void logRoleChange(String operatorId, String operatorName, String targetUserId, 
                             String targetUsername, String oldRoles, String newRoles) {
        SecurityAuditLog auditLog = SecurityAuditLog.builder()
                .eventType(SecurityAuditLog.EventType.ROLE_CHANGE)
                .userId(operatorId)
                .username(operatorName)
                .resource("user_roles")
                .action("modify")
                .success(true)
                .details(String.format("角色变更 - 目标用户: %s(%s), 原角色: %s, 新角色: %s", 
                        targetUsername, targetUserId, oldRoles, newRoles))
                .timestamp(Instant.now())
                .build();

        logAuditEvent(auditLog);
    }

    /**
     * 记录敏感操作事件
     */
    public void logSensitiveOperation(String userId, String username, String operation, 
                                    String resource, String details, boolean success) {
        SecurityAuditLog auditLog = SecurityAuditLog.builder()
                .eventType(SecurityAuditLog.EventType.SENSITIVE_OPERATION)
                .userId(userId)
                .username(username)
                .resource(resource)
                .action(operation)
                .success(success)
                .details(details)
                .timestamp(Instant.now())
                .build();

        logAuditEvent(auditLog);
    }

    /**
     * 记录数据访问事件
     */
    public void logDataAccess(String userId, String username, String dataType, 
                             String operation, String resourceId, boolean success) {
        SecurityAuditLog auditLog = SecurityAuditLog.builder()
                .eventType(SecurityAuditLog.EventType.DATA_ACCESS)
                .userId(userId)
                .username(username)
                .resource(dataType)
                .action(operation)
                .success(success)
                .details(String.format("数据访问 - 类型: %s, 操作: %s, 资源ID: %s", 
                        dataType, operation, resourceId))
                .timestamp(Instant.now())
                .build();

        logAuditEvent(auditLog);
    }

    /**
     * 记录系统配置变更事件
     */
    public void logConfigurationChange(String userId, String username, String configKey, 
                                     String oldValue, String newValue) {
        SecurityAuditLog auditLog = SecurityAuditLog.builder()
                .eventType(SecurityAuditLog.EventType.CONFIGURATION_CHANGE)
                .userId(userId)
                .username(username)
                .resource("system_config")
                .action("modify")
                .success(true)
                .details(String.format("配置变更 - 键: %s, 原值: %s, 新值: %s", 
                        configKey, oldValue, newValue))
                .timestamp(Instant.now())
                .build();

        logAuditEvent(auditLog);
    }

    /**
     * 异步记录审计事件
     */
    private void logAuditEvent(SecurityAuditLog auditLog) {
        try {
            // 先加入Redis队列，确保不丢失
            redisTemplate.opsForList().leftPush(AUDIT_QUEUE_KEY, auditLog);
            
            // 异步写入数据库
            CompletableFuture.runAsync(() -> {
                try {
                    auditLogMapper.insert(auditLog);
                    // 成功写入数据库后从队列中移除
                    redisTemplate.opsForList().rightPop(AUDIT_QUEUE_KEY);
                } catch (Exception e) {
                    log.error("Failed to persist audit log to database", e);
                    // 数据库写入失败时，日志仍保留在Redis队列中
                }
            });
            
        } catch (Exception e) {
            log.error("Failed to log audit event", e);
            // 即使Redis写入失败，也要记录到应用日志
            log.warn("SECURITY_AUDIT: {}", auditLog);
        }
    }

    /**
     * 安全审计日志Mapper接口
     */
    public interface SecurityAuditLogMapper extends BaseMapper<SecurityAuditLog> {
    }
}