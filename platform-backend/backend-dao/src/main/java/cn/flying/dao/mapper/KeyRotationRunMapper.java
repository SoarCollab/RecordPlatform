package cn.flying.dao.mapper;

import cn.flying.dao.entity.KeyRotationRun;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Mapper for immutable rotation snapshots and aggregate lifecycle state.
 */
@Mapper
public interface KeyRotationRunMapper extends BaseMapper<KeyRotationRun> {

    /**
     * Locks one tenant run before a lifecycle transition.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, tenant_id, policy_id, policy_version, trigger_type, trigger_key,
                   mode, status, target_provider, target_provider_contract, target_key_id,
                   target_provider_key_version, target_wrapping_algorithm, target_context_schema,
                   target_logical_key_version, batch_size, max_items_per_minute, max_attempts,
                   initial_backoff_seconds, max_backoff_seconds, lease_seconds, grace_period_seconds,
                   snapshot_max_envelope_id, scan_cursor_id, discovery_complete, total_count,
                   pending_count, running_count, succeeded_count, skipped_count, failed_count,
                   remaining_count, rate_window_started_at, rate_window_count,
                   created_by, started_at, completed_at, retirement_status,
                   retirement_eligible_at, last_error_category, last_error_class,
                   create_time, update_time, deleted
            FROM key_rotation_run
            WHERE tenant_id = #{tenantId}
              AND id = #{runId}
              AND deleted = 0
            FOR UPDATE
            """)
    KeyRotationRun selectRunForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("runId") Long runId);

    /**
     * Recomputes bounded counters from item facts without trusting worker-local totals.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE key_rotation_run r
            SET total_count = (
                    SELECT COUNT(*) FROM key_rotation_item i
                    WHERE i.tenant_id = #{tenantId} AND i.run_id = #{runId} AND i.deleted = 0),
                pending_count = (
                    SELECT COUNT(*) FROM key_rotation_item i
                    WHERE i.tenant_id = #{tenantId} AND i.run_id = #{runId}
                      AND i.status = 'PENDING' AND i.deleted = 0),
                running_count = (
                    SELECT COUNT(*) FROM key_rotation_item i
                    WHERE i.tenant_id = #{tenantId} AND i.run_id = #{runId}
                      AND i.status = 'RUNNING' AND i.deleted = 0),
                succeeded_count = (
                    SELECT COUNT(*) FROM key_rotation_item i
                    WHERE i.tenant_id = #{tenantId} AND i.run_id = #{runId}
                      AND i.status = 'SUCCEEDED' AND i.deleted = 0),
                skipped_count = (
                    SELECT COUNT(*) FROM key_rotation_item i
                    WHERE i.tenant_id = #{tenantId} AND i.run_id = #{runId}
                      AND i.status = 'SKIPPED' AND i.deleted = 0),
                failed_count = (
                    SELECT COUNT(*) FROM key_rotation_item i
                    WHERE i.tenant_id = #{tenantId} AND i.run_id = #{runId}
                      AND i.status = 'FAILED' AND i.deleted = 0),
                remaining_count = (
                    SELECT COUNT(*) FROM key_rotation_item i
                    WHERE i.tenant_id = #{tenantId} AND i.run_id = #{runId}
                      AND i.status NOT IN ('SUCCEEDED', 'SKIPPED') AND i.deleted = 0),
                last_error_category = (
                    SELECT i.failure_category FROM key_rotation_item i
                    WHERE i.tenant_id = #{tenantId} AND i.run_id = #{runId}
                      AND i.status = 'FAILED' AND i.deleted = 0
                    ORDER BY i.update_time DESC, i.id DESC LIMIT 1),
                last_error_class = (
                    SELECT i.last_error_class FROM key_rotation_item i
                    WHERE i.tenant_id = #{tenantId} AND i.run_id = #{runId}
                      AND i.status = 'FAILED' AND i.deleted = 0
                    ORDER BY i.update_time DESC, i.id DESC LIMIT 1),
                update_time = NOW()
            WHERE r.tenant_id = #{tenantId}
              AND r.id = #{runId}
              AND r.deleted = 0
            """)
    int refreshCounts(@Param("tenantId") Long tenantId, @Param("runId") Long runId);
}
