package cn.flying.monitor.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Audit log entity for security and compliance tracking
 */
@Data
@TableName("audit_logs")
@AllArgsConstructor
@NoArgsConstructor
public class AuditLog {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    @TableField("user_id")
    private Long userId;
    
    @TableField("action")
    private String action;
    
    @TableField("resource_type")
    private String resourceType;
    
    @TableField("resource_id")
    private String resourceId;
    
    @TableField("details")
    private String details; // JSON string with additional details
    
    @TableField("ip_address")
    private String ipAddress;
    
    @TableField("user_agent")
    private String userAgent;
    
    @TableField("correlation_id")
    private String correlationId;
    
    @TableField("signature")
    private String signature; // Cryptographic signature for tamper protection
    
    @TableField(value = "timestamp", fill = FieldFill.INSERT)
    private LocalDateTime timestamp;
    
    // Audit action constants
    public static final class Actions {
        public static final String LOGIN = "LOGIN";
        public static final String LOGOUT = "LOGOUT";
        public static final String LOGIN_FAILED = "LOGIN_FAILED";
        public static final String MFA_SETUP = "MFA_SETUP";
        public static final String MFA_DISABLE = "MFA_DISABLE";
        public static final String PASSWORD_CHANGE = "PASSWORD_CHANGE";
        public static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";
        public static final String ACCOUNT_UNLOCKED = "ACCOUNT_UNLOCKED";
        public static final String ROLE_ASSIGNED = "ROLE_ASSIGNED";
        public static final String ROLE_REMOVED = "ROLE_REMOVED";
        public static final String PERMISSION_DENIED = "PERMISSION_DENIED";
        public static final String CLIENT_CREATED = "CLIENT_CREATED";
        public static final String CLIENT_UPDATED = "CLIENT_UPDATED";
        public static final String CLIENT_DELETED = "CLIENT_DELETED";
        public static final String ALERT_CREATED = "ALERT_CREATED";
        public static final String ALERT_UPDATED = "ALERT_UPDATED";
        public static final String ALERT_DELETED = "ALERT_DELETED";
        public static final String SSH_SESSION_START = "SSH_SESSION_START";
        public static final String SSH_SESSION_END = "SSH_SESSION_END";
        public static final String DATA_EXPORT = "DATA_EXPORT";
        public static final String CONFIG_CHANGE = "CONFIG_CHANGE";
    }
    
    // Resource type constants
    public static final class ResourceTypes {
        public static final String USER = "USER";
        public static final String ROLE = "ROLE";
        public static final String CLIENT = "CLIENT";
        public static final String ALERT = "ALERT";
        public static final String SSH_SESSION = "SSH_SESSION";
        public static final String SYSTEM = "SYSTEM";
    }
}