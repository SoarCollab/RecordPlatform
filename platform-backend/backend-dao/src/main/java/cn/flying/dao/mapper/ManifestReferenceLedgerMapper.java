package cn.flying.dao.mapper;

import cn.flying.dao.entity.ManifestReferenceLedger;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper that materializes known reference sources and conservative unknown holds.
 */
@Mapper
public interface ManifestReferenceLedgerMapper extends BaseMapper<ManifestReferenceLedger> {

    /**
     * Materializes active and historical manifest item references.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT IGNORE INTO manifest_reference_ledger (
                census_id, tenant_id, path_tenant_id, storage_path, cipher_hash,
                object_identity_digest, source_type, source_id, source_key_digest,
                hold_reason, known_reference, observed_at, deleted
            )
            SELECT #{censusId}, manifest.tenant_id, manifest.tenant_id,
                   item.storage_path, item.cipher_hash,
                   SHA2(CONCAT_WS(CHAR(10), manifest.tenant_id, item.storage_path, item.cipher_hash), 256),
                   'MANIFEST', CAST(manifest.id AS CHAR),
                   SHA2(CONCAT('MANIFEST:', manifest.id, ':', item.chunk_index), 256),
                   CASE WHEN manifest.status = 'ACTIVE' AND manifest.deleted = 0
                        THEN 'ACTIVE_MANIFEST' ELSE 'HISTORICAL_MANIFEST' END,
                   1, NOW(), 0
            FROM file_chunk_manifest manifest
            INNER JOIN file_chunk_manifest_item item
                    ON item.manifest_id = manifest.id
                   AND item.tenant_id = manifest.tenant_id
                   AND item.deleted = 0
            WHERE manifest.tenant_id = #{tenantId}
            """)
    int insertManifestReferences(
            @Param("censusId") Long censusId,
            @Param("tenantId") Long tenantId);

    /**
     * Materializes file-version ownership of manifest objects as a separate reference source.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT IGNORE INTO manifest_reference_ledger (
                census_id, tenant_id, path_tenant_id, storage_path, cipher_hash,
                object_identity_digest, source_type, source_id, source_key_digest,
                hold_reason, known_reference, observed_at, deleted
            )
            SELECT #{censusId}, file_record.tenant_id, manifest.tenant_id,
                   item.storage_path, item.cipher_hash,
                   SHA2(CONCAT_WS(CHAR(10), manifest.tenant_id, item.storage_path, item.cipher_hash), 256),
                   'FILE_VERSION', CAST(file_record.id AS CHAR),
                   SHA2(CONCAT('FILE_VERSION:', file_record.id, ':', item.chunk_index), 256),
                   'FILE_VERSION_REFERENCE', 1, NOW(), 0
            FROM file file_record
            INNER JOIN file_chunk_manifest manifest
                    ON manifest.tenant_id = file_record.tenant_id
                   AND manifest.file_id = file_record.id
            INNER JOIN file_chunk_manifest_item item
                    ON item.manifest_id = manifest.id
                   AND item.tenant_id = manifest.tenant_id
                   AND item.deleted = 0
            WHERE file_record.tenant_id = #{tenantId}
            """)
    int insertFileVersionReferences(
            @Param("censusId") Long censusId,
            @Param("tenantId") Long tenantId);

    /**
     * Materializes provenance/share holds against source-file manifest objects.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT IGNORE INTO manifest_reference_ledger (
                census_id, tenant_id, path_tenant_id, storage_path, cipher_hash,
                object_identity_digest, source_type, source_id, source_key_digest,
                hold_reason, known_reference, observed_at, deleted
            )
            SELECT #{censusId}, #{tenantId}, manifest.tenant_id,
                   item.storage_path, item.cipher_hash,
                   SHA2(CONCAT_WS(CHAR(10), manifest.tenant_id, item.storage_path, item.cipher_hash), 256),
                   'SHARE', CAST(source.id AS CHAR),
                   SHA2(CONCAT('SHARE:', source.id, ':', item.chunk_index), 256),
                   'SHARE_SOURCE_REFERENCE', 1, NOW(), 0
            FROM file_source source
            INNER JOIN file_chunk_manifest manifest
                    ON manifest.file_id IN (source.origin_file_id, source.source_file_id)
                   AND manifest.tenant_id = #{tenantId}
                   AND manifest.status = 'ACTIVE'
                   AND manifest.deleted = 0
            INNER JOIN file_chunk_manifest_item item
                    ON item.manifest_id = manifest.id
                   AND item.tenant_id = manifest.tenant_id
                   AND item.deleted = 0
            WHERE source.deleted = 0
            """)
    int insertShareReferences(
            @Param("censusId") Long censusId,
            @Param("tenantId") Long tenantId);

    /**
     * Materializes attestation-leaf bindings to manifest objects.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT IGNORE INTO manifest_reference_ledger (
                census_id, tenant_id, path_tenant_id, storage_path, cipher_hash,
                object_identity_digest, source_type, source_id, source_key_digest,
                hold_reason, known_reference, observed_at, deleted
            )
            SELECT #{censusId}, leaf.tenant_id, manifest.tenant_id,
                   item.storage_path, item.cipher_hash,
                   SHA2(CONCAT_WS(CHAR(10), manifest.tenant_id, item.storage_path, item.cipher_hash), 256),
                   'ATTESTATION', CAST(leaf.id AS CHAR),
                   SHA2(CONCAT('ATTESTATION:', leaf.id, ':', item.chunk_index), 256),
                   'ATTESTATION_REFERENCE', 1, NOW(), 0
            FROM attestation_leaf leaf
            INNER JOIN file_chunk_manifest manifest
                    ON manifest.id = leaf.manifest_id
                   AND manifest.tenant_id = leaf.tenant_id
            INNER JOIN file_chunk_manifest_item item
                    ON item.manifest_id = manifest.id
                   AND item.tenant_id = manifest.tenant_id
                   AND item.deleted = 0
            WHERE leaf.tenant_id = #{tenantId}
              AND leaf.deleted = 0
            """)
    int insertAttestationReferences(
            @Param("censusId") Long censusId,
            @Param("tenantId") Long tenantId);

    /**
     * Materializes production attestation candidates that retain their bound manifest objects.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT IGNORE INTO manifest_reference_ledger (
                census_id, tenant_id, path_tenant_id, storage_path, cipher_hash,
                object_identity_digest, source_type, source_id, source_key_digest,
                hold_reason, known_reference, observed_at, deleted
            )
            SELECT #{censusId}, candidate.tenant_id, manifest.tenant_id,
                   item.storage_path, item.cipher_hash,
                   SHA2(CONCAT_WS(CHAR(10), manifest.tenant_id, item.storage_path, item.cipher_hash), 256),
                   'ATTESTATION_CANDIDATE', CAST(candidate.id AS CHAR),
                   SHA2(CONCAT('ATTESTATION_CANDIDATE:', candidate.id, ':', item.chunk_index), 256),
                   'PRODUCTION_CANDIDATE_REFERENCE', 1, NOW(), 0
            FROM attestation_batch_candidate candidate
            INNER JOIN file_chunk_manifest manifest
                    ON manifest.id = candidate.manifest_id
                   AND manifest.tenant_id = candidate.tenant_id
            INNER JOIN file_chunk_manifest_item item
                    ON item.manifest_id = manifest.id
                   AND item.tenant_id = manifest.tenant_id
                   AND item.deleted = 0
            WHERE candidate.tenant_id = #{tenantId}
              AND candidate.deleted = 0
            """)
    int insertAttestationCandidateReferences(
            @Param("censusId") Long censusId,
            @Param("tenantId") Long tenantId);

    /**
     * Adds a conservative hold for legacy attestation leaves without manifest-bound evidence.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT IGNORE INTO manifest_reference_ledger (
                census_id, tenant_id, path_tenant_id, storage_path, cipher_hash,
                object_identity_digest, source_type, source_id, source_key_digest,
                hold_reason, known_reference, observed_at, deleted
            )
            SELECT #{censusId}, leaf.tenant_id, NULL, NULL, NULL,
                   SHA2(CONCAT('UNKNOWN:ATTESTATION:', leaf.tenant_id, ':', leaf.id), 256),
                   'ATTESTATION_LEGACY', CAST(leaf.id AS CHAR),
                   SHA2(CONCAT('ATTESTATION_LEGACY:', leaf.id), 256),
                   'LEGACY_ATTESTATION_REFERENCE', 0, NOW(), 0
            FROM attestation_leaf leaf
            WHERE leaf.tenant_id = #{tenantId}
              AND leaf.deleted = 0
              AND (leaf.manifest_id IS NULL OR leaf.evidence_type <> 'MANIFEST_HASH')
            """)
    int insertLegacyAttestationUnknownHolds(
            @Param("censusId") Long censusId,
            @Param("tenantId") Long tenantId);

    /**
     * Materializes signed-proof bindings through the exact issued file version's manifest.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT IGNORE INTO manifest_reference_ledger (
                census_id, tenant_id, path_tenant_id, storage_path, cipher_hash,
                object_identity_digest, source_type, source_id, source_key_digest,
                hold_reason, known_reference, observed_at, deleted
            )
            SELECT #{censusId}, proof.tenant_id, manifest.tenant_id,
                   item.storage_path, item.cipher_hash,
                   SHA2(CONCAT_WS(CHAR(10), manifest.tenant_id, item.storage_path, item.cipher_hash), 256),
                   'PROOF', CAST(proof.id AS CHAR),
                   SHA2(CONCAT('PROOF:', proof.id, ':', item.chunk_index), 256),
                   'SIGNED_PROOF_REFERENCE', 1, NOW(), 0
            FROM proof_bundle_issuance proof
            INNER JOIN file_chunk_manifest manifest
                    ON manifest.tenant_id = proof.tenant_id
                   AND manifest.file_id = proof.file_id
                   AND manifest.file_version = proof.file_version
            INNER JOIN file_chunk_manifest_item item
                    ON item.manifest_id = manifest.id
                   AND item.tenant_id = manifest.tenant_id
                   AND item.deleted = 0
            WHERE proof.tenant_id = #{tenantId}
              AND proof.deleted = 0
            """)
    int insertProofReferences(
            @Param("censusId") Long censusId,
            @Param("tenantId") Long tenantId);

    /**
     * Adds a conservative hold for every successful legacy file whose pointer is not manifested.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT IGNORE INTO manifest_reference_ledger (
                census_id, tenant_id, path_tenant_id, storage_path, cipher_hash,
                object_identity_digest, source_type, source_id, source_key_digest,
                hold_reason, known_reference, observed_at, deleted
            )
            SELECT #{censusId}, file_record.tenant_id, NULL, NULL, NULL,
                   SHA2(CONCAT('UNKNOWN:LEGACY:', file_record.tenant_id, ':', file_record.id), 256),
                   'LEGACY', CAST(file_record.id AS CHAR),
                   SHA2(CONCAT('LEGACY:', file_record.id), 256),
                   'UNKNOWN_REFERENCE', 0, NOW(), 0
            FROM file file_record
            WHERE file_record.tenant_id = #{tenantId}
              AND file_record.status = 1
              AND file_record.deleted = 0
              AND NOT EXISTS (
                    SELECT 1
                    FROM file_chunk_manifest manifest
                    WHERE manifest.tenant_id = file_record.tenant_id
                      AND manifest.file_id = file_record.id
                      AND manifest.status = 'ACTIVE'
                      AND manifest.deleted = 0
              )
            """)
    int insertLegacyUnknownHolds(
            @Param("censusId") Long censusId,
            @Param("tenantId") Long tenantId);

    /**
     * Adds conservative holds for unfinished Saga/finalization state.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT IGNORE INTO manifest_reference_ledger (
                census_id, tenant_id, path_tenant_id, storage_path, cipher_hash,
                object_identity_digest, source_type, source_id, source_key_digest,
                hold_reason, known_reference, observed_at, deleted
            )
            SELECT #{censusId}, saga.tenant_id, NULL, NULL, NULL,
                   SHA2(CONCAT('UNKNOWN:SAGA:', saga.tenant_id, ':', saga.id), 256),
                   'SAGA', CAST(saga.id AS CHAR), SHA2(CONCAT('SAGA:', saga.id), 256),
                   'UNFINISHED_FINALIZATION', 0, NOW(), 0
            FROM file_saga saga
            WHERE saga.tenant_id = #{tenantId}
              AND saga.status NOT IN ('COMPLETED', 'COMPENSATED')
            """)
    int insertSagaUnknownHolds(
            @Param("censusId") Long censusId,
            @Param("tenantId") Long tenantId);

    /**
     * Adds a conservative hold for file-level direct-finalization checkpoints not represented by Saga rows.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT IGNORE INTO manifest_reference_ledger (
                census_id, tenant_id, path_tenant_id, storage_path, cipher_hash,
                object_identity_digest, source_type, source_id, source_key_digest,
                hold_reason, known_reference, observed_at, deleted
            )
            SELECT #{censusId}, file_record.tenant_id, NULL, NULL, NULL,
                   SHA2(CONCAT('UNKNOWN:FINALIZATION:', file_record.tenant_id, ':', file_record.id), 256),
                   'FINALIZATION', CAST(file_record.id AS CHAR),
                   SHA2(CONCAT('FINALIZATION:', file_record.id), 256),
                   'UNFINISHED_FINALIZATION', 0, NOW(), 0
            FROM file file_record
            WHERE file_record.tenant_id = #{tenantId}
              AND file_record.deleted = 0
              AND JSON_VALID(file_record.file_param)
              AND JSON_UNQUOTE(JSON_EXTRACT(
                    IF(JSON_VALID(file_record.file_param), file_record.file_param, NULL),
                    '$._finalizationClaim.phase'
                  )) IN ('CLAIMED', 'CHAIN_ATTESTING', 'CHAIN_ATTESTED')
            """)
    int insertFinalizationUnknownHolds(
            @Param("censusId") Long censusId,
            @Param("tenantId") Long tenantId);

    /**
     * Loads ordered rows for deterministic census digest calculation.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, census_id, tenant_id, path_tenant_id, storage_path, cipher_hash,
                   object_identity_digest, source_type, source_id, source_key_digest,
                   hold_reason, known_reference, observed_at, create_time, update_time, deleted
            FROM manifest_reference_ledger
            WHERE census_id = #{censusId}
              AND tenant_id = #{tenantId}
              AND deleted = 0
            ORDER BY object_identity_digest ASC, source_type ASC, source_key_digest ASC, id ASC
            """)
    List<ManifestReferenceLedger> selectCensusRows(
            @Param("censusId") Long censusId,
            @Param("tenantId") Long tenantId);

    /**
     * Counts exact object references in one completed census.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT COUNT(*)
            FROM manifest_reference_ledger
            WHERE census_id = #{censusId}
              AND path_tenant_id = #{pathTenantId}
              AND object_identity_digest = #{objectIdentityDigest}
              AND known_reference = 1
              AND deleted = 0
            """)
    long countExactReferences(
            @Param("censusId") Long censusId,
            @Param("pathTenantId") Long pathTenantId,
            @Param("objectIdentityDigest") String objectIdentityDigest);

    /**
     * Counts any conservative unknown-reference holds in a census.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT COUNT(*)
            FROM manifest_reference_ledger
            WHERE census_id = #{censusId}
              AND tenant_id = #{tenantId}
              AND known_reference = 0
              AND deleted = 0
            """)
    long countUnknownHolds(@Param("censusId") Long censusId, @Param("tenantId") Long tenantId);
}
