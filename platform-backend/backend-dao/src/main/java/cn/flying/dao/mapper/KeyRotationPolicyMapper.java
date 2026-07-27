package cn.flying.dao.mapper;

import cn.flying.dao.entity.KeyRotationPolicy;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;

/**
 * Mapper for tenant rotation policy state and schedule serialization.
 */
@Mapper
public interface KeyRotationPolicyMapper extends BaseMapper<KeyRotationPolicy> {

    /**
     * Selects only active-tenant policies with due schedule, runnable work, or elapsed retirement grace.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT p.tenant_id
            FROM key_rotation_policy p
            INNER JOIN tenant t
                    ON t.id = p.tenant_id
                   AND t.status = 1
                   AND t.deleted = 0
            LEFT JOIN key_rotation_run r
                   ON r.policy_id = p.id
                  AND r.tenant_id = p.tenant_id
                  AND r.deleted = 0
            WHERE p.deleted = 0
              AND (
                    (p.status = 'ACTIVE' AND p.schedule_enabled = 1
                        AND p.next_run_at IS NOT NULL AND p.next_run_at <= #{now})
                    OR r.status IN ('PLANNED', 'RUNNING')
                    OR (r.id = p.last_run_id AND r.status = 'COMPLETED'
                        AND r.mode = 'APPLY' AND r.retirement_status = 'NOT_READY'
                        AND r.retirement_eligible_at IS NOT NULL
                        AND r.retirement_eligible_at <= #{now})
              )
            GROUP BY p.tenant_id
            ORDER BY MIN(CASE
                    WHEN r.status IN ('PLANNED', 'RUNNING') THEN r.update_time
                    WHEN r.id = p.last_run_id AND r.retirement_eligible_at IS NOT NULL
                        THEN r.retirement_eligible_at
                    ELSE p.next_run_at
                END) ASC,
                p.tenant_id ASC
            LIMIT #{limit}
            """)
    List<Long> selectWorkTenantIds(@Param("now") Date now, @Param("limit") int limit);

    /**
     * Locks the single tenant policy before mutation or scheduled triggering.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, tenant_id, status, target_provider, target_provider_contract,
                   target_key_id, target_provider_key_version, target_wrapping_algorithm,
                   target_context_schema, target_logical_key_version, batch_size,
                   max_items_per_minute, schedule_enabled, schedule_interval_seconds,
                   next_run_at, max_attempts, initial_backoff_seconds, max_backoff_seconds,
                   lease_seconds, grace_period_seconds, policy_version, created_by, updated_by,
                   last_run_id, retirement_status, retirement_eligible_at,
                   retirement_acknowledged_at, create_time, update_time, deleted
            FROM key_rotation_policy
            WHERE tenant_id = #{tenantId}
              AND deleted = 0
            FOR UPDATE
            """)
    KeyRotationPolicy selectTenantPolicyForUpdate(@Param("tenantId") Long tenantId);
}
