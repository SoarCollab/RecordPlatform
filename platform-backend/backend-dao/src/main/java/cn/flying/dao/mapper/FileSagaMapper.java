package cn.flying.dao.mapper;

import cn.flying.dao.entity.FileSaga;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;

@Mapper
public interface FileSagaMapper extends BaseMapper<FileSaga> {

    /**
     * 根据请求ID查询 Saga（带租户隔离）
     */
    @Select("SELECT * FROM file_saga WHERE request_id = #{requestId} AND tenant_id = #{tenantId}")
    FileSaga selectByRequestId(@Param("requestId") String requestId, @Param("tenantId") Long tenantId);

    /**
     * 查询卡住的 Saga（定时任务按租户调用）
     */
    @Select("SELECT * FROM file_saga WHERE status = #{status} AND tenant_id = #{tenantId} AND update_time < #{cutoffTime} LIMIT #{limit}")
    List<FileSaga> selectStuckSagas(@Param("status") String status,
                                     @Param("tenantId") Long tenantId,
                                     @Param("cutoffTime") Date cutoffTime,
                                     @Param("limit") int limit);

    @Update("UPDATE file_saga SET status = #{newStatus}, update_time = NOW() " +
            "WHERE id = #{id} AND status = #{expectedStatus}")
    int updateStatusWithCas(@Param("id") Long id,
                           @Param("expectedStatus") String expectedStatus,
                           @Param("newStatus") String newStatus);

    /**
     * 查询待重试补偿的 Saga（状态为 PENDING_COMPENSATION 且已到重试时间）
     * 使用 FOR UPDATE SKIP LOCKED 避免多实例重复处理同一记录
     * 定时任务按租户调用，需指定 tenantId
     */
    @Select("SELECT * FROM file_saga WHERE status = 'PENDING_COMPENSATION' " +
            "AND tenant_id = #{tenantId} " +
            "AND (next_retry_at IS NULL OR next_retry_at <= NOW()) " +
            "ORDER BY next_retry_at ASC LIMIT #{limit} " +
            "FOR UPDATE SKIP LOCKED")
    List<FileSaga> selectPendingCompensation(@Param("tenantId") Long tenantId, @Param("limit") int limit);

    /**
     * 统计指定状态的 Saga 数量（跨租户监控）
     * 调用方应使用 @TenantScope(ignoreIsolation=true) 或 TenantContext.runWithoutIsolation()
     */
    @Select("SELECT COUNT(*) FROM file_saga WHERE status = #{status}")
    long countByStatus(@Param("status") String status);

    /**
     * 统计失败且超过最大重试次数的 Saga 数量（跨租户监控）
     * 调用方应使用 @TenantScope(ignoreIsolation=true) 或 TenantContext.runWithoutIsolation()
     */
    @Select("SELECT COUNT(*) FROM file_saga WHERE status = 'FAILED' AND retry_count >= #{maxRetries}")
    long countExhaustedRetries(@Param("maxRetries") int maxRetries);
}
