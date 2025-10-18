package cn.flying.monitor.notification.service.impl;

import cn.flying.monitor.notification.entity.AlertInstance;
import cn.flying.monitor.notification.entity.AlertRule;
import cn.flying.monitor.notification.mapper.AlertInstanceMapper;
import cn.flying.monitor.notification.service.AlertEvaluationService;
import cn.flying.monitor.notification.service.AlertRuleService;
import cn.flying.monitor.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 告警评估服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertEvaluationServiceImpl implements AlertEvaluationService {

    private final AlertRuleService alertRuleService;
    private final AlertInstanceMapper alertInstanceMapper;
    private final NotificationService notificationService;

    @Override
    public void evaluateMetric(String metricName, BigDecimal metricValue, String clientId, Map<String, Object> metadata) {
        log.debug("Evaluating metric: {} = {} for client: {}", metricName, metricValue, clientId);
        
        List<AlertRule> rules = alertRuleService.getAlertRulesByMetricName(metricName);
        
        for (AlertRule rule : rules) {
            if (rule.getEnabled()) {
                boolean shouldAlert = evaluateRule(rule, metricValue, clientId, metadata);
                
                if (shouldAlert) {
                    // 检查是否已有活跃的告警实例
                    AlertInstance existingInstance = alertInstanceMapper.selectByRuleAndClient(rule.getId(), clientId);
                    
                    if (existingInstance == null) {
                        // 创建新的告警实例
                        AlertInstance alertInstance = triggerAlert(rule, clientId, metricValue);
                        log.info("Triggered new alert: rule={}, client={}, value={}", rule.getName(), clientId, metricValue);
                        
                        // 发送通知
                        notificationService.sendAlertNotification(alertInstance, rule);
                    } else {
                        // 更新现有告警实例
                        existingInstance.setLastTriggered(LocalDateTime.now());
                        alertInstanceMapper.updateById(existingInstance);
                        log.debug("Updated existing alert instance: {}", existingInstance.getId());
                    }
                } else {
                    // 检查是否需要解决现有告警
                    AlertInstance existingInstance = alertInstanceMapper.selectByRuleAndClient(rule.getId(), clientId);
                    if (existingInstance != null && AlertInstance.Status.FIRING.getValue().equals(existingInstance.getStatus())) {
                        resolveAlert(rule.getId(), clientId);
                        log.info("Resolved alert: rule={}, client={}", rule.getName(), clientId);
                    }
                }
            }
        }
    }

    @Override
    public boolean evaluateRule(AlertRule rule, BigDecimal metricValue, String clientId, Map<String, Object> metadata) {
        // 检查客户端过滤条件
        if (rule.getClientFilter() != null && !rule.getClientFilter().isEmpty()) {
            if (!matchesClientFilter(rule.getClientFilter(), clientId, metadata)) {
                return false;
            }
        }
        
        // 评估阈值条件
        BigDecimal threshold = rule.getThresholdValue();
        String operator = rule.getConditionOperator();
        
        return switch (operator) {
            case ">" -> metricValue.compareTo(threshold) > 0;
            case "<" -> metricValue.compareTo(threshold) < 0;
            case ">=" -> metricValue.compareTo(threshold) >= 0;
            case "<=" -> metricValue.compareTo(threshold) <= 0;
            case "=" -> metricValue.compareTo(threshold) == 0;
            case "!=" -> metricValue.compareTo(threshold) != 0;
            default -> {
                log.warn("Unknown operator: {}", operator);
                yield false;
            }
        };
    }

    @Override
    @Transactional
    public AlertInstance triggerAlert(AlertRule rule, String clientId, BigDecimal metricValue) {
        AlertInstance alertInstance = new AlertInstance();
        alertInstance.setRuleId(rule.getId());
        alertInstance.setClientId(clientId);
        alertInstance.setStatus(AlertInstance.Status.FIRING.getValue());
        alertInstance.setFirstTriggered(LocalDateTime.now());
        alertInstance.setLastTriggered(LocalDateTime.now());
        alertInstance.setNotificationSent(false);
        alertInstance.setEscalationLevel(0);
        
        alertInstanceMapper.insert(alertInstance);
        return alertInstance;
    }

    @Override
    @Transactional
    public void resolveAlert(Long ruleId, String clientId) {
        AlertInstance alertInstance = alertInstanceMapper.selectByRuleAndClient(ruleId, clientId);
        if (alertInstance != null && AlertInstance.Status.FIRING.getValue().equals(alertInstance.getStatus())) {
            alertInstanceMapper.resolveAlert(alertInstance.getId(), LocalDateTime.now());
            log.info("Resolved alert instance: {}", alertInstance.getId());
        }
    }

    @Override
    @Transactional
    public void acknowledgeAlert(Long alertInstanceId, Long acknowledgedBy) {
        AlertInstance alertInstance = alertInstanceMapper.selectById(alertInstanceId);
        if (alertInstance == null) {
            throw new IllegalArgumentException("Alert instance not found: " + alertInstanceId);
        }
        
        if (!AlertInstance.Status.FIRING.getValue().equals(alertInstance.getStatus())) {
            throw new IllegalStateException("Can only acknowledge firing alerts");
        }
        
        alertInstanceMapper.acknowledgeAlert(alertInstanceId, acknowledgedBy, LocalDateTime.now());
        log.info("Acknowledged alert instance: {} by user: {}", alertInstanceId, acknowledgedBy);
    }

    @Override
    public boolean matchesClientFilter(Map<String, Object> clientFilter, String clientId, Map<String, Object> metadata) {
        if (clientFilter == null || clientFilter.isEmpty()) {
            return true;
        }
        
        // 检查客户端ID过滤
        if (clientFilter.containsKey("client_ids")) {
            @SuppressWarnings("unchecked")
            List<String> allowedClientIds = (List<String>) clientFilter.get("client_ids");
            if (!allowedClientIds.contains(clientId)) {
                return false;
            }
        }
        
        // 检查环境过滤
        if (clientFilter.containsKey("environment") && metadata.containsKey("environment")) {
            String requiredEnv = (String) clientFilter.get("environment");
            String clientEnv = (String) metadata.get("environment");
            if (!requiredEnv.equals(clientEnv)) {
                return false;
            }
        }
        
        // 检查区域过滤
        if (clientFilter.containsKey("region") && metadata.containsKey("region")) {
            String requiredRegion = (String) clientFilter.get("region");
            String clientRegion = (String) metadata.get("region");
            if (!requiredRegion.equals(clientRegion)) {
                return false;
            }
        }
        
        return true;
    }

    @Override
    public void processAlertEscalation() {
        log.debug("Processing alert escalation");
        
        // 查询需要升级的告警（超过30分钟未确认的告警）
        LocalDateTime escalationThreshold = LocalDateTime.now().minusMinutes(30);
        List<AlertInstance> alertsForEscalation = alertInstanceMapper.selectForEscalation(escalationThreshold);
        
        for (AlertInstance alertInstance : alertsForEscalation) {
            AlertRule rule = alertRuleService.getAlertRuleById(alertInstance.getRuleId());
            if (rule != null && rule.getEscalationRules() != null) {
                // 增加升级级别
                alertInstance.setEscalationLevel(alertInstance.getEscalationLevel() + 1);
                alertInstanceMapper.updateById(alertInstance);
                
                // 发送升级通知
                notificationService.sendEscalationNotification(alertInstance, rule);
                log.info("Escalated alert instance: {} to level: {}", alertInstance.getId(), alertInstance.getEscalationLevel());
            }
        }
    }
}