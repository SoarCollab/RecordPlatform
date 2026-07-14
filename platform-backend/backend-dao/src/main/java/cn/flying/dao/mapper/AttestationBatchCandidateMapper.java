package cn.flying.dao.mapper;

import cn.flying.dao.entity.AttestationBatchCandidate;
import cn.flying.dao.vo.attestation.AttestationBatchCandidateStats;
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
 * 生产 Merkle batch 候选的租户内发现、领取和终结 Mapper。
 */
@Mapper
public interface AttestationBatchCandidateMapper extends BaseMapper<AttestationBatchCandidate> {

    /**
     * 从已成功文件和当前版本 active manifest 中发现尚未入批的候选源。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT f.tenant_id AS tenant_id,
                   f.id AS file_id,
                   COALESCE(f.version, 1) AS file_version,
                   manifest_set.manifest_id AS manifest_id,
                   manifest_set.active_manifest_count AS active_manifest_count,
                   manifest.manifest_hash AS evidence_hash,
                   f.file_hash AS chain_record_id
            FROM file f
            INNER JOIN (
                SELECT tenant_id,
                       file_id,
                       file_version,
                       MAX(id) AS manifest_id,
                       COUNT(*) AS active_manifest_count
                FROM file_chunk_manifest
                WHERE status = 'ACTIVE'
                  AND deleted = 0
                  AND tenant_id = #{tenantId}
                GROUP BY tenant_id, file_id, file_version
            ) manifest_set
                    ON manifest_set.tenant_id = f.tenant_id
                   AND manifest_set.file_id = f.id
                   AND manifest_set.file_version = COALESCE(f.version, 1)
            INNER JOIN file_chunk_manifest manifest
                    ON manifest.id = manifest_set.manifest_id
                   AND manifest.tenant_id = f.tenant_id
                   AND manifest.file_id = f.id
                   AND manifest.deleted = 0
            WHERE f.tenant_id = #{tenantId}
              AND f.status = #{successStatus}
              AND f.deleted = 0
              AND NOT EXISTS (
                    SELECT 1
                    FROM attestation_leaf leaf
                    WHERE leaf.tenant_id = f.tenant_id
                      AND leaf.file_id = f.id
                      AND leaf.file_version = COALESCE(f.version, 1)
                      AND leaf.deleted = 0
              )
              AND NOT EXISTS (
                    SELECT 1
                    FROM attestation_batch_candidate candidate
                    WHERE candidate.tenant_id = f.tenant_id
                      AND candidate.file_id = f.id
                      AND candidate.file_version = COALESCE(f.version, 1)
                      AND candidate.deleted = 0
              )
            ORDER BY f.create_time ASC, f.id ASC
            LIMIT #{limit}
            """)
    List<AttestationBatchCandidate> selectEligibleSources(
            @Param("tenantId") Long tenantId,
            @Param("successStatus") int successStatus,
            @Param("limit") int limit
    );

    /**
     * 批量插入候选，并由租户/文件/版本唯一键收敛并发发现。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert({
            "<script>",
            "INSERT IGNORE INTO attestation_batch_candidate (",
            "id, tenant_id, file_id, file_version, manifest_id, evidence_type, evidence_hash,",
            "chain_record_id, status, attempt_count, last_error, eligible_at, deleted",
            ") VALUES",
            "<foreach collection='candidates' item='candidate' separator=','>",
            "(#{candidate.id}, #{candidate.tenantId}, #{candidate.fileId}, #{candidate.fileVersion},",
            "#{candidate.manifestId}, #{candidate.evidenceType}, #{candidate.evidenceHash},",
            "#{candidate.chainRecordId}, #{candidate.status}, #{candidate.attemptCount},",
            "#{candidate.lastError}, #{candidate.eligibleAt}, #{candidate.deleted})",
            "</foreach>",
            "</script>"
    })
    int insertIgnoreBatch(@Param("candidates") List<AttestationBatchCandidate> candidates);

    /**
     * 把租约到期且已耗尽处理次数的候选转入 dead-letter。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE attestation_batch_candidate
            SET status = 'DEAD_LETTER',
                claim_token = NULL,
                lease_expires_at = NULL,
                last_error = 'Candidate claim lease expired after maximum attempts',
                update_time = NOW()
            WHERE tenant_id = #{tenantId}
              AND status = 'CLAIMED'
              AND lease_expires_at IS NOT NULL
              AND lease_expires_at <= #{now}
              AND attempt_count >= #{maxAttempts}
              AND deleted = 0
            """)
    int markExpiredExhausted(
            @Param("tenantId") Long tenantId,
            @Param("now") Date now,
            @Param("maxAttempts") int maxAttempts
    );

    /**
     * 把尚未耗尽次数的过期 claim 恢复为 READY，供新 worker 重新领取。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE attestation_batch_candidate
            SET status = 'READY',
                claim_token = NULL,
                lease_expires_at = NULL,
                last_error = 'Candidate claim lease expired before batch creation',
                update_time = NOW()
            WHERE tenant_id = #{tenantId}
              AND status = 'CLAIMED'
              AND lease_expires_at IS NOT NULL
              AND lease_expires_at <= #{now}
              AND attempt_count < #{maxAttempts}
              AND deleted = 0
            """)
    int releaseExpiredRetryable(
            @Param("tenantId") Long tenantId,
            @Param("now") Date now,
            @Param("maxAttempts") int maxAttempts
    );

    /**
     * 锁定一页 READY 或租约过期的候选，跳过其他 worker 已持有的行锁。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, tenant_id, file_id, file_version, manifest_id, evidence_type,
                   evidence_hash, chain_record_id, status, batch_id, claim_token,
                   lease_expires_at, attempt_count, last_error, eligible_at, batched_at,
                   create_time, update_time, deleted
            FROM attestation_batch_candidate
            WHERE tenant_id = #{tenantId}
              AND deleted = 0
              AND attempt_count < #{maxAttempts}
              AND (
                    status = 'READY'
                    OR (status = 'CLAIMED' AND lease_expires_at IS NOT NULL AND lease_expires_at <= #{now})
              )
            ORDER BY eligible_at ASC, id ASC
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<AttestationBatchCandidate> selectClaimableForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("now") Date now,
            @Param("maxAttempts") int maxAttempts,
            @Param("limit") int limit
    );

    /**
     * 使用一次批量条件更新为所选候选设置 claim token 和租约。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update({
            "<script>",
            "UPDATE attestation_batch_candidate",
            "SET status = 'CLAIMED', claim_token = #{claimToken}, lease_expires_at = #{leaseExpiresAt},",
            "attempt_count = attempt_count + 1, last_error = NULL, update_time = NOW()",
            "WHERE tenant_id = #{tenantId} AND deleted = 0",
            "AND (status = 'READY' OR (status = 'CLAIMED' AND lease_expires_at IS NOT NULL AND lease_expires_at &lt;= #{now}))",
            "AND id IN",
            "<foreach collection='candidateIds' item='candidateId' open='(' separator=',' close=')'>",
            "#{candidateId}",
            "</foreach>",
            "</script>"
    })
    int claimSelected(
            @Param("tenantId") Long tenantId,
            @Param("candidateIds") List<Long> candidateIds,
            @Param("claimToken") String claimToken,
            @Param("now") Date now,
            @Param("leaseExpiresAt") Date leaseExpiresAt
    );

    /**
     * 读取一个 token 当前仍持有的完整候选快照。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, tenant_id, file_id, file_version, manifest_id, evidence_type,
                   evidence_hash, chain_record_id, status, batch_id, claim_token,
                   lease_expires_at, attempt_count, last_error, eligible_at, batched_at,
                   create_time, update_time, deleted
            FROM attestation_batch_candidate
            WHERE tenant_id = #{tenantId}
              AND claim_token = #{claimToken}
              AND status = 'CLAIMED'
              AND deleted = 0
            ORDER BY eligible_at ASC, id ASC
            """)
    List<AttestationBatchCandidate> selectClaimedByToken(
            @Param("tenantId") Long tenantId,
            @Param("claimToken") String claimToken
    );

    /**
     * 把当前 token 持有的所有候选原子绑定到新 batch。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE attestation_batch_candidate
            SET status = 'BATCHED',
                batch_id = #{batchId},
                claim_token = NULL,
                lease_expires_at = NULL,
                last_error = NULL,
                batched_at = NOW(),
                update_time = NOW()
            WHERE tenant_id = #{tenantId}
              AND claim_token = #{claimToken}
              AND status = 'CLAIMED'
              AND lease_expires_at IS NOT NULL
              AND lease_expires_at > NOW()
              AND deleted = 0
            """)
    int markBatched(
            @Param("tenantId") Long tenantId,
            @Param("claimToken") String claimToken,
            @Param("batchId") Long batchId
    );

    /**
     * 创建失败时释放当前 claim，达到上限的候选转入 dead-letter。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE attestation_batch_candidate
            SET status = CASE WHEN attempt_count >= #{maxAttempts} THEN 'DEAD_LETTER' ELSE 'READY' END,
                claim_token = NULL,
                lease_expires_at = NULL,
                last_error = #{errorMessage},
                update_time = NOW()
            WHERE tenant_id = #{tenantId}
              AND claim_token = #{claimToken}
              AND status = 'CLAIMED'
              AND deleted = 0
            """)
    int releaseClaim(
            @Param("tenantId") Long tenantId,
            @Param("claimToken") String claimToken,
            @Param("maxAttempts") int maxAttempts,
            @Param("errorMessage") String errorMessage
    );

    /**
     * 聚合当前租户 candidate backlog 和终态数量。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT COALESCE(SUM(CASE WHEN status = 'READY' THEN 1 ELSE 0 END), 0) AS ready_count,
                   COALESCE(SUM(CASE WHEN status = 'CLAIMED' THEN 1 ELSE 0 END), 0) AS claimed_count,
                   COALESCE(SUM(CASE WHEN status = 'BATCHED' THEN 1 ELSE 0 END), 0) AS batched_count,
                   COALESCE(SUM(CASE WHEN status = 'DEAD_LETTER' THEN 1 ELSE 0 END), 0) AS dead_letter_count,
                   MIN(CASE WHEN status = 'READY' THEN eligible_at ELSE NULL END) AS oldest_ready_at
            FROM attestation_batch_candidate
            WHERE tenant_id = #{tenantId}
              AND deleted = 0
            """)
    AttestationBatchCandidateStats selectStats(@Param("tenantId") Long tenantId);
}
