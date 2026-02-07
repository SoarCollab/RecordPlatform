package cn.flying.dao.mapper;

import cn.flying.dao.entity.OutboxEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;

@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {

    /**
     * 获取待发送事件（定时任务按租户调用）
     */
    @Select("SELECT id, tenant_id, trace_id, aggregate_type, aggregate_id, event_type, payload, status, next_attempt_at, retry_count, create_time, sent_time " +
            "FROM outbox_event " +
            "WHERE status = 'PENDING' AND tenant_id = #{tenantId} AND next_attempt_at <= #{now} " +
            "ORDER BY create_time LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<OutboxEvent> fetchPendingEvents(@Param("tenantId") Long tenantId,
                                         @Param("now") Date now,
                                         @Param("limit") int limit);

    /**
     * 标记事件为已发送（id 唯一，无需租户参数）
     */
    @Update("UPDATE outbox_event SET status = 'SENT', sent_time = NOW() WHERE id = #{id}")
    int markSent(@Param("id") String id);

    /**
     * 标记事件为发送失败（id 唯一，无需租户参数）
     */
    @Update("UPDATE outbox_event SET status = 'FAILED', retry_count = retry_count + 1, " +
            "next_attempt_at = #{nextAttempt} WHERE id = #{id}")
    int markFailed(@Param("id") String id, @Param("nextAttempt") Date nextAttempt);

    /**
     * 统计指定状态的事件数量（跨租户监控）
     * 调用方应使用 @TenantScope(ignoreIsolation=true)
     */
    @Select("SELECT COUNT(*) FROM outbox_event WHERE status = #{status}")
    long countByStatus(@Param("status") String status);

    /**
     * 统计失败且超过最大重试次数的事件数量（跨租户监控）
     * 调用方应使用 @TenantScope(ignoreIsolation=true)
     */
    @Select("SELECT COUNT(*) FROM outbox_event WHERE status = 'FAILED' AND retry_count >= #{maxRetries}")
    long countExhaustedRetries(@Param("maxRetries") int maxRetries);

    /**
     * 删除已发送且超过保留期的事件（定时任务按租户调用）
     */
    @Delete("DELETE FROM outbox_event WHERE status = 'SENT' AND tenant_id = #{tenantId} AND sent_time < #{cutoffDate}")
    int cleanupSentEvents(@Param("tenantId") Long tenantId, @Param("cutoffDate") Date cutoffDate);

    /**
     * 删除永久失败的事件（定时任务按租户调用）
     */
    @Delete("DELETE FROM outbox_event WHERE status = 'FAILED' AND tenant_id = #{tenantId} " +
            "AND retry_count >= #{maxRetries} AND create_time < #{cutoffDate}")
    int cleanupFailedEvents(@Param("tenantId") Long tenantId,
                            @Param("maxRetries") int maxRetries,
                            @Param("cutoffDate") Date cutoffDate);
}
