package cn.flying.dao.mapper;

import cn.flying.dao.dto.SysOperationLog;
import cn.flying.dao.vo.audit.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 系统审计Mapper接口
 */
@Mapper
public interface SysAuditMapper {

    /**
     * 查询高频操作记录
     */
    @Select("SELECT * FROM v_high_frequency_operations")
    List<HighFrequencyOperationVO> selectHighFrequencyOperations();

    /**
     * 查询敏感操作记录
     */
    @Select({"<script>",
            "SELECT * FROM v_sensitive_operations",
            "WHERE 1=1",
            "<if test='userId != null and userId != \"\"'>AND user_id = #{userId}</if>",
            "<if test='username != null and username != \"\"'>AND username LIKE CONCAT('%',#{username},'%')</if>",
            "<if test='module != null and module != \"\"'>AND module LIKE CONCAT('%',#{module},'%')</if>",
            "<if test='operationType != null and operationType != \"\"'>AND operation_type = #{operationType}</if>",
            "<if test='startTime != null and startTime != \"\" and endTime != null and endTime != \"\"'>AND operation_time BETWEEN #{startTime} AND #{endTime}</if>",
            "ORDER BY operation_time DESC",
            "LIMIT #{pageSize} OFFSET #{offset}",
            "</script>"})
    List<SysOperationLog> selectSensitiveOperations(
            @Param("userId") String userId,
            @Param("username") String username,
            @Param("module") String module,
            @Param("operationType") String operationType,
            @Param("startTime") String startTime,
            @Param("endTime") String endTime,
            @Param("pageSize") Integer pageSize,
            @Param("offset") Integer offset);

    /**
     * 计算敏感操作总数
     */
    @Select({"<script>",
            "SELECT COUNT(*) FROM v_sensitive_operations",
            "WHERE 1=1",
            "<if test='userId != null and userId != \"\"'>AND user_id = #{userId}</if>",
            "<if test='username != null and username != \"\"'>AND username LIKE CONCAT('%',#{username},'%')</if>",
            "<if test='module != null and module != \"\"'>AND module LIKE CONCAT('%',#{module},'%')</if>",
            "<if test='operationType != null and operationType != \"\"'>AND operation_type = #{operationType}</if>",
            "<if test='startTime != null and startTime != \"\" and endTime != null and endTime != \"\"'>AND operation_time BETWEEN #{startTime} AND #{endTime}</if>",
            "</script>"})
    Long countSensitiveOperations(
            @Param("userId") String userId,
            @Param("username") String username,
            @Param("module") String module,
            @Param("operationType") String operationType,
            @Param("startTime") String startTime,
            @Param("endTime") String endTime);

    /**
     * 查询错误操作统计
     */
    @Select("SELECT * FROM v_error_operations_stats")
    List<ErrorOperationStatsVO> selectErrorOperationStats();

    /**
     * 查询用户操作时间分布
     */
    @Select("SELECT * FROM v_user_time_distribution")
    List<UserTimeDistributionVO> selectUserTimeDistribution();

    /**
     * 查询审计配置列表
     */
    @Select("SELECT * FROM sys_audit_config")
    List<AuditConfigVO> selectAuditConfigs();

    /**
     * 更新审计配置
     */
    @Update("UPDATE sys_audit_config SET config_value = #{configValue}, description = #{description} WHERE config_key = #{configKey}")
    int updateAuditConfig(
            @Param("configKey") String configKey,
            @Param("configValue") String configValue,
            @Param("description") String description);

    /**
     * 获取审计概览数据 - 总操作数
     */
    @Select("SELECT COUNT(*) FROM sys_operation_log")
    Integer selectTotalOperations();

    /**
     * 获取审计概览数据 - 今日操作数
     */
    @Select("SELECT COUNT(*) FROM sys_operation_log WHERE DATE(operation_time) = CURDATE()")
    Integer selectTodayOperations();

    /**
     * 获取审计概览数据 - 异常操作数
     */
    @Select("SELECT COUNT(*) FROM sys_operation_log WHERE status = 1")
    Integer selectErrorOperations();

    /**
     * 获取审计概览数据 - 敏感操作数
     */
    @Select("SELECT COUNT(*) FROM v_sensitive_operations")
    Integer selectSensitiveOperationCount();

    /**
     * 获取审计概览数据 - 活跃用户数
     */
    @Select("SELECT COUNT(DISTINCT user_id) FROM sys_operation_log WHERE DATE(operation_time) = CURDATE()")
    Integer selectActiveUsers();

    /**
     * 获取最近7天的操作统计
     */
    @Select("SELECT DATE(operation_time) as date, COUNT(*) as count FROM sys_operation_log " +
            "WHERE operation_time >= DATE_SUB(CURDATE(), INTERVAL 7 DAY) " +
            "GROUP BY DATE(operation_time) ORDER BY date")
    List<Map<String, Object>> selectDailyStats();

    /**
     * 执行异常检查
     */
    @Select("CALL proc_check_operation_anomalies(@p_has_anomalies, @p_anomaly_details); " +
            "SELECT @p_has_anomalies as hasAnomalies, @p_anomaly_details as anomalyDetails")
    Map<String, Object> checkAnomalies();

    /**
     * 执行日志备份
     */
    @Update("CALL proc_backup_operation_logs(#{days}, #{deleteAfterBackup})")
    void backupLogs(
            @Param("days") Integer days,
            @Param("deleteAfterBackup") Boolean deleteAfterBackup);
} 