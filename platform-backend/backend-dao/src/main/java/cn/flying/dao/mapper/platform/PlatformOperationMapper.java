package cn.flying.dao.mapper.platform;

import cn.flying.dao.entity.platform.PlatformOperationLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** System-owned operation statements that retain the normal tenant interceptor. */
@Mapper
public interface PlatformOperationMapper {

    String COLUMNS = "id, tenant_id, actor_id, idempotency_key, request_hash, operation, target_tenant_id, "
            + "resource_type, resource_id, resource_key, reason, before_summary, after_summary, status, "
            + "result_json, error_code, trace_id, started_at, completed_at, duration_ms";
    String FILTER = """
            WHERE tenant_id = 0
            <if test="targetTenantId != null">AND target_tenant_id = #{targetTenantId}</if>
            <if test="status != null">AND status = #{status}</if>
            """;

    /** Durably claims one actor/key pair; unique-key conflict never means permission to rerun it. */
    @Insert("""
            INSERT INTO platform_operation_log
                (id, tenant_id, actor_id, idempotency_key, request_hash, operation, target_tenant_id,
                 resource_type, resource_id, resource_key, reason, status, trace_id, started_at)
            VALUES (#{id}, 0, #{actorId}, #{idempotencyKey}, #{requestHash}, #{operation}, #{targetTenantId},
                    #{resourceType}, #{resourceId}, #{resourceKey}, #{reason}, 'PROCESSING', #{traceId}, #{startedAt})
            """)
    int insertClaim(PlatformOperationLog operation);

    /** Looks up the original command outcome under the unique actor/key identity. */
    @Select("SELECT " + COLUMNS + " FROM platform_operation_log "
            + "WHERE tenant_id = 0 AND actor_id = #{actorId} AND idempotency_key = #{key}")
    PlatformOperationLog selectByActorAndKey(@Param("actorId") Long actorId, @Param("key") String key);

    /** Locks the claimed command before any business mutation or terminal transition. */
    @Select("SELECT " + COLUMNS + " FROM platform_operation_log WHERE tenant_id = 0 AND id = #{id} FOR UPDATE")
    PlatformOperationLog selectForUpdate(@Param("id") Long id);

    /** Commits the sanitized successful result in the same transaction as the business mutation. */
    @Update("""
            UPDATE platform_operation_log
               SET target_tenant_id = #{targetTenantId}, resource_id = #{resourceId}, resource_key = #{resourceKey},
                   before_summary = #{beforeSummary}, after_summary = #{afterSummary},
                   status = 'SUCCESS', result_json = #{resultJson}, error_code = NULL,
                   completed_at = #{completedAt}, duration_ms = #{durationMs}
             WHERE tenant_id = 0 AND id = #{id} AND status = 'PROCESSING'
            """)
    int completeSuccess(PlatformOperationLog operation);

    /** Records only a stable failure code after rollback and never overwrites terminal success. */
    @Update("""
            UPDATE platform_operation_log
               SET status = 'FAILURE', result_json = NULL, error_code = #{errorCode},
                   completed_at = #{completedAt}, duration_ms = #{durationMs}
             WHERE tenant_id = 0 AND id = #{id} AND status = 'PROCESSING'
            """)
    int completeFailure(@Param("id") Long id, @Param("errorCode") int errorCode,
                        @Param("completedAt") LocalDateTime completedAt, @Param("durationMs") long durationMs);

    /** Returns a bounded history page without exposing the request fingerprint at the API boundary. */
    @Select("<script>SELECT " + COLUMNS + " FROM platform_operation_log " + FILTER
            + " ORDER BY started_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<PlatformOperationLog> selectPage(@Param("offset") long offset, @Param("limit") long limit,
                                         @Param("targetTenantId") Long targetTenantId, @Param("status") String status);

    /** Counts the same system-owned operation set as the bounded history query. */
    @Select("<script>SELECT COUNT(*) FROM platform_operation_log " + FILTER + "</script>")
    long countPage(@Param("targetTenantId") Long targetTenantId, @Param("status") String status);

    /** Reads one operation in system scope; target tenants cannot observe this table through the API. */
    @Select("SELECT " + COLUMNS + " FROM platform_operation_log WHERE tenant_id = 0 AND id = #{id}")
    PlatformOperationLog selectById(@Param("id") Long id);
}
