package cn.flying.monitor.notification.mapper;

import cn.flying.monitor.notification.entity.AlertRule;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 告警规则Mapper
 */
@Mapper
public interface AlertRuleMapper extends BaseMapper<AlertRule> {

    /**
     * 查询启用的告警规则
     *
     * @return 启用的告警规则列表
     */
    @Select("SELECT * FROM alert_rules WHERE enabled = true ORDER BY created_at DESC")
    List<AlertRule> selectEnabledRules();

    /**
     * 根据指标名称查询告警规则
     *
     * @param metricName 指标名称
     * @return 告警规则列表
     */
    @Select("SELECT * FROM alert_rules WHERE metric_name = #{metricName} AND enabled = true")
    List<AlertRule> selectByMetricName(@Param("metricName") String metricName);

    /**
     * 根据创建者查询告警规则
     *
     * @param createdBy 创建者ID
     * @return 告警规则列表
     */
    @Select("SELECT * FROM alert_rules WHERE created_by = #{createdBy} ORDER BY created_at DESC")
    List<AlertRule> selectByCreatedBy(@Param("createdBy") Long createdBy);
}