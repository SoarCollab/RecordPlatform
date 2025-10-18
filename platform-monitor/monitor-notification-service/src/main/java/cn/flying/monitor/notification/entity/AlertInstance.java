package cn.flying.monitor.notification.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 告警实例实体
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("alert_instances")
public class AlertInstance {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 规则ID
     */
    @TableField("rule_id")
    private Long ruleId;

    /**
     * 客户端ID
     */
    @TableField("client_id")
    private String clientId;

    /**
     * 告警状态
     */
    @TableField("status")
    private String status;

    /**
     * 首次触发时间
     */
    @TableField("first_triggered")
    private LocalDateTime firstTriggered;

    /**
     * 最后触发时间
     */
    @TableField("last_triggered")
    private LocalDateTime lastTriggered;

    /**
     * 解决时间
     */
    @TableField("resolved_at")
    private LocalDateTime resolvedAt;

    /**
     * 确认者ID
     */
    @TableField("acknowledged_by")
    private Long acknowledgedBy;

    /**
     * 确认时间
     */
    @TableField("acknowledged_at")
    private LocalDateTime acknowledgedAt;

    /**
     * 是否已发送通知
     */
    @TableField("notification_sent")
    private Boolean notificationSent;

    /**
     * 升级级别
     */
    @TableField("escalation_level")
    private Integer escalationLevel;

    /**
     * 创建时间
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 告警状态枚举
     */
    public enum Status {
        FIRING("firing"),
        RESOLVED("resolved"),
        ACKNOWLEDGED("acknowledged");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    /**
     * 告警级别枚举
     */
    public enum Severity {
        LOW("low"),
        MEDIUM("medium"),
        HIGH("high"),
        CRITICAL("critical");

        private final String value;

        Severity(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }
}