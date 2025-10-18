package cn.flying.monitor.notification.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 通知历史实体
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("notification_history")
public class NotificationHistory {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 告警实例ID
     */
    @TableField("alert_instance_id")
    private Long alertInstanceId;

    /**
     * 通知类型
     */
    @TableField("notification_type")
    private String notificationType;

    /**
     * 接收者
     */
    @TableField("recipient")
    private String recipient;

    /**
     * 通知标题
     */
    @TableField("title")
    private String title;

    /**
     * 通知内容
     */
    @TableField("content")
    private String content;

    /**
     * 发送状态
     */
    @TableField("status")
    private String status;

    /**
     * 重试次数
     */
    @TableField("retry_count")
    private Integer retryCount;

    /**
     * 错误信息
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * 发送时间
     */
    @TableField("sent_at")
    private LocalDateTime sentAt;

    /**
     * 下次重试时间
     */
    @TableField("next_retry_at")
    private LocalDateTime nextRetryAt;

    /**
     * 通知配置
     */
    @TableField(value = "notification_config", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> notificationConfig;

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
     * 通知类型枚举
     */
    public enum NotificationType {
        EMAIL("email"),
        SMS("sms"),
        WEBHOOK("webhook"),
        SLACK("slack"),
        DINGTALK("dingtalk");

        private final String value;

        NotificationType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    /**
     * 发送状态枚举
     */
    public enum Status {
        PENDING("pending"),
        SENT("sent"),
        FAILED("failed"),
        RETRY("retry");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }
}