package cn.flying.dao.mapper;

import cn.flying.dao.entity.FileKeyEnvelope;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Mapper for file_key_envelope table.
 */
@Mapper
public interface FileKeyEnvelopeMapper extends BaseMapper<FileKeyEnvelope> {

    /**
     * Returns the upper envelope boundary used to freeze one tenant run.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT COALESCE(MAX(id), 0)
            FROM file_key_envelope
            WHERE tenant_id = #{tenantId}
              AND deleted = 0
            """)
    Long selectMaxEnvelopeId(@Param("tenantId") Long tenantId);

    /**
     * Discovers a bounded active-recipient page inside the immutable run boundary.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, tenant_id, file_id, file_hash, recipient_type, recipient_id,
                   key_version, algorithm_suite, signature_suite, kem_suite, proof_suite,
                   encryption_algorithm, wrapping_algorithm, kms_provider,
                   provider_contract_version, kms_key_id, provider_key_version,
                   context_schema, encrypted_data_key, wrapping_iv, aad_hash, status,
                   deprecated_after, create_time, update_time, deleted
            FROM file_key_envelope
            WHERE tenant_id = #{tenantId}
              AND id > #{afterId}
              AND id <= #{maxId}
              AND status = 'ACTIVE'
              AND recipient_type IN ('OWNER', 'SHARE', 'FRIEND_SHARE')
              AND deleted = 0
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    List<FileKeyEnvelope> selectRotationCandidatePage(
            @Param("tenantId") Long tenantId,
            @Param("afterId") Long afterId,
            @Param("maxId") Long maxId,
            @Param("limit") int limit);

    /**
     * Locks one source envelope before its final authority transition.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, tenant_id, file_id, file_hash, recipient_type, recipient_id,
                   key_version, algorithm_suite, signature_suite, kem_suite, proof_suite,
                   encryption_algorithm, wrapping_algorithm, kms_provider,
                   provider_contract_version, kms_key_id, provider_key_version,
                   context_schema, encrypted_data_key, wrapping_iv, aad_hash, status,
                   deprecated_after, create_time, update_time, deleted
            FROM file_key_envelope
            WHERE tenant_id = #{tenantId}
              AND id = #{envelopeId}
              AND deleted = 0
            FOR UPDATE
            """)
    FileKeyEnvelope selectEnvelopeForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("envelopeId") Long envelopeId);

    /**
     * Locks the currently readable row for one recipient before revocation.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, tenant_id, file_id, file_hash, recipient_type, recipient_id,
                   key_version, algorithm_suite, signature_suite, kem_suite, proof_suite,
                   encryption_algorithm, wrapping_algorithm, kms_provider,
                   provider_contract_version, kms_key_id, provider_key_version,
                   context_schema, encrypted_data_key, wrapping_iv, aad_hash, status,
                   deprecated_after, create_time, update_time, deleted
            FROM file_key_envelope
            WHERE tenant_id = #{tenantId}
              AND recipient_type = #{recipientType}
              AND recipient_id = #{recipientId}
              AND status = 'ACTIVE'
              AND deleted = 0
            ORDER BY id ASC
            FOR UPDATE
            """)
    List<FileKeyEnvelope> selectActiveRecipientForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("recipientType") String recipientType,
            @Param("recipientId") Long recipientId);

    /**
     * Moves one exact lifecycle state with a tenant/id/status CAS fence.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE file_key_envelope
            SET status = #{targetStatus}, update_time = NOW()
            WHERE tenant_id = #{tenantId}
              AND id = #{envelopeId}
              AND status = #{expectedStatus}
              AND deleted = 0
            """)
    int compareAndSetStatus(
            @Param("tenantId") Long tenantId,
            @Param("envelopeId") Long envelopeId,
            @Param("expectedStatus") String expectedStatus,
            @Param("targetStatus") String targetStatus);
}
