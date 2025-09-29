package cn.flying.identity.mapper;

import cn.flying.identity.dto.OperationLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 操作日志 Mapper 接口
 * 整合了原 AuditLogMapper 的功能
 * 
 * @author 王贝强
 */
@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLog> {

    /**
     * 根据用户ID查询操作日志
     *
     * @param userId    用户ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 操作日志列表
     */
    @Select("SELECT * FROM operation_log WHERE user_id = #{userId} " +
            "AND operation_time BETWEEN #{startTime} AND #{endTime} " +
            "ORDER BY operation_time DESC")
    List<OperationLog> findByUserIdAndTimeRange(@Param("userId") Long userId,
                                                @Param("startTime") LocalDateTime startTime,
                                                @Param("endTime") LocalDateTime endTime);

    /**
     * 根据操作类型查询日志
     *
     * @param operationType 操作类型
     * @param startTime     开始时间
     * @param endTime       结束时间
     * @return 操作日志列表
     */
    @Select("SELECT * FROM operation_log WHERE operation_type = #{operationType} " +
            "AND operation_time BETWEEN #{startTime} AND #{endTime} " +
            "ORDER BY operation_time DESC")
    List<OperationLog> findByOperationTypeAndTimeRange(@Param("operationType") String operationType,
                                                       @Param("startTime") LocalDateTime startTime,
                                                       @Param("endTime") LocalDateTime endTime);

    /**
     * 根据模块查询日志
     *
     * @param module    操作模块
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 操作日志列表
     */
    @Select("SELECT * FROM operation_log WHERE module = #{module} " +
            "AND operation_time BETWEEN #{startTime} AND #{endTime} " +
            "ORDER BY operation_time DESC")
    List<OperationLog> findByModuleAndTimeRange(@Param("module") String module,
                                                @Param("startTime") LocalDateTime startTime,
                                                @Param("endTime") LocalDateTime endTime);

    /**
     * 根据IP地址查询日志
     *
     * @param clientIp  客户端IP
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 操作日志列表
     */
    @Select("SELECT * FROM operation_log WHERE client_ip = #{clientIp} " +
            "AND operation_time BETWEEN #{startTime} AND #{endTime} " +
            "ORDER BY operation_time DESC")
    List<OperationLog> findByClientIpAndTimeRange(@Param("clientIp") String clientIp,
                                                  @Param("startTime") LocalDateTime startTime,
                                                  @Param("endTime") LocalDateTime endTime);

    /**
     * 查询失败的操作日志
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 失败操作日志列表
     */
    @Select("SELECT * FROM operation_log WHERE status = 1 " +
            "AND operation_time BETWEEN #{startTime} AND #{endTime} " +
            "ORDER BY operation_time DESC")
    List<OperationLog> findFailedOperations(@Param("startTime") LocalDateTime startTime,
                                            @Param("endTime") LocalDateTime endTime);

    /**
     * 根据风险等级查询日志
     *
     * @param riskLevel 风险等级
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 操作日志列表
     */
    @Select("SELECT * FROM operation_log WHERE risk_level = #{riskLevel} " +
            "AND operation_time BETWEEN #{startTime} AND #{endTime} " +
            "ORDER BY operation_time DESC")
    List<OperationLog> findByRiskLevelAndTimeRange(@Param("riskLevel") String riskLevel,
                                                   @Param("startTime") LocalDateTime startTime,
                                                   @Param("endTime") LocalDateTime endTime);

    /**
     * 统计操作类型分布
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 操作类型统计
     */
    @Select("SELECT operation_type, COUNT(*) as count " +
            "FROM operation_log " +
            "WHERE operation_time BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY operation_type " +
            "ORDER BY count DESC")
    List<Map<String, Object>> countByOperationType(@Param("startTime") LocalDateTime startTime,
                                                    @Param("endTime") LocalDateTime endTime);

    /**
     * 统计用户操作次数
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param limit     限制数量
     * @return 用户操作统计
     */
    @Select("SELECT user_id, username, COUNT(*) as count " +
            "FROM operation_log " +
            "WHERE operation_time BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY user_id, username " +
            "ORDER BY count DESC " +
            "LIMIT #{limit}")
    List<Map<String, Object>> countByUser(@Param("startTime") LocalDateTime startTime,
                                          @Param("endTime") LocalDateTime endTime,
                                          @Param("limit") Integer limit);

    /**
     * 统计IP访问次数
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param limit     限制数量
     * @return IP访问统计
     */
    @Select("SELECT client_ip, COUNT(*) as count " +
            "FROM operation_log " +
            "WHERE operation_time BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY client_ip " +
            "ORDER BY count DESC " +
            "LIMIT #{limit}")
    List<Map<String, Object>> countByClientIp(@Param("startTime") LocalDateTime startTime,
                                              @Param("endTime") LocalDateTime endTime,
                                              @Param("limit") Integer limit);

    /**
     * 统计每日操作数量
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 每日操作统计
     */
    @Select("SELECT DATE(operation_time) as date, COUNT(*) as count " +
            "FROM operation_log " +
            "WHERE operation_time BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY DATE(operation_time) " +
            "ORDER BY date")
    List<Map<String, Object>> countByDate(@Param("startTime") LocalDateTime startTime,
                                          @Param("endTime") LocalDateTime endTime);

    /**
     * 统计每小时操作数量
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 每小时操作统计
     */
    @Select("SELECT HOUR(operation_time) as hour, COUNT(*) as count " +
            "FROM operation_log " +
            "WHERE operation_time BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY HOUR(operation_time) " +
            "ORDER BY hour")
    List<Map<String, Object>> countByHour(@Param("startTime") LocalDateTime startTime,
                                          @Param("endTime") LocalDateTime endTime);

    /**
     * 清理过期的操作日志
     *
     * @param expireTime 过期时间
     * @return 删除的记录数
     */
    @Update("DELETE FROM operation_log WHERE operation_time < #{expireTime}")
    int deleteExpiredLogs(@Param("expireTime") LocalDateTime expireTime);

    /**
     * 查询高风险操作
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 高风险操作列表
     */
    @Select("SELECT * FROM operation_log " +
            "WHERE risk_level IN ('HIGH', 'CRITICAL') " +
            "AND operation_time BETWEEN #{startTime} AND #{endTime} " +
            "ORDER BY operation_time DESC")
    List<OperationLog> findHighRiskOperations(@Param("startTime") LocalDateTime startTime,
                                              @Param("endTime") LocalDateTime endTime);

    /**
     * 查询异常登录记录
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 异常登录记录列表
     */
    @Select("SELECT * FROM operation_log " +
            "WHERE operation_type = 'LOGIN' " +
            "AND (risk_level IN ('HIGH', 'CRITICAL') OR status = 0) " +
            "AND operation_time BETWEEN #{startTime} AND #{endTime} " +
            "ORDER BY operation_time DESC")
    List<OperationLog> findAbnormalLogins(@Param("startTime") LocalDateTime startTime,
                                          @Param("endTime") LocalDateTime endTime);

    /**
     * 统计IP失败次数
     *
     * @param clientIp  客户端IP
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 失败次数
     */
    @Select("SELECT COUNT(*) FROM operation_log " +
            "WHERE client_ip = #{clientIp} " +
            "AND status = 0 " +
            "AND operation_time BETWEEN #{startTime} AND #{endTime}")
    Long countFailuresByIp(@Param("clientIp") String clientIp,
                           @Param("startTime") LocalDateTime startTime,
                           @Param("endTime") LocalDateTime endTime);

    /**
     * 统计用户失败次数
     *
     * @param userId    用户ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 失败次数
     */
    @Select("SELECT COUNT(*) FROM operation_log " +
            "WHERE user_id = #{userId} " +
            "AND status = 0 " +
            "AND operation_time BETWEEN #{startTime} AND #{endTime}")
    Long countFailuresByUser(@Param("userId") Long userId,
                             @Param("startTime") LocalDateTime startTime,
                             @Param("endTime") LocalDateTime endTime);
}
