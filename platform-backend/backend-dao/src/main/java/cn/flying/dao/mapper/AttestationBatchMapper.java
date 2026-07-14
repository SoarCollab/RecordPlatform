package cn.flying.dao.mapper;

import cn.flying.dao.entity.AttestationBatch;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;

/**
 * Mapper for Merkle attestation batches.
 */
@Mapper
public interface AttestationBatchMapper extends BaseMapper<AttestationBatch> {

    /**
     * 按稳定顺序读取当前租户可恢复提交的批次 ID。
     */
    @Select("""
            SELECT id
            FROM attestation_batch
            WHERE tenant_id = #{tenantId}
              AND deleted = 0
              AND (
                    (status = 'CHAIN_PENDING' AND attempt_count < #{maxAttempts})
                 OR (status = 'CHAIN_RETRY' AND attempt_count < #{maxAttempts}
                     AND next_attempt_at IS NOT NULL AND next_attempt_at <= #{now})
                 OR (status = 'CHAIN_SUBMITTING' AND attempt_count <= #{maxAttempts}
                     AND lease_expires_at IS NOT NULL AND lease_expires_at <= #{now})
              )
            ORDER BY COALESCE(next_attempt_at, create_time) ASC, id ASC
            LIMIT #{limit}
            """)
    List<Long> selectDueBatchIds(
            @Param("tenantId") Long tenantId,
            @Param("now") Date now,
            @Param("maxAttempts") int maxAttempts,
            @Param("limit") int limit
    );

    /**
     * 统计当前租户可恢复提交的批次数量。
     */
    @Select("""
            SELECT COUNT(*)
            FROM attestation_batch
            WHERE tenant_id = #{tenantId}
              AND deleted = 0
              AND (
                    (status = 'CHAIN_PENDING' AND attempt_count < #{maxAttempts})
                 OR (status = 'CHAIN_RETRY' AND attempt_count < #{maxAttempts}
                     AND next_attempt_at IS NOT NULL AND next_attempt_at <= #{now})
                 OR (status = 'CHAIN_SUBMITTING' AND attempt_count <= #{maxAttempts}
                     AND lease_expires_at IS NOT NULL AND lease_expires_at <= #{now})
              )
            """)
    long countDueBatches(
            @Param("tenantId") Long tenantId,
            @Param("now") Date now,
            @Param("maxAttempts") int maxAttempts
    );

    /**
     * 原子领取一个可提交或租约已过期的批次。
     */
    @Update("""
            UPDATE attestation_batch
            SET status = 'CHAIN_SUBMITTING',
                claim_token = #{claimToken},
                lease_expires_at = #{leaseExpiresAt},
                next_attempt_at = NULL,
                attempt_count = attempt_count + 1,
                state_version = state_version + 1,
                chain_error = NULL,
                update_time = NOW()
            WHERE id = #{batchId}
              AND tenant_id = #{tenantId}
              AND deleted = 0
              AND (
                    (status = 'CHAIN_PENDING' AND attempt_count < #{maxAttempts})
                 OR (status = 'CHAIN_RETRY' AND attempt_count < #{maxAttempts}
                     AND next_attempt_at IS NOT NULL AND next_attempt_at <= #{now})
                 OR (status = 'CHAIN_SUBMITTING' AND attempt_count <= #{maxAttempts}
                     AND lease_expires_at IS NOT NULL AND lease_expires_at <= #{now})
              )
            """)
    int claimForSubmission(
            @Param("tenantId") Long tenantId,
            @Param("batchId") Long batchId,
            @Param("claimToken") String claimToken,
            @Param("now") Date now,
            @Param("leaseExpiresAt") Date leaseExpiresAt,
            @Param("maxAttempts") int maxAttempts
    );

    /**
     * 在当前 claim 内重新确认批次的不可变注册表仍等于 provider 当前 ACTIVE 条目。
     */
    @Update("""
            UPDATE attestation_batch
            SET state_version = state_version + 1,
                update_time = NOW()
            WHERE id = #{batchId}
              AND tenant_id = #{tenantId}
              AND status = 'CHAIN_SUBMITTING'
              AND claim_token = #{claimToken}
              AND contract_registry_fingerprint = #{registryFingerprint}
              AND contract_registry_json IS NOT NULL
              AND deleted = 0
            """)
    int verifyContractRegistryClaim(
            @Param("tenantId") Long tenantId,
            @Param("batchId") Long batchId,
            @Param("claimToken") String claimToken,
            @Param("registryFingerprint") String registryFingerprint
    );

    /**
     * 仅允许当前有效 claim 将批次确认成兼容 proof 的完成终态。
     */
    @Update("""
            UPDATE attestation_batch
            SET status = 'COMPLETED',
                chain_transaction_hash = #{transactionHash},
                chain_file_hash = #{chainRoot},
                chain_error = NULL,
                next_attempt_at = NULL,
                claim_token = NULL,
                lease_expires_at = NULL,
                confirmation_source = #{confirmationSource},
                state_version = state_version + 1,
                update_time = NOW()
            WHERE id = #{batchId}
              AND tenant_id = #{tenantId}
              AND status = 'CHAIN_SUBMITTING'
              AND claim_token = #{claimToken}
              AND LOWER(merkle_root) = LOWER(#{chainRoot})
              AND deleted = 0
            """)
    int confirmSubmission(
            @Param("tenantId") Long tenantId,
            @Param("batchId") Long batchId,
            @Param("claimToken") String claimToken,
            @Param("transactionHash") String transactionHash,
            @Param("chainRoot") String chainRoot,
            @Param("confirmationSource") String confirmationSource
    );

    /**
     * 仅允许当前有效 claim 安排下一次退避重试。
     */
    @Update("""
            UPDATE attestation_batch
            SET status = 'CHAIN_RETRY',
                chain_error = #{errorMessage},
                next_attempt_at = #{nextAttemptAt},
                claim_token = NULL,
                lease_expires_at = NULL,
                confirmation_source = NULL,
                state_version = state_version + 1,
                update_time = NOW()
            WHERE id = #{batchId}
              AND tenant_id = #{tenantId}
              AND status = 'CHAIN_SUBMITTING'
              AND claim_token = #{claimToken}
              AND deleted = 0
            """)
    int scheduleRetry(
            @Param("tenantId") Long tenantId,
            @Param("batchId") Long batchId,
            @Param("claimToken") String claimToken,
            @Param("errorMessage") String errorMessage,
            @Param("nextAttemptAt") Date nextAttemptAt
    );

    /**
     * 仅允许当前有效 claim 把不可自动恢复的批次置为人工处理终态。
     */
    @Update("""
            UPDATE attestation_batch
            SET status = 'MANUAL_REVIEW',
                chain_error = #{errorMessage},
                next_attempt_at = NULL,
                claim_token = NULL,
                lease_expires_at = NULL,
                confirmation_source = #{confirmationSource},
                state_version = state_version + 1,
                update_time = NOW()
            WHERE id = #{batchId}
              AND tenant_id = #{tenantId}
              AND status = 'CHAIN_SUBMITTING'
              AND claim_token = #{claimToken}
              AND deleted = 0
            """)
    int markManualReview(
            @Param("tenantId") Long tenantId,
            @Param("batchId") Long batchId,
            @Param("claimToken") String claimToken,
            @Param("errorMessage") String errorMessage,
            @Param("confirmationSource") String confirmationSource
    );
}
