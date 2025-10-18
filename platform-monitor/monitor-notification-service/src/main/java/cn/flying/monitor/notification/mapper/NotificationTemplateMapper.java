package cn.flying.monitor.notification.mapper;

import cn.flying.monitor.notification.entity.NotificationTemplate;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 通知模板Mapper
 */
@Mapper
public interface NotificationTemplateMapper extends BaseMapper<NotificationTemplate> {

    /**
     * 根据通知类型和告警级别查询模板
     *
     * @param notificationType 通知类型
     * @param severity         告警级别
     * @return 通知模板
     */
    @Select("SELECT * FROM notification_templates WHERE notification_type = #{notificationType} AND severity = #{severity} AND enabled = true LIMIT 1")
    NotificationTemplate selectByTypeAndSeverity(@Param("notificationType") String notificationType, @Param("severity") String severity);

    /**
     * 根据通知类型查询默认模板
     *
     * @param notificationType 通知类型
     * @return 通知模板
     */
    @Select("SELECT * FROM notification_templates WHERE notification_type = #{notificationType} AND severity IS NULL AND enabled = true LIMIT 1")
    NotificationTemplate selectDefaultByType(@Param("notificationType") String notificationType);

    /**
     * 查询启用的模板
     *
     * @return 启用的模板列表
     */
    @Select("SELECT * FROM notification_templates WHERE enabled = true ORDER BY created_at DESC")
    List<NotificationTemplate> selectEnabledTemplates();
}