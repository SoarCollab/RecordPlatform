package cn.flying.monitor.notification.mapper;

import cn.flying.monitor.notification.entity.AlertInstance;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 告警实例Mapper
 */
@Mapper
public interface AlertInstanceMapper extends BaseMapper<AlertInstance> {

    /**
     * 查询活跃的告警实例
     *
     * @return 活跃的告警实例列表
     */
    @Select("SELECT * FROM alert_instances WHERE status IN ('firing', 'acknowledged') ORDER BY last_triggered DESC")
    List<AlertInstance> selectActiveInstances();

    /**
     * 根据规则ID和客户端ID查询告警实例
     *
     * @param ruleId   规则ID
     * @param clientId 客户端ID
     * @return 告警实例
     */
    @Select("SELECT * FROM alert_instances WHERE rule_id = #{ruleId} AND client_id = #{clientId} AND status != 'resolved' ORDER BY last_triggered DESC LIMIT 1")
    AlertInstance selectByRuleAndClient(@Param("ruleId") Long ruleId, @Param("clientId") String clientId);

    /**
     * 确认告警
     *
     * @param id            告警实例ID
     * @param acknowledgedBy 确认者ID
     * @param acknowledgedAt 确认时间
     * @return 更新行数
     */
    @Update("UPDATE alert_instances SET status = 'acknowledged', acknowledged_by = #{acknowledgedBy}, acknowledged_at = #{acknowledgedAt} WHERE id = #{id}")
    int acknowledgeAlert(@Param("id") Long id, @Param("acknowledgedBy") Long acknowledgedBy, @Param("acknowledgedAt") LocalDateTime acknowledgedAt);

    /**
     * 解决告警
     *
     * @param id         告警实例ID
     * @param resolvedAt 解决时间
     * @return 更新行数
     */
    @Update("UPDATE alert_instances SET status = 'resolved', resolved_at = #{resolvedAt} WHERE id = #{id}")
    int resolveAlert(@Param("id") Long id, @Param("resolvedAt") LocalDateTime resolvedAt);

    /**
     * 根据客户端ID查询告警实例
     *
     * @param clientId 客户端ID
     * @return 告警实例列表
     */
    @Select("SELECT * FROM alert_instances WHERE client_id = #{clientId} ORDER BY last_triggered DESC")
    List<AlertInstance> selectByClientId(@Param("clientId") String clientId);

    /**
     * 查询需要升级的告警实例
     *
     * @param escalationThreshold 升级阈值时间
     * @return 需要升级的告警实例列表
     */
    @Select("SELECT * FROM alert_instances WHERE status = 'firing' AND last_triggered < #{escalationThreshold}")
    List<AlertInstance> selectForEscalation(@Param("escalationThreshold") LocalDateTime escalationThreshold);
}