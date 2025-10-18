package cn.flying.monitor.notification.service.impl;

import cn.flying.monitor.notification.dto.AlertRuleDTO;
import cn.flying.monitor.notification.entity.AlertRule;
import cn.flying.monitor.notification.mapper.AlertRuleMapper;
import cn.flying.monitor.notification.service.AlertRuleService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 告警规则服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertRuleServiceImpl implements AlertRuleService {

    private final AlertRuleMapper alertRuleMapper;

    @Override
    @Transactional
    public AlertRule createAlertRule(AlertRuleDTO alertRuleDTO) {
        log.info("Creating alert rule: {}", alertRuleDTO.getName());
        
        // 验证规则配置
        if (!validateAlertRule(alertRuleDTO)) {
            throw new IllegalArgumentException("Invalid alert rule configuration");
        }
        
        // 检查规则名称是否已存在
        QueryWrapper<AlertRule> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("name", alertRuleDTO.getName());
        if (alertRuleMapper.selectCount(queryWrapper) > 0) {
            throw new IllegalArgumentException("Alert rule name already exists: " + alertRuleDTO.getName());
        }
        
        AlertRule alertRule = new AlertRule();
        BeanUtils.copyProperties(alertRuleDTO, alertRule);
        alertRule.setCreatedAt(LocalDateTime.now());
        alertRule.setUpdatedAt(LocalDateTime.now());
        
        alertRuleMapper.insert(alertRule);
        log.info("Created alert rule with ID: {}", alertRule.getId());
        
        return alertRule;
    }

    @Override
    @Transactional
    public AlertRule updateAlertRule(Long id, AlertRuleDTO alertRuleDTO) {
        log.info("Updating alert rule ID: {}", id);
        
        AlertRule existingRule = alertRuleMapper.selectById(id);
        if (existingRule == null) {
            throw new IllegalArgumentException("Alert rule not found with ID: " + id);
        }
        
        // 验证规则配置
        if (!validateAlertRule(alertRuleDTO)) {
            throw new IllegalArgumentException("Invalid alert rule configuration");
        }
        
        // 检查规则名称是否与其他规则冲突
        if (!existingRule.getName().equals(alertRuleDTO.getName())) {
            QueryWrapper<AlertRule> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("name", alertRuleDTO.getName());
            queryWrapper.ne("id", id);
            if (alertRuleMapper.selectCount(queryWrapper) > 0) {
                throw new IllegalArgumentException("Alert rule name already exists: " + alertRuleDTO.getName());
            }
        }
        
        BeanUtils.copyProperties(alertRuleDTO, existingRule, "id", "createdAt", "createdBy");
        existingRule.setUpdatedAt(LocalDateTime.now());
        
        alertRuleMapper.updateById(existingRule);
        log.info("Updated alert rule ID: {}", id);
        
        return existingRule;
    }

    @Override
    @Transactional
    public void deleteAlertRule(Long id) {
        log.info("Deleting alert rule ID: {}", id);
        
        AlertRule existingRule = alertRuleMapper.selectById(id);
        if (existingRule == null) {
            throw new IllegalArgumentException("Alert rule not found with ID: " + id);
        }
        
        alertRuleMapper.deleteById(id);
        log.info("Deleted alert rule ID: {}", id);
    }

    @Override
    public AlertRule getAlertRuleById(Long id) {
        return alertRuleMapper.selectById(id);
    }

    @Override
    public IPage<AlertRule> getAlertRules(Page<AlertRule> page) {
        QueryWrapper<AlertRule> queryWrapper = new QueryWrapper<>();
        queryWrapper.orderByDesc("created_at");
        return alertRuleMapper.selectPage(page, queryWrapper);
    }

    @Override
    public List<AlertRule> getEnabledAlertRules() {
        return alertRuleMapper.selectEnabledRules();
    }

    @Override
    public List<AlertRule> getAlertRulesByMetricName(String metricName) {
        return alertRuleMapper.selectByMetricName(metricName);
    }

    @Override
    @Transactional
    public void toggleAlertRule(Long id, Boolean enabled) {
        log.info("Toggling alert rule ID: {} to enabled: {}", id, enabled);
        
        AlertRule alertRule = alertRuleMapper.selectById(id);
        if (alertRule == null) {
            throw new IllegalArgumentException("Alert rule not found with ID: " + id);
        }
        
        alertRule.setEnabled(enabled);
        alertRule.setUpdatedAt(LocalDateTime.now());
        alertRuleMapper.updateById(alertRule);
        
        log.info("Toggled alert rule ID: {} to enabled: {}", id, enabled);
    }

    @Override
    public boolean validateAlertRule(AlertRuleDTO alertRuleDTO) {
        // 基本字段验证
        if (alertRuleDTO.getName() == null || alertRuleDTO.getName().trim().isEmpty()) {
            log.warn("Alert rule name is required");
            return false;
        }
        
        if (alertRuleDTO.getMetricName() == null || alertRuleDTO.getMetricName().trim().isEmpty()) {
            log.warn("Metric name is required");
            return false;
        }
        
        if (alertRuleDTO.getConditionOperator() == null || 
            !List.of(">", "<", ">=", "<=", "=", "!=").contains(alertRuleDTO.getConditionOperator())) {
            log.warn("Invalid condition operator: {}", alertRuleDTO.getConditionOperator());
            return false;
        }
        
        if (alertRuleDTO.getThresholdValue() == null || alertRuleDTO.getThresholdValue().compareTo(java.math.BigDecimal.ZERO) < 0) {
            log.warn("Invalid threshold value: {}", alertRuleDTO.getThresholdValue());
            return false;
        }
        
        if (alertRuleDTO.getSeverity() == null || 
            !List.of("low", "medium", "high", "critical").contains(alertRuleDTO.getSeverity())) {
            log.warn("Invalid severity: {}", alertRuleDTO.getSeverity());
            return false;
        }
        
        // 评估间隔验证
        if (alertRuleDTO.getEvaluationInterval() != null && 
            (alertRuleDTO.getEvaluationInterval() < 30 || alertRuleDTO.getEvaluationInterval() > 3600)) {
            log.warn("Invalid evaluation interval: {}", alertRuleDTO.getEvaluationInterval());
            return false;
        }
        
        // 连续失败次数验证
        if (alertRuleDTO.getConsecutiveFailuresRequired() != null && 
            (alertRuleDTO.getConsecutiveFailuresRequired() < 1 || alertRuleDTO.getConsecutiveFailuresRequired() > 10)) {
            log.warn("Invalid consecutive failures required: {}", alertRuleDTO.getConsecutiveFailuresRequired());
            return false;
        }
        
        return true;
    }
}