package cn.flying.identity.mapper;

import cn.flying.identity.dto.TrafficMonitorEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 流量监控 Mapper 接口
 * 提供流量监控数据的数据库访问功能
 *
 * @author 王贝强
 */
@Mapper
public interface TrafficMonitorMapper extends BaseMapper<TrafficMonitorEntity> {

    /**
     * 根据IP地址查询流量记录
     */
    @Select("SELECT * FROM traffic_monitor WHERE client_ip = #{clientIp} " +
            "AND request_time BETWEEN #{startTime} AND #{endTime} " +
            "ORDER BY request_time DESC")
    List<TrafficMonitorEntity> findByClientIpAndTimeRange(@Param("clientIp") String clientIp,
                                                          @Param("startTime") LocalDateTime startTime,
                                                          @Param("endTime") LocalDateTime endTime);

    /**
     * 根据用户ID查询流量记录
     */
    @Select("SELECT * FROM traffic_monitor WHERE user_id = #{userId} " +
            "AND request_time BETWEEN #{startTime} AND #{endTime} " +
            "ORDER BY request_time DESC")
    List<TrafficMonitorEntity> findByUserIdAndTimeRange(@Param("userId") Long userId,
                                                        @Param("startTime") LocalDateTime startTime,
                                                        @Param("endTime") LocalDateTime endTime);

    /**
     * 查询异常流量记录
     */
    @Select("SELECT * FROM traffic_monitor WHERE is_abnormal = 1 " +
            "AND request_time BETWEEN #{startTime} AND #{endTime} " +
            "ORDER BY risk_score DESC, request_time DESC")
    List<TrafficMonitorEntity> findAbnormalTraffic(@Param("startTime") LocalDateTime startTime,
                                                   @Param("endTime") LocalDateTime endTime);

    /**
     * 统计IP访问次数排行
     */
    @Select("SELECT client_ip, COUNT(*) as request_count, " +
            "AVG(response_time) as avg_response_time, " +
            "COUNT(CASE WHEN response_status >= 400 THEN 1 END) as error_count " +
            "FROM traffic_monitor " +
            "WHERE request_time BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY client_ip " +
            "ORDER BY request_count DESC " +
            "LIMIT #{limit}")
    List<Map<String, Object>> getTopTrafficIps(@Param("startTime") LocalDateTime startTime,
                                               @Param("endTime") LocalDateTime endTime,
                                               @Param("limit") int limit);

    /**
     * 统计API访问次数排行
     */
    @Select("SELECT request_path, request_method, COUNT(*) as request_count, " +
            "AVG(response_time) as avg_response_time, " +
            "COUNT(CASE WHEN response_status >= 400 THEN 1 END) as error_count " +
            "FROM traffic_monitor " +
            "WHERE request_time BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY request_path, request_method " +
            "ORDER BY request_count DESC " +
            "LIMIT #{limit}")
    List<Map<String, Object>> getTopApis(@Param("startTime") LocalDateTime startTime,
                                        @Param("endTime") LocalDateTime endTime,
                                        @Param("limit") int limit);

    /**
     * 统计异常类型分布
     */
    @Select("SELECT abnormal_type, COUNT(*) as count " +
            "FROM traffic_monitor " +
            "WHERE is_abnormal = 1 " +
            "AND request_time BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY abnormal_type " +
            "ORDER BY count DESC")
    List<Map<String, Object>> countByAbnormalType(@Param("startTime") LocalDateTime startTime,
                                                  @Param("endTime") LocalDateTime endTime);

    /**
     * 统计响应状态码分布
     */
    @Select("SELECT response_status, COUNT(*) as count " +
            "FROM traffic_monitor " +
            "WHERE request_time BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY response_status " +
            "ORDER BY count DESC")
    List<Map<String, Object>> countByResponseStatus(@Param("startTime") LocalDateTime startTime,
                                                    @Param("endTime") LocalDateTime endTime);

    /**
     * 统计每小时请求量
     */
    @Select("SELECT DATE_FORMAT(request_time, '%Y-%m-%d %H:00:00') as hour, " +
            "COUNT(*) as request_count, " +
            "AVG(response_time) as avg_response_time, " +
            "COUNT(CASE WHEN is_abnormal = 1 THEN 1 END) as abnormal_count " +
            "FROM traffic_monitor " +
            "WHERE request_time BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY hour " +
            "ORDER BY hour")
    List<Map<String, Object>> getHourlyTrafficStats(@Param("startTime") LocalDateTime startTime,
                                                    @Param("endTime") LocalDateTime endTime);

    /**
     * 统计每日请求量
     */
    @Select("SELECT DATE(request_time) as date, " +
            "COUNT(*) as request_count, " +
            "AVG(response_time) as avg_response_time, " +
            "COUNT(CASE WHEN is_abnormal = 1 THEN 1 END) as abnormal_count, " +
            "COUNT(DISTINCT client_ip) as unique_ips " +
            "FROM traffic_monitor " +
            "WHERE request_time BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY date " +
            "ORDER BY date")
    List<Map<String, Object>> getDailyTrafficStats(@Param("startTime") LocalDateTime startTime,
                                                   @Param("endTime") LocalDateTime endTime);

    /**
     * 获取高风险流量记录
     */
    @Select("SELECT * FROM traffic_monitor " +
            "WHERE risk_score >= #{minRiskScore} " +
            "AND request_time BETWEEN #{startTime} AND #{endTime} " +
            "ORDER BY risk_score DESC, request_time DESC " +
            "LIMIT #{limit}")
    List<TrafficMonitorEntity> getHighRiskTraffic(@Param("minRiskScore") int minRiskScore,
                                                  @Param("startTime") LocalDateTime startTime,
                                                  @Param("endTime") LocalDateTime endTime,
                                                  @Param("limit") int limit);

    /**
     * 统计IP的请求频率
     */
    @Select("SELECT COUNT(*) FROM traffic_monitor " +
            "WHERE client_ip = #{clientIp} " +
            "AND request_time BETWEEN #{startTime} AND #{endTime}")
    long countRequestsByIpAndTimeRange(@Param("clientIp") String clientIp,
                                      @Param("startTime") LocalDateTime startTime,
                                      @Param("endTime") LocalDateTime endTime);

    /**
     * 统计用户的请求频率
     */
    @Select("SELECT COUNT(*) FROM traffic_monitor " +
            "WHERE user_id = #{userId} " +
            "AND request_time BETWEEN #{startTime} AND #{endTime}")
    long countRequestsByUserAndTimeRange(@Param("userId") Long userId,
                                        @Param("startTime") LocalDateTime startTime,
                                        @Param("endTime") LocalDateTime endTime);

    /**
     * 获取慢请求统计
     */
    @Select("SELECT request_path, request_method, " +
            "AVG(response_time) as avg_response_time, " +
            "MAX(response_time) as max_response_time, " +
            "COUNT(*) as slow_request_count " +
            "FROM traffic_monitor " +
            "WHERE response_time > #{threshold} " +
            "AND request_time BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY request_path, request_method " +
            "ORDER BY avg_response_time DESC " +
            "LIMIT #{limit}")
    List<Map<String, Object>> getSlowRequests(@Param("threshold") long threshold,
                                             @Param("startTime") LocalDateTime startTime,
                                             @Param("endTime") LocalDateTime endTime,
                                             @Param("limit") int limit);

    /**
     * 统计地理位置分布
     */
    @Select("SELECT geo_location, COUNT(*) as count, " +
            "COUNT(DISTINCT client_ip) as unique_ips " +
            "FROM traffic_monitor " +
            "WHERE geo_location IS NOT NULL " +
            "AND request_time BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY geo_location " +
            "ORDER BY count DESC " +
            "LIMIT #{limit}")
    List<Map<String, Object>> getGeographicDistribution(@Param("startTime") LocalDateTime startTime,
                                                        @Param("endTime") LocalDateTime endTime,
                                                        @Param("limit") int limit);

    /**
     * 清理过期数据
     */
    @Update("DELETE FROM traffic_monitor WHERE request_time < #{expireTime}")
    int deleteExpiredRecords(@Param("expireTime") LocalDateTime expireTime);

    /**
     * 获取系统流量概览
     */
    @Select("SELECT " +
            "COUNT(*) as total_requests, " +
            "COUNT(DISTINCT client_ip) as unique_ips, " +
            "COUNT(DISTINCT user_id) as unique_users, " +
            "AVG(response_time) as avg_response_time, " +
            "COUNT(CASE WHEN response_status >= 400 THEN 1 END) as error_count, " +
            "COUNT(CASE WHEN is_abnormal = 1 THEN 1 END) as abnormal_count, " +
            "COUNT(CASE WHEN block_status > 0 THEN 1 END) as blocked_count " +
            "FROM traffic_monitor " +
            "WHERE request_time BETWEEN #{startTime} AND #{endTime}")
    Map<String, Object> getTrafficOverview(@Param("startTime") LocalDateTime startTime,
                                          @Param("endTime") LocalDateTime endTime);

    /**
     * 获取实时流量统计（最近N分钟）
     */
    @Select("SELECT " +
            "COUNT(*) as current_requests, " +
            "COUNT(DISTINCT client_ip) as current_ips, " +
            "AVG(response_time) as current_avg_response_time, " +
            "COUNT(CASE WHEN response_status >= 400 THEN 1 END) as current_errors " +
            "FROM traffic_monitor " +
            "WHERE request_time >= #{since}")
    Map<String, Object> getCurrentTrafficStats(@Param("since") LocalDateTime since);
}
