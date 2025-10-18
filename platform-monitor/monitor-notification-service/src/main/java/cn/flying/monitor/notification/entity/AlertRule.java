package cn.flying.monitor.notification.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 告警规则实体
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("alert_rules")
public class AlertRule {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 规则名称
     */
    @TableField("name")
    private String name;

    /**
     * 规则描述
     */
    @TableField("description")
    private String description;

    /**
     * 监控指标名称
     */
    @TableField("metric_name")
    private String metricName;

    /**
     * 条件操作符
     */
    @TableField("condition_operator")
    private String conditionOperator;

    /**
     * 阈值
     */
    @TableField("threshold_value")
    private BigDecimal thresholdValue;

    /**
     * 告警级别
     */
    @TableField("severity")
    private String severity;

    /**
     * 是否启用
     */
    @TableField("enabled")
    private Boolean enabled;

    /**
     * 客户端过滤条件
     */
    @TableField(value = "client_filter", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> clientFilter;

    /**
     * 通知渠道配置
     */
    @TableField(value = "notification_channels", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> notificationChannels;

    /**
     * 升级规则配置
     */
    @TableField(value = "escalation_rules", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> escalationRules;

    /**
     * 评估间隔（秒）
     */
    @TableField("evaluation_interval")
    private Integer evaluationInterval;

    /**
     * 连续失败次数要求
     */
    @TableField("consecutive_failures_required")
    private Integer consecutiveFailuresRequired;

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