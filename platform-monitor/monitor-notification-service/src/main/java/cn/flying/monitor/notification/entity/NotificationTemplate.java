package cn.flying.monitor.notification.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 通知模板实体
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("notification_templates")
public class NotificationTemplate {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 模板名称
     */
    @TableField("name")
    private String name;

    /**
     * 通知类型
     */
    @TableField("notification_type")
    private String notificationType;

    /**
     * 告警级别
     */
    @TableField("severity")
    private String severity;

    /**
     * 模板标题
     */
    @TableField("title_template")
    private String titleTemplate;

    /**
     * 模板内容
     */
    @TableField("content_template")
    private String contentTemplate;

    /**
     * 是否启用
     */
    @TableField("enabled")
    private Boolean enabled;

    /**
     * 创建者ID
     */
    @TableField("created_by")
    private Long createdBy;

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
}