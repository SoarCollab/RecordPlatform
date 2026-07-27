package cn.flying.dao.mapper;

import cn.flying.dao.entity.KeyRotationItem;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;

/**
 * Mapper for idempotent discovery and claim-token fenced rotation work.
 */
@Mapper
public interface KeyRotationItemMapper extends BaseMapper<KeyRotationItem> {

    /**
     * Inserts one discovered source once for the owning run.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT IGNORE INTO key_rotation_item (
                id, tenant_id, run_id, source_envelope_id, candidate_envelope_id,
                file_id, recipient_type, recipient_id, status, outcome, retryable,
                attempt_count, claim_token, lease_expires_at, next_retry_at,
                failure_category, last_error_class, deleted
            ) VALUES (
                #{item.id}, #{item.tenantId}, #{item.runId}, #{item.sourceEnvelopeId},
                #{item.candidateEnvelopeId}, #{item.fileId}, #{item.recipientType},
                #{item.recipientId}, #{item.status}, #{item.outcome}, #{item.retryable},
                #{item.attemptCount}, #{item.claimToken}, #{item.leaseExpiresAt},
                #{item.nextRetryAt}, #{item.failureCategory}, #{item.lastErrorClass}, #{item.deleted}
            )
            """)
    int insertIgnore(@Param("item") KeyRotationItem item);

    /**
     * Locks a bounded claim page in claim-index order so SKIP LOCKED workers do not
     * filesort and lock the same page.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, tenant_id, run_id, source_envelope_id, candidate_envelope_id,
                   file_id, recipient_type, recipient_id, status, outcome, retryable,
                   attempt_count, claim_token, lease_expires_at, next_retry_at,
                   failure_category, last_error_class, create_time, update_time, deleted
            FROM key_rotation_item FORCE INDEX (idx_key_rotation_item_claim)
            WHERE tenant_id = #{tenantId}
              AND run_id = #{runId}
              AND deleted = 0
              AND attempt_count < #{maxAttempts}
              AND (
                    status = 'PENDING'
                    OR (status = 'FAILED' AND retryable = 1
                        AND (next_retry_at IS NULL OR next_retry_at <= #{now}))
                    OR (status = 'RUNNING' AND lease_expires_at IS NOT NULL
                        AND lease_expires_at <= #{now})
              )
            ORDER BY status ASC, retryable ASC, next_retry_at ASC,
                     lease_expires_at ASC, id ASC
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<KeyRotationItem> selectClaimableForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("runId") Long runId,
            @Param("now") Date now,
            @Param("maxAttempts") int maxAttempts,
            @Param("limit") int limit);

    /**
     * Claims the already locked item IDs with one random ownership token.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update({
            "<script>",
            "UPDATE key_rotation_item",
            "SET status = 'RUNNING', claim_token = #{claimToken}, lease_expires_at = #{leaseExpiresAt},",
            "attempt_count = attempt_count + 1, next_retry_at = NULL, failure_category = NULL,",
            "last_error_class = NULL, update_time = NOW()",
            "WHERE tenant_id = #{tenantId} AND run_id = #{runId} AND deleted = 0",
            "AND attempt_count &lt; #{maxAttempts}",
            "AND (status = 'PENDING' OR (status = 'FAILED' AND retryable = 1",
            "AND (next_retry_at IS NULL OR next_retry_at &lt;= #{now}))",
            "OR (status = 'RUNNING' AND lease_expires_at IS NOT NULL AND lease_expires_at &lt;= #{now}))",
            "AND id IN",
            "<foreach collection='itemIds' item='itemId' open='(' separator=',' close=')'>",
            "#{itemId}",
            "</foreach>",
            "</script>"
    })
    int claimSelected(
            @Param("tenantId") Long tenantId,
            @Param("runId") Long runId,
            @Param("itemIds") List<Long> itemIds,
            @Param("claimToken") String claimToken,
            @Param("now") Date now,
            @Param("leaseExpiresAt") Date leaseExpiresAt,
            @Param("maxAttempts") int maxAttempts);

    /**
     * Completes or reschedules a claim only while its unexpired token owns the row.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE key_rotation_item
            SET status = #{status}, outcome = #{outcome}, retryable = #{retryable},
                candidate_envelope_id = COALESCE(#{candidateEnvelopeId}, candidate_envelope_id),
                next_retry_at = #{nextRetryAt}, failure_category = #{failureCategory},
                last_error_class = #{lastErrorClass}, claim_token = NULL,
                lease_expires_at = NULL, update_time = NOW()
            WHERE tenant_id = #{tenantId}
              AND run_id = #{runId}
              AND id = #{itemId}
              AND claim_token = #{claimToken}
              AND status = 'RUNNING'
              AND lease_expires_at > NOW()
              AND deleted = 0
            """)
    int completeClaim(
            @Param("tenantId") Long tenantId,
            @Param("runId") Long runId,
            @Param("itemId") Long itemId,
            @Param("claimToken") String claimToken,
            @Param("status") String status,
            @Param("outcome") String outcome,
            @Param("retryable") int retryable,
            @Param("candidateEnvelopeId") Long candidateEnvelopeId,
            @Param("nextRetryAt") Date nextRetryAt,
            @Param("failureCategory") String failureCategory,
            @Param("lastErrorClass") String lastErrorClass);

    /**
     * Renews one unexpired claim before a potentially remote provider call.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE key_rotation_item
            SET lease_expires_at = #{leaseExpiresAt}, update_time = NOW()
            WHERE tenant_id = #{tenantId}
              AND run_id = #{runId}
              AND id = #{itemId}
              AND claim_token = #{claimToken}
              AND status = 'RUNNING'
              AND lease_expires_at > NOW()
              AND deleted = 0
            """)
    int renewLease(
            @Param("tenantId") Long tenantId,
            @Param("runId") Long runId,
            @Param("itemId") Long itemId,
            @Param("claimToken") String claimToken,
            @Param("leaseExpiresAt") Date leaseExpiresAt);

    /**
     * Requeues provider-classified retryable terminal failures for an explicit administrator retry.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE key_rotation_item
            SET status = 'PENDING', outcome = NULL, retryable = 0,
                attempt_count = 0,
                next_retry_at = NULL, failure_category = NULL, last_error_class = NULL,
                claim_token = NULL, lease_expires_at = NULL, update_time = NOW()
            WHERE tenant_id = #{tenantId}
              AND run_id = #{runId}
              AND status = 'FAILED'
              AND retryable = 1
              AND deleted = 0
            """)
    int retryFailed(@Param("tenantId") Long tenantId, @Param("runId") Long runId);
}
