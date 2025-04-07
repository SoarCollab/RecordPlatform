package cn.flying.dao.mapper;

import cn.flying.dao.dto.SysOperationLog;
import cn.flying.dao.vo.audit.AuditConfigVO;
import cn.flying.dao.vo.audit.ErrorOperationStatsVO;
import cn.flying.dao.vo.audit.HighFrequencyOperationVO;
import cn.flying.dao.vo.audit.UserTimeDistributionVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 系统操作日志Mapper接口
 */
@Mapper
public interface SysOperationLogMapper extends BaseMapper<SysOperationLog> {

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
    List<Map<String, Object>> selectDailyStats();

    /**
     * 执行异常检查
     */
    Map<String, Object> checkAnomalies();

    /**
     * 执行日志备份
     */
    void backupLogs(@Param("days") Integer days, @Param("deleteAfterBackup") Boolean deleteAfterBackup);

    /**
     * 查询高频操作记录 (v_high_frequency_operations)
     */
    List<HighFrequencyOperationVO> selectHighFrequencyOperations();

    /**
     * 查询敏感操作记录 (v_sensitive_operations) - 分页
     */
    List<SysOperationLog> selectSensitiveOperations(
            @Param("userId") String userId,
            @Param("username") String username,
            @Param("module") String module,
            @Param("operationType") String operationType,
            @Param("startTime") String startTime,
            @Param("endTime") String endTime,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    /**
     * 查询敏感操作记录总数 (v_sensitive_operations) - 用于分页
     */
    Long countSensitiveOperations(
            @Param("userId") String userId,
            @Param("username") String username,
            @Param("module") String module,
            @Param("operationType") String operationType,
            @Param("startTime") String startTime,
            @Param("endTime") String endTime
    );

    /**
     * 查询错误操作统计 (v_error_operations_stats)
     */
    List<ErrorOperationStatsVO> selectErrorOperationStats();

    /**
     * 查询用户操作时间分布 (v_user_time_distribution)
     */
    List<UserTimeDistributionVO> selectUserTimeDistribution();

    /**
     * 查询所有审计配置 (sys_audit_config)
     */
    List<AuditConfigVO> selectAuditConfigs();

    /**
     * 根据Key查询单个审计配置
     */
    AuditConfigVO selectAuditConfigByKey(@Param("configKey") String configKey);

    /**
     * 更新审计配置 (sys_audit_config)
     */
    int updateAuditConfig(@Param("configKey") String configKey, @Param("configValue") String configValue, @Param("description") String description);

    /**
     * 查询总操作日志数 (sys_operation_log)
     */
    Long selectTotalOperations();

    /**
     * 查询指定时间范围内的操作日志数 (sys_operation_log)
     */
    Long selectOperationsBetween(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    /**
     * 查询总错误操作日志数 (sys_operation_log where status=1)
     */
    Long selectTotalErrorOperations();

    /**
     * 查询指定时间范围内的错误操作日志数 (sys_operation_log where status=1)
     */
    Long selectErrorOperationsBetween(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    /**
     * 查询指定时间范围内的敏感操作日志数 (v_sensitive_operations)
     */
    Long selectSensitiveOperationsCountBetween(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    /**
     * 查询指定时间范围内的活跃用户数 (sys_operation_log)
     */
    Long selectActiveUsersBetween(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    /**
     * 查询高频操作告警数量 (v_high_frequency_operations)
     */
    Long selectHighFrequencyAlertCount();

    /**
     * 查询过去N天的每日操作统计 (sys_operation_log)
     * 返回 Map 列表，例如: [{operation_date: '2024-04-01', operation_count: 150}, ...]
     */
    List<Map<String, Object>> selectDailyStats(@Param("days") int days);

} 