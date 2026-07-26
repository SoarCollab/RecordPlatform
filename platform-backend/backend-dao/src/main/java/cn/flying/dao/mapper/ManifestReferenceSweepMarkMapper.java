package cn.flying.dao.mapper;

import cn.flying.dao.entity.ManifestReferenceSweepMark;
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
 * Mapper for mark/grace/lease/delete sweep fencing.
 */
@Mapper
public interface ManifestReferenceSweepMarkMapper extends BaseMapper<ManifestReferenceSweepMark> {

    /**
     * Idempotently creates one sweep mark for an exact object identity.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT IGNORE INTO manifest_reference_sweep_mark (
                id, tenant_id, path_tenant_id, storage_path, cipher_hash, content_length, etag,
                object_identity_digest, mark_census_id, status, protection_until,
                attempt_count, deleted
            ) VALUES (
                #{mark.id}, #{mark.tenantId}, #{mark.pathTenantId}, #{mark.storagePath},
                #{mark.cipherHash}, #{mark.contentLength}, #{mark.etag},
                #{mark.objectIdentityDigest}, #{mark.markCensusId},
                #{mark.status}, #{mark.protectionUntil}, #{mark.attemptCount}, #{mark.deleted}
            )
            """)
    int insertIgnoreMark(@Param("mark") ManifestReferenceSweepMark mark);

    /**
     * Loads the existing idempotent lifecycle for one tenant/object identity.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, tenant_id, path_tenant_id, storage_path, cipher_hash, content_length, etag,
                   object_identity_digest, mark_census_id, status, protection_until,
                   claim_token, lease_expires_at, attempt_count, next_retry_at,
                   reason_code, last_error_class, deleted_at, create_time, update_time, deleted
            FROM manifest_reference_sweep_mark
            WHERE tenant_id = #{tenantId}
              AND object_identity_digest = #{objectIdentityDigest}
              AND deleted = 0
            LIMIT 1
            """)
    ManifestReferenceSweepMark selectByObjectIdentity(
            @Param("tenantId") Long tenantId,
            @Param("objectIdentityDigest") String objectIdentityDigest);

    /**
     * Locks due marks while skipping another sweep worker's row locks.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, tenant_id, path_tenant_id, storage_path, cipher_hash, content_length, etag,
                   object_identity_digest, mark_census_id, status, protection_until,
                   claim_token, lease_expires_at, attempt_count, next_retry_at,
                   reason_code, last_error_class, deleted_at, create_time, update_time, deleted
            FROM manifest_reference_sweep_mark
            WHERE tenant_id = #{tenantId}
              AND deleted = 0
              AND protection_until <= #{now}
              AND (
                    status = 'MARKED'
                    OR (status = 'FAILED' AND (next_retry_at IS NULL OR next_retry_at <= #{now}))
                    OR (status = 'DELETING' AND lease_expires_at IS NOT NULL AND lease_expires_at <= #{now})
              )
            ORDER BY protection_until ASC, id ASC
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<ManifestReferenceSweepMark> selectDueForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("now") Date now,
            @Param("limit") int limit);

    /**
     * Claims one due sweep mark with a delete lease.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE manifest_reference_sweep_mark
            SET status = 'DELETING',
                claim_token = #{claimToken},
                lease_expires_at = #{leaseExpiresAt},
                attempt_count = attempt_count + 1,
                reason_code = NULL,
                last_error_class = NULL,
                update_time = NOW()
            WHERE id = #{markId}
              AND tenant_id = #{tenantId}
              AND deleted = 0
              AND (
                    status = 'MARKED'
                    OR status = 'FAILED'
                    OR (status = 'DELETING' AND lease_expires_at <= #{now})
              )
            """)
    int claimMark(
            @Param("markId") Long markId,
            @Param("tenantId") Long tenantId,
            @Param("claimToken") String claimToken,
            @Param("now") Date now,
            @Param("leaseExpiresAt") Date leaseExpiresAt);

    /**
     * Completes or retains a sweep mark only while the claim token remains authoritative.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE manifest_reference_sweep_mark
            SET status = #{status},
                reason_code = #{reasonCode},
                last_error_class = #{lastErrorClass},
                next_retry_at = #{nextRetryAt},
                deleted_at = CASE WHEN #{status} = 'DELETED' THEN NOW() ELSE deleted_at END,
                claim_token = NULL,
                lease_expires_at = NULL,
                update_time = NOW()
            WHERE id = #{markId}
              AND tenant_id = #{tenantId}
              AND claim_token = #{claimToken}
              AND status = 'DELETING'
              AND lease_expires_at > NOW()
              AND deleted = 0
            """)
    int completeClaim(
            @Param("markId") Long markId,
            @Param("tenantId") Long tenantId,
            @Param("claimToken") String claimToken,
            @Param("status") String status,
            @Param("reasonCode") String reasonCode,
            @Param("lastErrorClass") String lastErrorClass,
            @Param("nextRetryAt") Date nextRetryAt);
}
