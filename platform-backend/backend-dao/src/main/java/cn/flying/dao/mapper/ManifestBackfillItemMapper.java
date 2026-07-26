package cn.flying.dao.mapper;

import cn.flying.dao.entity.ManifestBackfillItem;
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
 * Mapper for deterministic snapshot items and claim-token fenced transitions.
 */
@Mapper
public interface ManifestBackfillItemMapper extends BaseMapper<ManifestBackfillItem> {

    /**
     * Inserts one immutable candidate snapshot while collapsing duplicate discovery.
     *
     * @param item prepared item
     * @return affected rows
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT IGNORE INTO manifest_backfill_item (
                id, run_id, tenant_id, file_id, file_version, owner_user_id,
                status, classification, reason_code, retryable, legacy_download_allowed,
                evidence_digest, evidence_payload, manifest_id, attempt_count,
                next_retry_at, last_error_class, deleted
            ) VALUES (
                #{item.id}, #{item.runId}, #{item.tenantId}, #{item.fileId}, #{item.fileVersion},
                #{item.ownerUserId}, #{item.status}, #{item.classification}, #{item.reasonCode},
                #{item.retryable}, #{item.legacyDownloadAllowed}, #{item.evidenceDigest},
                #{item.evidencePayload}, #{item.manifestId}, #{item.attemptCount},
                #{item.nextRetryAt}, #{item.lastErrorClass}, #{item.deleted}
            )
            """)
    int insertIgnoreSnapshot(@Param("item") ManifestBackfillItem item);

    /**
     * Selects a bounded page of claimable apply items with row locks and skip-locked concurrency.
     *
     * @param runId apply run ID
     * @param tenantId tenant ID
     * @param now claim/retry clock
     * @param maxAttempts maximum attempts
     * @param limit page size
     * @return locked candidates
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, run_id, tenant_id, file_id, file_version, owner_user_id,
                   status, classification, reason_code, retryable, legacy_download_allowed,
                   evidence_digest, evidence_payload, manifest_id, claim_token,
                   lease_expires_at, attempt_count, next_retry_at, last_error_class,
                   create_time, update_time, deleted
            FROM manifest_backfill_item FORCE INDEX (idx_manifest_backfill_item_claim)
            WHERE run_id = #{runId}
              AND tenant_id = #{tenantId}
              AND deleted = 0
              AND classification = 'BACKFILLABLE'
              AND attempt_count < #{maxAttempts}
              AND (
                    status = 'PENDING'
                    OR (status = 'FAILED' AND retryable = 1
                        AND (next_retry_at IS NULL OR next_retry_at <= #{now}))
                    OR (status = 'RUNNING' AND lease_expires_at IS NOT NULL
                        AND lease_expires_at <= #{now})
              )
            ORDER BY file_id ASC, id ASC
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<ManifestBackfillItem> selectClaimableForUpdate(
            @Param("runId") Long runId,
            @Param("tenantId") Long tenantId,
            @Param("now") Date now,
            @Param("maxAttempts") int maxAttempts,
            @Param("limit") int limit);

    /**
     * Claims the locked IDs with one token and lease.
     *
     * @param runId run ID
     * @param tenantId tenant ID
     * @param itemIds locked item IDs
     * @param claimToken random ownership token
     * @param now claim clock
     * @param leaseExpiresAt lease expiry
     * @return affected rows
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update({
            "<script>",
            "UPDATE manifest_backfill_item",
            "SET status = 'RUNNING', claim_token = #{claimToken}, lease_expires_at = #{leaseExpiresAt},",
            "attempt_count = attempt_count + 1, next_retry_at = NULL, last_error_class = NULL, update_time = NOW()",
            "WHERE run_id = #{runId} AND tenant_id = #{tenantId} AND deleted = 0",
            "AND (status = 'PENDING' OR (status = 'FAILED' AND retryable = 1)",
            "OR (status = 'RUNNING' AND lease_expires_at IS NOT NULL AND lease_expires_at &lt;= #{now}))",
            "AND id IN",
            "<foreach collection='itemIds' item='itemId' open='(' separator=',' close=')'>",
            "#{itemId}",
            "</foreach>",
            "</script>"
    })
    int claimSelected(
            @Param("runId") Long runId,
            @Param("tenantId") Long tenantId,
            @Param("itemIds") List<Long> itemIds,
            @Param("claimToken") String claimToken,
            @Param("now") Date now,
            @Param("leaseExpiresAt") Date leaseExpiresAt);

    /**
     * Locks and reloads a claimed item before publication.
     *
     * @param tenantId tenant ID
     * @param runId run ID
     * @param itemId item ID
     * @param claimToken ownership token
     * @return locked item or null
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, run_id, tenant_id, file_id, file_version, owner_user_id,
                   status, classification, reason_code, retryable, legacy_download_allowed,
                   evidence_digest, evidence_payload, manifest_id, claim_token,
                   lease_expires_at, attempt_count, next_retry_at, last_error_class,
                   create_time, update_time, deleted
            FROM manifest_backfill_item
            WHERE tenant_id = #{tenantId}
              AND run_id = #{runId}
              AND id = #{itemId}
              AND claim_token = #{claimToken}
              AND status = 'RUNNING'
              AND deleted = 0
            FOR UPDATE
            """)
    ManifestBackfillItem selectClaimedForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("runId") Long runId,
            @Param("itemId") Long itemId,
            @Param("claimToken") String claimToken);

    /**
     * Completes a claimed item only while its token and unexpired lease remain authoritative.
     *
     * @param tenantId tenant ID
     * @param runId run ID
     * @param itemId item ID
     * @param claimToken ownership token
     * @param status terminal status
     * @param classification terminal classification
     * @param reasonCode stable reason
     * @param manifestId optional manifest ID
     * @param retryable retry flag
     * @param nextRetryAt bounded retry time
     * @param lastErrorClass bounded error class
     * @return affected rows
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE manifest_backfill_item
            SET status = #{status},
                classification = #{classification},
                reason_code = #{reasonCode},
                manifest_id = #{manifestId},
                retryable = #{retryable},
                next_retry_at = #{nextRetryAt},
                last_error_class = #{lastErrorClass},
                claim_token = NULL,
                lease_expires_at = NULL,
                update_time = NOW()
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
            @Param("classification") String classification,
            @Param("reasonCode") String reasonCode,
            @Param("manifestId") Long manifestId,
            @Param("retryable") int retryable,
            @Param("nextRetryAt") Date nextRetryAt,
            @Param("lastErrorClass") String lastErrorClass);

    /**
     * Loads every item in stable order for digest/count finalization or snapshot cloning.
     *
     * @param runId run ID
     * @param tenantId tenant ID
     * @return ordered items
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, run_id, tenant_id, file_id, file_version, owner_user_id,
                   status, classification, reason_code, retryable, legacy_download_allowed,
                   evidence_digest, evidence_payload, manifest_id, claim_token,
                   lease_expires_at, attempt_count, next_retry_at, last_error_class,
                   create_time, update_time, deleted
            FROM manifest_backfill_item
            WHERE run_id = #{runId}
              AND tenant_id = #{tenantId}
              AND deleted = 0
            ORDER BY file_id ASC, file_version ASC, id ASC
            """)
    List<ManifestBackfillItem> selectRunItems(
            @Param("runId") Long runId,
            @Param("tenantId") Long tenantId);

    /**
     * Pages administrator details through an opaque decoded item-ID cursor.
     *
     * @param runId run ID
     * @param tenantId tenant ID
     * @param afterId exclusive item cursor
     * @param status optional status filter
     * @param classification optional classification filter
     * @param reasonCode optional reason filter
     * @param limit page size
     * @return filtered item page
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            <script>
            SELECT id, run_id, tenant_id, file_id, file_version, owner_user_id,
                   status, classification, reason_code, retryable, legacy_download_allowed,
                   evidence_digest, evidence_payload, manifest_id, claim_token,
                   lease_expires_at, attempt_count, next_retry_at, last_error_class,
                   create_time, update_time, deleted
            FROM manifest_backfill_item
            WHERE run_id = #{runId}
              AND tenant_id = #{tenantId}
              AND id > #{afterId}
              AND deleted = 0
            <if test="status != null and status != ''">AND status = #{status}</if>
            <if test="classification != null and classification != ''">AND classification = #{classification}</if>
            <if test="reasonCode != null and reasonCode != ''">AND reason_code = #{reasonCode}</if>
            ORDER BY id ASC
            LIMIT #{limit}
            </script>
            """)
    List<ManifestBackfillItem> selectItemPage(
            @Param("runId") Long runId,
            @Param("tenantId") Long tenantId,
            @Param("afterId") Long afterId,
            @Param("status") String status,
            @Param("classification") String classification,
            @Param("reasonCode") String reasonCode,
            @Param("limit") int limit);

    /**
     * Loads the newest observable governance state for a file version.
     *
     * @param tenantId tenant ID
     * @param fileId file ID
     * @param fileVersion file version
     * @return latest item or null
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT item.*
            FROM manifest_backfill_item item
            INNER JOIN manifest_backfill_run run_record
                    ON run_record.id = item.run_id
                   AND run_record.tenant_id = item.tenant_id
                   AND run_record.deleted = 0
            WHERE item.tenant_id = #{tenantId}
              AND item.file_id = #{fileId}
              AND item.file_version = #{fileVersion}
              AND item.deleted = 0
              AND (run_record.mode <> 'SCAN' OR run_record.status = 'SNAPSHOT_READY')
            ORDER BY item.create_time DESC, item.id DESC
            LIMIT 1
            """)
    ManifestBackfillItem selectLatestForFile(
            @Param("tenantId") Long tenantId,
            @Param("fileId") Long fileId,
            @Param("fileVersion") Integer fileVersion);

    /**
     * Loads the newest governance state when the caller only has a stable file ID.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT item.*
            FROM manifest_backfill_item item
            INNER JOIN manifest_backfill_run run_record
                    ON run_record.id = item.run_id
                   AND run_record.tenant_id = item.tenant_id
                   AND run_record.deleted = 0
            WHERE item.tenant_id = #{tenantId}
              AND item.file_id = #{fileId}
              AND item.deleted = 0
              AND (run_record.mode <> 'SCAN' OR run_record.status = 'SNAPSHOT_READY')
            ORDER BY item.file_version DESC, item.create_time DESC, item.id DESC
            LIMIT 1
            """)
    ManifestBackfillItem selectLatestForFileAnyVersion(
            @Param("tenantId") Long tenantId,
            @Param("fileId") Long fileId);

    /**
     * Requeues a failed item after an explicit administrator decision.
     *
     * @param itemId item ID
     * @param tenantId tenant ID
     * @return affected rows
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE manifest_backfill_item
            SET status = 'PENDING',
                retryable = 1,
                reason_code = 'ADMIN_REQUEUED',
                claim_token = NULL,
                lease_expires_at = NULL,
                attempt_count = 0,
                next_retry_at = NOW(),
                last_error_class = NULL,
                update_time = NOW()
            WHERE id = #{itemId}
              AND tenant_id = #{tenantId}
              AND status = 'FAILED'
              AND retryable = 1
              AND deleted = 0
            """)
    int requeueFailed(@Param("itemId") Long itemId, @Param("tenantId") Long tenantId);
}
