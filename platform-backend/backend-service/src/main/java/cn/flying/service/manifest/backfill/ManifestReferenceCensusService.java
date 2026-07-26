package cn.flying.service.manifest.backfill;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.SnowflakeIdGenerator;
import cn.flying.dao.entity.ManifestReferenceCensus;
import cn.flying.dao.entity.ManifestReferenceLedger;
import cn.flying.dao.mapper.ManifestReferenceCensusMapper;
import cn.flying.dao.mapper.ManifestReferenceLedgerMapper;
import cn.flying.platformapi.constant.Result;
import cn.flying.service.remote.FileRemoteClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Builds a versioned, tenant-scoped reference census before any object can enter sweep grace.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManifestReferenceCensusService {

    private final ManifestReferenceCensusMapper censusMapper;
    private final ManifestReferenceLedgerMapper ledgerMapper;
    private final FileRemoteClient fileRemoteClient;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final TransactionTemplate transactionTemplate;

    /**
     * Materializes every database reference source and a conservative degraded-write hold.
     *
     * @param tenantId authenticated tenant ID
     * @return completed census boundary
     */
    public ManifestReferenceCensus createCensus(Long tenantId) {
        if (tenantId == null || !Objects.equals(TenantContext.requireTenantId(), tenantId)) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
        }
        ManifestReferenceCensus census = new ManifestReferenceCensus()
                .setId(snowflakeIdGenerator.nextId())
                .setTenantId(tenantId)
                .setStatus("RUNNING")
                .setKnownReferenceCount(0L)
                .setUnknownHoldCount(0L)
                .setDeleted(0);
        censusMapper.insert(census);

        try {
            Long degradedWriteCount = requireDegradedWriteCount();
            ManifestReferenceCensus completed = transactionTemplate.execute(status ->
                    materializeCensus(census, degradedWriteCount));
            if (completed == null) {
                throw new IllegalStateException("reference census transaction returned no result");
            }
            return completed;
        } catch (RuntimeException failure) {
            ManifestReferenceCensus failed = new ManifestReferenceCensus()
                    .setId(census.getId())
                    .setStatus("FAILED")
                    .setLastErrorClass(truncate(failure.getClass().getSimpleName()));
            censusMapper.updateById(failed);
            log.error("Reference census failed: tenantId={}, censusId={}, errorClass={}",
                    tenantId, census.getId(), failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /**
     * Returns the newest completed tenant census, or fails closed when none exists.
     */
    public ManifestReferenceCensus requireLatestCompleted(Long tenantId) {
        if (tenantId == null || !Objects.equals(TenantContext.requireTenantId(), tenantId)) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
        }
        ManifestReferenceCensus census = censusMapper.selectLatestCompleted(tenantId);
        if (census == null) {
            throw new GeneralException(ResultEnum.RESULT_DATA_NONE, "reference census is required");
        }
        return census;
    }

    /**
     * Populates a repeatable transaction snapshot and seals it with a deterministic digest.
     */
    private ManifestReferenceCensus materializeCensus(
            ManifestReferenceCensus census,
            long degradedWriteCount
    ) {
        Long censusId = census.getId();
        Long tenantId = census.getTenantId();
        ledgerMapper.insertManifestReferences(censusId, tenantId);
        ledgerMapper.insertFileVersionReferences(censusId, tenantId);
        ledgerMapper.insertShareReferences(censusId, tenantId);
        ledgerMapper.insertAttestationReferences(censusId, tenantId);
        ledgerMapper.insertAttestationCandidateReferences(censusId, tenantId);
        ledgerMapper.insertLegacyAttestationUnknownHolds(censusId, tenantId);
        ledgerMapper.insertProofReferences(censusId, tenantId);
        ledgerMapper.insertLegacyUnknownHolds(censusId, tenantId);
        ledgerMapper.insertSagaUnknownHolds(censusId, tenantId);
        ledgerMapper.insertFinalizationUnknownHolds(censusId, tenantId);
        if (degradedWriteCount > 0) {
            insertDegradedUnknownHold(censusId, tenantId, degradedWriteCount);
        }

        List<ManifestReferenceLedger> rows = ledgerMapper.selectCensusRows(censusId, tenantId);
        long knownCount = rows.stream().filter(row -> Integer.valueOf(1).equals(row.getKnownReference())).count();
        long unknownCount = rows.size() - knownCount;
        StringBuilder digestInput = new StringBuilder("manifest-reference-census.v1");
        rows.forEach(row -> digestInput.append('\n').append(digestLine(row)));
        ManifestReferenceCensus completed = new ManifestReferenceCensus()
                .setId(censusId)
                .setStatus("COMPLETED")
                .setCensusDigest("sha256:" + sha256(digestInput.toString()))
                .setKnownReferenceCount(knownCount)
                .setUnknownHoldCount(unknownCount)
                .setLastErrorClass(null)
                .setCompletedAt(new Date());
        censusMapper.updateById(completed);
        return censusMapper.selectById(censusId);
    }

    /**
     * Inserts one census-wide unknown hold when storage still owns degraded repair state.
     */
    private void insertDegradedUnknownHold(Long censusId, Long tenantId, long degradedWriteCount) {
        String sourceId = "pending-count:" + degradedWriteCount;
        ledgerMapper.insert(new ManifestReferenceLedger()
                .setCensusId(censusId)
                .setTenantId(tenantId)
                .setObjectIdentityDigest(sha256("UNKNOWN:DEGRADED:" + tenantId + ":" + sourceId))
                .setSourceType("DEGRADED")
                .setSourceId(sourceId)
                .setSourceKeyDigest(sha256("DEGRADED:" + sourceId))
                .setHoldReason("DEGRADED_REPAIR_PENDING")
                .setKnownReference(0)
                .setObservedAt(new Date())
                .setDeleted(0));
    }

    /**
     * Reads storage repair state and fails closed on an unavailable or malformed response.
     */
    private Long requireDegradedWriteCount() {
        Result<Long> result = fileRemoteClient.getDegradedWriteCount();
        if (result == null || !result.isSuccess() || result.getData() == null || result.getData() < 0) {
            throw new IllegalStateException("degraded write state is unavailable");
        }
        return result.getData();
    }

    /**
     * Encodes one stable ordered digest line without timestamps or auto-generated IDs.
     */
    private String digestLine(ManifestReferenceLedger row) {
        return String.join("\n",
                nullSafe(row.getObjectIdentityDigest()),
                nullSafe(row.getSourceType()),
                nullSafe(row.getSourceKeyDigest()),
                nullSafe(row.getHoldReason()),
                String.valueOf(row.getKnownReference()));
    }

    /**
     * Calculates a lowercase SHA-256 digest without a scheme prefix.
     */
    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * Normalizes nullable digest fields.
     */
    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /**
     * Bounds persisted failure classes to the migration schema.
     */
    private String truncate(String value) {
        return value != null && value.length() > 128 ? value.substring(0, 128) : value;
    }
}
