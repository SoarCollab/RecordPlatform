package cn.flying.service.manifest.backfill;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.SnowflakeIdGenerator;
import cn.flying.dao.entity.ManifestReferenceCensus;
import cn.flying.dao.entity.ManifestReferenceSweepMark;
import cn.flying.dao.mapper.ManifestReferenceLedgerMapper;
import cn.flying.dao.mapper.ManifestReferenceSweepMarkMapper;
import cn.flying.dao.mapper.TenantMapper;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.response.StorageObjectHeadVO;
import cn.flying.service.remote.FileRemoteClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implements the independent mark, grace, fresh-census, HEAD, and delete lifecycle.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManifestReferenceSweepService {

    private static final Pattern TENANT_PATH_PATTERN = Pattern.compile(
            "^(?:storage|minio)/tenant/([0-9]+)/(?:(?:chunk/)|(?:node/[^/]+/))(.+)$");
    private static final int MAX_ATTEMPTS = 3;

    private final ManifestReferenceCensusService censusService;
    private final ManifestReferenceLedgerMapper ledgerMapper;
    private final ManifestReferenceSweepMarkMapper markMapper;
    private final ManifestReferenceSweepClaimService claimService;
    private final FileRemoteClient fileRemoteClient;
    private final TenantMapper tenantMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    @Value("${manifest.reference-sweep.mark-enabled:false}")
    private boolean markEnabled;

    @Value("${manifest.reference-sweep.delete-enabled:false}")
    private boolean deleteEnabled;

    @Value("${manifest.reference-sweep.protection-days:30}")
    private int protectionDays;

    @Value("${manifest.reference-sweep.batch-size:20}")
    private int batchSize;

    @Value("${manifest.reference-sweep.lease-seconds:120}")
    private long leaseSeconds;

    /**
     * Marks one exact object only after a fresh completed census and a trustworthy HEAD.
     */
    public ManifestReferenceSweepMark markObject(Long tenantId, String storagePath, String cipherHash) {
        if (!markEnabled) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED,
                    "reference sweep marking is disabled by rollout policy");
        }
        if (!Objects.equals(TenantContext.requireTenantId(), tenantId)
                || !StringUtils.hasText(storagePath) || !StringUtils.hasText(cipherHash)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID);
        }
        Long pathTenantId = parsePathTenant(storagePath);
        if (!Objects.equals(pathTenantId, tenantId)) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED,
                    "cross-tenant object path cannot be marked");
        }

        ManifestReferenceCensus census = censusService.createCensus(tenantId);
        String identityDigest = objectIdentityDigest(pathTenantId, storagePath, cipherHash);
        if (hasReferencesOrUnknownHolds(census, pathTenantId, identityDigest)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR,
                    "object remains referenced or the census contains unknown holds");
        }
        StorageObjectHeadVO head = requireTrustworthyHead(tenantId, storagePath, cipherHash);
        ManifestReferenceSweepMark mark = new ManifestReferenceSweepMark()
                .setId(snowflakeIdGenerator.nextId())
                .setTenantId(tenantId)
                .setPathTenantId(pathTenantId)
                .setStoragePath(storagePath)
                .setCipherHash(cipherHash)
                .setContentLength(head.contentLength())
                .setEtag(head.eTag())
                .setObjectIdentityDigest(identityDigest)
                .setMarkCensusId(census.getId())
                .setStatus("MARKED")
                .setProtectionUntil(Date.from(Instant.now().plusSeconds(Math.max(1, protectionDays) * 86_400L)))
                .setAttemptCount(0)
                .setDeleted(0);
        if (markMapper.insertIgnoreMark(mark) == 1) {
            return mark;
        }
        ManifestReferenceSweepMark existing = markMapper.selectByObjectIdentity(tenantId, identityDigest);
        if (existing == null) {
            throw new IllegalStateException("idempotent sweep mark insert produced no durable row");
        }
        return existing;
    }

    /**
     * Polls active tenants and processes due marks only when delete rollout is enabled.
     */
    @Scheduled(fixedDelayString = "${manifest.reference-sweep.worker-delay-ms:60000}")
    public void sweepDueMarks() {
        if (!deleteEnabled) {
            return;
        }
        List<Long> tenantIds = tenantMapper.selectActiveTenantIds();
        if (tenantIds == null) {
            return;
        }
        for (Long tenantId : tenantIds) {
            TenantContext.runWithTenantIsolation(tenantId, this::sweepTenantBatch);
        }
    }

    /**
     * Claims and processes one tenant batch with no database lock held over remote calls.
     */
    public void sweepTenantBatch() {
        List<ManifestReferenceSweepMark> claims = claimService.claimDue(
                Math.max(1, Math.min(batchSize, 100)), Math.max(30L, leaseSeconds));
        for (ManifestReferenceSweepMark mark : claims) {
            processClaim(mark);
        }
    }

    /**
     * Re-censuses and re-HEADs an object immediately before an idempotent delete request.
     */
    private void processClaim(ManifestReferenceSweepMark mark) {
        Long tenantId = TenantContext.requireTenantId();
        try {
            ManifestReferenceCensus freshCensus = censusService.createCensus(tenantId);
            if (hasReferencesOrUnknownHolds(
                    freshCensus, mark.getPathTenantId(), mark.getObjectIdentityDigest())) {
                complete(mark, "RETAINED", "REFERENCE_REAPPEARED", null, null);
                return;
            }

            Result<StorageObjectHeadVO> headResult = fileRemoteClient.headObject(
                    mark.getStoragePath(), mark.getCipherHash());
            if (headResult == null || !headResult.isSuccess() || headResult.getData() == null) {
                failOrRetain(mark, "HEAD_RPC_TRANSIENT", "StorageHeadUnavailable");
                return;
            }
            StorageObjectHeadVO head = headResult.getData();
            if (!head.exists()) {
                complete(mark, "DELETED", "ALREADY_ABSENT", null, null);
                return;
            }
            if (!headMatchesMark(mark, head)) {
                complete(mark, "RETAINED", "DELETE_HEAD_MISMATCH", null, null);
                return;
            }

            Result<Boolean> deletion = fileRemoteClient.deleteStorageFile(
                    Map.of(mark.getCipherHash(), mark.getStoragePath()));
            if (deletion == null || !deletion.isSuccess() || !Boolean.TRUE.equals(deletion.getData())) {
                failOrRetain(mark, "DELETE_RPC_TRANSIENT", "StorageDeleteUnavailable");
                return;
            }
            complete(mark, "DELETED", "DELETE_CONFIRMED", null, null);
        } catch (RuntimeException failure) {
            failOrRetain(mark, "SWEEP_TRANSIENT", truncate(failure.getClass().getSimpleName()));
            log.warn("Reference sweep claim failed: tenantId={}, markId={}, errorClass={}",
                    tenantId, mark.getId(), failure.getClass().getSimpleName());
        }
    }

    /**
     * Checks exact references plus any census-wide unknown hold.
     */
    private boolean hasReferencesOrUnknownHolds(
            ManifestReferenceCensus census,
            Long pathTenantId,
            String objectIdentityDigest
    ) {
        return ledgerMapper.countExactReferences(census.getId(), pathTenantId, objectIdentityDigest) > 0
                || ledgerMapper.countUnknownHolds(census.getId(), census.getTenantId()) > 0;
    }

    /**
     * Performs mark-time HEAD validation and rejects absent or incomplete metadata.
     */
    private StorageObjectHeadVO requireTrustworthyHead(
            Long tenantId,
            String storagePath,
            String cipherHash
    ) {
        Result<StorageObjectHeadVO> result = fileRemoteClient.headObject(storagePath, cipherHash);
        if (result == null || !result.isSuccess() || result.getData() == null
                || !result.getData().exists()) {
            throw new GeneralException(ResultEnum.FILE_SERVICE_ERROR, "object HEAD is unavailable");
        }
        StorageObjectHeadVO head = result.getData();
        if (!Objects.equals(head.filePath(), storagePath)
                || !Objects.equals(head.fileHash(), cipherHash)
                || !Objects.equals(head.tenantId(), tenantId)
                || !Objects.equals(head.metadataTenantId(), tenantId)
                || !Objects.equals(head.metadataHash(), cipherHash)
                || head.contentLength() == null || head.contentLength() < 0
                || !StringUtils.hasText(head.eTag())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "object HEAD does not match its identity");
        }
        return head;
    }

    /**
     * Compares delete-time object metadata with immutable mark-time evidence.
     */
    private boolean headMatchesMark(ManifestReferenceSweepMark mark, StorageObjectHeadVO head) {
        return Objects.equals(head.filePath(), mark.getStoragePath())
                && Objects.equals(head.fileHash(), mark.getCipherHash())
                && Objects.equals(head.tenantId(), mark.getPathTenantId())
                && Objects.equals(head.metadataTenantId(), mark.getPathTenantId())
                && Objects.equals(head.metadataHash(), mark.getCipherHash())
                && Objects.equals(head.contentLength(), mark.getContentLength())
                && Objects.equals(head.eTag(), mark.getEtag());
    }

    /**
     * Retains exhausted claims; otherwise schedules a bounded retry.
     */
    private void failOrRetain(ManifestReferenceSweepMark mark, String reason, String errorClass) {
        if (mark.getAttemptCount() != null && mark.getAttemptCount() >= MAX_ATTEMPTS) {
            complete(mark, "RETAINED", "RETRY_EXHAUSTED", errorClass, null);
            return;
        }
        long delaySeconds = 30L << Math.max(0, (mark.getAttemptCount() == null ? 1 : mark.getAttemptCount()) - 1);
        complete(mark, "FAILED", reason, errorClass,
                Date.from(Instant.now().plusSeconds(Math.min(delaySeconds, 300L))));
    }

    /**
     * Applies a lease-token CAS terminal or retry transition.
     */
    private void complete(
            ManifestReferenceSweepMark mark,
            String status,
            String reason,
            String errorClass,
            Date nextRetryAt
    ) {
        int changed = markMapper.completeClaim(mark.getId(), mark.getTenantId(), mark.getClaimToken(),
                status, reason, truncate(errorClass), nextRetryAt);
        if (changed != 1) {
            log.warn("Reference sweep completion lost lease: tenantId={}, markId={}, status={}",
                    mark.getTenantId(), mark.getId(), status);
        }
    }

    /**
     * Extracts the tenant identity encoded by an accepted storage path.
     */
    private Long parsePathTenant(String storagePath) {
        Matcher matcher = TENANT_PATH_PATTERN.matcher(Objects.toString(storagePath, ""));
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Long.valueOf(matcher.group(1));
        } catch (NumberFormatException invalidTenant) {
            return null;
        }
    }

    /**
     * Calculates the exact logical object identity used by census SQL.
     */
    private String objectIdentityDigest(Long pathTenantId, String storagePath, String cipherHash) {
        return sha256(String.join("\n", String.valueOf(pathTenantId), storagePath, cipherHash));
    }

    /**
     * Calculates a lowercase SHA-256 digest.
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
     * Bounds failure classes to the persisted schema.
     */
    private String truncate(String value) {
        return value != null && value.length() > 128 ? value.substring(0, 128) : value;
    }
}
