package cn.flying.dao.mapper;

import cn.flying.dao.entity.ManifestBackfillRun;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Date;

/**
 * Mapper for durable manifest backfill runs.
 */
@Mapper
public interface ManifestBackfillRunMapper extends BaseMapper<ManifestBackfillRun> {

    /**
     * Selects runnable cross-tenant work without trusting an HTTP tenant hint.
     *
     * @param staleBefore running lease cutoff used for crash recovery
     * @param limit maximum run count
     * @return planned runs in stable order
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, tenant_id, snapshot_run_id, mode, status, snapshot_version,
                   snapshot_digest, cursor_file_id, created_by, total_count, pending_count,
                   backfilled_count, reupload_count, unrecoverable_count, ignored_count,
                   failed_count, last_error_class, started_at, completed_at,
                   create_time, update_time, deleted
            FROM manifest_backfill_run
            WHERE (
                    status = 'PLANNED'
                    OR (status IN ('SCANNING', 'APPLYING') AND update_time <= #{staleBefore})
                  )
              AND deleted = 0
            ORDER BY create_time ASC, id ASC
            LIMIT #{limit}
            """)
    List<ManifestBackfillRun> selectRunnableRuns(
            @Param("staleBefore") Date staleBefore,
            @Param("limit") int limit);

    /**
     * Claims a planned run for one worker by changing its phase atomically.
     *
     * @param runId run ID
     * @param runningStatus SCANNING or APPLYING
     * @param staleBefore running lease cutoff used for crash recovery
     * @return affected rows
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE manifest_backfill_run
            SET status = #{runningStatus},
                started_at = COALESCE(started_at, NOW()),
                last_error_class = NULL,
                update_time = NOW()
            WHERE id = #{runId}
              AND (
                    status = 'PLANNED'
                    OR (status = #{runningStatus} AND update_time <= #{staleBefore})
                  )
              AND deleted = 0
            """)
    int claimPlannedRun(
            @Param("runId") Long runId,
            @Param("runningStatus") String runningStatus,
            @Param("staleBefore") Date staleBefore);

    /**
     * Refreshes a running lease without changing the run phase.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE manifest_backfill_run
            SET update_time = NOW()
            WHERE id = #{runId}
              AND tenant_id = #{tenantId}
              AND status = #{runningStatus}
              AND deleted = 0
            """)
    int touchRunLease(
            @Param("runId") Long runId,
            @Param("tenantId") Long tenantId,
            @Param("runningStatus") String runningStatus);

    /**
     * Advances a scan cursor monotonically so a recovered worker cannot move it backwards.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE manifest_backfill_run
            SET cursor_file_id = GREATEST(COALESCE(cursor_file_id, 0), #{cursorFileId}),
                update_time = NOW()
            WHERE id = #{runId}
              AND tenant_id = #{tenantId}
              AND status = 'SCANNING'
              AND deleted = 0
            """)
    int advanceScanCursor(
            @Param("runId") Long runId,
            @Param("tenantId") Long tenantId,
            @Param("cursorFileId") Long cursorFileId);

    /**
     * Loads one run irrespective of the caller's ambient tenant interceptor.
     *
     * @param runId run ID
     * @return run row or null
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, tenant_id, snapshot_run_id, mode, status, snapshot_version,
                   snapshot_digest, cursor_file_id, created_by, total_count, pending_count,
                   backfilled_count, reupload_count, unrecoverable_count, ignored_count,
                   failed_count, last_error_class, started_at, completed_at,
                   create_time, update_time, deleted
            FROM manifest_backfill_run
            WHERE id = #{runId}
              AND deleted = 0
            """)
    ManifestBackfillRun selectRunGlobally(@Param("runId") Long runId);

    /**
     * Lists the newest bounded run set for one authenticated tenant.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, tenant_id, snapshot_run_id, mode, status, snapshot_version,
                   snapshot_digest, cursor_file_id, created_by, total_count, pending_count,
                   backfilled_count, reupload_count, unrecoverable_count, ignored_count,
                   failed_count, last_error_class, started_at, completed_at,
                   create_time, update_time, deleted
            FROM manifest_backfill_run
            WHERE tenant_id = #{tenantId}
              AND deleted = 0
            ORDER BY create_time DESC, id DESC
            LIMIT #{limit}
            """)
    List<ManifestBackfillRun> selectTenantRuns(
            @Param("tenantId") Long tenantId,
            @Param("limit") int limit);
}
