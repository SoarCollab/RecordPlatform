package cn.flying.monitor.notification.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警实例DTO
 */
@Data
public class AlertInstanceDTO {

    private Long id;

    private Long ruleId;

    private String ruleName;

    private String clientId;

    private String status;

    private String severity;

    private LocalDateTime firstTriggered;

    private LocalDateTime lastTriggered;

    private LocalDateTime resolvedAt;

    private Long acknowledgedBy;

    private String acknowledgedByName;

    private LocalDateTime acknowledgedAt;

    private Boolean notificationSent;

    private Integer escalationLevel;

    private String metricName;

    private String conditionOperator;

    private String thresholdValue;

    private String currentValue;
}