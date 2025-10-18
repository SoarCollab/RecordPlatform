package cn.flying.monitor.notification.service;

import cn.flying.monitor.notification.dto.AlertRuleDTO;
import cn.flying.monitor.notification.entity.AlertRule;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

/**
 * 告警规则服务接口
 */
public interface AlertRuleService {

    /**
     * 创建告警规则
     *
     * @param alertRuleDTO 告警规则DTO
     * @return 创建的告警规则
     */
    AlertRule createAlertRule(AlertRuleDTO alertRuleDTO);

    /**
     * 更新告警规则
     *
     * @param id           规则ID
     * @param alertRuleDTO 告警规则DTO
     * @return 更新的告警规则
     */
    AlertRule updateAlertRule(Long id, AlertRuleDTO alertRuleDTO);

    /**
     * 删除告警规则
     *
     * @param id 规则ID
     */
    void deleteAlertRule(Long id);

    /**
     * 根据ID查询告警规则
     *
     * @param id 规则ID
     * @return 告警规则
     */
    AlertRule getAlertRuleById(Long id);

    /**
     * 分页查询告警规则
     *
     * @param page 分页参数
     * @return 分页结果
     */
    IPage<AlertRule> getAlertRules(Page<AlertRule> page);

    /**
     * 查询启用的告警规则
     *
     * @return 启用的告警规则列表
     */
    List<AlertRule> getEnabledAlertRules();

    /**
     * 根据指标名称查询告警规则
     *
     * @param metricName 指标名称
     * @return 告警规则列表
     */
    List<AlertRule> getAlertRulesByMetricName(String metricName);

    /**
     * 启用/禁用告警规则
     *
     * @param id      规则ID
     * @param enabled 是否启用
     */
    void toggleAlertRule(Long id, Boolean enabled);

    /**
     * 验证告警规则配置
     *
     * @param alertRuleDTO 告警规则DTO
     * @return 验证结果
     */
    boolean validateAlertRule(AlertRuleDTO alertRuleDTO);
}