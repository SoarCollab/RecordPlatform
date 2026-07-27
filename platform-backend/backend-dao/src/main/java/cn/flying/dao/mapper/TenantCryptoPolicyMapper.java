package cn.flying.dao.mapper;

import cn.flying.dao.entity.TenantCryptoPolicy;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Tenant-isolated persistence boundary for runtime crypto policy.
 */
@Mapper
public interface TenantCryptoPolicyMapper extends BaseMapper<TenantCryptoPolicy> {

    /**
     * Locks the current tenant policy before an optimistic update.
     */
    @Select("""
            SELECT id, tenant_id, content_encryption_suite, envelope_signature_suite,
                   kem_suite, proof_suite, wrapping_provider, wrapping_provider_contract,
                   signed_proof_signature_suite, signed_proof_suite,
                   signing_provider, signing_provider_contract, policy_version,
                   created_by, updated_by, create_time, update_time, deleted
              FROM tenant_crypto_policy
             WHERE tenant_id = #{tenantId}
               AND deleted = 0
             LIMIT 1
               FOR UPDATE
            """)
    TenantCryptoPolicy selectTenantPolicyForUpdate(@Param("tenantId") Long tenantId);
}
