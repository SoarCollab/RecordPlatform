package cn.flying.monitor.notification.dto;

import lombok.Data;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.Map;

/**
 * 告警规则DTO
 */
@Data
public class AlertRuleDTO {

    private Long id;

    @NotBlank(message = "规则名称不能为空")
    @Size(max = 255, message = "规则名称长度不能超过255个字符")
    private String name;

    @Size(max = 1000, message = "规则描述长度不能超过1000个字符")
    private String description;

    @NotBlank(message = "监控指标名称不能为空")
    @Size(max = 100, message = "监控指标名称长度不能超过100个字符")
    private String metricName;

    @NotBlank(message = "条件操作符不能为空")
    @Pattern(regexp = "^(>|<|>=|<=|=|!=)$", message = "条件操作符必须是 >, <, >=, <=, =, != 中的一个")
    private String conditionOperator;

    @NotNull(message = "阈值不能为空")
    @DecimalMin(value = "0.0", message = "阈值不能为负数")
    private BigDecimal thresholdValue;

    @NotBlank(message = "告警级别不能为空")
    @Pattern(regexp = "^(low|medium|high|critical)$", message = "告警级别必须是 low, medium, high, critical 中的一个")
    private String severity;

    @NotNull(message = "启用状态不能为空")
    private Boolean enabled;

    private Map<String, Object> clientFilter;

    private Map<String, Object> notificationChannels;

    private Map<String, Object> escalationRules;

    @Min(value = 30, message = "评估间隔不能少于30秒")
    @Max(value = 3600, message = "评估间隔不能超过3600秒")
    private Integer evaluationInterval = 60;

    @Min(value = 1, message = "连续失败次数要求不能少于1次")
    @Max(value = 10, message = "连续失败次数要求不能超过10次")
    private Integer consecutiveFailuresRequired = 1;

    private Long createdBy;
}