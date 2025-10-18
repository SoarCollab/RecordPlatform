package cn.flying.monitor.common.mapper;

import cn.flying.monitor.common.entity.AuditLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Audit log mapper
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
    
    /**
     * Find audit logs by user ID
     */
    @Select("SELECT * FROM audit_logs WHERE user_id = #{userId} ORDER BY timestamp DESC LIMIT #{limit}")
    List<AuditLog> findByUserId(@Param("userId") Long userId, @Param("limit") int limit);
    
    /**
     * Find audit logs by action
     */
    @Select("SELECT * FROM audit_logs WHERE action = #{action} ORDER BY timestamp DESC LIMIT #{limit}")
    List<AuditLog> findByAction(@Param("action") String action, @Param("limit") int limit);
    
    /**
     * Find audit logs by time range
     */
    @Select("SELECT * FROM audit_logs WHERE timestamp BETWEEN #{startTime} AND #{endTime} ORDER BY timestamp DESC")
    List<AuditLog> findByTimeRange(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);
    
    /**
     * Find audit logs by correlation ID
     */
    @Select("SELECT * FROM audit_logs WHERE correlation_id = #{correlationId} ORDER BY timestamp ASC")
    List<AuditLog> findByCorrelationId(@Param("correlationId") String correlationId);
    
    /**
     * Count audit logs by action in time range
     */
    @Select("SELECT COUNT(*) FROM audit_logs WHERE action = #{action} AND timestamp BETWEEN #{startTime} AND #{endTime}")
    long countByActionInTimeRange(@Param("action") String action, @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);
}