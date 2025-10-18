package cn.flying.monitor.notification.mapper;

import cn.flying.monitor.notification.entity.NotificationHistory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 通知历史Mapper
 */
@Mapper
public interface NotificationHistoryMapper extends BaseMapper<NotificationHistory> {

    /**
     * 查询需要重试的通知
     *
     * @param now 当前时间
     * @return 需要重试的通知列表
     */
    @Select("SELECT * FROM notification_history WHERE status = 'retry' AND next_retry_at <= #{now} AND retry_count < 3")
    List<NotificationHistory> selectForRetry(@Param("now") LocalDateTime now);

    /**
     * 根据告警实例ID查询通知历史
     *
     * @param alertInstanceId 告警实例ID
     * @return 通知历史列表
     */
    @Select("SELECT * FROM notification_history WHERE alert_instance_id = #{alertInstanceId} ORDER BY created_at DESC")
    List<NotificationHistory> selectByAlertInstanceId(@Param("alertInstanceId") Long alertInstanceId);

    /**
     * 查询失败的通知
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 失败的通知列表
     */
    @Select("SELECT * FROM notification_history WHERE status = 'failed' AND created_at BETWEEN #{startTime} AND #{endTime}")
    List<NotificationHistory> selectFailedNotifications(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    /**
     * 统计通知发送情况
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 统计结果
     */
    @Select("SELECT notification_type, status, COUNT(*) as count FROM notification_history WHERE created_at BETWEEN #{startTime} AND #{endTime} GROUP BY notification_type, status")
    List<Map<String, Object>> selectNotificationStats(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);
}