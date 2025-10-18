package cn.flying.monitor.notification.service;

import cn.flying.monitor.notification.entity.AlertInstance;
import cn.flying.monitor.notification.entity.AlertRule;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 告警评估服务接口
 */
public interface AlertEvaluationService {

    /**
     * 评估指标数据是否触发告警
     *
     * @param metricName  指标名称
     * @param metricValue 指标值
     * @param clientId    客户端ID
     * @param metadata    元数据
     */
    void evaluateMetric(String metricName, BigDecimal metricValue, String clientId, Map<String, Object> metadata);

    /**
     * 评估单个告警规则
     *
     * @param rule        告警规则
     * @param metricValue 指标值
     * @param clientId    客户端ID
     * @param metadata    元数据
     * @return 是否触发告警
     */
    boolean evaluateRule(AlertRule rule, BigDecimal metricValue, String clientId, Map<String, Object> metadata);

    /**
     * 触发告警
     *
     * @param rule        告警规则
     * @param clientId    客户端ID
     * @param metricValue 指标值
     * @return 告警实例
     */
    AlertInstance triggerAlert(AlertRule rule, String clientId, BigDecimal metricValue);

    /**
     * 解决告警
     *
     * @param ruleId   规则ID
     * @param clientId 客户端ID
     */
    void resolveAlert(Long ruleId, String clientId);

    /**
     * 确认告警
     *
     * @param alertInstanceId 告警实例ID
     * @param acknowledgedBy  确认者ID
     */
    void acknowledgeAlert(Long alertInstanceId, Long acknowledgedBy);

    /**
     * 检查客户端过滤条件
     *
     * @param clientFilter 客户端过滤条件
     * @param clientId     客户端ID
     * @param metadata     元数据
     * @return 是否匹配过滤条件
     */
    boolean matchesClientFilter(Map<String, Object> clientFilter, String clientId, Map<String, Object> metadata);

    /**
     * 处理告警升级
     */
    void processAlertEscalation();
}