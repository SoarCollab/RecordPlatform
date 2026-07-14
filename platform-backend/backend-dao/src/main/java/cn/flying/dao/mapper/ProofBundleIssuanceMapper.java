package cn.flying.dao.mapper;

import cn.flying.dao.entity.ProofBundleIssuance;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Mapper for signed proof issuance snapshots and public status lookups.
 */
@Mapper
public interface ProofBundleIssuanceMapper extends BaseMapper<ProofBundleIssuance> {

    /**
     * 在当前事务中按 tenant/leaf 锁定并读取唯一签发记录，避免 RR 旧快照参与状态决策。
     *
     * @param tenantId 租户ID
     * @param leafId 证明叶子ID
     * @return 当前签发记录
     */
    @Select("""
            SELECT id, tenant_id, proof_id, file_id, file_version, leaf_id,
                   manifest_hash, manifest_json, signature_jws, signature_algorithm,
                   key_id, key_version, public_key_spki, public_key_fingerprint,
                   issued_status, status, status_version, status_reason,
                   issued_at, revoked_at, create_time, update_time, deleted
              FROM proof_bundle_issuance
             WHERE tenant_id = #{tenantId}
               AND leaf_id = #{leafId}
               AND deleted = 0
             LIMIT 1
               FOR UPDATE
            """)
    ProofBundleIssuance selectByLeafForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("leafId") Long leafId);

    /**
     * 按格式受限的公开 proofId 跨租户读取状态；调用方不得接受其他查询条件。
     *
     * @param proofId 公开证明标识
     * @return 签发记录
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT proof_id, key_id, key_version,
                   issued_status, status, status_version, status_reason,
                   issued_at, revoked_at, create_time, update_time, deleted
              FROM proof_bundle_issuance
             WHERE proof_id = #{proofId}
               AND deleted = 0
             LIMIT 1
            """)
    ProofBundleIssuance selectPublicByProofId(@Param("proofId") String proofId);

}
