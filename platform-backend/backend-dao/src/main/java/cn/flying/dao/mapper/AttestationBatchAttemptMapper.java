package cn.flying.dao.mapper;

import cn.flying.dao.entity.AttestationBatchAttempt;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * Mapper for Merkle attestation batch submission attempts.
 */
@Mapper
public interface AttestationBatchAttemptMapper extends BaseMapper<AttestationBatchAttempt> {

    /**
     * 记录当前 claim 的最终处理结果。
     */
    @Update("""
            UPDATE attestation_batch_attempt
            SET status = #{status},
                confirmation_source = #{confirmationSource},
                transaction_hash = #{transactionHash},
                chain_root = #{chainRoot},
                error_message = #{errorMessage},
                update_time = NOW()
            WHERE tenant_id = #{tenantId}
              AND batch_id = #{batchId}
              AND claim_token = #{claimToken}
              AND status = 'CLAIMED'
            """)
    int updateResult(
            @Param("tenantId") Long tenantId,
            @Param("batchId") Long batchId,
            @Param("claimToken") String claimToken,
            @Param("status") String status,
            @Param("confirmationSource") String confirmationSource,
            @Param("transactionHash") String transactionHash,
            @Param("chainRoot") String chainRoot,
            @Param("errorMessage") String errorMessage
    );
}
