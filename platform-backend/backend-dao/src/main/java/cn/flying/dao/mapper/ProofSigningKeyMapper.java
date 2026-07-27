package cn.flying.dao.mapper;

import cn.flying.dao.entity.ProofSigningKeyRecord;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 全局 proof signing key 注册表；所有 SQL 都显式绕过 tenant line，因为表本身不含 tenant_id。
 */
@Mapper
public interface ProofSigningKeyMapper extends BaseMapper<ProofSigningKeyRecord> {

    /**
     * 原子注册一个全局唯一 key id/version；冲突时不覆盖任何已有公钥材料。
     *
     * @param key 待注册公开 key
     * @return 插入行数
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT INTO proof_signing_key(
                id, key_id, key_version, signing_provider, signing_provider_contract,
                signature_suite, proof_suite, signature_algorithm,
                public_key_spki, public_key_fingerprint, status,
                first_seen_at, deleted
            ) VALUES (
                #{key.id}, #{key.keyId}, #{key.keyVersion},
                #{key.signingProvider}, #{key.signingProviderContract},
                #{key.signatureSuite}, #{key.proofSuite}, #{key.signatureAlgorithm},
                #{key.publicKeySpki}, #{key.publicKeyFingerprint}, #{key.status},
                #{key.firstSeenAt}, 0
            )
            ON DUPLICATE KEY UPDATE key_id = key_id
            """)
    int registerKey(@Param("key") ProofSigningKeyRecord key);

    /**
     * 按公开 key id/version 读取唯一注册记录。
     *
     * @param keyId key id
     * @param keyVersion key version
     * @return 注册记录
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, key_id, key_version, signing_provider, signing_provider_contract,
                   signature_suite, proof_suite, signature_algorithm,
                   public_key_spki, public_key_fingerprint, status,
                   first_seen_at, create_time, update_time, deleted
              FROM proof_signing_key
             WHERE key_id = #{keyId}
               AND key_version = #{keyVersion}
               AND deleted = 0
             LIMIT 1
            """)
    ProofSigningKeyRecord selectByIdentity(
            @Param("keyId") String keyId,
            @Param("keyVersion") Integer keyVersion);

    /**
     * 使用当前读锁定全局 key 身份，避免 RR 快照在并发首次注册后仍返回空记录。
     *
     * @param keyId key id
     * @param keyVersion key version
     * @return 当前注册记录
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, key_id, key_version, signing_provider, signing_provider_contract,
                   signature_suite, proof_suite, signature_algorithm,
                   public_key_spki, public_key_fingerprint, status,
                   first_seen_at, create_time, update_time, deleted
              FROM proof_signing_key
             WHERE key_id = #{keyId}
               AND key_version = #{keyVersion}
               AND deleted = 0
             LIMIT 1
               FOR UPDATE
            """)
    ProofSigningKeyRecord selectByIdentityForUpdate(
            @Param("keyId") String keyId,
            @Param("keyVersion") Integer keyVersion);
}
