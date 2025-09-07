package cn.flying.identity.mapper;

import cn.flying.identity.dto.TokenMonitor;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Token监控 Mapper 接口
 * 提供Token监控数据的数据库操作方法
 *
 * @author flying
 * @date 2024
 */
@Mapper
public interface TokenMonitorMapper extends BaseMapper<TokenMonitor> {

    /**
     * 根据Token ID查询监控记录
     *
     * @param tokenId   Token ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 监控记录列表
     */
    @Select("SELECT * FROM token_monitor WHERE token_id = #{tokenId} " +
            "AND event_time BETWEEN #{startTime} AND #{endTime} " +
            "ORDER BY event_time DESC")
    List<TokenMonitor> findByTokenIdAndTimeRange(@Param("tokenId") String tokenId,
                                                 @Param("startTime") LocalDateTime startTime,
                                                 @Param("endTime") LocalDateTime endTime);

    /**
     * 根据用户ID查询Token监控记录
     *
     * @param userId    用户ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 监控记录列表
     */
    @Select("SELECT * FROM token_monitor WHERE user_id = #{userId} " +
            "AND event_time BETWEEN #{startTime} AND #{endTime} " +
            "ORDER BY event_time DESC")
    List<TokenMonitor> findByUserIdAndTimeRange(@Param("userId") Long userId,
                                                @Param("startTime") LocalDateTime startTime,
                                                @Param("endTime") LocalDateTime endTime);

    /**
     * 根据客户端ID查询监控记录
     *
     * @param clientId  客户端ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 监控记录列表
     */
    @Select("SELECT * FROM token_monitor WHERE client_id = #{clientId} " +
            "AND event_time BETWEEN #{startTime} AND #{endTime} " +
            "ORDER BY event_time DESC")
    List<TokenMonitor> findByClientIdAndTimeRange(@Param("clientId") String clientId,
                                                  @Param("startTime") LocalDateTime startTime,
                                                  @Param("endTime") LocalDateTime endTime);

    /**
     * 查询异常Token事件
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 异常事件列表
     */
    @Select("SELECT * FROM token_monitor WHERE is_abnormal = 1 " +
            "AND event_time BETWEEN #{startTime} AND #{endTime} " +
            "ORDER BY event_time DESC")
    List<TokenMonitor> findAbnormalEvents(@Param("startTime") LocalDateTime startTime,
                                          @Param("endTime") LocalDateTime endTime);

    /**
     * 根据事件类型查询监控记录
     *
     * @param eventType 事件类型
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 监控记录列表
     */
    @Select("SELECT * FROM token_monitor WHERE event_type = #{eventType} " +
            "AND event_time BETWEEN #{startTime} AND #{endTime} " +
            "ORDER BY event_time DESC")
    List<TokenMonitor> findByEventTypeAndTimeRange(@Param("eventType") String eventType,
                                                   @Param("startTime") LocalDateTime startTime,
                                                   @Param("endTime") LocalDateTime endTime);

    /**
     * 根据异常类型查询监控记录
     *
     * @param abnormalType 异常类型
     * @param startTime    开始时间
     * @param endTime      结束时间
     * @return 监控记录列表
     */
    @Select("SELECT * FROM token_monitor WHERE abnormal_type = #{abnormalType} " +
            "AND event_time BETWEEN #{startTime} AND #{endTime} " +
            "ORDER BY event_time DESC")
    List<TokenMonitor> findByAbnormalTypeAndTimeRange(@Param("abnormalType") String abnormalType,
                                                      @Param("startTime") LocalDateTime startTime,
                                                      @Param("endTime") LocalDateTime endTime);

    /**
     * 根据风险评分范围查询监控记录
     *
     * @param minScore  最小风险评分
     * @param maxScore  最大风险评分
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 监控记录列表
     */
    @Select("SELECT * FROM token_monitor " +
            "WHERE risk_score BETWEEN #{minScore} AND #{maxScore} " +
            "AND event_time BETWEEN #{startTime} AND #{endTime} " +
            "ORDER BY risk_score DESC, event_time DESC")
    List<TokenMonitor> findByRiskScoreRange(@Param("minScore") Integer minScore,
                                            @Param("maxScore") Integer maxScore,
                                            @Param("startTime") LocalDateTime startTime,
                                            @Param("endTime") LocalDateTime endTime);

    /**
     * 根据IP地址查询监控记录
     *
     * @param clientIp  客户端IP
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 监控记录列表
     */
    @Select("SELECT * FROM token_monitor WHERE client_ip = #{clientIp} " +
            "AND event_time BETWEEN #{startTime} AND #{endTime} " +
            "ORDER BY event_time DESC")
    List<TokenMonitor> findByClientIpAndTimeRange(@Param("clientIp") String clientIp,
                                                  @Param("startTime") LocalDateTime startTime,
                                                  @Param("endTime") LocalDateTime endTime);

    /**
     * 查询未处理的异常事件
     *
     * @return 未处理异常事件列表
     */
    @Select("SELECT * FROM token_monitor " +
            "WHERE is_abnormal = 1 AND handle_status = 'PENDING' " +
            "ORDER BY event_time DESC")
    List<TokenMonitor> findUnhandledAbnormalEvents();

    /**
     * 统计Token事件类型分布
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 事件类型统计
     */
    @Select("SELECT event_type, COUNT(*) as count " +
            "FROM token_monitor " +
            "WHERE event_time BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY event_type " +
            "ORDER BY count DESC")
    List<Map<String, Object>> countByEventType(@Param("startTime") LocalDateTime startTime,
                                               @Param("endTime") LocalDateTime endTime);

    /**
     * 统计Token类型分布
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return Token类型统计
     */
    @Select("SELECT token_type, COUNT(*) as count " +
            "FROM token_monitor " +
            "WHERE event_time BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY token_type " +
            "ORDER BY count DESC")
    List<Map<String, Object>> countByTokenType(@Param("startTime") LocalDateTime startTime,
                                               @Param("endTime") LocalDateTime endTime);

    /**
     * 统计异常类型分布
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 异常类型统计
     */
    @Select("SELECT abnormal_type, COUNT(*) as count " +
            "FROM token_monitor " +
            "WHERE is_abnormal = 1 " +
            "AND event_time BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY abnormal_type " +
            "ORDER BY count DESC")
    List<Map<String, Object>> countByAbnormalType(@Param("startTime") LocalDateTime startTime,
                                                  @Param("endTime") LocalDateTime endTime);

    /**
     * 统计用户Token使用情况
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param limit     限制数量
     * @return 用户Token使用统计
     */
    @Select("SELECT user_id, COUNT(*) as count, COUNT(DISTINCT token_id) as token_count " +
            "FROM token_monitor " +
            "WHERE event_time BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY user_id " +
            "ORDER BY count DESC " +
            "LIMIT #{limit}")
    List<Map<String, Object>> countByUser(@Param("startTime") LocalDateTime startTime,
                                          @Param("endTime") LocalDateTime endTime,
                                          @Param("limit") Integer limit);

    /**
     * 统计客户端Token使用情况
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param limit     限制数量
     * @return 客户端Token使用统计
     */
    @Select("SELECT client_id, COUNT(*) as count, COUNT(DISTINCT token_id) as token_count " +
            "FROM token_monitor " +
            "WHERE event_time BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY client_id " +
            "ORDER BY count DESC " +
            "LIMIT #{limit}")
    List<Map<String, Object>> countByClient(@Param("startTime") LocalDateTime startTime,
                                            @Param("endTime") LocalDateTime endTime,
                                            @Param("limit") Integer limit);

    /**
     * 统计IP访问情况
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param limit     限制数量
     * @return IP访问统计
     */
    @Select("SELECT client_ip, COUNT(*) as count, COUNT(DISTINCT token_id) as token_count " +
            "FROM token_monitor " +
            "WHERE event_time BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY client_ip " +
            "ORDER BY count DESC " +
            "LIMIT #{limit}")
    List<Map<String, Object>> countByClientIp(@Param("startTime") LocalDateTime startTime,
                                              @Param("endTime") LocalDateTime endTime,
                                              @Param("limit") Integer limit);

    /**
     * 统计每日Token事件数量
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 每日事件统计
     */
    @Select("SELECT DATE(event_time) as date, COUNT(*) as count " +
            "FROM token_monitor " +
            "WHERE event_time BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY DATE(event_time) " +
            "ORDER BY date")
    List<Map<String, Object>> countByDate(@Param("startTime") LocalDateTime startTime,
                                          @Param("endTime") LocalDateTime endTime);

    /**
     * 统计每小时Token事件数量
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 每小时事件统计
     */
    @Select("SELECT HOUR(event_time) as hour, COUNT(*) as count " +
            "FROM token_monitor " +
            "WHERE event_time BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY HOUR(event_time) " +
            "ORDER BY hour")
    List<Map<String, Object>> countByHour(@Param("startTime") LocalDateTime startTime,
                                          @Param("endTime") LocalDateTime endTime);

    /**
     * 查询高风险Token事件
     *
     * @param minRiskScore 最小风险评分
     * @param startTime    开始时间
     * @param endTime      结束时间
     * @return 高风险事件列表
     */
    @Select("SELECT * FROM token_monitor " +
            "WHERE risk_score >= #{minRiskScore} " +
            "AND event_time BETWEEN #{startTime} AND #{endTime} " +
            "ORDER BY risk_score DESC, event_time DESC")
    List<TokenMonitor> findHighRiskEvents(@Param("minRiskScore") Integer minRiskScore,
                                          @Param("startTime") LocalDateTime startTime,
                                          @Param("endTime") LocalDateTime endTime);

    /**
     * 清理过期的监控记录
     *
     * @param expireTime 过期时间
     * @return 删除的记录数
     */
    @Select("DELETE FROM token_monitor WHERE event_time < #{expireTime}")
    int deleteExpiredRecords(@Param("expireTime") LocalDateTime expireTime);

    /**
     * 查询Token的完整生命周期
     *
     * @param tokenId Token ID
     * @return Token生命周期记录
     */
    @Select("SELECT * FROM token_monitor WHERE token_id = #{tokenId} " +
            "ORDER BY event_time ASC")
    List<TokenMonitor> findTokenLifecycle(@Param("tokenId") String tokenId);

    /**
     * 查询可疑的Token活动
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 可疑活动列表
     */
    @Select("SELECT * FROM token_monitor " +
            "WHERE (risk_score >= 70 OR is_abnormal = 1) " +
            "AND event_time BETWEEN #{startTime} AND #{endTime} " +
            "ORDER BY risk_score DESC, event_time DESC")
    List<TokenMonitor> findSuspiciousActivities(@Param("startTime") LocalDateTime startTime,
                                                @Param("endTime") LocalDateTime endTime);

    long countByParams(Map<String, Object> params);

    List<TokenMonitor> selectByParams(Map<String, Object> params);
}