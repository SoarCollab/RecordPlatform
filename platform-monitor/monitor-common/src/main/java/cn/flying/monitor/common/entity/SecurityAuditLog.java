package cn.flying.monitor.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;

/**
 * 安全审计日志实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("security_audit_logs")
public class SecurityAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 事件类型
     */
    @TableField("event_type")
    private EventType eventType;

    /**
     * 用户ID
     */
    @TableField("user_id")
    private String userId;

    /**
     * 用户名
     */
    @TableField("username")
    private String username;

    /**
     * 客户端IP地址
     */
    @TableField("client_ip")
    private String clientIp;

    /**
     * 用户代理
     */
    @TableField("user_agent")
    private String userAgent;

    /**
     * 资源
     */
    @TableField("resource")
    private String resource;

    /**
     * 操作
     */
    @TableField("action")
    private String action;

    /**
     * 操作是否成功
     */
    @TableField("success")
    private Boolean success;

    /**
     * 详细信息
     */
    @TableField("details")
    private String details;

    /**
     * 风险级别
     */
    @TableField("risk_level")
    private RiskLevel riskLevel;

    /**
     * 事件时间戳
     */
    @TableField("timestamp")
    private Instant timestamp;

    /**
     * 会话ID
     */
    @TableField("session_id")
    private String sessionId;

    /**
     * 请求ID
     */
    @TableField("request_id")
    private String requestId;

    /**
     * 创建时间
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    /**
     * 事件类型枚举
     */
    public enum EventType {
        LOGIN_ATTEMPT("LOGIN_ATTEMPT", "登录尝试"),
        LOGOUT("LOGOUT", "登出"),
        MFA_VERIFICATION("MFA_VERIFICATION", "多因子认证验证"),
        PERMISSION_CHECK("PERMISSION_CHECK", "权限检查"),
        CERTIFICATE_AUTH("CERTIFICATE_AUTH", "证书认证"),
        ROLE_CHANGE("ROLE_CHANGE", "角色变更"),
        SENSITIVE_OPERATION("SENSITIVE_OPERATION", "敏感操作"),
        DATA_ACCESS("DATA_ACCESS", "数据访问"),
        CONFIGURATION_CHANGE("CONFIGURATION_CHANGE", "配置变更"),
        PASSWORD_CHANGE("PASSWORD_CHANGE", "密码变更"),
        ACCOUNT_LOCK("ACCOUNT_LOCK", "账户锁定"),
        ACCOUNT_UNLOCK("ACCOUNT_UNLOCK", "账户解锁"),
        SECURITY_VIOLATION("SECURITY_VIOLATION", "安全违规"),
        API_ACCESS("API_ACCESS", "API访问"),
        FILE_ACCESS("FILE_ACCESS", "文件访问"),
        SYSTEM_ACCESS("SYSTEM_ACCESS", "系统访问");

        private final String code;
        private final String description;

        EventType(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String getCode() {
            return code;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * 风险级别枚举
     */
    public enum RiskLevel {
        LOW("LOW", "低风险"),
        MEDIUM("MEDIUM", "中风险"),
        HIGH("HIGH", "高风险"),
        CRITICAL("CRITICAL", "严重风险");

        private final String code;
        private final String description;

        RiskLevel(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String getCode() {
            return code;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * 根据事件类型和成功状态自动设置风险级别
     */
    public void setRiskLevelByEvent() {
        if (this.eventType == null) {
            this.riskLevel = RiskLevel.LOW;
            return;
        }

        switch (this.eventType) {
            case LOGIN_ATTEMPT:
                this.riskLevel = success ? RiskLevel.LOW : RiskLevel.MEDIUM;
                break;
            case MFA_VERIFICATION:
                this.riskLevel = success ? RiskLevel.LOW : RiskLevel.HIGH;
                break;
            case CERTIFICATE_AUTH:
                this.riskLevel = success ? RiskLevel.LOW : RiskLevel.HIGH;
                break;
            case ROLE_CHANGE:
            case SENSITIVE_OPERATION:
            case CONFIGURATION_CHANGE:
                this.riskLevel = RiskLevel.HIGH;
                break;
            case SECURITY_VIOLATION:
                this.riskLevel = RiskLevel.CRITICAL;
                break;
            case PERMISSION_CHECK:
                this.riskLevel = success ? RiskLevel.LOW : RiskLevel.MEDIUM;
                break;
            default:
                this.riskLevel = RiskLevel.LOW;
        }
    }
}